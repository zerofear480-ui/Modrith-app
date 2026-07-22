package com.modrith.filesystem

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class CopyEngine(
    private val directoryWalker: DirectoryWalker = DirectoryWalker(),
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
) {
    suspend fun copy(
        source: StorageProvider,
        sourcePath: StoragePath,
        destination: StorageProvider,
        destinationPath: StoragePath,
        progressListener: StorageProgressListener = StorageProgressListener.NONE,
    ): StorageResult<CopySummary> = withContext(Dispatchers.IO) {
        val sourceMetadata = source.metadata(sourcePath)
        if (sourceMetadata is StorageResult.Failure) return@withContext sourceMetadata
        val sourceEntry = (sourceMetadata as StorageResult.Success<StorageEntry>).value
        if (sourceEntry.type == StorageEntryType.FILE) {
            return@withContext copySingleFile(
                source,
                sourceEntry,
                destination,
                destinationPath,
                1,
                sourceEntry.sizeBytes,
                progressListener,
            ).map { copiedBytes ->
                CopySummary(copiedFiles = 1, copiedDirectories = 0, copiedBytes = copiedBytes)
            }
        }

        val walked = directoryWalker.walk(source, sourcePath, includeRoot = true)
        if (walked is StorageResult.Failure) return@withContext walked
        val entries = (walked as StorageResult.Success<List<StorageEntry>>).value
        val files = entries.filter { it.type == StorageEntryType.FILE }
        val totalBytes = files.map(StorageEntry::sizeBytes)
            .takeIf { sizes -> sizes.all { it != null } }
            ?.sumOf { requireNotNull(it) }
        var copiedFiles = 0
        var copiedDirectories = 0
        var copiedBytes = 0L

        try {
            val directories = entries
                .filter { it.type == StorageEntryType.DIRECTORY }
                .sortedBy { it.path.value.count { character -> character == '/' } }
            for (directory in directories) {
                currentCoroutineContext().ensureActive()
                val target = destinationPath.append(relativePath(sourcePath, directory.path))
                val result = destination.createDirectory(target)
                if (result is StorageResult.Failure) throw CopyFailure(result)
                copiedDirectories += 1
            }
            for (file in files.sortedBy { it.path.value }) {
                currentCoroutineContext().ensureActive()
                val target = destinationPath.append(relativePath(sourcePath, file.path))
                val result = copySingleFile(
                    source,
                    file,
                    destination,
                    target,
                    files.size,
                    totalBytes,
                    StorageProgressListener { progress ->
                        progressListener.emit(
                            progress.copy(
                                completedFiles = copiedFiles,
                                processedBytes = copiedBytes + progress.processedBytes,
                            ),
                        )
                    },
                )
                if (result is StorageResult.Failure) throw CopyFailure(result)
                copiedFiles += 1
                copiedBytes += (result as StorageResult.Success<Long>).value
                progressListener.emit(
                    StorageProgress(
                        StorageOperation.COPY,
                        target,
                        copiedFiles,
                        files.size,
                        copiedBytes,
                        totalBytes,
                    ),
                )
            }
            StorageResult.Success(CopySummary(copiedFiles, copiedDirectories, copiedBytes))
        } catch (error: CopyFailure) {
            error.result
        } catch (error: CancellationException) {
            throw error
        }
    }

    private suspend fun copySingleFile(
        source: StorageProvider,
        sourceEntry: StorageEntry,
        destination: StorageProvider,
        destinationPath: StoragePath,
        totalFiles: Int,
        totalBytes: Long?,
        progressListener: StorageProgressListener,
    ): StorageResult<Long> {
        val inputResult = source.openInput(sourceEntry.path, bufferSize)
        if (inputResult is StorageResult.Failure) return inputResult
        val sourceInput = (inputResult as StorageResult.Success<java.io.InputStream>).value
        var bytes = 0L
        val replaceResult = destination.replaceFile(
            destinationPath,
            sourceEntry.mimeType ?: StorageProvider.DEFAULT_MIME_TYPE,
        ) { output ->
            sourceInput.use { input ->
                val buffer = ByteArray(bufferSize)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    bytes += read
                    progressListener.emit(
                        StorageProgress(
                            StorageOperation.COPY,
                            destinationPath,
                            0,
                            totalFiles,
                            bytes,
                            totalBytes,
                        ),
                    )
                }
            }
        }
        return when (replaceResult) {
            is StorageResult.Failure -> replaceResult
            is StorageResult.Success -> StorageResult.Success(bytes)
        }
    }

    private fun relativePath(
        root: StoragePath,
        child: StoragePath,
    ): StoragePath = when {
        child == root -> StoragePath.ROOT
        root.isRoot -> child
        else -> StoragePath(child.value.removePrefix("${root.value}/"))
    }

    private fun StoragePath.append(relative: StoragePath): StoragePath = when {
        relative.isRoot -> this
        isRoot -> relative
        else -> StoragePath("$value/${relative.value}")
    }

    private class CopyFailure(
        val result: StorageResult.Failure,
    ) : IOException()
}
