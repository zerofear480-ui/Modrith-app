package com.modrith.orchestrator

import com.modrith.launcher.LauncherInstance
import com.modrith.models.DownloadConfiguration
import java.nio.file.Path

class DefaultInstallManager(
    private val coordinator: InstallCoordinator,
) : InstallManager {
    override fun install(
        source: MrPackSource,
        launcherInstance: LauncherInstance,
        cacheDirectory: Path,
        downloadConfiguration: DownloadConfiguration,
    ): InstallSession = coordinator.start(
        InstallRequest(
            source = source,
            launcherInstance = launcherInstance,
            cacheDirectory = cacheDirectory,
            downloadConfiguration = downloadConfiguration,
        ),
    )

    override fun resume(sessionId: String): InstallSession = coordinator.resume(sessionId)
}
