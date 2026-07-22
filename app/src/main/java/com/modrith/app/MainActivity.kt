package com.modrith.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modrith.app.navigation.ModrithNavigation
import com.modrith.ui.settings.SettingsViewModel
import com.modrith.ui.theme.ModrithTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

            ModrithTheme(
                themeMode = settings.themeMode,
                useDynamicColors = settings.useDynamicColors,
            ) {
                ModrithNavigation(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
