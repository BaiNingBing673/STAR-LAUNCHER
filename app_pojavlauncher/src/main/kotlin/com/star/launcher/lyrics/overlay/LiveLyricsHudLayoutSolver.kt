package com.star.launcher.lyrics.overlay

import android.graphics.RectF
import com.star.launcher.hud.StarHudConfig
import com.star.launcher.hud.StarHudElementId
import com.star.launcher.hud.StarHudLayoutSolver
import com.star.launcher.hud.StarHudSafeInsets
import com.star.launcher.lyrics.config.IslandPosition
import com.star.launcher.lyrics.config.LiveLyricsConfig
import kotlin.math.max
import kotlin.math.min

enum class HudZoneCategory {
    DRAWER_PULL,
    TOP_HUD_ACTION,
    TOUCH_BUTTON,
    JOYSTICK,
    HOTBAR_AND_VITALS,
    LEGACY_CONTROL,
    CHAT_AREA
}

data class HudExclusionRect(
    val id: String,
    val label: String,
    val category: HudZoneCategory,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = max(0f, right - left)
    val height: Float get() = max(0f, bottom - top)

    fun overlaps(
        otherLeft: Float,
        otherTop: Float,
        otherRight: Float,
        otherBottom: Float,
        marginPx: Float = 0f
    ): Boolean {
        if (width <= 0f || height <= 0f) return false
        return otherLeft < (right + marginPx) &&
            otherRight > (left - marginPx) &&
            otherTop < (bottom + marginPx) &&
            otherBottom > (top - marginPx)
    }
}

data class HudCollisionWarning(
    val zoneId: String,
    val label: String,
    val category: HudZoneCategory,
    val rect: HudExclusionRect
)

data class HudPlacementRequest(
    val containerWidthPx: Float,
    val containerHeightPx: Float,
    val density: Float,
    val safeLeftPx: Float,
    val safeTopPx: Float,
    val safeRightPx: Float,
    val safeBottomPx: Float,
    val desiredWidthPx: Float,
    val desiredHeightPx: Float,
    val config: LiveLyricsConfig,
    val starHudConfig: StarHudConfig = StarHudConfig.defaults(),
    val liveControlRects: List<HudExclusionRect> = emptyList(),
    val includeVanillaBottomHud: Boolean = true,
    val includeDrawerPull: Boolean = true,
    val includeChatSafetyZone: Boolean = false
)

data class HudPlacementResult(
    val leftPx: Float,
    val topPx: Float,
    val widthPx: Float,
    val heightPx: Float,
    val appliedScale: Float,
    val autoRepositioned: Boolean,
    val shouldTemporarilyHide: Boolean,
    val collidingZones: List<HudCollisionWarning>,
    val allExclusionRects: List<HudExclusionRect>,
    val selectedSlotName: String
)

/**
 * Pure, allocation-light HUD-safe layout & collision solver shared between
 * [StarLiveLyricsOverlay] (runtime) and LiveLyricsSettingsScreen (draggable preview).
 *
 * Ensures Live Lyrics never covers Minecraft's hotbar, health/hunger/armor/XP bars,
 * drawer handle, STAR Touch HUD buttons, virtual joystick, or legacy ControlLayout buttons.
 */
object LiveLyricsHudLayoutSolver {

