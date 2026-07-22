package com.modrith.downloader

import com.modrith.downloader.logging.DownloadLogger
import com.modrith.downloader.logging.JvmDownloadLogger
import com.modrith.models.DownloadArtifactResult
import com.modrith.models.DownloadArtifactStatus
import com.modrith.models.DownloadConfiguration
import com.modrith.models.DownloadFailure
import com.modrith.models.DownloadFailureCode
import com.modrith.models.DownloadRepository
import com.modrith.models.DownloadStateRecord
import com.modrith.models.DownloadStateStatus
import com.modrith.models.HashAlgorithm
import com.modrith.models.ResolvedDownloadEntry
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class DownloadWorker(
    private val client: OkHttpClient,
    private val repository: DownloadRepository,
    private val retryDelay: RetryDelay = CoroutineRetryDelay,
    private val logger: DownloadLogger = JvmDownloadLogger(),
) {
    internal suspend fun download(
        sessionId: String,
        entry: ResolvedDownloadEntry,
        cacheRoot: Path,
        configuration: DownloadConfiguration,
        progress: DownloadProgressTracker,
    ): DownloadArtifactResult {
        val paths = entry.cachePaths(cacheRoot)
        Files.createDirectories(paths.finalPath.parent)

        if (Files.isRegularFile(paths.finalPath)) {
            if (verifyFile(paths.finalPath, entry)) {
                Files.deleteIfExists(paths.temporaryPath)
                val size = Files.size(paths.finalPath)
                progress.startArtifact(entry.artifactKey, size)
                progress.complete(entry.artifactKey, size)
                persist(
                    sessionId,
                    entry,
                    paths,
                    DownloadStateStatus.COMPLETED,
                    size,
                    null,
                    0,
                    null,
                )
                logger.info(
                    "download.skipped_verified",
                    mapOf("artifactKey" to entry.artifactKey, "bytes" to size),
                )
                return DownloadArtifactResult(
                    artifactKey = entry.artifactKey,
                    status = DownloadArtifactStatus.SKIPPED_VERIFIED,
                    cachePath = paths.finalPath.toString(),
                    bytesDownloaded = size,
                    attempts = 0,
                )
            }
            Files.deleteIfExists(paths.finalPath)
        }

        var existingBytes = partialSize(paths.temporaryPath, entry.sizeBytes)
        progress.startArtifact(entry.artifactKey, existingBytes)
        persist(
            sessionId,
            entry,
            paths,
            DownloadStateStatus.QUEUED,
            existingBytes,
            null,
            0,
            null,
        )

        var attempts = 0
        var lastFailure: DownloadFailure? = null
        try {
            for (round in 0..configuration.maxRetries) {
                for (url in entry.urls) {
                    currentCoroutineContext().ensureActive()
                    attempts += 1
                    try {
                        existingBytes = partialSize(paths.temporaryPath, entry.sizeBytes)
                        performAttempt(
                            sessionId = sessionId,
                            entry = entry,
                            paths = paths,
                            url = url,
                            attempt = attempts,
                            initialBytes = existingBytes,
                            configuration = configuration,
                            progress = progress,
                        )
                        val finalSize = Files.size(paths.finalPath)
                        progress.complete(entry.artifactKey, finalSize)
                        logger.info(
                            "download.completed",
                            mapOf(
                                "artifactKey" to entry.artifactKey,
                                "bytes" to finalSize,
                                "attempts" to attempts,
                            ),
                        )
                        return DownloadArtifactResult(
                            artifactKey = entry.artifactKey,
                            status = DownloadArtifactStatus.DOWNLOADED,
                            cachePath = paths.finalPath.toString(),
                            bytesDownloaded = finalSize,
                            attempts = attempts,
                        )
                    } catch (error: DownloadAttemptException) {
                        lastFailure = error.failure.copy(artifactKey = entry.artifactKey)
                        logger.warn(
                            "download.attempt_failed",
                            mapOf(
                                "artifactKey" to entry.artifactKey,
                                "attempt" to attempts,
                                "code" to error.failure.code,
                            ),
                            error.cause,
                        )
                        if (error.deletePartial) {
                            Files.deleteIfExists(paths.temporaryPath)
                            progress.startArtifact(entry.artifactKey, 0)
                        }
                    }
                }
                if (round < configuration.maxRetries) {
                    retryDelay.wait(backoffMillis(round, configuration))
                }
            }
        } catch (error: CancellationException) {
            val bytes = paths.temporaryPath.sizeIfExists()
            withContext(NonCancellable) {
                persist(
                    sessionId,
                    entry,
                    paths,
                    DownloadStateStatus.CANCELLED,
                    bytes,
                    null,
                    attempts,
                    DownloadFailureCode.CANCELLED,
                )
            }
            logger.info(
                "download.cancelled",
                mapOf("artifactKey" to entry.artifactKey, "bytes" to bytes),
            )
            throw error
        } catch (error: IOException) {
            lastFailure = DownloadFailure(
                code = DownloadFailureCode.CACHE_IO,
                message = "Cache I/O failed.",
                recoverable = true,
                artifactKey = entry.artifactKey,
            )
            logger.error(
                "download.cache_failed",
                mapOf("artifactKey" to entry.artifactKey),
                error,
            )
        }

        Files.deleteIfExists(paths.temporaryPath)
        Files.deleteIfExists(paths.finalPath)
        val failure = lastFailure ?: DownloadFailure(
            DownloadFailureCode.INTERNAL,
            "Download failed without a classified cause.",
            recoverable = false,
            artifactKey = entry.artifactKey,
        )
        persist(
            sessionId,
            entry,
            paths,
            DownloadStateStatus.FAILED,
            0,
            null,
            attempts,
            failure.code,
        )
        return DownloadArtifactResult(
            artifactKey = entry.artifactKey,
            status = DownloadArtifactStatus.FAILED,
            cachePath = null,
            bytesDownloaded = 0,
            attempts = attempts,
            failure = failure,
        )
    }

    private suspend fun performAttempt(
        sessionId: String,
        entry: ResolvedDownloadEntry,
        paths: DownloadCachePaths,
        url: String,
        attempt: Int,
        initialBytes: Long,
        configuration: DownloadConfiguration,
        progress: DownloadProgressTracker,
    ) {
        persist(
            sessionId,
            entry,
            paths,
            DownloadStateStatus.DOWNLOADING,
            initialBytes,
            url,
            attempt,
            null,
        )
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", configuration.userAgent)
        if (initialBytes > 0) {
            requestBuilder.header("Range", "bytes=$initialBytes-")
        }

        val call = client.newCall(requestBuilder.build())
        val response = try {
            call.await()
        } catch (error: SocketTimeoutException) {
            throw DownloadAttemptException(
                DownloadFailure(
                    DownloadFailureCode.NETWORK_TIMEOUT,
                    "The download timed out.",
                    recoverable = true,
                ),
                cause = error,
            )
        } catch (error: IOException) {
            throw DownloadAttemptException(
                DownloadFailure(
                    DownloadFailureCode.NETWORK_IO,
                    "A network I/O error occurred.",
                    recoverable = true,
                ),
                cause = error,
            )
        }

        response.use {
            val append = when {
                initialBytes == 0L && response.isSuccessful -> false
                initialBytes > 0 && response.code == HTTP_PARTIAL_CONTENT &&
                    response.validContentRangeStart(initialBytes) -> true
                initialBytes > 0 && response.code == HTTP_OK -> false
                response.code == HTTP_RANGE_NOT_SATISFIABLE -> {
                    if (verifyAndPromote(paths, entry, sessionId, url, attempt)) return
                    throw DownloadAttemptException(
                        DownloadFailure(
                            DownloadFailureCode.RANGE_REJECTED,
                            "The server rejected the resume range.",
                            recoverable = true,
                        ),
                        deletePartial = true,
                    )
                }
                else -> throw DownloadAttemptException(
                    DownloadFailure(
                        DownloadFailureCode.HTTP_STATUS,
                        "HTTP ${response.code} while downloading.",
                        recoverable = response.code in RetryableHttpCodes,
                    ),
                )
            }

            val body = response.body
            val startBytes = if (append) initialBytes else 0L
            if (!append && initialBytes > 0) {
                progress.startArtifact(entry.artifactKey, 0)
            }
            val options = if (append) {
                arrayOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            } else {
                arrayOf(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
            }
            var currentBytes = startBytes
            var lastPersistedBytes = startBytes
            Files.newOutputStream(paths.temporaryPath, *options).buffered().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        currentBytes += read
                        val expectedSize = entry.sizeBytes
                        if (expectedSize != null && currentBytes > expectedSize) {
                            throw DownloadAttemptException(
                                DownloadFailure(
                                    DownloadFailureCode.SIZE_MISMATCH,
                                    "The response exceeded the declared file size.",
                                    recoverable = false,
                                ),
                                deletePartial = true,
                            )
                        }
                        progress.update(entry.artifactKey, currentBytes, read.toLong())
                        if (currentBytes - lastPersistedBytes >= PERSIST_INTERVAL_BYTES) {
                            persist(
                                sessionId,
                                entry,
                                paths,
                                DownloadStateStatus.DOWNLOADING,
                                currentBytes,
                                url,
                                attempt,
                                null,
                            )
                            lastPersistedBytes = currentBytes
                        }
                    }
                }
            }
            if (entry.sizeBytes != null && currentBytes != entry.sizeBytes) {
                throw DownloadAttemptException(
                    DownloadFailure(
                        DownloadFailureCode.SIZE_MISMATCH,
                        "The response size did not match the declared file size.",
                        recoverable = true,
                    ),
                )
            }
        }

        if (!verifyAndPromote(paths, entry, sessionId, url, attempt)) {
            throw DownloadAttemptException(
                DownloadFailure(
                    DownloadFailureCode.HASH_MISMATCH,
                    "The downloaded file failed hash verification.",
                    recoverable = false,
                ),
                deletePartial = true,
            )
        }
    }

    private suspend fun verifyAndPromote(
        paths: DownloadCachePaths,
        entry: ResolvedDownloadEntry,
        sessionId: String,
        url: String,
        attempt: Int,
    ): Boolean {
        if (!Files.isRegularFile(paths.temporaryPath)) return false
        persist(
            sessionId,
            entry,
            paths,
            DownloadStateStatus.VERIFYING,
            Files.size(paths.temporaryPath),
            url,
            attempt,
            null,
        )
        if (!verifyFile(paths.temporaryPath, entry)) return false
        Files.move(
            paths.temporaryPath,
            paths.finalPath,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        persist(
            sessionId,
            entry,
            paths,
            DownloadStateStatus.COMPLETED,
            Files.size(paths.finalPath),
            url,
            attempt,
            null,
        )
        return true
    }

    private fun verifyFile(
        path: Path,
        entry: ResolvedDownloadEntry,
    ): Boolean {
        if (!Files.isRegularFile(path)) return false
        if (entry.sizeBytes != null && Files.size(path) != entry.sizeBytes) return false

        val sha1 = entry.hashes.sha1?.let { MessageDigest.getInstance("SHA-1") }
        val sha512 = entry.hashes.sha512?.let { MessageDigest.getInstance("SHA-512") }
        Files.newInputStream(path).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sha1?.update(buffer, 0, read)
                sha512?.update(buffer, 0, read)
            }
        }
        return (sha1 == null || sha1.digest().toHex() == entry.hashes.sha1?.lowercase()) &&
            (sha512 == null || sha512.digest().toHex() == entry.hashes.sha512?.lowercase())
    }

    private fun partialSize(
        temporaryPath: Path,
        expectedSize: Long?,
    ): Long {
        if (!Files.isRegularFile(temporaryPath)) return 0
        val size = Files.size(temporaryPath)
        if (expectedSize != null && size > expectedSize) {
            Files.deleteIfExists(temporaryPath)
            return 0
        }
        return size
    }

    private suspend fun persist(
        sessionId: String,
        entry: ResolvedDownloadEntry,
        paths: DownloadCachePaths,
        status: DownloadStateStatus,
        downloadedBytes: Long,
        url: String?,
        attempt: Int,
        failureCode: DownloadFailureCode?,
    ) {
        repository.upsert(
            DownloadStateRecord(
                sessionId = sessionId,
                artifactKey = entry.artifactKey,
                status = status,
                downloadedBytes = downloadedBytes,
                totalBytes = entry.sizeBytes,
                activeUrl = url,
                attempt = attempt,
                temporaryPath = paths.temporaryPath.toString(),
                finalPath = paths.finalPath.toString(),
                failureCode = failureCode,
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    private fun backoffMillis(
        round: Int,
        configuration: DownloadConfiguration,
    ): Long {
        var delay = configuration.initialBackoffMillis
        repeat(round) {
            delay = (delay * 2).coerceAtMost(configuration.maxBackoffMillis)
        }
        return delay
    }

    private fun Response.validContentRangeStart(expectedStart: Long): Boolean {
        val value = header("Content-Range") ?: return false
        val match = ContentRangePattern.matchEntire(value) ?: return false
        return match.groupValues[1].toLongOrNull() == expectedStart
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) {
                        continuation.resume(response)
                    } else {
                        response.close()
                    }
                }
            },
        )
    }

    private class DownloadAttemptException(
        val failure: DownloadFailure,
        val deletePartial: Boolean = false,
        cause: Throwable? = null,
    ) : IOException(failure.message, cause)

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_PARTIAL_CONTENT = 206
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val PERSIST_INTERVAL_BYTES = 256L * 1024
        val RetryableHttpCodes = setOf(408, 425, 429, 500, 502, 503, 504)
        val ContentRangePattern = Regex("^bytes (\\d+)-\\d+/(?:\\d+|\\*)$")
    }
}

private fun Path.sizeIfExists(): Long =
    if (Files.isRegularFile(this)) Files.size(this) else 0

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte)
}
