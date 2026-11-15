package com.star.launcher.hud

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Cross-process atomic file-backed persistence for [StarHudConfig], multi-layout profiles,
 * and named presets.
 *
 * Uses app-private `filesDir` with write-to-temp + replace semantics so dashboard UI and `:game`
 * share immediate consistency and partial writes cannot corrupt saved layouts.
 */
object StarHudConfigStore {
    private const val TAG = "StarHudConfigStore"
    private const val ACTIVE_CONFIG_FILE = "star_hud_config.json"
    private const val PRESETS_DIR = "star_hud_presets"
    private const val LAYOUTS_DIR = "star_hud_layouts"
    private const val QUICK_BACKUP_NAME = "last_saved"

    private val _configFlow = MutableStateFlow(StarHudConfig.defaults())
    val configFlow: StateFlow<StarHudConfig> = _configFlow.asStateFlow()

    @Volatile
    private var lastObservedModifiedMs: Long = -2L

    @Volatile
    private var defaultLayoutSeeded: Boolean = false

    @JvmStatic
    fun load(context: Context): StarHudConfig {
        val file = activeFile(context)
        val mod = if (file.isFile) file.lastModified() else -1L
        if (lastObservedModifiedMs != -2L && mod == lastObservedModifiedMs) {
            return _configFlow.value
        }
        ensureDefaultLayoutSeeded(context)
        val loaded = if (file.isFile) {
            runCatching { fromJson(file.readText(Charsets.UTF_8)) }
                .onFailure { Log.w(TAG, "Failed to parse HUD config, recovering defaults", it) }
                .getOrElse { StarHudConfig.defaults() }
        } else {
            StarHudConfig.defaults()
        }.sanitized()
        lastObservedModifiedMs = if (file.isFile) file.lastModified() else -1L
        _configFlow.value = loaded
        return loaded
    }

    @JvmStatic
    fun save(context: Context, config: StarHudConfig): StarHudConfig {
        val clean = config.sanitized()
        val json = toJson(clean)
        val active = activeFile(context)
        writeJsonAtomic(active, json)
        writeJsonAtomic(layoutFile(context, clean.layoutId), json)
        lastObservedModifiedMs = if (active.isFile) active.lastModified() else System.currentTimeMillis()
        _configFlow.value = clean
        return clean
    }

    @JvmStatic
    fun resetToDefault(context: Context): StarHudConfig {
        val current = load(context)
        val reset = StarHudConfig.defaults().copy(
            layoutId = current.layoutId,
            layoutName = current.layoutName
        ).sanitized()
        return save(context, reset)
    }

    /**
     * Ensures a recoverable Default Controller layout file always exists on disk.
     */
    @JvmStatic
    fun ensureDefaultLayoutSeeded(context: Context) {
        if (defaultLayoutSeeded) return
        val defaultFile = layoutFile(context, StarHudConfig.DEFAULT_LAYOUT_ID)
        if (!defaultFile.isFile) {
            writeJsonAtomic(defaultFile, toJson(StarHudConfig.defaults()))
        }
        defaultLayoutSeeded = true
    }

    @JvmStatic
    fun listLayouts(context: Context): List<StarHudLayoutSummary> {
        ensureDefaultLayoutSeeded(context)
        val dir = layoutsDir(context)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()
        return files.mapNotNull { file ->
            runCatching {
                val cfg = fromJson(file.readText(Charsets.UTF_8)).sanitized()
                val id = file.name.removeSuffix(".json")
                StarHudLayoutSummary(
                    layoutId = id,
                    layoutName = cfg.layoutName,
                    updatedAtMs = file.lastModified(),
                    buttonCount = cfg.buttons.size,
                    isDefault = id == StarHudConfig.DEFAULT_LAYOUT_ID
                )
            }.onFailure {
                Log.w(TAG, "Ignoring corrupt layout file ${file.name}", it)
            }.getOrNull()
        }.sortedWith(compareByDescending<StarHudLayoutSummary> { it.isDefault }.thenBy { it.layoutName.lowercase() })
    }

    @JvmStatic
    @JvmOverloads
    fun createLayout(
        context: Context,
        layoutName: String,
        sourceConfig: StarHudConfig = load(context)
    ): StarHudConfig {
        ensureDefaultLayoutSeeded(context)
        val cleanName = layoutName.trim().ifBlank { "Custom Layout" }.take(40)
        val newId = generateUniqueLayoutId(context, cleanName)
        val created = sourceConfig.copy(layoutId = newId, layoutName = cleanName).sanitized()
        return save(context, created)
    }