    fun buildExclusionZones(request: HudPlacementRequest): List<HudExclusionRect> {
        val w = request.containerWidthPx
        val h = request.containerHeightPx
        val d = max(1f, request.density)
        if (w <= 0f || h <= 0f) return emptyList()

        val zones = ArrayList<HudExclusionRect>(20)
        val liveById = HashMap<String, HudExclusionRect>(request.liveControlRects.size)
        for (rect in request.liveControlRects) {
            if (rect.width > 0f && rect.height > 0f) {
                liveById[rect.id] = rect
                zones.add(rect)
            }
        }

        if (request.includeDrawerPull && !liveById.containsKey("drawer_pull")) {
            val halfDrawerW = 22f * d
            val drawerH = 22f * d
            zones.add(
                HudExclusionRect(
                    id = "drawer_pull",
                    label = "Menu Handle",
                    category = HudZoneCategory.DRAWER_PULL,
                    left = (w * 0.5f) - halfDrawerW,
                    top = request.safeTopPx,
                    right = (w * 0.5f) + halfDrawerW,
                    bottom = request.safeTopPx + drawerH
                )
            )
        }

        val starInsets = StarHudSafeInsets(
            left = request.safeLeftPx.toInt(),
            top = request.safeTopPx.toInt(),
            right = request.safeRightPx.toInt(),
            bottom = request.safeBottomPx.toInt()
        )
        val starRects = StarHudLayoutSolver.solveAllEnabledRects(
            config = request.starHudConfig,
            containerWidth = w.toInt(),
            containerHeight = h.toInt(),
            density = d,
            insets = starInsets
        )

        var hotbarRectAdded = liveById.containsKey("star_HOTBAR") || liveById.containsKey("hotbar_view")
        for ((id, rect) in starRects) {
            val key = "star_${id.name}"
            if (liveById.containsKey(key)) continue
            if (id == StarHudElementId.HOTBAR) {
                hotbarRectAdded = true
                // Expand above touch hotbar to protect Minecraft's hearts, armor, hunger, and XP bar.
                zones.add(
                    HudExclusionRect(
                        id = key,
                        label = "Hotbar & Health/Hunger",
                        category = HudZoneCategory.HOTBAR_AND_VITALS,
                        left = rect.left.toFloat(),
                        top = max(0f, rect.top.toFloat() - 36f * d),
                        right = rect.right.toFloat(),
                        bottom = rect.bottom.toFloat()
                    )
                )
            } else {
                zones.add(
                    HudExclusionRect(
                        id = key,
                        label = elementDisplayName(id),
                        category = elementCategory(id),
                        left = rect.left.toFloat(),
                        top = rect.top.toFloat(),
                        right = rect.right.toFloat(),
                        bottom = rect.bottom.toFloat()
                    )
                )
            }
        }

        if (request.includeVanillaBottomHud && !hotbarRectAdded) {
            val usableW = max(1f, w - request.safeLeftPx - request.safeRightPx)
            val usableBottom = h - request.safeBottomPx
            val hudW = min(usableW * 0.50f, 260f * d)
            val hudH = 82f * d
            val centerX = request.safeLeftPx + usableW * 0.5f
            zones.add(
                HudExclusionRect(
                    id = "vanilla_bottom_hud",
                    label = "Hotbar & Vitals",
                    category = HudZoneCategory.HOTBAR_AND_VITALS,
                    left = centerX - hudW * 0.5f,
                    top = max(request.safeTopPx, usableBottom - hudH),
                    right = centerX + hudW * 0.5f,
                    bottom = usableBottom
                )
            )
        }

        if (request.includeChatSafetyZone && !liveById.containsKey("chat_area")) {
            val usableW = max(1f, w - request.safeLeftPx - request.safeRightPx)
            val usableH = max(1f, h - request.safeTopPx - request.safeBottomPx)
            zones.add(
                HudExclusionRect(
                    id = "chat_area",
                    label = "Minecraft Chat",
                    category = HudZoneCategory.CHAT_AREA,
                    left = request.safeLeftPx + 4f * d,
                    top = h - request.safeBottomPx - min(usableH * 0.42f, 175f * d),
                    right = request.safeLeftPx + min(usableW * 0.42f, 290f * d),
                    bottom = h - request.safeBottomPx - 86f * d
                )
            )
        }

        return zones
    }

