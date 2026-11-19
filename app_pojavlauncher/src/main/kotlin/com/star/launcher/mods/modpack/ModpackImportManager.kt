package com.star.launcher.mods.modpack

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.modloaders.modpacks.api.CurseforgeApi
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModLoader
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModrinthApi
import net.kdt.pojavlaunch.utils.ZipUtils
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

enum class ModpackFormat(val label: String) {
    MODRINTH("Modrinth (.mrpack)"),
    CURSEFORGE("CurseForge (.zip)")
}

enum class ModpackImportStage(val label: String) {
    IDLE("Idle"),
    PREPARING("Checking storage & archive..."),
    STREAMING_COPY("Streaming modpack archive..."),
    VALIDATING("Validating archive & manifest..."),
    DOWNLOADING_MODS("Downloading modpack dependencies..."),
    EXTRACTING("Extracting modpack overrides..."),
    FINALIZING("Finalizing instance..."),
    COMPLETED("Modpack imported"),
    CANCELLED("Import cancelled"),
    FAILED("Import failed")
}

data class ModpackImportProgress(
    val isActive: Boolean = false,
    val stage: ModpackImportStage = ModpackImportStage.IDLE,
    val archiveName: String? = null,
    val progressPercent: Int? = null,
    val processedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val detailMessage: String = "",
    val canCancel: Boolean = false,
    val errorMessage: String? = null,
    val completedInstanceName: String? = null
)

data class ValidatedModpackManifest(
    val format: ModpackFormat,
    val name: String,
    val version: String,
    val minecraftVersion: String,
    val overridesDir: String,
    val entryCount: Int,
    val totalUncompressedOverrideBytes: Long,
    val remoteFilesCount: Int
)

data class ModpackImportResult(
    val format: ModpackFormat,
    val modpackName: String,
    val modpackVersion: String,
    val minecraftVersion: String,
    val instanceDir: File,
    val loaderInfo: ModLoader?
)

class InsufficientStorageException(
    val requiredBytes: Long,
    val availableBytes: Long,
    val targetPath: String
) : IOException(
    "Insufficient storage space: need ${formatStorageMb(requiredBytes)}, but only ${formatStorageMb(availableBytes)} is available."
)

class InvalidModpackArchiveException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Streaming, low-memory, cancellable modpack import engine for large `.zip` and `.mrpack` archives.
 *
 * Key guarantees:
 * - Never loads entire modpack archives into RAM (64 KiB streaming buffers).
 * - Computes SHA-1 in a single pass while streaming from Android SAF `Uri`.
 * - Checks available storage via `StatFs` before copying and before extracting.
 * - Validates ZIP integrity, blocks ZIP Slip / path traversal across every entry and manifest path,
 *   and checks required manifest metadata before writing any instance files.
 * - Extracts into an isolated temporary staging directory and promotes atomically on success;
 *   rolls back and cleans up all temporary files on failure or cancellation without touching
 *   existing game installations or user data.
 */
