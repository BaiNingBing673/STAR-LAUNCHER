package com.star.launcher.ui.theme.liquid

import android.graphics.ColorMatrix as AndroidColorMatrix
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.star.launcher.ui.theme.AetherSpacing
import com.star.launcher.ui.theme.studio.StarBackgroundConfig
import com.star.launcher.ui.theme.studio.StarBackgroundEngine
import com.star.launcher.ui.theme.studio.StarComponentTarget
import com.star.launcher.ui.theme.studio.StarEffectQuality
import com.star.launcher.ui.theme.studio.StarPresetId
import com.star.launcher.ui.theme.studio.StarResolvedTheme
import com.star.launcher.ui.theme.studio.StarThemePresets
import com.star.launcher.ui.theme.studio.blendColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

val LocalStarTheme = compositionLocalOf {
    StarResolvedTheme.resolve(StarThemePresets.presetConfig(StarPresetId.STAR_LIQUID_GLASS))
}

@Stable
class StarBackdropHostState {
    var sharpBitmap: ImageBitmap? by mutableStateOf(null)
    var backgroundBlurredBitmap: ImageBitmap? by mutableStateOf(null)
    var blurredTiers: Map<Int, ImageBitmap> by mutableStateOf(emptyMap())
    var rootSizePx: IntSize by mutableStateOf(IntSize.Zero)
    var rootPositionInWindow: Offset by mutableStateOf(Offset.Zero)
    var colorFilter: ColorFilter? by mutableStateOf(null)
}

val LocalStarBackdropHost = compositionLocalOf<StarBackdropHostState?> { null }

/**
 * Loads and caches sharp + quantized blurred wallpaper bitmaps off the UI thread so every
 * Liquid Glass surface can sample backdrop blur at 60/120 FPS with zero per-frame blur work.
 */
@Composable
fun rememberStarBackdropHostState(theme: StarResolvedTheme): StarBackdropHostState {
    val hostState = remember { StarBackdropHostState() }
    val bg = theme.config.background
    val quality = theme.config.effectQuality

    // Cache ColorFilter only when brightness/contrast/saturation change
    hostState.colorFilter = remember(bg.brightness, bg.contrast, bg.saturation) {
        buildAdjustmentColorFilter(
            brightness = bg.brightness,
            contrast = bg.contrast,
            saturation = bg.saturation
        )
    }

    val neededBuckets = remember(theme) {
        if (!bg.enabled || bg.imagePath.isNullOrBlank()) {
            emptySet()
        } else {
            buildSet {
                add(StarBackgroundEngine.quantizeBlurBucket(bg.blurRadiusDp))
                if (quality.allowsBackdropBlurSampling) {
                    theme.componentStyles.values.forEach { style ->
                        if (style.glassEffectEnabled && style.blurIntensity > 0f) {
                            add(StarBackgroundEngine.quantizeBlurBucket(style.blurIntensity))
                        }
                    }
                }
            }.filter { it > 0 }.toSet()
        }
    }

    LaunchedEffect(bg.enabled, bg.imagePath, bg.blurRadiusDp, quality, neededBuckets) {
        if (!bg.enabled || bg.imagePath.isNullOrBlank()) {
            hostState.sharpBitmap = null
            hostState.backgroundBlurredBitmap = null
            hostState.blurredTiers = emptyMap()
            return@LaunchedEffect
        }

        val sharp = StarBackgroundEngine.loadSharpBackdrop(bg.imagePath, quality)
        hostState.sharpBitmap = sharp

        val bgBucket = StarBackgroundEngine.quantizeBlurBucket(bg.blurRadiusDp)
        val loadedTiers = mutableMapOf<Int, ImageBitmap>()
        for (bucket in neededBuckets) {
            val blurred = StarBackgroundEngine.loadBlurredBackdrop(
                imagePath = bg.imagePath,
                blurRadiusDp = bucket.toFloat(),
                quality = quality
            )
            if (blurred != null) {
                loadedTiers[bucket] = blurred
            }
        }
        hostState.blurredTiers = loadedTiers
        hostState.backgroundBlurredBitmap = if (bgBucket > 0) loadedTiers[bgBucket] else sharp
    }

    return hostState
}

