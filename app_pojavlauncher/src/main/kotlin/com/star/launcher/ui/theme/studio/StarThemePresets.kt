package com.star.launcher.ui.theme.studio

/**
 * Curated predefined color palettes for quick selection in STAR UI Studio.
 */
data class StarNamedPalette(
    val id: String,
    val name: String,
    val palette: StarColorPalette
)

object StarThemePresets {

    val namedPalettes: List<StarNamedPalette> = listOf(
        StarNamedPalette(
            id = "star_electric",
            name = "STAR Electric",
            palette = StarColorPalette(
                primaryAccentArgb = 0xFF5FA8FF,
                secondaryAccentArgb = 0xFF5EE2FF,
                backgroundTintArgb = 0xFF0A0F1A,
                textColorArgb = 0xFFECEFF6,
                borderColorArgb = 0xFF5372A6,
                gradientStartArgb = 0xFF173566,
                gradientEndArgb = 0xFF0B1930,
                highlightColorArgb = 0xFFD9F0FF
            )
        ),
        StarNamedPalette(
            id = "glacial_cyan",
            name = "Glacial Crystal",
            palette = StarColorPalette(
                primaryAccentArgb = 0xFF38BDF8,
                secondaryAccentArgb = 0xFF7DF9FF,
                backgroundTintArgb = 0xFF07131E,
                textColorArgb = 0xFFF0F8FF,
                borderColorArgb = 0xFF5FAFD6,
                gradientStartArgb = 0xFF103F5C,
                gradientEndArgb = 0xFF091B2C,
                highlightColorArgb = 0xFFE3FBFF
            )
        ),
        StarNamedPalette(
            id = "boreal_aurora",
            name = "Boreal Aurora",
            palette = StarColorPalette(
                primaryAccentArgb = 0xFF38E8C6,
                secondaryAccentArgb = 0xFFA78BFA,
                backgroundTintArgb = 0xFF091219,
                textColorArgb = 0xFFEDF7F6,
                borderColorArgb = 0xFF4F8E9B,
                gradientStartArgb = 0xFF123F47,
                gradientEndArgb = 0xFF1C163B,
                highlightColorArgb = 0xFFD5FFF6
            )
        ),
        StarNamedPalette(
            id = "midnight_cobalt",
            name = "Midnight Cobalt",
            palette = StarColorPalette(
                primaryAccentArgb = 0xFF6B8CFF,
                secondaryAccentArgb = 0xFFB8D0FF,
                backgroundTintArgb = 0xFF070912,
                textColorArgb = 0xFFE8ECF7,
                borderColorArgb = 0xFF3D4E7A,
                gradientStartArgb = 0xFF121C3A,
                gradientEndArgb = 0xFF090C18,
                highlightColorArgb = 0xFFCFDFFF
            )
        ),
        StarNamedPalette(
            id = "obsidian_silver",
            name = "Obsidian Silver",
            palette = StarColorPalette(
                primaryAccentArgb = 0xFF94A3B8,
                secondaryAccentArgb = 0xFFE2E8F0,
                backgroundTintArgb = 0xFF0B0D12,
                textColorArgb = 0xFFF1F5F9,
                borderColorArgb = 0xFF475569,
                gradientStartArgb = 0xFF1B202C,
                gradientEndArgb = 0xFF10131A,
                highlightColorArgb = 0xFFF8FAFC
            )
        ),
        StarNamedPalette(
            id = "solar_amber",
            name = "Solar Amber",
            palette = StarColorPalette(
                primaryAccentArgb = 0xFFFBBF24,
                secondaryAccentArgb = 0xFFFDE68A,
                backgroundTintArgb = 0xFF14100B,
                textColorArgb = 0xFFFAF6EE,
                borderColorArgb = 0xFF8C6B32,
                gradientStartArgb = 0xFF3B2810,
                gradientEndArgb = 0xFF19120B,
                highlightColorArgb = 0xFFFFF3CC
            )
        ),
        StarNamedPalette(
            id = "crimson_nebula",
            name = "Crimson Nebula",
            palette = StarColorPalette(
                primaryAccentArgb = 0xFFFB7185,
                secondaryAccentArgb = 0xFFFDA4AF,
                backgroundTintArgb = 0xFF140A10,
                textColorArgb = 0xFFFAF0F3,
                borderColorArgb = 0xFF8A3E52,
                gradientStartArgb = 0xFF421526,
                gradientEndArgb = 0xFF180C14,
                highlightColorArgb = 0xFFFFE0E6
            )
        ),
        StarNamedPalette(
            id = "deep_amethyst",
            name = "Deep Amethyst",
            palette = StarColorPalette(
                primaryAccentArgb = 0xFFC084FC,
                secondaryAccentArgb = 0xFF67E8F9,
                backgroundTintArgb = 0xFF0E091A,
                textColorArgb = 0xFFF3EEFF,
                borderColorArgb = 0xFF6D4C9E,
                gradientStartArgb = 0xFF2B174A,
                gradientEndArgb = 0xFF120C26,
                highlightColorArgb = 0xFFF0DFFF
            )
        )
    )