class ModpackImportManager(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val availableBytesProvider: (File) -> Long = ::defaultAvailableBytes
) {
    private val _progress = MutableStateFlow(ModpackImportProgress())
    val progress: StateFlow<ModpackImportProgress> = _progress.asStateFlow()

    @Volatile
    private var activeJob: Job? = null
    private val cancelRequested = AtomicBoolean(false)

    fun cancelActiveImport() {
        cancelRequested.set(true)
        activeJob?.cancel()
        _progress.update {
            if (it.isActive) {
                it.copy(
                    stage = ModpackImportStage.CANCELLED,
                    detailMessage = "Cancelling import and cleaning temporary files...",
                    canCancel = false
                )
            } else {
                it
            }
        }
    }

    fun dismissProgress() {
        if (!_progress.value.isActive) {
            _progress.value = ModpackImportProgress()
        }
    }

    /**
     * Imports a modpack from an Android SAF [Uri] using single-pass streaming copy + validation.
     */
    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        instancesRoot: File,
        stagingCacheRoot: File,
        onRegisterInstance: (suspend (ModpackImportResult) -> Unit)? = null
    ): ModpackImportResult = withContext(ioDispatcher) {
        val appContext = context.applicationContext
        activeJob = currentCoroutineContext()[Job]
        cancelRequested.set(false)

        val displayName = sanitizeFileName(Tools.getFileName(appContext, uri))
            .ifBlank { "modpack_${System.currentTimeMillis()}.zip" }
        val declaredSize = queryUriSize(appContext, uri)

        updateProgress(
            isActive = true,
            stage = ModpackImportStage.PREPARING,
            archiveName = displayName,
            progressPercent = 0,
            processedBytes = 0L,
            totalBytes = declaredSize,
            detailMessage = "Preparing storage for $displayName...",
            canCancel = true
        )

        val tempDir = chooseStagingDirectory(stagingCacheRoot, instancesRoot, declaredSize)
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw IOException("Unable to create temporary staging directory: ${tempDir.absolutePath}")
        }

        if (declaredSize > 0L) {
            ensureEnoughSpace(tempDir, declaredSize + STORAGE_SAFETY_MARGIN_BYTES)
        }

        val extension = if (displayName.lowercase(Locale.ROOT).endsWith(".mrpack")) ".mrpack" else ".zip"
        val stagedArchive = File(tempDir, "modpack_import_${UUID.randomUUID()}$extension")

        try {
            val streamResult = appContext.contentResolver.openInputStream(uri)?.use { input ->
                streamCopyWithSha1(
                    input = input,
                    targetFile = stagedArchive,
                    expectedTotalBytes = declaredSize,
                    archiveName = displayName
                )
            } ?: throw IOException("Failed to open selected modpack file from Android storage.")

            val result = importValidatedFileInternal(
                archiveFile = stagedArchive,
                originalDisplayName = displayName,
                precomputedSha1 = streamResult.sha1Hex,
                instancesRoot = instancesRoot,
                curseforgeApi = CurseforgeApi(appContext),
                modrinthApi = ModrinthApi(),
                updateLegacyProfiles = true
            )

            onRegisterInstance?.invoke(result)

            updateProgress(
                isActive = false,
                stage = ModpackImportStage.COMPLETED,
                archiveName = displayName,
                progressPercent = 100,
                processedBytes = streamResult.bytesCopied,
                totalBytes = streamResult.bytesCopied,
                detailMessage = "Imported '${result.modpackName}' (${result.minecraftVersion})",
                canCancel = false,
                completedInstanceName = result.modpackName
            )
            result
        } catch (e: CancellationException) {
            updateProgress(
                isActive = false,
                stage = ModpackImportStage.CANCELLED,
                archiveName = displayName,
                detailMessage = "Modpack import cancelled. No existing game data was modified.",
                canCancel = false
            )
            throw e
        } catch (e: InterruptedIOException) {
            updateProgress(
                isActive = false,
                stage = ModpackImportStage.CANCELLED,
                archiveName = displayName,
                detailMessage = "Modpack import cancelled. No existing game data was modified.",
                canCancel = false
            )
            throw CancellationException("Modpack import cancelled", e)
        } catch (e: Exception) {
            Log.e(TAG, "Modpack import failed for $displayName", e)
            updateProgress(
                isActive = false,
                stage = ModpackImportStage.FAILED,
                archiveName = displayName,
                detailMessage = e.message ?: "Modpack import failed.",
                canCancel = false,
                errorMessage = e.message ?: e::class.java.simpleName
            )
            throw e
        } finally {
            activeJob = null
            if (stagedArchive.exists()) {
                stagedArchive.delete()
            }
        }
    }

    /**
     * Imports a local modpack file directly (used both internally and by unit tests without requiring SAF).
     */
    suspend fun importLocalFile(
        archiveFile: File,
        instancesRoot: File,
        curseforgeApi: CurseforgeApi? = null,
        modrinthApi: ModrinthApi = ModrinthApi(),
        updateLegacyProfiles: Boolean = false
    ): ModpackImportResult = withContext(ioDispatcher) {
        activeJob = currentCoroutineContext()[Job]
        cancelRequested.set(false)
        try {
            if (!archiveFile.isFile || archiveFile.length() <= 0L) {
                throw InvalidModpackArchiveException("Modpack file is missing or empty (0 bytes).")
            }
            val sha1 = computeSha1Streaming(archiveFile)
            val result = importValidatedFileInternal(
                archiveFile = archiveFile,
                originalDisplayName = archiveFile.name,
                precomputedSha1 = sha1,
                instancesRoot = instancesRoot,
                curseforgeApi = curseforgeApi,
                modrinthApi = modrinthApi,
                updateLegacyProfiles = updateLegacyProfiles
            )
            updateProgress(
                isActive = false,
                stage = ModpackImportStage.COMPLETED,
                archiveName = archiveFile.name,
                progressPercent = 100,
                detailMessage = "Imported '${result.modpackName}' (${result.minecraftVersion})",
                canCancel = false,
                completedInstanceName = result.modpackName
            )
            result
        } catch (e: CancellationException) {
            updateProgress(
                isActive = false,
                stage = ModpackImportStage.CANCELLED,
                archiveName = archiveFile.name,
                detailMessage = "Modpack import cancelled.",
                canCancel = false
            )
            throw e
        } catch (e: InterruptedIOException) {
            updateProgress(
                isActive = false,
                stage = ModpackImportStage.CANCELLED,
                archiveName = archiveFile.name,
                detailMessage = "Modpack import cancelled.",
                canCancel = false
            )
            throw CancellationException("Modpack import cancelled", e)
        } catch (e: Exception) {
            updateProgress(
                isActive = false,
                stage = ModpackImportStage.FAILED,
                archiveName = archiveFile.name,
                detailMessage = e.message ?: "Modpack import failed.",
                canCancel = false,
                errorMessage = e.message ?: e::class.java.simpleName
            )
            throw e
        } finally {
            activeJob = null
        }
    }

    /**
     * Validates archive structure, Central Directory integrity, ZIP Slip safety, and required manifest fields.
     * Does not extract any files or load archive contents into memory.
     */
    fun validateArchive(
        archiveFile: File,
        originalDisplayName: String = archiveFile.name
    ): ValidatedModpackManifest {
        if (!archiveFile.isFile || archiveFile.length() <= 0L) {
            throw InvalidModpackArchiveException("Modpack archive is empty or missing.")
        }
        return try {
            ZipFile(archiveFile).use { zip ->
                var hasModrinth = false
                var hasCurseforge = false
                var entryCount = 0
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    entryCount++
                    val name = entry.name
                    if (!ZipUtils.isSafeEntryName(name)) {
                        throw InvalidModpackArchiveException("Blocked unsafe path in modpack archive: '$name'.")
                    }
                    if (name == "modrinth.index.json") hasModrinth = true
                    if (name == "manifest.json") hasCurseforge = true
                }
                if (entryCount == 0) {
                    throw InvalidModpackArchiveException("Modpack ZIP archive is empty.")
                }

                val format = when {
                    hasModrinth && hasCurseforge -> {
                        if (originalDisplayName.lowercase(Locale.ROOT).endsWith(".mrpack")) {
                            ModpackFormat.MODRINTH
                        } else {
                            throw InvalidModpackArchiveException(
                                "Ambiguous archive contains both modrinth.index.json and manifest.json."
                            )
                        }
                    }
                    hasModrinth -> ModpackFormat.MODRINTH
                    hasCurseforge -> ModpackFormat.CURSEFORGE
                    else -> throw InvalidModpackArchiveException(
                        "Invalid modpack archive: missing manifest.json (CurseForge) or modrinth.index.json (Modrinth)."
                    )
                }

                when (format) {
                    ModpackFormat.CURSEFORGE -> parseAndValidateCurseForgeManifest(zip, entryCount)
                    ModpackFormat.MODRINTH -> parseAndValidateModrinthManifest(zip, entryCount)
                }
            }
        } catch (e: InvalidModpackArchiveException) {
            throw e
        } catch (e: Exception) {
            throw InvalidModpackArchiveException(
                "Corrupted or unreadable modpack ZIP archive: ${e.message ?: e::class.java.simpleName}",
                e
            )
        }
    }

    private suspend fun importValidatedFileInternal(
        archiveFile: File,
        originalDisplayName: String,
        precomputedSha1: String,
        instancesRoot: File,
        curseforgeApi: CurseforgeApi?,
        modrinthApi: ModrinthApi,
        updateLegacyProfiles: Boolean
    ): ModpackImportResult {
        checkNotCancelled()
        updateProgress(
            isActive = true,
            stage = ModpackImportStage.VALIDATING,
            archiveName = originalDisplayName,
            progressPercent = null,
            detailMessage = "Validating archive integrity and manifest...",
            canCancel = true
        )

        val manifest = validateArchive(archiveFile, originalDisplayName)
        checkNotCancelled()

        if (!instancesRoot.exists() && !instancesRoot.mkdirs()) {
            throw IOException("Unable to create instances directory: ${instancesRoot.absolutePath}")
        }

        // Verify disk space before extracting overrides
        val requiredExtractionBytes = manifest.totalUncompressedOverrideBytes + STORAGE_SAFETY_MARGIN_BYTES
        ensureEnoughSpace(instancesRoot, requiredExtractionBytes)

        val shortHash = precomputedSha1.take(12)
        var folderName = "${manifest.name} ${manifest.version} for ${manifest.minecraftVersion} $shortHash"
            .trim()
            .replace(Regex("[\\\\/:*?\"<>| \\t\\n]"), "_")
        if (folderName.length > 110) {
            folderName = folderName.substring(0, 110)
        }

        val stagingDir = File(instancesRoot, ".staging_${folderName}_${UUID.randomUUID()}")
        var finalDir = File(instancesRoot, folderName)
        if (finalDir.exists()) {
            // Preserve any existing installation with the same folder name!
            finalDir = File(instancesRoot, "${folderName}_${System.currentTimeMillis()}")
        }

        var loaderInfo: ModLoader? = null
        try {
            if (!stagingDir.mkdirs()) {
                throw IOException("Failed to create temporary staging directory: ${stagingDir.absolutePath}")
            }

            if (manifest.remoteFilesCount > 0) {
                updateProgress(
                    isActive = true,
                    stage = ModpackImportStage.DOWNLOADING_MODS,
                    archiveName = originalDisplayName,
                    progressPercent = null,
                    detailMessage = "Installing ${manifest.name} (${manifest.remoteFilesCount} remote files)...",
                    canCancel = true
                )
                loaderInfo = when (manifest.format) {
                    ModpackFormat.CURSEFORGE -> {
                        val api = curseforgeApi
                            ?: throw IOException("CurseForge API is required to download remote files in this modpack.")
                        api.installCurseforgeZip(archiveFile, stagingDir)
                    }
                    ModpackFormat.MODRINTH -> {
                        modrinthApi.installMrpack(archiveFile, stagingDir)
                    }
                }
            } else {
                // Local / overrides-only modpack: extract directly with streaming progress & zero network requirement!
                updateProgress(
                    isActive = true,
                    stage = ModpackImportStage.EXTRACTING,
                    archiveName = originalDisplayName,
                    progressPercent = 0,
                    processedBytes = 0L,
                    totalBytes = manifest.totalUncompressedOverrideBytes,
                    detailMessage = "Extracting overrides for ${manifest.name}...",
                    canCancel = true
                )
                ZipFile(archiveFile).use { zip ->
                    val cancelCheck = ZipUtils.CancellationCheck { cancelRequested.get() || Thread.currentThread().isInterrupted }
                    val progressCb = ZipUtils.ZipProgressCallback { extracted, total, entry ->
                        val pct = if (total > 0L) ((extracted * 100L) / total).toInt().coerceIn(0, 100) else null
                        updateProgress(
                            isActive = true,
                            stage = ModpackImportStage.EXTRACTING,
                            archiveName = originalDisplayName,
                            progressPercent = pct,
                            processedBytes = extracted,
                            totalBytes = total,
                            detailMessage = if (entry.isNotBlank()) "Extracting $entry" else "Extracting overrides...",
                            canCancel = true
                        )
                    }
                    when (manifest.format) {
                        ModpackFormat.CURSEFORGE -> {
                            ZipUtils.zipExtract(zip, manifest.overridesDir, stagingDir, progressCb, cancelCheck)
                            loaderInfo = extractCurseForgeLoaderInfo(zip)
                        }
                        ModpackFormat.MODRINTH -> {
                            ZipUtils.zipExtract(zip, "overrides/", stagingDir, progressCb, cancelCheck)
                            ZipUtils.zipExtract(zip, "client-overrides/", stagingDir, progressCb, cancelCheck)
                            loaderInfo = extractModrinthLoaderInfo(zip)
                        }
                    }
                }
            }

            checkNotCancelled()
            updateProgress(
                isActive = true,
                stage = ModpackImportStage.FINALIZING,
                archiveName = originalDisplayName,
                progressPercent = 98,
                detailMessage = "Finalizing '${manifest.name}'...",
                canCancel = false
            )

            if (!stagingDir.renameTo(finalDir)) {
                throw IOException("Failed to move staged modpack into ${finalDir.absolutePath}")
            }

            if (updateLegacyProfiles) {
                runCatching {
                    LauncherProfiles.load()
                    if (LauncherProfiles.mainProfileJson != null && LauncherProfiles.mainProfileJson.profiles != null) {
                        val profile = MinecraftProfile.getDefaultProfile()
                        profile.gameDir = "./custom_instances/${finalDir.name}"
                        profile.name = manifest.name
                        profile.lastVersionId = loaderInfo?.versionId?.takeIf { it.isNotBlank() } ?: manifest.minecraftVersion
                        LauncherProfiles.mainProfileJson.profiles[finalDir.name] = profile
                        LauncherProfiles.write()
                    }
                }
            }

            return ModpackImportResult(
                format = manifest.format,
                modpackName = manifest.name,
                modpackVersion = manifest.version,
                minecraftVersion = manifest.minecraftVersion,
                instanceDir = finalDir,
                loaderInfo = loaderInfo
            )
        } catch (e: Throwable) {
            deleteRecursivelyQuietly(stagingDir)
            throw e
        }
    }

    private fun parseAndValidateCurseForgeManifest(zip: ZipFile, entryCount: Int): ValidatedModpackManifest {
        val raw = zip.getInputStream(zip.getEntry("manifest.json")).use { it.bufferedReader().readText() }
        val json = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrElse {
            throw InvalidModpackArchiveException("Corrupted CurseForge manifest.json: invalid JSON.", it)
        }
        val name = json.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        val version = json.get("version")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty().ifBlank { "1.0" }
        val mcObj = json.getAsJsonObject("minecraft")
        val mcVersion = mcObj?.get("version")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        if (name.isBlank() || mcVersion.isBlank()) {
            throw InvalidModpackArchiveException("CurseForge manifest.json is missing required 'name' or 'minecraft.version'.")
        }
        val overridesRaw = json.get("overrides")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty().ifBlank { "overrides" }
        val overridesDir = if (overridesRaw.endsWith("/")) overridesRaw else "$overridesRaw/"
        if (!ZipUtils.isSafeEntryName(overridesDir)) {
            throw InvalidModpackArchiveException("Blocked unsafe overrides path in manifest.json: '$overridesDir'.")
        }
        val filesArray = json.getAsJsonArray("files")
        val remoteCount = filesArray?.size() ?: 0
        val uncompressedBytes = ZipUtils.calculateUncompressedBytes(zip, overridesDir)

        return ValidatedModpackManifest(
            format = ModpackFormat.CURSEFORGE,
            name = name,
            version = version,
            minecraftVersion = mcVersion,
            overridesDir = overridesDir,
            entryCount = entryCount,
            totalUncompressedOverrideBytes = uncompressedBytes,
            remoteFilesCount = remoteCount
        )
    }

    private fun parseAndValidateModrinthManifest(zip: ZipFile, entryCount: Int): ValidatedModpackManifest {
        val raw = zip.getInputStream(zip.getEntry("modrinth.index.json")).use { it.bufferedReader().readText() }
        val json = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrElse {
            throw InvalidModpackArchiveException("Corrupted modrinth.index.json: invalid JSON.", it)
        }
        val name = json.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        val version = json.get("versionId")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty().ifBlank { "1.0" }
        val deps = json.getAsJsonObject("dependencies")
        val mcVersion = deps?.get("minecraft")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        if (name.isBlank() || mcVersion.isBlank()) {
            throw InvalidModpackArchiveException("modrinth.index.json is missing required 'name' or 'dependencies.minecraft'.")
        }
        val filesArray = json.getAsJsonArray("files")
        var remoteCount = 0
        var remoteDeclaredBytes = 0L
        if (filesArray != null) {
            for (elem in filesArray) {
                val obj = elem.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val path = obj.get("path")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                if (path.isBlank() || !ZipUtils.isSafeEntryName(path)) {
                    throw InvalidModpackArchiveException("Blocked unsafe file path in modrinth.index.json: '$path'.")
                }
                val downloads = obj.getAsJsonArray("downloads")
                if (downloads != null && downloads.size() > 0) {
                    remoteCount++
                    val sz = obj.get("fileSize")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                    if (sz > 0L) remoteDeclaredBytes += sz
                }
            }
        }
        val overridesBytes = ZipUtils.calculateUncompressedBytes(zip, "overrides/") +
            ZipUtils.calculateUncompressedBytes(zip, "client-overrides/")

        return ValidatedModpackManifest(
            format = ModpackFormat.MODRINTH,
            name = name,
            version = version,
            minecraftVersion = mcVersion,
            overridesDir = "overrides/",
            entryCount = entryCount,
            totalUncompressedOverrideBytes = overridesBytes + remoteDeclaredBytes,
            remoteFilesCount = remoteCount
        )
    }

    private fun extractCurseForgeLoaderInfo(zip: ZipFile): ModLoader? = runCatching {
        val raw = zip.getInputStream(zip.getEntry("manifest.json")).use { it.bufferedReader().readText() }
        val json = JsonParser.parseString(raw).asJsonObject
        val mcObj = json.getAsJsonObject("minecraft") ?: return null
        val mcVer = mcObj.get("version")?.asString ?: return null
        val loaders = mcObj.getAsJsonArray("modLoaders") ?: return null
        if (loaders.size() == 0) return null
        var chosen = loaders[0].asJsonObject
        for (el in loaders) {
            val obj = el.asJsonObject
            if (obj.get("primary")?.asBoolean == true) {
                chosen = obj
                break
            }
        }
        val id = chosen.get("id")?.asString ?: return null
        val dash = id.indexOf('-')
        if (dash <= 0 || dash >= id.length - 1) return null
        val type = when (id.substring(0, dash).lowercase(Locale.ROOT)) {
            "forge" -> ModLoader.MOD_LOADER_FORGE
            "fabric" -> ModLoader.MOD_LOADER_FABRIC
            "quilt" -> ModLoader.MOD_LOADER_QUILT
            "neoforge" -> ModLoader.MOD_LOADER_NEOFORGE
            else -> return null
        }
        ModLoader(type, id.substring(dash + 1), mcVer)
    }.getOrNull()

    private fun extractModrinthLoaderInfo(zip: ZipFile): ModLoader? = runCatching {
        val raw = zip.getInputStream(zip.getEntry("modrinth.index.json")).use { it.bufferedReader().readText() }
        val json = JsonParser.parseString(raw).asJsonObject
        val deps = json.getAsJsonObject("dependencies") ?: return null
        val mcVer = deps.get("minecraft")?.asString ?: return null
        deps.get("forge")?.asString?.let { return ModLoader(ModLoader.MOD_LOADER_FORGE, it, mcVer) }
        deps.get("fabric-loader")?.asString?.let { return ModLoader(ModLoader.MOD_LOADER_FABRIC, it, mcVer) }
        deps.get("quilt-loader")?.asString?.let { return ModLoader(ModLoader.MOD_LOADER_QUILT, it, mcVer) }
        deps.get("neoforge")?.asString?.let { return ModLoader(ModLoader.MOD_LOADER_NEOFORGE, it, mcVer) }
        null
    }.getOrNull()

    private data class StreamCopyResult(val bytesCopied: Long, val sha1Hex: String)

    private suspend fun streamCopyWithSha1(
        input: InputStream,
        targetFile: File,
        expectedTotalBytes: Long,
        archiveName: String
    ): StreamCopyResult {
        val digest = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        val magic = ByteArray(4)
        var magicLen = 0
        var copied = 0L
        var lastReported = -1L

        FileOutputStream(targetFile).use { out ->
            while (true) {
                checkNotCancelled()
                val read = input.read(buffer)
                if (read <= 0) break
                if (magicLen < 4) {
                    val toCopy = minOf(4 - magicLen, read)
                    System.arraycopy(buffer, 0, magic, magicLen, toCopy)
                    magicLen += toCopy
                    if (magicLen == 4 && !isZipSignature(magic)) {
                        throw InvalidModpackArchiveException(
                            "Selected file '$archiveName' is not a valid ZIP/.mrpack archive."
                        )
                    }
                }
                digest.update(buffer, 0, read)
                out.write(buffer, 0, read)
                copied += read

                if (copied - lastReported >= PROGRESS_EMIT_STEP_BYTES || lastReported < 0L) {
                    lastReported = copied
                    val pct = if (expectedTotalBytes > 0L) {
                        ((copied * 100L) / expectedTotalBytes).toInt().coerceIn(0, 100)
                    } else null
                    val detail = if (expectedTotalBytes > 0L) {
                        "Streaming ${formatStorageMb(copied)} / ${formatStorageMb(expectedTotalBytes)}"
                    } else {
                        "Streaming ${formatStorageMb(copied)}..."
                    }
                    updateProgress(
                        isActive = true,
                        stage = ModpackImportStage.STREAMING_COPY,
                        archiveName = archiveName,
                        progressPercent = pct,
                        processedBytes = copied,
                        totalBytes = expectedTotalBytes,
                        detailMessage = detail,
                        canCancel = true
                    )
                }
            }
            out.flush()
        }

        if (copied <= 0L) {
            throw InvalidModpackArchiveException("Selected modpack archive is empty (0 bytes).")
        }
        val sha1Hex = digest.digest().joinToString("") { "%02x".format(it) }
        return StreamCopyResult(copied, sha1Hex)
    }

    private suspend fun computeSha1Streaming(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        file.inputStream().use { input ->
            while (true) {
                checkNotCancelled()
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun chooseStagingDirectory(cacheRoot: File, instancesRoot: File, requiredBytes: Long): File {
        val cacheAvail = availableBytesProvider(cacheRoot)
        val extStaging = File(instancesRoot, ".tmp_imports")
        val extAvail = availableBytesProvider(extStaging)
        val needed = if (requiredBytes > 0L) requiredBytes + STORAGE_SAFETY_MARGIN_BYTES else 0L
        return when {
            extAvail > cacheAvail && (needed == 0L || extAvail >= needed) -> extStaging
            cacheAvail >= needed -> File(cacheRoot, "modpack_staging")
            else -> extStaging
        }
    }

    private fun ensureEnoughSpace(targetDir: File, requiredBytes: Long) {
        if (requiredBytes <= 0L) return
        val available = availableBytesProvider(targetDir)
        if (available in 0 until requiredBytes) {
            throw InsufficientStorageException(
                requiredBytes = requiredBytes,
                availableBytes = available,
                targetPath = targetDir.absolutePath
            )
        }
    }

    private suspend fun checkNotCancelled() {
        currentCoroutineContext().ensureActive()
        if (cancelRequested.get() || Thread.currentThread().isInterrupted) {
            throw CancellationException("Modpack import cancelled")
        }
    }

    private fun updateProgress(
        isActive: Boolean,
        stage: ModpackImportStage,
        archiveName: String? = _progress.value.archiveName,
        progressPercent: Int? = _progress.value.progressPercent,
        processedBytes: Long = _progress.value.processedBytes,
        totalBytes: Long = _progress.value.totalBytes,
        detailMessage: String = _progress.value.detailMessage,
        canCancel: Boolean = _progress.value.canCancel,
        errorMessage: String? = null,
        completedInstanceName: String? = null
    ) {
        _progress.value = ModpackImportProgress(
            isActive = isActive,
            stage = stage,
            archiveName = archiveName,
            progressPercent = progressPercent,
            processedBytes = processedBytes,
            totalBytes = totalBytes,
            detailMessage = detailMessage,
            canCancel = canCancel,
            errorMessage = errorMessage,
            completedInstanceName = completedInstanceName
        )
    }

    private fun queryUriSize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else -1L
            } else {
                -1L
            }
        } ?: -1L
    }.getOrDefault(-1L)

    companion object {
        private const val TAG = "ModpackImportManager"
        private const val STREAM_BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_EMIT_STEP_BYTES = 256L * 1024L
        const val STORAGE_SAFETY_MARGIN_BYTES = 32L * 1024L * 1024L

        private fun isZipSignature(header: ByteArray): Boolean =
            header.size >= 4 &&
                header[0] == 0x50.toByte() &&
                header[1] == 0x4B.toByte() &&
                (header[2] == 0x03.toByte() || header[2] == 0x05.toByte() || header[2] == 0x07.toByte())

        private fun sanitizeFileName(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            return raw.substringAfterLast('/').substringAfterLast('\\').substringAfterLast(':').trim()
        }

        private fun defaultAvailableBytes(dir: File): Long = runCatching {
            var probe: File? = dir
            while (probe != null && !probe.exists()) {
                probe = probe.parentFile
            }
            val target = probe ?: return -1L
            StatFs(target.absolutePath).availableBytes
        }.getOrDefault(-1L)

        private fun deleteRecursivelyQuietly(file: File?) {
            if (file == null || !file.exists()) return
            runCatching { file.deleteRecursively() }
        }
    }
}

fun formatStorageMb(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
}
