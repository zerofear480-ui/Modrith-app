package com.modrith.downloader.persistence

import com.modrith.models.DownloadFailureCode
import com.modrith.models.DownloadRepository
import com.modrith.models.DownloadStateRecord
import com.modrith.models.DownloadStateStatus

class RoomDownloadRepository(
    private val dao: DownloadStateDao,
) : DownloadRepository {
    override suspend fun get(artifactKey: String): DownloadStateRecord? =
        dao.get(artifactKey)?.toModel()

    override suspend fun getSession(sessionId: String): List<DownloadStateRecord> =
        dao.getSession(sessionId).map { entity -> entity.toModel() }

    override suspend fun upsert(record: DownloadStateRecord) {
        dao.upsert(record.toEntity())
    }

    override suspend fun delete(artifactKey: String) {
        dao.delete(artifactKey)
    }

    private fun DownloadStateEntity.toModel() = DownloadStateRecord(
        sessionId = sessionId,
        artifactKey = artifactKey,
        status = DownloadStateStatus.valueOf(status),
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        activeUrl = activeUrl,
        attempt = attempt,
        temporaryPath = temporaryPath,
        finalPath = finalPath,
        failureCode = failureCode?.let(DownloadFailureCode::valueOf),
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    private fun DownloadStateRecord.toEntity() = DownloadStateEntity(
        artifactKey = artifactKey,
        sessionId = sessionId,
        status = status.name,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        activeUrl = activeUrl,
        attempt = attempt,
        temporaryPath = temporaryPath,
        finalPath = finalPath,
        failureCode = failureCode?.name,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}