    @JvmStatic
    fun switchLayout(context: Context, layoutId: String): StarHudConfig? {
        val safeId = sanitizeLayoutId(layoutId)
        val file = layoutFile(context, safeId)
        if (!file.isFile) return null
        val loaded = runCatching { fromJson(file.readText(Charsets.UTF_8)) }
            .onFailure { Log.w(TAG, "Failed to load layout '$safeId'", it) }
            .getOrNull()
            ?.copy(layoutId = safeId)
            ?.sanitized() ?: return null
        val active = activeFile(context)
        writeJsonAtomic(active, toJson(loaded))
        lastObservedModifiedMs = if (active.isFile) active.lastModified() else System.currentTimeMillis()
        _configFlow.value = loaded
        return loaded
    }

    @JvmStatic
    fun renameLayout(context: Context, layoutId: String, newName: String): StarHudConfig? {
        val safeId = sanitizeLayoutId(layoutId)
        val cleanName = newName.trim().ifBlank { return null }.take(40)
        val file = layoutFile(context, safeId)
        if (!file.isFile) return null
        val existing = runCatching { fromJson(file.readText(Charsets.UTF_8)) }.getOrNull()?.sanitized() ?: return null
        val updated = existing.copy(layoutId = safeId, layoutName = cleanName).sanitized()
        writeJsonAtomic(file, toJson(updated))
        val active = load(context)
        if (active.layoutId == safeId) {
            return save(context, updated)
        }
        return updated
    }

    @JvmStatic
    @JvmOverloads
    fun duplicateLayout(
        context: Context,
        sourceLayoutId: String,
        duplicateName: String? = null
    ): StarHudConfig? {
        val safeSourceId = sanitizeLayoutId(sourceLayoutId)
        val file = layoutFile(context, safeSourceId)
        if (!file.isFile) return null
        val sourceCfg = runCatching { fromJson(file.readText(Charsets.UTF_8)) }.getOrNull()?.sanitized() ?: return null
        val nextName = duplicateName?.trim()?.takeIf { it.isNotEmpty() } ?: "${sourceCfg.layoutName} Copy"
        return createLayout(context, nextName, sourceCfg)
    }

    @JvmStatic
    fun deleteLayout(context: Context, layoutId: String): StarHudConfig? {
        val safeId = sanitizeLayoutId(layoutId)
        if (safeId == StarHudConfig.DEFAULT_LAYOUT_ID) {
            return null
        }
        val file = layoutFile(context, safeId)
        if (!file.delete() && file.exists()) {
            return null
        }
        val current = load(context)
        return if (current.layoutId == safeId) {
            switchLayout(context, StarHudConfig.DEFAULT_LAYOUT_ID) ?: save(context, StarHudConfig.defaults())
        } else {
            current
        }
    }

    @JvmStatic
    fun restoreDefaultLayout(context: Context): StarHudConfig {
        val defaults = StarHudConfig.defaults()
        writeJsonAtomic(layoutFile(context, StarHudConfig.DEFAULT_LAYOUT_ID), toJson(defaults))
        return save(context, defaults)
    }

    /**
     * Backward-compatible preset backup API.
     */
    @JvmStatic
    @JvmOverloads
    fun savePreset(context: Context, presetName: String = QUICK_BACKUP_NAME, config: StarHudConfig = load(context)): String {
        val safeName = sanitizePresetName(presetName)
        val clean = config.sanitized()
        val dir = presetsDir(context)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        writeJsonAtomic(File(dir, "$safeName.json"), toJson(clean))
        return safeName
    }

    @JvmStatic
    @JvmOverloads
    fun restorePreset(context: Context, presetName: String = QUICK_BACKUP_NAME): StarHudConfig? {
        val safeName = sanitizePresetName(presetName)
        val presetFile = File(presetsDir(context), "$safeName.json")
        if (!presetFile.isFile) return null
        val loaded = runCatching { fromJson(presetFile.readText(Charsets.UTF_8)) }
            .onFailure { Log.w(TAG, "Failed to restore preset $safeName", it) }
            .getOrNull()
            ?.sanitized() ?: return null
        return save(context, loaded)
    }

    @JvmStatic
    fun hasSavedPreset(context: Context, presetName: String = QUICK_BACKUP_NAME): Boolean {
        val safeName = sanitizePresetName(presetName)
        return File(presetsDir(context), "$safeName.json").isFile
    }

    @JvmStatic
    fun listPresets(context: Context): List<String> {
        val dir = presetsDir(context)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.map { it.name.removeSuffix(".json") }
            ?.sorted()
            ?: emptyList()
    }