    fun presetConfig(
        presetId: StarPresetId,
        effectQuality: StarEffectQuality = StarEffectQuality.HIGH
    ): StarThemeConfig = when (presetId) {
        StarPresetId.STAR_LIQUID_GLASS -> StarThemeConfig(
            themeId = "preset_star_liquid_glass",
            themeName = StarPresetId.STAR_LIQUID_GLASS.displayName,
            presetId = StarPresetId.STAR_LIQUID_GLASS,
            effectQuality = effectQuality,
            background = StarBackgroundConfig(
                enabled = false,
                darkOverlayAlpha = 0.32f,
                gradientOverlayEnabled = true,
                gradientOverlayTopArgb = 0xCC0C1A33,
                gradientOverlayBottomArgb = 0xE6070B14,
                gradientOverlayAlpha = 0.62f,
                gradientAngleDeg = 135f,
                blurRadiusDp = 0f
            ),
            glass = StarGlassConfig(
                blurIntensity = 18f,
                backgroundOpacity = 0.62f,
                borderOpacity = 0.36f,
                cornerRadiusDp = 16f,
                shadowIntensity = 0.34f,
                refractionIntensity = 0.40f,
                highlightIntensity = 0.30f,
                edgeReflectionIntensity = 0.36f,
                waterDropletsEnabled = false,
                waterDropletsAnimated = true,
                waterDropletIntensity = 0.38f,
                glassEffectEnabled = true,
                gradientEnabled = true
            ),
            colors = namedPalettes[0].palette,
            componentOverrides = emptyMap()
        )

        StarPresetId.MIDNIGHT_GLASS -> StarThemeConfig(
            themeId = "preset_midnight_glass",
            themeName = StarPresetId.MIDNIGHT_GLASS.displayName,
            presetId = StarPresetId.MIDNIGHT_GLASS,
            effectQuality = effectQuality,
            background = StarBackgroundConfig(
                enabled = false,
                darkOverlayAlpha = 0.44f,
                gradientOverlayEnabled = true,
                gradientOverlayTopArgb = 0xCC0A1024,
                gradientOverlayBottomArgb = 0xF005070E,
                gradientOverlayAlpha = 0.72f,
                gradientAngleDeg = 155f,
                blurRadiusDp = 6f
            ),
            glass = StarGlassConfig(
                blurIntensity = 20f,
                backgroundOpacity = 0.72f,
                borderOpacity = 0.28f,
                cornerRadiusDp = 15f,
                shadowIntensity = 0.42f,
                refractionIntensity = 0.28f,
                highlightIntensity = 0.22f,
                edgeReflectionIntensity = 0.30f,
                waterDropletsEnabled = false,
                waterDropletsAnimated = false,
                waterDropletIntensity = 0.25f,
                glassEffectEnabled = true,
                gradientEnabled = true
            ),
            colors = namedPalettes[3].palette,
            componentOverrides = mapOf(
                StarComponentTarget.MAIN_NAVIGATION to StarComponentStyleOverride(
                    overrideEnabled = true,
                    backgroundOpacity = 0.84f,
                    blurIntensity = 24f,
                    borderOpacity = 0.32f,
                    cornerRadiusDp = 14f,
                    shadowIntensity = 0.40f,
                    accentColorArgb = 0xFF6B8CFF,
                    gradientEnabled = true,
                    gradientStartArgb = 0xFF141E3E,
                    gradientEndArgb = 0xFF080B16,
                    glassEffectEnabled = true
                )
            )
        )

        StarPresetId.AURORA_GLASS -> StarThemeConfig(
            themeId = "preset_aurora_glass",
            themeName = StarPresetId.AURORA_GLASS.displayName,
            presetId = StarPresetId.AURORA_GLASS,
            effectQuality = effectQuality,
            background = StarBackgroundConfig(
                enabled = false,
                darkOverlayAlpha = 0.28f,
                gradientOverlayEnabled = true,
                gradientOverlayTopArgb = 0xCC0F3B42,
                gradientOverlayBottomArgb = 0xE6181236,
                gradientOverlayAlpha = 0.68f,
                gradientAngleDeg = 120f,
                blurRadiusDp = 4f
            ),
            glass = StarGlassConfig(
                blurIntensity = 22f,
                backgroundOpacity = 0.56f,
                borderOpacity = 0.40f,
                cornerRadiusDp = 18f,
                shadowIntensity = 0.36f,
                refractionIntensity = 0.48f,
                highlightIntensity = 0.36f,
                edgeReflectionIntensity = 0.42f,
                waterDropletsEnabled = false,
                waterDropletsAnimated = true,
                waterDropletIntensity = 0.35f,
                glassEffectEnabled = true,
                gradientEnabled = true
            ),
            colors = namedPalettes[2].palette,
            componentOverrides = emptyMap()
        )

        StarPresetId.WATER_CRYSTAL -> StarThemeConfig(
            themeId = "preset_water_crystal",
            themeName = StarPresetId.WATER_CRYSTAL.displayName,
            presetId = StarPresetId.WATER_CRYSTAL,
            effectQuality = effectQuality,
            background = StarBackgroundConfig(
                enabled = false,
                darkOverlayAlpha = 0.24f,
                gradientOverlayEnabled = true,
                gradientOverlayTopArgb = 0xBF0E3854,
                gradientOverlayBottomArgb = 0xE0061422,
                gradientOverlayAlpha = 0.60f,
                gradientAngleDeg = 140f,
                blurRadiusDp = 8f
            ),
            glass = StarGlassConfig(
                blurIntensity = 24f,
                backgroundOpacity = 0.46f,
                borderOpacity = 0.48f,
                cornerRadiusDp = 20f,
                shadowIntensity = 0.32f,
                refractionIntensity = 0.58f,
                highlightIntensity = 0.44f,
                edgeReflectionIntensity = 0.50f,
                waterDropletsEnabled = true,
                waterDropletsAnimated = true,
                waterDropletIntensity = 0.52f,
                glassEffectEnabled = true,
                gradientEnabled = true
            ),
            colors = namedPalettes[1].palette,
            componentOverrides = emptyMap()
        )

        StarPresetId.MINIMAL_DARK -> StarThemeConfig(
            themeId = "preset_minimal_dark",
            themeName = StarPresetId.MINIMAL_DARK.displayName,
            presetId = StarPresetId.MINIMAL_DARK,
            effectQuality = effectQuality,
            background = StarBackgroundConfig(
                enabled = false,
                darkOverlayAlpha = 0.52f,
                gradientOverlayEnabled = false,
                gradientOverlayTopArgb = 0xFF0E1117,
                gradientOverlayBottomArgb = 0xFF090B10,
                gradientOverlayAlpha = 0.30f,
                gradientAngleDeg = 180f,
                blurRadiusDp = 0f
            ),
            glass = StarGlassConfig(
                blurIntensity = 4f,
                backgroundOpacity = 0.88f,
                borderOpacity = 0.22f,
                cornerRadiusDp = 12f,
                shadowIntensity = 0.16f,
                refractionIntensity = 0.08f,
                highlightIntensity = 0.10f,
                edgeReflectionIntensity = 0.12f,
                waterDropletsEnabled = false,
                waterDropletsAnimated = false,
                waterDropletIntensity = 0.15f,
                glassEffectEnabled = false,
                gradientEnabled = false
            ),
            colors = namedPalettes[4].palette,
            componentOverrides = emptyMap()
        )

        StarPresetId.CUSTOM -> presetConfig(StarPresetId.STAR_LIQUID_GLASS, effectQuality).copy(
            themeId = "custom_workspace",
            themeName = StarPresetId.CUSTOM.displayName,
            presetId = StarPresetId.CUSTOM
        )
    }

    fun builtInEntries(effectQuality: StarEffectQuality = StarEffectQuality.HIGH): List<StarSavedThemeEntry> {
        return listOf(
            StarPresetId.STAR_LIQUID_GLASS,
            StarPresetId.MIDNIGHT_GLASS,
            StarPresetId.AURORA_GLASS,
            StarPresetId.WATER_CRYSTAL,
            StarPresetId.MINIMAL_DARK
        ).map { preset ->
            val cfg = presetConfig(preset, effectQuality)
            StarSavedThemeEntry(
                id = cfg.themeId,
                name = cfg.themeName,
                isBuiltIn = true,
                updatedAtEpochMs = 0L,
                config = cfg
            )
        }
    }
}
