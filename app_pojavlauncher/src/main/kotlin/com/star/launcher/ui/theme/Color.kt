package com.star.launcher.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Core color palette and semantic tokens for STAR Launcher.
 * Dark-first deep navy & charcoal obsidian palette accented with electric blue and crisp cyan.
 */
object AetherColors {
    // Signature STAR palette (deep electric blue -> sky blue -> crisp cyan)
    val AccentViolet = Color(0xFF2563EB)
    val AccentBlue = Color(0xFF5FA8FF)
    val AccentCyan = Color(0xFF5EE2FF)

    val PrimaryDark = Color(0xFF5FA8FF)
    val OnPrimaryDark = Color(0xFF002140)
    val PrimaryContainerDark = Color(0xFF102E52)
    val OnPrimaryContainerDark = Color(0xFFD6EAFF)

    val SecondaryDark = Color(0xFF38BDF8)
    val OnSecondaryDark = Color(0xFF00263A)
    val SecondaryContainerDark = Color(0xFF0E334D)
    val OnSecondaryContainerDark = Color(0xFFCDEEFF)

    val TertiaryDark = Color(0xFF6BE5C3)
    val OnTertiaryDark = Color(0xFF00382C)
    val TertiaryContainerDark = Color(0xFF0C4438)
    val OnTertiaryContainerDark = Color(0xFFB3F8E3)

    // Deep charcoal / studio obsidian surface hierarchy
    val BackgroundDark = Color(0xFF090B11)
    val OnBackgroundDark = Color(0xFFECEFF6)
    val SurfaceDark = Color(0xFF10141F)
    val OnSurfaceDark = Color(0xFFECEFF6)
    val SurfaceElevatedDark = Color(0xFF161B29)
    val SurfaceVariantDark = Color(0xFF1B2233)
    val OnSurfaceVariantDark = Color(0xFF96A2BA)
    val SurfaceTranslucentDark = Color(0xCC141926)
    val OutlineDark = Color(0xFF2D364D)
    val OutlineVariantDark = Color(0xFF1E2536)

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
}
