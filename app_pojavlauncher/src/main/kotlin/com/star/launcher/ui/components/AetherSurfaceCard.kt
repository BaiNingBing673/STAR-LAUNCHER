package com.star.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.star.launcher.ui.theme.AetherMonoStyle
import com.star.launcher.ui.theme.AetherSpacing
import com.star.launcher.ui.theme.liquid.LocalStarTheme
import com.star.launcher.ui.theme.liquid.StarLiquidGlassSurface
import com.star.launcher.ui.theme.studio.StarComponentTarget

@Composable
fun AetherSurfaceCard(
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    borderColor: Color? = null,
    componentTarget: StarComponentTarget = StarComponentTarget.CARDS,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    StarLiquidGlassSurface(
        componentTarget = componentTarget,
        modifier = modifier,
        containerColorOverride = containerColor,
        borderColorOverride = borderColor,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(AetherSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm),
            content = content
        )
    }
}

@Composable
fun AetherAccentCard(
    modifier: Modifier = Modifier,
    accentColor: Color? = null,
    componentTarget: StarComponentTarget = StarComponentTarget.CARDS,
    content: @Composable ColumnScope.() -> Unit
) {
    val theme = LocalStarTheme.current
    val resolvedAccent = accentColor ?: theme.styleFor(componentTarget).accentColor
    StarLiquidGlassSurface(
        componentTarget = componentTarget,
        modifier = modifier,
        containerColorOverride = theme.surfaceElevated,
        borderColorOverride = resolvedAccent.copy(alpha = 0.55f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            resolvedAccent.copy(alpha = 0.13f),
                            Color.Transparent
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier.padding(AetherSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm),
                content = content
            )
        }
    }
}

@Composable
fun AetherSectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    action: @Composable (RowScope.() -> Unit)? = null
) {
    val theme = LocalStarTheme.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AetherSpacing.Xxs)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = theme.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (action != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Xs),
                verticalAlignment = Alignment.CenterVertically,
                content = action
            )
        }
    }
}

@Composable
fun AetherMetricChip(
    label: String,
    value: String,
    accentColor: Color? = null,
    modifier: Modifier = Modifier
) {
    val theme = LocalStarTheme.current
    val resolvedAccent = accentColor ?: theme.secondaryAccent
    val chipRadius = (theme.styleFor(StarComponentTarget.CARDS).cornerRadiusDp * 0.65f).coerceIn(6f, 18f)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(chipRadius.dp))
            .background(theme.surfaceVariant.copy(alpha = 0.62f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = theme.textSecondary,
            maxLines = 1
        )
        Text(
            text = value,
            style = AetherMonoStyle.copy(color = resolvedAccent),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
