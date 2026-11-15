package com.star.launcher.version

import com.star.launcher.core.AetherStorageManager
import com.star.launcher.data.db.dao.GameVersionDao
import com.star.launcher.data.db.entity.GameVersionEntity
import com.star.launcher.data.db.model.ModLoaderType
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant

data class ParsedVersionManifest(
    val versionId: String,
    val inheritsFrom: String?,
    val versionType: String,
    val modLoaderType: ModLoaderType,
    val modLoaderVersion: String?,
    val requiredJavaMajor: Int,
    val lwjglMajor: Int,
    val releaseTimeEpochMs: Long,
    val hasJavaVersionDeclared: Boolean,
    val hasClientDownload: Boolean = false,
    val assetIndexId: String? = null,
    val detectedMinecraftVersion: String? = null
)

data class VersionVerificationResult(
    val versionId: String,
    val isValid: Boolean,
    val failureCategory: InstallErrorCategory? = null,
    val failureMessage: String? = null,
    val diagnosticDetails: String? = null,
    val diskSizeBytes: Long = 0L,
    val installedManifestSha1: String? = null,
    val clientJarPresent: Boolean = false,
    val missingLibrariesCount: Int = 0,
    val assetIndexPresent: Boolean = true,
    val requiredJavaMajor: Int = 8,
    val installedAt: Long = System.currentTimeMillis()
)

/**
 * Discovers, parses, and indexes local Minecraft & mod-loader version manifests into Room.
 */
