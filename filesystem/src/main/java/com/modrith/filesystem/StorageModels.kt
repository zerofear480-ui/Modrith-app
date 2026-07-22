package com.modrith.filesystem

import java.io.InputStream
import java.io.OutputStream

@JvmInline
value class StoragePath(
    val value: String,
) {
    val isRoot: Boolean
        get() = value.isEmpty()

    val name: String
        get() = value.substringAfterLast('/', value)

    val parent: StoragePath?
        get() = when {
            isRoot -> null
            '/' !in value -> ROOT
            else -> StoragePath(value.substringBeforeLast('/'))
        }

    companion object {
        val ROOT = StoragePath("")
    }
}

enum class StorageEntryType {
    FILE,
    DIRECTORY,
}

data class StorageEntry(
    val path: StoragePath,
    val name: String,
    val type: StorageEntryType,
    val mimeType: String?,
    val sizeBytes: Long?,
    val lastModifiedEpochMillis: Long?,
    val readable: Boolean,
    val writable: Boolean,
)

data class TreePermission(
    val treeUri: String,
    val read: Boolean,
    val write: Boolean,
    val persisted: Boolean,
)

data class ResolvedDocumentTree(
    val selectedTreeUri: String,
    val treeDocumentId: String?,
    val rootDocumentUri: String,
    val rootDisplayName: String?,
)

enum class StorageHashAlgorithm(
    val jcaName: String,
) {
    SHA1("SHA-1"),
    SHA256("SHA-256"),
    SHA512("SHA-512"),
}

data class FileHashResult(
    val path: StoragePath,
    val sizeBytes: Long,
    val hashes: Map<StorageHashAlgorithm, String>,
)

enum class ReplaceGuarantee {
    ATOMIC,
    PROVIDER_RENAME,
    BEST_EFFORT,
}

data class FileReplaceResult(
    val path: StoragePath,
    val sizeBytes: Long,
    val guarantee: ReplaceGuarantee,
)

enum class StorageOperation {
    COPY,
    DELETE,
    HASH,
    CLEANUP,
}

data class StorageProgress(
    val operation: StorageOperation,
    val currentPath: StoragePath?,
    val completedFiles: Int,
    val totalFiles: Int?,
    val processedBytes: Long,
    val totalBytes: Long?,
)

fun interface StorageProgressListener {
    fun onProgress(progress: StorageProgress)

    companion object {
        val NONE = StorageProgressListener { }
    }
}

data class CopySummary(
    val copiedFiles: Int,
    val copiedDirectories: Int,
    val copiedBytes: Long,
)

data class DeleteSummary(
    val deletedFiles: Int,
    val deletedDirectories: Int,
    val deletedBytes: Long,
)

data class CacheCleanupPolicy(
    val maxBytes: Long,
    val maxAgeMillis: Long? = null,
    val protectedPaths: Set<StoragePath> = emptySet(),
    val nowEpochMillis: Long = System.currentTimeMillis(),
) {
    init {
        require(maxBytes >= 0)
        require(maxAgeMillis == null || maxAgeMillis >= 0)
    }
}

data class CacheCleanupResult(
    val bytesBefore: Long,
    val bytesAfter: Long,
    val deletedFiles: Int,
    val deletedBytes: Long,
)

enum class StorageErrorCode {
    INVALID_PATH,
    NOT_FOUND,
    ALREADY_EXISTS,
    NOT_A_FILE,
    NOT_A_DIRECTORY,
    READ_PERMISSION_DENIED,
    WRITE_PERMISSION_DENIED,
    PERSIST_PERMISSION_FAILED,
    UNSUPPORTED_OPERATION,
    IO_ERROR,
    LIMIT_EXCEEDED,
    CANCELLED,
    INTERNAL,
}

data class StorageError(
    val code: StorageErrorCode,
    val message: String,
    val path: StoragePath? = null,
    val recoverable: Boolean,
)

sealed interface StorageResult<out T> {
    data class Success<T>(
        val value: T,
    ) : StorageResult<T>

    data class Failure(
        val error: StorageError,
    ) : StorageResult<Nothing>
}

typealias StorageInput = StorageResult<InputStream>
typealias StorageOutput = StorageResult<OutputStream>

inline fun <T, R> StorageResult<T>.map(transform: (T) -> R): StorageResult<R> = when (this) {
    is StorageResult.Success -> StorageResult.Success(transform(value))
    is StorageResult.Failure -> this
}

fun <T> StorageResult<T>.getOrNull(): T? = (this as? StorageResult.Success)?.value
