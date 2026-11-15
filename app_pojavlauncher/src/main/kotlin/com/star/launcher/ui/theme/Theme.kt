package com.star.launcher.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.star.launcher.ui.theme.liquid.LocalStarTheme
import com.star.launcher.ui.theme.studio.StarResolvedTheme
import com.star.launcher.ui.theme.studio.StarThemeManager
import com.star.launcher.ui.theme.studio.blendColors

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
 * Root Material 3 + Liquid Glass theme for STAR Launcher Compose surfaces.
 * Reads [StarThemeManager.resolvedThemeFlow] unless an explicit [resolvedTheme] is supplied
 * (e.g. for isolated live preview rendering).
 */
@Composable
fun AetherTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    resolvedTheme: StarResolvedTheme? = null,
    content: @Composable () -> Unit
) {
    val activeResolved by StarThemeManager.resolvedThemeFlow.collectAsStateWithLifecycle()
    val effectiveTheme = resolvedTheme ?: activeResolved

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> remember(effectiveTheme) { buildStarColorScheme(effectiveTheme) }
        else -> AetherLightColorScheme
    }

    val radiusDp = effectiveTheme.config.glass.cornerRadiusDp
    val shapes = remember(radiusDp) {
        Shapes(
            extraSmall = RoundedCornerShape((radiusDp * 0.38f).coerceIn(4f, 12f).dp),
            small = RoundedCornerShape((radiusDp * 0.52f).coerceIn(6f, 16f).dp),
            medium = RoundedCornerShape((radiusDp * 0.75f).coerceIn(8f, 24f).dp),
            large = RoundedCornerShape(radiusDp.coerceIn(6f, 32f).dp),
            extraLarge = RoundedCornerShape((radiusDp * 1.3f).coerceIn(12f, 36f).dp)
        )
    }

    CompositionLocalProvider(LocalStarTheme provides effectiveTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AetherTypography,
            shapes = shapes,
            content = content
        )
    }
}

fun buildStarColorScheme(theme: StarResolvedTheme) = darkColorScheme(
    primary = theme.primaryAccent,
    onPrimary = if (theme.primaryAccent.luminance() > 0.45f) Color(0xFF061321) else Color(0xFFF5F9FF),
    primaryContainer = blendColors(theme.backgroundBase, theme.primaryAccent, 0.24f),
    onPrimaryContainer = blendColors(theme.textPrimary, theme.primaryAccent, 0.25f),
    secondary = theme.secondaryAccent,
    onSecondary = if (theme.secondaryAccent.luminance() > 0.45f) Color(0xFF051822) else Color(0xFFF4FAFF),
    secondaryContainer = blendColors(theme.backgroundBase, theme.secondaryAccent, 0.20f),
    onSecondaryContainer = blendColors(theme.textPrimary, theme.secondaryAccent, 0.25f),
    tertiary = AetherColors.TertiaryDark,
    onTertiary = AetherColors.OnTertiaryDark,
    tertiaryContainer = AetherColors.TertiaryContainerDark,
    onTertiaryContainer = AetherColors.OnTertiaryContainerDark,
    background = theme.backgroundBase,
    onBackground = theme.textPrimary,
    surface = theme.surfaceBase,
    onSurface = theme.textPrimary,
    surfaceVariant = theme.surfaceVariant,
    onSurfaceVariant = theme.textSecondary,
    outline = theme.borderColor,
    outlineVariant = blendColors(theme.borderColor, theme.backgroundBase, 0.45f),
    error = AetherColors.ErrorDark,
    onError = AetherColors.OnErrorDark,
    errorContainer = AetherColors.ErrorContainerDark,
    onErrorContainer = AetherColors.OnErrorContainerDark
)
