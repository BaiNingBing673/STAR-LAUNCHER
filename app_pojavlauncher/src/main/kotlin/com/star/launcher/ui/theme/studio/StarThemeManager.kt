package com.star.launcher.ui.theme.studio

import android.content.Context
import android.util.Log
import com.star.launcher.compat.UiPerformanceProfile
import com.star.launcher.compat.UiPerformanceProfileManager
import com.star.launcher.ui.theme.AetherColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Centralized theme engine and persistent library manager for STAR UI Studio.
 *
 * Uses atomic JSON file writes in app-private storage so themes persist cleanly across restarts
 * and never interfere with existing launcher settings or Room database schemas.
 */
object StarThemeManager {
    private const val TAG = "StarThemeManager"
    private const val STUDIO_DIR_NAME = "star_ui_studio"
    private const val ACTIVE_THEME_FILE = "active_theme.json"
    private const val SAVED_THEMES_FILE = "saved_themes.json"

    @Volatile
    private var initialized = false

    @Volatile
    private var defaultQualityForDevice: StarEffectQuality = StarEffectQuality.HIGH

    private val initialConfig = StarThemePresets.presetConfig(StarPresetId.STAR_LIQUID_GLASS, StarEffectQuality.HIGH)

    private val _activeThemeFlow = MutableStateFlow(initialConfig)
    val activeThemeFlow: StateFlow<StarThemeConfig> = _activeThemeFlow.asStateFlow()

    private val _resolvedThemeFlow = MutableStateFlow(StarResolvedTheme.resolve(initialConfig))
    val resolvedThemeFlow: StateFlow<StarResolvedTheme> = _resolvedThemeFlow.asStateFlow()

    private val _savedThemesFlow = MutableStateFlow(StarThemePresets.builtInEntries(StarEffectQuality.HIGH))
    val savedThemesFlow: StateFlow<List<StarSavedThemeEntry>> = _savedThemesFlow.asStateFlow()

    fun ensureInitialized(context: Context): StarThemeConfig {
        if (initialized) return _activeThemeFlow.value
        synchronized(this) {
            if (initialized) return _activeThemeFlow.value
            val appContext = context.applicationContext ?: context
            val perfSpec = UiPerformanceProfileManager.ensureInitialized(appContext)
            defaultQualityForDevice = defaultQualityForProfile(perfSpec.profile)

            val activeFile = studioFile(appContext, ACTIVE_THEME_FILE)
            val loadedActive = if (activeFile.isFile) {
                runCatching {
                    val raw = activeFile.readText(Charsets.UTF_8)
                    when (val res = StarThemeJsonSerializer.deserializeAndValidate(raw, allowLocalImagePath = true)) {
                        is StarThemeImportResult.Success -> {
                            // Keep presetId when loading our own persisted file
                            val parsedPreset = runCatching {
                                val json = org.json.JSONObject(raw)
                                val name = json.optString("presetId", "")
                                StarPresetId.values().firstOrNull { it.name == name } ?: res.config.presetId
                            }.getOrDefault(res.config.presetId)
                            validateBackgroundFileExists(res.config.copy(presetId = parsedPreset))
                        }
                        is StarThemeImportResult.Error -> {
                            Log.w(TAG, "Active theme failed validation: ${res.reason}")
                            StarThemePresets.presetConfig(StarPresetId.STAR_LIQUID_GLASS, defaultQualityForDevice)
                        }
                    }
                }.getOrElse {
                    Log.w(TAG, "Failed reading active theme, falling back to default", it)
                    StarThemePresets.presetConfig(StarPresetId.STAR_LIQUID_GLASS, defaultQualityForDevice)
                }
            } else {
                StarThemePresets.presetConfig(StarPresetId.STAR_LIQUID_GLASS, defaultQualityForDevice)
            }.sanitized()

            val customEntries = loadCustomSavedThemes(appContext)
            publishActiveTheme(loadedActive)
            publishSavedThemes(customEntries)
            initialized = true
            return loadedActive
        }
    }

    fun recommendedQualityForDevice(): StarEffectQuality = defaultQualityForDevice

    fun applyTheme(context: Context, config: StarThemeConfig): StarThemeConfig {
        val appContext = context.applicationContext ?: context
        ensureInitialized(appContext)
        val clean = validateBackgroundFileExists(config.sanitized())
        writeAtomic(
            studioFile(appContext, ACTIVE_THEME_FILE),
            StarThemeJsonSerializer.serialize(clean, includeLocalImagePath = true)
        )
        publishActiveTheme(clean)
        cleanupOrphanedWallpapers(appContext)
        return clean
    }

