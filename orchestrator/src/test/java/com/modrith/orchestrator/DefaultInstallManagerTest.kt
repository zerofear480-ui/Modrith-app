package com.modrith.orchestrator

import com.modrith.filesystem.DeleteSummary
import com.modrith.filesystem.FileReplaceResult
import com.modrith.filesystem.StorageEntry
import com.modrith.filesystem.StorageError
import com.modrith.filesystem.StorageErrorCode
import com.modrith.filesystem.StoragePath
import com.modrith.filesystem.StorageProgressListener
import com.modrith.filesystem.StorageProvider
import com.modrith.filesystem.StorageResult
import com.modrith.installer.InstallEngine
import com.modrith.installer.InstallFailure
import com.modrith.installer.InstallFailureCode
import com.modrith.installer.InstallProgress
import com.modrith.installer.InstallProgressListener
import com.modrith.installer.InstallProgressPhase
import com.modrith.installer.InstallSession as InstallerSession
import com.modrith.installer.InstallSessionResult
import com.modrith.installer.InstallSessionStatus
import com.modrith.launcher.LauncherCapabilities
import com.modrith.launcher.LauncherDirectory
import com.modrith.launcher.LauncherError
import com.modrith.launcher.LauncherErrorCode
import com.modrith.launcher.LauncherInfo
import com.modrith.launcher.LauncherInstance
import com.modrith.launcher.LauncherLoader
import com.modrith.launcher.LauncherProvider
import com.modrith.launcher.LauncherResult
import com.modrith.launcher.LauncherVersion
import com.modrith.models.DownloadArtifactResult
import com.modrith.models.DownloadArtifactStatus
import com.modrith.models.DownloadConfiguration
import com.modrith.models.DownloadEngine
import com.modrith.models.DownloadEntry
import com.modrith.models.DownloadFailure
import com.modrith.models.DownloadFailureCode
import com.modrith.models.DownloadProgress
import com.modrith.models.DownloadProgressListener
import com.modrith.models.DownloadSession
import com.modrith.models.DownloadSessionResult
import com.modrith.models.DownloadSessionStatus
import com.modrith.models.EnvironmentSupport
import com.modrith.models.HashAlgorithm
import com.modrith.models.InstallPlan
import com.modrith.models.LoaderType
import com.modrith.models.MrPackDependencies
import com.modrith.models.MrPackFile
import com.modrith.models.MrPackHashes
import com.modrith.models.MrPackManifest
import com.modrith.models.MrPackParseError
import com.modrith.models.MrPackParseErrorCode
import com.modrith.models.MrPackParseResult
import com.modrith.models.ResolvedDownloadEntry
import com.modrith.models.ResolvedFileType
import com.modrith.models.ResolvedHash
import com.modrith.models.ResolvedInstallPlan
import com.modrith.models.ResolvedInstallStep
import com.modrith.models.ResolvedInstallStepType
import com.modrith.models.ResolvedLoader
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultInstallManagerTest {
    @Test
    fun successfulInstallRunsTheCompletePipelineAndAggregatesProgressAndLogs() = runBlocking {
        val environment = TestEnvironment()

        val session = environment.manager.install(
            source = MrPackSource.FileSource(environment.archive.toFile()),
            launcherInstance = TestInstance,
            cacheDirectory = environment.cache,
        )
        val result = session.await()

        assertTrue(result is InstallResult.Success)
        assertEquals(InstallStatus.COMPLETED, session.state.value.status)
        assertEquals(100.0, session.state.value.progress.percentage, 0.0)
        assertEquals(1, environment.parser.calls.get())
        assertEquals(1, environment.resolver.calls.get())
        assertEquals(1, environment.downloader.starts.get())
        assertEquals(1, environment.installer.starts.get())
        assertEquals(1, environment.launcher.inspections.get())
        assertTrue(
            result.logs.map(InstallLogEntry::source).containsAll(
                setOf(
                    InstallLogSource.ORCHESTRATOR,
                    InstallLogSource.PARSER,
                    InstallLogSource.RESOLVER,
                    InstallLogSource.DOWNLOADER,
                    InstallLogSource.FILESYSTEM,
                    InstallLogSource.INSTALLER,
                    InstallLogSource.LAUNCHER,
                ),
            ),
        )
    }

    @Test
    fun parserFailureStopsBeforeResolution() = runBlocking {
        val environment = TestEnvironment(
            parserResult = MrPackParseResult.Failure(
                MrPackParseError(
                    MrPackParseErrorCode.INVALID_JSON,
                    "Invalid manifest.",
                ),
            ),
        )

        val result = environment.start().await()

        assertFailure(result, InstallErrorSource.PARSER, "INVALID_JSON")
        assertEquals(0, environment.resolver.calls.get())
        assertEquals(0, environment.downloader.starts.get())
    }

    @Test
    fun resolverFailureStopsBeforeDownload() = runBlocking {
        val invalid = resolvedPlan().copy(
            fatalErrors = listOf(
                com.modrith.models.ResolutionError(
                    com.modrith.models.ResolutionErrorCode.MISSING_LOADER,
                    "Loader missing.",
                    recoverable = false,
                ),
            ),
        )
        val environment = TestEnvironment(resolvedPlan = invalid)

        val result = environment.start().await()

        assertFailure(result, InstallErrorSource.RESOLVER, "MISSING_LOADER")
        assertEquals(0, environment.downloader.starts.get())
    }

    @Test
    fun downloadFailureStopsBeforeInstaller() = runBlocking {
        val environment = TestEnvironment(
            downloadResult = failedDownload(),
        )

        val result = environment.start().await()

        assertFailure(result, InstallErrorSource.DOWNLOADER, "NETWORK_IO")
        assertEquals(0, environment.installer.starts.get())
    }

    @Test
    fun installerRollbackFailureIsPropagatedWithoutOrchestratorRecoveryLogic() = runBlocking {
        val environment = TestEnvironment(
            installResult = InstallSessionResult(
                transactionId = "transaction",
                status = InstallSessionStatus.FAILED,
                installedFiles = 0,
                installedBytes = 0,
                failure = InstallFailure(
                    InstallFailureCode.DESTINATION_IO,
                    "Commit failed and installer rolled back.",
                    recoverable = true,
                ),
            ),
        )
        environment.installer.rollbackPerformed.set(true)

        val result = environment.start().await()

        assertFailure(result, InstallErrorSource.INSTALLER, "DESTINATION_IO")
        assertTrue(environment.installer.rollbackPerformed.get())
        assertEquals(CheckpointPhase.INSTALLING, result.checkpoint?.phase)
    }

    @Test
    fun cancellationCancelsTheActiveDownloaderAndEndsInCancelledState() = runBlocking {
        val environment = TestEnvironment(blockDownload = true)
        val session = environment.start()
        withTimeout(2_000) {
            while (environment.downloader.lastSession == null) delay(10)
        }

        session.cancel()
        val result = withTimeout(2_000) { session.await() }

        assertTrue(result is InstallResult.Cancelled)
        assertEquals(InstallStatus.CANCELLED, session.state.value.status)
        assertTrue(environment.downloader.lastSession?.cancelled?.get() == true)
        assertEquals(0, environment.installer.starts.get())
    }

    @Test
    fun resumeContinuesAnInterruptedInstallerTransaction() = runBlocking {
        val environment = TestEnvironment()
        val sessionId = "resume-session"
        environment.checkpoints.save(
            OrchestratorCheckpoint(
                sessionId = sessionId,
                request = environment.request(),
                phase = CheckpointPhase.INSTALLING,
                archivePath = environment.archive,
                parsedPlan = validPlan(),
                resolvedPlan = resolvedPlan(),
                installerTransactionId = "interrupted-transaction",
                updatedAtEpochMillis = 1,
            ),
        )

        val result = environment.manager.resume(sessionId).await()

        assertTrue(result is InstallResult.Success)
        assertEquals(0, environment.parser.calls.get())
        assertEquals(0, environment.resolver.calls.get())
        assertEquals(0, environment.downloader.starts.get())
        assertEquals(0, environment.installer.starts.get())
        assertEquals(1, environment.installer.resumes.get())
    }

    @Test
    fun resumeRehydratesPlansBeforeContinuingInterruptedInstallerTransaction() = runBlocking {
        val environment = TestEnvironment()
        val sessionId = "rehydrated-resume-session"
        environment.checkpoints.save(
            OrchestratorCheckpoint(
                sessionId = sessionId,
                request = environment.request(),
                phase = CheckpointPhase.INSTALLING,
                installerTransactionId = "interrupted-transaction",
                updatedAtEpochMillis = 1,
            ),
        )

        val result = environment.manager.resume(sessionId).await()

        assertTrue(result is InstallResult.Success)
        assertEquals(1, environment.parser.calls.get())
        assertEquals(1, environment.resolver.calls.get())
        assertEquals(0, environment.downloader.starts.get())
        assertEquals(0, environment.installer.starts.get())
        assertEquals(1, environment.installer.resumes.get())
    }

    @Test
    fun invalidLauncherStopsBeforeDownload() = runBlocking {
        val environment = TestEnvironment(
            launcherResult = LauncherResult.Failure(
                listOf(
                    LauncherError(
                        LauncherErrorCode.INVALID_ROOT,
                        "Invalid launcher root.",
                        recoverable = true,
                    ),
                ),
            ),
        )

        val result = environment.start().await()

        assertFailure(result, InstallErrorSource.LAUNCHER, "INVALID_ROOT")
        assertEquals(0, environment.downloader.starts.get())
    }

    @Test
    fun customLauncherGameDirectoryStopsBeforeDownload() = runBlocking {
        val customInstance = TestInstance.copy(gameDirectory = "/private/launcher/instance")
        val environment = TestEnvironment(
            launcherResult = validLauncher(customInstance),
            launcherInstance = customInstance,
        )

        val result = environment.start().await()

        assertFailure(
            result,
            InstallErrorSource.LAUNCHER,
            "CUSTOM_GAME_DIRECTORY_UNSUPPORTED",
        )
        assertEquals(0, environment.downloader.starts.get())
    }

    @Test
    fun incompatibleLauncherVersionStopsBeforeDownload() = runBlocking {
        val environment = TestEnvironment(
            launcherResult = validLauncher(
                version = TestVersion.copy(minecraftVersion = "1.20.1"),
            ),
        )

        val result = environment.start().await()

        assertFailure(result, InstallErrorSource.LAUNCHER, "MINECRAFT_VERSION_MISMATCH")
        assertEquals(0, environment.downloader.starts.get())
    }

    @Test
    fun invalidMrpackSourceReturnsStructuredSourceError() = runBlocking {
        val environment = TestEnvironment(
            sourceResult = ArchiveSourceResult.Failure(
                InstallError(
                    InstallErrorSource.SOURCE,
                    "INVALID_MRPACK",
                    "The selected MRPack cannot be opened.",
                    recoverable = true,
                ),
            ),
        )

        val result = environment.start().await()

        assertFailure(result, InstallErrorSource.SOURCE, "INVALID_MRPACK")
        assertEquals(0, environment.parser.calls.get())
    }

    private fun assertFailure(
        result: InstallResult,
        source: InstallErrorSource,
        code: String,
    ) {
        assertTrue(result is InstallResult.Failure)
        result as InstallResult.Failure
        assertEquals(source, result.error.source)
        assertEquals(code, result.error.code)
    }
}

