package com.modrith.downloader.persistence

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [DownloadStateEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class DownloadStateDatabase : RoomDatabase() {
    abstract fun downloadStateDao(): DownloadStateDao
}