    fun solve(request: HudPlacementRequest): HudPlacementResult {
        val zones = buildExclusionZones(request)
        val w = request.containerWidthPx
        val h = request.containerHeightPx
        val d = max(1f, request.density)
        if (w <= 0f || h <= 0f || request.desiredWidthPx <= 0f || request.desiredHeightPx <= 0f) {
            return HudPlacementResult(
                leftPx = request.safeLeftPx,
                topPx = request.safeTopPx,
                widthPx = max(0f, request.desiredWidthPx),
                heightPx = max(0f, request.desiredHeightPx),
                appliedScale = 1f,
                autoRepositioned = false,
                shouldTemporarilyHide = false,
                collidingZones = emptyList(),
                allExclusionRects = zones,
                selectedSlotName = "InvalidBounds"
            )
        }

        val edgePadPx = 6f * d
        val minX = request.safeLeftPx + edgePadPx
        val maxX = max(minX, w - request.safeRightPx - edgePadPx)
        val minY = request.safeTopPx + edgePadPx
        val maxY = max(minY, h - request.safeBottomPx - edgePadPx)
        val usableW = max(1f, maxX - minX)
        val usableH = max(1f, maxY - minY)
        val collisionMarginPx = 4f * d

        // Manual placement mode: stay exactly where the user placed it (within safe screen bounds),
        // and report any colliding HUD zones as warnings for settings UI / awareness.
        if (!request.config.autoHudSafePositioning) {
            val fitScale = min(
                1f,
                min(
                    usableW / max(1f, request.desiredWidthPx),
                    usableH / max(1f, request.desiredHeightPx)
                )
            )
            val pillW = min(request.desiredWidthPx * fitScale, usableW)
            val pillH = min(request.desiredHeightPx * fitScale, usableH)
            val centerX = request.safeLeftPx + (w - request.safeLeftPx - request.safeRightPx) * request.config.manualPosXFraction
            val centerY = request.safeTopPx + (h - request.safeTopPx - request.safeBottomPx) * request.config.manualPosYFraction
            val clampedLeft = (centerX - pillW * 0.5f).coerceIn(minX, max(minX, maxX - pillW))
            val clampedTop = (centerY - pillH * 0.5f).coerceIn(minY, max(minY, maxY - pillH))
            val collisions = findCollisions(zones, clampedLeft, clampedTop, clampedLeft + pillW, clampedTop + pillH, collisionMarginPx)
            return HudPlacementResult(
                leftPx = clampedLeft,
                topPx = clampedTop,
                widthPx = pillW,
                heightPx = pillH,
                appliedScale = fitScale,
                autoRepositioned = false,
                shouldTemporarilyHide = false,
                collidingZones = collisions,
                allExclusionRects = zones,
                selectedSlotName = "Manual"
            )
        }

        // Auto HUD-safe mode: evaluate preferred slot, then conflict-free candidates, then gentle shrink,
        // and only temporarily hide if no non-overlapping safe spot exists.
        val scalesToTry = floatArrayOf(1.0f, 0.88f, 0.76f)
        for (scale in scalesToTry) {
            val pillW = min(request.desiredWidthPx * scale, usableW * 0.94f)
            val pillH = min(request.desiredHeightPx * scale, usableH * 0.65f)
            val candidates = buildCandidates(
                request = request,
                pillW = pillW,
                pillH = pillH,
                minX = minX,
                maxX = maxX,
                minY = minY,
                maxY = maxY,
                density = d,
                zones = zones
            )

            for (index in candidates.indices) {
                val candidate = candidates[index]
                val left = candidate.left.coerceIn(minX, max(minX, maxX - pillW))
                val top = candidate.top.coerceIn(minY, max(minY, maxY - pillH))
                val collisions = findCollisions(
                    zones = zones,
                    left = left,
                    top = top,
                    right = left + pillW,
                    bottom = top + pillH,
                    marginPx = collisionMarginPx
                )
                if (collisions.isEmpty()) {
                    val didMoveOrResize = index > 0 || scale < 0.99f
                    return HudPlacementResult(
                        leftPx = left,
                        topPx = top,
                        widthPx = pillW,
                        heightPx = pillH,
                        appliedScale = scale,
                        autoRepositioned = didMoveOrResize,
                        shouldTemporarilyHide = false,
                        collidingZones = emptyList(),
                        allExclusionRects = zones,
                        selectedSlotName = candidate.name
                    )
                }
            }
        }

        // No conflict-free position found anywhere -> temporarily hide rather than blocking gameplay HUD.
        val fallbackW = min(request.desiredWidthPx * 0.76f, usableW)
        val fallbackH = min(request.desiredHeightPx * 0.76f, usableH)
        val fallbackLeft = (minX + (usableW - fallbackW) * 0.5f).coerceIn(minX, max(minX, maxX - fallbackW))
        val fallbackTop = (minY + 26f * d).coerceIn(minY, max(minY, maxY - fallbackH))
        val remainingCollisions = findCollisions(
            zones, fallbackLeft, fallbackTop, fallbackLeft + fallbackW, fallbackTop + fallbackH, collisionMarginPx
        )
        return HudPlacementResult(
            leftPx = fallbackLeft,
            topPx = fallbackTop,
            widthPx = fallbackW,
            heightPx = fallbackH,
            appliedScale = 0.76f,
            autoRepositioned = true,
            shouldTemporarilyHide = true,
            collidingZones = remainingCollisions,
            allExclusionRects = zones,
            selectedSlotName = "HiddenDueToOverlap"
        )
    }

    private data class CandidateSlot(
        val name: String,
        val left: Float,
        val top: Float
    )

