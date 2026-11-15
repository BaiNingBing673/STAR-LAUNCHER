package com.star.launcher.hud

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pixel safe-area insets (in view coordinates) used to keep HUD controls clear of display cutouts
 * and system bars across orientations and aspect ratios.
 */
data class StarHudSafeInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
)

/**
 * Computed screen rectangle in pixels for a built-in structural HUD element.
 */
data class StarHudElementRect(
    val id: StarHudElementId,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height

    fun overlaps(other: StarHudElementRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

/**
 * Computed screen rectangle in pixels for any customizable button on the STAR Controller.
 */
data class StarHudButtonRect(
    val buttonId: String,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    fun overlaps(other: StarHudButtonRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

/**
 * Result of alignment snapping during interactive drag positioning.
 */
data class StarHudSnapResult(
    val leftPx: Int,
    val topPx: Int,
    val guideX: Int? = null,
    val guideY: Int? = null
)

/**
 * Shared layout math between the live [StarTouchHudOverlay] and the settings preview editor.
 */
object StarHudLayoutSolver {
    private const val OUTER_MARGIN_DP = 8f
    private const val HOTBAR_BASE_WIDTH_DP = 240f
    private const val HOTBAR_BASE_HEIGHT_DP = 48f

    @JvmStatic
    fun measureElementSizePx(
        id: StarHudElementId,
        config: StarHudConfig,
        density: Float,
        containerWidth: Int
    ): Pair<Int, Int> {
        val safeDensity = max(0.75f, density)
        val scale = config.hudScale
        val joyScale = config.joystickSize
        return when (id) {
            StarHudElementId.JOYSTICK -> {
                val side = (StarJoystickView.DEFAULT_DIAMETER_DP * joyScale * safeDensity).roundToInt()
                side to side
            }
            StarHudElementId.HOTBAR -> {
                val rawW = (HOTBAR_BASE_WIDTH_DP * config.hotbarScale * safeDensity).roundToInt()
                val cappedW = if (containerWidth > 0) {
                    min(rawW, max((180f * safeDensity).roundToInt(), (containerWidth * 0.36f).roundToInt()))
                } else {
                    rawW
                }
                val h = (HOTBAR_BASE_HEIGHT_DP * config.hotbarScale * safeDensity).roundToInt()
                cappedW to h
            }
            else -> {
                val btnId = id.toBuiltInButtonId()
                val btnCfg = if (btnId != null) config.findButton(btnId) else null
                if (btnCfg != null) {
                    measureButtonSizePx(btnCfg, config, safeDensity)
                } else {
                    val action = id.toActionType()
                    val w = (action.defaultWidthDp * scale * safeDensity).roundToInt()
                    val h = (action.defaultHeightDp * scale * safeDensity).roundToInt()
                    w to h
                }
            }
        }
    }

    @JvmStatic
    fun measureButtonSizePx(
        button: StarHudButtonConfig,
        config: StarHudConfig,
        density: Float
    ): Pair<Int, Int> {
        val safeDensity = max(0.75f, density)
        val w = (button.widthDp * config.hudScale * safeDensity).roundToInt().coerceAtLeast(24)
        val h = (button.heightDp * config.hudScale * safeDensity).roundToInt().coerceAtLeast(24)
        return w to h
    }

    @JvmStatic
    fun solveButtonRect(
        button: StarHudButtonConfig,
        config: StarHudConfig,
        containerWidth: Int,
        containerHeight: Int,
        density: Float,
        insets: StarHudSafeInsets
    ): StarHudButtonRect {
        val (w, h) = measureButtonSizePx(button, config, density)
        val margin = (OUTER_MARGIN_DP * max(0.75f, density)).roundToInt()
        val minX = max(0, insets.left) + margin
        val minY = max(0, insets.top) + margin
        val maxRight = max(minX + w, containerWidth - max(0, insets.right) - margin)
        val maxBottom = max(minY + h, containerHeight - max(0, insets.bottom) - margin)

        val availX = max(0, (maxRight - minX) - w)
        val availY = max(0, (maxBottom - minY) - h)

        val left = minX + (availX * button.xFraction.coerceIn(0f, 1f)).roundToInt()
        val top = minY + (availY * button.yFraction.coerceIn(0f, 1f)).roundToInt()
        return StarHudButtonRect(button.id, left, top, w, h)
    }

    @JvmStatic
    fun solveElementRect(
        id: StarHudElementId,
        config: StarHudConfig,
        containerWidth: Int,
        containerHeight: Int,
        density: Float,
        insets: StarHudSafeInsets,
        explicitWidthPx: Int = -1,
        explicitHeightPx: Int = -1
    ): StarHudElementRect {
        val (defaultW, defaultH) = measureElementSizePx(id, config, density, containerWidth)
        val w = if (explicitWidthPx > 0) explicitWidthPx else defaultW
        val h = if (explicitHeightPx > 0) explicitHeightPx else defaultH

        val margin = (OUTER_MARGIN_DP * max(0.75f, density)).roundToInt()
        val extraBottom = if (id == StarHudElementId.HOTBAR) 0 else margin
        val minX = max(0, insets.left) + margin
        val minY = max(0, insets.top) + margin
        val maxRight = max(minX + w, containerWidth - max(0, insets.right) - margin)
        val maxBottom = max(minY + h, containerHeight - max(0, insets.bottom) - extraBottom)

        val availX = max(0, (maxRight - minX) - w)
        val availY = max(0, (maxBottom - minY) - h)

        val layout = config.elementLayout(id)
        val left = minX + (availX * layout.xFraction.coerceIn(0f, 1f)).roundToInt()
        val top = minY + (availY * layout.yFraction.coerceIn(0f, 1f)).roundToInt()
        return StarHudElementRect(id, left, top, w, h)
    }

    @JvmStatic
    fun solveAllEnabledRects(
        config: StarHudConfig,
        containerWidth: Int,
        containerHeight: Int,
        density: Float,
        insets: StarHudSafeInsets = StarHudSafeInsets()
    ): Map<StarHudElementId, StarHudElementRect> =
        StarHudElementId.values()
            .filter { config.isElementEnabled(it) }
            .associateWith { id ->
                solveElementRect(id, config, containerWidth, containerHeight, density, insets)
            }

    @JvmStatic
    fun fractionsFromTopLeft(
        id: StarHudElementId,
        leftPx: Int,
        topPx: Int,
        elemWidthPx: Int,
        elemHeightPx: Int,
        containerWidth: Int,
        containerHeight: Int,
        density: Float,
        insets: StarHudSafeInsets
    ): Pair<Float, Float> {
        val extraBottom = if (id == StarHudElementId.HOTBAR) 0 else (OUTER_MARGIN_DP * max(0.75f, density)).roundToInt()
        return computeFractions(
            leftPx, topPx, elemWidthPx, elemHeightPx, containerWidth, containerHeight, density, insets, extraBottom
        )
    }

    @JvmStatic
    fun fractionsFromTopLeftForButton(
        leftPx: Int,
        topPx: Int,
        elemWidthPx: Int,
        elemHeightPx: Int,
        containerWidth: Int,
        containerHeight: Int,
        density: Float,
        insets: StarHudSafeInsets
    ): Pair<Float, Float> {
        val margin = (OUTER_MARGIN_DP * max(0.75f, density)).roundToInt()
        return computeFractions(
            leftPx, topPx, elemWidthPx, elemHeightPx, containerWidth, containerHeight, density, insets, margin
        )
    }

    private fun computeFractions(
        leftPx: Int,
        topPx: Int,
        elemWidthPx: Int,
        elemHeightPx: Int,
        containerWidth: Int,
        containerHeight: Int,
        density: Float,
        insets: StarHudSafeInsets,
        extraBottomPx: Int
    ): Pair<Float, Float> {
        val margin = (OUTER_MARGIN_DP * max(0.75f, density)).roundToInt()
        val minX = max(0, insets.left) + margin
        val minY = max(0, insets.top) + margin
        val maxRight = max(minX + elemWidthPx, containerWidth - max(0, insets.right) - margin)
        val maxBottom = max(minY + elemHeightPx, containerHeight - max(0, insets.bottom) - extraBottomPx)

        val availX = max(1, (maxRight - minX) - elemWidthPx)
        val availY = max(1, (maxBottom - minY) - elemHeightPx)

        val fx = ((leftPx - minX).toFloat() / availX.toFloat()).coerceIn(0f, 1f)
        val fy = ((topPx - minY).toFloat() / availY.toFloat()).coerceIn(0f, 1f)
        return fx to fy
    }

    /**
     * Snaps a dragged element's position against container center lines and sibling element centers/edges.
     */
    @JvmStatic
    fun snapDragPosition(
        rawLeftPx: Int,
        rawTopPx: Int,
        elemWidthPx: Int,
        elemHeightPx: Int,
        containerWidth: Int,
        containerHeight: Int,
        density: Float,
        peerCenters: List<Pair<Int, Int>>
    ): StarHudSnapResult {
        val threshold = (8f * max(0.75f, density)).roundToInt().coerceAtLeast(6)
        var bestLeft = rawLeftPx
        var bestTop = rawTopPx
        var guideX: Int? = null
        var guideY: Int? = null

        val rawCx = rawLeftPx + elemWidthPx / 2
        val rawCy = rawTopPx + elemHeightPx / 2

        val containerCx = containerWidth / 2
        val containerCy = containerHeight / 2

        var bestDx = threshold + 1
        if (abs(rawCx - containerCx) < bestDx) {
            bestDx = abs(rawCx - containerCx)
            bestLeft = containerCx - elemWidthPx / 2
            guideX = containerCx
        }
        var bestDy = threshold + 1
        if (abs(rawCy - containerCy) < bestDy) {
            bestDy = abs(rawCy - containerCy)
            bestTop = containerCy - elemHeightPx / 2
            guideY = containerCy
        }

        for ((px, py) in peerCenters) {
            val dx = abs(rawCx - px)
            if (dx < bestDx) {
                bestDx = dx
                bestLeft = px - elemWidthPx / 2
                guideX = px
            }
            val dy = abs(rawCy - py)
            if (dy < bestDy) {
                bestDy = dy
                bestTop = py - elemHeightPx / 2
                guideY = py
            }
        }

        return StarHudSnapResult(bestLeft, bestTop, guideX, guideY)
    }
}
