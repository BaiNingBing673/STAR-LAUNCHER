package com.star.launcher.mods.shaders

import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.star.launcher.mods.compat.InstanceEnvironment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kdt.pojavlaunch.Tools
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

enum class ShaderInstallStatus(val label: String) {
    ENABLED("Enabled"),
    DISABLED("Disabled"),
    CORRUPTED("Corrupted"),
    INVALID("Invalid")
}

enum class ShaderCompatibilityLevel(val label: String) {
    COMPATIBLE("Compatible"),
    WARNING("Caution"),
    INCOMPATIBLE("Incompatible"),
    UNKNOWN("Unverified")
}

data class ShaderPackValidationResult(
    val isValid: Boolean,
    val isCorrupted: Boolean = false,
    val isOrdinaryModArchive: Boolean = false,
    val hasUnsafePath: Boolean = false,
    val shaderFileCount: Int = 0,
    val hasComputeShaders: Boolean = false,
    val hasShadersProperties: Boolean = false,
    val formatLabel: String = "Shader Pack",
    val errorMessage: String? = null
)

data class ShaderPackEntry(
    val fileName: String,
    val displayName: String,
    val filePath: String,
    val isDirectory: Boolean,
    val isEnabled: Boolean,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val status: ShaderInstallStatus,
    val formatLabel: String,
    val mcVersionHint: String?,
    val rendererCompatibilityLabel: String,
    val compatibilityLevel: ShaderCompatibilityLevel,
    val warnings: List<String>
)

data class ShaderPackInventoryReport(
    val instanceId: String,
    val instanceName: String,
    val shaderpacksDirPath: String,
    val mcVersion: String?,
    val activeRendererId: String?,
    val activeRendererLabel: String,
    val hasShaderModInstalled: Boolean,
    val detectedShaderModName: String?,
    val rendererCompatibilityLevel: ShaderCompatibilityLevel,
    val rendererGuidance: String,
    val packs: List<ShaderPackEntry>,
    val nonShaderFiles: List<String>
)

data class ShaderImportFailure(
    val fileName: String,
    val reason: String
)

data class ShaderImportBatchResult(
    val imported: List<String>,
    val skippedDuplicates: List<String>,
    val failures: List<ShaderImportFailure>
)

/**
 * Dedicated shader pack manager scoped strictly to `<gameDirPath>/shaderpacks/`.
 *
 * Guarantees:
 * - Never writes shader packs into `<gameDirPath>/mods/`.
 * - Validates ZIP archives before moving into `shaderpacks/` (detecting corrupted files,
 *   ZIP Slip path traversal, non-shader archives, and ordinary Fabric/Forge/NeoForge mod JARs).
 * - Preserves existing shader files and sidecar `.txt` settings files during toggles and imports.
 */
