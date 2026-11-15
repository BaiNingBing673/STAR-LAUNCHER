package com.star.launcher.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.star.launcher.ui.theme.AetherColors
import com.star.launcher.ui.theme.AetherSpacing

data class AetherNavDestination<T>(
    val id: T,
    val label: String,
    val icon: ImageVector
)

@Composable
fun AetherBrandMark(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(30.dp)) {
        val w = size.width
        val h = size.height
        // Geometric shooting-star comet matching the STAR emblem
        val coreComet = Path().apply {
            moveTo(w * 0.14f, h * 0.86f)
            lineTo(w * 0.88f, h * 0.12f)
            lineTo(w * 0.42f, h * 0.72f)
            lineTo(w * 0.62f, h * 0.72f)
            close()
        }
        drawPath(
            path = coreComet,
            brush = Brush.linearGradient(
                listOf(AetherColors.OnBackgroundDark, AetherColors.AccentCyan, AetherColors.AccentBlue)
            )
        )
        // Upper and lower comet speed streaks
        drawLine(
            color = AetherColors.AccentCyan.copy(alpha = 0.9f),
            start = Offset(w * 0.22f, h * 0.56f),
            end = Offset(w * 0.68f, h * 0.18f),
            strokeWidth = w * 0.065f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = AetherColors.AccentBlue.copy(alpha = 0.85f),
            start = Offset(w * 0.44f, h * 0.78f),
            end = Offset(w * 0.84f, h * 0.36f),
            strokeWidth = w * 0.06f,
            cap = StrokeCap.Round
        )
        // Accent star glint
        drawCircle(
            color = AetherColors.AccentCyan,
            radius = w * 0.045f,
            center = Offset(w * 0.74f, h * 0.58f)
        )
    }
}

@Composable
fun AetherTopAppBar(
    activeAccountLabel: String?,
    runtimeHealthy: Boolean,
    onAccountClick: () -> Unit,
    onRuntimeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = AetherColors.BackgroundDark.copy(alpha = 0.94f)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = AetherSpacing.Md, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AetherBrandMark()
                    Column {
                        Text(
                            text = "STAR",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.6.sp
                            ),
                            color = AetherColors.OnBackgroundDark
                        )
                        Text(
                            text = "YOUR MINECRAFT. YOUR UNIVERSE.",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.0.sp),
                            color = AetherColors.OnSurfaceVariantDark
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
                    // Runtime health pill
                    Surface(
                        onClick = onRuntimeClick,
                        shape = RoundedCornerShape(999.dp),
                        color = if (runtimeHealthy) AetherColors.TierVerifiedBg else AetherColors.TierBlockedBg,
                        border = BorderStroke(
                            1.dp,
                            if (runtimeHealthy) {
                                AetherColors.TierVerifiedFg.copy(alpha = 0.35f)
                            } else {
                                AetherColors.TierBlockedFg.copy(alpha = 0.45f)
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (runtimeHealthy) AetherColors.TierVerifiedFg else AetherColors.TierBlockedFg
                                    )
                            )
                            Text(
                                text = if (runtimeHealthy) "JRE Ready" else "JRE Missing",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (runtimeHealthy) AetherColors.TierVerifiedFg else AetherColors.TierBlockedFg
                            )
                        }
                    }

                    // Active account selector pill
                    Surface(
                        onClick = onAccountClick,
                        shape = RoundedCornerShape(999.dp),
                        color = AetherColors.SurfaceElevatedDark,
                        border = BorderStroke(1.dp, AetherColors.OutlineDark)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = AetherIcons.Account,
                                contentDescription = "Account",
                                tint = AetherColors.AccentCyan,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = activeAccountLabel ?: "Sign in",
                                style = MaterialTheme.typography.labelSmall,
                                color = AetherColors.OnSurfaceDark,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = AetherColors.OutlineVariantDark, thickness = 1.dp)
        }
    }
}

@Composable
fun <T> AetherNavigationBar(
    destinations: List<AetherNavDestination<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = AetherColors.OutlineVariantDark, thickness = 1.dp)
        NavigationBar(
            modifier = Modifier.navigationBarsPadding(),
            containerColor = AetherColors.SurfaceDark,
            tonalElevation = 0.dp
        ) {
            destinations.forEach { dest ->
                val isSelected = dest.id == selected
                NavigationBarItem(
                    selected = isSelected,
                    onClick = { onSelect(dest.id) },
                    icon = {
                        Icon(
                            imageVector = dest.icon,
                            contentDescription = dest.label,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    label = {
                        Text(
                            text = dest.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = if (destinations.size >= 6) 10.sp else 11.sp
                            ),
                            softWrap = false,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AetherColors.AccentCyan,
                        selectedTextColor = AetherColors.AccentCyan,
                        indicatorColor = AetherColors.AccentBlue.copy(alpha = 0.16f),
                        unselectedIconColor = AetherColors.OnSurfaceVariantDark,
                        unselectedTextColor = AetherColors.OnSurfaceVariantDark
                    )
                )
            }
        }
    }
}

@Composable
fun <T> AetherNavigationRail(
    destinations: List<AetherNavDestination<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxHeight()) {
        NavigationRail(
            containerColor = AetherColors.SurfaceDark,
            contentColor = AetherColors.OnSurfaceDark,
            header = {
                Spacer(modifier = Modifier.height(AetherSpacing.Sm))
                AetherBrandMark()
                Spacer(modifier = Modifier.height(AetherSpacing.Sm))
            }
        ) {
            destinations.forEach { dest ->
                val isSelected = dest.id == selected
                NavigationRailItem(
                    selected = isSelected,
                    onClick = { onSelect(dest.id) },
                    icon = {
                        Icon(
                            imageVector = dest.icon,
                            contentDescription = dest.label,
                            modifier = Modifier.size(21.dp)
                        )
                    },
                    label = {
                        Text(
                            text = dest.label,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    },
                    colors = NavigationRailItemDefaults.colors(
                        selectedIconColor = AetherColors.AccentCyan,
                        selectedTextColor = AetherColors.AccentCyan,
                        indicatorColor = AetherColors.AccentBlue.copy(alpha = 0.16f),
                        unselectedIconColor = AetherColors.OnSurfaceVariantDark,
                        unselectedTextColor = AetherColors.OnSurfaceVariantDark
                    )
                )
            }
        }
        VerticalDivider(color = AetherColors.OutlineVariantDark, thickness = 1.dp)
    }
}
