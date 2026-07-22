package com.modrith.app

import com.modrith.app.install.LauncherDiagnosticsRecorder
import com.modrith.filesystem.CacheProvider
import com.modrith.filesystem.StoragePath
import com.modrith.filesystem.StorageResult
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherDiagnosticsRecorderTest {
    @Test
    fun recordsRuntimeDiscoveryWithoutAssumingASelectedTreeLayout() = withStorage { storage ->
        storage.createDirectory(StoragePath(".minecraft/versions")).success()
        storage.createDirectory(StoragePath(".minecraft/libraries")).success()
        storage.createDirectory(StoragePath(".minecraft/assets")).success()
        storage.replaceFile(
            StoragePath(".minecraft/launcher_profiles.json"),
            "application/json",
        ) {
            it.write("""{"profiles":{}}""".toByteArray())
        }.success()

        val recorder = LauncherDiagnosticsRecorder(
            delegate = storage,
            selectedSafUri = "content://provider/tree/root",
            documentTreeRoot = "content://provider/tree/root/document/root",
            treeDocumentId = "root",
            rootDisplayName = "Minecraft",
        )

        recorder.list(StoragePath.ROOT).success()
        recorder.list(StoragePath(".minecraft")).success()
        recorder.metadata(StoragePath(".minecraft/launcher_profiles.json")).success()
        val diagnostics = recorder.snapshot("No compatible instance was found.")

        assertEquals(
            listOf("<selected-tree>", ".minecraft"),
            diagnostics.visitedDirectories,
        )
        assertEquals(
            listOf(".minecraft/launcher_profiles.json"),
            diagnostics.discoveredFiles,
        )
        assertTrue(diagnostics.launcherProfilesJsonExists)
        assertTrue(diagnostics.versionsDirectoryExists)
        assertTrue(diagnostics.librariesDirectoryExists)
        assertTrue(diagnostics.assetsDirectoryExists)
        assertTrue(diagnostics.dotMinecraftDirectoryExists)
        assertFalse(diagnostics.discoveryLogs.isEmpty())
        assertEquals("No compatible instance was found.", diagnostics.rejectionReason)
    }

    private fun <T> withStorage(block: suspend (CacheProvider) -> T): T = runBlocking {
        val root = Files.createTempDirectory("launcher-diagnostics-test")
        try {
            block(CacheProvider(root))
        } finally {
            deleteRecursively(root)
        }
    }

    private fun deleteRecursively(root: Path) {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun <T> StorageResult<T>.success(): T =
        (this as? StorageResult.Success<T>)?.value ?: error("Expected success, got $this")
}
