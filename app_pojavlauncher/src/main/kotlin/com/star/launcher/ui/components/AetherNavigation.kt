package com.star.launcher.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.star.launcher.ui.theme.AetherColors
import com.star.launcher.ui.theme.AetherSpacing
import com.star.launcher.ui.theme.liquid.LocalStarTheme
import com.star.launcher.ui.theme.liquid.StarLiquidGlassSurface
import com.star.launcher.ui.theme.studio.StarComponentTarget
import net.kdt.pojavlaunch.R

data class AetherNavDestination<T>(
    val id: T,
    val label: String,
    val icon: ImageVector
)

/**
 * Official STAR Launcher app brand mark rendered directly from the launcher's unified
 * icon asset ([R.drawable.ic_pojav_full], matching `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`).
 */
@Composable
fun AetherBrandMark(modifier: Modifier = Modifier.size(32.dp)) {
    Image(
        painter = painterResource(id = R.drawable.ic_pojav_full),
        contentDescription = "STAR Launcher",
        contentScale = ContentScale.Fit,
        modifier = modifier.clip(RoundedCornerShape(8.dp))
    )
}

@Composable
fun AetherTopAppBar(
    activeAccountLabel: String?,
    runtimeHealthy: Boolean,
    onAccountClick: () -> Unit,
    onRuntimeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalStarTheme.current
    val navStyle = theme.styleFor(StarComponentTarget.MAIN_NAVIGATION)
    StarLiquidGlassSurface(
        componentTarget = StarComponentTarget.MAIN_NAVIGATION,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0.dp),
        borderWidthDp = 0f
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
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AetherBrandMark()
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "STAR",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.6.sp
                            ),
                            color = theme.textPrimary,
                            maxLines = 1
                        )
                        Text(
                            text = "YOUR MINECRAFT. YOUR UNIVERSE.",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 7.5.sp,
                                letterSpacing = 0.05.sp
                            ),
                            color = theme.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)
                ) {
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
                                color = if (runtimeHealthy) AetherColors.TierVerifiedFg else AetherColors.TierBlockedFg,
                                softWrap = false,
                                maxLines = 1
                            )
                        }
                    }

                    // Active account selector pill styled via PROFILE_COMPONENTS
                    StarLiquidGlassSurface(
                        componentTarget = StarComponentTarget.PROFILE_COMPONENTS,
                        shape = RoundedCornerShape(999.dp),
                        onClick = onAccountClick
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = AetherIcons.Account,
                                contentDescription = "Account",
                                tint = theme.secondaryAccent,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = activeAccountLabel ?: "Sign in",
                                style = MaterialTheme.typography.labelSmall,
                                color = theme.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            HorizontalDivider(
                color = theme.borderColor.copy(alpha = (navStyle.borderOpacity * 0.7f).coerceIn(0.08f, 0.75f)),
                thickness = 1.dp
            )
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
    val theme = LocalStarTheme.current
    val navStyle = theme.styleFor(StarComponentTarget.MAIN_NAVIGATION)
    val topRadius = navStyle.cornerRadiusDp.coerceIn(0f, 24f).dp
    StarLiquidGlassSurface(
        componentTarget = StarComponentTarget.MAIN_NAVIGATION,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = topRadius, topEnd = topRadius, bottomStart = 0.dp, bottomEnd = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            NavigationBar(
                modifier = Modifier.navigationBarsPadding(),
                containerColor = Color.Transparent,
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
                                modifier = Modifier.size(if (destinations.size >= 6) 19.dp else 20.dp)
                            )
                        },
                        label = {
                            Text(
                                text = dest.label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = if (destinations.size >= 6) 9.sp else 11.sp,
                                    letterSpacing = if (destinations.size >= 6) (-0.25).sp else 0.sp
                                ),
                                softWrap = false,
                                maxLines = 1,
                                overflow = TextOverflow.Clip
                            )
                        },
                        alwaysShowLabel = true,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = theme.secondaryAccent,
                            selectedTextColor = theme.secondaryAccent,
                            indicatorColor = navStyle.accentColor.copy(alpha = 0.20f),
                            unselectedIconColor = theme.textSecondary,
                            unselectedTextColor = theme.textSecondary
                        )
                    )
                }
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
    val theme = LocalStarTheme.current
    val navStyle = theme.styleFor(StarComponentTarget.MAIN_NAVIGATION)
    val sideRadius = navStyle.cornerRadiusDp.coerceIn(0f, 24f).dp
    Row(modifier = modifier.fillMaxHeight()) {
        StarLiquidGlassSurface(
            componentTarget = StarComponentTarget.MAIN_NAVIGATION,
            shape = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = sideRadius, bottomEnd = sideRadius)
        ) {
            NavigationRail(
                containerColor = Color.Transparent,
                contentColor = theme.textPrimary,
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
                            selectedIconColor = theme.secondaryAccent,
                            selectedTextColor = theme.secondaryAccent,
                            indicatorColor = navStyle.accentColor.copy(alpha = 0.20f),
                            unselectedIconColor = theme.textSecondary,
                            unselectedTextColor = theme.textSecondary
                        )
                    )
                }
            }
        }
        VerticalDivider(
            color = theme.borderColor.copy(alpha = (navStyle.borderOpacity * 0.5f).coerceIn(0.08f, 0.5f)),
            thickness = 1.dp
        )
    }
}