    private fun buildCandidates(
        request: HudPlacementRequest,
        pillW: Float,
        pillH: Float,
        minX: Float,
        maxX: Float,
        minY: Float,
        maxY: Float,
        density: Float,
        zones: List<HudExclusionRect>
    ): List<CandidateSlot> {
        val usableW = max(1f, maxX - minX)
        val usableH = max(1f, maxY - minY)
        val centerLeft = minX + (usableW - pillW) * 0.5f
        val offsetShiftPx = usableW * request.config.offsetXFraction
        val prefTop = request.safeTopPx + request.config.offsetYDp * density
        val prefLeft = when (request.config.positionAnchor) {
            IslandPosition.TOP_LEFT -> minX + 4f * density + offsetShiftPx
            IslandPosition.TOP_RIGHT -> maxX - pillW - 4f * density + offsetShiftPx
            IslandPosition.TOP_CENTER -> centerLeft + offsetShiftPx
        }

        val belowDrawerTop = request.safeTopPx + 26f * density
        // Bottom edge of any top-row HUD icons (INVENTORY/CHAT/THIRD_PERSON/PAUSE etc.)
        var topHudBottom = request.safeTopPx
        for (z in zones) {
            if (z.category == HudZoneCategory.TOP_HUD_ACTION || z.category == HudZoneCategory.LEGACY_CONTROL) {
                if (z.top < request.safeTopPx + usableH * 0.25f) {
                    topHudBottom = max(topHudBottom, z.bottom)
                }
            }
        }
        val belowTopHudBar = max(belowDrawerTop, topHudBottom + 6f * density)

        // Find horizontal free intervals in the top band (between minY and minY + 52dp)
        // so compact pills sit nicely in the upper-left-center gap clear of right-side HUD buttons & drawer pull!
        val topLeftEdge = findRightmostObstructionEdgeBefore(
            zones, minX, minY, minY + pillH + 8f * density, minX + usableW * 0.45f
        ) ?: minX
        val topRightEdge = findLeftmostObstructionEdgeAfter(
            zones, maxX, minY, minY + pillH + 8f * density, minX + usableW * 0.35f
        ) ?: maxX
        val topFreeBandLeft = topLeftEdge + 6f * density
        val topFreeBandRight = topRightEdge - 6f * density

        val list = ArrayList<CandidateSlot>(12)
        list.add(CandidateSlot("Preferred", prefLeft, prefTop))
        list.add(CandidateSlot("TopCenterBelowDrawer", centerLeft, belowDrawerTop))
        if (topFreeBandRight - topFreeBandLeft >= pillW) {
            val bandCenterLeft = (topFreeBandLeft + topFreeBandRight - pillW) * 0.5f
            list.add(CandidateSlot("TopGapSafe", bandCenterLeft, belowDrawerTop))
            list.add(CandidateSlot("TopGapHigh", topFreeBandLeft + 2f * density, minY + 2f * density))
        }
        list.add(CandidateSlot("UpperCenterSafe", centerLeft, belowTopHudBar))
        list.add(CandidateSlot("UpperLeftSafe", minX + 4f * density, belowTopHudBar))
        list.add(CandidateSlot("UpperRightSafe", maxX - pillW - 4f * density, belowTopHudBar))
        list.add(CandidateSlot("TopLeftEdge", minX + 4f * density, minY + 2f * density))
        list.add(CandidateSlot("TopRightEdge", maxX - pillW - 4f * density, minY + 2f * density))

        // Secondary fallback shelves (upper-mid or lower-mid corners away from crosshair at 50% H & hotbar bottom)
        val upperQuarterY = request.safeTopPx + usableH * 0.19f
        list.add(CandidateSlot("QuarterCenter", centerLeft, upperQuarterY))
        list.add(CandidateSlot("QuarterLeft", minX + 4f * density, upperQuarterY))
        list.add(CandidateSlot("QuarterRight", maxX - pillW - 4f * density, upperQuarterY))

        val midBottomCornerY = maxY - pillH - 4f * density
        list.add(CandidateSlot("BottomLeftClear", minX + 4f * density, midBottomCornerY))
        list.add(CandidateSlot("BottomRightClear", maxX - pillW - 4f * density, midBottomCornerY))
        return list
    }

    private fun findRightmostObstructionEdgeBefore(
        zones: List<HudExclusionRect>,
        startX: Float,
        bandTop: Float,
        bandBottom: Float,
        limitX: Float
    ): Float? {
        var edge: Float? = null
        for (z in zones) {
            if (z.bottom > bandTop && z.top < bandBottom && z.right <= limitX && z.right > startX) {
                edge = max(edge ?: z.right, z.right)
            }
        }
        return edge
    }

