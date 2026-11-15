package com.star.launcher.mods.inventory

import com.star.launcher.data.db.model.ModLoaderType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

enum class ModMetadataSource {
    JAR_MANIFEST,
    FILENAME_INFERRED
}

data class ParsedJarModMetadata(
    val modId: String?,
    val displayName: String,
    val version: String?,
    val loaderType: ModLoaderType?,
    val mcVersionHint: String?,
    val source: ModMetadataSource,
    val isValidArchive: Boolean = true
)

/**
 * Safely inspects `.jar` / `.jar.disabled` mod archives for standard Fabric, Quilt,
 * NeoForge, and Forge metadata files without executing any bytecode.
 */
object JarModMetadataParser {

    fun isValidZipArchive(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        return runCatching {
            ZipFile(file).use { zip ->
                zip.entries().hasMoreElements()
            }
        }.getOrDefault(false)
    }

    fun parse(file: File): ParsedJarModMetadata {
        val cleanBaseName = file.name
            .removeSuffix(".disabled")
            .removeSuffix(".jar")

        if (!file.isFile || file.length() <= 0L) {
            return ParsedJarModMetadata(
                modId = null,
                displayName = cleanBaseName,
                version = null,
                loaderType = null,
                mcVersionHint = null,
                source = ModMetadataSource.FILENAME_INFERRED,
                isValidArchive = false
            )
        }

        var archiveReadable = false
        val parsedFromZip = runCatching {
            ZipFile(file).use { zip ->
                archiveReadable = zip.entries().hasMoreElements()
                parseFabric(zip)
                    ?: parseQuilt(zip)
                    ?: parseNeoForgeToml(zip)
                    ?: parseForgeToml(zip)
                    ?: parseLegacyMcModInfo(zip)
            }
        }.getOrNull()

        if (parsedFromZip != null) return parsedFromZip

        return inferFromFileName(cleanBaseName, isValidArchive = archiveReadable)
    }

    private fun parseFabric(zip: ZipFile): ParsedJarModMetadata? {
        val entry = zip.getEntry("fabric.mod.json") ?: return null
        val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val json = JSONObject(text)
        val modId = json.optString("id").takeIf { it.isNotBlank() }
        val name = json.optString("name").ifBlank { modId ?: return null }
        val version = json.optString("version").takeIf { it.isNotBlank() }
        val mcHint = json.optJSONObject("depends")?.opt("minecraft")?.toString()
            ?.replace(Regex("""["\[\]]"""), "")
            ?.takeIf { it.isNotBlank() }
        return ParsedJarModMetadata(
            modId = modId,
            displayName = name,
            version = version,
            loaderType = ModLoaderType.FABRIC,
            mcVersionHint = mcHint,
            source = ModMetadataSource.JAR_MANIFEST
        )
    }

    private fun parseQuilt(zip: ZipFile): ParsedJarModMetadata? {
        val entry = zip.getEntry("quilt.mod.json") ?: return null
        val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(text)
        val loader = root.optJSONObject("quilt_loader") ?: return null
        val modId = loader.optString("id").takeIf { it.isNotBlank() }
        val meta = loader.optJSONObject("metadata")
        val name = meta?.optString("name")?.takeIf { it.isNotBlank() } ?: modId ?: return null
        val version = loader.optString("version").takeIf { it.isNotBlank() }
        return ParsedJarModMetadata(
            modId = modId,
            displayName = name,
            version = version,
            loaderType = ModLoaderType.QUILT,
            mcVersionHint = null,
            source = ModMetadataSource.JAR_MANIFEST
        )
    }

    private fun parseNeoForgeToml(zip: ZipFile): ParsedJarModMetadata? {
        val entry = zip.getEntry("META-INF/neoforge.mods.toml") ?: return null
        val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return parseTomlModsBlock(text, ModLoaderType.NEOFORGE)
    }

    private fun parseForgeToml(zip: ZipFile): ParsedJarModMetadata? {
        val entry = zip.getEntry("META-INF/mods.toml") ?: return null
        val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val loader = if (text.contains("neoforge", ignoreCase = true)) {
            ModLoaderType.NEOFORGE
        } else {
            ModLoaderType.FORGE
        }
        return parseTomlModsBlock(text, loader)
    }

    private fun parseTomlModsBlock(text: String, loader: ModLoaderType): ParsedJarModMetadata? {
        val modId = extractTomlValue(text, "modId")
        val displayName = extractTomlValue(text, "displayName") ?: modId ?: return null
        val version = extractTomlValue(text, "version")
            ?.takeIf { !it.contains("\${") }
        return ParsedJarModMetadata(
            modId = modId,
            displayName = displayName,
            version = version,
            loaderType = loader,
            mcVersionHint = null,
            source = ModMetadataSource.JAR_MANIFEST
        )
    }

    private fun parseLegacyMcModInfo(zip: ZipFile): ParsedJarModMetadata? {
        val entry = zip.getEntry("mcmod.info") ?: return null
        val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
        val array = if (text.startsWith("[")) {
            JSONArray(text)
        } else {
            JSONObject(text).optJSONArray("modList") ?: return null
        }
        val first = array.optJSONObject(0) ?: return null
        val modId = first.optString("modid").takeIf { it.isNotBlank() }
        val name = first.optString("name").ifBlank { modId ?: return null }
        val version = first.optString("version").takeIf { it.isNotBlank() }
        val mcVersion = first.optString("mcversion").takeIf { it.isNotBlank() }
        return ParsedJarModMetadata(
            modId = modId,
            displayName = name,
            version = version,
            loaderType = ModLoaderType.FORGE,
            mcVersionHint = mcVersion,
            source = ModMetadataSource.JAR_MANIFEST
        )
    }

    private fun extractTomlValue(toml: String, key: String): String? {
        val regex = Regex("""(?m)^\s*${Regex.escape(key)}\s*=\s*["']([^"']+)["']""")
        return regex.find(toml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun inferFromFileName(
        cleanBaseName: String,
        isValidArchive: Boolean = true
    ): ParsedJarModMetadata {
        val lower = cleanBaseName.lowercase()
        val loader = when {
            lower.contains("neoforge") -> ModLoaderType.NEOFORGE
            lower.contains("fabric") -> ModLoaderType.FABRIC
            lower.contains("quilt") -> ModLoaderType.QUILT
            lower.contains("forge") -> ModLoaderType.FORGE
            else -> null
        }
        val mcMatch = Regex("""1\.\d+(?:\.\d+)?""").find(cleanBaseName)?.value
        return ParsedJarModMetadata(
            modId = null,
            displayName = cleanBaseName,
            version = null,
            loaderType = loader,
            mcVersionHint = mcMatch,
            source = ModMetadataSource.FILENAME_INFERRED,
            isValidArchive = isValidArchive
        )
    }
}
