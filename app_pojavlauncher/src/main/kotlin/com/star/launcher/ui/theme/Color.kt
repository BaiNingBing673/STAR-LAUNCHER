package com.star.launcher.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.star.launcher.ui.theme.studio.StarResolvedTheme
import com.star.launcher.ui.theme.studio.blendColors

/**
 * Core color palette and semantic tokens for STAR Launcher.
 * Dynamically synced with STAR UI Studio's active [StarResolvedTheme] while preserving
 * the signature deep-navy & electric-cyan STAR default palette.
 */
object AetherColors {
    // Signature STAR palette (deep electric blue -> sky blue -> crisp cyan)
    var AccentViolet by mutableStateOf(Color(0xFF2563EB))
        private set
    var AccentBlue by mutableStateOf(Color(0xFF5FA8FF))
        private set
    var AccentCyan by mutableStateOf(Color(0xFF5EE2FF))
        private set

    var PrimaryDark by mutableStateOf(Color(0xFF5FA8FF))
        private set
    var OnPrimaryDark by mutableStateOf(Color(0xFF002140))
        private set
    var PrimaryContainerDark by mutableStateOf(Color(0xFF102E52))
        private set
    var OnPrimaryContainerDark by mutableStateOf(Color(0xFFD6EAFF))
        private set

    var SecondaryDark by mutableStateOf(Color(0xFF38BDF8))
        private set
    var OnSecondaryDark by mutableStateOf(Color(0xFF00263A))
        private set
    var SecondaryContainerDark by mutableStateOf(Color(0xFF0E334D))
        private set
    var OnSecondaryContainerDark by mutableStateOf(Color(0xFFCDEEFF))
        private set

    var TertiaryDark by mutableStateOf(Color(0xFF6BE5C3))
        private set
    var OnTertiaryDark by mutableStateOf(Color(0xFF00382C))
        private set
    var TertiaryContainerDark by mutableStateOf(Color(0xFF0C4438))
        private set
    var OnTertiaryContainerDark by mutableStateOf(Color(0xFFB3F8E3))
        private set

    // Deep charcoal / studio obsidian surface hierarchy
    var BackgroundDark by mutableStateOf(Color(0xFF090B11))
        private set
    var OnBackgroundDark by mutableStateOf(Color(0xFFECEFF6))
        private set
    var SurfaceDark by mutableStateOf(Color(0xFF10141F))
        private set
    var OnSurfaceDark by mutableStateOf(Color(0xFFECEFF6))
        private set
    var SurfaceElevatedDark by mutableStateOf(Color(0xFF161B29))
        private set
    var SurfaceVariantDark by mutableStateOf(Color(0xFF1B2233))
        private set
    var OnSurfaceVariantDark by mutableStateOf(Color(0xFF96A2BA))
        private set
    var SurfaceTranslucentDark by mutableStateOf(Color(0xCC141926))
        private set
    var OutlineDark by mutableStateOf(Color(0xFF2D364D))
        private set
    var OutlineVariantDark by mutableStateOf(Color(0xFF1E2536))
        private set

    val ErrorDark = Color(0xFFFF8F96)
    val OnErrorDark = Color(0xFF5A060E)
    val ErrorContainerDark = Color(0xFF3E151B)
    val OnErrorContainerDark = Color(0xFFFFD6D9)

    // Light palette fallback (for system light-theme interoperability)
    val PrimaryLight = Color(0xFF006687)
    val OnPrimaryLight = Color(0xFFFFFFFF)
    val PrimaryContainerLight = Color(0xFFC2E8FF)
    val OnPrimaryContainerLight = Color(0xFF001E2B)

    val BackgroundLight = Color(0xFFF6F8FC)
    val OnBackgroundLight = Color(0xFF131924)
    val SurfaceLight = Color(0xFFFFFFFF)
    val OnSurfaceLight = Color(0xFF131924)
    val SurfaceVariantLight = Color(0xFFE4EAF3)
    val OnSurfaceVariantLight = Color(0xFF47546A)
    val OutlineLight = Color(0xFF75849B)

    // Compatibility & diagnostic semantic tokens
    val TierDetectedBg = Color(0xFF1B253A)
    val TierDetectedFg = Color(0xFF8FB8FF)

    val TierTheoreticalBg = Color(0xFF261F40)
    val TierTheoreticalFg = Color(0xFFC7B2FF)

    val TierTestedBg = Color(0xFF362812)
    val TierTestedFg = Color(0xFFFFCA62)

    val TierVerifiedBg = Color(0xFF0D3326)
    val TierVerifiedFg = Color(0xFF5EE8B0)

    val TierBlockedBg = Color(0xFF3F161D)
    val TierBlockedFg = Color(0xFFFF8A93)

    /**
     * Synchronizes reactive palette tokens from the currently active [StarResolvedTheme].
     */
    fun applyResolvedTheme(resolved: StarResolvedTheme) {
        val primary = resolved.primaryAccent
        val secondary = resolved.secondaryAccent
        val bg = resolved.backgroundBase

        AccentBlue = primary
        AccentCyan = secondary
        AccentViolet = blendColors(primary, resolved.gradientStart, 0.45f)

        PrimaryDark = primary
        OnPrimaryDark = if (primary.luminance() > 0.45f) Color(0xFF061321) else Color(0xFFF5F9FF)
        PrimaryContainerDark = blendColors(bg, primary, 0.24f)
        OnPrimaryContainerDark = blendColors(resolved.textPrimary, primary, 0.25f)

        SecondaryDark = secondary
        OnSecondaryDark = if (secondary.luminance() > 0.45f) Color(0xFF051822) else Color(0xFFF4FAFF)
        SecondaryContainerDark = blendColors(bg, secondary, 0.20f)
        OnSecondaryContainerDark = blendColors(resolved.textPrimary, secondary, 0.25f)

        BackgroundDark = bg
        OnBackgroundDark = resolved.textPrimary
        SurfaceDark = resolved.surfaceBase
        OnSurfaceDark = resolved.textPrimary
        SurfaceElevatedDark = resolved.surfaceElevated
        SurfaceVariantDark = resolved.surfaceVariant
        OnSurfaceVariantDark = resolved.textSecondary
        SurfaceTranslucentDark = resolved.surfaceBase.copy(alpha = resolved.config.glass.backgroundOpacity)
        OutlineDark = resolved.borderColor
        OutlineVariantDark = blendColors(resolved.borderColor, bg, 0.45f)
    }
}
