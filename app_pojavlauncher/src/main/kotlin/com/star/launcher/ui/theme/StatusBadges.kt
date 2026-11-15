package com.star.launcher.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Visual evidence tiers matching STAR Launcher's compatibility policy:
 * hardware capabilities, theoretical support, matrix tests, and verified runtime runs
 * are never conflated.
 */
enum class CompatibilityBadgeTier {
    DETECTED,
    THEORETICAL,
    TESTED,
    VERIFIED_RUNTIME,
    BLOCKED
}

data class CompatibilityBadgeStyle(
    val label: String,
    val shortLabel: String,
    val background: Color,
    val foreground: Color
)

/**
 * Returns deterministic display copy and semantic colors for each [CompatibilityBadgeTier].
 */
fun badgeStyleFor(tier: CompatibilityBadgeTier): CompatibilityBadgeStyle = when (tier) {
    CompatibilityBadgeTier.DETECTED -> CompatibilityBadgeStyle(
        label = "Detected",
        shortLabel = "DET",
        background = AetherColors.TierDetectedBg,
        foreground = AetherColors.TierDetectedFg
    )
    CompatibilityBadgeTier.THEORETICAL -> CompatibilityBadgeStyle(
        label = "Theoretical",
        shortLabel = "THEO",
        background = AetherColors.TierTheoreticalBg,
        foreground = AetherColors.TierTheoreticalFg
    )
    CompatibilityBadgeTier.TESTED -> CompatibilityBadgeStyle(
        label = "Tested",
        shortLabel = "TEST",
        background = AetherColors.TierTestedBg,
        foreground = AetherColors.TierTestedFg
    )
    CompatibilityBadgeTier.VERIFIED_RUNTIME -> CompatibilityBadgeStyle(
        label = "Verified Runtime",
        shortLabel = "VER",
        background = AetherColors.TierVerifiedBg,
        foreground = AetherColors.TierVerifiedFg
    )
    CompatibilityBadgeTier.BLOCKED -> CompatibilityBadgeStyle(
        label = "Blocked",
        shortLabel = "BLK",
        background = AetherColors.TierBlockedBg,
        foreground = AetherColors.TierBlockedFg
    )
}

@Composable
fun CompatibilityTierBadge(
    tier: CompatibilityBadgeTier,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val style = badgeStyleFor(tier)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = style.background,
        border = BorderStroke(1.dp, style.foreground.copy(alpha = 0.35f))
    ) {
        Text(
            text = if (compact) style.shortLabel else style.label,
            style = MaterialTheme.typography.labelSmall,
            color = style.foreground,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