/**
 * Root atmospheric & custom wallpaper backdrop for STAR Launcher and the Studio Live Preview.
 */
@Composable
fun StarWallpaperBackdrop(
    theme: StarResolvedTheme,
    reducedMotion: Boolean = false,
    hostState: StarBackdropHostState = rememberStarBackdropHostState(theme),
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val bg = theme.config.background
    val glass = theme.config.glass
    val quality = theme.config.effectQuality

    CompositionLocalProvider(
        LocalStarTheme provides theme,
        LocalStarBackdropHost provides hostState
    ) {
        Box(
            modifier = modifier
                .onGloballyPositioned { coords ->
                    hostState.rootSizePx = coords.size
                    hostState.rootPositionInWindow = coords.positionInWindow()
                }
                .drawBehind {
                    drawBaseBackdrop(
                        theme = theme,
                        bg = bg,
                        hostState = hostState
                    )
                }
        ) {
            if (glass.waterDropletsEnabled && quality.allowsWaterDroplets) {
                StarWaterDropletsOverlay(
                    intensity = glass.waterDropletIntensity,
                    animated = glass.waterDropletsAnimated && !reducedMotion && quality.allowsAnimatedDroplets,
                    highlightColor = theme.highlightColor,
                    accentColor = theme.secondaryAccent,
                    modifier = Modifier.fillMaxSize()
                )
            }
            content()
        }
    }
}

private fun DrawScope.drawBaseBackdrop(
    theme: StarResolvedTheme,
    bg: StarBackgroundConfig,
    hostState: StarBackdropHostState
) {
    drawRect(color = theme.backgroundBase)

    val activeBitmap = if (bg.enabled) {
        if (bg.blurRadiusDp >= 1.5f) hostState.backgroundBlurredBitmap ?: hostState.sharpBitmap else hostState.sharpBitmap
    } else null

    if (activeBitmap != null) {
        drawTransformedWallpaper(
            bitmap = activeBitmap,
            canvasWidth = size.width,
            canvasHeight = size.height,
            bg = bg,
            colorFilter = hostState.colorFilter,
            topLeftInRoot = Offset.Zero
        )
    } else {
        // Subtle procedural STAR ambient depth when no custom photo is set
        val topLeft = blendColors(theme.gradientStart, theme.backgroundBase, 0.35f)
        val bottomRight = blendColors(theme.gradientEnd, theme.backgroundBase, 0.45f)
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(topLeft, theme.backgroundBase, bottomRight),
                start = Offset.Zero,
                end = Offset(size.width, size.height)
            )
        )
        if (theme.config.effectQuality != StarEffectQuality.LOW) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        theme.primaryAccent.copy(alpha = 0.14f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.82f, size.height * 0.16f),
                    radius = max(size.width, size.height) * 0.48f
                ),
                center = Offset(size.width * 0.82f, size.height * 0.16f),
                radius = max(size.width, size.height) * 0.48f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        theme.secondaryAccent.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.14f, size.height * 0.84f),
                    radius = max(size.width, size.height) * 0.42f
                ),
                center = Offset(size.width * 0.14f, size.height * 0.84f),
                radius = max(size.width, size.height) * 0.42f
            )
        }
    }

    if (bg.darkOverlayAlpha > 0.005f) {
        drawRect(color = Color.Black.copy(alpha = bg.darkOverlayAlpha))
    }

    if (bg.gradientOverlayEnabled && bg.gradientOverlayAlpha > 0.005f) {
        val (startPt, endPt) = angleToEndpoints(bg.gradientAngleDeg, size.width, size.height)
        val topColor = Color(bg.gradientOverlayTopArgb.toInt()).copy(alpha = bg.gradientOverlayAlpha)
        val bottomColor = Color(bg.gradientOverlayBottomArgb.toInt()).copy(alpha = bg.gradientOverlayAlpha)
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(topColor, bottomColor),
                start = startPt,
                end = endPt
            )
        )
    }
}

