package com.modrith.orchestrator

import com.modrith.installer.InstallProgressPhase
import com.modrith.installer.InstallProgress as InstallerProgress
import com.modrith.models.DownloadProgress as DownloaderProgress

internal class InstallProgressAggregator {
    private var lastDownloadedBytes = 0L
    private var lastTotalBytes: Long? = null

    @Synchronized
    fun phase(
        phase: InstallPhase,
        message: String,
        totalFiles: Int = 0,
    ): InstallProgress = InstallProgress(
        phase = phase,
        currentFile = null,
        filesCompleted = 0,
        totalFiles = totalFiles,
        bytesDownloaded = lastDownloadedBytes,
        totalBytes = lastTotalBytes,
        percentage = phase.basePercentage,
        estimatedRemainingMillis = null,
        currentSpeedBytesPerSecond = 0,
        statusMessage = message,
    )

    @Synchronized
    fun download(progress: DownloaderProgress): InstallProgress {
        lastDownloadedBytes = progress.downloadedBytes
        lastTotalBytes = progress.totalBytes
        val totalBytes = progress.totalBytes
        val fraction = when {
            totalBytes != null && totalBytes > 0 ->
                progress.downloadedBytes.toDouble() / totalBytes
            progress.totalFiles > 0 ->
                progress.completedFiles.toDouble() / progress.totalFiles
            else -> 0.0
        }.coerceIn(0.0, 1.0)
        return InstallProgress(
            phase = InstallPhase.DOWNLOADING,
            currentFile = progress.artifactKey,
            filesCompleted = progress.completedFiles,
            totalFiles = progress.totalFiles,
            bytesDownloaded = progress.downloadedBytes,
            totalBytes = progress.totalBytes,
            percentage = 25.0 + (fraction * 55.0),
            estimatedRemainingMillis = progress.estimatedRemainingMillis,
            currentSpeedBytesPerSecond = progress.bytesPerSecond,
            statusMessage = "Downloading verified files.",
        )
    }

    @Synchronized
    fun install(progress: InstallerProgress): InstallProgress {
        val totalBytes = progress.totalBytes
        val fraction = when {
            totalBytes != null && totalBytes > 0 ->
                progress.processedBytes.toDouble() / totalBytes
            progress.totalFiles > 0 ->
                progress.completedFiles.toDouble() / progress.totalFiles
            else -> 0.0
        }.coerceIn(0.0, 1.0)
        val phase = if (progress.phase == InstallProgressPhase.ROLLING_BACK) {
            InstallPhase.ROLLING_BACK
        } else {
            InstallPhase.INSTALLING
        }
        return InstallProgress(
            phase = phase,
            currentFile = progress.currentPath,
            filesCompleted = progress.completedFiles,
            totalFiles = progress.totalFiles,
            bytesDownloaded = lastDownloadedBytes,
            totalBytes = lastTotalBytes,
            percentage = if (phase == InstallPhase.ROLLING_BACK) 80.0 else 80.0 + (fraction * 20.0),
            estimatedRemainingMillis = null,
            currentSpeedBytesPerSecond = 0,
            statusMessage = when (progress.phase) {
                InstallProgressPhase.WAITING_FOR_DOWNLOADS -> "Confirming downloaded files."
                InstallProgressPhase.VERIFYING -> "Verifying downloaded files."
                InstallProgressPhase.STAGING -> "Staging installation files."
                InstallProgressPhase.COMMITTING -> "Committing installation transaction."
                InstallProgressPhase.ROLLING_BACK -> "Rolling back installation."
                InstallProgressPhase.CLEANING_UP -> "Cleaning temporary installation files."
                InstallProgressPhase.COMPLETED -> "Installation completed."
            },
        )
    }

    @Synchronized
    fun completed(
        totalFiles: Int,
        installedBytes: Long,
    ): InstallProgress = InstallProgress(
        phase = InstallPhase.COMPLETED,
        currentFile = null,
        filesCompleted = totalFiles,
        totalFiles = totalFiles,
        bytesDownloaded = lastDownloadedBytes,
        totalBytes = lastTotalBytes,
        percentage = 100.0,
        estimatedRemainingMillis = 0,
        currentSpeedBytesPerSecond = 0,
        statusMessage = "Installation completed.",
    )

    private val InstallPhase.basePercentage: Double
        get() = when (this) {
            InstallPhase.PREPARING_SOURCE -> 0.0
            InstallPhase.PARSING -> 5.0
            InstallPhase.RESOLVING -> 15.0
            InstallPhase.VALIDATING_LAUNCHER -> 20.0
            InstallPhase.DOWNLOADING -> 25.0
            InstallPhase.INSTALLING,
            InstallPhase.ROLLING_BACK,
            -> 80.0
            InstallPhase.COMPLETED -> 100.0
        }
}
