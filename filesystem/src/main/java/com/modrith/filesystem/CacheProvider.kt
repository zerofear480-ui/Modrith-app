package com.modrith.filesystem

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class CacheProvider(
    rootDirectory: Path,
    private val pathResolver: PathResolver = PathResolver(),
    private val logger: StorageLogger = JvmStorageLogger(),
) : StorageProvider {
    private val root = rootDirectory.toAbsolutePath().normalize()

    override val providerId: String = "cache"

    init {
        Files.createDirectories(root)
        require(!Files.isSymbolicLink(root)) { "Cache root must not be a symbolic link." }
    }

    override suspend fun metadata(path: StoragePath): StorageResult<StorageEntry> =
        withContext(Dispatchers.IO) {
            access(path, requireExisting = true).mapCatching(path) { target ->
                target.toStorageEntry(path)
            }
        }

    override suspend fun exists(path: StoragePath): StorageResult<Boolean> =
        withContext(Dispatchers.IO) {
            access(path, requireExisting = false).map { target ->
                Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)
            }
        }

    override suspend fun list(path: StoragePath): StorageResult<List<StorageEntry>> =
        withContext(Dispatchers.IO) {
            access(path, requireExisting = true).flatMap { directory ->
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    failure(StorageErrorCode.NOT_A_DIRECTORY, "Path is not a directory.", path)
                } else {
                    try {
                        Files.list(directory).use { children ->
                            val entries = buildList {
                                children.sorted().iterator().forEachRemaining { child ->
                                    if (Files.isSymbolicLink(child)) {
                                        throw UnsafeCachePathException()
                                    }
                                    val childPath = pathResolver.child(path, child.fileName.toString())
                                    if (childPath !is StorageResult.Success) {
                                        throw UnsafeCachePathException()
                                    }
                                    add(child.toStorageEntry(childPath.value))
                                }
                            }
                            StorageResult.Success(entries)
                        }
                    } catch (error: UnsafeCachePathException) {
                        failure(
                            StorageErrorCode.INVALID_PATH,
                            "Cache directory contains an unsafe entry.",
                            path,
                        )
                    } catch (error: IOException) {
                        ioFailure("Cache directory could not be listed.", path, error)
                    }
                }
            }
        }

    override suspend fun createDirectory(path: StoragePath): StorageResult<StorageEntry> =
        withContext(Dispatchers.IO) {
            access(path, requireExisting = false).mapCatching(path) { directory ->
                Files.createDirectories(directory)
                verifyExistingPath(directory)
                directory.toStorageEntry(path)
            }
        }

    override suspend fun openInput(
        path: StoragePath,
        bufferSize: Int,
    ): StorageResult<InputStream> = withContext(Dispatchers.IO) {
        access(path, requireExisting = true).flatMap { target ->
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                failure(StorageErrorCode.NOT_A_FILE, "Path is not a regular file.", path)
            } else {
                try {
                    StorageResult.Success(Files.newInputStream(target).buffered(bufferSize))
                } catch (error: IOException) {
                    ioFailure("Cache file could not be opened for reading.", path, error)
                }
            }
        }
    }

    override suspend fun openOutput(
        path: StoragePath,
        mimeType: String,
        append: Boolean,
        bufferSize: Int,
    ): StorageResult<OutputStream> = withContext(Dispatchers.IO) {
        access(path, requireExisting = false).flatMap { target ->
            try {
                val parent = requireNotNull(target.parent)
                Files.createDirectories(parent)
                verifyExistingPath(parent)
                if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                    failure(StorageErrorCode.NOT_A_FILE, "Path is a directory.", path)
                } else {
                    val options = if (append) {
                        arrayOf(
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.APPEND,
                        )
                    } else {
                        arrayOf(
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                        )
                    }
                    StorageResult.Success(Files.newOutputStream(target, *options).buffered(bufferSize))
                }
            } catch (error: IOException) {
                ioFailure("Cache file could not be opened for writing.", path, error)
            }
        }
    }

    override suspend fun replaceFile(
        path: StoragePath,
        mimeType: String,
        writer: suspend (OutputStream) -> Unit,
    ): StorageResult<FileReplaceResult> = withContext(Dispatchers.IO) {
        access(path, requireExisting = false).flatMap { target ->
            val temporary = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.tmp")
            try {
                val parent = requireNotNull(target.parent)
                Files.createDirectories(parent)
                verifyExistingPath(parent)
                Files.newOutputStream(
                    temporary,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                ).buffered().use { output ->
                    writer(output)
                    output.flush()
                }
                currentCoroutineContext().ensureActive()
                val guarantee = try {
                    Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    ReplaceGuarantee.ATOMIC
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                    ReplaceGuarantee.BEST_EFFORT
                }
                val result = FileReplaceResult(path, Files.size(target), guarantee)
                logger.info(
                    "storage.cache.replaced",
                    mapOf("path" to path.value, "bytes" to result.sizeBytes, "guarantee" to guarantee),
                )
                StorageResult.Success(result)
            } catch (error: CancellationException) {
                Files.deleteIfExists(temporary)
                throw error
            } catch (error: IOException) {
                Files.deleteIfExists(temporary)
                ioFailure("Cache file could not be replaced.", path, error)
            } catch (error: RuntimeException) {
                Files.deleteIfExists(temporary)
                internalFailure("Cache replacement failed.", path, error)
            }
        }
    }

    override suspend fun delete(
        path: StoragePath,
        recursive: Boolean,
        progressListener: StorageProgressListener,
    ): StorageResult<DeleteSummary> = withContext(Dispatchers.IO) {
        if (path.isRoot) {
            return@withContext failure(
                StorageErrorCode.UNSUPPORTED_OPERATION,
                "The cache root cannot be deleted.",
                path,
            )
        }
        access(path, requireExisting = true).flatMap { target ->
            try {
                val coroutineContext = currentCoroutineContext()
                if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) && !recursive) {
                    Files.delete(target)
                    StorageResult.Success(DeleteSummary(0, 1, 0))
                } else {
                    val accumulator = DeleteAccumulator()
                    Files.walkFileTree(
                        target,
                        object : SimpleFileVisitor<Path>() {
                            override fun visitFile(
                                file: Path,
                                attrs: BasicFileAttributes,
                            ): FileVisitResult {
                                coroutineContext.ensureActive()
                                val size = attrs.size()
                                Files.delete(file)
                                accumulator.files += 1
                                accumulator.bytes += size
                                progressListener.emit(
                                    StorageProgress(
                                        StorageOperation.DELETE,
                                        relativeStoragePath(file),
                                        accumulator.files,
                                        null,
                                        accumulator.bytes,
                                        null,
                                    ),
                                )
                                return FileVisitResult.CONTINUE
                            }

                            override fun postVisitDirectory(
                                directory: Path,
                                error: IOException?,
                            ): FileVisitResult {
                                if (error != null) throw error
                                Files.delete(directory)
                                accumulator.directories += 1
                                return FileVisitResult.CONTINUE
                            }
                        },
                    )
                    StorageResult.Success(
                        DeleteSummary(accumulator.files, accumulator.directories, accumulator.bytes),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                ioFailure("Cache path could not be deleted.", path, error)
            }
        }
    }

    suspend fun cleanup(
        policy: CacheCleanupPolicy,
        progressListener: StorageProgressListener = StorageProgressListener.NONE,
    ): StorageResult<CacheCleanupResult> = withContext(Dispatchers.IO) {
        try {
            val protected = policy.protectedPaths.mapNotNull { path ->
                access(path, requireExisting = false).getOrNull()
            }
            val files = Files.walk(root).use { paths ->
                buildList {
                    paths.iterator().forEachRemaining(::add)
                }
                    .asSequence()
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .filterNot { Files.isSymbolicLink(it) }
                    .map { path ->
                        CacheFile(
                            path = path,
                            size = Files.size(path),
                            lastModified = Files.getLastModifiedTime(
                                path,
                                LinkOption.NOFOLLOW_LINKS,
                            ).toMillis(),
                        )
                    }
                    .sortedWith(compareBy(CacheFile::lastModified, CacheFile::path))
                    .toList()
            }
            val bytesBefore = files.sumOf(CacheFile::size)
            var bytesAfter = bytesBefore
            var deletedFiles = 0
            var deletedBytes = 0L
            for (file in files) {
                currentCoroutineContext().ensureActive()
                val isProtected = protected.any { protectedPath ->
                    file.path == protectedPath || file.path.startsWith(protectedPath)
                }
                val expired = policy.maxAgeMillis?.let { maxAge ->
                    policy.nowEpochMillis - file.lastModified > maxAge
                } == true
                if (!isProtected && (expired || bytesAfter > policy.maxBytes)) {
                    Files.deleteIfExists(file.path)
                    bytesAfter -= file.size
                    deletedFiles += 1
                    deletedBytes += file.size
                    progressListener.emit(
                        StorageProgress(
                            StorageOperation.CLEANUP,
                            relativeStoragePath(file.path),
                            deletedFiles,
                            null,
                            deletedBytes,
                            bytesBefore,
                        ),
                    )
                }
            }
            removeEmptyDirectories()
            logger.info(
                "storage.cache.cleaned",
                mapOf(
                    "bytesBefore" to bytesBefore,
                    "bytesAfter" to bytesAfter,
                    "deletedFiles" to deletedFiles,
                ),
            )
            StorageResult.Success(
                CacheCleanupResult(bytesBefore, bytesAfter, deletedFiles, deletedBytes),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            ioFailure("Cache cleanup failed.", StoragePath.ROOT, error)
        }
    }

    private fun access(
        path: StoragePath,
        requireExisting: Boolean,
    ): StorageResult<Path> {
        val validated = pathResolver.resolve(path.value)
        if (validated is StorageResult.Failure) return validated
        val validatedPath = (validated as StorageResult.Success<StoragePath>).value
        val target = root.resolve(validatedPath.value).normalize()
        if (!target.startsWith(root)) {
            return failure(StorageErrorCode.INVALID_PATH, "Path escapes the cache root.", path)
        }
        return try {
            verifyExistingAncestors(target)
            if (requireExisting && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                failure(StorageErrorCode.NOT_FOUND, "Storage path does not exist.", path)
            } else if (Files.isSymbolicLink(target)) {
                failure(StorageErrorCode.INVALID_PATH, "Symbolic links are not supported.", path)
            } else {
                StorageResult.Success(target)
            }
        } catch (error: UnsafeCachePathException) {
            failure(
                StorageErrorCode.INVALID_PATH,
                "Symbolic-link traversal is not supported.",
                path,
                recoverable = false,
            )
        } catch (error: IOException) {
            ioFailure("Cache path could not be resolved.", path, error)
        }
    }

    private fun verifyExistingAncestors(target: Path) {
        var current: Path? = target
        while (current != null && current.startsWith(root)) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw UnsafeCachePathException()
            }
            if (current == root) break
            current = current.parent
        }
        verifyExistingPath(root)
    }

    private fun verifyExistingPath(path: Path) {
        val realPath = path.toRealPath(LinkOption.NOFOLLOW_LINKS)
        val realRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS)
        if (!realPath.startsWith(realRoot) || Files.isSymbolicLink(path)) {
            throw UnsafeCachePathException()
        }
    }

    private fun Path.toStorageEntry(path: StoragePath): StorageEntry {
        val attributes = Files.readAttributes(
            this,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (attributes.isSymbolicLink || (!attributes.isDirectory && !attributes.isRegularFile)) {
            throw UnsafeCachePathException()
        }
        return StorageEntry(
            path = path,
            name = if (path.isRoot) root.fileName?.toString().orEmpty() else fileName.toString(),
            type = if (attributes.isDirectory) {
                StorageEntryType.DIRECTORY
            } else {
                StorageEntryType.FILE
            },
            mimeType = if (attributes.isRegularFile) Files.probeContentType(this) else null,
            sizeBytes = attributes.size().takeIf { attributes.isRegularFile },
            lastModifiedEpochMillis = attributes.lastModifiedTime().toMillis(),
            readable = Files.isReadable(this),
            writable = Files.isWritable(this),
        )
    }

    private fun relativeStoragePath(path: Path): StoragePath =
        StoragePath(root.relativize(path.toAbsolutePath().normalize()).joinToString("/"))

    private fun removeEmptyDirectories() {
        Files.walk(root).use { paths ->
            paths
                .filter { it != root }
                .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .sorted(Comparator.reverseOrder())
                .forEach { directory ->
                    runCatching {
                        Files.newDirectoryStream(directory).use { children ->
                            if (!children.iterator().hasNext()) Files.deleteIfExists(directory)
                        }
                    }
                }
        }
    }

    private data class CacheFile(
        val path: Path,
        val size: Long,
        val lastModified: Long,
    )

    private class DeleteAccumulator(
        var files: Int = 0,
        var directories: Int = 0,
        var bytes: Long = 0,
    )

    private class UnsafeCachePathException : IOException()
}