private fun DrawScope.drawTransformedWallpaper(
    bitmap: ImageBitmap,
    canvasWidth: Float,
    canvasHeight: Float,
    bg: StarBackgroundConfig,
    colorFilter: ColorFilter?,
    topLeftInRoot: Offset
) {
    if (canvasWidth <= 0f || canvasHeight <= 0f || bitmap.width <= 0 || bitmap.height <= 0) return
    val scaleFill = max(canvasWidth / bitmap.width.toFloat(), canvasHeight / bitmap.height.toFloat())
    val zoomedScale = scaleFill * bg.zoom.coerceIn(1f, 3.5f)
    val drawnW = bitmap.width * zoomedScale
    val drawnH = bitmap.height * zoomedScale

    val extraW = max(0f, drawnW - canvasWidth)
    val extraH = max(0f, drawnH - canvasHeight)

    // Pan smoothly across overflow + allow subtle shift even at zoom 1.0
    val panRangeX = max(extraW * 0.5f, canvasWidth * 0.12f * (bg.zoom - 1f))
    val panRangeY = max(extraH * 0.5f, canvasHeight * 0.12f * (bg.zoom - 1f))

    val left = (canvasWidth - drawnW) * 0.5f + bg.offsetX * panRangeX - topLeftInRoot.x
    val top = (canvasHeight - drawnH) * 0.5f + bg.offsetY * panRangeY - topLeftInRoot.y

    drawImage(
        image = bitmap,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(bitmap.width, bitmap.height),
        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
        dstSize = IntSize(drawnW.roundToInt().coerceAtLeast(1), drawnH.roundToInt().coerceAtLeast(1)),
        colorFilter = colorFilter
    )
}

/**
 * Procedural refractive water droplet overlay with lifecycle-aware animation pausing.
 */
@Composable
fun StarWaterDropletsOverlay(
    intensity: Float,
    animated: Boolean,
    highlightColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            isResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val shouldAnimate = animated && isResumed
    val driftPhase = if (shouldAnimate) {
        val transition = rememberInfiniteTransition(label = "water_droplets")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 14000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "droplet_drift"
        )
    } else {
        null
    }

    val clampedIntensity = intensity.coerceIn(0.05f, 1f)
    val droplets = remember(clampedIntensity) {
        val count = (12 + (clampedIntensity * 22).roundToInt()).coerceIn(10, 34)
        List(count) { idx ->
            // Deterministic low-discrepancy distribution
            val fx = ((idx * 0.6180339f + 0.13f) % 1f)
            val fy = ((idx * 0.3819660f + 0.07f) % 1f)
            val radiusScale = 0.45f + ((idx * 17) % 10) / 14f
            val driftSpeed = 0.35f + ((idx * 7) % 5) * 0.15f
            DropletSeed(fx, fy, radiusScale, driftSpeed)
        }
    }

    Canvas(
        modifier = modifier
            .graphicsLayer()
            .drawWithCache {
                val baseMaxR = (size.minDimension * 0.022f).coerceIn(5.dp.toPx(), 16.dp.toPx())
                val shadowColor = Color.Black.copy(alpha = 0.24f * clampedIntensity)
                val rimColor = highlightColor.copy(alpha = 0.36f * clampedIntensity)
                val causticColor = accentColor.copy(alpha = 0.26f * clampedIntensity)
                val glintColor = Color.White.copy(alpha = 0.58f * clampedIntensity)
                val strokeWidth = 0.8.dp.toPx()

                onDrawBehind {
                    val phase = driftPhase?.value ?: 0f
                    val w = size.width
                    val h = size.height
                    for (d in droplets) {
                        val r = baseMaxR * d.radiusScale
                        val cx = w * d.fx
                        val yProgress = (d.fy + phase * d.driftSpeed * 0.18f) % 1.08f - 0.04f
                        val cy = h * yProgress
                        val ry = r * 1.12f

                        // Soft contact shadow underneath droplet
                        drawOval(
                            color = shadowColor,
                            topLeft = Offset(cx - r * 0.92f, cy - ry * 0.72f + r * 0.26f),
                            size = Size(r * 1.84f, ry * 1.65f)
                        )
                        // Subtle bottom refraction caustic crescent
                        drawOval(
                            color = causticColor,
                            topLeft = Offset(cx - r * 0.78f, cy - ry * 0.1f),
                            size = Size(r * 1.56f, ry * 0.95f)
                        )
                        // Meniscus glass rim
                        drawOval(
                            color = rimColor,
                            topLeft = Offset(cx - r, cy - ry),
                            size = Size(r * 2f, ry * 2f),
                            style = Stroke(width = strokeWidth)
                        )
                        // Crisp top-left specular pinpoint
                        drawCircle(
                            color = glintColor,
                            radius = (r * 0.24f).coerceAtLeast(1.4f),
                            center = Offset(cx - r * 0.34f, cy - ry * 0.38f)
                        )
                    }
                }
            }
    ) {}
}

