package com.modrith.downloader

import com.modrith.models.DownloadArtifactStatus
import com.modrith.models.DownloadConfiguration
import com.modrith.models.DownloadEntry
import com.modrith.models.DownloadFailureCode
import com.modrith.models.DownloadProgress
import com.modrith.models.DownloadRepository
import com.modrith.models.DownloadSessionStatus
import com.modrith.models.DownloadStateRecord
import com.modrith.models.EnvironmentSupport
import com.modrith.models.HashAlgorithm
import com.modrith.models.InstallPlan
import com.modrith.models.LoaderType
import com.modrith.models.MrPackDependencies
import com.modrith.models.MrPackFile
import com.modrith.models.MrPackHashes
import com.modrith.models.MrPackManifest
import com.modrith.models.ResolvedDownloadEntry
import com.modrith.models.ResolvedFileType
import com.modrith.models.ResolvedHash
import com.modrith.models.ResolvedInstallPlan
import com.modrith.models.ResolvedLoader
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpDownloadEngineTest {
    @Test
    fun successfulDownloadReportsProgressAndPersistsCompletion() = withTempDirectory { cache ->
        val content = "verified artifact".toByteArray()
        val interceptor = RecordingInterceptor { request -> response(request, content) }
        val repository = InMemoryDownloadRepository()
        val progress = mutableListOf<DownloadProgress>()
        val entry = entry("mods/success.jar", content)

        val result = engine(interceptor, repository).start(
            plan(entry),
            cache,
            configuration(),
        ) { update -> progress += update }.await()

        assertEquals(DownloadSessionStatus.COMPLETED, result.status)
        assertEquals(DownloadArtifactStatus.DOWNLOADED, result.artifacts.single().status)
        assertArrayEquals(
            content,
            Files.readAllBytes(File(result.artifacts.single().cachePath).toPath()),
        )
        assertEquals("Modrith-Test/1.0", interceptor.requests.single().header("User-Agent"))
        assertTrue(progress.any { it.downloadedBytes == content.size.toLong() })
        assertEquals(content.size.toLong(), result.totalBytes)
        assertEquals(content.size.toLong(), result.downloadedBytes)
        assertEquals(
            com.modrith.models.DownloadStateStatus.COMPLETED,
            repository.get(entry.artifactKey)?.status,
        )
    }

    @Test
    fun cancelledDownloadCancelsCallAndPreservesPartialForResume() = withTempDirectory { cache ->
        val content = "partial content that should survive cancellation".toByteArray()
        val entry = entry("mods/cancel.jar", content)
        val paths = entry.cachePaths(cache)
        Files.createDirectories(paths.temporaryPath.parent)
        Files.write(paths.temporaryPath, content.copyOfRange(0, 7))
        val interceptor = BlockingInterceptor()
        val session = engine(interceptor).start(plan(entry), cache, configuration())

        assertTrue(interceptor.started.await(5, TimeUnit.SECONDS))
        session.cancel()
        val result = session.await()

        assertEquals(DownloadSessionStatus.CANCELLED, result.status)
        assertEquals("bytes=7-", interceptor.request?.header("Range"))
        assertTrue(Files.isRegularFile(paths.temporaryPath))
        assertEquals(7L, Files.size(paths.temporaryPath))
    }

    @Test
    fun resumeDownloadUsesRangeAndAppendsPartialContent() = withTempDirectory { cache ->
        val content = "resume-me-completely".toByteArray()
        val prefixSize = 8
        val entry = entry("mods/resume.jar", content)
        val paths = entry.cachePaths(cache)
        Files.createDirectories(paths.temporaryPath.parent)
        Files.write(paths.temporaryPath, content.copyOfRange(0, prefixSize))
        val interceptor = RecordingInterceptor { request ->
            assertEquals("bytes=$prefixSize-", request.header("Range"))
            response(
                request = request,
                content = content.copyOfRange(prefixSize, content.size),
                code = 206,
                headers = mapOf(
                    "Content-Range" to "bytes $prefixSize-${content.lastIndex}/${content.size}",
                ),
            )
        }

        val result = engine(interceptor).start(plan(entry), cache, configuration()).await()

        assertEquals(DownloadSessionStatus.COMPLETED, result.status)
        assertArrayEquals(content, Files.readAllBytes(entry.cachePaths(cache).finalPath))
        assertFalse(Files.exists(paths.temporaryPath))
    }

    @Test
    fun corruptedHashFailsAndCleansTemporaryAndFinalFiles() = withTempDirectory { cache ->
        val expected = "expected".toByteArray()
        val entry = entry("mods/corrupt.jar", expected)
        val interceptor = RecordingInterceptor { request ->
            response(request, "corrupt!".toByteArray())
        }

        val result = engine(interceptor).start(plan(entry), cache, configuration()).await()

        assertEquals(DownloadSessionStatus.FAILED, result.status)
        assertEquals(DownloadFailureCode.HASH_MISMATCH, result.failure?.code)
        assertFalse(Files.exists(entry.cachePaths(cache).temporaryPath))
        assertFalse(Files.exists(entry.cachePaths(cache).finalPath))
    }

    @Test
    fun timeoutReturnsStructuredFailure() = withTempDirectory { cache ->
        val content = "timeout".toByteArray()
        val interceptor = RecordingInterceptor { throw SocketTimeoutException("timed out") }

        val result = engine(interceptor).start(
            plan(entry("mods/timeout.jar", content)),
            cache,
            configuration(),
        ).await()

        assertEquals(DownloadSessionStatus.FAILED, result.status)
        assertEquals(DownloadFailureCode.NETWORK_TIMEOUT, result.failure?.code)
        assertTrue(result.failure?.recoverable == true)
    }

    @Test
    fun retryUsesExponentialPolicyAndEventuallySucceeds() = withTempDirectory { cache ->
        val content = "retry succeeds".toByteArray()
        val requests = AtomicInteger()
        val delays = mutableListOf<Long>()
        val interceptor = RecordingInterceptor { request ->
            if (requests.incrementAndGet() == 1) {
                response(request, ByteArray(0), code = 503)
            } else {
                response(request, content)
            }
        }
        val engine = engine(
            interceptor = interceptor,
            retryDelay = RetryDelay { delay -> delays += delay },
        )

        val result = engine.start(
            plan(entry("mods/retry.jar", content)),
            cache,
            configuration(maxRetries = 1, initialBackoffMillis = 25),
        ).await()

        assertEquals(DownloadSessionStatus.COMPLETED, result.status)
        assertEquals(2, requests.get())
        assertEquals(listOf(25L), delays)
        assertEquals(2, result.artifacts.single().attempts)
    }

    @Test
    fun parallelDownloadsHonorConfiguredWorkerCount() = withTempDirectory { cache ->
        val contents = (0 until 3).associate { index ->
            "/artifact-$index" to "parallel-$index".toByteArray()
        }
        val interceptor = ConcurrentInterceptor(contents, expectedConcurrentRequests = 3)
        val entries = contents.map { (path, content) ->
            entry(
                destinationPath = "mods/${path.removePrefix("/")}.jar",
                content = content,
                url = "https://downloads.test$path",
            )
        }

        val result = engine(interceptor).start(
            plan(*entries.toTypedArray()),
            cache,
            configuration(workerCount = 3),
        ).await()

        assertEquals(DownloadSessionStatus.COMPLETED, result.status)
        assertEquals(3, result.artifacts.size)
        assertEquals(3, interceptor.maximumConcurrent.get())
    }

    @Test
    fun duplicateRequestsAreDownloadedOnlyOnce() = withTempDirectory { cache ->
        val content = "deduplicated".toByteArray()
        val entry = entry("mods/duplicate.jar", content)
        val interceptor = RecordingInterceptor { request -> response(request, content) }

        val result = engine(interceptor).start(
            plan(entry, entry.copy(destinationPath = "mods/copy.jar")),
            cache,
            configuration(),
        ).await()

        assertEquals(DownloadSessionStatus.COMPLETED, result.status)
        assertEquals(1, result.artifacts.size)
        assertEquals(1, interceptor.requests.size)
    }

    @Test
    fun alreadyExistingVerifiedFileSkipsNetwork() = withTempDirectory { cache ->
        val content = "already cached".toByteArray()
        val entry = entry("mods/cached.jar", content)
        val paths = entry.cachePaths(cache)
        Files.createDirectories(paths.finalPath.parent)
        Files.write(paths.finalPath, content)
        val interceptor = RecordingInterceptor { error("Network must not be used") }

        val result = engine(interceptor).start(plan(entry), cache, configuration()).await()

        assertEquals(DownloadSessionStatus.COMPLETED, result.status)
        assertEquals(DownloadArtifactStatus.SKIPPED_VERIFIED, result.artifacts.single().status)
        assertTrue(interceptor.requests.isEmpty())
        assertArrayEquals(content, Files.readAllBytes(paths.finalPath))
    }

    private fun engine(
        interceptor: Interceptor,
        repository: DownloadRepository = InMemoryDownloadRepository(),
        retryDelay: RetryDelay = RetryDelay { },
    ): OkHttpDownloadEngine = OkHttpDownloadEngine(
        baseClient = OkHttpClient.Builder().addInterceptor(interceptor).build(),
        repository = repository,
        dispatcher = Dispatchers.IO,
        retryDelay = retryDelay,
        diskSpaceProvider = DiskSpaceProvider { Long.MAX_VALUE },
    )

    private fun configuration(
        workerCount: Int = 2,
        maxRetries: Int = 0,
        initialBackoffMillis: Long = 0,
    ) = DownloadConfiguration(
        workerCount = workerCount,
        maxRetries = maxRetries,
        initialBackoffMillis = initialBackoffMillis,
        maxBackoffMillis = initialBackoffMillis,
        connectTimeoutMillis = 1_000,
        readTimeoutMillis = 1_000,
        callTimeoutMillis = 2_000,
        progressIntervalMillis = 0,
        diskSpaceReserveBytes = 0,
        userAgent = "Modrith-Test/1.0",
    )

    private fun entry(
        destinationPath: String,
        content: ByteArray,
        url: String = "https://downloads.test/artifact",
    ): ResolvedDownloadEntry {
        val sha1 = content.digest("SHA-1")
        val sha512 = content.digest("SHA-512")
        return ResolvedDownloadEntry(
            destinationPath = destinationPath,
            urls = listOf(url),
            hashes = MrPackHashes(sha1 = sha1, sha512 = sha512),
            preferredHash = ResolvedHash(HashAlgorithm.SHA512, sha512),
            sizeBytes = content.size.toLong(),
            required = true,
            fileType = ResolvedFileType.MOD,
            artifactKey = "sha512:$sha512",
        )
    }

    private fun plan(vararg entries: ResolvedDownloadEntry): ResolvedInstallPlan {
        val dependencies = MrPackDependencies(
            minecraft = "1.21.1",
            fabricLoader = "0.16.10",
        )
        val sourceDownloads = entries.map { entry ->
            DownloadEntry(
                path = entry.destinationPath,
                urls = entry.urls,
                hashes = entry.hashes,
                sizeBytes = entry.sizeBytes,
                clientRequirement = EnvironmentSupport.REQUIRED,
            )
        }
        val source = InstallPlan(
            manifest = MrPackManifest(
                formatVersion = 1,
                game = "minecraft",
                versionId = "test",
                name = "Downloader Test",
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
            modCount = entries.size,
            overrideFolders = emptyList(),
            downloadEntries = sourceDownloads,
            totalDownloadSize = entries.sumOf { requireNotNull(it.sizeBytes) },
            warnings = emptyList(),
            cacheDirectory = "/unused-parser-cache",
        )
        return ResolvedInstallPlan(
            source = source,
            minecraftVersion = "1.21.1",
            loader = ResolvedLoader(LoaderType.FABRIC, "fabric-loader", "0.16.10"),
            downloadEntries = entries.toList(),
            overrideFiles = emptyList(),
            installOrder = emptyList(),
            totalDownloadSize = entries.sumOf { requireNotNull(it.sizeBytes) },
            warnings = emptyList(),
            recoverableErrors = emptyList(),
            fatalErrors = emptyList(),
        )
    }

    private fun response(
        request: Request,
        content: ByteArray,
        code: Int = 200,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test response")
            .body(content.toResponseBody("application/octet-stream".toMediaType()))
        headers.forEach(builder::header)
        return builder.build()
    }

    private fun ByteArray.digest(algorithm: String): String =
        MessageDigest.getInstance(algorithm).digest(this).joinToString("") { "%02x".format(it) }

    private fun <T> withTempDirectory(block: suspend (Path) -> T): T = runBlocking {
        val root = Files.createTempDirectory("downloader-test")
        try {
            block(root)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}

private class RecordingInterceptor(
    private val responder: (Request) -> Response,
) : Interceptor {
    val requests = mutableListOf<Request>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        synchronized(requests) {
            requests += request
        }
        return responder(request)
    }
}

private class BlockingInterceptor : Interceptor {
    val started = CountDownLatch(1)

    @Volatile
    var request: Request? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        request = chain.request()
        started.countDown()
        while (!chain.call().isCanceled()) {
            Thread.sleep(5)
        }
        throw IOException("cancelled")
    }
}

private class ConcurrentInterceptor(
    private val contents: Map<String, ByteArray>,
    expectedConcurrentRequests: Int,
) : Interceptor {
    private val allStarted = CountDownLatch(expectedConcurrentRequests)
    private val active = AtomicInteger()
    val maximumConcurrent = AtomicInteger()

    override fun intercept(chain: Interceptor.Chain): Response {
        val current = active.incrementAndGet()
        maximumConcurrent.accumulateAndGet(current, ::maxOf)
        allStarted.countDown()
        check(allStarted.await(5, TimeUnit.SECONDS)) {
            "Expected requests were not started concurrently."
        }
        return try {
            val request = chain.request()
            val content = checkNotNull(contents[request.url.encodedPath])
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("test response")
                .body(content.toResponseBody())
                .build()
        } finally {
            active.decrementAndGet()
        }
    }
}

private class InMemoryDownloadRepository : DownloadRepository {
    private val records = ConcurrentHashMap<String, DownloadStateRecord>()

    override suspend fun get(artifactKey: String): DownloadStateRecord? = records[artifactKey]

    override suspend fun getSession(sessionId: String): List<DownloadStateRecord> =
        records.values.filter { it.sessionId == sessionId }.sortedBy { it.artifactKey }

    override suspend fun upsert(record: DownloadStateRecord) {
        records[record.artifactKey] = record
    }

    override suspend fun delete(artifactKey: String) {
        records.remove(artifactKey)
    }
}