    fun resetToDefault(context: Context): StarThemeConfig {
        val appContext = context.applicationContext ?: context
        ensureInitialized(appContext)
        val defaultCfg = StarThemePresets.presetConfig(
            presetId = StarPresetId.STAR_LIQUID_GLASS,
            effectQuality = defaultQualityForDevice
        )
        return applyTheme(appContext, defaultCfg)
    }

    fun switchToTheme(context: Context, entryId: String): StarThemeConfig? {
        val appContext = context.applicationContext ?: context
        ensureInitialized(appContext)
        val target = _savedThemesFlow.value.firstOrNull { it.id == entryId } ?: return null
        val configToApply = if (target.isBuiltIn) {
            target.config.copy(effectQuality = _activeThemeFlow.value.effectQuality)
        } else {
            validateBackgroundFileExists(target.config)
        }
        return applyTheme(appContext, configToApply)
    }

    fun saveThemeAsNew(
        context: Context,
        name: String,
        config: StarThemeConfig,
        activate: Boolean = true
    ): StarSavedThemeEntry {
        val appContext = context.applicationContext ?: context
        ensureInitialized(appContext)
        val cleanName = StarThemeConfig.sanitizeThemeName(name)
        val newId = "custom_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val savedConfig = validateBackgroundFileExists(
            config.copy(
                themeId = newId,
                themeName = cleanName,
                presetId = StarPresetId.CUSTOM
            ).sanitized()
        )
        val entry = StarSavedThemeEntry(
            id = newId,
            name = cleanName,
            isBuiltIn = false,
            updatedAtEpochMs = System.currentTimeMillis(),
            config = savedConfig
        )
        val updatedCustom = (_savedThemesFlow.value.filter { !it.isBuiltIn } + entry)
        persistCustomSavedThemes(appContext, updatedCustom)
        publishSavedThemes(updatedCustom)
        if (activate) {
            applyTheme(appContext, savedConfig)
        }
        return entry
    }

    fun overwriteSavedTheme(
        context: Context,
        entryId: String,
        config: StarThemeConfig
    ): StarSavedThemeEntry? {
        val appContext = context.applicationContext ?: context
        ensureInitialized(appContext)
        val existing = _savedThemesFlow.value.firstOrNull { it.id == entryId && !it.isBuiltIn } ?: return null
        val updatedConfig = validateBackgroundFileExists(
            config.copy(
                themeId = existing.id,
                themeName = existing.name,
                presetId = StarPresetId.CUSTOM
            ).sanitized()
        )
        val updatedEntry = existing.copy(
            updatedAtEpochMs = System.currentTimeMillis(),
            config = updatedConfig
        )
        val updatedCustom = _savedThemesFlow.value
            .filter { !it.isBuiltIn }
            .map { if (it.id == entryId) updatedEntry else it }
        persistCustomSavedThemes(appContext, updatedCustom)
        publishSavedThemes(updatedCustom)
        if (_activeThemeFlow.value.themeId == entryId) {
            applyTheme(appContext, updatedConfig)
        }
        cleanupOrphanedWallpapers(appContext)
        return updatedEntry
    }

    fun renameTheme(context: Context, entryId: String, newName: String): StarSavedThemeEntry? {
        val appContext = context.applicationContext ?: context
        ensureInitialized(appContext)
        val cleanName = StarThemeConfig.sanitizeThemeName(newName)
        val customList = _savedThemesFlow.value.filter { !it.isBuiltIn }
        var renamed: StarSavedThemeEntry? = null
        val updatedCustom = customList.map { entry ->
            if (entry.id == entryId) {
                entry.copy(
                    name = cleanName,
                    updatedAtEpochMs = System.currentTimeMillis(),
                    config = entry.config.copy(themeName = cleanName)
                ).also { renamed = it }
            } else entry
        }
        val result = renamed ?: return null
        persistCustomSavedThemes(appContext, updatedCustom)
        publishSavedThemes(updatedCustom)
        if (_activeThemeFlow.value.themeId == entryId) {
            applyTheme(appContext, _activeThemeFlow.value.copy(themeName = cleanName))
        }
        return result
    }

    fun duplicateTheme(context: Context, sourceId: String): StarSavedThemeEntry? {
        val appContext = context.applicationContext ?: context
        ensureInitialized(appContext)
        val source = _savedThemesFlow.value.firstOrNull { it.id == sourceId } ?: return null
        val copyName = StarThemeConfig.sanitizeThemeName("${source.name} Copy")
        return saveThemeAsNew(
            context = appContext,
            name = copyName,
            config = source.config,
            activate = false
        )
    }

    fun deleteTheme(context: Context, entryId: String): Boolean {
        val appContext = context.applicationContext ?: context
        ensureInitialized(appContext)
        val target = _savedThemesFlow.value.firstOrNull { it.id == entryId } ?: return false
        if (target.isBuiltIn) return false
        val updatedCustom = _savedThemesFlow.value.filter { !it.isBuiltIn && it.id != entryId }
        persistCustomSavedThemes(appContext, updatedCustom)
        publishSavedThemes(updatedCustom)
        cleanupOrphanedWallpapers(appContext)
        return true
    }

    fun exportThemeJson(config: StarThemeConfig): String {
        return StarThemeJsonSerializer.serialize(config, includeLocalImagePath = false)
    }

    fun importThemeJson(
        context: Context,
        rawJson: String,
        saveToLibrary: Boolean = true,
        applyImmediately: Boolean = false
    ): StarThemeImportResult {
        val appContext = context.applicationContext ?: context
        ensureInitialized(appContext)
        val result = StarThemeJsonSerializer.deserializeAndValidate(rawJson, allowLocalImagePath = false)
        if (result !is StarThemeImportResult.Success) return result

        val imported = result.config
        val savedConfig = if (saveToLibrary) {
            saveThemeAsNew(
                context = appContext,
                name = imported.themeName,
                config = imported,
                activate = applyImmediately
            ).config
        } else {
            if (applyImmediately) applyTheme(appContext, imported) else imported
        }
        return StarThemeImportResult.Success(config = savedConfig, warnings = result.warnings)
    }

    private fun validateBackgroundFileExists(config: StarThemeConfig): StarThemeConfig {
        val path = config.background.imagePath
        if (path.isNullOrBlank()) {
            return if (config.background.enabled) {
                config.copy(background = config.background.copy(enabled = false, imagePath = null))
            } else config
        }
        val exists = File(path).let { it.isFile && it.canRead() }
        return if (exists) {
            config
        } else {
            config.copy(background = config.background.copy(enabled = false, imagePath = null))
        }
    }

    private fun loadCustomSavedThemes(context: Context): List<StarSavedThemeEntry> {
        val file = studioFile(context, SAVED_THEMES_FILE)
        if (!file.isFile) return emptyList()
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        return StarThemeJsonSerializer.deserializeSavedThemesList(raw)
            .map { entry -> entry.copy(config = validateBackgroundFileExists(entry.config)) }
    }

    private fun persistCustomSavedThemes(context: Context, entries: List<StarSavedThemeEntry>) {
        writeAtomic(
            studioFile(context, SAVED_THEMES_FILE),
            StarThemeJsonSerializer.serializeSavedThemesList(entries)
        )
    }

    private fun cleanupOrphanedWallpapers(context: Context) {
        val referenced = buildSet {
            _activeThemeFlow.value.background.imagePath?.let(::add)
            _savedThemesFlow.value.forEach { entry ->
                entry.config.background.imagePath?.let(::add)
            }
        }
        StarBackgroundEngine.cleanupOrphanedBackgrounds(context, referenced)
    }

    private fun publishActiveTheme(config: StarThemeConfig) {
        val resolved = StarResolvedTheme.resolve(config)
        _activeThemeFlow.value = config
        _resolvedThemeFlow.value = resolved
        AetherColors.applyResolvedTheme(resolved)
    }

    private fun publishSavedThemes(customEntries: List<StarSavedThemeEntry>) {
        _savedThemesFlow.value = StarThemePresets.builtInEntries(defaultQualityForDevice) + customEntries
    }

    private fun studioFile(context: Context, name: String): File {
        val dir = File(context.filesDir, STUDIO_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, name)
    }

    private fun writeAtomic(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            tmp.writeText(content, Charsets.UTF_8)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed persisting ${target.name}", e)
            tmp.delete()
        }
    }

    internal fun defaultQualityForProfile(profile: UiPerformanceProfile): StarEffectQuality = when (profile) {
        UiPerformanceProfile.LOW -> StarEffectQuality.LOW
        UiPerformanceProfile.BALANCED -> StarEffectQuality.MEDIUM
        UiPerformanceProfile.HIGH -> StarEffectQuality.HIGH
    }
}
