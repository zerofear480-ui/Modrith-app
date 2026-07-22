package com.modrith.ui.install

import kotlinx.coroutines.flow.StateFlow

interface InstallWorkflow {
    val state: StateFlow<InstallUiState>

    fun selectMrPack(uri: String)

    fun selectLauncherTree(uri: String)

    fun exportLauncherDiagnostics(uri: String)

    fun selectLauncherInstance(profileId: String)

    fun startInstallation()

    fun cancelInstallation()

    fun retryInstallation()

    fun reset()
}

enum class InstallScreen {
    HOME,
    CONFIRMATION,
    PROGRESS,
    SUCCESS,
    ERROR,
}

data class InstallUiState(
    val screen: InstallScreen = InstallScreen.HOME,
    val busy: Boolean = false,
    val pack: PackSummary? = null,
    val launcher: LauncherSummary? = null,
    val selectedLauncherProfileId: String? = null,
    val progress: InstallProgressUi = InstallProgressUi(),
    val logs: List<InstallLogUi> = emptyList(),
    val result: InstallResultUi? = null,
    val error: InstallErrorUi? = null,
    val resumableSessionId: String? = null,
    val launcherDiagnostics: LauncherDiagnosticsUi? = null,
) {
    val canInstall: Boolean
        get() = pack != null &&
            launcher != null &&
            selectedLauncherProfileId != null &&
            !busy
}

data class LauncherDiagnosticsUi(
    val selectedSafUri: String,
    val documentTreeRoot: String?,
    val treeDocumentId: String?,
    val rootDisplayName: String?,
    val visitedDirectories: List<String>,
    val discoveredFiles: List<String>,
    val launcherProfilesJsonExists: Boolean,
    val versionsDirectoryExists: Boolean,
    val librariesDirectoryExists: Boolean,
    val assetsDirectoryExists: Boolean,
    val dotMinecraftDirectoryExists: Boolean,
    val rejectionReason: String?,
    val discoveryLogs: List<String>,
    val exportStatus: String? = null,
)

data class PackSummary(
    val displayName: String,
    val name: String,
    val versionId: String,
    val minecraftVersion: String,
    val loader: String,
    val loaderVersion: String,
    val modCount: Int,
    val totalFiles: Int,
    val totalDownloadBytes: Long?,
    val warnings: List<String>,
)

data class LauncherSummary(
    val displayName: String,
    val instances: List<LauncherInstanceUi>,
    val warnings: List<String>,
)

data class LauncherInstanceUi(
    val profileId: String,
    val name: String,
    val versionId: String,
)

data class InstallProgressUi(
    val title: String = "Preparing installation",
    val message: String = "Waiting to start.",
    val percentage: Float = 0f,
    val filesCompleted: Int = 0,
    val totalFiles: Int = 0,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val bytesPerSecond: Long = 0,
    val estimatedRemainingMillis: Long? = null,
    val currentFile: String? = null,
)

data class InstallLogUi(
    val timestampEpochMillis: Long,
    val level: String,
    val source: String,
    val message: String,
)

data class InstallResultUi(
    val packName: String,
    val launcherInstanceName: String,
    val installedFiles: Int,
    val installedBytes: Long,
)

data class InstallErrorUi(
    val title: String,
    val message: String,
    val action: String,
    val recoverable: Boolean,
    val code: String,
)
