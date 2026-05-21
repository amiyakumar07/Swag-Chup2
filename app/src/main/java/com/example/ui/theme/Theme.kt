package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = BentoPurplePrimary,
    secondary = BentoPurpleSecondary,
    tertiary = BentoPinkContainer,
    background = BentoBackgroundDark,
    surface = BentoSurfaceDark,
    onPrimary = BentoOnSurfaceDark,
    onSecondary = BentoBackgroundDark,
    onTertiary = BentoBackgroundDark,
    onBackground = BentoOnSurfaceDark,
    onSurface = BentoOnSurfaceDark,
    surfaceVariant = BentoPurpleContainerDark,
    onSurfaceVariant = BentoOnPurpleContainerDark,
    outline = BentoBorderDark
)

private val LightColorScheme = lightColorScheme(
    primary = BentoPurplePrimary,
    secondary = BentoPurpleSecondary,
    tertiary = BentoPinkContainer,
    background = BentoBackgroundLight,
    surface = BentoSurfaceLight,
    onPrimary = BentoBackgroundLight,
    onSecondary = BentoOnSurfaceLight,
    onTertiary = BentoOnSurfaceLight,
    onBackground = BentoOnSurfaceLight,
    onSurface = BentoOnSurfaceLight,
    surfaceVariant = BentoPurpleContainer,
    onSurfaceVariant = BentoOnPurpleContainer,
    outline = BentoBorderLight
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color matches wallpaper palette on newer Android, but we disable it
    // by default here to ensure our brand-specific custom colors apply perfectly!
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