class ShaderPackManager(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun inspectShaderPacks(
        environment: InstanceEnvironment,
        activeRendererId: String?,
        activeRendererLabel: String?
    ): ShaderPackInventoryReport = withContext(ioDispatcher) {
        val rootDir = File(environment.gameDirPath)
        val shaderDir = File(rootDir, SHADERPACKS_DIR_NAME)
        val resolvedRendererLabel = activeRendererLabel?.takeIf { it.isNotBlank() }
            ?: friendlyRendererName(activeRendererId)
        val detectedShaderMod = detectInstalledShaderMod(File(rootDir, "mods"))
        val (rendererLevel, rendererGuidance) = evaluateRendererForShaders(
            rendererId = activeRendererId,
            mcVersion = environment.baseMinecraftVersion,
            detectedShaderMod = detectedShaderMod
        )

        if (!shaderDir.isDirectory) {
            return@withContext ShaderPackInventoryReport(
                instanceId = environment.instanceId,
                instanceName = environment.instanceName,
                shaderpacksDirPath = shaderDir.absolutePath,
                mcVersion = environment.baseMinecraftVersion,
                activeRendererId = activeRendererId,
                activeRendererLabel = resolvedRendererLabel,
                hasShaderModInstalled = detectedShaderMod != null,
                detectedShaderModName = detectedShaderMod,
                rendererCompatibilityLevel = rendererLevel,
                rendererGuidance = rendererGuidance,
                packs = emptyList(),
                nonShaderFiles = emptyList()
            )
        }

        val allEntries = shaderDir.listFiles()
            ?.filter { !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }
            .orEmpty()

        val nonShaderFiles = mutableListOf<String>()
        val candidateEntries = mutableListOf<File>()
        for (entry in allEntries) {
            val lower = entry.name.lowercase(Locale.ROOT)
            when {
                // Iris / OptiFine shader settings sidecar files (e.g. Complementary.zip.txt) - preserve silently
                entry.isFile && (lower.endsWith(".zip.txt") || lower.endsWith(".txt") || lower.endsWith(".properties")) -> {
                    continue
                }
                entry.isFile && (lower.endsWith(".zip") || lower.endsWith(".zip.disabled")) -> {
                    candidateEntries += entry
                }
                entry.isDirectory -> {
                    candidateEntries += entry
                }
                else -> {
                    nonShaderFiles += entry.name
                }
            }
        }

        val baseNameCounts = candidateEntries
            .map { normalizeBaseShaderName(it.name).lowercase(Locale.ROOT) }
            .groupingBy { it }
            .eachCount()

        val packs = candidateEntries.map { file ->
            val enabled = !file.name.endsWith(DISABLED_SUFFIX, ignoreCase = true)
            val baseName = normalizeBaseShaderName(file.name)
            val displayName = baseName.removeSuffix(".zip").removeSuffix(".ZIP")
            val validation = if (file.isDirectory) {
                validateShaderDirectory(file)
            } else {
                validateShaderZip(file)
            }

            val warnings = mutableListOf<String>()
            if (!validation.isValid && validation.errorMessage != null) {
                warnings += validation.errorMessage
            }
            if ((baseNameCounts[baseName.lowercase(Locale.ROOT)] ?: 0) > 1) {
                warnings += "Duplicate shader pack detected (both enabled and disabled copies exist)."
            }
            if (detectedShaderMod == null) {
                warnings += "No Iris, Oculus, or OptiFine shader loader detected in mods/."
            }
            if (validation.hasComputeShaders && isLowComputeRenderer(activeRendererId)) {
                warnings += "Uses compute shaders (.csh), which may require Desktop OpenGL / MobileGlues GLES 3.2+."
            }

            val status = when {
                validation.isCorrupted -> ShaderInstallStatus.CORRUPTED
                !validation.isValid -> ShaderInstallStatus.INVALID
                enabled -> ShaderInstallStatus.ENABLED
                else -> ShaderInstallStatus.DISABLED
            }

            val (packCompatLevel, packCompatLabel) = combinePackCompatibility(
                validation = validation,
                rendererLevel = rendererLevel,
                rendererLabel = resolvedRendererLabel,
                detectedShaderMod = detectedShaderMod,
                activeRendererId = activeRendererId
            )

            ShaderPackEntry(
                fileName = file.name,
                displayName = displayName,
                filePath = file.absolutePath,
                isDirectory = file.isDirectory,
                isEnabled = enabled && validation.isValid,
                sizeBytes = if (file.isDirectory) computeDirectorySize(file) else file.length(),
                lastModifiedMs = file.lastModified(),
                status = status,
                formatLabel = validation.formatLabel,
                mcVersionHint = environment.baseMinecraftVersion,
                rendererCompatibilityLabel = packCompatLabel,
                compatibilityLevel = packCompatLevel,
                warnings = warnings
            )
        }

        ShaderPackInventoryReport(
            instanceId = environment.instanceId,
            instanceName = environment.instanceName,
            shaderpacksDirPath = shaderDir.absolutePath,
            mcVersion = environment.baseMinecraftVersion,
            activeRendererId = activeRendererId,
            activeRendererLabel = resolvedRendererLabel,
            hasShaderModInstalled = detectedShaderMod != null,
            detectedShaderModName = detectedShaderMod,
            rendererCompatibilityLevel = rendererLevel,
            rendererGuidance = rendererGuidance,
            packs = packs,
            nonShaderFiles = nonShaderFiles
        )
    }

    suspend fun importShaderPacksFromUris(
        context: Context,
        gameDirPath: String,
        uris: List<Uri>
    ): ShaderImportBatchResult = withContext(ioDispatcher) {
        val shaderDir = File(gameDirPath, SHADERPACKS_DIR_NAME)
        if (!shaderDir.exists() && !shaderDir.mkdirs()) {
            return@withContext ShaderImportBatchResult(
                imported = emptyList(),
                skippedDuplicates = emptyList(),
                failures = listOf(
                    ShaderImportFailure(
                        fileName = SHADERPACKS_DIR_NAME,
                        reason = "Unable to create shaderpacks directory at ${shaderDir.absolutePath}."
                    )
                )
            )
        }

        val imported = mutableListOf<String>()
        val duplicates = mutableListOf<String>()
        val failures = mutableListOf<ShaderImportFailure>()

        for (uri in uris) {
            val rawName = sanitizeImportFileName(Tools.getFileName(context, uri))
            val displayName = rawName.ifBlank { "shaderpack_${System.currentTimeMillis()}.zip" }
            val result = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    importShaderPackFromStream(
                        shaderDir = shaderDir,
                        rawFileName = displayName,
                        inputStream = stream
                    )
                } ?: SingleImportOutcome.Failed(displayName, "Cannot open selected file from storage.")
            }.getOrElse { err ->
                SingleImportOutcome.Failed(
                    displayName,
                    err.message ?: "Unexpected error importing shader pack."
                )
            }

            when (result) {
                is SingleImportOutcome.Imported -> imported += result.fileName
                is SingleImportOutcome.Duplicate -> duplicates += result.fileName
                is SingleImportOutcome.Failed -> failures += ShaderImportFailure(result.fileName, result.reason)
            }
        }

        ShaderImportBatchResult(
            imported = imported,
            skippedDuplicates = duplicates,
            failures = failures
        )
    }

    fun importShaderPackFromFile(
        gameDirPath: String,
        sourceFile: File
    ): ShaderImportBatchResult {
        val shaderDir = File(gameDirPath, SHADERPACKS_DIR_NAME)
        if (!shaderDir.exists() && !shaderDir.mkdirs()) {
            return ShaderImportBatchResult(
                imported = emptyList(),
                skippedDuplicates = emptyList(),
                failures = listOf(ShaderImportFailure(sourceFile.name, "Unable to create shaderpacks directory."))
            )
        }
        if (!sourceFile.isFile) {
            return ShaderImportBatchResult(
                imported = emptyList(),
                skippedDuplicates = emptyList(),
                failures = listOf(ShaderImportFailure(sourceFile.name, "Selected shader file does not exist."))
            )
        }
        val outcome = sourceFile.inputStream().use { stream ->
            importShaderPackFromStream(shaderDir, sourceFile.name, stream)
        }
        return when (outcome) {
            is SingleImportOutcome.Imported -> ShaderImportBatchResult(listOf(outcome.fileName), emptyList(), emptyList())
            is SingleImportOutcome.Duplicate -> ShaderImportBatchResult(emptyList(), listOf(outcome.fileName), emptyList())
            is SingleImportOutcome.Failed -> ShaderImportBatchResult(emptyList(), emptyList(), listOf(ShaderImportFailure(outcome.fileName, outcome.reason)))
        }
    }

    private sealed interface SingleImportOutcome {
        data class Imported(val fileName: String) : SingleImportOutcome
        data class Duplicate(val fileName: String) : SingleImportOutcome
        data class Failed(val fileName: String, val reason: String) : SingleImportOutcome
    }

    private fun importShaderPackFromStream(
        shaderDir: File,
        rawFileName: String,
        inputStream: InputStream
    ): SingleImportOutcome {
        val cleanName = sanitizeImportFileName(rawFileName)
        if (cleanName.isBlank()) {
            return SingleImportOutcome.Failed(rawFileName, "Invalid shader pack filename.")
        }
        val lower = cleanName.lowercase(Locale.ROOT)
        if (lower.endsWith(".jar") || lower.endsWith(".jar.disabled")) {
            return SingleImportOutcome.Failed(
                cleanName,
                "'.jar' files are Minecraft mods, not shader packs. Install mods in the Mods tab instead."
            )
        }
        val finalName = if (lower.endsWith(".zip")) cleanName else "$cleanName.zip"
        val enabledTarget = File(shaderDir, finalName)
        val disabledTarget = File(shaderDir, "$finalName$DISABLED_SUFFIX")
        if (enabledTarget.exists() || disabledTarget.exists()) {
            return SingleImportOutcome.Duplicate(finalName)
        }

        val tempFile = File(shaderDir, ".shader_import_${UUID.randomUUID()}.tmp")
        try {
            var totalBytes = 0L
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            val header = ByteArray(4)
            var headerFilled = 0
            FileOutputStream(tempFile).use { out ->
                while (true) {
                    val read = inputStream.read(buffer)
                    if (read <= 0) break
                    if (headerFilled < 4) {
                        val copyLen = minOf(4 - headerFilled, read)
                        System.arraycopy(buffer, 0, header, headerFilled, copyLen)
                        headerFilled += copyLen
                    }
                    totalBytes += read
                    out.write(buffer, 0, read)
                }
                out.flush()
            }

            if (totalBytes <= 0L) {
                return SingleImportOutcome.Failed(finalName, "File is empty (0 bytes).")
            }
            if (headerFilled < 4 ||
                header[0] != 0x50.toByte() ||
                header[1] != 0x4B.toByte() ||
                (header[2] != 0x03.toByte() && header[2] != 0x05.toByte() && header[2] != 0x07.toByte())
            ) {
                return SingleImportOutcome.Failed(
                    finalName,
                    "Not a valid .zip shader archive (missing ZIP header)."
                )
            }

            val validation = validateShaderZip(tempFile)
            if (!validation.isValid) {
                return SingleImportOutcome.Failed(
                    finalName,
                    validation.errorMessage ?: "Archive is not a valid Minecraft shader pack."
                )
            }

            // Ensure target path stays strictly inside shaderpacks/
            val canonicalDir = shaderDir.canonicalPath
            val canonicalTarget = enabledTarget.canonicalPath
            val dirPrefix = if (canonicalDir.endsWith(File.separator)) canonicalDir else "$canonicalDir${File.separator}"
            if (!canonicalTarget.startsWith(dirPrefix)) {
                return SingleImportOutcome.Failed(finalName, "Blocked unsafe target filename.")
            }

            if (!tempFile.renameTo(enabledTarget)) {
                tempFile.copyTo(enabledTarget, overwrite = false)
                tempFile.delete()
            }
            return SingleImportOutcome.Imported(finalName)
        } catch (e: IOException) {
            return SingleImportOutcome.Failed(
                finalName,
                if (isInsufficientSpaceError(e, shaderDir)) {
                    "Insufficient storage space to import '$finalName'."
                } else {
                    "Failed to import '$finalName': ${e.message ?: "I/O error"}"
                }
            )
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    suspend fun toggleShaderEnabled(
        gameDirPath: String,
        shaderFileName: String
    ): Result<Boolean> = withContext(ioDispatcher) {
        if (!isSafeSingleSegmentName(shaderFileName)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid shader pack name: $shaderFileName"))
        }
        val shaderDir = File(gameDirPath, SHADERPACKS_DIR_NAME)
        val source = File(shaderDir, shaderFileName)
        if (!source.exists()) {
            return@withContext Result.failure(IllegalStateException("Shader pack '$shaderFileName' no longer exists."))
        }
        val currentlyDisabled = shaderFileName.endsWith(DISABLED_SUFFIX, ignoreCase = true)
        val targetName = if (currentlyDisabled) {
            shaderFileName.substring(0, shaderFileName.length - DISABLED_SUFFIX.length)
        } else {
            "$shaderFileName$DISABLED_SUFFIX"
        }
        val target = File(shaderDir, targetName)
        if (target.exists()) {
            return@withContext Result.failure(
                IllegalStateException("Cannot toggle: '$targetName' already exists in shaderpacks/.")
            )
        }
        if (!source.renameTo(target)) {
            return@withContext Result.failure(
                IllegalStateException("Failed to rename '$shaderFileName' to '$targetName'.")
            )
        }
        Result.success(currentlyDisabled)
    }

    suspend fun setShadersEnabled(
        gameDirPath: String,
        shaderFileNames: Collection<String>,
        enable: Boolean
    ): Result<Int> = withContext(ioDispatcher) {
        var changed = 0
        for (name in shaderFileNames) {
            val isCurrentlyEnabled = !name.endsWith(DISABLED_SUFFIX, ignoreCase = true)
            if (isCurrentlyEnabled == enable) continue
            val res = toggleShaderEnabled(gameDirPath, name)
            if (res.isSuccess) changed++
        }
        Result.success(changed)
    }

    suspend fun removeShader(
        gameDirPath: String,
        shaderFileName: String
    ): Result<Unit> = withContext(ioDispatcher) {
        if (!isSafeSingleSegmentName(shaderFileName)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid shader pack name: $shaderFileName"))
        }
        val shaderDir = File(gameDirPath, SHADERPACKS_DIR_NAME)
        val target = File(shaderDir, shaderFileName)
        if (!target.exists()) {
            return@withContext Result.success(Unit)
        }
        val canonicalDir = shaderDir.canonicalPath
        val canonicalTarget = target.canonicalPath
        val prefix = if (canonicalDir.endsWith(File.separator)) canonicalDir else "$canonicalDir${File.separator}"
        if (!canonicalTarget.startsWith(prefix)) {
            return@withContext Result.failure(SecurityException("Blocked unsafe path deletion: $shaderFileName"))
        }
        val deleted = if (target.isDirectory) target.deleteRecursively() else target.delete()
        if (!deleted) {
            return@withContext Result.failure(IOException("Unable to delete '$shaderFileName'."))
        }
        Result.success(Unit)
    }

    suspend fun removeShaders(
        gameDirPath: String,
        shaderFileNames: Collection<String>
    ): Result<Int> = withContext(ioDispatcher) {
        var removed = 0
        var lastError: Throwable? = null
        for (name in shaderFileNames) {
            val res = removeShader(gameDirPath, name)
            if (res.isSuccess) {
                removed++
            } else {
                lastError = res.exceptionOrNull()
            }
        }
        if (removed == 0 && lastError != null) {
            Result.failure(lastError)
        } else {
            Result.success(removed)
        }
    }

    fun validateShaderZip(file: File): ShaderPackValidationResult {
        if (!file.isFile || file.length() <= 0L) {
            return ShaderPackValidationResult(
                isValid = false,
                isCorrupted = true,
                errorMessage = "Shader pack file is empty (0 bytes) or unreadable."
            )
        }
        return try {
            ZipFile(file).use { zip ->
                var hasShadersDir = false
                var shaderFileCount = 0
                var hasCompute = false
                var hasProperties = false
                var hasModMetadata = false
                var hasClassFiles = false
                var entryCount = 0

                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    entryCount++
                    val rawName = entry.name
                    if (isUnsafeZipEntryPath(rawName)) {
                        return ShaderPackValidationResult(
                            isValid = false,
                            hasUnsafePath = true,
                            errorMessage = "Blocked unsafe archive entry path: '$rawName'."
                        )
                    }
                    val normalized = rawName.replace('\\', '/').trimStart('/')
                    val lower = normalized.lowercase(Locale.ROOT)
                    val segments = lower.split('/')

                    if (lower == "fabric.mod.json" ||
                        lower == "quilt.mod.json" ||
                        lower == "meta-inf/mods.toml" ||
                        lower == "meta-inf/neoforge.mods.toml" ||
                        lower == "mcmod.info"
                    ) {
                        hasModMetadata = true
                    }
                    if (lower.endsWith(".class")) {
                        hasClassFiles = true
                    }
                    if (segments.contains("shaders")) {
                        hasShadersDir = true
                    }
                    if (!entry.isDirectory) {
                        if (lower.endsWith("shaders.properties")) {
                            hasProperties = true
                        }
                        if (SHADER_EXTENSIONS.any { lower.endsWith(it) }) {
                            shaderFileCount++
                            if (lower.endsWith(".csh")) {
                                hasCompute = true
                            }
                        }
                    }
                }

                when {
                    entryCount == 0 -> ShaderPackValidationResult(
                        isValid = false,
                        isCorrupted = true,
                        errorMessage = "ZIP archive contains no files."
                    )
                    hasModMetadata && !hasShadersDir -> ShaderPackValidationResult(
                        isValid = false,
                        isOrdinaryModArchive = true,
                        errorMessage = "This archive is a Minecraft mod (Fabric/Forge/NeoForge), not a shader pack. Install it in Mods instead."
                    )
                    hasClassFiles && shaderFileCount == 0 && !hasShadersDir -> ShaderPackValidationResult(
                        isValid = false,
                        isOrdinaryModArchive = true,
                        errorMessage = "This archive contains compiled Java classes instead of shader files."
                    )
                    !hasShadersDir && shaderFileCount == 0 && !hasProperties -> ShaderPackValidationResult(
                        isValid = false,
                        errorMessage = "Not a valid shader pack: missing 'shaders/' folder or GLSL shader files (.vsh/.fsh)."
                    )
                    else -> {
                        val format = when {
                            hasCompute -> "Iris / OptiFine GLSL (Compute)"
                            hasProperties -> "Iris / OptiFine Shader Pack"
                            else -> "GLSL Shader Pack"
                        }
                        ShaderPackValidationResult(
                            isValid = true,
                            shaderFileCount = shaderFileCount,
                            hasComputeShaders = hasCompute,
                            hasShadersProperties = hasProperties,
                            formatLabel = format
                        )
                    }
                }
            }
        } catch (e: Exception) {
            ShaderPackValidationResult(
                isValid = false,
                isCorrupted = true,
                errorMessage = "Corrupted or unreadable ZIP archive: ${e.message ?: e::class.java.simpleName}"
            )
        }
    }

    private fun validateShaderDirectory(dir: File): ShaderPackValidationResult {
        val shadersSub = File(dir, "shaders")
        if (!shadersSub.isDirectory) {
            return ShaderPackValidationResult(
                isValid = false,
                errorMessage = "Folder does not contain a 'shaders/' directory."
            )
        }
        var shaderFiles = 0
        var hasCompute = false
        var hasProps = false
        shadersSub.walkTopDown().maxDepth(4).forEach { f ->
            if (f.isFile) {
                val lower = f.name.lowercase(Locale.ROOT)
                if (lower == "shaders.properties") hasProps = true
                if (SHADER_EXTENSIONS.any { lower.endsWith(it) }) {
                    shaderFiles++
                    if (lower.endsWith(".csh")) hasCompute = true
                }
            }
        }
        return if (shaderFiles > 0 || hasProps) {
            ShaderPackValidationResult(
                isValid = true,
                shaderFileCount = shaderFiles,
                hasComputeShaders = hasCompute,
                hasShadersProperties = hasProps,
                formatLabel = "Extracted Shader Folder"
            )
        } else {
            ShaderPackValidationResult(
                isValid = false,
                errorMessage = "Folder 'shaders/' contains no GLSL shader files."
            )
        }
    }

    private fun detectInstalledShaderMod(modsDir: File): String? {
        if (!modsDir.isDirectory) return null
        val files = modsDir.listFiles { f ->
            f.isFile && f.name.endsWith(".jar", ignoreCase = true)
        }.orEmpty()
        for (file in files) {
            val lower = file.name.lowercase(Locale.ROOT)
            when {
                lower.contains("iris") -> return "Iris Shaders"
                lower.contains("oculus") -> return "Oculus Shaders"
                lower.contains("optifine") -> return "OptiFine"
            }
        }
        return null
    }

    private fun evaluateRendererForShaders(
        rendererId: String?,
        mcVersion: String?,
        detectedShaderMod: String?
    ): Pair<ShaderCompatibilityLevel, String> {
        val id = rendererId?.lowercase(Locale.ROOT).orEmpty()
        val versionSuffix = mcVersion?.let { " on MC $it" }.orEmpty()
        return when {
            id.contains("opengles2") || id.contains("gl4es") ->
                ShaderCompatibilityLevel.INCOMPATIBLE to
                    "NG-GL4ES$versionSuffix has very limited shader support. Switch renderer to MobileGlues or Kopper-Zink for Iris/OptiFine shaders."
            id.contains("mobileglues") ->
                if (detectedShaderMod != null) {
                    ShaderCompatibilityLevel.COMPATIBLE to
                        "MobileGlues + $detectedShaderMod$versionSuffix supports modern GLSL shader packs."
                } else {
                    ShaderCompatibilityLevel.WARNING to
                        "MobileGlues supports shaders$versionSuffix, but Iris/Oculus/OptiFine was not found in mods/."
                }
            id.contains("zink") || id.contains("vulkan") || id.contains("stargfx") ->
                if (detectedShaderMod != null) {
                    ShaderCompatibilityLevel.COMPATIBLE to
                        "${friendlyRendererName(rendererId)} + $detectedShaderMod$versionSuffix supports desktop-grade shader pipelines (driver dependent)."
                } else {
                    ShaderCompatibilityLevel.WARNING to
                        "${friendlyRendererName(rendererId)} supports shaders$versionSuffix, but install Iris/Oculus/OptiFine in mods/ to activate them."
                }
            id.contains("ltw") ->
                ShaderCompatibilityLevel.WARNING to
                    "LTW$versionSuffix supports basic shaders; heavy Iris packs may require MobileGlues or Kopper-Zink."
            else ->
                if (detectedShaderMod != null) {
                    ShaderCompatibilityLevel.COMPATIBLE to
                        "$detectedShaderMod detected$versionSuffix. Verify your renderer supports OpenGL ES 3.2+ or Vulkan."
                } else {
                    ShaderCompatibilityLevel.WARNING to
                        "Install Iris (Fabric/NeoForge), Oculus (Forge), or OptiFine to load shader packs$versionSuffix."
                }
        }
    }

    private fun combinePackCompatibility(
        validation: ShaderPackValidationResult,
        rendererLevel: ShaderCompatibilityLevel,
        rendererLabel: String,
        detectedShaderMod: String?,
        activeRendererId: String?
    ): Pair<ShaderCompatibilityLevel, String> {
        if (!validation.isValid) {
            return ShaderCompatibilityLevel.INCOMPATIBLE to (validation.errorMessage ?: "Invalid shader pack")
        }
        if (rendererLevel == ShaderCompatibilityLevel.INCOMPATIBLE) {
            return ShaderCompatibilityLevel.INCOMPATIBLE to "Unsupported on $rendererLabel"
        }
        if (detectedShaderMod == null) {
            return ShaderCompatibilityLevel.WARNING to "Requires Iris / Oculus / OptiFine"
        }
        if (validation.hasComputeShaders && isLowComputeRenderer(activeRendererId)) {
            return ShaderCompatibilityLevel.WARNING to "Compute shaders may lag on $rendererLabel"
        }
        return rendererLevel to "Ready ($rendererLabel · $detectedShaderMod)"
    }

    private fun isLowComputeRenderer(rendererId: String?): Boolean {
        val id = rendererId?.lowercase(Locale.ROOT).orEmpty()
        return id.contains("opengles2") || id.contains("gl4es") || id.contains("ltw")
    }

    private fun computeDirectorySize(dir: File): Long {
        var total = 0L
        dir.walkTopDown().maxDepth(6).forEach { f ->
            if (f.isFile) total += f.length()
        }
        return total
    }

    private fun isInsufficientSpaceError(e: IOException, dir: File): Boolean {
        val msg = e.message?.lowercase(Locale.ROOT).orEmpty()
        if (msg.contains("enospc") || msg.contains("no space left")) return true
        return runCatching {
            val stat = StatFs(dir.absolutePath)
            stat.availableBytes < 4L * 1024L * 1024L
        }.getOrDefault(false)
    }

    companion object {
        const val SHADERPACKS_DIR_NAME = "shaderpacks"
        const val DISABLED_SUFFIX = ".disabled"
        private const val STREAM_BUFFER_BYTES = 64 * 1024

        private val SHADER_EXTENSIONS = listOf(
            ".fsh", ".vsh", ".gsh", ".csh", ".tcs", ".tes", ".glsl"
        )

        fun normalizeBaseShaderName(fileName: String): String =
            if (fileName.endsWith(DISABLED_SUFFIX, ignoreCase = true)) {
                fileName.substring(0, fileName.length - DISABLED_SUFFIX.length)
            } else {
                fileName
            }

        fun isSafeSingleSegmentName(name: String): Boolean =
            name.isNotBlank() &&
                !name.contains('/') &&
                !name.contains('\\') &&
                !name.contains("..") &&
                !name.contains('\u0000')

        fun sanitizeImportFileName(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            val leaf = raw.substringAfterLast('/').substringAfterLast('\\').substringAfterLast(':')
            return leaf.replace(Regex("[\\r\\n\\t\\u0000*?\"<>|]"), "_").trim()
        }

        fun isUnsafeZipEntryPath(entryName: String): Boolean {
            if (entryName.isBlank() || entryName.contains('\u0000')) return true
            val normalized = entryName.replace('\\', '/')
            if (normalized.startsWith("/")) return true
            if (normalized.length >= 2 && normalized[1] == ':') return true
            return normalized.split('/').any { it == ".." }
        }

        fun friendlyRendererName(rendererId: String?): String = when (rendererId?.lowercase(Locale.ROOT)) {
            null, "" -> "Auto Renderer"
            "opengles_mobileglues" -> "MobileGlues"
            "opengles3_desktopgl_zink_kopper" -> "Kopper-Zink"
            "vulkan_zink" -> "OSMesa Zink"
            "opengles2", "opengles2_5", "opengles3" -> "NG-GL4ES"
            "opengles3_ltw" -> "LTW"
            "stargfx_vulkan" -> "STAR GFX (Vulkan)"
            "stargfx_gles" -> "STAR GFX (GLES)"
            else -> rendererId
        }
    }
}
