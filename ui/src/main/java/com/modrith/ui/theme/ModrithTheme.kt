package com.modrith.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import com.modrith.models.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF176B45),
    secondary = Color(0xFF4B6356),
    tertiary = Color(0xFF315C7B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8DD8AA),
    secondary = Color(0xFFB2CCBC),
    tertiary = Color(0xFFA3CDF0),
)

@Composable
fun ModrithTheme(
    themeMode: ThemeMode,
    useDynamicColors: Boolean,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colorScheme = when {
        useDynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
