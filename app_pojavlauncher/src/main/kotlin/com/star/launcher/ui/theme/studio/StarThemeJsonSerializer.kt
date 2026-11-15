package com.star.launcher.ui.theme.studio

import org.json.JSONArray
import org.json.JSONObject

sealed interface StarThemeImportResult {
    data class Success(
        val config: StarThemeConfig,
        val warnings: List<String> = emptyList()
    ) : StarThemeImportResult

    data class Error(val reason: String) : StarThemeImportResult
}

/**
 * Safe, versioned JSON serializer and strict validator for STAR UI Studio themes.
 *
 * Security guarantees:
 * - Uses strict manual [JSONObject] field extraction (no reflection or dynamic class loading).
 * - Rejects oversized payloads, unsupported schema versions, and malformed non-theme JSON files.
 * - Strips local filesystem image paths from external imports to prevent path traversal.
 * - Clamps all numeric values and sanitizes names/IDs before returning a theme configuration.
 */
object StarThemeJsonSerializer {
    const val FORMAT_IDENTIFIER = "star.ui.theme"
    const val SUPPORTED_SCHEMA_VERSION = 1
    const val MAX_IMPORT_CHARS = 256 * 1024

    fun serialize(config: StarThemeConfig, includeLocalImagePath: Boolean = false): String {
        val clean = config.sanitized()
        return JSONObject().apply {
            put("format", FORMAT_IDENTIFIER)
            put("schemaVersion", SUPPORTED_SCHEMA_VERSION)
            put("themeId", clean.themeId)
            put("themeName", clean.themeName)
            put("presetId", clean.presetId.name)
            put("effectQuality", clean.effectQuality.name)
            put("background", serializeBackground(clean.background, includeLocalImagePath))
            put("glass", serializeGlass(clean.glass))
            put("colors", serializeColors(clean.colors))
            put("componentOverrides", serializeOverrides(clean.componentOverrides))
        }.toString(2)
    }

