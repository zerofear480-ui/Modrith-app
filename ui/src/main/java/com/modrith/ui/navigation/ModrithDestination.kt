package com.modrith.ui.navigation

sealed class ModrithDestination(
    val route: String,
    val label: String,
) {
    data object Home : ModrithDestination("home", "Home")
    data object InstallConfirmation : ModrithDestination("install/confirmation", "Confirm")
    data object InstallProgress : ModrithDestination("install/progress", "Installing")
    data object InstallSuccess : ModrithDestination("install/success", "Installed")
    data object InstallError : ModrithDestination("install/error", "Installation error")
    data object Settings : ModrithDestination("settings", "Settings")
}
