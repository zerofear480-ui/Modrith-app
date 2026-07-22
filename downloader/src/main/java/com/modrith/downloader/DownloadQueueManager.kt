package com.modrith.downloader

import com.modrith.downloader.logging.DownloadLogger
import com.modrith.downloader.logging.JvmDownloadLogger
import com.modrith.models.DownloadArtifactResult
import com.modrith.models.DownloadConfiguration
import com.modrith.models.DownloadFailure
import com.modrith.models.DownloadFailureCode
import com.modrith.models.DownloadProgressListener
import com.modrith.models.DownloadRepository
import com.modrith.models.DownloadSessionResult
import com.modrith.models.DownloadSessionStatus
import com.modrith.models.ResolvedDownloadEntry
import com.modrith.models.ResolvedInstallPlan
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient

class DownloadQueueManager(
    private val baseClient: OkHttpClient,
    private val repository: DownloadRepository,
    private val retryDelay: RetryDelay = CoroutineRetryDelay,
    private val clock: DownloadClock = SystemDownloadClock,
    private val diskSpaceProvider: DiskSpaceProvider = FileStoreDiskSpaceProvider,
    private val logger: DownloadLogger = JvmDownloadLogger(),
) {
    suspend fun execute(
        sessionId: String,
        plan: ResolvedInstallPlan,
        cacheDirectory: Path,
        configuration: DownloadConfiguration,
        progressListener: DownloadProgressListener,
    ): DownloadSessionResult {
        val startedAt = clock.nanoTime()
        if (!plan.isReady) {
            return failedSession(
                sessionId,
                startedAt,
                DownloadFailure(
                    DownloadFailureCode.PLAN_NOT_READY,
                    "ResolvedInstallPlan contains unresolved errors.",
                    recoverable = false,
                ),
            )
        }

        Files.createDirectories(cacheDirectory)
        val entries = plan.downloadEntries.distinctBy(ResolvedDownloadEntry::artifactKey)
        val requiredBytes = calculateRequiredBytes(entries, cacheDirectory, configuration)
        if (diskSpaceProvider.usableBytes(cacheDirectory) < requiredBytes) {
            return failedSession(
                sessionId,
                startedAt,
                DownloadFailure(
                    DownloadFailureCode.INSUFFICIENT_DISK_SPACE,
                    "Insufficient cache space for downloads.",
                    recoverable = true,
                ),
            )
        }

        val client = baseClient.newBuilder()
            .connectTimeout(configuration.connectTimeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(configuration.readTimeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
            .callTimeout(configuration.callTimeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        val worker = DownloadWorker(client, repository, retryDelay, logger)
        val progress = DownloadProgressTracker(
            sessionId,
            entries,
            progressListener,
            clock,
            configuration.progressIntervalMillis,
        )
        logger.info(
            "download.session_started",
            mapOf(
                "sessionId" to sessionId,
                "artifacts" to entries.size,
                "workerCount" to configuration.workerCount,
            ),
        )

        return try {
            val semaphore = Semaphore(configuration.workerCount)
            val artifacts = coroutineScope {
                entries.map { entry ->
                    async {
                        semaphore.withPermit {
                            worker.download(
                                sessionId,
                                entry,
                                cacheDirectory,
                                configuration,
                                progress,
                            )
                        }
                    }
                }.awaitAll()
            }
            val snapshot = progress.snapshot()
            val failed = artifacts.firstOrNull { it.failure != null }
            DownloadSessionResult(
                sessionId = sessionId,
                status = if (failed == null) {
                    DownloadSessionStatus.COMPLETED
                } else {
                    DownloadSessionStatus.FAILED
                },
                artifacts = artifacts,
                downloadedBytes = snapshot.progressBytes,
                totalBytes = snapshot.totalBytes,
                elapsedMillis = snapshot.elapsedMillis,
                averageBytesPerSecond = snapshot.speedBytesPerSecond,
                failure = failed?.failure,
            )
        } catch (error: CancellationException) {
            logger.info("download.session_cancelled", mapOf("sessionId" to sessionId))
            throw error
        }
    }

    private fun calculateRequiredBytes(
        entries: List<ResolvedDownloadEntry>,
        cacheDirectory: Path,
        configuration: DownloadConfiguration,
    ): Long {
        var required = configuration.diskSpaceReserveBytes
        entries.forEach { entry ->
            val expected = entry.sizeBytes ?: return Long.MAX_VALUE
            val paths = entry.cachePaths(cacheDirectory)
            val existing = when {
                Files.isRegularFile(paths.finalPath) -> expected
                Files.isRegularFile(paths.temporaryPath) ->
                    Files.size(paths.temporaryPath).coerceAtMost(expected)
                else -> 0
            }
            val remaining = expected - existing
            if (required > Long.MAX_VALUE - remaining) return Long.MAX_VALUE
            required += remaining
        }
        return required
    }

    private fun failedSession(
        sessionId: String,
        startedAt: Long,
        failure: DownloadFailure,
    ): DownloadSessionResult = DownloadSessionResult(
        sessionId = sessionId,
        status = DownloadSessionStatus.FAILED,
        artifacts = emptyList(),
        downloadedBytes = 0,
        totalBytes = null,
        elapsedMillis = (clock.nanoTime() - startedAt).coerceAtLeast(0) / NANOS_PER_MILLISECOND,
        averageBytesPerSecond = 0,
        failure = failure,
    )

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
