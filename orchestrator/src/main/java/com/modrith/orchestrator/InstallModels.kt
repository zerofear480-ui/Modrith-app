package com.modrith.orchestrator

import com.modrith.launcher.LauncherInstance
import com.modrith.models.DownloadConfiguration
import com.modrith.models.InstallPlan
import com.modrith.models.ResolvedInstallPlan
import java.io.File
import java.nio.file.Path

sealed interface MrPackSource {
    data class FileSource(
        val file: File,
    ) : MrPackSource

    data class UriSource(
        val uri: String,
    ) : MrPackSource
}

data class InstallRequest(
    val source: MrPackSource,
    val launcherInstance: LauncherInstance,
    val cacheDirectory: Path,
    val downloadConfiguration: DownloadConfiguration = DownloadConfiguration(),
)

enum class InstallPhase {
    PREPARING_SOURCE,
    PARSING,
    RESOLVING,
    VALIDATING_LAUNCHER,
    DOWNLOADING,
    INSTALLING,
    ROLLING_BACK,
    COMPLETED,
}

enum class InstallStatus {
    CREATED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class InstallProgress(
    val phase: InstallPhase,
    val currentFile: String?,
    val filesCompleted: Int,
    val totalFiles: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val percentage: Double,
    val estimatedRemainingMillis: Long?,
    val currentSpeedBytesPerSecond: Long,
    val statusMessage: String,
)

data class InstallState(
    val sessionId: String,
    val status: InstallStatus,
    val progress: InstallProgress,
    val checkpoint: OrchestratorCheckpoint?,
    val error: InstallError?,
    val logs: List<InstallLogEntry>,
)

sealed interface InstallResult {
    val sessionId: String
    val logs: List<InstallLogEntry>
    val checkpoint: OrchestratorCheckpoint?

    data class Success(
        override val sessionId: String,
        val transactionId: String,
        val installedFiles: Int,
        val installedBytes: Long,
        override val logs: List<InstallLogEntry>,
        override val checkpoint: OrchestratorCheckpoint,
    ) : InstallResult

    data class Failure(
        override val sessionId: String,
        val error: InstallError,
        override val logs: List<InstallLogEntry>,
        override val checkpoint: OrchestratorCheckpoint?,
    ) : InstallResult

    data class Cancelled(
        override val sessionId: String,
        override val logs: List<InstallLogEntry>,
        override val checkpoint: OrchestratorCheckpoint?,
    ) : InstallResult
}

enum class InstallErrorSource {
    SOURCE,
    PARSER,
    RESOLVER,
    FILESYSTEM,
    LAUNCHER,
    DOWNLOADER,
    INSTALLER,
    ORCHESTRATOR,
}

data class InstallError(
    val source: InstallErrorSource,
    val code: String,
    val message: String,
    val recoverable: Boolean,
    val details: Map<String, String> = emptyMap(),
)

sealed class InstallException(
    open val error: InstallError,
    cause: Throwable? = null,
) : Exception(error.message, cause)

class SourceInstallException(
    override val error: InstallError,
    cause: Throwable? = null,
) : InstallException(error, cause)

class ParserInstallException(
    override val error: InstallError,
    cause: Throwable? = null,
) : InstallException(error, cause)

class ResolverInstallException(
    override val error: InstallError,
    cause: Throwable? = null,
) : InstallException(error, cause)

class FilesystemInstallException(
    override val error: InstallError,
    cause: Throwable? = null,
) : InstallException(error, cause)

class LauncherInstallException(
    override val error: InstallError,
    cause: Throwable? = null,
) : InstallException(error, cause)

class DownloadInstallException(
    override val error: InstallError,
    cause: Throwable? = null,
) : InstallException(error, cause)

class InstallerInstallException(
    override val error: InstallError,
    cause: Throwable? = null,
) : InstallException(error, cause)

class InternalInstallException(
    override val error: InstallError,
    cause: Throwable? = null,
) : InstallException(error, cause)

enum class CheckpointPhase {
    CREATED,
    SOURCE_READY,
    PARSED,
    RESOLVED,
    DOWNLOADING,
    DOWNLOADED,
    INSTALLING,
    COMPLETED,
}

data class OrchestratorCheckpoint(
    val sessionId: String,
    val request: InstallRequest,
    val phase: CheckpointPhase,
    val archivePath: Path? = null,
    val deleteArchiveAfterParsing: Boolean = false,
    val parsedPlan: InstallPlan? = null,
    val resolvedPlan: ResolvedInstallPlan? = null,
    val installerTransactionId: String? = null,
    val installedFiles: Int = 0,
    val installedBytes: Long = 0,
    val updatedAtEpochMillis: Long,
)

internal fun initialProgress(): InstallProgress = InstallProgress(
    phase = InstallPhase.PREPARING_SOURCE,
    currentFile = null,
    filesCompleted = 0,
    totalFiles = 0,
    bytesDownloaded = 0,
    totalBytes = null,
    percentage = 0.0,
    estimatedRemainingMillis = null,
    currentSpeedBytesPerSecond = 0,
    statusMessage = "Preparing installation.",
)