private inline fun <T, R> StorageResult<T>.mapCatching(
    path: StoragePath,
    transform: (T) -> R,
): StorageResult<R> = when (this) {
    is StorageResult.Failure -> this
    is StorageResult.Success -> try {
        StorageResult.Success(transform(value))
    } catch (error: IOException) {
        ioFailure("Storage operation failed.", path, error)
    } catch (error: RuntimeException) {
        internalFailure("Storage operation failed.", path, error)
    }
}

internal inline fun <T, R> StorageResult<T>.flatMap(
    transform: (T) -> StorageResult<R>,
): StorageResult<R> = when (this) {
    is StorageResult.Failure -> this
    is StorageResult.Success -> transform(value)
}

internal fun failure(
    code: StorageErrorCode,
    message: String,
    path: StoragePath?,
    recoverable: Boolean = code != StorageErrorCode.INVALID_PATH,
): StorageResult.Failure = StorageResult.Failure(
    StorageError(code, message, path, recoverable),
)

internal fun ioFailure(
    message: String,
    path: StoragePath?,
    cause: Throwable,
): StorageResult.Failure = failure(
    when (cause) {
        is java.nio.file.AccessDeniedException, is SecurityException ->
            StorageErrorCode.WRITE_PERMISSION_DENIED
        else -> StorageErrorCode.IO_ERROR
    },
    message,
    path,
)

internal fun internalFailure(
    message: String,
    path: StoragePath?,
    cause: Throwable,
): StorageResult.Failure = failure(
    StorageErrorCode.INTERNAL,
    "$message ${cause::class.simpleName.orEmpty()}",
    path,
    recoverable = false,
)

internal fun StorageProgressListener.emit(progress: StorageProgress) {
    runCatching { onProgress(progress) }
}
