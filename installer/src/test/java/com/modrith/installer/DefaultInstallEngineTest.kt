package com.modrith.installer

import com.modrith.filesystem.CacheProvider
import com.modrith.filesystem.DeleteSummary
import com.modrith.filesystem.FileReplaceResult
import com.modrith.filesystem.StorageError
import com.modrith.filesystem.StorageErrorCode
import com.modrith.filesystem.StoragePath
import com.modrith.filesystem.StorageProgressListener
import com.modrith.filesystem.StorageProvider
import com.modrith.filesystem.StorageResult
import com.modrith.models.DownloadArtifactResult
import com.modrith.models.DownloadArtifactStatus
import com.modrith.models.DownloadEntry
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
import com.modrith.models.OverrideFile
import com.modrith.models.OverrideFolder
import com.modrith.models.ResolvedDownloadEntry
import com.modrith.models.ResolvedFileType
import com.modrith.models.ResolvedHash
import com.modrith.models.ResolvedInstallPlan
import com.modrith.models.ResolvedLoader
import com.modrith.models.ResolvedOverrideFile
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultInstallEngineTest {
    @Test
    fun successfulInstallCopiesDownloadsAndEveryOverride() = withRoots { root ->
        val fixture = fixture(
            root,
            downloads = linkedMapOf("mods/example.jar" to "mod-content".toByteArray()),
            overrides = linkedMapOf(
                "config/settings.json" to """{"enabled":true}""".toByteArray(),
                "options.txt" to "fov:90".toByteArray(),
            ),
        )
        val destinationRoot = root.resolve("destination")
        val destination = CacheProvider(destinationRoot)
        val repository = InMemoryInstallRepository()
        val progress = mutableListOf<InstallProgress>()

        val result = engine(repository).start(
            fixture.plan,
            fixture.downloadSession,
            destination,
        ) { update -> progress += update }.await()

        assertEquals(InstallSessionStatus.COMPLETED, result.status)
        assertArrayEquals(
            "mod-content".toByteArray(),
            Files.readAllBytes(destinationRoot.resolve("mods/example.jar")),
        )
        assertArrayEquals(
            """{"enabled":true}""".toByteArray(),
            Files.readAllBytes(destinationRoot.resolve("config/settings.json")),
        )
        assertArrayEquals(
            "fov:90".toByteArray(),
            Files.readAllBytes(destinationRoot.resolve("options.txt")),
        )
        assertTrue(progress.any { it.phase == InstallProgressPhase.COMMITTING })
        assertTrue(progress.any { it.phase == InstallProgressPhase.CLEANING_UP })
        assertFalse(Files.exists(destinationRoot.resolve(".modrith-installer/work/${result.transactionId}")))
        val transaction = repository.get(result.transactionId).success()
        assertEquals(InstallTransactionStatus.COMPLETED, transaction.status)
        assertTrue(transaction.log.any { it.event == "install.completed" })
    }

    @Test
    fun resumeAfterInterruptionCompletesCheckpointedCommit() = withRoots { root ->
        val destinationRoot = root.resolve("destination")
        val destination = CacheProvider(destinationRoot)
        val repository = InMemoryInstallRepository()
        val transactionId = "resume-transaction"
        val old = "old-content".toByteArray()
        val updated = "updated-content".toByteArray()
        val stagePath = StoragePath(
            ".modrith-installer/work/$transactionId/stage/mods/example.jar",
        )
        val backupPath = StoragePath(
            ".modrith-installer/work/$transactionId/backup/mods/example.jar",
        )
        destination.replaceFile(StoragePath("mods/example.jar")) { it.write(updated) }.success()
        destination.replaceFile(stagePath) { it.write(updated) }.success()
        destination.replaceFile(backupPath) { it.write(old) }.success()
        val now = System.currentTimeMillis()
        repository.save(
            InstallTransaction(
                id = transactionId,
                destinationProviderId = destination.providerId,
                status = InstallTransactionStatus.ACTIVE,
                checkpoint = InstallCheckpoint(InstallCheckpointPhase.COMMITTING, 0),
                operations = listOf(
                    InstallFileOperation(
                        sourceType = InstallSourceType.DOWNLOAD,
                        sourcePath = root.resolve("unused").toString(),
                        destinationPath = "mods/example.jar",
                        expectedSha1 = updated.digest("SHA-1"),
                        expectedSha512 = updated.digest("SHA-512"),
                        expectedSizeBytes = updated.size.toLong(),
                        stagePath = stagePath.value,
                        backupPath = backupPath.value,
                        state = InstallFileState.BACKED_UP,
                        originalExisted = true,
                        originalSha512 = old.digest("SHA-512"),
                    ),
                ),
                log = emptyList(),
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        ).success()

        val result = engine(repository).resume(transactionId, destination).await()

        assertEquals(InstallSessionStatus.COMPLETED, result.status)
        assertArrayEquals(updated, Files.readAllBytes(destinationRoot.resolve("mods/example.jar")))
        assertEquals(
            InstallTransactionStatus.COMPLETED,
            repository.get(transactionId).success().status,
        )
    }

    @Test
    fun rollbackAfterFailureRemovesNewFiles() = withRoots { root ->
        val fixture = fixture(
            root,
            downloads = linkedMapOf(
                "mods/a.jar" to "first".toByteArray(),
                "mods/b.jar" to "second".toByteArray(),
            ),
        )
        val destinationRoot = root.resolve("destination")
        val base = CacheProvider(destinationRoot)
        val destination = FailingReplaceProvider(
            base,
            failPath = "mods/b.jar",
            errorCode = StorageErrorCode.IO_ERROR,
        )

        val result = engine(InMemoryInstallRepository()).start(
            fixture.plan,
            fixture.downloadSession,
            destination,
        ).await()

        assertEquals(InstallSessionStatus.FAILED, result.status)
        assertEquals(InstallFailureCode.DESTINATION_IO, result.failure?.code)
        assertFalse(Files.exists(destinationRoot.resolve("mods/a.jar")))
        assertFalse(Files.exists(destinationRoot.resolve("mods/b.jar")))
    }

    @Test
    fun backupRestorationRestoresPreviousFilesOnFailure() = withRoots { root ->
        val fixture = fixture(
            root,
            downloads = linkedMapOf(
                "mods/a.jar" to "new-a".toByteArray(),
                "mods/b.jar" to "new-b".toByteArray(),
            ),
        )
        val destinationRoot = root.resolve("destination")
        val base = CacheProvider(destinationRoot)
        val oldA = "old-a".toByteArray()
        val oldB = "old-b".toByteArray()
        base.replaceFile(StoragePath("mods/a.jar")) { it.write(oldA) }.success()
        base.replaceFile(StoragePath("mods/b.jar")) { it.write(oldB) }.success()
        val destination = FailingReplaceProvider(
            base,
            failPath = "mods/b.jar",
            errorCode = StorageErrorCode.IO_ERROR,
        )

        val result = engine(InMemoryInstallRepository()).start(
            fixture.plan,
            fixture.downloadSession,
            destination,
        ).await()

        assertEquals(InstallSessionStatus.FAILED, result.status)
        assertArrayEquals(oldA, Files.readAllBytes(destinationRoot.resolve("mods/a.jar")))
        assertArrayEquals(oldB, Files.readAllBytes(destinationRoot.resolve("mods/b.jar")))
    }

    @Test
    fun missingDownloadFailsBeforeDestinationChanges() = withRoots { root ->
        val fixture = fixture(
            root,
            downloads = linkedMapOf("mods/missing.jar" to "expected".toByteArray()),
            omitDownloadResults = true,
        )
        val destinationRoot = root.resolve("destination")

        val result = engine(InMemoryInstallRepository()).start(
            fixture.plan,
            fixture.downloadSession,
            CacheProvider(destinationRoot),
        ).await()

        assertEquals(InstallSessionStatus.FAILED, result.status)
        assertEquals(InstallFailureCode.MISSING_DOWNLOAD, result.failure?.code)
        assertFalse(Files.exists(destinationRoot.resolve("mods/missing.jar")))
    }

    @Test
    fun hashMismatchFailsBeforeDestinationChanges() = withRoots { root ->
        val fixture = fixture(
            root,
            downloads = linkedMapOf("mods/hash.jar" to "expected".toByteArray()),
            corruptDownloadCache = true,
        )
        val destinationRoot = root.resolve("destination")

        val result = engine(InMemoryInstallRepository()).start(
            fixture.plan,
            fixture.downloadSession,
            CacheProvider(destinationRoot),
        ).await()

        assertEquals(InstallSessionStatus.FAILED, result.status)
        assertEquals(InstallFailureCode.HASH_MISMATCH, result.failure?.code)
        assertFalse(Files.exists(destinationRoot.resolve("mods/hash.jar")))
    }

    @Test
    fun diskFullFailsBeforeStaging() = withRoots { root ->
        val fixture = fixture(
            root,
            downloads = linkedMapOf("mods/large.jar" to ByteArray(32) { 7 }),
        )
        val destinationRoot = root.resolve("destination")
        val engine = engine(
            InMemoryInstallRepository(),
            diskSpaceChecker = InstallDiskSpaceChecker { _, _ -> false },
        )

        val result = engine.start(
            fixture.plan,
            fixture.downloadSession,
            CacheProvider(destinationRoot),
        ).await()

        assertEquals(InstallSessionStatus.FAILED, result.status)
        assertEquals(InstallFailureCode.DISK_FULL, result.failure?.code)
        assertFalse(Files.exists(destinationRoot.resolve("mods/large.jar")))
    }

    @Test
    fun permissionDeniedReturnsStructuredFailureAndRollsBack() = withRoots { root ->
        val fixture = fixture(
            root,
            downloads = linkedMapOf("mods/denied.jar" to "denied".toByteArray()),
        )
        val destinationRoot = root.resolve("destination")
        val destination = FailingReplaceProvider(
            CacheProvider(destinationRoot),
            failPath = "mods/denied.jar",
            errorCode = StorageErrorCode.WRITE_PERMISSION_DENIED,
        )

        val result = engine(InMemoryInstallRepository()).start(
            fixture.plan,
            fixture.downloadSession,
            destination,
        ).await()

        assertEquals(InstallSessionStatus.FAILED, result.status)
        assertEquals(InstallFailureCode.PERMISSION_DENIED, result.failure?.code)
        assertFalse(Files.exists(destinationRoot.resolve("mods/denied.jar")))
    }

    @Test
    fun unexpectedProviderExceptionRetainsExactDebugDetails() = withRoots { root ->
        val fixture = fixture(
            root,
            downloads = linkedMapOf("mods/provider-failure.jar" to "content".toByteArray()),
        )
        val destination = ThrowingReplaceProvider(CacheProvider(root.resolve("destination")))

        val result = engine(
            repository = InMemoryInstallRepository(),
            includeExceptionDetails = true,
        ).start(
            fixture.plan,
            fixture.downloadSession,
            destination,
        ).await()

        assertEquals(InstallSessionStatus.FAILED, result.status)
        assertEquals(InstallFailureCode.INTERNAL, result.failure?.code)
        assertEquals(
            IllegalStateException::class.qualifiedName,
            result.failure?.details?.get("exceptionClass"),
        )
        assertEquals("SAF provider rename failed.", result.failure?.details?.get("exceptionMessage"))
        assertEquals("stage_copy", result.failure?.details?.get("failingStep"))
        assertEquals(
            "mods/provider-failure.jar",
            result.failure?.details?.get("failingPath"),
        )
        assertTrue(result.failure?.details?.get("stackTrace")?.contains("IllegalStateException") == true)
    }

    @Test
    fun cancellationRestoresExistingFileAndCleansTransactionData() = withRoots { root ->
        val fixture = fixture(
            root,
            downloads = linkedMapOf("mods/cancel.jar" to "new-value".toByteArray()),
        )
        val destinationRoot = root.resolve("destination")
        val base = CacheProvider(destinationRoot)
        val old = "old-value".toByteArray()
        base.replaceFile(StoragePath("mods/cancel.jar")) { it.write(old) }.success()
        val blocking = BlockingReplaceProvider(base, "mods/cancel.jar")
        val repository = InMemoryInstallRepository()
        val session = engine(repository).start(
            fixture.plan,
            fixture.downloadSession,
            blocking,
        )

        assertTrue(blocking.started.await(5, TimeUnit.SECONDS))
        session.cancel()
        val result = session.await()

        assertEquals(InstallSessionStatus.CANCELLED, result.status)
        assertArrayEquals(old, Files.readAllBytes(destinationRoot.resolve("mods/cancel.jar")))
        val transaction = repository.get(session.id).success()
        assertEquals(InstallTransactionStatus.ROLLED_BACK, transaction.status)
        assertFalse(Files.exists(destinationRoot.resolve(".modrith-installer/work/${session.id}")))
    }

    @Test
    fun existingInstallationUpdateReplacesFilesAfterBackup() = withRoots { root ->
        val fixture = fixture(
            root,
            downloads = linkedMapOf("mods/update.jar" to "version-two".toByteArray()),
        )
        val destinationRoot = root.resolve("destination")
        val destination = CacheProvider(destinationRoot)
        destination.replaceFile(StoragePath("mods/update.jar")) {
            it.write("version-one".toByteArray())
        }.success()
        val repository = InMemoryInstallRepository()

        val result = engine(repository).start(
            fixture.plan,
            fixture.downloadSession,
            destination,
        ).await()

        assertEquals(InstallSessionStatus.COMPLETED, result.status)
        assertArrayEquals(
            "version-two".toByteArray(),
            Files.readAllBytes(destinationRoot.resolve("mods/update.jar")),
        )
        val transaction = repository.get(result.transactionId).success()
        assertTrue(transaction.log.any { it.event == "install.file_backed_up" })
        assertFalse(Files.exists(destinationRoot.resolve(".modrith-installer/work/${result.transactionId}")))
    }

    private fun engine(
        repository: InstallRepository,
        diskSpaceChecker: InstallDiskSpaceChecker = InstallDiskSpaceChecker { _, _ -> true },
        includeExceptionDetails: Boolean = false,
    ) = DefaultInstallEngine(
        repository = repository,
        dispatcher = Dispatchers.IO,
        diskSpaceChecker = diskSpaceChecker,
        includeExceptionDetails = includeExceptionDetails,
    )

    private fun fixture(
        root: Path,
        downloads: LinkedHashMap<String, ByteArray>,
        overrides: LinkedHashMap<String, ByteArray> = linkedMapOf(),
        omitDownloadResults: Boolean = false,
        corruptDownloadCache: Boolean = false,
    ): Fixture {
        val downloadRoot = root.resolve("download-cache")
        val parserRoot = root.resolve("parser-cache")
        Files.createDirectories(downloadRoot)
        Files.createDirectories(parserRoot)

        val resolvedDownloads = mutableListOf<ResolvedDownloadEntry>()
        val sourceDownloads = mutableListOf<DownloadEntry>()
        val artifactResults = mutableListOf<DownloadArtifactResult>()
        downloads.entries.forEachIndexed { index, (destinationPath, content) ->
            val sha1 = content.digest("SHA-1")
            val sha512 = content.digest("SHA-512")
            val artifactKey = "sha512:$sha512"
            val cachePath = downloadRoot.resolve("artifact-$index")
            val cachedContent = if (corruptDownloadCache) {
                content.copyOf().also { bytes ->
                    if (bytes.isNotEmpty()) bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
                }
            } else {
                content
            }
            Files.write(
                cachePath,
                cachedContent,
            )
            val hashes = MrPackHashes(sha1 = sha1, sha512 = sha512)
            resolvedDownloads += ResolvedDownloadEntry(
                destinationPath = destinationPath,
                urls = listOf("https://downloads.test/$index"),
                hashes = hashes,
                preferredHash = ResolvedHash(HashAlgorithm.SHA512, sha512),
                sizeBytes = content.size.toLong(),
                required = true,
                fileType = ResolvedFileType.MOD,
                artifactKey = artifactKey,
            )
            sourceDownloads += DownloadEntry(
                path = destinationPath,
                urls = listOf("https://downloads.test/$index"),
                hashes = hashes,
                sizeBytes = content.size.toLong(),
                clientRequirement = EnvironmentSupport.REQUIRED,
            )
            if (!omitDownloadResults) {
                artifactResults += DownloadArtifactResult(
                    artifactKey = artifactKey,
                    status = DownloadArtifactStatus.DOWNLOADED,
                    cachePath = cachePath.toString(),
                    bytesDownloaded = content.size.toLong(),
                    attempts = 1,
                )
            }
        }

        val overrideFiles = overrides.map { (destinationPath, content) ->
            val sourcePath = "overrides/$destinationPath"
            val path = parserRoot.resolve(sourcePath)
            Files.createDirectories(path.parent)
            Files.write(path, content)
            ResolvedOverrideFile(
                sourceRoot = "overrides",
                sourcePath = sourcePath,
                destinationPath = destinationPath,
                sizeBytes = content.size.toLong(),
            )
        }
        val dependencies = MrPackDependencies(
            minecraft = "1.21.1",
            fabricLoader = "0.16.10",
        )
        val source = InstallPlan(
            manifest = MrPackManifest(
                formatVersion = 1,
                game = "minecraft",
                versionId = "test",
                name = "Installer Test",
                files = sourceDownloads.map { download ->
                    MrPackFile(
                        path = download.path,
                        hashes = download.hashes,
                        downloads = download.urls,
                        fileSize = download.sizeBytes,
                    )
                },
                dependencies = dependencies,
            ),
            minecraftVersion = "1.21.1",
            loaderType = LoaderType.FABRIC,
            loaderVersion = "0.16.10",
            modCount = downloads.size,
            overrideFolders = if (overrides.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    OverrideFolder(
                        name = "overrides",
                        extractedPath = parserRoot.resolve("overrides").toString(),
                        files = overrideFiles.map {
                            OverrideFile(
                                archivePath = it.sourcePath,
                                relativePath = it.destinationPath,
                                sizeBytes = it.sizeBytes,
                            )
                        },
                    ),
                )
            },
            downloadEntries = sourceDownloads,
            totalDownloadSize = sourceDownloads.sumOf { requireNotNull(it.sizeBytes) },
            warnings = emptyList(),
            cacheDirectory = parserRoot.toString(),
        )
        val plan = ResolvedInstallPlan(
            source = source,
            minecraftVersion = "1.21.1",
            loader = ResolvedLoader(LoaderType.FABRIC, "fabric-loader", "0.16.10"),
            downloadEntries = resolvedDownloads,
            overrideFiles = overrideFiles,
            installOrder = emptyList(),
            totalDownloadSize = resolvedDownloads.sumOf { requireNotNull(it.sizeBytes) },
            warnings = emptyList(),
            recoverableErrors = emptyList(),
            fatalErrors = emptyList(),
        )
        val downloadResult = DownloadSessionResult(
            sessionId = "download-session",
            status = DownloadSessionStatus.COMPLETED,
            artifacts = artifactResults,
            downloadedBytes = artifactResults.sumOf(DownloadArtifactResult::bytesDownloaded),
            totalBytes = resolvedDownloads.sumOf { requireNotNull(it.sizeBytes) },
            elapsedMillis = 1,
            averageBytesPerSecond = 1,
        )
        return Fixture(plan, CompletedDownloadSession(downloadResult))
    }

    private fun <T> withRoots(block: suspend (Path) -> T): T = runBlocking {
        val root = Files.createTempDirectory("installer-test")
        try {
            block(root)
        } finally {
            if (Files.exists(root)) {
                Files.walk(root).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
        }
    }

    private fun ByteArray.digest(algorithm: String): String =
        MessageDigest.getInstance(algorithm).digest(this).joinToString("") { "%02x".format(it) }

    private fun <T> StorageResult<T>.success(): T =
        (this as? StorageResult.Success<T>)?.value ?: error("Expected success, got $this")

    private fun <T> InstallRepositoryResult<T>.success(): T =
        (this as? InstallRepositoryResult.Success<T>)?.value
            ?: error("Expected repository success, got $this")

    private data class Fixture(
        val plan: ResolvedInstallPlan,
        val downloadSession: DownloadSession,
    )
}

private class CompletedDownloadSession(
    private val result: DownloadSessionResult,
) : DownloadSession {
    override val id: String = result.sessionId

    override fun cancel() = Unit

    override suspend fun await(): DownloadSessionResult = result
}

private class InMemoryInstallRepository : InstallRepository {
    private val transactions = ConcurrentHashMap<String, InstallTransaction>()

    override suspend fun get(transactionId: String): InstallRepositoryResult<InstallTransaction> =
        transactions[transactionId]?.let(InstallRepositoryResult<InstallTransaction>::Success)
            ?: InstallRepositoryResult.Failure(
                InstallFailure(
                    InstallFailureCode.TRANSACTION_NOT_FOUND,
                    "Transaction not found.",
                    recoverable = false,
                ),
            )

    override suspend fun save(
        transaction: InstallTransaction,
    ): InstallRepositoryResult<Unit> {
        transactions[transaction.id] = transaction
        return InstallRepositoryResult.Success(Unit)
    }

    override suspend fun pending(): InstallRepositoryResult<List<InstallTransaction>> =
        InstallRepositoryResult.Success(
            transactions.values
                .filter { it.status == InstallTransactionStatus.ACTIVE }
                .sortedBy(InstallTransaction::createdAtEpochMillis),
        )

    override suspend fun delete(transactionId: String): InstallRepositoryResult<Unit> {
        transactions.remove(transactionId)
        return InstallRepositoryResult.Success(Unit)
    }
}

private class FailingReplaceProvider(
    private val delegate: StorageProvider,
    private val failPath: String,
    private val errorCode: StorageErrorCode,
) : StorageProvider by delegate {
    private val failed = AtomicBoolean(false)

    override suspend fun replaceFile(
        path: StoragePath,
        mimeType: String,
        writer: suspend (OutputStream) -> Unit,
    ): StorageResult<FileReplaceResult> {
        if (path.value == failPath && failed.compareAndSet(false, true)) {
            return StorageResult.Failure(
                StorageError(
                    errorCode,
                    "Injected destination failure.",
                    path,
                    recoverable = true,
                ),
            )
        }
        return delegate.replaceFile(path, mimeType, writer)
    }
}

private class BlockingReplaceProvider(
    private val delegate: StorageProvider,
    private val blockedPath: String,
) : StorageProvider by delegate {
    val started = CountDownLatch(1)
    private val blocked = AtomicBoolean(false)

    override suspend fun replaceFile(
        path: StoragePath,
        mimeType: String,
        writer: suspend (OutputStream) -> Unit,
    ): StorageResult<FileReplaceResult> {
        if (path.value == blockedPath && blocked.compareAndSet(false, true)) {
            started.countDown()
            delay(Long.MAX_VALUE)
        }
        return delegate.replaceFile(path, mimeType, writer)
    }
}

private class ThrowingReplaceProvider(
    private val delegate: StorageProvider,
) : StorageProvider by delegate {
    override suspend fun replaceFile(
        path: StoragePath,
        mimeType: String,
        writer: suspend (OutputStream) -> Unit,
    ): StorageResult<FileReplaceResult> {
        throw IllegalStateException("SAF provider rename failed.")
    }
}
