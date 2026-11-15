package com.star.launcher.hud

import kotlin.math.roundToInt

/**
 * Identifiers for built-in core elements in the STAR Launcher in-game touch HUD.
 * Retained for backward compatibility alongside the extensible [StarHudButtonConfig] list.
 */
enum class StarHudElementId(
    val displayName: String,
    val description: String,
    val defaultXFraction: Float,
    val defaultYFraction: Float,
    val defaultEnabled: Boolean = true,
    val isCoreCombatOrMovement: Boolean = true
) {
    JOYSTICK(
        displayName = "Movement Joystick",
        description = "Left-side 360° virtual movement pad (WASD)",
        defaultXFraction = 0.025f,
        defaultYFraction = 0.85f,
        defaultEnabled = true,
        isCoreCombatOrMovement = true
    ),
    JUMP(
        displayName = "Jump",
        description = "Hold to jump continuously or swim upward (Space)",
        defaultXFraction = 0.965f,
        defaultYFraction = 0.88f,
        defaultEnabled = true,
        isCoreCombatOrMovement = true
    ),
    ATTACK(
        displayName = "Attack / Mine",
        description = "Tap to attack or hold to break blocks (Left Click)",
        defaultXFraction = 0.825f,
        defaultYFraction = 0.62f,
        defaultEnabled = true,
        isCoreCombatOrMovement = true
    ),
    INTERACT(
        displayName = "Interact / Use",
        description = "Tap to place/use or hold to eat/draw bow (Right Click)",
        defaultXFraction = 0.965f,
        defaultYFraction = 0.57f,
        defaultEnabled = true,
        isCoreCombatOrMovement = true
    ),
    SNEAK(
        displayName = "Sneak / Crouch",
        description = "Crouch safely at block edges (Left Shift)",
        defaultXFraction = 0.825f,
        defaultYFraction = 0.90f,
        defaultEnabled = true,
        isCoreCombatOrMovement = true
    ),
    SPRINT(
        displayName = "Sprint",
        description = "Optional sprint toggle/hold button (Left Ctrl)",
        defaultXFraction = 0.045f,
        defaultYFraction = 0.41f,
        defaultEnabled = true,
        isCoreCombatOrMovement = true
    ),
    INVENTORY(
        displayName = "Inventory",
        description = "Open player inventory screen (E)",
        defaultXFraction = 0.690f,
        defaultYFraction = 0.03f,
        defaultEnabled = true,
        isCoreCombatOrMovement = false
    ),
    CHAT(
        displayName = "Chat",
        description = "Open in-game chat prompt (T)",
        defaultXFraction = 0.785f,
        defaultYFraction = 0.03f,
        defaultEnabled = true,
        isCoreCombatOrMovement = false
    ),
    THIRD_PERSON(
        displayName = "Camera View",
        description = "Cycle first/third-person perspective (F5)",
        defaultXFraction = 0.880f,
        defaultYFraction = 0.03f,
        defaultEnabled = true,
        isCoreCombatOrMovement = false
    ),
    PAUSE(
        displayName = "Pause / Back",
        description = "Open pause menu or close active screen (Esc)",
        defaultXFraction = 0.975f,
        defaultYFraction = 0.03f,
        defaultEnabled = true,
        isCoreCombatOrMovement = false
    ),
    HOTBAR(
        displayName = "Hotbar Overlay",
        description = "9-slot tap & horizontal-swipe hotbar at bottom center",
        defaultXFraction = 0.50f,
        defaultYFraction = 1.00f,
        defaultEnabled = true,
        isCoreCombatOrMovement = false
    );

    fun toBuiltInButtonId(): String? = when (this) {
        JOYSTICK, HOTBAR -> null
        JUMP -> "builtin_jump"
        ATTACK -> "builtin_attack"
        INTERACT -> "builtin_interact"
        SNEAK -> "builtin_sneak"
        SPRINT -> "builtin_sprint"
        INVENTORY -> "builtin_inventory"
        CHAT -> "builtin_chat"
        THIRD_PERSON -> "builtin_third_person"
        PAUSE -> "builtin_pause"
    }

    fun toActionType(): StarHudActionType = when (this) {
        JOYSTICK -> StarHudActionType.JOYSTICK
        JUMP -> StarHudActionType.JUMP
        ATTACK -> StarHudActionType.ATTACK
        INTERACT -> StarHudActionType.INTERACT
        SNEAK -> StarHudActionType.SNEAK
        SPRINT -> StarHudActionType.SPRINT
        INVENTORY -> StarHudActionType.INVENTORY
        CHAT -> StarHudActionType.CHAT
        THIRD_PERSON -> StarHudActionType.TOGGLE_PERSPECTIVE
        PAUSE -> StarHudActionType.PAUSE_MENU
        HOTBAR -> StarHudActionType.HOTBAR_STRIP
    }

    companion object {
        @JvmStatic
        fun fromBuiltInButtonId(buttonId: String?): StarHudElementId? = when (buttonId) {
            "builtin_jump" -> JUMP
            "builtin_attack" -> ATTACK
            "builtin_interact" -> INTERACT
            "builtin_sneak" -> SNEAK
            "builtin_sprint" -> SPRINT
            "builtin_inventory" -> INVENTORY
            "builtin_chat" -> CHAT
            "builtin_third_person" -> THIRD_PERSON
            "builtin_pause" -> PAUSE
            else -> null
        }
    }
}