private class TestEnvironment(
    parserResult: MrPackParseResult = MrPackParseResult.Success(validPlan()),
    resolvedPlan: ResolvedInstallPlan = resolvedPlan(),
    downloadResult: DownloadSessionResult = successfulDownload(),
    installResult: InstallSessionResult = successfulInstall(),
    launcherResult: LauncherResult = validLauncher(),
    sourceResult: ArchiveSourceResult? = null,
    blockDownload: Boolean = false,
    private val launcherInstance: LauncherInstance = TestInstance,
) {
    val root: Path = Files.createTempDirectory("orchestrator-test")
    val archive: Path = Files.write(root.resolve("pack.mrpack"), byteArrayOf(1))
    val cache: Path = Files.createDirectories(root.resolve("cache"))
    val parser = FakeParser(parserResult)
    val resolver = FakeResolver(resolvedPlan)
    val downloader = FakeDownloader(downloadResult, blockDownload)
    val installer = FakeInstaller(installResult)
    val filesystem = FakeStorageProvider()
    val launcher = FakeLauncher(launcherResult)
    val checkpoints = InMemoryOrchestratorCheckpointRepository()
    private val logger = JvmInstallLogger(logger = java.util.logging.Logger.getAnonymousLogger())
    private val sourceResolver = FakeSourceResolver(
        sourceResult ?: ArchiveSourceResult.Success(
            ResolvedArchiveSource(archive, deleteAfterParsing = false),
        ),
    )
    private val targetProvider = LauncherTargetProvider {
        LauncherTargetResult.Success(LauncherTarget(filesystem))
    }
    private val pipeline = InstallPipeline(
        parser = parser,
        resolver = resolver,
        downloadEngine = downloader,
        installEngine = installer,
        launcherProvider = launcher,
        launcherTargetProvider = targetProvider,
        sourceResolver = sourceResolver,
        checkpoints = checkpoints,
        logger = logger,
    )
    val manager: InstallManager = DefaultInstallManager(
        InstallCoordinator(pipeline, checkpoints, logger),
    )

    fun request(): InstallRequest = InstallRequest(
        source = MrPackSource.FileSource(archive.toFile()),
        launcherInstance = launcherInstance,
        cacheDirectory = cache,
        downloadConfiguration = DownloadConfiguration(),
    )

    fun start(): InstallSession = manager.install(
        source = MrPackSource.FileSource(archive.toFile()),
        launcherInstance = launcherInstance,
        cacheDirectory = cache,
    )
}

