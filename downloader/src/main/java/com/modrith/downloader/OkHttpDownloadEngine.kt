package com.modrith.downloader

import com.modrith.downloader.logging.DownloadLogger
import com.modrith.downloader.logging.JvmDownloadLogger
import com.modrith.models.DownloadConfiguration
import com.modrith.models.DownloadEngine
import com.modrith.models.DownloadFailure
import com.modrith.models.DownloadFailureCode
import com.modrith.models.DownloadProgressListener
import com.modrith.models.DownloadRepository
import com.modrith.models.DownloadSession
import com.modrith.models.DownloadSessionResult
import com.modrith.models.DownloadSessionStatus
import com.modrith.models.ResolvedInstallPlan
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import okhttp3.OkHttpClient

class OkHttpDownloadEngine(
    baseClient: OkHttpClient,
    repository: DownloadRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    retryDelay: RetryDelay = CoroutineRetryDelay,
    clock: DownloadClock = SystemDownloadClock,
    diskSpaceProvider: DiskSpaceProvider = FileStoreDiskSpaceProvider,
    logger: DownloadLogger = JvmDownloadLogger(),
) : DownloadEngine {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val queueManager = DownloadQueueManager(
        baseClient = baseClient,
        repository = repository,
        retryDelay = retryDelay,
        clock = clock,
        diskSpaceProvider = diskSpaceProvider,
        logger = logger,
    )

    override fun start(
        plan: ResolvedInstallPlan,
        cacheDirectory: Path,
        configuration: DownloadConfiguration,
        progressListener: DownloadProgressListener,
    ): DownloadSession {
        val sessionId = UUID.randomUUID().toString()
        val cancelled = AtomicBoolean(false)
        val deferred = scope.async {
            queueManager.execute(
                sessionId,
                plan,
                cacheDirectory,
                configuration,
                progressListener,
            )
        }
        return DefaultDownloadSession(sessionId, deferred, cancelled)
    }
}

private class DefaultDownloadSession(
    override val id: String,
    private val deferred: Deferred<DownloadSessionResult>,
    private val cancelled: AtomicBoolean,
) : DownloadSession {
    override fun cancel() {
        cancelled.set(true)
        deferred.cancel(CancellationException("Download session cancelled."))
    }

    override suspend fun await(): DownloadSessionResult = try {
        deferred.await()
    } catch (error: CancellationException) {
        if (!cancelled.get()) throw error
        DownloadSessionResult(
            sessionId = id,
            status = DownloadSessionStatus.CANCELLED,
            artifacts = emptyList(),
            downloadedBytes = 0,
            totalBytes = null,
            elapsedMillis = 0,
            averageBytesPerSecond = 0,
            failure = DownloadFailure(
                code = DownloadFailureCode.CANCELLED,
                message = "Download session was cancelled.",
                recoverable = true,
            ),
        )
    }
}
