package com.modrith.app.install

import com.modrith.filesystem.StorageEntry
import com.modrith.filesystem.StorageEntryType
import com.modrith.filesystem.StoragePath
import com.modrith.filesystem.StorageProvider
import com.modrith.filesystem.StorageResult
import com.modrith.ui.install.LauncherDiagnosticsUi
import java.io.InputStream
import java.util.Locale

class LauncherDiagnosticsRecorder(
    private val delegate: StorageProvider,
    private val selectedSafUri: String,
    private val documentTreeRoot: String?,
    private val treeDocumentId: String?,
    private val rootDisplayName: String?,
) : StorageProvider by delegate {
    private val visitedDirectories = linkedSetOf<String>()
    private val discoveredFiles = linkedSetOf<String>()
    private val discoveredEntries = linkedMapOf<String, StorageEntryType>()
    private val logs = mutableListOf<String>()

    init {
        log("Selected SAF URI: $selectedSafUri")
        log("Document tree root: ${documentTreeRoot ?: "<unavailable>"}")
        log("Tree document ID: ${treeDocumentId ?: "<unavailable>"}")
        log("Root display name: ${rootDisplayName ?: "<unavailable>"}")
    }

    override suspend fun metadata(path: StoragePath): StorageResult<StorageEntry> {
        val result = delegate.metadata(path)
        when (result) {
            is StorageResult.Success -> {
                recordEntry(result.value)
                log(
                    "METADATA ${path.displayValue()} -> " +
                        "${result.value.type} readable=${result.value.readable} " +
                        "writable=${result.value.writable}",
                )
            }
            is StorageResult.Failure -> log(
                "METADATA ${path.displayValue()} -> " +
                    "${result.error.code}: ${result.error.message}",
            )
        }
        return result
    }

    override suspend fun exists(path: StoragePath): StorageResult<Boolean> {
        val result = delegate.exists(path)
        when (result) {
            is StorageResult.Success ->
                log("EXISTS ${path.displayValue()} -> ${result.value}")
            is StorageResult.Failure -> log(
                "EXISTS ${path.displayValue()} -> " +
                    "${result.error.code}: ${result.error.message}",
            )
        }
        return result
    }

    override suspend fun list(path: StoragePath): StorageResult<List<StorageEntry>> {
        val displayedPath = path.displayValue()
        visitedDirectories += displayedPath
        log("VISIT DIRECTORY $displayedPath")
        val result = delegate.list(path)
        when (result) {
            is StorageResult.Success -> {
                log("LIST $displayedPath -> ${result.value.size} entries")
                result.value.forEach { entry ->
                    recordEntry(entry)
                    log(
                        "DISCOVERED ${entry.type} ${entry.path.displayValue()} " +
                            "readable=${entry.readable} writable=${entry.writable}" +
                            entry.sizeBytes?.let { " size=$it" }.orEmpty(),
                    )
                }
            }
            is StorageResult.Failure -> log(
                "LIST $displayedPath -> ${result.error.code}: ${result.error.message}",
            )
        }
        return result
    }

    override suspend fun openInput(
        path: StoragePath,
        bufferSize: Int,
    ): StorageResult<InputStream> {
        val result = delegate.openInput(path, bufferSize)
        when (result) {
            is StorageResult.Success -> log("OPEN INPUT ${path.displayValue()} -> success")
            is StorageResult.Failure -> log(
                "OPEN INPUT ${path.displayValue()} -> " +
                    "${result.error.code}: ${result.error.message}",
            )
        }
        return result
    }

    fun record(message: String) {
        log(message)
    }

    fun snapshot(
        rejectionReason: String?,
        exportStatus: String? = null,
    ): LauncherDiagnosticsUi {
        val normalizedRootName = rootDisplayName?.lowercase(Locale.ROOT)
        return LauncherDiagnosticsUi(
            selectedSafUri = selectedSafUri,
            documentTreeRoot = documentTreeRoot,
            treeDocumentId = treeDocumentId,
            rootDisplayName = rootDisplayName,
            visitedDirectories = visitedDirectories.toList(),
            discoveredFiles = discoveredFiles.toList(),
            launcherProfilesJsonExists = hasEntry("launcher_profiles.json", StorageEntryType.FILE),
            versionsDirectoryExists = hasEntry("versions", StorageEntryType.DIRECTORY),
            librariesDirectoryExists = hasEntry("libraries", StorageEntryType.DIRECTORY),
            assetsDirectoryExists = hasEntry("assets", StorageEntryType.DIRECTORY),
            dotMinecraftDirectoryExists =
                normalizedRootName == ".minecraft" ||
                    hasEntry(".minecraft", StorageEntryType.DIRECTORY),
            rejectionReason = rejectionReason,
            discoveryLogs = logs.toList(),
            exportStatus = exportStatus,
        )
    }

    private fun recordEntry(entry: StorageEntry) {
        val displayedPath = entry.path.displayValue()
        discoveredEntries[displayedPath] = entry.type
        if (entry.type == StorageEntryType.FILE) {
            discoveredFiles += displayedPath
        }
    }

    private fun hasEntry(
        name: String,
        type: StorageEntryType,
    ): Boolean = discoveredEntries.any { (path, entryType) ->
        entryType == type && path.substringAfterLast('/').equals(name, ignoreCase = true)
    }

    private fun log(message: String) {
        logs += "%04d  %s".format(Locale.ROOT, logs.size + 1, message)
    }

    private fun StoragePath.displayValue(): String = value.ifEmpty { "<selected-tree>" }
}
