package com.modrith.downloader.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "download_state")
data class DownloadStateEntity(
    @PrimaryKey val artifactKey: String,
    val sessionId: String,
    val status: String,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val activeUrl: String?,
    val attempt: Int,
    val temporaryPath: String,
    val finalPath: String,
    val failureCode: String?,
    val updatedAtEpochMillis: Long,
)