/**
 * Normalized position & visibility of an individual structural HUD element within safe-area bounds.
 */
data class StarHudElementLayout(
    val xFraction: Float,
    val yFraction: Float,
    val enabled: Boolean = true
) {
    fun clamped(): StarHudElementLayout = copy(
        xFraction = xFraction.coerceIn(0f, 1f),
        yFraction = yFraction.coerceIn(0f, 1f)
    )
}

/**
 * Serializable definition for an individual customizable touch button on the STAR Controller.
 */
data class StarHudButtonConfig(
    val id: String,
    val action: StarHudActionType,
    val customKeycode: Int = 0,
    val xFraction: Float,
    val yFraction: Float,
    val widthDp: Float = action.defaultWidthDp,
    val heightDp: Float = action.defaultHeightDp,
    val opacity: Float = 1.0f,
    val backgroundAlpha: Float = 0.80f,
    val icon: StarHudIconId = action.defaultIcon,
    val label: String = action.defaultShortLabel,
    val visible: Boolean = true,
    val interactionMode: StarHudInteractionMode = action.defaultInteractionMode,
    val isBuiltIn: Boolean = false
) {
    fun sanitized(): StarHudButtonConfig {
        val safeAction = if (action.isBuiltInStructural) StarHudActionType.CUSTOM_KEY else action
        val safeMode = if (!safeAction.allowHoldOrToggle && interactionMode != StarHudInteractionMode.TAP) {
            StarHudInteractionMode.TAP
        } else {
            interactionMode
        }
        return copy(
            id = id.ifBlank { "btn_${System.currentTimeMillis()}" },
            action = safeAction,
            xFraction = xFraction.coerceIn(0f, 1f),
            yFraction = yFraction.coerceIn(0f, 1f),
            widthDp = widthDp.coerceIn(MIN_BUTTON_DP, MAX_BUTTON_DP),
            heightDp = heightDp.coerceIn(MIN_BUTTON_DP, MAX_BUTTON_DP),
            opacity = opacity.coerceIn(MIN_BUTTON_OPACITY, 1.0f),
            backgroundAlpha = backgroundAlpha.coerceIn(0f, 1.0f),
            label = label.take(12),
            interactionMode = safeMode
        )
    }

    fun effectiveShortLabel(): String {
        if (label.isNotBlank()) return label
        if (action == StarHudActionType.CUSTOM_KEY) {
            return StarHudActionRegistry.keyShortLabel(customKeycode)
        }
        return action.defaultShortLabel
    }

    companion object {
        const val MIN_BUTTON_DP = 32f
        const val MAX_BUTTON_DP = 140f
        const val MIN_BUTTON_OPACITY = 0.15f
    }
}

/**
 * Metadata summary for a saved controller layout.
 */
data class StarHudLayoutSummary(
    val layoutId: String,
    val layoutName: String,
    val updatedAtMs: Long,
    val buttonCount: Int,
    val isDefault: Boolean
)

/**
 * Persistent versioned configuration for STAR Launcher's customizable mobile touch controller.
 */