    @JvmStatic
    fun deletePreset(context: Context, presetName: String): Boolean {
        val safeName = sanitizePresetName(presetName)
        return File(presetsDir(context), "$safeName.json").delete()
    }

    @JvmStatic
    fun toJson(config: StarHudConfig): String {
        val clean = config.sanitized()
        val root = JSONObject()
        root.put("version", StarHudConfig.CONFIG_VERSION)
        root.put("layoutId", clean.layoutId)
        root.put("layoutName", clean.layoutName)
        root.put("useStarHud", clean.useStarHud)
        root.put("hudScale", clean.hudScale.toDouble())
        root.put("hudOpacity", clean.hudOpacity.toDouble())
        root.put("joystickSize", clean.joystickSize.toDouble())
        root.put("joystickDynamic", clean.joystickDynamic)
        root.put("hotbarScale", clean.hotbarScale.toDouble())
        root.put("cameraSensitivity", clean.cameraSensitivity.toDouble())
        root.put("sneakToggle", clean.sneakToggle)
        root.put("sprintToggle", clean.sprintToggle)
        root.put("screenGesturesEnabled", clean.screenGesturesEnabled)
        root.put("snapToGuides", clean.snapToGuides)
        root.put("showAlignmentGuides", clean.showAlignmentGuides)
        root.put("showInputDebugPanel", clean.showInputDebugPanel)

        val elementsObj = JSONObject()
        StarHudElementId.values().forEach { id ->
            val layout = clean.elementLayout(id)
            val entry = JSONObject()
            entry.put("x", layout.xFraction.toDouble())
            entry.put("y", layout.yFraction.toDouble())
            entry.put("enabled", layout.enabled)
            elementsObj.put(id.name, entry)
        }
        root.put("elements", elementsObj)

        val buttonsArr = JSONArray()
        clean.buttons.forEach { btn ->
            val bObj = JSONObject()
            bObj.put("id", btn.id)
            bObj.put("action", btn.action.name)
            bObj.put("customKeycode", btn.customKeycode)
            bObj.put("x", btn.xFraction.toDouble())
            bObj.put("y", btn.yFraction.toDouble())
            bObj.put("widthDp", btn.widthDp.toDouble())
            bObj.put("heightDp", btn.heightDp.toDouble())
            bObj.put("opacity", btn.opacity.toDouble())
            bObj.put("backgroundAlpha", btn.backgroundAlpha.toDouble())
            bObj.put("icon", btn.icon.name)
            bObj.put("label", btn.label)
            bObj.put("visible", btn.visible)
            bObj.put("interactionMode", btn.interactionMode.name)
            bObj.put("isBuiltIn", btn.isBuiltIn)
            buttonsArr.put(bObj)
        }
        root.put("buttons", buttonsArr)
        return root.toString(2)
    }

