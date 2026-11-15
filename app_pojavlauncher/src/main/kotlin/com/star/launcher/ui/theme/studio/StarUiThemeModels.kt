package com.star.launcher.ui.theme.studio

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/**
 * Built-in theme preset identifiers for STAR UI Studio.
 */
enum class StarPresetId(val displayName: String, val subtitle: String) {
    STAR_LIQUID_GLASS(
        displayName = "STAR Liquid Glass",
        subtitle = "Electric sapphire glass with subtle refraction"
    ),
    MIDNIGHT_GLASS(
        displayName = "Midnight Glass",
        subtitle = "Deep obsidian night glass with crisp cobalt edges"
    ),
    AURORA_GLASS(
        displayName = "Aurora Glass",
        subtitle = "Boreal emerald-violet sheen and luminous highlights"
    ),
    WATER_CRYSTAL(
        displayName = "Water Crystal",
        subtitle = "Translucent aqua crystal with gentle water droplets"
    ),
    MINIMAL_DARK(
        displayName = "Minimal Dark",
        subtitle = "Matte architectural precision for maximum clarity"
    ),
    CUSTOM(
        displayName = "Custom",
        subtitle = "Personalized Liquid Glass configuration"
    );

    val isBuiltInPreset: Boolean
        get() = this != CUSTOM
}

/**
 * Effect quality tiers balancing Liquid Glass fidelity and frame budget across Android devices.
 */
enum class StarEffectQuality(
    val displayName: String,
    val description: String,
    val blurDownscaleFactor: Int,
    val allowsBackdropBlurSampling: Boolean,
    val allowsRefractionLayer: Boolean,
    val allowsWaterDroplets: Boolean,
    val allowsAnimatedDroplets: Boolean,
    val allowsShadows: Boolean
) {
    LOW(
        displayName = "Low",
        description = "Fast tinted surfaces, no blur or droplet passes (best for low-RAM devices)",
        blurDownscaleFactor = 10,
        allowsBackdropBlurSampling = false,
        allowsRefractionLayer = false,
        allowsWaterDroplets = false,
        allowsAnimatedDroplets = false,
        allowsShadows = false
    ),
    MEDIUM(
        displayName = "Medium",
        description = "Cached downscaled blur & subtle frosted borders without animated layers",
        blurDownscaleFactor = 6,
        allowsBackdropBlurSampling = true,
        allowsRefractionLayer = false,
        allowsWaterDroplets = false,
        allowsAnimatedDroplets = false,
        allowsShadows = true
    ),
    HIGH(
        displayName = "High",
        description = "Full Liquid Glass refraction, edge reflections, and static/subtle droplets",
        blurDownscaleFactor = 4,
        allowsBackdropBlurSampling = true,
        allowsRefractionLayer = true,
        allowsWaterDroplets = true,
        allowsAnimatedDroplets = true,
        allowsShadows = true
    ),
    ULTRA(
        displayName = "Ultra",
        description = "Highest-resolution cached backdrop blur, full specular lighting & animated droplets",
        blurDownscaleFactor = 3,
        allowsBackdropBlurSampling = true,
        allowsRefractionLayer = true,
        allowsWaterDroplets = true,
        allowsAnimatedDroplets = true,
        allowsShadows = true
    )
}

/**
 * Individual UI component categories customizable globally or independently in STAR UI Studio.
 */
enum class StarComponentTarget(val displayName: String, val description: String) {
    MAIN_NAVIGATION("Main Navigation", "Top app bar, bottom bar, and navigation rail"),
    BUTTONS("Buttons", "Primary play button, tonal, and outlined action buttons"),
    CARDS("Cards", "Standard content cards, metric chips, and status surfaces"),
    PANELS("Panels", "Hero artwork stage, banners, and structural panels"),
    DIALOGS("Dialogs", "Modal overlays, creation forms, and confirmation prompts"),
    SETTINGS_PANELS("Settings Panels", "Settings cards, engine preferences, and studio blocks"),
    VERSION_PANELS("Version Selection", "Minecraft version rows, filters, and detail panels"),
    MOD_MANAGER_PANELS("Mod Manager", "Mod catalog cards, detail panels, and filters"),
    DOWNLOAD_PROGRESS_PANELS("Download Progress", "Install progress bars and active download cards"),
    PROFILE_COMPONENTS("Profile Components", "Game instances, profiles, and account pills")
}

/**
 * Studio workspace editor categories.
 */
