package com.modrith.models

import java.nio.file.Path

data class DownloadConfiguration(
    val workerCount: Int = 3,
    val maxRetries: Int = 3,
    val initialBackoffMillis: Long = 500,
    val maxBackoffMillis: Long = 15_000,
    val connectTimeoutMillis: Long = 15_000,
    val readTimeoutMillis: Long = 30_000,
    val callTimeoutMillis: Long = 60_000,
    val progressIntervalMillis: Long = 200,
    val diskSpaceReserveBytes: Long = 16L * 1024 * 1024,
    val userAgent: String = "Modrith/0.1",
) {
    init {
        require(workerCount > 0)
        require(maxRetries >= 0)
        require(initialBackoffMillis >= 0)
        require(maxBackoffMillis >= initialBackoffMillis)
        require(connectTimeoutMillis > 0)
        require(readTimeoutMillis > 0)
        require(callTimeoutMillis > 0)
        require(progressIntervalMillis >= 0)
        require(diskSpaceReserveBytes >= 0)
        require(userAgent.isNotBlank())
    }
}

data class DownloadProgress(
    val sessionId: String,
    val artifactKey: String?,
    val completedFiles: Int,
    val totalFiles: Int,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val bytesPerSecond: Long,
    val estimatedRemainingMillis: Long?,
)

fun interface DownloadProgressListener {
    fun onProgress(progress: DownloadProgress)

    companion object {
        val NONE = DownloadProgressListener { }
    }
}

interface DownloadEngine {
    fun start(
        plan: ResolvedInstallPlan,
        cacheDirectory: Path,
        configuration: DownloadConfiguration = DownloadConfiguration(),
        progressListener: DownloadProgressListener = DownloadProgressListener.NONE,
    ): DownloadSession
}

interface DownloadSession {
    val id: String

    fun cancel()

    suspend fun await(): DownloadSessionResult
}

data class DownloadSessionResult(
    val sessionId: String,
    val status: DownloadSessionStatus,
    val artifacts: List<DownloadArtifactResult>,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val elapsedMillis: Long,
    val averageBytesPerSecond: Long,
    val failure: DownloadFailure? = null,
)

enum class DownloadSessionStatus {
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class DownloadArtifactResult(
    val artifactKey: String,
    val status: DownloadArtifactStatus,
    val cachePath: String?,
    val bytesDownloaded: Long,
    val attempts: Int,
    val failure: DownloadFailure? = null,
)

enum class DownloadArtifactStatus {
    DOWNLOADED,
    SKIPPED_VERIFIED,
    FAILED,
    CANCELLED,
}

data class DownloadFailure(
    val code: DownloadFailureCode,
    val message: String,
    val recoverable: Boolean,
    val artifactKey: String? = null,
)

enum class DownloadFailureCode {
    PLAN_NOT_READY,
    INSUFFICIENT_DISK_SPACE,
    NETWORK_TIMEOUT,
    NETWORK_IO,
    HTTP_STATUS,
    RANGE_REJECTED,
    HASH_MISMATCH,
    SIZE_MISMATCH,
    CACHE_IO,
    CANCELLED,
    INTERNAL,
}

data class DownloadStateRecord(
    val sessionId: String,
    val artifactKey: String,
    val status: DownloadStateStatus,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val activeUrl: String?,
    val attempt: Int,
    val temporaryPath: String,
    val finalPath: String,
    val failureCode: DownloadFailureCode?,
    val updatedAtEpochMillis: Long,
)

enum class DownloadStateStatus {
    QUEUED,
    DOWNLOADING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

interface DownloadRepository {
    suspend fun get(artifactKey: String): DownloadStateRecord?

    suspend fun getSession(sessionId: String): List<DownloadStateRecord>

    suspend fun upsert(record: DownloadStateRecord)

    suspend fun delete(artifactKey: String)
}
