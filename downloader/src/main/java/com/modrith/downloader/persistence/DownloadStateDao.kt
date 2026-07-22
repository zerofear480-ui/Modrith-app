package com.modrith.downloader.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DownloadStateDao {
    @Query("SELECT * FROM download_state WHERE artifactKey = :artifactKey")
    suspend fun get(artifactKey: String): DownloadStateEntity?

    @Query("SELECT * FROM download_state WHERE sessionId = :sessionId ORDER BY artifactKey")
    suspend fun getSession(sessionId: String): List<DownloadStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadStateEntity)

    @Query("DELETE FROM download_state WHERE artifactKey = :artifactKey")
    suspend fun delete(artifactKey: String)
}