enum class StarStudioCategory(val title: String) {
    PRESETS("Presets"),
    BACKGROUND("Background"),
    LIQUID_GLASS("Liquid Glass"),
    COLORS("Colors"),
    COMPONENTS("Components"),
    PERFORMANCE("Quality")
}

/**
 * Custom launcher wallpaper & atmospheric overlay configuration.
 */
data class StarBackgroundConfig(
    val enabled: Boolean = false,
    val imagePath: String? = null,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val zoom: Float = 1f,
    val brightness: Float = 0f,
    val saturation: Float = 1f,
    val contrast: Float = 1f,
    val darkOverlayAlpha: Float = 0.36f,
    val gradientOverlayEnabled: Boolean = true,
    val gradientOverlayTopArgb: Long = 0xCC0A1224,
    val gradientOverlayBottomArgb: Long = 0xE6060912,
    val gradientOverlayAlpha: Float = 0.55f,
    val gradientAngleDeg: Float = 135f,
    val blurRadiusDp: Float = 0f
) {
    fun sanitized(): StarBackgroundConfig = copy(
        imagePath = imagePath?.trim()?.takeIf { it.isNotEmpty() },
        offsetX = offsetX.coerceFinite(-1f, 1f, 0f),
        offsetY = offsetY.coerceFinite(-1f, 1f, 0f),
        zoom = zoom.coerceFinite(1f, 3.5f, 1f),
        brightness = brightness.coerceFinite(-0.5f, 0.5f, 0f),
        saturation = saturation.coerceFinite(0f, 2f, 1f),
        contrast = contrast.coerceFinite(0.5f, 1.8f, 1f),
        darkOverlayAlpha = darkOverlayAlpha.coerceFinite(0f, 0.88f, 0.36f),
        gradientOverlayAlpha = gradientOverlayAlpha.coerceFinite(0f, 1f, 0.55f),
        gradientAngleDeg = ((gradientAngleDeg.takeIf { it.isFinite() } ?: 135f) % 360f + 360f) % 360f,
        blurRadiusDp = blurRadiusDp.coerceFinite(0f, 36f, 0f)
    )
}

/**
 * Global Liquid Glass engine parameters.
 */
data class StarGlassConfig(
    val blurIntensity: Float = 16f,
    val backgroundOpacity: Float = 0.64f,
    val borderOpacity: Float = 0.34f,
    val cornerRadiusDp: Float = 16f,
    val shadowIntensity: Float = 0.32f,
    val refractionIntensity: Float = 0.38f,
    val highlightIntensity: Float = 0.28f,
    val edgeReflectionIntensity: Float = 0.35f,
    val waterDropletsEnabled: Boolean = false,
    val waterDropletsAnimated: Boolean = true,
    val waterDropletIntensity: Float = 0.42f,
    val glassEffectEnabled: Boolean = true,
    val gradientEnabled: Boolean = true
) {
    fun sanitized(): StarGlassConfig = copy(
        blurIntensity = blurIntensity.coerceFinite(0f, 36f, 16f),
        backgroundOpacity = backgroundOpacity.coerceFinite(0.06f, 0.98f, 0.64f),
        borderOpacity = borderOpacity.coerceFinite(0f, 1f, 0.34f),
        cornerRadiusDp = cornerRadiusDp.coerceFinite(0f, 32f, 16f),
        shadowIntensity = shadowIntensity.coerceFinite(0f, 1f, 0.32f),
        refractionIntensity = refractionIntensity.coerceFinite(0f, 1f, 0.38f),
        highlightIntensity = highlightIntensity.coerceFinite(0f, 1f, 0.28f),
        edgeReflectionIntensity = edgeReflectionIntensity.coerceFinite(0f, 1f, 0.35f),
        waterDropletIntensity = waterDropletIntensity.coerceFinite(0.05f, 1f, 0.42f)
    )
}

/**
 * Editable color tokens stored as ARGB longs for lossless JSON serialization.
 */