data class StarHudConfig(
    val layoutId: String = DEFAULT_LAYOUT_ID,
    val layoutName: String = DEFAULT_LAYOUT_NAME,
    val useStarHud: Boolean = true,
    val hudScale: Float = 1.0f,
    val hudOpacity: Float = 0.78f,
    val joystickSize: Float = 1.0f,
    val joystickDynamic: Boolean = true,
    val hotbarScale: Float = 1.0f,
    val cameraSensitivity: Float = 1.0f,
    val sneakToggle: Boolean = true,
    val sprintToggle: Boolean = true,
    val screenGesturesEnabled: Boolean = true,
    val snapToGuides: Boolean = true,
    val showAlignmentGuides: Boolean = true,
    val showInputDebugPanel: Boolean = false,
    val elements: Map<StarHudElementId, StarHudElementLayout> = defaultElements(),
    val buttons: List<StarHudButtonConfig> = defaultButtons()
) {
    fun elementLayout(id: StarHudElementId): StarHudElementLayout {
        val btnId = id.toBuiltInButtonId()
        if (btnId != null) {
            val match = buttons.firstOrNull { it.id == btnId }
            if (match != null) {
                return StarHudElementLayout(match.xFraction, match.yFraction, match.visible).clamped()
            }
        }
        return elements[id]?.clamped() ?: StarHudElementLayout(
            xFraction = id.defaultXFraction,
            yFraction = id.defaultYFraction,
            enabled = id.defaultEnabled
        )
    }

    fun isElementEnabled(id: StarHudElementId): Boolean = elementLayout(id).enabled

    fun findButton(buttonId: String): StarHudButtonConfig? = buttons.firstOrNull { it.id == buttonId }

    fun withElementEnabled(id: StarHudElementId, enabled: Boolean): StarHudConfig {
        val current = elementLayout(id)
        val nextElements = elements + (id to current.copy(enabled = enabled))
        val btnId = id.toBuiltInButtonId()
        val nextButtons = if (btnId != null) {
            buttons.map { if (it.id == btnId) it.copy(visible = enabled) else it }
        } else {
            buttons
        }
        return copy(elements = nextElements, buttons = nextButtons)
    }

    fun withElementPosition(id: StarHudElementId, xFraction: Float, yFraction: Float): StarHudConfig {
        val fx = xFraction.coerceIn(0f, 1f)
        val fy = yFraction.coerceIn(0f, 1f)
        val current = elementLayout(id)
        val nextElements = elements + (id to current.copy(xFraction = fx, yFraction = fy))
        val btnId = id.toBuiltInButtonId()
        val nextButtons = if (btnId != null) {
            buttons.map { if (it.id == btnId) it.copy(xFraction = fx, yFraction = fy) else it }
        } else {
            buttons
        }
        return copy(elements = nextElements, buttons = nextButtons)
    }

    fun withButtonUpdated(updated: StarHudButtonConfig): StarHudConfig {
        val cleanBtn = updated.sanitized()
        val exists = buttons.any { it.id == cleanBtn.id }
        val nextButtons = if (exists) {
            buttons.map { if (it.id == cleanBtn.id) cleanBtn else it }
        } else {
            buttons + cleanBtn
        }
        val legacyId = StarHudElementId.fromBuiltInButtonId(cleanBtn.id)
        val nextElements = if (legacyId != null) {
            elements + (legacyId to StarHudElementLayout(cleanBtn.xFraction, cleanBtn.yFraction, cleanBtn.visible))
        } else {
            elements
        }
        return copy(elements = nextElements, buttons = nextButtons)
    }

    fun withButtonPosition(buttonId: String, xFraction: Float, yFraction: Float): StarHudConfig {
        val existing = findButton(buttonId) ?: return this
        return withButtonUpdated(existing.copy(xFraction = xFraction.coerceIn(0f, 1f), yFraction = yFraction.coerceIn(0f, 1f)))
    }

    fun withButtonSize(buttonId: String, widthDp: Float, heightDp: Float): StarHudConfig {
        val existing = findButton(buttonId) ?: return this
        return withButtonUpdated(existing.copy(widthDp = widthDp, heightDp = heightDp))
    }

    fun withAddedButton(button: StarHudButtonConfig): StarHudConfig {
        val uniqueId = if (buttons.any { it.id == button.id } || button.id.isBlank()) {
            nextUniqueCustomButtonId(button.action)
        } else {
            button.id
        }
        return withButtonUpdated(button.copy(id = uniqueId, isBuiltIn = false))
    }

    fun withRemovedButton(buttonId: String): StarHudConfig {
        val target = findButton(buttonId) ?: return this
        return if (target.isBuiltIn) {
            withButtonUpdated(target.copy(visible = false))
        } else {
            copy(buttons = buttons.filterNot { it.id == buttonId })
        }
    }

    fun withDuplicatedButton(buttonId: String): StarHudConfig {
        val source = findButton(buttonId) ?: return this
        val copyBtn = source.copy(
            id = nextUniqueCustomButtonId(source.action),
            xFraction = (source.xFraction - 0.06f).coerceIn(0.05f, 0.95f),
            yFraction = (source.yFraction - 0.06f).coerceIn(0.05f, 0.95f),
            isBuiltIn = false
        )
        return withButtonUpdated(copyBtn)
    }

    fun withResetButton(buttonId: String): StarHudConfig {
        val existing = findButton(buttonId) ?: return this
        val defaultMatch = defaultButtons().firstOrNull { it.id == buttonId }
        return if (defaultMatch != null) {
            withButtonUpdated(defaultMatch)
        } else {
            withButtonUpdated(
                existing.copy(
                    widthDp = existing.action.defaultWidthDp,
                    heightDp = existing.action.defaultHeightDp,
                    opacity = 1.0f,
                    backgroundAlpha = 0.80f,
                    icon = existing.action.defaultIcon,
                    label = if (existing.action == StarHudActionType.CUSTOM_KEY) {
                        StarHudActionRegistry.keyShortLabel(existing.customKeycode)
                    } else {
                        existing.action.defaultShortLabel
                    },
                    visible = true,
                    interactionMode = existing.action.defaultInteractionMode
                )
            )
        }
    }

    fun nextUniqueCustomButtonId(action: StarHudActionType): String {
        val prefix = "custom_${action.name.lowercase()}"
        var index = 1
        while (buttons.any { it.id == "${prefix}_$index" }) {
            index++
        }
        return "${prefix}_$index"
    }

    fun sanitized(): StarHudConfig {
        val dedupedButtons = LinkedHashMap<String, StarHudButtonConfig>()
        val sourceButtons = if (buttons.isEmpty()) synthesizeButtonsFromElements(elements, sneakToggle, sprintToggle) else buttons
        sourceButtons.forEach { btn ->
            val clean = btn.sanitized()
            dedupedButtons[clean.id] = clean
        }
        val syncedElements = StarHudElementId.values().associateWith { id ->
            val btnId = id.toBuiltInButtonId()
            val btn = if (btnId != null) dedupedButtons[btnId] else null
            if (btn != null) {
                StarHudElementLayout(btn.xFraction, btn.yFraction, btn.visible).clamped()
            } else {
                elements[id]?.clamped() ?: StarHudElementLayout(
                    xFraction = id.defaultXFraction,
                    yFraction = id.defaultYFraction,
                    enabled = id.defaultEnabled
                )
            }
        }
        return copy(
            layoutId = layoutId.ifBlank { DEFAULT_LAYOUT_ID },
            layoutName = layoutName.ifBlank { DEFAULT_LAYOUT_NAME }.take(40),
            hudScale = hudScale.coerceIn(MIN_HUD_SCALE, MAX_HUD_SCALE),
            hudOpacity = hudOpacity.coerceIn(MIN_HUD_OPACITY, MAX_HUD_OPACITY),
            joystickSize = joystickSize.coerceIn(MIN_JOYSTICK_SIZE, MAX_JOYSTICK_SIZE),
            hotbarScale = hotbarScale.coerceIn(MIN_HOTBAR_SCALE, MAX_HOTBAR_SCALE),
            cameraSensitivity = cameraSensitivity.coerceIn(MIN_CAMERA_SENSITIVITY, MAX_CAMERA_SENSITIVITY),
            elements = syncedElements,
            buttons = dedupedButtons.values.toList()
        )
    }

    fun scalePercent(): Int = (hudScale * 100f).roundToInt()
    fun opacityPercent(): Int = (hudOpacity * 100f).roundToInt()
    fun joystickSizePercent(): Int = (joystickSize * 100f).roundToInt()
    fun hotbarScalePercent(): Int = (hotbarScale * 100f).roundToInt()
    fun cameraSensitivityPercent(): Int = (cameraSensitivity * 100f).roundToInt()

    companion object {
        const val CONFIG_VERSION = 2
        const val DEFAULT_LAYOUT_ID = "default"
        const val DEFAULT_LAYOUT_NAME = "Default Controller"

        const val MIN_HUD_SCALE = 0.65f
        const val MAX_HUD_SCALE = 1.50f
        const val MIN_HUD_OPACITY = 0.20f
        const val MAX_HUD_OPACITY = 1.00f
        const val MIN_JOYSTICK_SIZE = 0.65f
        const val MAX_JOYSTICK_SIZE = 1.50f
        const val MIN_HOTBAR_SCALE = 0.75f
        const val MAX_HOTBAR_SCALE = 1.40f
        const val MIN_CAMERA_SENSITIVITY = 0.25f
        const val MAX_CAMERA_SENSITIVITY = 3.00f

        @JvmStatic
        fun defaultElements(): Map<StarHudElementId, StarHudElementLayout> =
            StarHudElementId.values().associateWith { id ->
                StarHudElementLayout(
                    xFraction = id.defaultXFraction,
                    yFraction = id.defaultYFraction,
                    enabled = id.defaultEnabled
                )
            }

        @JvmStatic
        fun defaultButtons(): List<StarHudButtonConfig> =
            synthesizeButtonsFromElements(defaultElements(), sneakToggle = true, sprintToggle = true)

        @JvmStatic
        fun synthesizeButtonsFromElements(
            elements: Map<StarHudElementId, StarHudElementLayout>,
            sneakToggle: Boolean,
            sprintToggle: Boolean
        ): List<StarHudButtonConfig> {
            val result = mutableListOf<StarHudButtonConfig>()
            StarHudElementId.values().forEach { id ->
                val btnId = id.toBuiltInButtonId() ?: return@forEach
                val action = id.toActionType()
                val layout = elements[id] ?: StarHudElementLayout(id.defaultXFraction, id.defaultYFraction, id.defaultEnabled)
                val mode = when (id) {
                    StarHudElementId.SNEAK -> if (sneakToggle) StarHudInteractionMode.TOGGLE else StarHudInteractionMode.HOLD
                    StarHudElementId.SPRINT -> if (sprintToggle) StarHudInteractionMode.TOGGLE else StarHudInteractionMode.HOLD
                    else -> action.defaultInteractionMode
                }
                result += StarHudButtonConfig(
                    id = btnId,
                    action = action,
                    customKeycode = 0,
                    xFraction = layout.xFraction.coerceIn(0f, 1f),
                    yFraction = layout.yFraction.coerceIn(0f, 1f),
                    widthDp = action.defaultWidthDp,
                    heightDp = action.defaultHeightDp,
                    opacity = 1.0f,
                    backgroundAlpha = 0.80f,
                    icon = action.defaultIcon,
                    label = action.defaultShortLabel,
                    visible = layout.enabled,
                    interactionMode = mode,
                    isBuiltIn = true
                )
            }
            return result
        }

        @JvmStatic
        fun createButtonForAction(
            id: String,
            action: StarHudActionType,
            customKeycode: Int = 0,
            xFraction: Float = 0.50f,
            yFraction: Float = 0.50f
        ): StarHudButtonConfig {
            val cleanLabel = if (action == StarHudActionType.CUSTOM_KEY) {
                StarHudActionRegistry.keyShortLabel(customKeycode)
            } else {
                action.defaultShortLabel
            }
            return StarHudButtonConfig(
                id = id,
                action = action,
                customKeycode = customKeycode,
                xFraction = xFraction.coerceIn(0f, 1f),
                yFraction = yFraction.coerceIn(0f, 1f),
                widthDp = action.defaultWidthDp,
                heightDp = action.defaultHeightDp,
                opacity = 1.0f,
                backgroundAlpha = 0.80f,
                icon = action.defaultIcon,
                label = cleanLabel,
                visible = true,
                interactionMode = action.defaultInteractionMode,
                isBuiltIn = false
            ).sanitized()
        }

        @JvmStatic
        fun defaults(): StarHudConfig = StarHudConfig()
    }
}
