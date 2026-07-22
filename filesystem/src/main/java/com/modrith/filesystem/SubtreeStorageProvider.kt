package com.modrith.filesystem

import java.io.InputStream
import java.io.OutputStream

class SubtreeStorageProvider(
    private val delegate: StorageProvider,
    private val subtreeRoot: StoragePath,
) : StorageProvider {
    init {
        require(!subtreeRoot.isRoot) {
            "Use the original provider when no subtree root is required."
        }
    }

    override val providerId: String = "${delegate.providerId}:subtree"

    override suspend fun metadata(path: StoragePath): StorageResult<StorageEntry> =
        delegate.metadata(resolve(path)).map { it.copy(path = path) }

    override suspend fun exists(path: StoragePath): StorageResult<Boolean> =
        delegate.exists(resolve(path))

    override suspend fun list(path: StoragePath): StorageResult<List<StorageEntry>> =
        delegate.list(resolve(path)).map { entries ->
            entries.map { entry -> entry.copy(path = relative(entry.path)) }
        }

    override suspend fun createDirectory(path: StoragePath): StorageResult<StorageEntry> =
        delegate.createDirectory(resolve(path)).map { it.copy(path = path) }

    override suspend fun openInput(
        path: StoragePath,
        bufferSize: Int,
    ): StorageResult<InputStream> = delegate.openInput(resolve(path), bufferSize)

    override suspend fun openOutput(
        path: StoragePath,
        mimeType: String,
        append: Boolean,
        bufferSize: Int,
    ): StorageResult<OutputStream> =
        delegate.openOutput(resolve(path), mimeType, append, bufferSize)

    override suspend fun replaceFile(
        path: StoragePath,
        mimeType: String,
        writer: suspend (OutputStream) -> Unit,
    ): StorageResult<FileReplaceResult> =
        delegate.replaceFile(resolve(path), mimeType, writer).map { it.copy(path = path) }

    override suspend fun delete(
        path: StoragePath,
        recursive: Boolean,
        progressListener: StorageProgressListener,
    ): StorageResult<DeleteSummary> = delegate.delete(
        resolve(path),
        recursive,
        StorageProgressListener { progress ->
            progressListener.onProgress(
                progress.copy(currentPath = progress.currentPath?.let(::relative)),
            )
        },
    )

    private fun resolve(path: StoragePath): StoragePath =
        if (path.isRoot) subtreeRoot else StoragePath("${subtreeRoot.value}/${path.value}")

    private fun relative(path: StoragePath): StoragePath {
        if (path == subtreeRoot) return StoragePath.ROOT
        val prefix = "${subtreeRoot.value}/"
        require(path.value.startsWith(prefix)) {
            "Delegate returned a path outside the configured subtree."
        }
        return StoragePath(path.value.removePrefix(prefix))
    }
}
