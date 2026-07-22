package com.modrith.ui

import com.modrith.ui.install.InstallUiState
import com.modrith.ui.install.LauncherInstanceUi
import com.modrith.ui.install.LauncherSummary
import com.modrith.ui.install.PackSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallUiStateTest {
    @Test
    fun installRequiresPackLauncherAndSelectedInstance() {
        val pack = PackSummary(
            displayName = "pack.mrpack",
            name = "Pack",
            versionId = "1.0",
            minecraftVersion = "1.21.1",
            loader = "Fabric",
            loaderVersion = "0.16.10",
            modCount = 1,
            totalFiles = 1,
            totalDownloadBytes = 10,
            warnings = emptyList(),
        )
        val launcher = LauncherSummary(
            displayName = "CS Launcher V2",
            instances = listOf(LauncherInstanceUi("profile", "Profile", "version")),
            warnings = emptyList(),
        )

        assertFalse(InstallUiState(pack = pack).canInstall)
        assertFalse(InstallUiState(pack = pack, launcher = launcher).canInstall)
        assertTrue(
            InstallUiState(
                pack = pack,
                launcher = launcher,
                selectedLauncherProfileId = "profile",
            ).canInstall,
        )
        assertFalse(
            InstallUiState(
                pack = pack,
                launcher = launcher,
                selectedLauncherProfileId = "profile",
                busy = true,
            ).canInstall,
        )
    }
}