data class StarColorPalette(
    val primaryAccentArgb: Long = 0xFF5FA8FF,
    val secondaryAccentArgb: Long = 0xFF5EE2FF,
    val backgroundTintArgb: Long = 0xFF0A0F1A,
    val textColorArgb: Long = 0xFFECEFF6,
    val borderColorArgb: Long = 0xFF5372A6,
    val gradientStartArgb: Long = 0xFF1A3A70,
    val gradientEndArgb: Long = 0xFF0B1C36,
    val highlightColorArgb: Long = 0xFFD9F0FF
) {
    fun sanitized(): StarColorPalette = copy(
        primaryAccentArgb = primaryAccentArgb.ensureOpaqueRgb(),
        secondaryAccentArgb = secondaryAccentArgb.ensureOpaqueRgb(),
        backgroundTintArgb = backgroundTintArgb.ensureOpaqueRgb(),
        textColorArgb = textColorArgb.ensureOpaqueRgb(),
        borderColorArgb = borderColorArgb.ensureOpaqueRgb(),
        gradientStartArgb = gradientStartArgb.ensureOpaqueRgb(),
        gradientEndArgb = gradientEndArgb.ensureOpaqueRgb(),
        highlightColorArgb = highlightColorArgb.ensureOpaqueRgb()
    )
}

/**
 * Per-component style override. When [overrideEnabled] is false, the component inherits from
 * the global Liquid Glass & color configuration.
 */
data class StarComponentStyleOverride(
    val overrideEnabled: Boolean = false,
    val backgroundOpacity: Float = 0.64f,
    val blurIntensity: Float = 16f,
    val borderOpacity: Float = 0.34f,
    val cornerRadiusDp: Float = 16f,
    val shadowIntensity: Float = 0.32f,
    val accentColorArgb: Long? = null,
    val gradientEnabled: Boolean = true,
    val gradientStartArgb: Long? = null,
    val gradientEndArgb: Long? = null,
    val glassEffectEnabled: Boolean = true
) {
    fun sanitized(): StarComponentStyleOverride = copy(
        backgroundOpacity = backgroundOpacity.coerceFinite(0.06f, 0.98f, 0.64f),
        blurIntensity = blurIntensity.coerceFinite(0f, 36f, 16f),
        borderOpacity = borderOpacity.coerceFinite(0f, 1f, 0.34f),
        cornerRadiusDp = cornerRadiusDp.coerceFinite(0f, 32f, 16f),
        shadowIntensity = shadowIntensity.coerceFinite(0f, 1f, 0.32f),
        accentColorArgb = accentColorArgb?.ensureOpaqueRgb(),
        gradientStartArgb = gradientStartArgb?.ensureOpaqueRgb(),
        gradientEndArgb = gradientEndArgb?.ensureOpaqueRgb()
    )

    companion object {
        fun fromGlobal(
            glass: StarGlassConfig,
            colors: StarColorPalette,
            overrideEnabled: Boolean = false
        ): StarComponentStyleOverride = StarComponentStyleOverride(
            overrideEnabled = overrideEnabled,
            backgroundOpacity = glass.backgroundOpacity,
            blurIntensity = glass.blurIntensity,
            borderOpacity = glass.borderOpacity,
            cornerRadiusDp = glass.cornerRadiusDp,
            shadowIntensity = glass.shadowIntensity,
            accentColorArgb = colors.primaryAccentArgb,
            gradientEnabled = glass.gradientEnabled,
            gradientStartArgb = colors.gradientStartArgb,
            gradientEndArgb = colors.gradientEndArgb,
            glassEffectEnabled = glass.glassEffectEnabled
        )
    }
}

/**
 * Versioned complete theme configuration for STAR UI Studio.
 */