private class FakeParser(
    private val result: MrPackParseResult,
) : OrchestratorParser {
    val calls = AtomicInteger()

    override suspend fun parse(
        archive: Path,
        cacheDirectory: Path,
        sessionId: String,
    ): MrPackParseResult {
        calls.incrementAndGet()
        return result
    }
}

private class FakeResolver(
    private val result: ResolvedInstallPlan,
) : OrchestratorResolver {
    val calls = AtomicInteger()

    override suspend fun resolve(plan: InstallPlan): ResolvedInstallPlan {
        calls.incrementAndGet()
        return result
    }
}

private class FakeSourceResolver(
    private val result: ArchiveSourceResult,
) : MrPackSourceResolver {
    override suspend fun resolve(
        source: MrPackSource,
        cacheDirectory: Path,
        sessionId: String,
    ): ArchiveSourceResult = result
}

private class FakeDownloader(
    private val configuredResult: DownloadSessionResult,
    private val block: Boolean,
) : DownloadEngine {
    val starts = AtomicInteger()
    var lastSession: FakeDownloadSession? = null

    override fun start(
        plan: ResolvedInstallPlan,
        cacheDirectory: Path,
        configuration: DownloadConfiguration,
        progressListener: DownloadProgressListener,
    ): DownloadSession {
        starts.incrementAndGet()
        progressListener.onProgress(
            DownloadProgress(
                sessionId = "download",
                artifactKey = plan.downloadEntries.firstOrNull()?.artifactKey,
                completedFiles = if (block) 0 else plan.downloadEntries.size,
                totalFiles = plan.downloadEntries.size,
                downloadedBytes = if (block) 0 else 10,
                totalBytes = 10,
                bytesPerSecond = if (block) 0 else 1_000,
                estimatedRemainingMillis = if (block) null else 0,
            ),
        )
        return FakeDownloadSession(configuredResult, block).also { lastSession = it }
    }
}

