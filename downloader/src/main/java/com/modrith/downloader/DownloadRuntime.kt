package com.modrith.downloader

import java.nio.file.FileStore
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.delay

fun interface RetryDelay {
    suspend fun wait(delayMillis: Long)
}

object CoroutineRetryDelay : RetryDelay {
    override suspend fun wait(delayMillis: Long) {
        delay(delayMillis)
    }
}

fun interface DownloadClock {
    fun nanoTime(): Long
}

object SystemDownloadClock : DownloadClock {
    override fun nanoTime(): Long = System.nanoTime()
}

fun interface DiskSpaceProvider {
    fun usableBytes(path: Path): Long
}

object FileStoreDiskSpaceProvider : DiskSpaceProvider {
    override fun usableBytes(path: Path): Long {
        val store: FileStore = Files.getFileStore(path)
        return store.usableSpace
    }
}
