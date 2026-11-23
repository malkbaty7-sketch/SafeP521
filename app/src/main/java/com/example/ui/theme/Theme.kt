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
import com.example.util.ThemeManager

private val BronzeLightColorScheme = lightColorScheme(
    primary = BronzePrimaryLight, onPrimary = BronzeOnPrimaryLight,
    primaryContainer = BronzePrimaryContainerLight, onPrimaryContainer = BronzeOnPrimaryContainerLight,
    secondary = BronzeSecondaryLight, onSecondary = BronzeOnSecondaryLight,
    secondaryContainer = BronzeSecondaryContainerLight, onSecondaryContainer = BronzeOnSecondaryContainerLight,
    tertiary = BronzeTertiaryLight, onTertiary = BronzeOnTertiaryLight,
    tertiaryContainer = BronzeTertiaryContainerLight, onTertiaryContainer = BronzeOnTertiaryContainerLight,
    background = BronzeBackgroundLight, onBackground = BronzeOnBackgroundLight,
    surface = BronzeSurfaceLight, onSurface = BronzeOnSurfaceLight,
    surfaceVariant = BronzeSurfaceVariantLight, onSurfaceVariant = BronzeOnSurfaceVariantLight,
    outline = BronzeOutlineLight, outlineVariant = BronzeOutlineVariantLight,
    error = ErrorLight, onError = OnErrorLight
)

private val BronzeDarkColorScheme = darkColorScheme(
    primary = BronzePrimaryDark, onPrimary = BronzeOnPrimaryDark,
    primaryContainer = BronzePrimaryContainerDark, onPrimaryContainer = BronzeOnPrimaryContainerDark,
    secondary = BronzeSecondaryDark, onSecondary = BronzeOnSecondaryDark,
    secondaryContainer = BronzeSecondaryContainerDark, onSecondaryContainer = BronzeOnSecondaryContainerDark,
    tertiary = BronzeTertiaryDark, onTertiary = BronzeOnTertiaryDark,
    tertiaryContainer = BronzeTertiaryContainerDark, onTertiaryContainer = BronzeOnTertiaryContainerDark,
    background = BronzeBackgroundDark, onBackground = BronzeOnBackgroundDark,
    surface = BronzeSurfaceDark, onSurface = BronzeOnSurfaceDark,
    surfaceVariant = BronzeSurfaceVariantDark, onSurfaceVariant = BronzeOnSurfaceVariantDark,
    outline = BronzeOutlineDark, outlineVariant = BronzeOutlineVariantDark,
    error = ErrorDark, onError = OnErrorDark
)

private val OceanLightColorScheme = lightColorScheme(
    primary = OceanPrimaryLight, onPrimary = OceanOnPrimaryLight,
    primaryContainer = OceanPrimaryContainerLight, onPrimaryContainer = OceanOnPrimaryContainerLight,
    secondary = OceanSecondaryLight, onSecondary = OceanOnSecondaryLight,
    secondaryContainer = OceanSecondaryContainerLight, onSecondaryContainer = OceanOnSecondaryContainerLight,
    tertiary = OceanTertiaryLight, onTertiary = OceanOnTertiaryLight,
    tertiaryContainer = OceanTertiaryContainerLight, onTertiaryContainer = OceanOnTertiaryContainerLight,
    background = OceanBackgroundLight, onBackground = OceanOnBackgroundLight,
    surface = OceanSurfaceLight, onSurface = OceanOnSurfaceLight,
    surfaceVariant = OceanSurfaceVariantLight, onSurfaceVariant = OceanOnSurfaceVariantLight,
    outline = OceanOutlineLight, outlineVariant = OceanOutlineVariantLight,
    error = ErrorLight, onError = OnErrorLight
)

private val OceanDarkColorScheme = darkColorScheme(
    primary = OceanPrimaryDark, onPrimary = OceanOnPrimaryDark,
    primaryContainer = OceanPrimaryContainerDark, onPrimaryContainer = OceanOnPrimaryContainerDark,
    secondary = OceanSecondaryDark, onSecondary = OceanOnSecondaryDark,
    secondaryContainer = OceanSecondaryContainerDark, onSecondaryContainer = OceanOnSecondaryContainerDark,
    tertiary = OceanTertiaryDark, onTertiary = OceanOnTertiaryDark,
    tertiaryContainer = OceanTertiaryContainerDark, onTertiaryContainer = OceanOnTertiaryContainerDark,
    background = OceanBackgroundDark, onBackground = OceanOnBackgroundDark,
    surface = OceanSurfaceDark, onSurface = OceanOnSurfaceDark,
    surfaceVariant = OceanSurfaceVariantDark, onSurfaceVariant = OceanOnSurfaceVariantDark,
    outline = OceanOutlineDark, outlineVariant = OceanOutlineVariantDark,
    error = ErrorDark, onError = OnErrorDark
)

