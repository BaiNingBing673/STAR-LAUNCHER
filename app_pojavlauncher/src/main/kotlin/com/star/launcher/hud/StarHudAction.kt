package com.star.launcher.hud

import net.kdt.pojavlaunch.LwjglGlfwKeycode

/**
 * Defines how a controller button dispatches its logical input.
 */
enum class StarHudInteractionMode(val displayName: String) {
    HOLD("Hold while pressed"),
    TAP("Single tap"),
    TOGGLE("Tap to toggle")
}

/**
 * Available original geometric vector icons in STAR Launcher's HUD.
 */
enum class StarHudIconId(val displayName: String) {
    NONE("Label Only"),
    JUMP("Jump Arrow"),
    ATTACK("Attack Blade"),
    INTERACT("Use Diamond"),
    SNEAK("Sneak Chevrons"),
    SPRINT("Sprint Double"),
    INVENTORY("Inventory Grid"),
    CHAT("Chat Bubble"),
    THIRD_PERSON("Camera Eye"),
    PAUSE("Pause Bars"),
    DROP("Drop Down"),
    SWAP_HANDS("Swap Arrows"),
    PICK_BLOCK("Pick Eyedrop"),
    HOTBAR_SLOT("Hotbar Slot"),
    HOTBAR_NEXT("Next Slot"),
    HOTBAR_PREV("Prev Slot"),
    KEY_CAP("Keycap"),
    MOVE_UP("Up Arrow"),
    MOVE_DOWN("Down Arrow"),
    MOVE_LEFT("Left Arrow"),
    MOVE_RIGHT("Right Arrow"),
    STAR_CUSTOM("STAR Badge")
}

/**
 * Descriptor for a single keyboard key available for custom button bindings.
 */
data class StarHudKeyBindingOption(
    val keycode: Int,
    val displayName: String,
    val shortLabel: String,
    val category: String
)

/**
 * Validation result for configuring an action / custom key binding.
 */
data class StarHudActionValidation(
    val isSupported: Boolean,
    val warningMessage: String? = null
)

/**
 * Logical target mapped to the LWJGL/GLFW input bridge.
 */
sealed class StarHudInputTarget {
    data class Key(val glfwKeycode: Int, val applyModifiers: Boolean = false) : StarHudInputTarget()
    data class Mouse(val glfwMouseButton: Int) : StarHudInputTarget()
    data class Scroll(val yOffset: Float) : StarHudInputTarget()
    object None : StarHudInputTarget()
}

/**
 * Centralized action catalog mapping gameplay actions, hotbar controls, and custom key
 * bindings to LWJGL/GLFW input descriptors without hardcoding actions inside visual buttons.
 */