    @JvmStatic
    fun fromJson(json: String): StarHudConfig {
        val root = JSONObject(json)
        val defaults = StarHudConfig.defaults()
        val elementsObj = root.optJSONObject("elements")
        val elementsMap = StarHudElementId.values().associateWith { id ->
            val entry = elementsObj?.optJSONObject(id.name)
            if (entry != null) {
                StarHudElementLayout(
                    xFraction = entry.optDouble("x", id.defaultXFraction.toDouble()).toFloat(),
                    yFraction = entry.optDouble("y", id.defaultYFraction.toDouble()).toFloat(),
                    enabled = entry.optBoolean("enabled", id.defaultEnabled)
                ).clamped()
            } else {
                defaults.elementLayout(id)
            }
        }

        val sneakToggle = root.optBoolean("sneakToggle", defaults.sneakToggle)
        val sprintToggle = root.optBoolean("sprintToggle", defaults.sprintToggle)

        val buttonsArr = root.optJSONArray("buttons")
        val parsedButtons = if (buttonsArr != null && buttonsArr.length() > 0) {
            buildList {
                for (i in 0 until buttonsArr.length()) {
                    val obj = buttonsArr.optJSONObject(i) ?: continue
                    val actionName = obj.optString("action", StarHudActionType.CUSTOM_KEY.name)
                    val action = runCatching { StarHudActionType.valueOf(actionName) }
                        .getOrElse { StarHudActionType.CUSTOM_KEY }
                    val iconName = obj.optString("icon", action.defaultIcon.name)
                    val icon = runCatching { StarHudIconId.valueOf(iconName) }
                        .getOrElse { action.defaultIcon }
                    val modeName = obj.optString("interactionMode", action.defaultInteractionMode.name)
                    val mode = runCatching { StarHudInteractionMode.valueOf(modeName) }
                        .getOrElse { action.defaultInteractionMode }
                    add(
                        StarHudButtonConfig(
                            id = obj.optString("id", "btn_$i"),
                            action = action,
                            customKeycode = obj.optInt("customKeycode", 0),
                            xFraction = obj.optDouble("x", 0.5).toFloat(),
                            yFraction = obj.optDouble("y", 0.5).toFloat(),
                            widthDp = obj.optDouble("widthDp", action.defaultWidthDp.toDouble()).toFloat(),
                            heightDp = obj.optDouble("heightDp", action.defaultHeightDp.toDouble()).toFloat(),
                            opacity = obj.optDouble("opacity", 1.0).toFloat(),
                            backgroundAlpha = obj.optDouble("backgroundAlpha", 0.80).toFloat(),
                            icon = icon,
                            label = obj.optString("label", action.defaultShortLabel),
                            visible = obj.optBoolean("visible", true),
                            interactionMode = mode,
                            isBuiltIn = obj.optBoolean("isBuiltIn", false)
                        ).sanitized()
                    )
                }
            }
        } else {
            // Automatic v1 -> v2 migration from legacy `elements` map
            StarHudConfig.synthesizeButtonsFromElements(elementsMap, sneakToggle, sprintToggle)
        }

        return StarHudConfig(
            layoutId = sanitizeLayoutId(root.optString("layoutId", defaults.layoutId)),
            layoutName = root.optString("layoutName", defaults.layoutName),
            useStarHud = root.optBoolean("useStarHud", defaults.useStarHud),
            hudScale = root.optDouble("hudScale", defaults.hudScale.toDouble()).toFloat(),
            hudOpacity = root.optDouble("hudOpacity", defaults.hudOpacity.toDouble()).toFloat(),
            joystickSize = root.optDouble("joystickSize", defaults.joystickSize.toDouble()).toFloat(),
            joystickDynamic = root.optBoolean("joystickDynamic", defaults.joystickDynamic),
            hotbarScale = root.optDouble("hotbarScale", defaults.hotbarScale.toDouble()).toFloat(),
            cameraSensitivity = root.optDouble("cameraSensitivity", defaults.cameraSensitivity.toDouble()).toFloat(),
            sneakToggle = sneakToggle,
            sprintToggle = sprintToggle,
            screenGesturesEnabled = root.optBoolean("screenGesturesEnabled", defaults.screenGesturesEnabled),
            snapToGuides = root.optBoolean("snapToGuides", defaults.snapToGuides),
            showAlignmentGuides = root.optBoolean("showAlignmentGuides", defaults.showAlignmentGuides),
            showInputDebugPanel = root.optBoolean("showInputDebugPanel", defaults.showInputDebugPanel),
            elements = elementsMap,
            buttons = parsedButtons
        ).sanitized()
    }

    private fun activeFile(context: Context): File =
        File(context.applicationContext.filesDir, ACTIVE_CONFIG_FILE)

    private fun presetsDir(context: Context): File =
        File(context.applicationContext.filesDir, PRESETS_DIR)

    private fun layoutsDir(context: Context): File =
        File(context.applicationContext.filesDir, LAYOUTS_DIR).apply {
            if (!exists()) mkdirs()
        }

    private fun layoutFile(context: Context, layoutId: String): File =
        File(layoutsDir(context), "${sanitizeLayoutId(layoutId)}.json")

    private fun generateUniqueLayoutId(context: Context, baseName: String): String {
        val baseSlug = sanitizeLayoutId(baseName.lowercase())
        var candidate = baseSlug
        var suffix = 2
        while (layoutFile(context, candidate).exists() || candidate == StarHudConfig.DEFAULT_LAYOUT_ID) {
            candidate = "${baseSlug}_$suffix"
            suffix++
        }
        return candidate
    }

    private fun sanitizeLayoutId(raw: String): String {
        val filtered = raw.trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
        return if (filtered.isEmpty()) StarHudConfig.DEFAULT_LAYOUT_ID else filtered.take(48)
    }

    private fun sanitizePresetName(raw: String): String {
        val filtered = raw.trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
        return if (filtered.isEmpty()) QUICK_BACKUP_NAME else filtered.take(48)
    }

    private fun writeJsonAtomic(target: File, content: String) {
        val parent = target.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmp = File(parent, "${target.name}.tmp")
        try {
            tmp.writeText(content, Charsets.UTF_8)
            if (target.exists() && !tmp.renameTo(target)) {
                target.writeText(content, Charsets.UTF_8)
                tmp.delete()
            } else if (!target.exists() && !tmp.renameTo(target)) {
                target.writeText(content, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write HUD config to ${target.absolutePath}", e)
            tmp.delete()
        }
    }
}