    fun deserializeAndValidate(
        rawJson: String,
        allowLocalImagePath: Boolean = false
    ): StarThemeImportResult {
        if (rawJson.isBlank()) {
            return StarThemeImportResult.Error("Theme JSON is empty.")
        }
        if (rawJson.length > MAX_IMPORT_CHARS) {
            return StarThemeImportResult.Error("Theme JSON exceeds maximum allowed size (${MAX_IMPORT_CHARS / 1024} KB).")
        }

        val root = runCatching { JSONObject(rawJson) }.getOrElse {
            return StarThemeImportResult.Error("Invalid JSON syntax: ${it.message ?: "parse error"}")
        }

        val format = root.optString("format", "")
        if (format.isNotEmpty() && format != FORMAT_IDENTIFIER) {
            return StarThemeImportResult.Error("Unsupported theme format '$format'. Expected '$FORMAT_IDENTIFIER'.")
        }

        if (!root.has("schemaVersion")) {
            return StarThemeImportResult.Error("Missing required 'schemaVersion' field.")
        }
        val schemaVersion = root.optInt("schemaVersion", -1)
        if (schemaVersion < 1 || schemaVersion > SUPPORTED_SCHEMA_VERSION) {
            return StarThemeImportResult.Error(
                "Unsupported schemaVersion $schemaVersion (supported: 1..$SUPPORTED_SCHEMA_VERSION)."
            )
        }

        val colorsObj = root.optJSONObject("colors")
        val glassObj = root.optJSONObject("glass")
        if (colorsObj == null && glassObj == null) {
            return StarThemeImportResult.Error("Theme JSON must contain 'colors' or 'glass' configuration.")
        }

        val warnings = mutableListOf<String>()
        val base = StarThemePresets.presetConfig(StarPresetId.CUSTOM)

        val presetId = parseEnum(root.optString("presetId", ""), StarPresetId.CUSTOM)
        val effectQuality = parseEnum(root.optString("effectQuality", ""), base.effectQuality)
        val rawName = root.optString("themeName", "Imported Theme")
        val rawId = root.optString("themeId", "imported_${System.currentTimeMillis()}")

        val background = root.optJSONObject("background")?.let { bgObj ->
            val rawPath = if (allowLocalImagePath) {
                bgObj.optString("imagePath", "").takeIf { it.isNotBlank() }
            } else {
                if (bgObj.has("imagePath") && bgObj.optString("imagePath", "").isNotBlank()) {
                    warnings += "Local wallpaper path was ignored for security."
                }
                null
            }
            val parsedEnabled = bgObj.optBoolean("enabled", false) && rawPath != null
            StarBackgroundConfig(
                enabled = parsedEnabled,
                imagePath = rawPath,
                offsetX = bgObj.optDouble("offsetX", 0.0).toFloat(),
                offsetY = bgObj.optDouble("offsetY", 0.0).toFloat(),
                zoom = bgObj.optDouble("zoom", 1.0).toFloat(),
                brightness = bgObj.optDouble("brightness", 0.0).toFloat(),
                saturation = bgObj.optDouble("saturation", 1.0).toFloat(),
                contrast = bgObj.optDouble("contrast", 1.0).toFloat(),
                darkOverlayAlpha = bgObj.optDouble("darkOverlayAlpha", base.background.darkOverlayAlpha.toDouble()).toFloat(),
                gradientOverlayEnabled = bgObj.optBoolean("gradientOverlayEnabled", base.background.gradientOverlayEnabled),
                gradientOverlayTopArgb = parseColorLong(bgObj, "gradientOverlayTopArgb", base.background.gradientOverlayTopArgb, warnings),
                gradientOverlayBottomArgb = parseColorLong(bgObj, "gradientOverlayBottomArgb", base.background.gradientOverlayBottomArgb, warnings),
                gradientOverlayAlpha = bgObj.optDouble("gradientOverlayAlpha", base.background.gradientOverlayAlpha.toDouble()).toFloat(),
                gradientAngleDeg = bgObj.optDouble("gradientAngleDeg", base.background.gradientAngleDeg.toDouble()).toFloat(),
                blurRadiusDp = bgObj.optDouble("blurRadiusDp", 0.0).toFloat()
            )
        } ?: base.background

        val glass = glassObj?.let { g ->
            StarGlassConfig(
                blurIntensity = g.optDouble("blurIntensity", base.glass.blurIntensity.toDouble()).toFloat(),
                backgroundOpacity = g.optDouble("backgroundOpacity", base.glass.backgroundOpacity.toDouble()).toFloat(),
                borderOpacity = g.optDouble("borderOpacity", base.glass.borderOpacity.toDouble()).toFloat(),
                cornerRadiusDp = g.optDouble("cornerRadiusDp", base.glass.cornerRadiusDp.toDouble()).toFloat(),
                shadowIntensity = g.optDouble("shadowIntensity", base.glass.shadowIntensity.toDouble()).toFloat(),
                refractionIntensity = g.optDouble("refractionIntensity", base.glass.refractionIntensity.toDouble()).toFloat(),
                highlightIntensity = g.optDouble("highlightIntensity", base.glass.highlightIntensity.toDouble()).toFloat(),
                edgeReflectionIntensity = g.optDouble("edgeReflectionIntensity", base.glass.edgeReflectionIntensity.toDouble()).toFloat(),
                waterDropletsEnabled = g.optBoolean("waterDropletsEnabled", base.glass.waterDropletsEnabled),
                waterDropletsAnimated = g.optBoolean("waterDropletsAnimated", base.glass.waterDropletsAnimated),
                waterDropletIntensity = g.optDouble("waterDropletIntensity", base.glass.waterDropletIntensity.toDouble()).toFloat(),
                glassEffectEnabled = g.optBoolean("glassEffectEnabled", base.glass.glassEffectEnabled),
                gradientEnabled = g.optBoolean("gradientEnabled", base.glass.gradientEnabled)
            )
        } ?: base.glass

        val colors = colorsObj?.let { c ->
            StarColorPalette(
                primaryAccentArgb = parseColorLong(c, "primaryAccentArgb", base.colors.primaryAccentArgb, warnings),
                secondaryAccentArgb = parseColorLong(c, "secondaryAccentArgb", base.colors.secondaryAccentArgb, warnings),
                backgroundTintArgb = parseColorLong(c, "backgroundTintArgb", base.colors.backgroundTintArgb, warnings),
                textColorArgb = parseColorLong(c, "textColorArgb", base.colors.textColorArgb, warnings),
                borderColorArgb = parseColorLong(c, "borderColorArgb", base.colors.borderColorArgb, warnings),
                gradientStartArgb = parseColorLong(c, "gradientStartArgb", base.colors.gradientStartArgb, warnings),
                gradientEndArgb = parseColorLong(c, "gradientEndArgb", base.colors.gradientEndArgb, warnings),
                highlightColorArgb = parseColorLong(c, "highlightColorArgb", base.colors.highlightColorArgb, warnings)
            )
        } ?: base.colors

        val overrides = mutableMapOf<StarComponentTarget, StarComponentStyleOverride>()
        root.optJSONObject("componentOverrides")?.let { ovRoot ->
            val keys = ovRoot.keys()
            while (keys.hasNext()) {
                val targetKey = keys.next()
                val target = StarComponentTarget.values().firstOrNull { it.name == targetKey }
                if (target == null) {
                    warnings += "Ignored unknown component target '$targetKey'."
                    continue
                }
                val ovObj = ovRoot.optJSONObject(targetKey) ?: continue
                overrides[target] = StarComponentStyleOverride(
                    overrideEnabled = ovObj.optBoolean("overrideEnabled", true),
                    backgroundOpacity = ovObj.optDouble("backgroundOpacity", glass.backgroundOpacity.toDouble()).toFloat(),
                    blurIntensity = ovObj.optDouble("blurIntensity", glass.blurIntensity.toDouble()).toFloat(),
                    borderOpacity = ovObj.optDouble("borderOpacity", glass.borderOpacity.toDouble()).toFloat(),
                    cornerRadiusDp = ovObj.optDouble("cornerRadiusDp", glass.cornerRadiusDp.toDouble()).toFloat(),
                    shadowIntensity = ovObj.optDouble("shadowIntensity", glass.shadowIntensity.toDouble()).toFloat(),
                    accentColorArgb = if (ovObj.has("accentColorArgb") && !ovObj.isNull("accentColorArgb")) {
                        parseColorLong(ovObj, "accentColorArgb", colors.primaryAccentArgb, warnings)
                    } else null,
                    gradientEnabled = ovObj.optBoolean("gradientEnabled", glass.gradientEnabled),
                    gradientStartArgb = if (ovObj.has("gradientStartArgb") && !ovObj.isNull("gradientStartArgb")) {
                        parseColorLong(ovObj, "gradientStartArgb", colors.gradientStartArgb, warnings)
                    } else null,
                    gradientEndArgb = if (ovObj.has("gradientEndArgb") && !ovObj.isNull("gradientEndArgb")) {
                        parseColorLong(ovObj, "gradientEndArgb", colors.gradientEndArgb, warnings)
                    } else null,
                    glassEffectEnabled = ovObj.optBoolean("glassEffectEnabled", glass.glassEffectEnabled)
                )
            }
        }

        val config = StarThemeConfig(
            schemaVersion = SUPPORTED_SCHEMA_VERSION,
            themeId = rawId,
            themeName = rawName,
            presetId = if (allowLocalImagePath) presetId else StarPresetId.CUSTOM,
            effectQuality = effectQuality,
            background = background,
            glass = glass,
            colors = colors,
            componentOverrides = overrides
        ).sanitized()

        return StarThemeImportResult.Success(config = config, warnings = warnings)
    }