enum class StarHudActionType(
    val displayName: String,
    val defaultShortLabel: String,
    val defaultIcon: StarHudIconId,
    val defaultInteractionMode: StarHudInteractionMode,
    val defaultWidthDp: Float,
    val defaultHeightDp: Float,
    val category: String,
    val isBuiltInStructural: Boolean = false,
    val allowHoldOrToggle: Boolean = true
) {
    // Structural elements
    JOYSTICK(
        displayName = "Movement Joystick",
        defaultShortLabel = "",
        defaultIcon = StarHudIconId.NONE,
        defaultInteractionMode = StarHudInteractionMode.HOLD,
        defaultWidthDp = 126f,
        defaultHeightDp = 126f,
        category = "Core",
        isBuiltInStructural = true,
        allowHoldOrToggle = false
    ),
    HOTBAR_STRIP(
        displayName = "Hotbar Overlay",
        defaultShortLabel = "",
        defaultIcon = StarHudIconId.NONE,
        defaultInteractionMode = StarHudInteractionMode.TAP,
        defaultWidthDp = 240f,
        defaultHeightDp = 48f,
        category = "Core",
        isBuiltInStructural = true,
        allowHoldOrToggle = false
    ),

    // Core Minecraft gameplay actions
    ATTACK(
        displayName = "Attack / Break Block",
        defaultShortLabel = "ATK",
        defaultIcon = StarHudIconId.ATTACK,
        defaultInteractionMode = StarHudInteractionMode.HOLD,
        defaultWidthDp = 62f,
        defaultHeightDp = 62f,
        category = "Gameplay"
    ),
    INTERACT(
        displayName = "Use Item / Interact",
        defaultShortLabel = "USE",
        defaultIcon = StarHudIconId.INTERACT,
        defaultInteractionMode = StarHudInteractionMode.HOLD,
        defaultWidthDp = 62f,
        defaultHeightDp = 62f,
        category = "Gameplay"
    ),
    JUMP(
        displayName = "Jump",
        defaultShortLabel = "",
        defaultIcon = StarHudIconId.JUMP,
        defaultInteractionMode = StarHudInteractionMode.HOLD,
        defaultWidthDp = 66f,
        defaultHeightDp = 66f,
        category = "Gameplay"
    ),
    SNEAK(
        displayName = "Sneak / Crouch",
        defaultShortLabel = "SNK",
        defaultIcon = StarHudIconId.SNEAK,
        defaultInteractionMode = StarHudInteractionMode.TOGGLE,
        defaultWidthDp = 54f,
        defaultHeightDp = 54f,
        category = "Gameplay"
    ),
    SPRINT(
        displayName = "Sprint",
        defaultShortLabel = "RUN",
        defaultIcon = StarHudIconId.SPRINT,
        defaultInteractionMode = StarHudInteractionMode.TOGGLE,
        defaultWidthDp = 54f,
        defaultHeightDp = 54f,
        category = "Gameplay"
    ),
    INVENTORY(
        displayName = "Inventory",
        defaultShortLabel = "",
        defaultIcon = StarHudIconId.INVENTORY,
        defaultInteractionMode = StarHudInteractionMode.TAP,
        defaultWidthDp = 44f,
        defaultHeightDp = 38f,
        category = "Gameplay",
        allowHoldOrToggle = false
    ),
    CHAT(
        displayName = "Open Chat",
        defaultShortLabel = "",
        defaultIcon = StarHudIconId.CHAT,
        defaultInteractionMode = StarHudInteractionMode.TAP,
        defaultWidthDp = 44f,
        defaultHeightDp = 38f,
        category = "Gameplay",
        allowHoldOrToggle = false
    ),
    DROP_ITEM(
        displayName = "Drop Item",
        defaultShortLabel = "DRP",
        defaultIcon = StarHudIconId.DROP,
        defaultInteractionMode = StarHudInteractionMode.TAP,
        defaultWidthDp = 50f,
        defaultHeightDp = 50f,
        category = "Gameplay",
        allowHoldOrToggle = true
    ),
    SWAP_HANDS(
        displayName = "Swap Hands",
        defaultShortLabel = "SWP",
        defaultIcon = StarHudIconId.SWAP_HANDS,
        defaultInteractionMode = StarHudInteractionMode.TAP,
        defaultWidthDp = 50f,
        defaultHeightDp = 50f,
        category = "Gameplay",
        allowHoldOrToggle = false
    ),
    PICK_BLOCK(
        displayName = "Pick Block",
        defaultShortLabel = "PCK",
        defaultIcon = StarHudIconId.PICK_BLOCK,
        defaultInteractionMode = StarHudInteractionMode.TAP,
        defaultWidthDp = 50f,
        defaultHeightDp = 50f,
        category = "Gameplay",
        allowHoldOrToggle = false
    ),
    TOGGLE_PERSPECTIVE(
        displayName = "Toggle Perspective",
        defaultShortLabel = "",
        defaultIcon = StarHudIconId.THIRD_PERSON,
        defaultInteractionMode = StarHudInteractionMode.TAP,
        defaultWidthDp = 44f,
        defaultHeightDp = 38f,
        category = "Gameplay",
        allowHoldOrToggle = false
    ),
    PAUSE_MENU(
        displayName = "Pause / Menu",
        defaultShortLabel = "",
        defaultIcon = StarHudIconId.PAUSE,
        defaultInteractionMode = StarHudInteractionMode.TAP,
        defaultWidthDp = 44f,
        defaultHeightDp = 38f,
        category = "Gameplay",
        allowHoldOrToggle = false
    ),

    // Hotbar actions
    HOTBAR_SLOT_1("Hotbar Slot 1", "1", StarHudIconId.HOTBAR_SLOT, StarHudInteractionMode.TAP, 44f, 44f, "Hotbar", allowHoldOrToggle = false),
    HOTBAR_SLOT_2("Hotbar Slot 2", "2", StarHudIconId.HOTBAR_SLOT, StarHudInteractionMode.TAP, 44f, 44f, "Hotbar", allowHoldOrToggle = false),
    HOTBAR_SLOT_3("Hotbar Slot 3", "3", StarHudIconId.HOTBAR_SLOT, StarHudInteractionMode.TAP, 44f, 44f, "Hotbar", allowHoldOrToggle = false),
    HOTBAR_SLOT_4("Hotbar Slot 4", "4", StarHudIconId.HOTBAR_SLOT, StarHudInteractionMode.TAP, 44f, 44f, "Hotbar", allowHoldOrToggle = false),
    HOTBAR_SLOT_5("Hotbar Slot 5", "5", StarHudIconId.HOTBAR_SLOT, StarHudInteractionMode.TAP, 44f, 44f, "Hotbar", allowHoldOrToggle = false),
    HOTBAR_SLOT_6("Hotbar Slot 6", "6", StarHudIconId.HOTBAR_SLOT, StarHudInteractionMode.TAP, 44f, 44f, "Hotbar", allowHoldOrToggle = false),
    HOTBAR_SLOT_7("Hotbar Slot 7", "7", StarHudIconId.HOTBAR_SLOT, StarHudInteractionMode.TAP, 44f, 44f, "Hotbar", allowHoldOrToggle = false),
    HOTBAR_SLOT_8("Hotbar Slot 8", "8", StarHudIconId.HOTBAR_SLOT, StarHudInteractionMode.TAP, 44f, 44f, "Hotbar", allowHoldOrToggle = false),
    HOTBAR_SLOT_9("Hotbar Slot 9", "9", StarHudIconId.HOTBAR_SLOT, StarHudInteractionMode.TAP, 44f, 44f, "Hotbar", allowHoldOrToggle = false),
    HOTBAR_NEXT("Next Hotbar Slot", "NXT", StarHudIconId.HOTBAR_NEXT, StarHudInteractionMode.TAP, 48f, 44f, "Hotbar", allowHoldOrToggle = false),
    HOTBAR_PREV("Previous Hotbar Slot", "PRV", StarHudIconId.HOTBAR_PREV, StarHudInteractionMode.TAP, 48f, 44f, "Hotbar", allowHoldOrToggle = false),

    // Custom key binding
    CUSTOM_KEY(
        displayName = "Custom Key Binding",
        defaultShortLabel = "KEY",
        defaultIcon = StarHudIconId.KEY_CAP,
        defaultInteractionMode = StarHudInteractionMode.HOLD,
        defaultWidthDp = 52f,
        defaultHeightDp = 52f,
        category = "Custom Key"
    );

    companion object {
        /**
         * All actions assignable to a pushable custom controller button.
         */
        @JvmStatic
        fun assignableButtonActions(): List<StarHudActionType> =
            values().filter { !it.isBuiltInStructural }
    }
}