private class FakeDownloadSession(
    result: DownloadSessionResult,
    block: Boolean,
) : DownloadSession {
    override val id: String = result.sessionId
    val cancelled = AtomicBoolean(false)
    private val result = CompletableDeferred<DownloadSessionResult>().apply {
        if (!block) complete(result)
    }

    override fun cancel() {
        cancelled.set(true)
        result.complete(
            DownloadSessionResult(
                sessionId = id,
                status = DownloadSessionStatus.CANCELLED,
                artifacts = emptyList(),
                downloadedBytes = 0,
                totalBytes = null,
                elapsedMillis = 0,
                averageBytesPerSecond = 0,
                failure = DownloadFailure(
                    DownloadFailureCode.CANCELLED,
                    "Cancelled.",
                    recoverable = true,
                ),
            ),
        )
    }

    override suspend fun await(): DownloadSessionResult = result.await()
}

private class FakeInstaller(
    private val configuredResult: InstallSessionResult,
) : InstallEngine {
    val starts = AtomicInteger()
    val resumes = AtomicInteger()
    val rollbackPerformed = AtomicBoolean(false)

    override fun start(
        plan: ResolvedInstallPlan,
        downloadSession: DownloadSession,
        destination: StorageProvider,
        progressListener: InstallProgressListener,
    ): InstallerSession {
        starts.incrementAndGet()
        progressListener.onProgress(
            InstallProgress(
                transactionId = configuredResult.transactionId,
                phase = if (rollbackPerformed.get()) {
                    InstallProgressPhase.ROLLING_BACK
                } else {
                    InstallProgressPhase.COMMITTING
                },
                currentPath = "mods/example.jar",
                completedFiles = configuredResult.installedFiles,
                totalFiles = plan.installOrder.size,
                processedBytes = configuredResult.installedBytes,
                totalBytes = 10,
            ),
        )
        return FakeInstallerSession(configuredResult)
    }

    override fun resume(
        transactionId: String,
        destination: StorageProvider,
        progressListener: InstallProgressListener,
    ): InstallerSession {
        resumes.incrementAndGet()
        return FakeInstallerSession(configuredResult.copy(transactionId = transactionId))
    }
}

