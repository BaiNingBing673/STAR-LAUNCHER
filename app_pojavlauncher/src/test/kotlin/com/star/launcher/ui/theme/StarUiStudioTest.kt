package com.star.launcher.ui.theme

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.ui.dashboard.DashboardTab
import com.star.launcher.ui.navigation.AetherRoute
import com.star.launcher.ui.screens.SettingsSubTab
import com.star.launcher.ui.theme.studio.StarBackgroundConfig
import com.star.launcher.ui.theme.studio.StarBackgroundEngine
import com.star.launcher.ui.theme.studio.StarColorPalette
import com.star.launcher.ui.theme.studio.StarComponentTarget
import com.star.launcher.ui.theme.studio.StarEffectQuality
import com.star.launcher.ui.theme.studio.StarGlassConfig
import com.star.launcher.ui.theme.studio.StarPresetId
import com.star.launcher.ui.theme.studio.StarResolvedTheme
import com.star.launcher.ui.theme.studio.StarThemeConfig
import com.star.launcher.ui.theme.studio.StarThemeImportResult
import com.star.launcher.ui.theme.studio.StarThemeJsonSerializer
import com.star.launcher.ui.theme.studio.StarThemeManager
import com.star.launcher.ui.theme.studio.StarThemePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StarUiStudioTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "star_ui_studio").deleteRecursively()
        StarThemeManager.ensureInitialized(context)
        StarThemeManager.resetToDefault(context)
    }

    @Test
    fun allSixPresetsAndPalettesAreDefinedAndDistinct() {
        val configs = StarPresetId.values().map { preset ->
            StarThemePresets.presetConfig(preset, StarEffectQuality.HIGH)
        }
        assertEquals(6, configs.size)
        assertEquals(5, StarThemePresets.builtInEntries().size)
        assertTrue(StarThemePresets.namedPalettes.size >= 6)

        val star = StarThemePresets.presetConfig(StarPresetId.STAR_LIQUID_GLASS)
        val midnight = StarThemePresets.presetConfig(StarPresetId.MIDNIGHT_GLASS)
        val aurora = StarThemePresets.presetConfig(StarPresetId.AURORA_GLASS)
        val water = StarThemePresets.presetConfig(StarPresetId.WATER_CRYSTAL)
        val minimal = StarThemePresets.presetConfig(StarPresetId.MINIMAL_DARK)

        assertTrue(water.glass.waterDropletsEnabled)
        assertFalse(minimal.glass.glassEffectEnabled)
        assertNotEquals(star.colors.primaryAccentArgb, aurora.colors.primaryAccentArgb)
        assertNotEquals(midnight.colors.backgroundTintArgb, aurora.colors.gradientStartArgb)
    }

    @Test
    fun sanitizationClampsOutOfRangeValuesAndControlCharacters() {
        val dirty = StarThemeConfig(
            themeId = " bad/id..\n\t!! ",
            themeName = "  <My\u0007 Theme>\n ",
            background = StarBackgroundConfig(
                enabled = true,
                imagePath = "/tmp/bg.png",
                offsetX = 99f,
                offsetY = -99f,
                zoom = 50f,
                brightness = 9f,
                saturation = -3f,
                contrast = 12f,
                darkOverlayAlpha = 2f,
                gradientOverlayAlpha = -1f,
                blurRadiusDp = 999f
            ),
            glass = StarGlassConfig(
                blurIntensity = -10f,
                backgroundOpacity = 5f,
                borderOpacity = -2f,
                cornerRadiusDp = 200f,
                shadowIntensity = 4f,
                refractionIntensity = 3f,
                highlightIntensity = -1f,
                edgeReflectionIntensity = 5f,
                waterDropletIntensity = 9f
            )
        ).sanitized()

        assertFalse(dirty.themeName.contains("\u0007"))
        assertFalse(dirty.themeName.contains("\n"))
        assertTrue(dirty.themeId.all { it.isLetterOrDigit() || it == '_' || it == '-' })
        assertTrue(dirty.background.offsetX in -1f..1f)
        assertTrue(dirty.background.offsetY in -1f..1f)
        assertTrue(dirty.background.zoom in 1f..3.5f)
        assertTrue(dirty.background.brightness in -0.5f..0.5f)
        assertTrue(dirty.background.saturation in 0f..2f)
        assertTrue(dirty.background.contrast in 0.5f..1.8f)
        assertTrue(dirty.background.darkOverlayAlpha in 0f..0.88f)
        assertTrue(dirty.background.gradientOverlayAlpha in 0f..1f)
        assertTrue(dirty.background.blurRadiusDp in 0f..36f)
        assertTrue(dirty.glass.blurIntensity in 0f..36f)
        assertTrue(dirty.glass.backgroundOpacity in 0.06f..0.98f)
        assertTrue(dirty.glass.borderOpacity in 0f..1f)
        assertTrue(dirty.glass.cornerRadiusDp in 0f..32f)
    }

    @Test
    fun resolvedThemeAppliesComponentOverridesAndClearsCleanly() {
        val base = StarThemePresets.presetConfig(StarPresetId.STAR_LIQUID_GLASS)
        val withOverride = base.withComponentOverride(StarComponentTarget.BUTTONS) { ov ->
            ov.copy(
                backgroundOpacity = 0.25f,
                blurIntensity = 26f,
                borderOpacity = 0.72f,
                cornerRadiusDp = 22f,
                accentColorArgb = 0xFF00FFA3L,
                glassEffectEnabled = true
            )
        }

        val resolved = StarResolvedTheme.resolve(withOverride)
        val buttonStyle = resolved.styleFor(StarComponentTarget.BUTTONS)
        val cardStyle = resolved.styleFor(StarComponentTarget.CARDS)

        assertEquals(0.25f, buttonStyle.backgroundOpacity, 0.001f)
        assertEquals(26f, buttonStyle.blurIntensity, 0.001f)
        assertEquals(22f, buttonStyle.cornerRadiusDp, 0.001f)
        assertEquals(Color(0xFF00FFA3.toInt()), buttonStyle.accentColor)

        // Cards still inherit global defaults
        assertEquals(base.glass.cornerRadiusDp, cardStyle.cornerRadiusDp, 0.001f)
        assertEquals(10, StarComponentTarget.values().size)

        val cleared = withOverride.clearComponentOverride(StarComponentTarget.BUTTONS)
        val clearedResolved = StarResolvedTheme.resolve(cleared)
        val expectedInheritedButtonRadius = (base.glass.cornerRadiusDp * 0.75f).coerceIn(6f, 24f)
        assertEquals(expectedInheritedButtonRadius, clearedResolved.styleFor(StarComponentTarget.BUTTONS).cornerRadiusDp, 0.001f)
    }

    @Test
    fun serializerRoundTripAndSecurityValidation() {
        val base = StarThemePresets.presetConfig(StarPresetId.AURORA_GLASS)
            .copy(
                themeName = "Cyber Crystal",
                effectQuality = StarEffectQuality.ULTRA,
                colors = StarColorPalette(
                    primaryAccentArgb = 0xFF38E8C6L,
                    secondaryAccentArgb = 0xFFA78BFAL,
                    backgroundTintArgb = 0xFF0A1019L,
                    textColorArgb = 0xFFEDF7F6L,
                    borderColorArgb = 0xFF5372A6L,
                    gradientStartArgb = 0xFF173566L,
                    gradientEndArgb = 0xFF1C163BL,
                    highlightColorArgb = 0xFFD9F0FFL
                ),
                background = StarBackgroundConfig(
                    enabled = true,
                    imagePath = "/secret/host/path/photo.png",
                    zoom = 1.4f,
                    brightness = 0.1f,
                    blurRadiusDp = 18f
                )
            )
            .withComponentOverride(StarComponentTarget.DIALOGS) { ov ->
                ov.copy(cornerRadiusDp = 24f, borderOpacity = 0.65f)
            }

        // Export strips local image path for safety
        val exported = StarThemeJsonSerializer.serialize(base, includeLocalImagePath = false)
        assertFalse(exported.contains("/secret/host/path/photo.png"))

        val importedResult = StarThemeJsonSerializer.deserializeAndValidate(exported, allowLocalImagePath = false)
        assertTrue(importedResult is StarThemeImportResult.Success)
        val imported = (importedResult as StarThemeImportResult.Success).config
        assertEquals("Cyber Crystal", imported.themeName)
        assertEquals(StarEffectQuality.ULTRA, imported.effectQuality)
        assertEquals(0xFF38E8C6L, imported.colors.primaryAccentArgb)
        assertEquals(1.4f, imported.background.zoom, 0.01f)
        assertFalse(imported.background.enabled)
        assertNull(imported.background.imagePath)
        assertEquals(24f, imported.overrideFor(StarComponentTarget.DIALOGS).cornerRadiusDp, 0.01f)

        // Malicious or external path injection in raw JSON must be stripped + warned
        val withPathJson = exported.replace("\"enabled\": false", "\"enabled\": true, \"imagePath\": \"../../etc/passwd\"")
        val stripped = StarThemeJsonSerializer.deserializeAndValidate(withPathJson, allowLocalImagePath = false)
        assertTrue(stripped is StarThemeImportResult.Success)
        val strippedSuccess = stripped as StarThemeImportResult.Success
        assertNull(strippedSuccess.config.background.imagePath)
        assertFalse(strippedSuccess.config.background.enabled)
        assertTrue(strippedSuccess.warnings.isNotEmpty())

        // Invalid payloads rejected cleanly
        assertTrue(StarThemeJsonSerializer.deserializeAndValidate("") is StarThemeImportResult.Error)
        assertTrue(StarThemeJsonSerializer.deserializeAndValidate("{not valid json") is StarThemeImportResult.Error)
        assertTrue(StarThemeJsonSerializer.deserializeAndValidate("{\"format\":\"evil.format\",\"schemaVersion\":1,\"colors\":{}}") is StarThemeImportResult.Error)
        assertTrue(StarThemeJsonSerializer.deserializeAndValidate("{\"format\":\"star.ui.theme\",\"schemaVersion\":99,\"colors\":{}}") is StarThemeImportResult.Error)
        assertTrue(StarThemeJsonSerializer.deserializeAndValidate("{\"format\":\"star.ui.theme\",\"schemaVersion\":1}") is StarThemeImportResult.Error)

        val oversized = "{ \"format\": \"star.ui.theme\", \"schemaVersion\": 1, \"colors\": {}, \"pad\": \"${"A".repeat(StarThemeJsonSerializer.MAX_IMPORT_CHARS)}\" }"
        assertTrue(StarThemeJsonSerializer.deserializeAndValidate(oversized) is StarThemeImportResult.Error)

        assertEquals("#FF38E8C6", StarThemeJsonSerializer.formatColorHex(0xFF38E8C6L))
        assertEquals(0xFF38E8C6L, StarThemeJsonSerializer.parseColorHexOrNull("#38E8C6"))
        assertEquals(0x8038E8C6L, StarThemeJsonSerializer.parseColorHexOrNull("8038E8C6"))
        assertNull(StarThemeJsonSerializer.parseColorHexOrNull("not-a-color"))
    }

    @Test
    fun themeManagerCrudPersistenceAndExportImport() {
        val customConfig = StarThemePresets.presetConfig(StarPresetId.WATER_CRYSTAL)
            .copy(
                colors = StarThemePresets.namedPalettes[2].palette,
                glass = StarGlassConfig(blurIntensity = 20f, backgroundOpacity = 0.50f)
            )
        val applied = StarThemeManager.applyTheme(context, customConfig)
        assertEquals(0.50f, StarThemeManager.activeThemeFlow.value.glass.backgroundOpacity, 0.001f)
        assertEquals(Color(applied.colors.primaryAccentArgb.toInt()), AetherColors.AccentBlue)

        val activeFile = File(context.filesDir, "star_ui_studio/active_theme.json")
        assertTrue(activeFile.isFile)

        // Save, rename, duplicate, delete workflow
        val saved = StarThemeManager.saveThemeAsNew(context, "Oceanic Custom", applied, activate = false)
        assertFalse(saved.isBuiltIn)
        assertTrue(StarThemeManager.savedThemesFlow.value.any { it.id == saved.id })

        val renamed = StarThemeManager.renameTheme(context, saved.id, "Oceanic Renamed")
        assertNotNull(renamed)
        assertEquals("Oceanic Renamed", renamed!!.name)

        val copy = StarThemeManager.duplicateTheme(context, saved.id)
        assertNotNull(copy)
        assertNotEquals(saved.id, copy!!.id)

        // Built-in presets cannot be deleted
        assertFalse(StarThemeManager.deleteTheme(context, "preset_star_liquid_glass"))
        // Custom themes can be deleted
        assertTrue(StarThemeManager.deleteTheme(context, saved.id))
        assertTrue(StarThemeManager.deleteTheme(context, copy.id))

        // Export & import back
        val exported = StarThemeManager.exportThemeJson(applied.copy(themeName = "Shared Theme"))
        val importRes = StarThemeManager.importThemeJson(context, exported, saveToLibrary = true, applyImmediately = true)
        assertTrue(importRes is StarThemeImportResult.Success)
        assertEquals("Shared Theme", StarThemeManager.activeThemeFlow.value.themeName)

        // Reset restores default built-in preset
        val reset = StarThemeManager.resetToDefault(context)
        assertEquals(StarPresetId.STAR_LIQUID_GLASS, reset.presetId)
    }

    @Test
    fun backgroundEngineBlurBucketAndStackBlurWorkCorrectly() {
        assertEquals(0, StarBackgroundEngine.quantizeBlurBucket(0.5f))
        assertEquals(6, StarBackgroundEngine.quantizeBlurBucket(5f))
        assertEquals(12, StarBackgroundEngine.quantizeBlurBucket(12f))
        assertEquals(20, StarBackgroundEngine.quantizeBlurBucket(22f))
        assertEquals(30, StarBackgroundEngine.quantizeBlurBucket(32f))

        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bmp.setPixel(8, 8, 0xFFFFFFFF.toInt())
        val blurred = StarBackgroundEngine.stackBlurInPlace(bmp, 4)
        assertEquals(16, blurred.width)
        assertEquals(16, blurred.height)

        StarBackgroundEngine.trimMemory(aggressive = false)
        StarBackgroundEngine.trimMemory(aggressive = true)
    }

    @Test
    fun starUiStudioRouteAndSettingsSubTabAreWiredToSettings() {
        assertEquals(DashboardTab.SETTINGS, AetherRoute.StarUiStudio.ownerTab)
        assertEquals("STAR UI Studio", SettingsSubTab.STAR_UI_STUDIO.title)
    }
}
