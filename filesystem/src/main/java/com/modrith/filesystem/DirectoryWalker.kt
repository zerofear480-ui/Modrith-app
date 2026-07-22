package com.modrith.filesystem

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class DirectoryWalker(
    private val maxDepth: Int = 128,
    private val maxEntries: Int = 100_000,
) {
    init {
        require(maxDepth >= 0)
        require(maxEntries > 0)
    }

    suspend fun walk(
        provider: StorageProvider,
        root: StoragePath = StoragePath.ROOT,
        includeRoot: Boolean = true,
    ): StorageResult<List<StorageEntry>> {
        val rootResult = provider.metadata(root)
        if (rootResult is StorageResult.Failure) return rootResult
        val rootEntry = (rootResult as StorageResult.Success<StorageEntry>).value

        val entries = mutableListOf<StorageEntry>()
        val pending = ArrayDeque<PendingEntry>()
        pending += PendingEntry(rootEntry, depth = 0)
        return try {
            while (pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val current = pending.removeFirst()
                if (includeRoot || current.depth > 0) entries += current.entry
                if (entries.size > maxEntries) {
                    return failure(
                        StorageErrorCode.LIMIT_EXCEEDED,
                        "Directory traversal exceeded the entry limit.",
                        current.entry.path,
                        recoverable = false,
                    )
                }
                if (current.entry.type == StorageEntryType.DIRECTORY) {
                    if (current.depth >= maxDepth) {
                        return failure(
                            StorageErrorCode.LIMIT_EXCEEDED,
                            "Directory traversal exceeded the depth limit.",
                            current.entry.path,
                            recoverable = false,
                        )
                    }
                    val children = provider.list(current.entry.path)
                    if (children is StorageResult.Failure) return children
                    (children as StorageResult.Success<List<StorageEntry>>).value
                        .sortedBy { it.path.value }
                        .forEach { child ->
                        pending += PendingEntry(child, current.depth + 1)
                    }
                }
            }
            StorageResult.Success(entries.toList())
        } catch (error: CancellationException) {
            throw error
        }
    }

    private data class PendingEntry(
        val entry: StorageEntry,
        val depth: Int,
    )
}
