package com.modrith.orchestrator

import com.modrith.installer.InstallEngine
import com.modrith.installer.InstallFailureCode
import com.modrith.installer.InstallProgressPhase
import com.modrith.installer.InstallProgressListener
import com.modrith.installer.InstallSessionStatus
import com.modrith.launcher.LauncherError
import com.modrith.launcher.LauncherLoader
import com.modrith.launcher.LauncherProvider
import com.modrith.launcher.LauncherResult
import com.modrith.models.DownloadEngine
import com.modrith.models.DownloadProgressListener
import com.modrith.models.DownloadSessionStatus
import com.modrith.models.LoaderType
import com.modrith.models.MrPackParseResult
import com.modrith.models.ResolvedInstallPlan
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal interface PipelineObserver {
    val cancellationRequested: Boolean

    fun registerCancellation(action: (() -> Unit)?)

    fun update(
        progress: InstallProgress,
        checkpoint: OrchestratorCheckpoint,
        error: InstallError? = null,
    )
}

class InstallPipeline(
    private val parser: OrchestratorParser,
    private val resolver: OrchestratorResolver,
    private val downloadEngine: DownloadEngine,
    private val installEngine: InstallEngine,
    private val launcherProvider: LauncherProvider,
    private val launcherTargetProvider: LauncherTargetProvider,
    private val sourceResolver: MrPackSourceResolver,
    private val checkpoints: OrchestratorCheckpointRepository,
    private val logger: InstallLogger,
) {
    internal suspend fun execute(
        initial: OrchestratorCheckpoint,
        observer: PipelineObserver,
    ): InstallResult {
        var checkpoint = initial
        val resumeInstallerTransaction =
            initial.phase == CheckpointPhase.INSTALLING &&
                initial.installerTransactionId != null
        val progress = InstallProgressAggregator()
        var parsedSuccessfully = checkpoint.phase.ordinal >= CheckpointPhase.PARSED.ordinal
        try {
            if (checkpoint.phase == CheckpointPhase.COMPLETED) {
                return InstallResult.Success(
                    sessionId = checkpoint.sessionId,
                    transactionId = requireNotNull(checkpoint.installerTransactionId),
                    installedFiles = checkpoint.installedFiles,
                    installedBytes = checkpoint.installedBytes,
                    logs = logger.entries(checkpoint.sessionId).value,
                    checkpoint = checkpoint,
                )
            }
            currentCoroutineContext().ensureActive()
            logger.log(
                checkpoint.sessionId,
                InstallLogSource.ORCHESTRATOR,
                InstallLogLevel.INFO,
                "install.pipeline_started",
                mapOf("checkpointPhase" to checkpoint.phase),
            )

            var archive = checkpoint.archivePath
            if (archive == null && checkpoint.parsedPlan == null) {
                observer.update(
                    progress.phase(
                        InstallPhase.PREPARING_SOURCE,
                        "Preparing MRPack source.",
                    ),
                    checkpoint,
                )
                val source = when (
                    val result = sourceResolver.resolve(
                        checkpoint.request.source,
                        checkpoint.request.cacheDirectory,
                        checkpoint.sessionId,
                    )
                ) {
                    is ArchiveSourceResult.Success -> result.source
                    is ArchiveSourceResult.Failure -> throw SourceInstallException(result.error)
                }
                archive = source.path
                checkpoint = checkpoint.copy(
                    phase = CheckpointPhase.SOURCE_READY,
                    archivePath = archive,
                    deleteArchiveAfterParsing = source.deleteAfterParsing,
                    updatedAtEpochMillis = now(),
                )
                save(checkpoint)
                observer.update(
                    progress.phase(
                        InstallPhase.PREPARING_SOURCE,
                        "MRPack source is ready.",
                    ),
                    checkpoint,
                )
            }

            var parsedPlan = checkpoint.parsedPlan
            if (parsedPlan == null) {
                currentCoroutineContext().ensureActive()
                observer.update(
                    progress.phase(
                        InstallPhase.PARSING,
                        "Parsing and validating MRPack.",
                    ),
                    checkpoint,
                )
                logger.log(
                    checkpoint.sessionId,
                    InstallLogSource.PARSER,
                    InstallLogLevel.INFO,
                    "install.parser_started",
                )
                parsedPlan = when (val result = parser.parse(
                    requireNotNull(archive),
                    checkpoint.request.cacheDirectory,
                    checkpoint.sessionId,
                )) {
                    is MrPackParseResult.Success -> result.plan
                    is MrPackParseResult.Failure -> throw ParserInstallException(
                        InstallError(
                            source = InstallErrorSource.PARSER,
                            code = result.error.code.name,
                            message = result.error.message,
                            recoverable = false,
                            details = result.error.details,
                        ),
                    )
                }
                parsedSuccessfully = true
                checkpoint = checkpoint.copy(
                    phase = CheckpointPhase.PARSED,
                    parsedPlan = parsedPlan,
                    updatedAtEpochMillis = now(),
                )
                save(checkpoint)
                deleteTemporaryArchive(checkpoint)
                observer.update(
                    progress.phase(
                        InstallPhase.PARSING,
                        "MRPack parsed successfully.",
                        parsedPlan.downloadEntries.size + parsedPlan.overrideFolders.sumOf { it.files.size },
                    ),
                    checkpoint,
                )
            }
            val requiredParsedPlan = requireNotNull(parsedPlan)

            var resolvedPlan = checkpoint.resolvedPlan
            if (resolvedPlan == null) {
                currentCoroutineContext().ensureActive()
                observer.update(
                    progress.phase(
                        InstallPhase.RESOLVING,
                        "Resolving dependencies and install order.",
                    ),
                    checkpoint,
                )
                logger.log(
                    checkpoint.sessionId,
                    InstallLogSource.RESOLVER,
                    InstallLogLevel.INFO,
                    "install.resolver_started",
                )
                resolvedPlan = resolver.resolve(requiredParsedPlan)
                if (!resolvedPlan.isReady) {
                    throw ResolverInstallException(resolutionError(resolvedPlan))
                }
                checkpoint = checkpoint.copy(
                    phase = CheckpointPhase.RESOLVED,
                    resolvedPlan = resolvedPlan,
                    updatedAtEpochMillis = now(),
                )
                save(checkpoint)
                observer.update(
                    progress.phase(
                        InstallPhase.RESOLVING,
                        "Dependencies resolved successfully.",
                        resolvedPlan.downloadEntries.size + resolvedPlan.overrideFiles.size,
                    ),
                    checkpoint,
                )
            }
            val requiredResolvedPlan = requireNotNull(resolvedPlan)

            val target = validateLauncher(
                checkpoint,
                requiredResolvedPlan,
                observer,
                progress,
            )

            if (resumeInstallerTransaction) {
                return resumeInstaller(
                    checkpoint,
                    requiredResolvedPlan,
                    target,
                    observer,
                    progress,
                )
            }

            currentCoroutineContext().ensureActive()
            observer.update(
                progress.phase(
                    InstallPhase.DOWNLOADING,
                    "Downloading verified files.",
                    requiredResolvedPlan.downloadEntries.size,
                ),
                checkpoint,
            )
            logger.log(
                checkpoint.sessionId,
                InstallLogSource.DOWNLOADER,
                InstallLogLevel.INFO,
                "install.downloader_started",
                mapOf("files" to requiredResolvedPlan.downloadEntries.size),
            )
            val downloadSession = downloadEngine.start(
                plan = requiredResolvedPlan,
                cacheDirectory = checkpoint.request.cacheDirectory,
                configuration = checkpoint.request.downloadConfiguration,
                progressListener = DownloadProgressListener { downloadProgress ->
                    observer.update(
                        progress.download(downloadProgress),
                        checkpoint,
                    )
                },
            )
            observer.registerCancellation(downloadSession::cancel)
            checkpoint = checkpoint.copy(
                phase = CheckpointPhase.DOWNLOADING,
                updatedAtEpochMillis = now(),
            )
            save(checkpoint)
            val downloadResult = try {
                downloadSession.await()
            } finally {
                observer.registerCancellation(null)
            }
            if (downloadResult.status == DownloadSessionStatus.CANCELLED ||
                observer.cancellationRequested
            ) {
                return cancelled(checkpoint)
            }
            if (downloadResult.status != DownloadSessionStatus.COMPLETED) {
                throw DownloadInstallException(downloadError(downloadResult.failure))
            }
            checkpoint = checkpoint.copy(
                phase = CheckpointPhase.DOWNLOADED,
                updatedAtEpochMillis = now(),
            )
            save(checkpoint)
            logger.log(
                checkpoint.sessionId,
                InstallLogSource.DOWNLOADER,
                InstallLogLevel.INFO,
                "install.downloader_completed",
                mapOf("files" to downloadResult.artifacts.size),
            )

            currentCoroutineContext().ensureActive()
            logger.log(
                checkpoint.sessionId,
                InstallLogSource.INSTALLER,
                InstallLogLevel.INFO,
                "install.installer_started",
                mapOf("files" to requiredResolvedPlan.installOrder.size),
            )
            val installSession = installEngine.start(
                plan = requiredResolvedPlan,
                downloadSession = downloadSession,
                destination = target.storage,
                progressListener = InstallProgressListener { installProgress ->
                    observer.update(
                        progress.install(installProgress),
                        checkpoint,
                    )
                    if (installProgress.phase == InstallProgressPhase.ROLLING_BACK) {
                        logger.log(
                            checkpoint.sessionId,
                            InstallLogSource.INSTALLER,
                            InstallLogLevel.WARNING,
                            "install.rollback_started",
                        )
                    }
                },
            )
            observer.registerCancellation(installSession::cancel)
            checkpoint = checkpoint.copy(
                phase = CheckpointPhase.INSTALLING,
                installerTransactionId = installSession.id,
                updatedAtEpochMillis = now(),
            )
            save(checkpoint)
            val installResult = try {
                installSession.await()
            } finally {
                observer.registerCancellation(null)
            }
            if (installResult.status == InstallSessionStatus.CANCELLED ||
                observer.cancellationRequested
            ) {
                return cancelled(checkpoint)
            }
            if (installResult.status != InstallSessionStatus.COMPLETED) {
                throw InstallerInstallException(installError(installResult.failure))
            }
            checkpoint = checkpoint.copy(
                phase = CheckpointPhase.COMPLETED,
                installedFiles = installResult.installedFiles,
                installedBytes = installResult.installedBytes,
                updatedAtEpochMillis = now(),
            )
            save(checkpoint)
            val finalProgress = progress.completed(
                totalFiles = requiredResolvedPlan.installOrder.size,
                installedBytes = installResult.installedBytes,
            )
            observer.update(finalProgress, checkpoint)
            logger.log(
                checkpoint.sessionId,
                InstallLogSource.INSTALLER,
                InstallLogLevel.INFO,
                "install.installer_completed",
                mapOf("transactionId" to installResult.transactionId),
            )
            logger.log(
                checkpoint.sessionId,
                InstallLogSource.ORCHESTRATOR,
                InstallLogLevel.INFO,
                "install.pipeline_completed",
                mapOf("transactionId" to installResult.transactionId),
            )
            return InstallResult.Success(
                sessionId = checkpoint.sessionId,
                transactionId = installResult.transactionId,
                installedFiles = installResult.installedFiles,
                installedBytes = installResult.installedBytes,
                logs = logger.entries(checkpoint.sessionId).value,
                checkpoint = checkpoint,
            )
        } catch (error: CancellationException) {
            logger.log(
                checkpoint.sessionId,
                InstallLogSource.ORCHESTRATOR,
                InstallLogLevel.WARNING,
                "install.pipeline_cancelled",
            )
            return cancelled(checkpoint)
        } catch (error: InstallException) {
            logger.log(
                checkpoint.sessionId,
                InstallLogSource.ORCHESTRATOR,
                InstallLogLevel.ERROR,
                "install.pipeline_failed",
                mapOf("source" to error.error.source, "code" to error.error.code),
                error,
            )
            observer.update(
                progress.phase(
                    when (error.error.source) {
                        InstallErrorSource.PARSER -> InstallPhase.PARSING
                        InstallErrorSource.RESOLVER -> InstallPhase.RESOLVING
                        InstallErrorSource.LAUNCHER -> InstallPhase.VALIDATING_LAUNCHER
                        InstallErrorSource.DOWNLOADER -> InstallPhase.DOWNLOADING
                        InstallErrorSource.INSTALLER -> InstallPhase.INSTALLING
                        else -> InstallPhase.PREPARING_SOURCE
                    },
                    error.error.message,
                ),
                checkpoint,
                error.error,
            )
            return InstallResult.Failure(
                sessionId = checkpoint.sessionId,
                error = error.error,
                logs = logger.entries(checkpoint.sessionId).value,
                checkpoint = checkpoint,
            )
        } catch (error: Throwable) {
            val internal = InstallError(
                source = InstallErrorSource.ORCHESTRATOR,
                code = "INTERNAL",
                message = "The installation pipeline failed unexpectedly.",
                recoverable = false,
                details = mapOf("type" to (error::class.simpleName ?: "unknown")),
            )
            logger.log(
                checkpoint.sessionId,
                InstallLogSource.ORCHESTRATOR,
                InstallLogLevel.ERROR,
                "install.pipeline_unexpected_failure",
                mapOf("type" to error::class.simpleName),
                error,
            )
            observer.update(
                progress.phase(
                    InstallPhase.PREPARING_SOURCE,
                    internal.message,
                ),
                checkpoint,
                internal,
            )
            return InstallResult.Failure(
                sessionId = checkpoint.sessionId,
                error = internal,
                logs = logger.entries(checkpoint.sessionId).value,
                checkpoint = checkpoint,
            )
        } finally {
            observer.registerCancellation(null)
            if (parsedSuccessfully) deleteTemporaryArchive(checkpoint)
        }
    }

    private suspend fun validateLauncher(
        checkpoint: OrchestratorCheckpoint,
        resolvedPlan: ResolvedInstallPlan,
        observer: PipelineObserver,
        progress: InstallProgressAggregator,
    ): LauncherTarget {
        observer.update(
            progress.phase(
                InstallPhase.VALIDATING_LAUNCHER,
                "Validating launcher destination.",
                resolvedPlan.installOrder.size,
            ),
            checkpoint,
        )
        val target = when (
            val result = launcherTargetProvider.targetFor(checkpoint.request.launcherInstance)
        ) {
            is LauncherTargetResult.Success -> result.target
            is LauncherTargetResult.Failure -> throw LauncherInstallException(result.error)
        }
        logger.log(
            checkpoint.sessionId,
            InstallLogSource.FILESYSTEM,
            InstallLogLevel.INFO,
            "install.destination_selected",
            mapOf("providerId" to target.storage.providerId),
        )
        val launcherResult = launcherProvider.inspect(target.storage, target.root)
        val info = when (launcherResult) {
            is LauncherResult.Failure -> {
                val error = launcherResult.errors.firstOrNull()
                throw LauncherInstallException(
                    launcherError(error, "Launcher inspection failed."),
                )
            }
            is LauncherResult.Success -> launcherResult.info
        }
        logger.log(
            checkpoint.sessionId,
            InstallLogSource.LAUNCHER,
            InstallLogLevel.INFO,
            "install.launcher_inspected",
            mapOf(
                "compatible" to info.capabilities.compatible,
                "profiles" to info.instances.size,
                "versions" to info.versions.size,
            ),
        )
        if (!info.capabilities.compatible) {
            throw LauncherInstallException(
                InstallError(
                    InstallErrorSource.LAUNCHER,
                    "INCOMPATIBLE_LAUNCHER",
                    "The selected launcher structure is not compatible.",
                    recoverable = true,
                ),
            )
        }
        val selectedInstance = info.instances.firstOrNull {
            it.profileId == checkpoint.request.launcherInstance.profileId
        }
        if (selectedInstance == null) {
            throw LauncherInstallException(
                InstallError(
                    InstallErrorSource.LAUNCHER,
                    "INSTANCE_NOT_FOUND",
                    "The selected launcher instance is no longer available.",
                    recoverable = true,
                ),
            )
        }
        if (!selectedInstance.gameDirectory.isNullOrBlank()) {
            throw LauncherInstallException(
                InstallError(
                    InstallErrorSource.LAUNCHER,
                    "CUSTOM_GAME_DIRECTORY_UNSUPPORTED",
                    "The selected launcher profile uses a separate game directory that cannot be safely mapped through the selected tree.",
                    recoverable = true,
                ),
            )
        }
        val selectedVersion = info.versions.firstOrNull {
            it.id == selectedInstance.lastVersionId
        } ?: throw LauncherInstallException(
            InstallError(
                InstallErrorSource.LAUNCHER,
                "LAUNCHER_VERSION_NOT_FOUND",
                "The selected launcher profile version could not be verified.",
                recoverable = true,
            ),
        )
        if (selectedVersion.minecraftVersion != resolvedPlan.minecraftVersion) {
            throw LauncherInstallException(
                InstallError(
                    InstallErrorSource.LAUNCHER,
                    "MINECRAFT_VERSION_MISMATCH",
                    "The selected launcher profile uses a different Minecraft version.",
                    recoverable = true,
                ),
            )
        }
        if (selectedVersion.loader != resolvedPlan.loader?.type.toLauncherLoader()) {
            throw LauncherInstallException(
                InstallError(
                    InstallErrorSource.LAUNCHER,
                    "LOADER_MISMATCH",
                    "The selected launcher profile uses a different mod loader.",
                    recoverable = true,
                ),
            )
        }
        return target
    }

    private fun LoaderType?.toLauncherLoader(): LauncherLoader = when (this) {
        LoaderType.FABRIC -> LauncherLoader.FABRIC
        LoaderType.FORGE -> LauncherLoader.FORGE
        LoaderType.NEOFORGE -> LauncherLoader.NEOFORGE
        LoaderType.QUILT,
        null,
        -> LauncherLoader.UNKNOWN
    }

    private suspend fun resumeInstaller(
        checkpoint: OrchestratorCheckpoint,
        resolvedPlan: ResolvedInstallPlan,
        target: LauncherTarget,
        observer: PipelineObserver,
        progress: InstallProgressAggregator,
    ): InstallResult {
        logger.log(
            checkpoint.sessionId,
            InstallLogSource.INSTALLER,
            InstallLogLevel.INFO,
            "install.installer_resume_started",
            mapOf("transactionId" to checkpoint.installerTransactionId),
        )
        val session = installEngine.resume(
            transactionId = requireNotNull(checkpoint.installerTransactionId),
            destination = target.storage,
            progressListener = InstallProgressListener { update ->
                observer.update(progress.install(update), checkpoint)
            },
        )
        observer.registerCancellation(session::cancel)
        val result = try {
            session.await()
        } finally {
            observer.registerCancellation(null)
        }
        if (result.status == InstallSessionStatus.CANCELLED || observer.cancellationRequested) {
            return cancelled(checkpoint)
        }
        if (result.status != InstallSessionStatus.COMPLETED) {
            throw InstallerInstallException(installError(result.failure))
        }
        val completedCheckpoint = checkpoint.copy(
            phase = CheckpointPhase.COMPLETED,
            installedFiles = result.installedFiles,
            installedBytes = result.installedBytes,
            updatedAtEpochMillis = now(),
        )
        save(completedCheckpoint)
        observer.update(
            progress.completed(resolvedPlan.installOrder.size, result.installedBytes),
            completedCheckpoint,
        )
        return InstallResult.Success(
            sessionId = checkpoint.sessionId,
            transactionId = result.transactionId,
            installedFiles = result.installedFiles,
            installedBytes = result.installedBytes,
            logs = logger.entries(checkpoint.sessionId).value,
            checkpoint = completedCheckpoint,
        )
    }

    private fun resolutionError(plan: ResolvedInstallPlan): InstallError {
        val error = plan.fatalErrors.firstOrNull() ?: plan.recoverableErrors.first()
        return InstallError(
            source = InstallErrorSource.RESOLVER,
            code = error.code.name,
            message = error.message,
            recoverable = error.recoverable,
            details = mapOf("path" to (error.path ?: "")),
        )
    }

    private fun downloadError(failure: com.modrith.models.DownloadFailure?): InstallError =
        InstallError(
        source = InstallErrorSource.DOWNLOADER,
        code = failure?.code?.name ?: "DOWNLOAD_FAILED",
        message = failure?.message ?: "Downloads did not complete.",
        recoverable = failure?.recoverable ?: false,
        details = mapOf("artifactKey" to (failure?.artifactKey ?: "")),
    )

    private fun installError(failure: com.modrith.installer.InstallFailure?): InstallError =
        InstallError(
            source = InstallErrorSource.INSTALLER,
            code = failure?.code?.name ?: InstallFailureCode.INTERNAL.name,
            message = failure?.message ?: "Installation did not complete.",
            recoverable = failure?.recoverable ?: false,
            details = mapOf("path" to (failure?.path ?: "")),
        )

    private fun launcherError(
        error: LauncherError?,
        fallback: String,
    ): InstallError = InstallError(
        source = InstallErrorSource.LAUNCHER,
        code = error?.code?.name ?: "INSPECTION_FAILED",
        message = error?.message ?: fallback,
        recoverable = error?.recoverable ?: true,
        details = mapOf("path" to (error?.path?.value ?: "")),
    )

    private suspend fun save(checkpoint: OrchestratorCheckpoint) {
        checkpoints.save(checkpoint)
    }

    private fun cancelled(checkpoint: OrchestratorCheckpoint): InstallResult.Cancelled =
        InstallResult.Cancelled(
            sessionId = checkpoint.sessionId,
            logs = logger.entries(checkpoint.sessionId).value,
            checkpoint = checkpoint,
        )

    private fun deleteTemporaryArchive(checkpoint: OrchestratorCheckpoint) {
        if (!checkpoint.deleteArchiveAfterParsing) return
        val archive = checkpoint.archivePath ?: return
        runCatching { Files.deleteIfExists(archive) }
            .onFailure { error ->
                logger.log(
                    checkpoint.sessionId,
                    InstallLogSource.FILESYSTEM,
                    InstallLogLevel.WARNING,
                    "install.source_cleanup_failed",
                    mapOf("type" to error::class.simpleName),
                    error,
                )
            }
    }

    private fun now(): Long = System.currentTimeMillis()
}