private val ForestLightColorScheme = lightColorScheme(
    primary = ForestPrimaryLight, onPrimary = ForestOnPrimaryLight,
    primaryContainer = ForestPrimaryContainerLight, onPrimaryContainer = ForestOnPrimaryContainerLight,
    secondary = ForestSecondaryLight, onSecondary = ForestOnSecondaryLight,
    secondaryContainer = ForestSecondaryContainerLight, onSecondaryContainer = ForestOnSecondaryContainerLight,
    tertiary = ForestTertiaryLight, onTertiary = ForestOnTertiaryLight,
    tertiaryContainer = ForestTertiaryContainerLight, onTertiaryContainer = ForestOnTertiaryContainerLight,
    background = ForestBackgroundLight, onBackground = ForestOnBackgroundLight,
    surface = ForestSurfaceLight, onSurface = ForestOnSurfaceLight,
    surfaceVariant = ForestSurfaceVariantLight, onSurfaceVariant = ForestOnSurfaceVariantLight,
    outline = ForestOutlineLight, outlineVariant = ForestOutlineVariantLight,
    error = ErrorLight, onError = OnErrorLight
)

private val ForestDarkColorScheme = darkColorScheme(
    primary = ForestPrimaryDark, onPrimary = ForestOnPrimaryDark,
    primaryContainer = ForestPrimaryContainerDark, onPrimaryContainer = ForestOnPrimaryContainerDark,
    secondary = ForestSecondaryDark, onSecondary = ForestOnSecondaryDark,
    secondaryContainer = ForestSecondaryContainerDark, onSecondaryContainer = ForestOnSecondaryContainerDark,
    tertiary = ForestTertiaryDark, onTertiary = ForestOnTertiaryDark,
    tertiaryContainer = ForestTertiaryContainerDark, onTertiaryContainer = ForestOnTertiaryContainerDark,
    background = ForestBackgroundDark, onBackground = ForestOnBackgroundDark,
    surface = ForestSurfaceDark, onSurface = ForestOnSurfaceDark,
    surfaceVariant = ForestSurfaceVariantDark, onSurfaceVariant = ForestOnSurfaceVariantDark,
    outline = ForestOutlineDark, outlineVariant = ForestOutlineVariantDark,
    error = ErrorDark, onError = OnErrorDark
)

private val SlateLightColorScheme = lightColorScheme(
    primary = SlatePrimaryLight, onPrimary = SlateOnPrimaryLight,
    primaryContainer = SlatePrimaryContainerLight, onPrimaryContainer = SlateOnPrimaryContainerLight,
    secondary = SlateSecondaryLight, onSecondary = SlateOnSecondaryLight,
    secondaryContainer = SlateSecondaryContainerLight, onSecondaryContainer = SlateOnSecondaryContainerLight,
    tertiary = SlateTertiaryLight, onTertiary = SlateOnTertiaryLight,
    tertiaryContainer = SlateTertiaryContainerLight, onTertiaryContainer = SlateOnTertiaryContainerLight,
    background = SlateBackgroundLight, onBackground = SlateOnBackgroundLight,
    surface = SlateSurfaceLight, onSurface = SlateOnSurfaceLight,
    surfaceVariant = SlateSurfaceVariantLight, onSurfaceVariant = SlateOnSurfaceVariantLight,
    outline = SlateOutlineLight, outlineVariant = SlateOutlineVariantLight,
    error = ErrorLight, onError = OnErrorLight
)

private val SlateDarkColorScheme = darkColorScheme(
    primary = SlatePrimaryDark, onPrimary = SlateOnPrimaryDark,
    primaryContainer = SlatePrimaryContainerDark, onPrimaryContainer = SlateOnPrimaryContainerDark,
    secondary = SlateSecondaryDark, onSecondary = SlateOnSecondaryDark,
    secondaryContainer = SlateSecondaryContainerDark, onSecondaryContainer = SlateOnSecondaryContainerDark,
    tertiary = SlateTertiaryDark, onTertiary = SlateOnTertiaryDark,
    tertiaryContainer = SlateTertiaryContainerDark, onTertiaryContainer = SlateOnTertiaryContainerDark,
    background = SlateBackgroundDark, onBackground = SlateOnBackgroundDark,
    surface = SlateSurfaceDark, onSurface = SlateOnSurfaceDark,
    surfaceVariant = SlateSurfaceVariantDark, onSurfaceVariant = SlateOnSurfaceVariantDark,
    outline = SlateOutlineDark, outlineVariant = SlateOutlineVariantDark,
    error = ErrorDark, onError = OnErrorDark
)


@Composable
fun MyApplicationTheme(
    themeColor: String = ThemeManager.COLOR_BRONZE,
    themeMode: String = ThemeManager.MODE_SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemInDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeManager.MODE_LIGHT -> false
        ThemeManager.MODE_DARK -> true
        else -> systemInDark
    }

    val colorScheme = when (themeColor) {
        ThemeManager.COLOR_OCEAN -> if (isDark) OceanDarkColorScheme else OceanLightColorScheme
        ThemeManager.COLOR_FOREST -> if (isDark) ForestDarkColorScheme else ForestLightColorScheme
        ThemeManager.COLOR_SLATE -> if (isDark) SlateDarkColorScheme else SlateLightColorScheme
        else -> if (isDark) BronzeDarkColorScheme else BronzeLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = SafePlayerShapes,
        content = content
    )
}
