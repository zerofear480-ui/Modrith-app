package com.modrith.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.modrith.ui.home.HomeScreen
import com.modrith.ui.install.InstallConfirmationScreen
import com.modrith.ui.install.InstallErrorScreen
import com.modrith.ui.install.InstallScreen
import com.modrith.ui.install.InstallSuccessScreen
import com.modrith.ui.install.InstallViewModel
import com.modrith.ui.install.InstallationProgressScreen
import com.modrith.ui.navigation.ModrithDestination
import com.modrith.ui.settings.SettingsScreen

@Composable
fun ModrithNavigation(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val installViewModel: InstallViewModel = hiltViewModel()
    val installState by installViewModel.state.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val targetRoute = when (installState.screen) {
        InstallScreen.HOME -> ModrithDestination.Home.route
        InstallScreen.CONFIRMATION -> ModrithDestination.InstallConfirmation.route
        InstallScreen.PROGRESS -> ModrithDestination.InstallProgress.route
        InstallScreen.SUCCESS -> ModrithDestination.InstallSuccess.route
        InstallScreen.ERROR -> ModrithDestination.InstallError.route
    }

    LaunchedEffect(targetRoute, backStackEntry?.destination?.route) {
        val currentRoute = backStackEntry?.destination?.route
        if (currentRoute != targetRoute && currentRoute != ModrithDestination.Settings.route) {
            navController.navigate(targetRoute) {
                launchSingleTop = true
                if (targetRoute == ModrithDestination.Home.route) {
                    popUpTo(ModrithDestination.Home.route) {
                        inclusive = false
                    }
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = ModrithDestination.Home.route,
        modifier = modifier,
    ) {
        composable(ModrithDestination.Home.route) {
            HomeScreen(
                onOpenSettings = {
                    navController.navigate(ModrithDestination.Settings.route)
                },
                viewModel = installViewModel,
            )
        }
        composable(ModrithDestination.InstallConfirmation.route) {
            InstallConfirmationScreen(
                onBack = installViewModel::reset,
                viewModel = installViewModel,
            )
        }
        composable(ModrithDestination.InstallProgress.route) {
            InstallationProgressScreen(viewModel = installViewModel)
        }
        composable(ModrithDestination.InstallSuccess.route) {
            InstallSuccessScreen(
                onDone = {
                    navController.navigate(ModrithDestination.Home.route) {
                        popUpTo(ModrithDestination.Home.route) {
                            inclusive = false
                        }
                        launchSingleTop = true
                    }
                },
                viewModel = installViewModel,
            )
        }
        composable(ModrithDestination.InstallError.route) {
            InstallErrorScreen(
                onChooseAnother = {
                    navController.navigate(ModrithDestination.Home.route) {
                        popUpTo(ModrithDestination.Home.route) {
                            inclusive = false
                        }
                        launchSingleTop = true
                    }
                },
                viewModel = installViewModel,
            )
        }
        composable(ModrithDestination.Settings.route) {
            SettingsScreen(onBack = navController::navigateUp)
        }
    }
}