class VersionManager(
    private val gameVersionDao: GameVersionDao,
    private val storageManager: AetherStorageManager
) {
    fun observeCatalog(): Flow<List<GameVersionEntity>> = gameVersionDao.observeAll()

    suspend fun getVersion(versionId: String): GameVersionEntity? = gameVersionDao.getById(versionId)

    /**
     * Scans `versions/` on disk without modifying or deleting any manifest/jar files.
     */
    suspend fun scanInstalledVersions(): List<GameVersionEntity> {
        val versionsDir = storageManager.versionsRoot()
        if (!versionsDir.isDirectory) return emptyList()

        val folders = versionsDir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        val parsedMap = LinkedHashMap<String, Triple<ParsedVersionManifest, JSONObject, File>>()

        for (dir in folders) {
            val manifestFile = File(dir, "${dir.name}.json")
            if (!manifestFile.isFile || manifestFile.length() <= 0L) continue
            val rawJson = runCatching { manifestFile.readText() }.getOrNull() ?: continue
            val rootObj = runCatching { JSONObject(rawJson) }.getOrNull() ?: continue
            val parsed = runCatching { parseManifestJson(rawJson, dir.name) }.getOrNull() ?: continue
            val clientJar = File(dir, "${dir.name}.jar")
            val hasJar = clientJar.isFile && clientJar.length() > 0L
            if (hasJar || parsed.inheritsFrom != null) {
                parsedMap[parsed.versionId] = Triple(parsed, rootObj, manifestFile)
            }
        }

        val entities = parsedMap.values.map { (parsed, _, manifestFile) ->
            val effectiveJava = resolveInheritedJavaMajorForTriples(parsed, parsedMap)
            val ownJar = File(manifestFile.parentFile, "${parsed.versionId}.jar")
            val hasOwnJar = ownJar.isFile && ownJar.length() > 0L
            val parentId = parsed.inheritsFrom
            val parentHasJar = parentId?.let { pId ->
                val parentPair = parsedMap[pId]
                val parentJar = parentPair?.third?.parentFile?.let { File(it, "$pId.jar") }
                parentPair != null && parentJar != null && parentJar.isFile && parentJar.length() > 0L
            } ?: false

            val isDiscoveredInstalled = if (parentId == null) {
                hasOwnJar
            } else {
                hasOwnJar || parentHasJar
            }

            val entity = GameVersionEntity(
                versionId = parsed.versionId,
                inheritsFrom = parsed.inheritsFrom,
                versionType = parsed.versionType,
                modLoaderType = parsed.modLoaderType,
                modLoaderVersion = parsed.modLoaderVersion,
                requiredJavaMajor = effectiveJava,
                lwjglMajor = parsed.lwjglMajor,
                manifestPath = manifestFile.absolutePath,
                isInstalled = isDiscoveredInstalled,
                releaseTimeEpochMs = parsed.releaseTimeEpochMs
            )
            gameVersionDao.upsert(entity)
            entity
        }
        return entities
    }

    private fun resolveInheritedJavaMajorForTriples(
        manifest: ParsedVersionManifest,
        map: Map<String, Triple<ParsedVersionManifest, JSONObject, File>>
    ): Int {
        if (manifest.hasJavaVersionDeclared) return manifest.requiredJavaMajor
        val visited = HashSet<String>()
        var current: ParsedVersionManifest? = manifest
        while (current != null && visited.add(current.versionId)) {
            if (current.hasJavaVersionDeclared) return current.requiredJavaMajor
            current = current.inheritsFrom?.let { map[it]?.first }
        }
        return manifest.requiredJavaMajor
    }

    /**
     * Verifies on-disk integrity of [versionId] before it may transition to [VersionState.INSTALLED].
     */
    suspend fun verifyInstallation(
        versionId: String,
        requireAssetIndex: Boolean = true,
        requireDeclaredLibraries: Boolean = true
    ): VersionVerificationResult {
        val versionsRoot = storageManager.versionsRoot()
        val versionDir = File(versionsRoot, versionId)
        val manifestFile = File(versionDir, "$versionId.json")
        if (!manifestFile.isFile || manifestFile.length() <= 0L) {
            return VersionVerificationResult(
                versionId = versionId,
                isValid = false,
                failureCategory = InstallErrorCategory.INVALID_METADATA,
                failureMessage = "Version metadata JSON is missing or empty for $versionId.",
                diagnosticDetails = "Expected readable non-empty JSON at ${manifestFile.absolutePath}"
            )
        }

        val rawJson = runCatching { manifestFile.readText() }.getOrElse { err ->
            return VersionVerificationResult(
                versionId = versionId,
                isValid = false,
                failureCategory = InstallErrorCategory.INVALID_METADATA,
                failureMessage = "Unable to read version metadata for $versionId.",
                diagnosticDetails = "${err::class.java.simpleName}: ${err.message}\nPath: ${manifestFile.absolutePath}"
            )
        }

        val root = runCatching { JSONObject(rawJson) }.getOrElse { err ->
            return VersionVerificationResult(
                versionId = versionId,
                isValid = false,
                failureCategory = InstallErrorCategory.INVALID_METADATA,
                failureMessage = "Version metadata JSON for $versionId is malformed.",
                diagnosticDetails = "${err::class.java.simpleName}: ${err.message}\nPath: ${manifestFile.absolutePath}"
            )
        }
        val parsed = runCatching { parseManifestJson(rawJson, versionId) }.getOrElse { err ->
            return VersionVerificationResult(
                versionId = versionId,
                isValid = false,
                failureCategory = InstallErrorCategory.INVALID_METADATA,
                failureMessage = "Could not parse version manifest for $versionId.",
                diagnosticDetails = "${err::class.java.simpleName}: ${err.message}"
            )
        }

        val visited = HashSet<String>()
        val chain = mutableListOf<Triple<String, JSONObject, ParsedVersionManifest>>()
        var currId: String? = versionId
        var currRoot: JSONObject? = root
        var currParsed: ParsedVersionManifest? = parsed
        while (currId != null && currRoot != null && currParsed != null) {
            if (!visited.add(currId)) {
                return VersionVerificationResult(
                    versionId = versionId,
                    isValid = false,
                    failureCategory = InstallErrorCategory.INVALID_METADATA,
                    failureMessage = "Circular version inheritance detected at $currId.",
                    diagnosticDetails = "Inheritance chain: ${visited.joinToString(" -> ")}"
                )
            }
            chain += Triple(currId, currRoot, currParsed)
            val parentId = currParsed.inheritsFrom ?: break
            val parentJsonFile = File(versionsRoot, "$parentId/$parentId.json")
            if (!parentJsonFile.isFile || parentJsonFile.length() <= 0L) {
                return VersionVerificationResult(
                    versionId = versionId,
                    isValid = false,
                    failureCategory = InstallErrorCategory.INVALID_METADATA,
                    failureMessage = "Base Minecraft version $parentId required by $versionId is missing.",
                    diagnosticDetails = "Missing parent manifest: ${parentJsonFile.absolutePath}"
                )
            }
            val parentRaw = runCatching { parentJsonFile.readText() }.getOrElse { err ->
                return VersionVerificationResult(
                    versionId = versionId,
                    isValid = false,
                    failureCategory = InstallErrorCategory.INVALID_METADATA,
                    failureMessage = "Unable to read parent metadata for $parentId.",
                    diagnosticDetails = "${err::class.java.simpleName}: ${err.message}"
                )
            }
            val parentRoot = runCatching { JSONObject(parentRaw) }.getOrNull()
                ?: return VersionVerificationResult(
                    versionId = versionId,
                    isValid = false,
                    failureCategory = InstallErrorCategory.INVALID_METADATA,
                    failureMessage = "Parent metadata JSON for $parentId is corrupted.",
                    diagnosticDetails = "Malformed JSON at ${parentJsonFile.absolutePath}"
                )
            val parentParsed = parseManifestJson(parentRaw, parentId)
            currId = parentId
            currRoot = parentRoot
            currParsed = parentParsed
        }

        val clientJarTargetId = chain.lastOrNull { it.third.hasClientDownload || it.third.inheritsFrom == null }?.first ?: versionId
        val clientJar = File(versionsRoot, "$clientJarTargetId/$clientJarTargetId.jar")
        val ownJar = File(versionDir, "$versionId.jar")
        val jarExists = (clientJar.isFile && clientJar.length() > 0L) || (ownJar.isFile && ownJar.length() > 0L)
        if (!jarExists) {
            return VersionVerificationResult(
                versionId = versionId,
                isValid = false,
                failureCategory = InstallErrorCategory.CORRUPTED_FILES,
                failureMessage = "Client JAR for $clientJarTargetId is missing or incomplete.",
                diagnosticDetails = "Expected non-empty client JAR at ${clientJar.absolutePath}",
                clientJarPresent = false
            )
        }

        val mcRoot = versionsRoot.parentFile
        val librariesRoot = File(mcRoot, "libraries")
        val missingLibraries = mutableListOf<String>()
        if (requireDeclaredLibraries) {
            for ((_, jsonObj, _) in chain) {
                val libs = jsonObj.optJSONArray("libraries") ?: continue
                for (i in 0 until libs.length()) {
                    val libObj = libs.optJSONObject(i) ?: continue
                    val name = libObj.optString("name")
                    if (name.isBlank() || name.startsWith("org.lwjgl")) continue
                    if (!shouldExpectLibraryForAndroid(libObj)) continue
                    val originalRelPath = artifactRelativePath(libObj, name)
                    val effectiveRelPath = resolveSubstitutedLibraryPath(name) ?: originalRelPath ?: continue
                    val effectiveFile = File(librariesRoot, effectiveRelPath)
                    val originalFile = originalRelPath?.takeIf { it != effectiveRelPath }?.let { File(librariesRoot, it) }
                    val present = (effectiveFile.isFile && effectiveFile.length() > 0L) ||
                        (originalFile != null && originalFile.isFile && originalFile.length() > 0L)
                    if (!present) {
                        missingLibraries += effectiveRelPath
                    }
                }
            }
        }
        if (missingLibraries.isNotEmpty()) {
            return VersionVerificationResult(
                versionId = versionId,
                isValid = false,
                failureCategory = InstallErrorCategory.MISSING_LIBRARIES,
                failureMessage = "${missingLibraries.size} required librar${if (missingLibraries.size == 1) "y is" else "ies are"} missing for $versionId.",
                diagnosticDetails = "Missing under ${librariesRoot.absolutePath}:\n" +
                    missingLibraries.take(12).joinToString("\n") +
                    if (missingLibraries.size > 12) "\n...and ${missingLibraries.size - 12} more" else "",
                clientJarPresent = true,
                missingLibrariesCount = missingLibraries.size
            )
        }

        val assetIndexId = chain.firstNotNullOfOrNull { it.third.assetIndexId }
        val assetIndexFile = assetIndexId?.let { File(mcRoot, "assets/indexes/$it.json") }
        val assetIndexOk = !requireAssetIndex || assetIndexFile == null || (assetIndexFile.isFile && assetIndexFile.length() > 0L)
        if (!assetIndexOk) {
            return VersionVerificationResult(
                versionId = versionId,
                isValid = false,
                failureCategory = InstallErrorCategory.CORRUPTED_FILES,
                failureMessage = "Asset index '$assetIndexId' is missing for $versionId.",
                diagnosticDetails = "Missing asset index at ${assetIndexFile.absolutePath}",
                clientJarPresent = true,
                assetIndexPresent = false
            )
        }

        val requiredJava = chain.firstNotNullOfOrNull { (_, _, p) ->
            if (p.hasJavaVersionDeclared) p.requiredJavaMajor else null
        } ?: parsed.requiredJavaMajor

        val existing = gameVersionDao.getById(versionId)
        val verifiedEntity = GameVersionEntity(
            versionId = versionId,
            inheritsFrom = parsed.inheritsFrom,
            versionType = existing?.versionType ?: parsed.versionType,
            modLoaderType = parsed.modLoaderType,
            modLoaderVersion = parsed.modLoaderVersion,
            requiredJavaMajor = requiredJava,
            lwjglMajor = parsed.lwjglMajor,
            manifestPath = manifestFile.absolutePath,
            isInstalled = true,
            releaseTimeEpochMs = maxOf(existing?.releaseTimeEpochMs ?: 0L, parsed.releaseTimeEpochMs)
        )
        gameVersionDao.upsert(verifiedEntity)

        return VersionVerificationResult(
            versionId = versionId,
            isValid = true,
            diskSizeBytes = computeDirectorySize(versionDir),
            installedManifestSha1 = computeSha1Hex(manifestFile),
            clientJarPresent = true,
            missingLibrariesCount = 0,
            assetIndexPresent = true,
            requiredJavaMajor = requiredJava,
            installedAt = manifestFile.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
        )
    }

    /**
     * Deletes local version files and temporary `.aetherpart` artifacts safely without touching user worlds/instances.
     */
    suspend fun deleteInstalledVersion(versionId: String) {
        val versionDir = File(storageManager.versionsRoot(), versionId)
        if (versionDir.exists()) {
            versionDir.deleteRecursively()
        }
        cleanupPartialFiles(versionId)
        val existing = gameVersionDao.getById(versionId)
        if (existing != null) {
            gameVersionDao.upsert(existing.copy(isInstalled = false))
        }
    }

    fun cleanupPartialFiles(versionId: String) {
        val mcRoot = storageManager.versionsRoot().parentFile
        val versionDir = File(storageManager.versionsRoot(), versionId)
        if (versionDir.isDirectory) {
            versionDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".aetherpart") }
                .forEach { it.delete() }
        }
        val libsDir = File(mcRoot, "libraries")
        if (libsDir.isDirectory) {
            libsDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".aetherpart") }
                .forEach { it.delete() }
        }
        val assetsDir = File(mcRoot, "assets")
        if (assetsDir.isDirectory) {
            assetsDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".aetherpart") }
                .forEach { it.delete() }
        }
    }

    fun checkUnsupportedReason(
        version: GameVersionEntity,
        installedJavaMajors: Set<Int>,
        isArm64Device: Boolean = true
    ): String? {
        if (!isArm64Device && version.requiredJavaMajor >= 21) {
            return "Java ${version.requiredJavaMajor} required by ${version.versionId} is unsupported on 32-bit ARM devices."
        }
        if (version.lwjglMajor == 2 && version.versionType.equals("old_alpha", ignoreCase = true) && !isArm64Device) {
            return null
        }
        return null
    }

    private fun shouldExpectLibraryForAndroid(libObj: JSONObject): Boolean {
        val downloads = libObj.optJSONObject("downloads")
        if (downloads != null && !downloads.has("artifact")) {
            return false
        }
        val rules = libObj.optJSONArray("rules") ?: return true
        var allowed = false
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            val action = rule.optString("action")
            val osName = rule.optJSONObject("os")?.optString("name")?.ifBlank { null }
            val matches = osName == null || osName.equals("linux", ignoreCase = true)
            if (matches) {
                allowed = action == "allow"
            }
        }
        return allowed
    }

    /**
     * Mirrors `Tools.preProcessLibraries` library substitutions used by `MinecraftDownloader`
     * so version verification checks the actual artifact path downloaded on Android.
     */
    private fun resolveSubstitutedLibraryPath(coord: String): String? {
        val parts = coord.split(":")
        val versionTokens = parts.getOrNull(2)?.split(".") ?: return null
        val major = versionTokens.getOrNull(0)?.toIntOrNull() ?: return null
        val minor = versionTokens.getOrNull(1)?.toIntOrNull()

        return when {
            coord.startsWith("net.java.dev.jna:jna:") -> {
                if (minor != null && !(major >= 5 && minor >= 13)) {
                    "net/java/dev/jna/jna/5.13.0/jna-5.13.0.jar"
                } else {
                    null
                }
            }
            coord.startsWith("com.github.oshi:oshi-core:") -> {
                if (major == 6 && minor == 2) {
                    "com/github/oshi/oshi-core/6.3.0/oshi-core-6.3.0.jar"
                } else {
                    null
                }
            }
            coord.startsWith("org.ow2.asm:asm-all:") -> {
                if (major < 5) {
                    "org/ow2/asm/asm-all/5.0.4/asm-all-5.0.4.jar"
                } else {
                    null
                }
            }
            else -> null
        }
    }

    private fun artifactRelativePath(libObj: JSONObject, coord: String): String? {
        val explicitPath = libObj.optJSONObject("downloads")
            ?.optJSONObject("artifact")
            ?.optString("path")
            ?.ifBlank { null }
        if (explicitPath != null) return explicitPath

        val parts = coord.split(":")
        if (parts.size < 3) return null
        val groupPath = parts[0].replace('.', '/')
        val artifact = parts[1]
        val versionAndExt = parts[2].split("@")
        val version = versionAndExt[0]
        val ext = versionAndExt.getOrNull(1) ?: "jar"
        val classifier = parts.getOrNull(3)?.split("@")?.getOrNull(0)?.takeIf { it.isNotBlank() }
        val fileName = if (classifier != null) "$artifact-$version-$classifier.$ext" else "$artifact-$version.$ext"
        return "$groupPath/$artifact/$version/$fileName"
    }

    private fun computeDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun resolveInheritedJavaMajor(
        manifest: ParsedVersionManifest,
        catalog: Map<String, Pair<ParsedVersionManifest, File>>
    ): Int {
        if (manifest.hasJavaVersionDeclared) return manifest.requiredJavaMajor
        val visited = HashSet<String>()
        var current: ParsedVersionManifest? = manifest
        while (current != null && visited.add(current.versionId)) {
            if (current.hasJavaVersionDeclared) return current.requiredJavaMajor
            val parentId = current.inheritsFrom ?: break
            current = catalog[parentId]?.first
        }
        return manifest.requiredJavaMajor
    }

    companion object {
        fun parseManifestJson(jsonText: String, fallbackVersionId: String): ParsedVersionManifest {
            val root = JSONObject(jsonText)
            val id = root.optString("id").ifBlank { fallbackVersionId }
            val inheritsFrom = root.optString("inheritsFrom").ifBlank { null }
            val versionType = root.optString("type").ifBlank {
                if (inheritsFrom != null) "modded" else "release"
            }

            val javaObj = root.optJSONObject("javaVersion")
            val declaredJava = javaObj?.optInt("majorVersion", 0) ?: 0
            val hasJavaDeclared = declaredJava > 0

            val libraries = root.optJSONArray("libraries")
            val libNames = buildList {
                if (libraries != null) {
                    for (i in 0 until libraries.length()) {
                        val name = libraries.optJSONObject(i)?.optString("name").orEmpty()
                        if (name.isNotBlank()) add(name)
                    }
                }
            }
            val mainClass = root.optString("mainClass")
            val gameArguments = extractGameArguments(root)

            val (loaderType, loaderVer) = detectModLoader(id, mainClass, libNames, gameArguments, root)
            val detectedMcVersion = extractDetectedMinecraftVersion(
                root = root,
                id = id,
                inheritsFrom = inheritsFrom,
                loaderType = loaderType,
                loaderVersion = loaderVer,
                libraries = libNames,
                gameArguments = gameArguments
            )
            val lwjglMajor = detectLwjglMajor(root, libNames, inheritsFrom)
            val releaseTimeMs = parseReleaseTimeMillis(root.optString("releaseTime"))
            val requiredJava = if (hasJavaDeclared) {
                declaredJava
            } else {
                inferDefaultJavaMajor(detectedMcVersion ?: inheritsFrom ?: id, loaderType)
            }

            val hasClientDownload = root.optJSONObject("downloads")?.has("client") == true
            val assetIndexId = root.optJSONObject("assetIndex")?.optString("id")?.ifBlank { null }
                ?: root.optString("assets").ifBlank { null }

            return ParsedVersionManifest(
                versionId = id,
                inheritsFrom = inheritsFrom,
                versionType = versionType,
                modLoaderType = loaderType,
                modLoaderVersion = loaderVer,
                requiredJavaMajor = requiredJava,
                lwjglMajor = lwjglMajor,
                releaseTimeEpochMs = releaseTimeMs,
                hasJavaVersionDeclared = hasJavaDeclared,
                hasClientDownload = hasClientDownload,
                assetIndexId = assetIndexId,
                detectedMinecraftVersion = detectedMcVersion
            )
        }

        private fun extractGameArguments(root: JSONObject): List<String> {
            val result = mutableListOf<String>()
            val argsObj = root.optJSONObject("arguments")
            val gameArr = argsObj?.optJSONArray("game")
            if (gameArr != null) {
                for (i in 0 until gameArr.length()) {
                    val item = gameArr.opt(i)
                    if (item is String && item.isNotBlank()) {
                        result.add(item.trim())
                    }
                }
            }
            val legacyArgs = root.optString("minecraftArguments").trim()
            if (legacyArgs.isNotEmpty()) {
                result.addAll(legacyArgs.split(Regex("\\s+")).filter { it.isNotBlank() })
            }
            return result
        }

        private fun findArgValue(args: List<String>, flag: String): String? {
            for (i in 0 until args.size - 1) {
                if (args[i].equals(flag, ignoreCase = true)) {
                    val value = args[i + 1].trim()
                    if (value.isNotBlank() && !value.startsWith("--")) {
                        return value
                    }
                }
            }
            return null
        }

        fun computeSha1Hex(file: File): String? {
            if (!file.isFile || !file.canRead()) return null
            return runCatching {
                val digest = MessageDigest.getInstance("SHA-1")
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }

        fun detectModLoader(
            versionId: String,
            mainClass: String,
            libraries: List<String>,
            gameArguments: List<String> = emptyList(),
            rootJson: JSONObject? = null
        ): Pair<ModLoaderType, String?> {
            val lowerId = versionId.lowercase()
            val lowerMain = mainClass.lowercase()

            // 1. Check explicit loader metadata object if present in manifest
            val loaderMeta = rootJson?.optJSONObject("starLoaderMetadata") ?: rootJson?.optJSONObject("loader")
            if (loaderMeta != null) {
                val metaTypeRaw = loaderMeta.optString("loaderType").ifBlank { loaderMeta.optString("type") }.lowercase()
                val metaVer = loaderMeta.optString("loaderVersion").ifBlank { loaderMeta.optString("version") }.ifBlank { null }
                when (metaTypeRaw) {
                    "neoforge" -> return ModLoaderType.NEOFORGE to (metaVer ?: extractNeoForgeVersion(libraries, gameArguments, versionId))
                    "forge" -> return ModLoaderType.FORGE to (metaVer ?: extractForgeVersion(libraries, gameArguments, versionId))
                    "fabric" -> return ModLoaderType.FABRIC to (metaVer ?: extractVersionSegment(libraries, "net.fabricmc:fabric-loader"))
                    "quilt" -> return ModLoaderType.QUILT to (metaVer ?: extractVersionSegment(libraries, "org.quiltmc:quilt-loader"))
                }
            }

            // 2. Check FML / ModLauncher / TweakClass game arguments (authoritative for Forge & NeoForge)
            val neoForgeArgVer = findArgValue(gameArguments, "--fml.neoForgeVersion")
            val forgeArgVer = findArgValue(gameArguments, "--fml.forgeVersion")
            val forgeGroupArg = findArgValue(gameArguments, "--fml.forgeGroup")?.lowercase()
            val launchTargetArg = findArgValue(gameArguments, "--launchTarget")?.lowercase()
            val tweakClassArgs = buildList {
                for (i in 0 until gameArguments.size - 1) {
                    if (gameArguments[i].equals("--tweakClass", ignoreCase = true)) {
                        add(gameArguments[i + 1].lowercase())
                    }
                }
            }

            if (libraries.any { it.contains("lwjgl3ify", ignoreCase = true) } || lowerId.contains("lwjgl3ify")) {
                return ModLoaderType.LWJGL3IFY to extractVersionSegment(libraries, "lwjgl3ify")
            }

            // Quilt check (must precede Fabric since Quilt profiles also include net.fabricmc:intermediary)
            if (lowerMain.contains("quiltmc") ||
                libraries.any { it.contains("org.quiltmc:quilt-loader", ignoreCase = true) } ||
                tweakClassArgs.any { it.contains("quiltmc") } ||
                lowerId.contains("quilt")
            ) {
                val ver = extractVersionSegment(libraries, "org.quiltmc:quilt-loader")
                    ?: extractVersionSegment(libraries, "quilt-loader")
                    ?: Regex("""^quilt-loader-([^-]+)-""", RegexOption.IGNORE_CASE).find(versionId)?.groupValues?.get(1)
                return ModLoaderType.QUILT to ver
            }

            // Fabric check
            if (lowerMain.contains("fabricmc") ||
                libraries.any { it.contains("net.fabricmc:fabric-loader", ignoreCase = true) } ||
                tweakClassArgs.any { it.contains("fabricmc") } ||
                lowerId.contains("fabric")
            ) {
                val ver = extractVersionSegment(libraries, "net.fabricmc:fabric-loader")
                    ?: extractVersionSegment(libraries, "fabric-loader")
                    ?: Regex("""^fabric-loader-([^-]+)-""", RegexOption.IGNORE_CASE).find(versionId)?.groupValues?.get(1)
                return ModLoaderType.FABRIC to ver
            }

            // NeoForge check
            if (neoForgeArgVer != null ||
                forgeGroupArg == "net.neoforged" ||
                launchTargetArg?.contains("neoforge") == true ||
                lowerMain.contains("neoforged") ||
                libraries.any { it.startsWith("net.neoforged:", ignoreCase = true) || it.startsWith("net.neoforged.", ignoreCase = true) } ||
                lowerId.contains("neoforge")
            ) {
                return ModLoaderType.NEOFORGE to extractNeoForgeVersion(libraries, gameArguments, versionId)
            }

            // Forge check
            val hasForgeLibrary = libraries.any {
                it.startsWith("net.minecraftforge:forge:", ignoreCase = true) ||
                    it.startsWith("net.minecraftforge:fmlloader:", ignoreCase = true) ||
                    it.startsWith("net.minecraftforge:fmlcore:", ignoreCase = true) ||
                    it.startsWith("net.minecraftforge:javafmllanguage:", ignoreCase = true) ||
                    it.startsWith("net.minecraftforge:minecraftforge:", ignoreCase = true)
            }
            if (forgeArgVer != null ||
                forgeGroupArg == "net.minecraftforge" ||
                launchTargetArg?.startsWith("forge") == true ||
                lowerMain.contains("minecraftforge") ||
                tweakClassArgs.any { it.contains("minecraftforge") || it.contains("fmltweaker") } ||
                hasForgeLibrary ||
                lowerId.contains("forge")
            ) {
                return ModLoaderType.FORGE to extractForgeVersion(libraries, gameArguments, versionId)
            }

            return ModLoaderType.VANILLA to null
        }

        private fun extractNeoForgeVersion(
            libraries: List<String>,
            gameArguments: List<String>,
            versionId: String
        ): String? {
            findArgValue(gameArguments, "--fml.neoForgeVersion")?.let { return it }
            extractVersionSegment(libraries, "net.neoforged:neoforge:")?.let { return it }
            extractVersionSegment(libraries, "net.neoforged:forge:")?.let { coordVer ->
                return coordVer.substringAfter('-', coordVer)
            }
            Regex("""^neoforge-(.+)$""", RegexOption.IGNORE_CASE).find(versionId)?.groupValues?.get(1)?.let { return it }
            return extractVersionSegment(libraries, "net.neoforged")
        }

        private fun extractForgeVersion(
            libraries: List<String>,
            gameArguments: List<String>,
            versionId: String
        ): String? {
            findArgValue(gameArguments, "--fml.forgeVersion")?.let { return it }
            val forgeCoord = extractVersionSegment(libraries, "net.minecraftforge:forge:")
                ?: extractVersionSegment(libraries, "net.minecraftforge:fmlloader:")
                ?: extractVersionSegment(libraries, "net.minecraftforge:fmlcore:")
                ?: extractVersionSegment(libraries, "forge")
            if (forgeCoord != null) return forgeCoord
            return Regex("""^\d+\.\d+(?:\.\d+)?-forge-(.+)$""", RegexOption.IGNORE_CASE)
                .find(versionId)?.groupValues?.get(1)
        }

        private fun extractDetectedMinecraftVersion(
            root: JSONObject,
            id: String,
            inheritsFrom: String?,
            loaderType: ModLoaderType,
            loaderVersion: String?,
            libraries: List<String>,
            gameArguments: List<String>
        ): String? {
            val mcRegex = Regex("""^\d+\.\d+(?:\.\d+)?$""")
            if (!inheritsFrom.isNullOrBlank() && mcRegex.matches(inheritsFrom.trim())) {
                return inheritsFrom.trim()
            }
            findArgValue(gameArguments, "--fml.mcVersion")?.takeIf { it.isNotBlank() }?.let { return it }
            val loaderMeta = root.optJSONObject("starLoaderMetadata") ?: root.optJSONObject("loader")
            loaderMeta?.optString("minecraftVersion")?.takeIf { it.isNotBlank() }?.let { return it }
            root.optString("minecraftVersion").takeIf { it.isNotBlank() }?.let { return it }
            root.optString("clientVersion").takeIf { it.isNotBlank() }?.let { return it }

            extractVersionSegment(libraries, "net.fabricmc:intermediary:")?.let { return it }
            extractVersionSegment(libraries, "org.quiltmc:hashed:")?.let { return it }
            extractVersionSegment(libraries, "net.minecraft:client:")?.let { clientVer ->
                val clean = clientVer.substringBefore('-')
                if (mcRegex.matches(clean)) return clean
            }
            val forgeLibVer = extractVersionSegment(libraries, "net.minecraftforge:forge:")
                ?: extractVersionSegment(libraries, "net.minecraftforge:fmlloader:")
                ?: extractVersionSegment(libraries, "net.neoforged:forge:")
            if (forgeLibVer != null && forgeLibVer.contains('-')) {
                val candidateMc = forgeLibVer.substringBefore('-')
                if (mcRegex.matches(candidateMc)) return candidateMc
            }
            if (!inheritsFrom.isNullOrBlank()) return inheritsFrom.trim()
            if (loaderType == ModLoaderType.NEOFORGE && !loaderVersion.isNullOrBlank()) {
                val parts = loaderVersion.split(".")
                val major = parts.getOrNull(0)?.toIntOrNull()
                val minor = parts.getOrNull(1)?.toIntOrNull()
                if (major != null && minor != null && major >= 20) {
                    return if (minor == 0) "1.$major" else "1.$major.$minor"
                }
            }
            return if (loaderType == ModLoaderType.VANILLA) id.takeIf { it.isNotBlank() } else null
        }

        private fun extractVersionSegment(libraries: List<String>, needle: String): String? {
            val coord = libraries.firstOrNull { it.contains(needle, ignoreCase = true) } ?: return null
            return coord.split(":").getOrNull(2)?.substringBefore('@')?.trim()?.takeIf { it.isNotEmpty() }
        }

        private fun detectLwjglMajor(
            root: JSONObject,
            libraries: List<String>,
            inheritsFrom: String?
        ): Int {
            if (libraries.any { it.contains("org.lwjgl:lwjgl:3.") || it.contains("lwjgl3ify") }) {
                return 3
            }
            if (root.has("arguments")) return 3
            if (root.has("minecraftArguments") && inheritsFrom == null) return 2
            return 3
        }

        private fun inferDefaultJavaMajor(mcVersionHint: String, loaderType: ModLoaderType): Int {
            if (loaderType == ModLoaderType.LWJGL3IFY) return 17
            val match = Regex("""1\.(\d+)(?:\.(\d+))?""").find(mcVersionHint) ?: return 8
            val minor = match.groupValues[1].toIntOrNull() ?: return 8
            val patch = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            return when {
                minor <= 16 -> 8
                minor == 17 -> 17
                minor in 18..19 -> 17
                minor == 20 && patch <= 4 -> 17
                else -> 21
            }
        }

        private fun parseReleaseTimeMillis(isoString: String): Long {
            if (isoString.isBlank()) return 0L
            return runCatching { Instant.parse(isoString).toEpochMilli() }.getOrDefault(0L)
        }
    }
}
