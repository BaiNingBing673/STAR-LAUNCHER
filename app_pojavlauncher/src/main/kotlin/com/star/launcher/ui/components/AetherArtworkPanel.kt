package com.star.launcher.ui.components

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.star.launcher.ui.theme.AetherColors
import com.star.launcher.ui.theme.AetherMonoStyle
import com.star.launcher.ui.theme.AetherSpacing
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun rememberReducedMotionEnabled(userReducedMotion: Boolean): Boolean {
    val context = LocalContext.current
    val systemScale = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
        }.getOrDefault(1.0f)
    }
    return userReducedMotion || systemScale <= 0f
}

/**
 * Lightweight decorative star-trail accent strip.
 * Falls back to static geometry when reduced motion is active or animation is disabled.
 */
@Composable
fun AetherSoundwaveStrip(
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
    accentStart: Color = AetherColors.AccentBlue,
    accentEnd: Color = AetherColors.AccentCyan,
    barCount: Int = 18
) {
    val phase = if (!reducedMotion) {
        val transition = rememberInfiniteTransition(label = "star_trail")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2.0 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "star_trail_phase"
        ).value
    } else {
        0.65f
    }

    Canvas(modifier = modifier.height(22.dp)) {
        val count = barCount.coerceAtLeast(6)
        val totalWidth = size.width
        val step = totalWidth / count
        val barWidth = (step * 0.45f).coerceIn(2f, 8f)
        for (i in 0 until count) {
            val t = i.toFloat() / (count - 1).toFloat()
            val harmonic = 0.28f + 0.65f * abs(sin(phase + i * 0.55f))
            val envelope = 0.45f + 0.55f * sin(t * PI.toFloat())
            val barHeight = (size.height * harmonic * envelope).coerceAtLeast(4f)
            val x = i * step + (step - barWidth) / 2f
            val y = (size.height - barHeight) / 2f
            val barColor = Color(
                red = accentStart.red + (accentEnd.red - accentStart.red) * t,
                green = accentStart.green + (accentEnd.green - accentStart.green) * t,
                blue = accentStart.blue + (accentEnd.blue - accentStart.blue) * t,
                alpha = 0.80f
            )
            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

/**
 * Hero Minecraft Java Edition stage panel with layered voxel terrain artwork,
 * atmospheric horizon lighting, and smooth transitions between featured instances.
 */
@Composable
fun AetherArtworkPanel(
    instanceTitle: String,
    versionLabel: String,
    loaderLabel: String,
    runtimeSummary: String,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier
) {
    val paletteKey = "$versionLabel|$loaderLabel"
    val (skyTop, horizonGlow, accentWave) = remember(paletteKey) {
        val hash = abs(paletteKey.hashCode()) % 3
        when (hash) {
            0 -> Triple(Color(0xFF0D1529), Color(0xFF1E3A6E), AetherColors.AccentCyan)
            1 -> Triple(Color(0xFF0F1B33), Color(0xFF1D4ED8), AetherColors.AccentBlue)
            else -> Triple(Color(0xFF0B1926), Color(0xFF154E68), AetherColors.TertiaryDark)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = AetherColors.SurfaceElevatedDark,
        border = BorderStroke(1.dp, AetherColors.OutlineDark.copy(alpha = 0.7f))
    ) {
        AnimatedContent(
            targetState = "$instanceTitle|$versionLabel|$loaderLabel|$runtimeSummary",
            transitionSpec = {
                if (reducedMotion) {
                    fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                } else {
                    fadeIn(tween(260)) togetherWith fadeOut(tween(180))
                }
            },
            label = "artwork_transition"
        ) { _ ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(206.dp)
            ) {
                // Lightweight vector/voxel landscape album artwork
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(skyTop, horizonGlow.copy(alpha = 0.65f), AetherColors.SurfaceDark)
                        )
                    )

                    // Atmospheric moon / portal halo
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accentWave.copy(alpha = 0.24f),
                                Color.Transparent
                            ),
                            center = Offset(w * 0.76f, h * 0.34f),
                            radius = w * 0.34f
                        ),
                        radius = w * 0.34f,
                        center = Offset(w * 0.76f, h * 0.34f)
                    )

                    // Subtle geometric voxel diamond moon
                    val moonCenter = Offset(w * 0.77f, h * 0.31f)
                    val r = h * 0.11f
                    drawRect(
                        color = Color(0xFFEAF4FF).copy(alpha = 0.85f),
                        topLeft = Offset(moonCenter.x - r, moonCenter.y - r),
                        size = Size(r * 2f, r * 2f)
                    )

                    // Minimal stars
                    val starPositions = listOf(
                        0.12f to 0.20f, 0.26f to 0.14f, 0.41f to 0.27f,
                        0.56f to 0.16f, 0.64f to 0.42f, 0.89f to 0.19f
                    )
                    starPositions.forEachIndexed { idx, (sx, sy) ->
                        drawRect(
                            color = Color.White.copy(alpha = if (idx % 2 == 0) 0.45f else 0.25f),
                            topLeft = Offset(w * sx, h * sy),
                            size = Size(3f, 3f)
                        )
                    }

                    // Far voxel ridge silhouette
                    val farRidge = Path().apply {
                        moveTo(0f, h)
                        lineTo(0f, h * 0.72f)
                        lineTo(w * 0.14f, h * 0.72f)
                        lineTo(w * 0.14f, h * 0.60f)
                        lineTo(w * 0.28f, h * 0.60f)
                        lineTo(w * 0.28f, h * 0.66f)
                        lineTo(w * 0.44f, h * 0.66f)
                        lineTo(w * 0.44f, h * 0.54f)
                        lineTo(w * 0.58f, h * 0.54f)
                        lineTo(w * 0.58f, h * 0.64f)
                        lineTo(w * 0.74f, h * 0.64f)
                        lineTo(w * 0.74f, h * 0.50f)
                        lineTo(w * 0.88f, h * 0.50f)
                        lineTo(w * 0.88f, h * 0.62f)
                        lineTo(w, h * 0.62f)
                        lineTo(w, h)
                        close()
                    }
                    drawPath(farRidge, color = Color(0xFF0E1320).copy(alpha = 0.72f))

                    // Near stepped voxel foreground
                    val nearRidge = Path().apply {
                        moveTo(0f, h)
                        lineTo(0f, h * 0.80f)
                        lineTo(w * 0.22f, h * 0.80f)
                        lineTo(w * 0.22f, h * 0.72f)
                        lineTo(w * 0.36f, h * 0.72f)
                        lineTo(w * 0.36f, h * 0.83f)
                        lineTo(w * 0.66f, h * 0.83f)
                        lineTo(w * 0.66f, h * 0.70f)
                        lineTo(w * 0.82f, h * 0.70f)
                        lineTo(w * 0.82f, h * 0.78f)
                        lineTo(w, h * 0.78f)
                        lineTo(w, h)
                        close()
                    }
                    drawPath(nearRidge, color = Color(0xFF0A0D15))

                    // Bottom readability scrim
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0xCC090B11)),
                            startY = h * 0.45f,
                            endY = h
                        )
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(AetherSpacing.Md),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x99090B11))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "FEATURED STAGE · $loaderLabel",
                                style = MaterialTheme.typography.labelSmall,
                                color = accentWave
                            )
                        }
                        Text(
                            text = versionLabel,
                            style = AetherMonoStyle.copy(
                                color = AetherColors.OnBackgroundDark,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm)) {
                        Text(
                            text = instanceTitle,
                            style = MaterialTheme.typography.headlineMedium,
                            color = AetherColors.OnBackgroundDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = runtimeSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = AetherColors.OnSurfaceVariantDark,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(1.dp))
                            AetherSoundwaveStrip(
                                modifier = Modifier.fillMaxWidth(0.32f),
                                reducedMotion = reducedMotion,
                                accentStart = AetherColors.AccentBlue,
                                accentEnd = accentWave,
                                barCount = 14
                            )
                        }
                    }
                }
            }
        }
    }
}
