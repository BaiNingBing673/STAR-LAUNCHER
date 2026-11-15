package com.star.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.star.launcher.ui.theme.AetherSpacing
import com.star.launcher.ui.theme.liquid.LocalStarTheme
import com.star.launcher.ui.theme.liquid.StarLiquidGlassSurface
import com.star.launcher.ui.theme.studio.StarComponentTarget
import com.star.launcher.ui.theme.studio.blendColors

@Composable
fun AetherPrimaryPlayButton(
    label: String,
    subtitle: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalStarTheme.current
    val btnStyle = theme.styleFor(StarComponentTarget.BUTTONS)
    val shape = RoundedCornerShape(btnStyle.cornerRadiusDp.dp)
    val accent = btnStyle.accentColor
    val secondary = theme.secondaryAccent

    val gradient = if (enabled) {
        if (btnStyle.gradientEnabled) {
            Brush.horizontalGradient(
                listOf(
                    blendColors(btnStyle.gradientStart, accent, 0.55f),
                    accent,
                    secondary
                )
            )
        } else {
            Brush.horizontalGradient(listOf(accent, accent))
        }
    } else {
        Brush.horizontalGradient(listOf(theme.surfaceVariant, theme.surfaceVariant))
    }

    val fgColor = if (enabled) {
        if (accent.luminance() > 0.42f) Color(0xFF06101B) else Color(0xFFF6FAFF)
    } else {
        theme.textSecondary
    }

    StarLiquidGlassSurface(
        componentTarget = StarComponentTarget.BUTTONS,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp),
        shape = shape,
        containerColorOverride = if (enabled) accent else theme.surfaceVariant,
        borderColorOverride = if (enabled) secondary else theme.borderColor,
        onClick = onClick,
        enabled = enabled,
        contentColor = fgColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradient)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (enabled) fgColor.copy(alpha = 0.18f) else theme.surfaceBase.copy(alpha = 0.7f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AetherIcons.Play,
                    contentDescription = label,
                    tint = fgColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(AetherSpacing.Md))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = fgColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = fgColor.copy(alpha = 0.80f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun AetherPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val theme = LocalStarTheme.current
    val btnStyle = theme.styleFor(StarComponentTarget.BUTTONS)
    val accent = btnStyle.accentColor
    val container = if (enabled) accent else theme.surfaceVariant
    val fg = if (enabled) {
        if (accent.luminance() > 0.42f) Color(0xFF051320) else Color(0xFFF6FAFF)
    } else {
        theme.textSecondary
    }
    val shape = RoundedCornerShape(btnStyle.cornerRadiusDp.dp)

    StarLiquidGlassSurface(
        componentTarget = StarComponentTarget.BUTTONS,
        modifier = modifier.heightIn(min = 44.dp),
        shape = shape,
        containerColorOverride = container,
        borderColorOverride = if (enabled) theme.highlightColor.copy(alpha = 0.45f) else theme.borderColor,
        onClick = onClick,
        enabled = enabled,
        contentColor = fg
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(AetherSpacing.Sm))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun AetherTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    containerColor: Color? = null,
    contentColor: Color? = null
) {
    val theme = LocalStarTheme.current
    val btnStyle = theme.styleFor(StarComponentTarget.BUTTONS)
    val resolvedBg = if (enabled) {
        containerColor ?: theme.surfaceVariant
    } else {
        theme.surfaceVariant.copy(alpha = 0.45f)
    }
    val resolvedFg = if (enabled) {
        contentColor ?: theme.textPrimary
    } else {
        theme.textSecondary
    }
    val shape = RoundedCornerShape(btnStyle.cornerRadiusDp.dp)

    StarLiquidGlassSurface(
        componentTarget = StarComponentTarget.BUTTONS,
        modifier = modifier.heightIn(min = 42.dp),
        shape = shape,
        containerColorOverride = resolvedBg,
        borderColorOverride = if (enabled) btnStyle.accentColor.copy(alpha = 0.35f) else theme.borderColor.copy(alpha = 0.3f),
        onClick = onClick,
        enabled = enabled,
        contentColor = resolvedFg
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = resolvedFg,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(AetherSpacing.Sm))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = resolvedFg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun AetherOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val theme = LocalStarTheme.current
    val btnStyle = theme.styleFor(StarComponentTarget.BUTTONS)
    val resolvedFg = if (enabled) theme.textPrimary else theme.textSecondary.copy(alpha = 0.6f)
    val shape = RoundedCornerShape(btnStyle.cornerRadiusDp.dp)

    StarLiquidGlassSurface(
        componentTarget = StarComponentTarget.BUTTONS,
        modifier = modifier.heightIn(min = 42.dp),
        shape = shape,
        containerColorOverride = theme.surfaceBase.copy(alpha = 0.38f),
        borderColorOverride = if (enabled) theme.borderColor else theme.borderColor.copy(alpha = 0.4f),
        onClick = onClick,
        enabled = enabled,
        contentColor = resolvedFg
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = resolvedFg,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(AetherSpacing.Sm))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = resolvedFg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
