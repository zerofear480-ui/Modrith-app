package com.modrith.downloader

import com.modrith.models.DownloadProgress
import com.modrith.models.DownloadProgressListener
import com.modrith.models.ResolvedDownloadEntry

internal class DownloadProgressTracker(
    private val sessionId: String,
    entries: List<ResolvedDownloadEntry>,
    private val listener: DownloadProgressListener,
    private val clock: DownloadClock,
    private val intervalMillis: Long,
) {
    private val lock = Any()
    private val currentBytes = entries.associate { it.artifactKey to 0L }.toMutableMap()
    private val completed = mutableSetOf<String>()
    private val totalFiles = entries.size
    private val totalBytes = entries.map(ResolvedDownloadEntry::sizeBytes)
        .takeIf { sizes -> sizes.all { it != null } }
        ?.sumOf { requireNotNull(it) }
    private val startedAt = clock.nanoTime()
    private var transferredBytes = 0L
    private var lastEmissionAt = 0L

    fun startArtifact(
        artifactKey: String,
        existingBytes: Long,
    ) {
        synchronized(lock) {
            currentBytes[artifactKey] = existingBytes
            emitLocked(artifactKey, force = true)
        }
    }

    fun update(
        artifactKey: String,
        currentArtifactBytes: Long,
        transferredDelta: Long,
    ) {
        synchronized(lock) {
            currentBytes[artifactKey] = currentArtifactBytes
            transferredBytes += transferredDelta.coerceAtLeast(0)
            emitLocked(artifactKey, force = false)
        }
    }

    fun complete(
        artifactKey: String,
        finalBytes: Long,
    ) {
        synchronized(lock) {
            currentBytes[artifactKey] = finalBytes
            completed += artifactKey
            emitLocked(artifactKey, force = true)
        }
    }

    fun snapshot(): ProgressSnapshot = synchronized(lock) {
        val elapsedNanos = (clock.nanoTime() - startedAt).coerceAtLeast(1)
        ProgressSnapshot(
            progressBytes = currentBytes.values.sum(),
            totalBytes = totalBytes,
            speedBytesPerSecond = bytesPerSecond(elapsedNanos),
            elapsedMillis = elapsedNanos / NANOS_PER_MILLISECOND,
        )
    }

    private fun emitLocked(
        artifactKey: String?,
        force: Boolean,
    ) {
        val now = clock.nanoTime()
        val intervalNanos = intervalMillis * NANOS_PER_MILLISECOND
        if (!force && now - lastEmissionAt < intervalNanos) return
        lastEmissionAt = now

        val elapsedNanos = (now - startedAt).coerceAtLeast(1)
        val progressBytes = currentBytes.values.sum()
        val speed = bytesPerSecond(elapsedNanos)
        val remainingMillis = if (totalBytes != null && speed > 0) {
            ((totalBytes - progressBytes).coerceAtLeast(0) * MILLIS_PER_SECOND) / speed
        } else {
            null
        }
        runCatching {
            listener.onProgress(
                DownloadProgress(
                    sessionId = sessionId,
                    artifactKey = artifactKey,
                    completedFiles = completed.size,
                    totalFiles = totalFiles,
                    downloadedBytes = progressBytes,
                    totalBytes = totalBytes,
                    bytesPerSecond = speed,
                    estimatedRemainingMillis = remainingMillis,
                ),
            )
        }
    }

    private fun bytesPerSecond(elapsedNanos: Long): Long =
        (transferredBytes * NANOS_PER_SECOND / elapsedNanos).coerceAtLeast(0)

    data class ProgressSnapshot(
        val progressBytes: Long,
        val totalBytes: Long?,
        val speedBytesPerSecond: Long,
        val elapsedMillis: Long,
    )

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val MILLIS_PER_SECOND = 1_000L
    }
}