data class StarThemeConfig(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val themeId: String = "preset_star_liquid_glass",
    val themeName: String = "STAR Liquid Glass",
    val presetId: StarPresetId = StarPresetId.STAR_LIQUID_GLASS,
    val effectQuality: StarEffectQuality = StarEffectQuality.HIGH,
    val background: StarBackgroundConfig = StarBackgroundConfig(),
    val glass: StarGlassConfig = StarGlassConfig(),
    val colors: StarColorPalette = StarColorPalette(),
    val componentOverrides: Map<StarComponentTarget, StarComponentStyleOverride> = emptyMap()
) {
    fun sanitized(): StarThemeConfig {
        val cleanGlass = glass.sanitized()
        val cleanColors = colors.sanitized()
        val cleanBg = background.sanitized()
        val cleanName = sanitizeThemeName(themeName)
        val cleanId = sanitizeThemeId(themeId)
        val cleanOverrides = componentOverrides
            .filterValues { it.overrideEnabled }
            .mapValues { (_, ov) -> ov.sanitized() }
        return copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            themeId = cleanId,
            themeName = cleanName,
            background = cleanBg,
            glass = cleanGlass,
            colors = cleanColors,
            componentOverrides = cleanOverrides
        )
    }

    fun overrideFor(target: StarComponentTarget): StarComponentStyleOverride {
        return componentOverrides[target]?.takeIf { it.overrideEnabled }
            ?: StarComponentStyleOverride.fromGlobal(glass, colors, overrideEnabled = false)
    }

    fun withComponentOverride(
        target: StarComponentTarget,
        transform: (StarComponentStyleOverride) -> StarComponentStyleOverride
    ): StarThemeConfig {
        val base = overrideFor(target)
        val updated = transform(base).copy(overrideEnabled = true).sanitized()
        val nextMap = componentOverrides.toMutableMap().apply {
            put(target, updated)
        }
        return copy(
            presetId = StarPresetId.CUSTOM,
            componentOverrides = nextMap
        )
    }

    fun clearComponentOverride(target: StarComponentTarget): StarThemeConfig {
        if (!componentOverrides.containsKey(target)) return this
        val nextMap = componentOverrides.toMutableMap().apply { remove(target) }
        return copy(componentOverrides = nextMap)
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MAX_THEME_NAME_LENGTH = 48
        const val MAX_THEME_ID_LENGTH = 64

        fun sanitizeThemeName(raw: String): String {
            val cleaned = buildString {
                for (ch in raw.trim()) {
                    if (!ch.isISOControl()) append(ch)
                }
            }.take(MAX_THEME_NAME_LENGTH).trim()
            return cleaned.ifEmpty { "Custom Glass Theme" }
        }

        fun sanitizeThemeId(raw: String): String {
            val cleaned = buildString {
                for (ch in raw.trim()) {
                    if (ch.isLetterOrDigit() || ch == '_' || ch == '-') append(ch)
                }
            }.take(MAX_THEME_ID_LENGTH)
            return cleaned.ifEmpty { "custom_${System.currentTimeMillis()}" }
        }
    }
}

/**
 * Saved theme entry displayed in the Theme Library.
 */
data class StarSavedThemeEntry(
    val id: String,
    val name: String,
    val isBuiltIn: Boolean,
    val updatedAtEpochMs: Long,
    val config: StarThemeConfig
)

/**
 * Resolved per-component style ready for zero-allocation rendering.
 */
data class StarResolvedComponentStyle(
    val target: StarComponentTarget,
    val backgroundOpacity: Float,
    val blurIntensity: Float,
    val borderOpacity: Float,
    val cornerRadiusDp: Float,
    val shadowIntensity: Float,
    val accentColor: Color,
    val gradientEnabled: Boolean,
    val gradientStart: Color,
    val gradientEnd: Color,
    val glassEffectEnabled: Boolean
)

/**
 * Pre-resolved immutable theme tree provided via [com.star.launcher.ui.theme.liquid.LocalStarTheme].
 */