/**
 * Centralized registry & resolver for actions and supported Minecraft Java GLFW keycodes.
 */
object StarHudActionRegistry {

    @JvmStatic
    val supportedKeys: List<StarHudKeyBindingOption> = listOf(
        // Movement & Stance
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_W.toInt(), "Move Forward (W)", "W", "Movement"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_A.toInt(), "Strafe Left (A)", "A", "Movement"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_S.toInt(), "Move Back (S)", "S", "Movement"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_D.toInt(), "Strafe Right (D)", "D", "Movement"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_SPACE.toInt(), "Space", "SPC", "Movement"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT.toInt(), "Left Shift", "SHF", "Movement"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL.toInt(), "Left Ctrl", "CTL", "Movement"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_LEFT_ALT.toInt(), "Left Alt", "ALT", "Movement"),

        // Common Gameplay Keys
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_E.toInt(), "E (Inventory)", "E", "Gameplay"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_Q.toInt(), "Q (Drop)", "Q", "Gameplay"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_F.toInt(), "F (Swap Hands)", "F", "Gameplay"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_T.toInt(), "T (Chat)", "T", "Gameplay"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_SLASH.toInt(), "/ (Command)", "/", "Gameplay"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_TAB.toInt(), "Tab (Players)", "TAB", "Gameplay"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_ESCAPE.toInt(), "Escape", "ESC", "Gameplay"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_ENTER.toInt(), "Enter", "ENT", "Gameplay"),

        // Extra Letters
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_B.toInt(), "B", "B", "Letters"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_C.toInt(), "C (Zoom / Mod)", "C", "Letters"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_G.toInt(), "G", "G", "Letters"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_H.toInt(), "H", "H", "Letters"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_I.toInt(), "I", "I", "Letters"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_J.toInt(), "J", "J", "Letters"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_K.toInt(), "K", "K", "Letters"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_L.toInt(), "L (Advancements)", "L", "Letters"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_M.toInt(), "M (Map / Mod)", "M", "Letters"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_N.toInt(), "N", "N", "Letters"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_O.toInt(), "O", "O", "Letters"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_P.toInt(), "P (Social)", "P", "Letters"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_R.toInt(), "R (Mod Key)", "R", "Letters"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_U.toInt(), "U", "U", "Letters"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_V.toInt(), "V (Voice / Mod)", "V", "Letters"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_X.toInt(), "X", "X", "Letters"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_Y.toInt(), "Y", "Y", "Letters"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_Z.toInt(), "Z", "Z", "Letters"),

        // Numbers
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_1.toInt(), "1", "1", "Numbers"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_2.toInt(), "2", "2", "Numbers"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_3.toInt(), "3", "3", "Numbers"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_4.toInt(), "4", "4", "Numbers"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_5.toInt(), "5", "5", "Numbers"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_6.toInt(), "6", "6", "Numbers"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_7.toInt(), "7", "7", "Numbers"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_8.toInt(), "8", "8", "Numbers"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_9.toInt(), "9", "9", "Numbers"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_0.toInt(), "0", "0", "Numbers"),

        // Function Keys & Arrows
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_F1.toInt(), "F1 (Hide HUD)", "F1", "Function"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_F2.toInt(), "F2 (Screenshot)", "F2", "Function"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_F3.toInt(), "F3 (Debug Screen)", "F3", "Function"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_F5.toInt(), "F5 (Perspective)", "F5", "Function"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_F6.toInt(), "F6", "F6", "Function"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_F7.toInt(), "F7", "F7", "Function"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_F8.toInt(), "F8", "F8", "Function"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_F9.toInt(), "F9", "F9", "Function"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_F10.toInt(), "F10", "F10", "Function"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_F11.toInt(), "F11", "F11", "Function"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_F12.toInt(), "F12", "F12", "Function"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_UP.toInt(), "Arrow Up", "UP", "Navigation"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_DOWN.toInt(), "Arrow Down", "DN", "Navigation"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_LEFT.toInt(), "Arrow Left", "LF", "Navigation"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_RIGHT.toInt(), "Arrow Right", "RT", "Navigation"),
        StarHudKeyBindingOption(LwjglGlfwKeycode.GLFW_KEY_GRAVE_ACCENT.toInt(), "Grave (`)", "`", "Navigation")
    )

    private val supportedKeyMap: Map<Int, StarHudKeyBindingOption> = supportedKeys.associateBy { it.keycode }

    @JvmStatic
    fun isKeySupported(keycode: Int): Boolean = supportedKeyMap.containsKey(keycode)

    @JvmStatic
    fun findKeyOption(keycode: Int): StarHudKeyBindingOption? = supportedKeyMap[keycode]

    @JvmStatic
    fun keyShortLabel(keycode: Int): String = supportedKeyMap[keycode]?.shortLabel ?: "?"

    @JvmStatic
    fun validateBinding(action: StarHudActionType, customKeycode: Int): StarHudActionValidation {
        if (action.isBuiltInStructural) {
            return StarHudActionValidation(
                isSupported = false,
                warningMessage = "${action.displayName} is a structural overlay element and cannot be bound to a button."
            )
        }
        if (action == StarHudActionType.CUSTOM_KEY && !isKeySupported(customKeycode)) {
            return StarHudActionValidation(
                isSupported = false,
                warningMessage = "Keycode $customKeycode is unsupported by STAR Launcher's GLFW input bridge."
            )
        }
        return StarHudActionValidation(isSupported = true)
    }

    @JvmStatic
    fun resolveTarget(action: StarHudActionType, customKeycode: Int): StarHudInputTarget = when (action) {
        StarHudActionType.ATTACK -> StarHudInputTarget.Mouse(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT.toInt())
        StarHudActionType.INTERACT -> StarHudInputTarget.Mouse(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT.toInt())
        StarHudActionType.PICK_BLOCK -> StarHudInputTarget.Mouse(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE.toInt())
        StarHudActionType.JUMP -> StarHudInputTarget.Key(LwjglGlfwKeycode.GLFW_KEY_SPACE.toInt(), applyModifiers = false)
        StarHudActionType.SNEAK -> StarHudInputTarget.Key(LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT.toInt(), applyModifiers = true)
        StarHudActionType.SPRINT -> StarHudInputTarget.Key(LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL.toInt(), applyModifiers = true)
        StarHudActionType.INVENTORY -> StarHudInputTarget.Key(LwjglGlfwKeycode.GLFW_KEY_E.toInt(), applyModifiers = false)
        StarHudActionType.CHAT -> StarHudInputTarget.Key(LwjglGlfwKeycode.GLFW_KEY_T.toInt(), applyModifiers = false)
        StarHudActionType.DROP_ITEM -> StarHudInputTarget.Key(LwjglGlfwKeycode.GLFW_KEY_Q.toInt(), applyModifiers = false)
        StarHudActionType.SWAP_HANDS -> StarHudInputTarget.Key(LwjglGlfwKeycode.GLFW_KEY_F.toInt(), applyModifiers = false)
        StarHudActionType.TOGGLE_PERSPECTIVE -> StarHudInputTarget.Key(LwjglGlfwKeycode.GLFW_KEY_F5.toInt(), applyModifiers = false)
        StarHudActionType.PAUSE_MENU -> StarHudInputTarget.Key(LwjglGlfwKeycode.GLFW_KEY_ESCAPE.toInt(), applyModifiers = false)
        StarHudActionType.HOTBAR_SLOT_1 -> StarHudInputTarget.Key(LwjglGlfwKeycode.GLFW_KEY_1.toInt(), applyModifiers = false)
        StarHudActionType.HOTBAR_SLOT_2 -> StarHudInputTarget.Key(LwjglGlfwKeycode.GLFW_KEY_2.toInt(), applyModifiers = false)
        StarHudActionType.HOTBAR_SLOT_3 -> StarHudInputTarget.Key(LwjglGlfwKeycode.GLFW_KEY_3.toInt(), applyModifiers = false)
        StarHudActionType.HOTBAR_SLOT_4 -> StarHudInputTarget.Key(LwjglGlfwKeycode.GLFW_KEY_4.toInt(), applyModifiers = false)
        StarHudActionType.HOTBAR_SLOT_5 -> StarHudInputTarget.Key(LwjglGlfwKeycode.GLFW_KEY_5.toInt(), applyModifiers = false)
        StarHudActionType.HOTBAR_SLOT_6 -> StarHudInputTarget.Key(LwjglGlfwKeycode.GLFW_KEY_6.toInt(), applyModifiers = false)
        StarHudActionType.HOTBAR_SLOT_7 -> StarHudInputTarget.Key(LwjglGlfwKeycode.GLFW_KEY_7.toInt(), applyModifiers = false)
        StarHudActionType.HOTBAR_SLOT_8 -> StarHudInputTarget.Key(LwjglGlfwKeycode.GLFW_KEY_8.toInt(), applyModifiers = false)
        StarHudActionType.HOTBAR_SLOT_9 -> StarHudInputTarget.Key(LwjglGlfwKeycode.GLFW_KEY_9.toInt(), applyModifiers = false)
        StarHudActionType.HOTBAR_NEXT -> StarHudInputTarget.Scroll(-1f)
        StarHudActionType.HOTBAR_PREV -> StarHudInputTarget.Scroll(1f)
        StarHudActionType.CUSTOM_KEY -> {
            if (isKeySupported(customKeycode)) {
                val applyMods = customKeycode == LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT.toInt() ||
                    customKeycode == LwjglGlfwKeycode.GLFW_KEY_RIGHT_SHIFT.toInt() ||
                    customKeycode == LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL.toInt() ||
                    customKeycode == LwjglGlfwKeycode.GLFW_KEY_RIGHT_CONTROL.toInt() ||
                    customKeycode == LwjglGlfwKeycode.GLFW_KEY_LEFT_ALT.toInt() ||
                    customKeycode == LwjglGlfwKeycode.GLFW_KEY_RIGHT_ALT.toInt()
                StarHudInputTarget.Key(customKeycode, applyModifiers = applyMods)
            } else {
                StarHudInputTarget.None
            }
        }
        StarHudActionType.JOYSTICK, StarHudActionType.HOTBAR_STRIP -> StarHudInputTarget.None
    }

    @JvmStatic
    fun keyCodeForJava(action: StarHudActionType, customKeycode: Int): Int =
        (resolveTarget(action, customKeycode) as? StarHudInputTarget.Key)?.glfwKeycode ?: 0

    @JvmStatic
    fun applyModifiersForJava(action: StarHudActionType, customKeycode: Int): Boolean =
        (resolveTarget(action, customKeycode) as? StarHudInputTarget.Key)?.applyModifiers ?: false

    @JvmStatic
    fun mouseButtonForJava(action: StarHudActionType): Int =
        (resolveTarget(action, 0) as? StarHudInputTarget.Mouse)?.glfwMouseButton ?: -1

    @JvmStatic
    fun scrollYForJava(action: StarHudActionType): Float =
        (resolveTarget(action, 0) as? StarHudInputTarget.Scroll)?.yOffset ?: 0f
}
