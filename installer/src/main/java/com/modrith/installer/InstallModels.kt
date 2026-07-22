package com.modrith.installer

import com.modrith.filesystem.StorageProvider
import com.modrith.models.DownloadSession
import com.modrith.models.ResolvedInstallPlan
import kotlinx.serialization.Serializable

interface InstallEngine {
    fun start(
        plan: ResolvedInstallPlan,
        downloadSession: DownloadSession,
        destination: StorageProvider,
        progressListener: InstallProgressListener = InstallProgressListener.NONE,
    ): InstallSession

    fun resume(
        transactionId: String,
        destination: StorageProvider,
        progressListener: InstallProgressListener = InstallProgressListener.NONE,
    ): InstallSession
}

interface InstallSession {
    val id: String

    fun cancel()

    suspend fun await(): InstallSessionResult
}

data class InstallSessionResult(
    val transactionId: String,
    val status: InstallSessionStatus,
    val installedFiles: Int,
    val installedBytes: Long,
    val failure: InstallFailure? = null,
)

enum class InstallSessionStatus {
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class InstallFailure(
    val code: InstallFailureCode,
    val message: String,
    val recoverable: Boolean,
    val path: String? = null,
)

enum class InstallFailureCode {
    PLAN_NOT_READY,
    DOWNLOAD_FAILED,
    MISSING_DOWNLOAD,
    SOURCE_NOT_FOUND,
    HASH_MISMATCH,
    SIZE_MISMATCH,
    DISK_FULL,
    PERMISSION_DENIED,
    DESTINATION_IO,
    TRANSACTION_NOT_FOUND,
    TRANSACTION_CORRUPT,
    PROVIDER_MISMATCH,
    ROLLBACK_FAILED,
    CANCELLED,
    INTERNAL,
}

enum class InstallProgressPhase {
    WAITING_FOR_DOWNLOADS,
    VERIFYING,
    STAGING,
    COMMITTING,
    ROLLING_BACK,
    CLEANING_UP,
    COMPLETED,
}

data class InstallProgress(
    val transactionId: String,
    val phase: InstallProgressPhase,
    val currentPath: String?,
    val completedFiles: Int,
    val totalFiles: Int,
    val processedBytes: Long,
    val totalBytes: Long?,
)

fun interface InstallProgressListener {
    fun onProgress(progress: InstallProgress)

    companion object {
        val NONE = InstallProgressListener { }
    }
}

@Serializable
data class InstallTransaction(
    val id: String,
    val destinationProviderId: String,
    val status: InstallTransactionStatus,
    val checkpoint: InstallCheckpoint,
    val operations: List<InstallFileOperation>,
    val log: List<InstallTransactionLogEntry>,
    val failure: PersistedInstallFailure? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Serializable
enum class InstallTransactionStatus {
    ACTIVE,
    COMPLETED,
    ROLLED_BACK,
    ROLLBACK_FAILED,
}

@Serializable
data class InstallCheckpoint(
    val phase: InstallCheckpointPhase,
    val operationIndex: Int,
)

@Serializable
enum class InstallCheckpointPhase {
    PREPARED,
    STAGING,
    COMMITTING,
    ROLLING_BACK,
    COMPLETED,
    ROLLED_BACK,
}

@Serializable
data class InstallFileOperation(
    val sourceType: InstallSourceType,
    val sourcePath: String,
    val destinationPath: String,
    val expectedSha1: String? = null,
    val expectedSha512: String,
    val expectedSizeBytes: Long,
    val stagePath: String,
    val backupPath: String,
    val state: InstallFileState = InstallFileState.PENDING,
    val originalExisted: Boolean? = null,
    val originalSha512: String? = null,
)

@Serializable
enum class InstallSourceType {
    DOWNLOAD,
    OVERRIDE,
}

@Serializable
enum class InstallFileState {
    PENDING,
    STAGED,
    BACKED_UP,
    INSTALLED,
    RESTORED,
}

@Serializable
data class InstallTransactionLogEntry(
    val timestampEpochMillis: Long,
    val event: String,
    val path: String? = null,
    val detail: String? = null,
)

@Serializable
data class PersistedInstallFailure(
    val code: String,
    val message: String,
    val recoverable: Boolean,
    val path: String? = null,
)

fun interface InstallDiskSpaceChecker {
    suspend fun hasSufficientSpace(
        destination: StorageProvider,
        requiredBytes: Long,
    ): Boolean?

    companion object {
        val UNKNOWN = InstallDiskSpaceChecker { _, _ -> null }
    }
}