    private fun findLeftmostObstructionEdgeAfter(
        zones: List<HudExclusionRect>,
        endX: Float,
        bandTop: Float,
        bandBottom: Float,
        afterX: Float
    ): Float? {
        var edge: Float? = null
        for (z in zones) {
            if (z.bottom > bandTop && z.top < bandBottom && z.left >= afterX && z.left < endX) {
                edge = min(edge ?: z.left, z.left)
            }
        }
        return edge
    }

    fun findCollisions(
        zones: List<HudExclusionRect>,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        marginPx: Float = 0f
    ): List<HudCollisionWarning> {
        val warnings = ArrayList<HudCollisionWarning>()
        for (z in zones) {
            if (z.overlaps(left, top, right, bottom, marginPx)) {
                warnings.add(
                    HudCollisionWarning(
                        zoneId = z.id,
                        label = z.label,
                        category = z.category,
                        rect = z
                    )
                )
            }
        }
        return warnings
    }

    data class DragClampResult(
        val clampedLeftPx: Float,
        val clampedTopPx: Float,
        val normalizedX: Float,
        val normalizedY: Float
    )

    fun clampManualPosition(
        containerWidthPx: Float,
        containerHeightPx: Float,
        density: Float,
        safeLeftPx: Float,
        safeTopPx: Float,
        safeRightPx: Float,
        safeBottomPx: Float,
        pillWidthPx: Float,
        pillHeightPx: Float,
        desiredLeftPx: Float,
        desiredTopPx: Float
    ): DragClampResult {
        val d = max(1f, density)
        val edgePadPx = 6f * d
        val minX = safeLeftPx + edgePadPx
        val maxX = max(minX, containerWidthPx - safeRightPx - edgePadPx)
        val minY = safeTopPx + edgePadPx
        val maxY = max(minY, containerHeightPx - safeBottomPx - edgePadPx)
        val clampedW = min(pillWidthPx, max(1f, maxX - minX))
        val clampedH = min(pillHeightPx, max(1f, maxY - minY))
        val clampedLeft = desiredLeftPx.coerceIn(minX, max(minX, maxX - clampedW))
        val clampedTop = desiredTopPx.coerceIn(minY, max(minY, maxY - clampedH))
        val centerX = clampedLeft + clampedW * 0.5f
        val centerY = clampedTop + clampedH * 0.5f
        val safeSpanW = max(1f, containerWidthPx - safeLeftPx - safeRightPx)
        val safeSpanH = max(1f, containerHeightPx - safeTopPx - safeBottomPx)
        val normX = ((centerX - safeLeftPx) / safeSpanW).coerceIn(0f, 1f)
        val normY = ((centerY - safeTopPx) / safeSpanH).coerceIn(0f, 1f)
        return DragClampResult(
            clampedLeftPx = clampedLeft,
            clampedTopPx = clampedTop,
            normalizedX = normX,
            normalizedY = normY
        )
    }

    fun rectFromCoordinates(
        id: String,
        label: String,
        category: HudZoneCategory,
        rect: RectF
    ): HudExclusionRect = HudExclusionRect(
        id = id,
        label = label,
        category = category,
        left = rect.left,
        top = rect.top,
        right = rect.right,
        bottom = rect.bottom
    )

    private fun elementDisplayName(id: StarHudElementId): String = when (id) {
        StarHudElementId.JOYSTICK -> "Joystick"
        StarHudElementId.JUMP -> "Jump Button"
        StarHudElementId.ATTACK -> "Attack Button"
        StarHudElementId.INTERACT -> "Interact Button"
        StarHudElementId.SNEAK -> "Sneak Button"
        StarHudElementId.SPRINT -> "Sprint Button"
        StarHudElementId.INVENTORY -> "Inventory Button"
        StarHudElementId.CHAT -> "Chat Button"
        StarHudElementId.THIRD_PERSON -> "Perspective Button"
        StarHudElementId.PAUSE -> "Pause Button"
        StarHudElementId.HOTBAR -> "Hotbar"
    }

    private fun elementCategory(id: StarHudElementId): HudZoneCategory = when (id) {
        StarHudElementId.JOYSTICK -> HudZoneCategory.JOYSTICK
        StarHudElementId.HOTBAR -> HudZoneCategory.HOTBAR_AND_VITALS
        StarHudElementId.INVENTORY,
        StarHudElementId.CHAT,
        StarHudElementId.THIRD_PERSON,
        StarHudElementId.PAUSE -> HudZoneCategory.TOP_HUD_ACTION
        else -> HudZoneCategory.TOUCH_BUTTON
    }
}
