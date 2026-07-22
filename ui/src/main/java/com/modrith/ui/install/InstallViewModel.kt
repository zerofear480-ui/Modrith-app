package com.modrith.ui.install

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class InstallViewModel @Inject constructor(
    private val workflow: InstallWorkflow,
) : ViewModel() {
    val state = workflow.state

    fun selectMrPack(uri: String) = workflow.selectMrPack(uri)

    fun selectLauncherTree(uri: String) = workflow.selectLauncherTree(uri)

    fun exportLauncherDiagnostics(uri: String) = workflow.exportLauncherDiagnostics(uri)

    fun selectLauncherInstance(profileId: String) =
        workflow.selectLauncherInstance(profileId)

    fun startInstallation() = workflow.startInstallation()

    fun cancelInstallation() = workflow.cancelInstallation()

    fun retryInstallation() = workflow.retryInstallation()

    fun reset() = workflow.reset()
}
