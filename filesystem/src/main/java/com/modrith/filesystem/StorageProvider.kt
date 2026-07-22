package com.modrith.filesystem

import java.io.InputStream
import java.io.OutputStream

interface StorageProvider {
    val providerId: String

    suspend fun metadata(path: StoragePath): StorageResult<StorageEntry>

    suspend fun exists(path: StoragePath): StorageResult<Boolean>

    suspend fun list(path: StoragePath): StorageResult<List<StorageEntry>>

    suspend fun createDirectory(path: StoragePath): StorageResult<StorageEntry>

    suspend fun openInput(
        path: StoragePath,
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
    ): StorageResult<InputStream>

    suspend fun openOutput(
        path: StoragePath,
        mimeType: String = DEFAULT_MIME_TYPE,
        append: Boolean = false,
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
    ): StorageResult<OutputStream>

    suspend fun replaceFile(
        path: StoragePath,
        mimeType: String = DEFAULT_MIME_TYPE,
        writer: suspend (OutputStream) -> Unit,
    ): StorageResult<FileReplaceResult>

    suspend fun delete(
        path: StoragePath,
        recursive: Boolean = false,
        progressListener: StorageProgressListener = StorageProgressListener.NONE,
    ): StorageResult<DeleteSummary>

    companion object {
        const val DEFAULT_MIME_TYPE = "application/octet-stream"
    }
}
