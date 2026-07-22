package com.modrith.filesystem

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

class TestDocumentsProvider : DocumentsProvider() {
    private lateinit var rootDirectory: File

    override fun onCreate(): Boolean {
        rootDirectory = File(requireNotNull(context).cacheDir, "documents-provider-root")
        rootDirectory.mkdirs()
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: RootColumns
        return MatrixCursor(columns).apply {
            newRow().apply {
                add(Root.COLUMN_ROOT_ID, ROOT_ID)
                add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
                add(Root.COLUMN_TITLE, "Filesystem test root")
                add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY)
                add(Root.COLUMN_MIME_TYPES, "*/*")
                add(Root.COLUMN_AVAILABLE_BYTES, rootDirectory.usableSpace)
            }
        }
    }

    override fun queryDocument(
        documentId: String,
        projection: Array<out String>?,
    ): Cursor = MatrixCursor(projection ?: DocumentColumns).apply {
        includeDocument(newRow(), documentId, fileForId(documentId))
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val parent = fileForId(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException(parentDocumentId)
        return MatrixCursor(projection ?: DocumentColumns).apply {
            parent.listFiles().orEmpty().sortedBy(File::getName).forEach { child ->
                includeDocument(newRow(), idForFile(child), child)
            }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = fileForId(documentId)
        if (!file.exists() || file.isDirectory) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        validateName(displayName)
        val parent = fileForId(parentDocumentId)
        val target = File(parent, displayName)
        if (target.exists()) throw IllegalStateException("Document already exists")
        val created = if (mimeType == Document.MIME_TYPE_DIR) {
            target.mkdir()
        } else {
            target.createNewFile()
        }
        if (!created) throw FileNotFoundException(displayName)
        return idForFile(target)
    }

    override fun deleteDocument(documentId: String) {
        val target = fileForId(documentId)
        if (target == rootDirectory || !target.deleteRecursively()) {
            throw FileNotFoundException(documentId)
        }
    }

    override fun renameDocument(
        documentId: String,
        displayName: String,
    ): String {
        validateName(displayName)
        val source = fileForId(documentId)
        val target = File(requireNotNull(source.parentFile), displayName)
        if (target.exists() || !source.renameTo(target)) {
            throw FileNotFoundException(documentId)
        }
        return idForFile(target)
    }

    override fun isChildDocument(
        parentDocumentId: String,
        documentId: String,
    ): Boolean {
        val parent = fileForId(parentDocumentId).canonicalFile
        val child = fileForId(documentId).canonicalFile
        return child != parent && child.path.startsWith("${parent.path}${File.separator}")
    }

    private fun includeDocument(
        row: MatrixCursor.RowBuilder,
        documentId: String,
        file: File,
    ) {
        val directory = file.isDirectory
        row.add(Document.COLUMN_DOCUMENT_ID, documentId)
        row.add(Document.COLUMN_DISPLAY_NAME, if (file == rootDirectory) "root" else file.name)
        row.add(
            Document.COLUMN_MIME_TYPE,
            if (directory) Document.MIME_TYPE_DIR else "application/octet-stream",
        )
        row.add(
            Document.COLUMN_FLAGS,
            if (directory) {
                Document.FLAG_DIR_SUPPORTS_CREATE or
                    Document.FLAG_SUPPORTS_DELETE or
                    Document.FLAG_SUPPORTS_RENAME
            } else {
                Document.FLAG_SUPPORTS_WRITE or
                    Document.FLAG_SUPPORTS_DELETE or
                    Document.FLAG_SUPPORTS_RENAME
            },
        )
        row.add(Document.COLUMN_SIZE, if (directory) 0 else file.length())
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
    }

    private fun fileForId(documentId: String): File {
        if (documentId == ROOT_ID) return rootDirectory
        if (!documentId.startsWith("$ROOT_ID/")) throw FileNotFoundException(documentId)
        val relative = documentId.removePrefix("$ROOT_ID/")
        val target = File(rootDirectory, relative).canonicalFile
        val root = rootDirectory.canonicalFile
        if (!target.path.startsWith("${root.path}${File.separator}")) {
            throw FileNotFoundException(documentId)
        }
        return target
    }

    private fun idForFile(file: File): String {
        val root = rootDirectory.canonicalFile
        val target = file.canonicalFile
        return if (target == root) {
            ROOT_ID
        } else {
            "$ROOT_ID/${target.relativeTo(root).invariantSeparatorsPath}"
        }
    }

    private fun validateName(displayName: String) {
        require(displayName.isNotBlank())
        require('/' !in displayName)
        require('\\' !in displayName)
        require(displayName != "." && displayName != "..")
    }

    private companion object {
        const val ROOT_ID = "root"
        val RootColumns = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_AVAILABLE_BYTES,
        )
        val DocumentColumns = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