    fun serializeSavedThemesList(entries: List<StarSavedThemeEntry>): String {
        val array = JSONArray()
        entries.filter { !it.isBuiltIn }.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("name", entry.name)
                    put("updatedAtEpochMs", entry.updatedAtEpochMs)
                    put("config", JSONObject(serialize(entry.config, includeLocalImagePath = true)))
                }
            )
        }
        return JSONObject().apply {
            put("schemaVersion", SUPPORTED_SCHEMA_VERSION)
            put("themes", array)
        }.toString(2)
    }

    fun deserializeSavedThemesList(rawJson: String): List<StarSavedThemeEntry> {
        if (rawJson.isBlank()) return emptyList()
        return runCatching {
            val root = JSONObject(rawJson)
            val arr = root.optJSONArray("themes") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val cfgObj = item.optJSONObject("config") ?: continue
                    val parsed = deserializeAndValidate(cfgObj.toString(), allowLocalImagePath = true)
                    if (parsed is StarThemeImportResult.Success) {
                        val id = StarThemeConfig.sanitizeThemeId(item.optString("id", parsed.config.themeId))
                        val name = StarThemeConfig.sanitizeThemeName(item.optString("name", parsed.config.themeName))
                        val updated = item.optLong("updatedAtEpochMs", System.currentTimeMillis())
                        add(
                            StarSavedThemeEntry(
                                id = id,
                                name = name,
                                isBuiltIn = false,
                                updatedAtEpochMs = updated,
                                config = parsed.config.copy(themeId = id, themeName = name)
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun serializeBackground(bg: StarBackgroundConfig, includeLocalImagePath: Boolean): JSONObject {
        return JSONObject().apply {
            put("enabled", bg.enabled && includeLocalImagePath && !bg.imagePath.isNullOrBlank())
            if (includeLocalImagePath && !bg.imagePath.isNullOrBlank()) {
                put("imagePath", bg.imagePath)
            }
            put("offsetX", bg.offsetX.toDouble())
            put("offsetY", bg.offsetY.toDouble())
            put("zoom", bg.zoom.toDouble())
            put("brightness", bg.brightness.toDouble())
            put("saturation", bg.saturation.toDouble())
            put("contrast", bg.contrast.toDouble())
            put("darkOverlayAlpha", bg.darkOverlayAlpha.toDouble())
            put("gradientOverlayEnabled", bg.gradientOverlayEnabled)
            put("gradientOverlayTopArgb", formatColorHex(bg.gradientOverlayTopArgb))
            put("gradientOverlayBottomArgb", formatColorHex(bg.gradientOverlayBottomArgb))
            put("gradientOverlayAlpha", bg.gradientOverlayAlpha.toDouble())
            put("gradientAngleDeg", bg.gradientAngleDeg.toDouble())
            put("blurRadiusDp", bg.blurRadiusDp.toDouble())
        }
    }

    private fun serializeGlass(glass: StarGlassConfig): JSONObject {
        return JSONObject().apply {
            put("blurIntensity", glass.blurIntensity.toDouble())
            put("backgroundOpacity", glass.backgroundOpacity.toDouble())
            put("borderOpacity", glass.borderOpacity.toDouble())
            put("cornerRadiusDp", glass.cornerRadiusDp.toDouble())
            put("shadowIntensity", glass.shadowIntensity.toDouble())
            put("refractionIntensity", glass.refractionIntensity.toDouble())
            put("highlightIntensity", glass.highlightIntensity.toDouble())
            put("edgeReflectionIntensity", glass.edgeReflectionIntensity.toDouble())
            put("waterDropletsEnabled", glass.waterDropletsEnabled)
            put("waterDropletsAnimated", glass.waterDropletsAnimated)
            put("waterDropletIntensity", glass.waterDropletIntensity.toDouble())
            put("glassEffectEnabled", glass.glassEffectEnabled)
            put("gradientEnabled", glass.gradientEnabled)
        }
    }

    private fun serializeColors(colors: StarColorPalette): JSONObject {
        return JSONObject().apply {
            put("primaryAccentArgb", formatColorHex(colors.primaryAccentArgb))
            put("secondaryAccentArgb", formatColorHex(colors.secondaryAccentArgb))
            put("backgroundTintArgb", formatColorHex(colors.backgroundTintArgb))
            put("textColorArgb", formatColorHex(colors.textColorArgb))
            put("borderColorArgb", formatColorHex(colors.borderColorArgb))
            put("gradientStartArgb", formatColorHex(colors.gradientStartArgb))
            put("gradientEndArgb", formatColorHex(colors.gradientEndArgb))
            put("highlightColorArgb", formatColorHex(colors.highlightColorArgb))
        }
    }

    private fun serializeOverrides(overrides: Map<StarComponentTarget, StarComponentStyleOverride>): JSONObject {
        return JSONObject().apply {
            overrides.forEach { (target, ov) ->
                if (ov.overrideEnabled) {
                    put(target.name, JSONObject().apply {
                        put("overrideEnabled", true)
                        put("backgroundOpacity", ov.backgroundOpacity.toDouble())
                        put("blurIntensity", ov.blurIntensity.toDouble())
                        put("borderOpacity", ov.borderOpacity.toDouble())
                        put("cornerRadiusDp", ov.cornerRadiusDp.toDouble())
                        put("shadowIntensity", ov.shadowIntensity.toDouble())
                        ov.accentColorArgb?.let { put("accentColorArgb", formatColorHex(it)) }
                        put("gradientEnabled", ov.gradientEnabled)
                        ov.gradientStartArgb?.let { put("gradientStartArgb", formatColorHex(it)) }
                        ov.gradientEndArgb?.let { put("gradientEndArgb", formatColorHex(it)) }
                        put("glassEffectEnabled", ov.glassEffectEnabled)
                    })
                }
            }
        }
    }

    fun formatColorHex(argb: Long): String {
        return String.format("#%08X", argb and 0xFFFFFFFFL)
    }

    fun parseColorHexOrNull(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
        return when (cleaned.length) {
            6 -> cleaned.toLongOrNull(16)?.let { 0xFF000000L or it }
            8 -> cleaned.toLongOrNull(16)
            else -> null
        }
    }

    private fun parseColorLong(
        obj: JSONObject,
        key: String,
        fallback: Long,
        warnings: MutableList<String>
    ): Long {
        if (!obj.has(key) || obj.isNull(key)) return fallback
        val raw = obj.opt(key)
        return when (raw) {
            is Number -> raw.toLong() and 0xFFFFFFFFL
            is String -> parseColorHexOrNull(raw) ?: run {
                warnings += "Invalid color value for '$key'; kept default."
                fallback
            }
            else -> {
                warnings += "Invalid color type for '$key'; kept default."
                fallback
            }
        }
    }

    private inline fun <reified T : Enum<T>> parseEnum(raw: String, fallback: T): T {
        if (raw.isBlank()) return fallback
        return enumValues<T>().firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: fallback
    }
}
