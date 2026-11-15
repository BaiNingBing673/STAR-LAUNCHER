package com.star.launcher.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val AetherDarkColorScheme = darkColorScheme(
    primary = AetherColors.PrimaryDark,
    onPrimary = AetherColors.OnPrimaryDark,
    primaryContainer = AetherColors.PrimaryContainerDark,
    onPrimaryContainer = AetherColors.OnPrimaryContainerDark,
    secondary = AetherColors.SecondaryDark,
    onSecondary = AetherColors.OnSecondaryDark,
    secondaryContainer = AetherColors.SecondaryContainerDark,
    onSecondaryContainer = AetherColors.OnSecondaryContainerDark,
    tertiary = AetherColors.TertiaryDark,
    onTertiary = AetherColors.OnTertiaryDark,
    tertiaryContainer = AetherColors.TertiaryContainerDark,
    onTertiaryContainer = AetherColors.OnTertiaryContainerDark,
    background = AetherColors.BackgroundDark,
    onBackground = AetherColors.OnBackgroundDark,
    surface = AetherColors.SurfaceDark,
    onSurface = AetherColors.OnSurfaceDark,
    surfaceVariant = AetherColors.SurfaceVariantDark,
    onSurfaceVariant = AetherColors.OnSurfaceVariantDark,
    outline = AetherColors.OutlineDark,
    outlineVariant = AetherColors.OutlineVariantDark,
    error = AetherColors.ErrorDark,
    onError = AetherColors.OnErrorDark,
    errorContainer = AetherColors.ErrorContainerDark,
    onErrorContainer = AetherColors.OnErrorContainerDark
)

private val AetherLightColorScheme = lightColorScheme(
    primary = AetherColors.PrimaryLight,
    onPrimary = AetherColors.OnPrimaryLight,
    primaryContainer = AetherColors.PrimaryContainerLight,
    onPrimaryContainer = AetherColors.OnPrimaryContainerLight,
    background = AetherColors.BackgroundLight,
    onBackground = AetherColors.OnBackgroundLight,
    surface = AetherColors.SurfaceLight,
    onSurface = AetherColors.OnSurfaceLight,
    surfaceVariant = AetherColors.SurfaceVariantLight,
    onSurfaceVariant = AetherColors.OnSurfaceVariantLight,
    outline = AetherColors.OutlineLight
)

val AetherShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(22.dp)
)

object AetherSpacing {
    val Xxs = 2.dp
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 16.dp
    val Lg = 24.dp
    val Xl = 32.dp
}

object AetherElevation {
    val None = 0.dp
    val Level1 = 1.dp
    val Level2 = 4.dp
    val Level3 = 8.dp
}

/**
 * Root Material 3 theme for STAR Launcher Compose surfaces.
 * Dark-first obsidian palette inspired by high-end audio & media studio interfaces.
 */
@Composable
fun AetherTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> AetherDarkColorScheme
        else -> AetherLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AetherTypography,
        shapes = AetherShapes,
        content = content
    )
}
