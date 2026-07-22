package com.modrith.core.data

import com.modrith.models.AppSettings
import com.modrith.models.ThemeMode
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setThemeMode(themeMode: ThemeMode)

    suspend fun setUseDynamicColors(enabled: Boolean)
}