private class FakeInstallerSession(
    private val result: InstallSessionResult,
) : InstallerSession {
    override val id: String = result.transactionId
    private val cancelled = AtomicBoolean(false)

    override fun cancel() {
        cancelled.set(true)
    }

    override suspend fun await(): InstallSessionResult =
        if (cancelled.get()) {
            InstallSessionResult(
                transactionId = id,
                status = InstallSessionStatus.CANCELLED,
                installedFiles = 0,
                installedBytes = 0,
                failure = InstallFailure(
                    InstallFailureCode.CANCELLED,
                    "Cancelled.",
                    recoverable = true,
                ),
            )
        } else {
            result
        }
}

private class FakeLauncher(
    private val result: LauncherResult,
) : LauncherProvider {
    val inspections = AtomicInteger()

    override suspend fun inspect(
        storage: StorageProvider,
        root: StoragePath,
    ): LauncherResult {
        inspections.incrementAndGet()
        return result
    }
}

private class FakeStorageProvider : StorageProvider {
    override val providerId: String = "fake-filesystem"

    override suspend fun metadata(path: StoragePath): StorageResult<StorageEntry> = unsupported(path)

    override suspend fun exists(path: StoragePath): StorageResult<Boolean> = unsupported(path)

    override suspend fun list(path: StoragePath): StorageResult<List<StorageEntry>> = unsupported(path)

    override suspend fun createDirectory(path: StoragePath): StorageResult<StorageEntry> =
        unsupported(path)

    override suspend fun openInput(
        path: StoragePath,
        bufferSize: Int,
    ): StorageResult<InputStream> = unsupported(path)

    override suspend fun openOutput(
        path: StoragePath,
        mimeType: String,
        append: Boolean,
        bufferSize: Int,
    ): StorageResult<OutputStream> = unsupported(path)

    override suspend fun replaceFile(
        path: StoragePath,
        mimeType: String,
        writer: suspend (OutputStream) -> Unit,
    ): StorageResult<FileReplaceResult> = unsupported(path)

    override suspend fun delete(
        path: StoragePath,
        recursive: Boolean,
        progressListener: StorageProgressListener,
    ): StorageResult<DeleteSummary> = unsupported(path)

    private fun <T> unsupported(path: StoragePath): StorageResult<T> = StorageResult.Failure(
        StorageError(
            StorageErrorCode.UNSUPPORTED_OPERATION,
            "Fake filesystem operation is not implemented.",
            path,
            recoverable = false,
        ),
    )
}

private fun validPlan(): InstallPlan {
    val hashes = MrPackHashes(
        sha1 = "0".repeat(40),
        sha512 = "1".repeat(128),
    )
    val download = DownloadEntry(
        path = "mods/example.jar",
        urls = listOf("https://example.invalid/example.jar"),
        hashes = hashes,
        sizeBytes = 10,
        clientRequirement = EnvironmentSupport.REQUIRED,
    )
    return InstallPlan(
        manifest = MrPackManifest(
            formatVersion = 1,
            game = "minecraft",
            versionId = "1.0.0",
            name = "Test Pack",
            files = listOf(
                MrPackFile(
                    path = download.path,
                    hashes = hashes,
                    downloads = download.urls,
                    fileSize = download.sizeBytes,
                ),
            ),
            dependencies = MrPackDependencies(
                minecraft = "1.21.1",
                fabricLoader = "0.16.10",
            ),
        ),
        minecraftVersion = "1.21.1",
        loaderType = LoaderType.FABRIC,
        loaderVersion = "0.16.10",
        modCount = 1,
        overrideFolders = emptyList(),
        downloadEntries = listOf(download),
        totalDownloadSize = 10,
        warnings = emptyList(),
        cacheDirectory = "/cache/parser",
    )
}

