package com.modrith.app.install

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.modrith.filesystem.DocumentTreeProvider
import com.modrith.filesystem.StorageResult
import com.modrith.launcher.LauncherInfo
import com.modrith.launcher.LauncherInstance
import com.modrith.launcher.LauncherProvider
import com.modrith.launcher.LauncherResult
import com.modrith.models.MrPackParseResult
import com.modrith.orchestrator.ArchiveSourceResult
import com.modrith.orchestrator.InstallError
import com.modrith.orchestrator.InstallErrorSource
import com.modrith.orchestrator.InstallLogEntry
import com.modrith.orchestrator.InstallManager
import com.modrith.orchestrator.InstallPhase
import com.modrith.orchestrator.InstallResult
import com.modrith.orchestrator.InstallSession
import com.modrith.orchestrator.InstallState
import com.modrith.orchestrator.InstallStatus
import com.modrith.orchestrator.MrPackSource
import com.modrith.orchestrator.MrPackSourceResolver
import com.modrith.orchestrator.OrchestratorParser
import com.modrith.orchestrator.OrchestratorResolver
import com.modrith.ui.install.InstallErrorUi
import com.modrith.ui.install.InstallLogUi
import com.modrith.ui.install.InstallProgressUi
import com.modrith.ui.install.InstallResultUi
import com.modrith.ui.install.InstallScreen
import com.modrith.ui.install.InstallUiState
import com.modrith.ui.install.InstallWorkflow
import com.modrith.ui.install.LauncherInstanceUi
import com.modrith.ui.install.LauncherSummary
import com.modrith.ui.install.PackSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@Singleton
class AndroidInstallWorkflow @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contentResolver: ContentResolver,
    private val installManager: InstallManager,
    private val launcherProvider: LauncherProvider,
    private val launcherTargets: SafLauncherTargetRegistry,
    private val sourceResolver: MrPackSourceResolver,
    private val parser: OrchestratorParser,
    private val resolver: OrchestratorResolver,
    private val stateStore: InstallWorkflowStateStore,
) : InstallWorkflow {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val restored = stateStore.load()
    private val mutableState = MutableStateFlow(restored.state)
    private var sourceUri: String? = restored.sourceUri
    private var launcherTreeUri: String? = restored.launcherTreeUri
    private var launcherInstances: List<LauncherInstance> = restored.launcherInstances
    private var activeSession: InstallSession? = null
    private var activeJob: Job? = null

    override val state: StateFlow<InstallUiState> = mutableState.asStateFlow()

    init {
        val sessionId = restored.activeSessionId
            ?: restored.state.resumableSessionId
                ?.takeIf { restored.state.screen == InstallScreen.PROGRESS }
        if (sessionId != null && restored.state.screen == InstallScreen.PROGRESS) {
            update {
                it.copy(
                    screen = InstallScreen.PROGRESS,
                    busy = true,
                    resumableSessionId = sessionId,
                    progress = it.progress.copy(
                        title = "Resuming installation",
                        message = "Restoring the interrupted installation.",
                    ),
                )
            }
            attach(installManager.resume(sessionId))
        }
    }

    override fun selectMrPack(uri: String) {
        activeJob?.cancel()
        scope.launch {
            if (sourceUri != uri) {
                releasePersistedPermission(sourceUri, read = true, write = false)
            }
            releasePersistedPermission(launcherTreeUri, read = true, write = true)
            sourceUri = null
            launcherTreeUri = null
            launcherInstances = emptyList()
            update {
                InstallUiState(
                    screen = InstallScreen.HOME,
                    busy = true,
                    progress = InstallProgressUi(
                        title = "Checking MRPack",
                        message = "Reading pack metadata.",
                    ),
                )
            }
            val parsedUri = Uri.parse(uri)
            if (!persistSourcePermission(parsedUri)) {
                showError(
                    InstallError(
                        source = InstallErrorSource.SOURCE,
                        code = "PERSIST_PERMISSION_FAILED",
                        message = "This document provider did not grant durable access to the MRPack.",
                        recoverable = true,
                    ),
                )
                return@launch
            }
            sourceUri = uri
            val displayName = queryDisplayName(parsedUri) ?: "Selected MRPack"
            if (!displayName.endsWith(".mrpack", ignoreCase = true)) {
                showError(
                    InstallError(
                        source = InstallErrorSource.SOURCE,
                        code = "INVALID_EXTENSION",
                        message = "Choose a file with the .mrpack extension.",
                        recoverable = true,
                    ),
                )
                return@launch
            }

            val previewRoot = context.cacheDir.toPath().resolve("phase8-preview")
            deleteRecursively(previewRoot)
            Files.createDirectories(previewRoot)
            val previewSessionId = "preview-${UUID.randomUUID()}"
            val source = when (
                val result = sourceResolver.resolve(
                    MrPackSource.UriSource(uri),
                    previewRoot,
                    previewSessionId,
                )
            ) {
                is ArchiveSourceResult.Success -> result.source
                is ArchiveSourceResult.Failure -> {
                    showError(result.error)
                    return@launch
                }
            }
            val parsed = parser.parse(source.path, previewRoot, previewSessionId)
            if (source.deleteAfterParsing) Files.deleteIfExists(source.path)
            val plan = when (parsed) {
                is MrPackParseResult.Success -> parsed.plan
                is MrPackParseResult.Failure -> {
                    showError(
                        InstallError(
                            source = InstallErrorSource.PARSER,
                            code = parsed.error.code.name,
                            message = parsed.error.message,
                            recoverable = false,
                        ),
                    )
                    return@launch
                }
            }
            val resolved = resolver.resolve(plan)
            if (!resolved.isReady) {
                val failure = resolved.fatalErrors.firstOrNull()
                    ?: resolved.recoverableErrors.first()
                showError(
                    InstallError(
                        source = InstallErrorSource.RESOLVER,
                        code = failure.code.name,
                        message = failure.message,
                        recoverable = failure.recoverable,
                    ),
                )
                return@launch
            }

            update {
                InstallUiState(
                    screen = InstallScreen.CONFIRMATION,
                    pack = PackSummary(
                        displayName = displayName,
                        name = plan.manifest.name,
                        versionId = plan.manifest.versionId,
                        minecraftVersion = plan.minecraftVersion,
                        loader = plan.loaderType.name.lowercase()
                            .replaceFirstChar { character -> character.uppercase() },
                        loaderVersion = plan.loaderVersion,
                        modCount = plan.modCount,
                        totalFiles = resolved.installOrder.size,
                        totalDownloadBytes = resolved.totalDownloadSize,
                        warnings = resolved.warnings.map { warning -> warning.message }.distinct(),
                    ),
                )
            }
        }
    }

    override fun selectLauncherTree(uri: String) {
        scope.launch {
            Timber.tag(LAUNCHER_DISCOVERY_TAG).i(
                "launcher.discovery.selected_saf_uri {uri=%s}",
                uri,
            )
            if (launcherTreeUri != uri) {
                releasePersistedPermission(launcherTreeUri, read = true, write = true)
            }
            launcherTreeUri = null
            launcherInstances = emptyList()
            update { it.copy(busy = true, error = null) }
            val provider = runCatching {
                DocumentTreeProvider(context, Uri.parse(uri))
            }.getOrElse {
                showError(
                    InstallError(
                        InstallErrorSource.FILESYSTEM,
                        "INVALID_TREE_URI",
                        "The selected launcher tree could not be opened.",
                        recoverable = true,
                    ),
                )
                return@launch
            }
            Timber.tag(LAUNCHER_DISCOVERY_TAG).i(
                "launcher.discovery.resolved_document_tree " +
                    "{selectedTreeUri=%s, treeDocumentId=%s, rootDocumentUri=%s, rootDisplayName=%s}",
                provider.resolvedTree.selectedTreeUri,
                provider.resolvedTree.treeDocumentId,
                provider.resolvedTree.rootDocumentUri,
                provider.resolvedTree.rootDisplayName,
            )
            val permission = provider.persistPermission(read = true, write = true)
            if (permission is StorageResult.Failure) {
                showError(
                    InstallError(
                        InstallErrorSource.FILESYSTEM,
                        permission.error.code.name,
                        permission.error.message,
                        recoverable = true,
                    ),
                )
                return@launch
            }
            launcherTreeUri = uri
            val info = when (val result = launcherProvider.inspect(provider)) {
                is LauncherResult.Failure -> {
                    val error = result.errors.firstOrNull()
                    showError(
                        InstallError(
                            InstallErrorSource.LAUNCHER,
                            error?.code?.name ?: "INSPECTION_FAILED",
                            error?.message ?: "The launcher tree could not be inspected.",
                            recoverable = true,
                        ),
                    )
                    return@launch
                }
                is LauncherResult.Success -> result.info
            }
            if (!info.capabilities.compatible || info.instances.isEmpty()) {
                showError(
                    InstallError(
                        InstallErrorSource.LAUNCHER,
                        "INCOMPATIBLE_LAUNCHER",
                        if (info.instances.isEmpty()) {
                            "No launcher instances were found in the selected tree."
                        } else {
                            "The selected launcher tree is not compatible."
                        },
                        recoverable = true,
                    ),
                )
                return@launch
            }
            launcherInstances = info.instances
            launcherTargets.remember(uri, info.root, info.instances)
            update {
                it.copy(
                    screen = InstallScreen.CONFIRMATION,
                    busy = false,
                    launcher = info.toUi(),
                    selectedLauncherProfileId = info.instances.first().profileId,
                    error = null,
                )
            }
        }
    }

    override fun selectLauncherInstance(profileId: String) {
        if (launcherInstances.none { it.profileId == profileId }) return
        update { it.copy(selectedLauncherProfileId = profileId) }
    }

    override fun startInstallation() {
        val uri = sourceUri ?: return
        val selectedId = mutableState.value.selectedLauncherProfileId ?: return
        val instance = launcherInstances.firstOrNull { it.profileId == selectedId } ?: return
        val cacheDirectory = context.cacheDir.toPath().resolve("install-cache")
        update {
            it.copy(
                screen = InstallScreen.PROGRESS,
                busy = true,
                error = null,
                result = null,
                progress = InstallProgressUi(
                    title = "Preparing installation",
                    message = "Starting the verified installation pipeline.",
                ),
            )
        }
        attach(
            installManager.install(
                source = MrPackSource.UriSource(uri),
                launcherInstance = instance,
                cacheDirectory = cacheDirectory,
            ),
        )
    }

    override fun cancelInstallation() {
        update {
            it.copy(
                progress = it.progress.copy(
                    title = "Cancelling installation",
                    message = "Stopping work and restoring the destination if needed.",
                ),
            )
        }
        activeSession?.cancel()
    }

    override fun retryInstallation() {
        val sessionId = mutableState.value.resumableSessionId
        if (sessionId == null) {
            update {
                it.copy(
                    screen = if (it.pack == null) {
                        InstallScreen.HOME
                    } else {
                        InstallScreen.CONFIRMATION
                    },
                    busy = false,
                    error = null,
                )
            }
            return
        }
        if (mutableState.value.error?.code == "CANCELLED") {
            startInstallation()
            return
        }
        update {
            it.copy(
                screen = InstallScreen.PROGRESS,
                busy = true,
                error = null,
                progress = it.progress.copy(
                    title = "Retrying installation",
                    message = "Resuming from the last durable checkpoint.",
                ),
            )
        }
        attach(installManager.resume(sessionId))
    }

    override fun reset() {
        activeSession?.cancel()
        activeJob?.cancel()
        releasePersistedPermission(sourceUri, read = true, write = false)
        releasePersistedPermission(launcherTreeUri, read = true, write = true)
        sourceUri = null
        launcherTreeUri = null
        launcherInstances = emptyList()
        activeSession = null
        activeJob = null
        mutableState.value = InstallUiState()
        stateStore.clear()
    }

    private fun attach(session: InstallSession) {
        activeJob?.cancel()
        activeSession = session
        stateStore.setActiveSession(session.id)
        update {
            it.copy(
                screen = InstallScreen.PROGRESS,
                busy = true,
                resumableSessionId = session.id,
            )
        }
        activeJob = scope.launch {
            val collector = launch {
                session.state.collect(::handleInstallState)
            }
            val result = try {
                session.await()
            } catch (_: CancellationException) {
                return@launch
            } finally {
                collector.cancel()
            }
            handleResult(result)
        }
    }

    private fun handleInstallState(state: InstallState) {
        update { current ->
            val restoredLogs = state.logs.takeLast(MAX_LOGS).map { log -> log.toUi() }
            current.copy(
                screen = if (state.status == InstallStatus.FAILED) {
                    InstallScreen.ERROR
                } else {
                    InstallScreen.PROGRESS
                },
                busy = state.status == InstallStatus.CREATED ||
                    state.status == InstallStatus.RUNNING,
                progress = state.toUiProgress(),
                logs = restoredLogs.ifEmpty { current.logs },
                error = state.error?.toUi(),
                resumableSessionId = state.sessionId,
            )
        }
    }

    private fun handleResult(result: InstallResult) {
        activeSession = null
        stateStore.setActiveSession(null)
        when (result) {
            is InstallResult.Success -> update { current ->
                val restoredLogs = result.logs.takeLast(MAX_LOGS).map { log -> log.toUi() }
                current.copy(
                    screen = InstallScreen.SUCCESS,
                    busy = false,
                    progress = current.progress.copy(
                        title = "Installation complete",
                        message = "All files were verified and installed.",
                        percentage = 1f,
                    ),
                    logs = restoredLogs.ifEmpty { current.logs },
                    result = InstallResultUi(
                        packName = current.pack?.name ?: "Modpack",
                        launcherInstanceName = selectedLauncherName(),
                        installedFiles = result.installedFiles,
                        installedBytes = result.installedBytes,
                    ),
                    error = null,
                    resumableSessionId = null,
                )
            }
            is InstallResult.Failure -> update { current ->
                val restoredLogs = result.logs.takeLast(MAX_LOGS).map { log -> log.toUi() }
                current.copy(
                    screen = InstallScreen.ERROR,
                    busy = false,
                    logs = restoredLogs.ifEmpty { current.logs },
                    error = result.error.toUi(),
                    resumableSessionId = if (result.error.recoverable) result.sessionId else null,
                )
            }
            is InstallResult.Cancelled -> update { current ->
                val restoredLogs = result.logs.takeLast(MAX_LOGS).map { log -> log.toUi() }
                current.copy(
                    screen = InstallScreen.ERROR,
                    busy = false,
                    logs = restoredLogs.ifEmpty { current.logs },
                    error = InstallErrorUi(
                        title = "Installation cancelled",
                        message = "The installation was stopped.",
                        action = "You can resume from the saved checkpoint or choose another pack.",
                        recoverable = true,
                        code = "CANCELLED",
                    ),
                    resumableSessionId = result.sessionId,
                )
            }
        }
    }

    private fun showError(error: InstallError) {
        update {
            it.copy(
                screen = InstallScreen.ERROR,
                busy = false,
                error = error.toUi(),
                resumableSessionId = null,
            )
        }
    }

    private fun update(transform: (InstallUiState) -> InstallUiState) {
        val updated = transform(mutableState.value)
        mutableState.value = updated
        stateStore.save(
            state = updated,
            sourceUri = sourceUri,
            launcherTreeUri = launcherTreeUri,
            launcherInstances = launcherInstances,
        )
    }

    private fun persistSourcePermission(uri: Uri): Boolean = try {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
    } catch (_: SecurityException) {
        false
    }

    private fun releasePersistedPermission(
        rawUri: String?,
        read: Boolean,
        write: Boolean,
    ) {
        val uri = rawUri?.let(Uri::parse) ?: return
        val flags = (if (read) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        runCatching { contentResolver.releasePersistableUriPermission(uri, flags) }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cursor: Cursor = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        ) ?: return null
        return cursor.use {
            if (!it.moveToFirst()) return@use null
            it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
    }

    private fun selectedLauncherName(): String {
        val selected = mutableState.value.selectedLauncherProfileId
        return launcherInstances.firstOrNull { it.profileId == selected }?.name
            ?: "Selected launcher instance"
    }

    private fun LauncherInfo.toUi() = LauncherSummary(
        displayName = displayName,
        instances = instances.map {
            LauncherInstanceUi(it.profileId, it.name, it.lastVersionId)
        },
        warnings = warnings.map { it.message }.distinct(),
    )

    private fun InstallState.toUiProgress(): InstallProgressUi {
        val title = when (progress.phase) {
            InstallPhase.PREPARING_SOURCE -> "Preparing source"
            InstallPhase.PARSING -> "Checking pack"
            InstallPhase.RESOLVING -> "Resolving files"
            InstallPhase.VALIDATING_LAUNCHER -> "Checking launcher"
            InstallPhase.DOWNLOADING -> "Downloading files"
            InstallPhase.INSTALLING -> "Installing files"
            InstallPhase.ROLLING_BACK -> "Restoring destination"
            InstallPhase.COMPLETED -> "Installation complete"
        }
        return InstallProgressUi(
            title = title,
            message = progress.statusMessage,
            percentage = (progress.percentage / 100.0).toFloat(),
            filesCompleted = progress.filesCompleted,
            totalFiles = progress.totalFiles,
            downloadedBytes = progress.bytesDownloaded,
            totalBytes = progress.totalBytes,
            bytesPerSecond = progress.currentSpeedBytesPerSecond,
            estimatedRemainingMillis = progress.estimatedRemainingMillis,
            currentFile = if (progress.phase == InstallPhase.DOWNLOADING) {
                null
            } else {
                progress.currentFile?.substringAfterLast('/')?.takeLast(80)
            },
        )
    }

    private fun InstallLogEntry.toUi(): InstallLogUi {
        val detail = attributes.entries
            .sortedBy(Map.Entry<String, String>::key)
            .joinToString(" ") { "${it.key}=${it.value}" }
        return InstallLogUi(
            timestampEpochMillis = timestampEpochMillis,
            level = level.name,
            source = source.name,
            message = if (detail.isBlank()) event else "$event $detail",
        )
    }

    private fun InstallError.toUi(): InstallErrorUi {
        val remediation = when (source) {
            InstallErrorSource.SOURCE ->
                "Choose the MRPack again from a document provider."
            InstallErrorSource.PARSER ->
                "Choose a valid official Modrinth MRPack."
            InstallErrorSource.RESOLVER ->
                "Choose a pack with a supported Minecraft loader and client files."
            InstallErrorSource.FILESYSTEM ->
                "Select the launcher tree again and confirm read and write access."
            InstallErrorSource.LAUNCHER ->
                "Select the launcher storage root and a compatible existing instance."
            InstallErrorSource.DOWNLOADER ->
                "Check the network connection and retry. Verified partial downloads are retained."
            InstallErrorSource.INSTALLER ->
                "Free storage or restore launcher-tree access, then retry."
            InstallErrorSource.ORCHESTRATOR ->
                "Retry the installation. Choose the pack again if the checkpoint is unavailable."
        }
        return InstallErrorUi(
            title = when (source) {
                InstallErrorSource.SOURCE -> "MRPack access failed"
                InstallErrorSource.PARSER -> "MRPack is not valid"
                InstallErrorSource.RESOLVER -> "Pack is not installable"
                InstallErrorSource.FILESYSTEM -> "Storage access failed"
                InstallErrorSource.LAUNCHER -> "Launcher is not ready"
                InstallErrorSource.DOWNLOADER -> "Download failed"
                InstallErrorSource.INSTALLER -> "Installation failed"
                InstallErrorSource.ORCHESTRATOR -> "Installation could not continue"
            },
            message = message,
            action = remediation,
            recoverable = recoverable,
            code = code,
        )
    }

    private suspend fun deleteRecursively(path: Path) = withContext(Dispatchers.IO) {
        if (!Files.exists(path)) return@withContext
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private companion object {
        const val LAUNCHER_DISCOVERY_TAG = "LauncherDiscovery"
        const val MAX_LOGS = 200
    }
}