data class StarResolvedTheme(
    val config: StarThemeConfig,
    val primaryAccent: Color,
    val secondaryAccent: Color,
    val backgroundBase: Color,
    val surfaceBase: Color,
    val surfaceElevated: Color,
    val surfaceVariant: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val borderColor: Color,
    val highlightColor: Color,
    val gradientStart: Color,
    val gradientEnd: Color,
    val isDarkSurface: Boolean,
    val componentStyles: Map<StarComponentTarget, StarResolvedComponentStyle>
) {
    fun styleFor(target: StarComponentTarget): StarResolvedComponentStyle {
        return componentStyles[target] ?: StarResolvedComponentStyle(
            target = target,
            backgroundOpacity = config.glass.backgroundOpacity,
            blurIntensity = config.glass.blurIntensity,
            borderOpacity = config.glass.borderOpacity,
            cornerRadiusDp = config.glass.cornerRadiusDp,
            shadowIntensity = config.glass.shadowIntensity,
            accentColor = primaryAccent,
            gradientEnabled = config.glass.gradientEnabled,
            gradientStart = gradientStart,
            gradientEnd = gradientEnd,
            glassEffectEnabled = config.glass.glassEffectEnabled
        )
    }

    companion object {
        fun resolve(rawConfig: StarThemeConfig): StarResolvedTheme {
            val config = rawConfig.sanitized()
            val palette = config.colors
            val primary = Color(palette.primaryAccentArgb.toInt())
            val secondary = Color(palette.secondaryAccentArgb.toInt())
            val bgBase = Color(palette.backgroundTintArgb.toInt())
            val textPrimary = Color(palette.textColorArgb.toInt())
            val border = Color(palette.borderColorArgb.toInt())
            val highlight = Color(palette.highlightColorArgb.toInt())
            val gradStart = Color(palette.gradientStartArgb.toInt())
            val gradEnd = Color(palette.gradientEndArgb.toInt())

            val isDark = bgBase.luminance() < 0.45f
            val surfaceBase = blendColors(bgBase, if (isDark) Color(0xFF182235) else Color.White, 0.28f)
            val surfaceElevated = blendColors(bgBase, if (isDark) Color(0xFF22304A) else Color(0xFFF0F4FA), 0.36f)
            val surfaceVariant = blendColors(surfaceElevated, primary, 0.14f)
            val textSecondary = blendColors(textPrimary, bgBase, 0.38f)

            val resolvedMap = StarComponentTarget.values().associateWith { target ->
                val ov = config.componentOverrides[target]
                if (ov != null && ov.overrideEnabled) {
                    StarResolvedComponentStyle(
                        target = target,
                        backgroundOpacity = ov.backgroundOpacity,
                        blurIntensity = ov.blurIntensity,
                        borderOpacity = ov.borderOpacity,
                        cornerRadiusDp = ov.cornerRadiusDp,
                        shadowIntensity = ov.shadowIntensity,
                        accentColor = ov.accentColorArgb?.let { Color(it.toInt()) } ?: primary,
                        gradientEnabled = ov.gradientEnabled,
                        gradientStart = ov.gradientStartArgb?.let { Color(it.toInt()) } ?: gradStart,
                        gradientEnd = ov.gradientEndArgb?.let { Color(it.toInt()) } ?: gradEnd,
                        glassEffectEnabled = ov.glassEffectEnabled
                    )
                } else {
                    val targetRadius = when (target) {
                        StarComponentTarget.BUTTONS -> (config.glass.cornerRadiusDp * 0.75f).coerceIn(6f, 24f)
                        StarComponentTarget.MAIN_NAVIGATION -> (config.glass.cornerRadiusDp * 0.9f).coerceIn(0f, 24f)
                        StarComponentTarget.DIALOGS -> (config.glass.cornerRadiusDp * 1.15f).coerceIn(10f, 32f)
                        else -> config.glass.cornerRadiusDp
                    }
                    val targetOpacity = when (target) {
                        StarComponentTarget.DIALOGS -> (config.glass.backgroundOpacity + 0.14f).coerceAtMost(0.95f)
                        StarComponentTarget.MAIN_NAVIGATION -> (config.glass.backgroundOpacity + 0.10f).coerceAtMost(0.96f)
                        else -> config.glass.backgroundOpacity
                    }
                    StarResolvedComponentStyle(
                        target = target,
                        backgroundOpacity = targetOpacity,
                        blurIntensity = config.glass.blurIntensity,
                        borderOpacity = config.glass.borderOpacity,
                        cornerRadiusDp = targetRadius,
                        shadowIntensity = config.glass.shadowIntensity,
                        accentColor = primary,
                        gradientEnabled = config.glass.gradientEnabled,
                        gradientStart = gradStart,
                        gradientEnd = gradEnd,
                        glassEffectEnabled = config.glass.glassEffectEnabled
                    )
                }
            }

            return StarResolvedTheme(
                config = config,
                primaryAccent = primary,
                secondaryAccent = secondary,
                backgroundBase = bgBase,
                surfaceBase = surfaceBase,
                surfaceElevated = surfaceElevated,
                surfaceVariant = surfaceVariant,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                borderColor = border,
                highlightColor = highlight,
                gradientStart = gradStart,
                gradientEnd = gradEnd,
                isDarkSurface = isDark,
                componentStyles = resolvedMap
            )
        }
    }
}

private fun Float.coerceFinite(min: Float, max: Float, fallback: Float): Float {
    return if (isFinite()) coerceIn(min, max) else fallback
}

private fun Long.ensureOpaqueRgb(): Long {
    val rgb = this and 0x00FFFFFFL
    return 0xFF000000L or rgb
}

fun Color.toOpaqueArgbLong(): Long = (this.toArgb().toLong() and 0x00FFFFFFL) or 0xFF000000L

fun Color.toArgbLong(): Long = this.toArgb().toLong() and 0xFFFFFFFFL

fun blendColors(first: Color, second: Color, ratio: Float): Color {
    val t = ratio.coerceIn(0f, 1f)
    return Color(
        red = first.red + (second.red - first.red) * t,
        green = first.green + (second.green - first.green) * t,
        blue = first.blue + (second.blue - first.blue) * t,
        alpha = first.alpha + (second.alpha - first.alpha) * t
    )
}