private data class DropletSeed(
    val fx: Float,
    val fy: Float,
    val radiusScale: Float,
    val driftSpeed: Float
)

/**
 * Core Liquid Glass surface composable powering STAR Launcher cards, panels, navigation,
 * buttons, dialogs, settings blocks, version cards, mod cards, downloads, and profile panels.
 */
@Composable
fun StarLiquidGlassSurface(
    componentTarget: StarComponentTarget,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    containerColorOverride: Color? = null,
    borderColorOverride: Color? = null,
    borderWidthDp: Float = 1f,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    contentColor: Color? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val theme = LocalStarTheme.current
    val backdropHost = LocalStarBackdropHost.current
    val style = theme.styleFor(componentTarget)
    val quality = theme.config.effectQuality
    val resolvedShape = shape ?: RoundedCornerShape(style.cornerRadiusDp.dp)

    var positionInRoot by remember { mutableStateOf(Offset.Zero) }

    val baseFillColor = containerColorOverride ?: theme.surfaceBase
    val effectiveFillAlpha = if (style.glassEffectEnabled) {
        style.backgroundOpacity.coerceIn(0.06f, 0.98f)
    } else {
        style.backgroundOpacity.coerceIn(0.75f, 0.98f)
    }

    val resolvedBorderColor = borderColorOverride ?: theme.borderColor
    val effectiveBorderAlpha = style.borderOpacity.coerceIn(0f, 1f)

    val borderBrush = remember(
        resolvedBorderColor,
        theme.highlightColor,
        style.accentColor,
        effectiveBorderAlpha,
        style.glassEffectEnabled,
        theme.config.glass.edgeReflectionIntensity
    ) {
        if (effectiveBorderAlpha <= 0.01f) {
            null
        } else if (style.glassEffectEnabled) {
            val edgeBoost = theme.config.glass.edgeReflectionIntensity
            Brush.linearGradient(
                colors = listOf(
                    theme.highlightColor.copy(alpha = (effectiveBorderAlpha * (0.75f + 0.55f * edgeBoost)).coerceAtMost(0.92f)),
                    resolvedBorderColor.copy(alpha = effectiveBorderAlpha * 0.65f),
                    style.accentColor.copy(alpha = (effectiveBorderAlpha * (0.45f + 0.40f * edgeBoost)).coerceAtMost(0.80f))
                )
            )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    resolvedBorderColor.copy(alpha = effectiveBorderAlpha),
                    resolvedBorderColor.copy(alpha = effectiveBorderAlpha)
                )
            )
        }
    }

    // Exterior-only soft shadow (clipped outside shapePath so translucent glass never shows an inner box)
    val shadowModifier = if (quality.allowsShadows && style.shadowIntensity > 0.02f && borderWidthDp >= 0f) {
        val strength = style.shadowIntensity.coerceIn(0f, 1f)
        val shadowBase = Color.Black
        val tintGlow = style.accentColor
        Modifier.drawWithCache {
            val shapePath = Path().apply {
                when (val outline = resolvedShape.createOutline(size, layoutDirection, this@drawWithCache)) {
                    is Outline.Rectangle -> addRect(outline.rect)
                    is Outline.Rounded -> addRoundRect(outline.roundRect)
                    is Outline.Generic -> addPath(outline.path)
                }
            }
            val outerStroke = Stroke(width = (8f * strength).dp.toPx())
            val tightStroke = Stroke(width = (3f * strength).dp.toPx())
            val dy = (1.5f * strength).dp.toPx()
            onDrawBehind {
                clipPath(shapePath, clipOp = ClipOp.Difference) {
                    translate(left = 0f, top = dy) {
                        drawPath(
                            path = shapePath,
                            color = shadowBase.copy(alpha = 0.22f * strength),
                            style = outerStroke
                        )
                        drawPath(
                            path = shapePath,
                            color = tintGlow.copy(alpha = 0.10f * strength),
                            style = tightStroke
                        )
                    }
                }
            }
        }
    } else {
        Modifier
    }

    val borderModifier = if (borderBrush != null) {
        Modifier.border(BorderStroke(borderWidthDp.dp, borderBrush), resolvedShape)
    } else {
        Modifier
    }

    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            enabled = enabled,
            role = Role.Button,
            interactionSource = remember { MutableInteractionSource() },
            onClick = onClick
        )
    } else {
        Modifier
    }

    val glassDrawModifier = Modifier
        .onGloballyPositioned { coords: LayoutCoordinates ->
            val hostRoot = backdropHost?.rootPositionInWindow ?: Offset.Zero
            val pos = coords.positionInWindow() - hostRoot
            if (pos != positionInRoot) {
                positionInRoot = pos
            }
        }
        .drawBehind {
            // 1. Sample cached blurred wallpaper backdrop aligned to component coordinates
            val bg = theme.config.background
            if (style.glassEffectEnabled &&
                quality.allowsBackdropBlurSampling &&
                bg.enabled &&
                backdropHost != null &&
                backdropHost.rootSizePx.width > 0 &&
                backdropHost.rootSizePx.height > 0
            ) {
                val bucket = StarBackgroundEngine.quantizeBlurBucket(style.blurIntensity)
                val blurredSlice = if (bucket > 0) {
                    backdropHost.blurredTiers[bucket] ?: backdropHost.backgroundBlurredBitmap ?: backdropHost.sharpBitmap
                } else {
                    backdropHost.sharpBitmap
                }
                if (blurredSlice != null) {
                    clipRect {
                        drawTransformedWallpaper(
                            bitmap = blurredSlice,
                            canvasWidth = backdropHost.rootSizePx.width.toFloat(),
                            canvasHeight = backdropHost.rootSizePx.height.toFloat(),
                            bg = bg,
                            colorFilter = backdropHost.colorFilter,
                            topLeftInRoot = positionInRoot
                        )
                    }
                }
            }

            // 2. Base translucent glass tint
            drawRect(color = baseFillColor.copy(alpha = effectiveFillAlpha))

            // 3. Optional subtle glass gradient
            if (style.gradientEnabled) {
                val gradStrength = if (style.glassEffectEnabled) 0.26f else 0.16f
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            style.gradientStart.copy(alpha = gradStrength),
                            Color.Transparent,
                            style.gradientEnd.copy(alpha = gradStrength * 0.85f)
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height)
                    )
                )
            }

            // 4. Liquid Glass specular refraction & subtle inner top highlight
            if (style.glassEffectEnabled && quality.allowsRefractionLayer) {
                val refract = theme.config.glass.refractionIntensity
                val highlight = theme.config.glass.highlightIntensity
                if (highlight > 0.01f || refract > 0.01f) {
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                theme.highlightColor.copy(alpha = (0.14f * highlight + 0.06f * refract).coerceAtMost(0.24f)),
                                Color.Transparent,
                                style.accentColor.copy(alpha = (0.07f * refract).coerceAtMost(0.12f))
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(size.width * 0.9f, size.height)
                        )
                    )
                }
                // Crisp subtle top-edge refraction line
                if (refract > 0.05f) {
                    drawLine(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                theme.highlightColor.copy(alpha = (0.34f * refract).coerceAtMost(0.45f)),
                                Color.Transparent
                            )
                        ),
                        start = Offset(size.width * 0.08f, 1.dp.toPx()),
                        end = Offset(size.width * 0.92f, 1.dp.toPx()),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
        }

    val resolvedContentColor = contentColor ?: theme.textPrimary
    CompositionLocalProvider(LocalContentColor provides resolvedContentColor) {
        Box(
            modifier = modifier
                .then(shadowModifier)
                .clip(resolvedShape)
                .then(glassDrawModifier)
                .then(borderModifier)
                .then(clickModifier),
            content = content
        )
    }
}

