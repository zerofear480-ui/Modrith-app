package com.modrith.filesystem

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class DocumentTreeProvider(
    context: Context,
    val treeUri: Uri,
    private val pathResolver: PathResolver = PathResolver(),
    private val logger: StorageLogger = JvmStorageLogger(),
) : StorageProvider {
    private val resolver = context.applicationContext.contentResolver
    private val root = requireNotNull(DocumentFile.fromTreeUri(context.applicationContext, treeUri)) {
        "A valid content tree URI is required."
    }

    override val providerId: String = "document-tree"
    val resolvedTree = ResolvedDocumentTree(
        selectedTreeUri = treeUri.toString(),
        treeDocumentId = runCatching {
            DocumentsContract.getTreeDocumentId(treeUri)
        }.getOrNull(),
        rootDocumentUri = root.uri.toString(),
        rootDisplayName = root.name,
    )

    init {
        require(treeUri.scheme == ContentResolver.SCHEME_CONTENT) {
            "DocumentTreeProvider requires a content URI."
        }
        logger.info(
            "storage.tree.resolved",
            mapOf(
                "selectedTreeUri" to resolvedTree.selectedTreeUri,
                "treeDocumentId" to resolvedTree.treeDocumentId,
                "rootDocumentUri" to resolvedTree.rootDocumentUri,
                "rootDisplayName" to resolvedTree.rootDisplayName,
            ),
        )
    }

    fun permission(): TreePermission {
        val persisted = resolver.persistedUriPermissions.firstOrNull { it.uri == treeUri }
        return TreePermission(
            treeUri = treeUri.toString(),
            read = persisted?.isReadPermission == true,
            write = persisted?.isWritePermission == true,
            persisted = persisted != null,
        )
    }

    fun persistPermission(
        read: Boolean,
        write: Boolean,
    ): StorageResult<TreePermission> {
        if (!read && !write) {
            return failure(
                StorageErrorCode.PERSIST_PERMISSION_FAILED,
                "At least one URI permission must be requested.",
                StoragePath.ROOT,
                recoverable = false,
            )
        }
        val flags = (if (read) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        return try {
            resolver.takePersistableUriPermission(treeUri, flags)
            val permission = permission()
            if ((read && !permission.read) || (write && !permission.write)) {
                failure(
                    StorageErrorCode.PERSIST_PERMISSION_FAILED,
                    "The requested tree URI permission was not persisted.",
                    StoragePath.ROOT,
                )
            } else {
                logger.info(
                    "storage.tree.permission_persisted",
                    mapOf("treeUri" to treeUri, "read" to read, "write" to write),
                )
                StorageResult.Success(permission)
            }
        } catch (error: SecurityException) {
            logger.warn(
                "storage.tree.permission_failed",
                mapOf("treeUri" to treeUri, "read" to read, "write" to write),
                error,
            )
            failure(
                StorageErrorCode.PERSIST_PERMISSION_FAILED,
                "The document provider rejected persistent URI permission.",
                StoragePath.ROOT,
            )
        }
    }

    fun releasePermission(
        read: Boolean,
        write: Boolean,
    ): StorageResult<TreePermission> {
        val flags = (if (read) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        return try {
            resolver.releasePersistableUriPermission(treeUri, flags)
            StorageResult.Success(permission())
        } catch (error: SecurityException) {
            failure(
                StorageErrorCode.PERSIST_PERMISSION_FAILED,
                "The persisted tree URI permission could not be released.",
                StoragePath.ROOT,
            )
        }
    }

    override suspend fun metadata(path: StoragePath): StorageResult<StorageEntry> =
        withContext(Dispatchers.IO) {
            resolveDocument(path).map { document -> document.toStorageEntry(path) }
        }

    override suspend fun exists(path: StoragePath): StorageResult<Boolean> =
        withContext(Dispatchers.IO) {
            val validated = pathResolver.resolve(path.value)
            if (validated is StorageResult.Failure) {
                validated
            } else {
                StorageResult.Success(
                    findDocument(
                        (validated as StorageResult.Success<StoragePath>).value,
                    ) != null,
                )
            }
        }

    override suspend fun list(path: StoragePath): StorageResult<List<StorageEntry>> =
        withContext(Dispatchers.IO) {
            resolveDocument(path).flatMap { directory ->
                if (!directory.isDirectory) {
                    failure(StorageErrorCode.NOT_A_DIRECTORY, "Path is not a directory.", path)
                } else if (!directory.canRead()) {
                    failure(
                        StorageErrorCode.READ_PERMISSION_DENIED,
                        "Directory is not readable.",
                        path,
                    )
                } else {
                    try {
                        val entries = mutableListOf<StorageEntry>()
                        directory.listFiles()
                            .sortedBy { it.name.orEmpty() }
                            .forEach { child ->
                                val name = child.name
                                    ?: return@withContext failure(
                                        StorageErrorCode.IO_ERROR,
                                        "A document has no display name.",
                                        path,
                                    )
                                val childPath = pathResolver.child(path, name)
                                if (childPath !is StorageResult.Success) {
                                    return@withContext failure(
                                        StorageErrorCode.INVALID_PATH,
                                        "The provider returned an unsafe document name.",
                                        path,
                                        recoverable = false,
                                    )
                                }
                                entries += child.toStorageEntry(childPath.value)
                            }
                        StorageResult.Success(entries.toList())
                    } catch (error: SecurityException) {
                        permissionFailure(read = true, path)
                    }
                }
            }
        }

    override suspend fun createDirectory(path: StoragePath): StorageResult<StorageEntry> =
        withContext(Dispatchers.IO) {
            val validated = pathResolver.resolve(path.value)
            if (validated is StorageResult.Failure) return@withContext validated
            val validatedPath = (validated as StorageResult.Success<StoragePath>).value
            if (validatedPath.isRoot) return@withContext StorageResult.Success(root.toStorageEntry(path))

            var current = root
            var currentPath = StoragePath.ROOT
            for (segment in validatedPath.value.split('/')) {
                currentCoroutineContext().ensureActive()
                val nextPath = (pathResolver.child(currentPath, segment) as StorageResult.Success).value
                val existing = current.findFile(segment)
                current = when {
                    existing == null -> {
                        if (!current.canWrite()) {
                            return@withContext permissionFailure(read = false, nextPath)
                        }
                        current.createDirectory(segment)
                            ?: return@withContext failure(
                                StorageErrorCode.IO_ERROR,
                                "Directory could not be created.",
                                nextPath,
                            )
                    }
                    !existing.isDirectory -> return@withContext failure(
                        StorageErrorCode.NOT_A_DIRECTORY,
                        "A path segment is not a directory.",
                        nextPath,
                    )
                    else -> existing
                }
                currentPath = nextPath
            }
            StorageResult.Success(current.toStorageEntry(validatedPath))
        }

    override suspend fun openInput(
        path: StoragePath,
        bufferSize: Int,
    ): StorageResult<InputStream> = withContext(Dispatchers.IO) {
        resolveDocument(path).flatMap { document ->
            if (!document.isFile) {
                failure(StorageErrorCode.NOT_A_FILE, "Path is not a file.", path)
            } else if (!document.canRead()) {
                permissionFailure(read = true, path)
            } else {
                try {
                    val input = resolver.openInputStream(document.uri)
                        ?: return@flatMap failure(
                            StorageErrorCode.IO_ERROR,
                            "Document provider returned no input stream.",
                            path,
                        )
                    StorageResult.Success(input.buffered(bufferSize))
                } catch (error: FileNotFoundException) {
                    failure(StorageErrorCode.NOT_FOUND, "Document no longer exists.", path)
                } catch (error: SecurityException) {
                    permissionFailure(read = true, path)
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
        resolveOrCreateFile(path, mimeType).flatMap { document ->
            if (!document.canWrite()) {
                permissionFailure(read = false, path)
            } else {
                try {
                    val mode = if (append) "wa" else "rwt"
                    val output = resolver.openOutputStream(document.uri, mode)
                        ?: return@flatMap failure(
                            StorageErrorCode.IO_ERROR,
                            "Document provider returned no output stream.",
                            path,
                        )
                    StorageResult.Success(output.buffered(bufferSize))
                } catch (error: FileNotFoundException) {
                    failure(StorageErrorCode.NOT_FOUND, "Document no longer exists.", path)
                } catch (error: SecurityException) {
                    permissionFailure(read = false, path)
                }
            }
        }
    }

    override suspend fun replaceFile(
        path: StoragePath,
        mimeType: String,
        writer: suspend (OutputStream) -> Unit,
    ): StorageResult<FileReplaceResult> = withContext(Dispatchers.IO) {
        val validated = pathResolver.resolve(path.value)
        if (validated is StorageResult.Failure) return@withContext validated
        val validatedPath = (validated as StorageResult.Success<StoragePath>).value
        if (validatedPath.isRoot) {
            return@withContext failure(
                StorageErrorCode.NOT_A_FILE,
                "Tree root is not a file.",
                path,
            )
        }
        val parentPath = requireNotNull(validatedPath.parent)
        val parentResult = createDirectory(parentPath)
        if (parentResult is StorageResult.Failure) return@withContext parentResult
        val parent = findDocument(parentPath)
            ?: return@withContext failure(
                StorageErrorCode.NOT_FOUND,
                "Destination directory does not exist.",
                parentPath,
            )
        if (!parent.canWrite()) return@withContext permissionFailure(read = false, parentPath)

        val targetName = validatedPath.name
        val temporaryName = ".$targetName.${UUID.randomUUID()}.tmp"
        val backupName = ".$targetName.${UUID.randomUUID()}.bak"
        val temporary = parent.createFile(mimeType, temporaryName)
            ?: return@withContext failure(
                StorageErrorCode.IO_ERROR,
                "Temporary document could not be created.",
                path,
            )
        var backup: DocumentFile? = null
        try {
            val output = resolver.openOutputStream(temporary.uri, "rwt")
                ?: return@withContext failure(
                    StorageErrorCode.IO_ERROR,
                    "Temporary document could not be opened.",
                    path,
                )
            output.buffered().use {
                writer(it)
                it.flush()
            }
            currentCoroutineContext().ensureActive()

            val existing = parent.findFile(targetName)
            val guarantee = if (existing == null) {
                if (temporary.tryRenameTo(targetName)) {
                    ReplaceGuarantee.PROVIDER_RENAME
                } else {
                    copyTemporaryToTarget(parent, temporary, targetName, mimeType, path)
                        ?: return@withContext failure(
                            StorageErrorCode.IO_ERROR,
                            "Temporary document could not be promoted.",
                            path,
                        )
                    ReplaceGuarantee.BEST_EFFORT
                }
            } else {
                if (existing.isDirectory) {
                    return@withContext failure(
                        StorageErrorCode.NOT_A_FILE,
                        "Destination path is a directory.",
                        path,
                    )
                }
                if (!existing.tryRenameTo(backupName)) {
                    copyTemporaryToTarget(parent, temporary, targetName, mimeType, path)
                        ?: return@withContext failure(
                            StorageErrorCode.IO_ERROR,
                            "Temporary document could not replace the destination.",
                            path,
                        )
                } else {
                    backup = parent.findFile(backupName)
                    if (!temporary.tryRenameTo(targetName)) {
                        if (backup?.tryRenameTo(targetName) == true) {
                            backup = null
                        }
                        copyTemporaryToTarget(parent, temporary, targetName, mimeType, path)
                            ?: return@withContext failure(
                                StorageErrorCode.IO_ERROR,
                                "Temporary document could not replace the destination.",
                                path,
                            )
                    }
                }
                backup?.delete()
                backup = null
                ReplaceGuarantee.BEST_EFFORT
            }
            val promoted = parent.findFile(targetName)
                ?: return@withContext failure(
                    StorageErrorCode.IO_ERROR,
                    "Promoted document is not visible.",
                    path,
                )
            val result = FileReplaceResult(path, promoted.length(), guarantee)
            logger.info(
                "storage.tree.replaced",
                mapOf("path" to path.value, "bytes" to result.sizeBytes, "guarantee" to guarantee),
            )
            StorageResult.Success(result)
        } catch (error: CancellationException) {
            temporary.delete()
            backup?.tryRenameTo(targetName)
            throw error
        } catch (error: SecurityException) {
            temporary.delete()
            backup?.tryRenameTo(targetName)
            permissionFailure(read = false, path)
        } catch (error: IOException) {
            temporary.delete()
            backup?.tryRenameTo(targetName)
            ioFailure("Document replacement failed.", path, error)
        } finally {
            parent.findFile(temporaryName)?.delete()
        }
    }

    private suspend fun copyTemporaryToTarget(
        parent: DocumentFile,
        temporary: DocumentFile,
        targetName: String,
        mimeType: String,
        path: StoragePath,
    ): DocumentFile? {
        val target = parent.findFile(targetName) ?: parent.createFile(mimeType, targetName)
            ?: return null
        if (!target.isFile || !target.canWrite()) return null
        val input = resolver.openInputStream(temporary.uri) ?: return null
        val output = resolver.openOutputStream(target.uri, "rwt") ?: run {
            input.close()
            return null
        }
        input.buffered().use { source ->
            output.buffered().use { destination ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    destination.write(buffer, 0, read)
                }
                destination.flush()
            }
        }
        logger.warn(
            "storage.tree.rename_fallback",
            mapOf("path" to path.value),
        )
        temporary.delete()
        return target
    }

    private fun DocumentFile.tryRenameTo(displayName: String): Boolean =
        runCatching { renameTo(displayName) }.getOrDefault(false)

    override suspend fun delete(
        path: StoragePath,
        recursive: Boolean,
        progressListener: StorageProgressListener,
    ): StorageResult<DeleteSummary> = withContext(Dispatchers.IO) {
        if (path.isRoot) {
            return@withContext failure(
                StorageErrorCode.UNSUPPORTED_OPERATION,
                "The selected document tree root cannot be deleted.",
                path,
            )
        }
        resolveDocument(path).flatMap { document ->
            if (document.isDirectory && !recursive && document.listFiles().isNotEmpty()) {
                failure(
                    StorageErrorCode.UNSUPPORTED_OPERATION,
                    "Directory is not empty; recursive deletion is required.",
                    path,
                )
            } else {
                deleteDocument(document, path, recursive, progressListener)
            }
        }
    }

    private suspend fun deleteDocument(
        document: DocumentFile,
        path: StoragePath,
        recursive: Boolean,
        progressListener: StorageProgressListener,
    ): StorageResult<DeleteSummary> {
        var files = 0
        var directories = 0
        var bytes = 0L
        if (document.isDirectory && recursive) {
            for (child in document.listFiles()) {
                currentCoroutineContext().ensureActive()
                val name = child.name
                    ?: return failure(StorageErrorCode.IO_ERROR, "Document has no name.", path)
                val childPath = pathResolver.child(path, name)
                if (childPath is StorageResult.Failure) return childPath
                val resolvedChildPath = (childPath as StorageResult.Success<StoragePath>).value
                val childResult = deleteDocument(child, resolvedChildPath, true, progressListener)
                if (childResult is StorageResult.Failure) return childResult
                val childSummary = (childResult as StorageResult.Success<DeleteSummary>).value
                files += childSummary.deletedFiles
                directories += childSummary.deletedDirectories
                bytes += childSummary.deletedBytes
            }
        }
        val wasDirectory = document.isDirectory
        val size = document.length().takeIf { !wasDirectory } ?: 0
        return if (document.delete()) {
            if (wasDirectory) {
                directories += 1
            } else {
                files += 1
                bytes += size
            }
            progressListener.emit(
                StorageProgress(
                    StorageOperation.DELETE,
                    path,
                    files,
                    null,
                    bytes,
                    null,
                ),
            )
            StorageResult.Success(DeleteSummary(files, directories, bytes))
        } else {
            failure(StorageErrorCode.IO_ERROR, "Document could not be deleted.", path)
        }
    }

    private fun resolveDocument(path: StoragePath): StorageResult<DocumentFile> {
        val validated = pathResolver.resolve(path.value)
        if (validated is StorageResult.Failure) return validated
        val validatedPath = (validated as StorageResult.Success<StoragePath>).value
        return findDocument(validatedPath)?.let(StorageResult<DocumentFile>::Success)
            ?: failure(StorageErrorCode.NOT_FOUND, "Document does not exist.", path)
    }

    private fun findDocument(path: StoragePath): DocumentFile? {
        if (path.isRoot) return root
        var current = root
        path.value.split('/').forEach { segment ->
            current = current.findFile(segment) ?: return null
            if (current != root && current.name != segment) return null
        }
        return current
    }

    private suspend fun resolveOrCreateFile(
        path: StoragePath,
        mimeType: String,
    ): StorageResult<DocumentFile> {
        val validated = pathResolver.resolve(path.value)
        if (validated is StorageResult.Failure) return validated
        val validatedPath = (validated as StorageResult.Success<StoragePath>).value
        if (validatedPath.isRoot) {
            return failure(StorageErrorCode.NOT_A_FILE, "Tree root is not a file.", path)
        }
        findDocument(validatedPath)?.let { existing ->
            return if (existing.isFile) {
                StorageResult.Success(existing)
            } else {
                failure(StorageErrorCode.NOT_A_FILE, "Destination is a directory.", path)
            }
        }
        val parentPath = requireNotNull(validatedPath.parent)
        val parentResult = createDirectory(parentPath)
        if (parentResult is StorageResult.Failure) return parentResult
        val parent = findDocument(parentPath)
            ?: return failure(StorageErrorCode.NOT_FOUND, "Parent directory does not exist.", parentPath)
        val file = parent.createFile(mimeType, validatedPath.name)
            ?: return failure(StorageErrorCode.IO_ERROR, "Document could not be created.", path)
        return StorageResult.Success(file)
    }

    private fun DocumentFile.toStorageEntry(path: StoragePath): StorageEntry = StorageEntry(
        path = path,
        name = name ?: if (path.isRoot) "tree" else path.name,
        type = if (isDirectory) StorageEntryType.DIRECTORY else StorageEntryType.FILE,
        mimeType = type,
        sizeBytes = length().takeIf { isFile && it >= 0 },
        lastModifiedEpochMillis = lastModified().takeIf { it > 0 },
        readable = canRead(),
        writable = canWrite(),
    )

    private fun permissionFailure(
        read: Boolean,
        path: StoragePath,
    ): StorageResult.Failure = failure(
        if (read) {
            StorageErrorCode.READ_PERMISSION_DENIED
        } else {
            StorageErrorCode.WRITE_PERMISSION_DENIED
        },
        if (read) "Read permission is unavailable." else "Write permission is unavailable.",
        path,
    )
}