private fun resolvedPlan(): ResolvedInstallPlan {
    val source = validPlan()
    val hash = ResolvedHash(HashAlgorithm.SHA512, "1".repeat(128))
    val download = ResolvedDownloadEntry(
        destinationPath = "mods/example.jar",
        urls = listOf("https://example.invalid/example.jar"),
        hashes = source.downloadEntries.single().hashes,
        preferredHash = hash,
        sizeBytes = 10,
        required = true,
        fileType = ResolvedFileType.MOD,
        artifactKey = "sha512:${hash.value}",
    )
    return ResolvedInstallPlan(
        source = source,
        minecraftVersion = "1.21.1",
        loader = ResolvedLoader(LoaderType.FABRIC, "fabric-loader", "0.16.10"),
        downloadEntries = listOf(download),
        overrideFiles = emptyList(),
        installOrder = listOf(
            ResolvedInstallStep(
                order = 0,
                type = ResolvedInstallStepType.DOWNLOAD,
                destinationPath = download.destinationPath,
                sourceReference = download.artifactKey,
            ),
        ),
        totalDownloadSize = 10,
        warnings = emptyList(),
        recoverableErrors = emptyList(),
        fatalErrors = emptyList(),
    )
}

private fun successfulDownload(): DownloadSessionResult = DownloadSessionResult(
    sessionId = "download",
    status = DownloadSessionStatus.COMPLETED,
    artifacts = listOf(
        DownloadArtifactResult(
            artifactKey = resolvedPlan().downloadEntries.single().artifactKey,
            status = DownloadArtifactStatus.DOWNLOADED,
            cachePath = "/cache/download/example.jar",
            bytesDownloaded = 10,
            attempts = 1,
        ),
    ),
    downloadedBytes = 10,
    totalBytes = 10,
    elapsedMillis = 10,
    averageBytesPerSecond = 1_000,
)

private fun failedDownload(): DownloadSessionResult = DownloadSessionResult(
    sessionId = "download",
    status = DownloadSessionStatus.FAILED,
    artifacts = emptyList(),
    downloadedBytes = 0,
    totalBytes = 10,
    elapsedMillis = 10,
    averageBytesPerSecond = 0,
    failure = DownloadFailure(
        DownloadFailureCode.NETWORK_IO,
        "Network failed.",
        recoverable = true,
    ),
)

private fun successfulInstall(): InstallSessionResult = InstallSessionResult(
    transactionId = "transaction",
    status = InstallSessionStatus.COMPLETED,
    installedFiles = 1,
    installedBytes = 10,
)

private fun validLauncher(
    instance: LauncherInstance = TestInstance,
    version: LauncherVersion = TestVersion,
): LauncherResult = LauncherResult.Success(
    LauncherInfo(
        launcherId = "cslauncher",
        displayName = "CS Launcher",
        root = StoragePath.ROOT,
        instances = listOf(instance),
        versions = listOf(version),
        detectedDirectories = LauncherDirectory.entries.toSet(),
        capabilities = LauncherCapabilities(
            compatible = true,
            readOnly = true,
            canReadProfiles = true,
            canScanVersions = true,
            hasFabric = true,
            hasForge = false,
            hasNeoForge = false,
            canInstall = false,
            canCreateInstances = false,
            canEditProfiles = false,
        ),
        warnings = emptyList(),
        errors = emptyList(),
    ),
)

private val TestInstance = LauncherInstance(
    profileId = "fabric",
    name = "Fabric",
    lastVersionId = "fabric-loader-0.16.10-1.21.1",
    gameDirectory = null,
    profileType = "custom",
)

private val TestVersion = LauncherVersion(
    id = TestInstance.lastVersionId,
    minecraftVersion = "1.21.1",
    loader = LauncherLoader.FABRIC,
    loaderVersion = "0.16.10",
    path = StoragePath("versions/${TestInstance.lastVersionId}"),
)