/**
 * Liquid Glass modal dialog wrapper styled via [StarComponentTarget.DIALOGS].
 */
@Composable
fun StarGlassDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(),
    title: (@Composable () -> Unit)? = null,
    text: (@Composable ColumnScope.() -> Unit)? = null,
    confirmButton: @Composable RowScope.() -> Unit,
    dismissButton: (@Composable RowScope.() -> Unit)? = null
) {
    val theme = LocalStarTheme.current
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties
    ) {
        StarLiquidGlassSurface(
            componentTarget = StarComponentTarget.DIALOGS,
            modifier = modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AetherSpacing.Lg),
                verticalArrangement = Arrangement.spacedBy(AetherSpacing.Md)
            ) {
                if (title != null) {
                    ProvideTextStyle(
                        MaterialTheme.typography.titleLarge.copy(color = theme.textPrimary)
                    ) {
                        title()
                    }
                }
                if (text != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(AetherSpacing.Sm),
                        content = text
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AetherSpacing.Sm, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    dismissButton?.invoke(this)
                    confirmButton()
                }
            }
        }
    }
}

private fun buildAdjustmentColorFilter(
    brightness: Float,
    contrast: Float,
    saturation: Float
): ColorFilter? {
    if (brightness == 0f && contrast == 1f && saturation == 1f) return null
    val matrix = AndroidColorMatrix()
    if (saturation != 1f) {
        val satMatrix = AndroidColorMatrix().apply { setSaturation(saturation) }
        matrix.postConcat(satMatrix)
    }
    if (contrast != 1f || brightness != 0f) {
        val c = contrast
        val translate = (-0.5f * c + 0.5f + brightness) * 255f
        val cbMatrix = AndroidColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, translate,
                0f, c, 0f, 0f, translate,
                0f, 0f, c, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        matrix.postConcat(cbMatrix)
    }
    return ColorFilter.colorMatrix(ColorMatrix(matrix.array))
}

private fun angleToEndpoints(angleDeg: Float, width: Float, height: Float): Pair<Offset, Offset> {
    val rad = angleDeg * (PI / 180.0)
    val dx = cos(rad).toFloat()
    val dy = sin(rad).toFloat()
    val cx = width * 0.5f
    val cy = height * 0.5f
    val halfSpan = max(width, height) * 0.65f
    return Offset(cx - dx * halfSpan, cy - dy * halfSpan) to Offset(cx + dx * halfSpan, cy + dy * halfSpan)
}
