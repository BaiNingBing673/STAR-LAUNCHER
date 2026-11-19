package com.star.launcher.bridge.auth

import android.util.Base64
import com.star.launcher.bridge.logging.BridgeLogManager
import com.star.launcher.bridge.model.InstalledArtifactMetadata
import com.star.launcher.bridge.runtime.ArtifactInstallResult
import com.star.launcher.bridge.runtime.BridgeUpdateManager
import com.star.launcher.bridge.runtime.BridgeVersionManager
import com.star.launcher.bridge.runtime.HashAlgorithm
import com.star.launcher.bridge.runtime.VerifiedArtifactRequest
import com.star.launcher.bridge.storage.BridgeStoragePaths
import com.star.launcher.data.security.TokenVault
import java.io.File
import java.security.SecureRandom

data class FloodgateValidationReport(
    val pluginInstalled: Boolean,
    val geyserKeyPresent: Boolean,
    val serverKeyPresent: Boolean,
    val keysMatch: Boolean,
    val backedUpInVault: Boolean,
    val isValidForLocalFloodgate: Boolean,
    val issues: List<String> = emptyList()
)

/**
 * Manages optional Floodgate plugin installation and secure `key.pem` provisioning/synchronization
 * backed by STAR's [TokenVault]. Never logs or exposes private key material.
 */
class FloodgateManager(
    private val paths: BridgeStoragePaths,
    private val versionManager: BridgeVersionManager,
    private val updateManager: BridgeUpdateManager,
    private val tokenVault: TokenVault,
    private val logManager: BridgeLogManager
) {
    fun isPluginInstalled(): Boolean = paths.floodgatePluginJarFile().isFile

    fun getInstalledPluginMetadata(): InstalledArtifactMetadata? {
        val jar = paths.floodgatePluginJarFile()
        if (!jar.isFile) return null
        return updateManager.readMetadata(paths.floodgateMetadataFile()) ?: InstalledArtifactMetadata(
            projectId = "floodgate",
            version = "installed",
            buildNumber = 0,
            fileName = jar.name,
            sha256 = "",
            installedAtMs = jar.lastModified(),
            jarPath = jar.absolutePath
        )
    }

    suspend fun installOrUpdateFloodgatePlugin(
        isServerRunning: () -> Boolean,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): ArtifactInstallResult {
        val release = versionManager.fetchLatestFloodgateSpigot()
        return updateManager.installVerifiedArtifact(
            request = VerifiedArtifactRequest(
                projectId = "floodgate",
                version = release.version,
                buildNumber = release.buildNumber,
                fileName = release.artifactName,
                downloadUrl = release.downloadUrl,
                expectedHash = release.sha256,
                hashAlgorithm = HashAlgorithm.SHA_256,
                targetJarFile = paths.floodgatePluginJarFile(),
                previousDir = paths.serverPreviousDir(),
                metadataFile = paths.floodgateMetadataFile()
            ),
            isRuntimeActive = isServerRunning,
            onProgress = onProgress
        )
    }

    fun inspectFloodgateState(keyFileName: String = "key.pem"): FloodgateValidationReport {
        val issues = mutableListOf<String>()
        val pluginInstalled = isPluginInstalled()
        val geyserKey = paths.geyserFloodgateKeyFile(keyFileName)
        val serverKey = paths.serverFloodgateKeyFile()
        val geyserBytes = readKeyBytesIfValid(geyserKey)
        val serverBytes = readKeyBytesIfValid(serverKey)
        val backedUp = runCatching { tokenVault.containsSecret(VAULT_ALIAS_FLOODGATE_KEY) }.getOrDefault(false)

        if (!pluginInstalled) {
            issues += "floodgate-spigot.jar is not installed in server plugins/"
        }
        if (geyserBytes == null) {
            issues += "Geyser Floodgate key (${geyserKey.name}) is missing or empty"
        }
        if (serverBytes == null) {
            issues += "Server Floodgate key (${serverKey.name}) is missing or empty"
        }
        val keysMatch = geyserBytes != null && serverBytes != null && geyserBytes.contentEquals(serverBytes)
        if (geyserBytes != null && serverBytes != null && !keysMatch) {
            issues += "Geyser key.pem and server plugins/floodgate/key.pem do not match"
        }

        return FloodgateValidationReport(
            pluginInstalled = pluginInstalled,
            geyserKeyPresent = geyserBytes != null,
            serverKeyPresent = serverBytes != null,
            keysMatch = keysMatch,
            backedUpInVault = backedUp,
            isValidForLocalFloodgate = pluginInstalled && keysMatch,
            issues = issues
        )
    }

    /**
     * Synchronizes existing Floodgate `key.pem` between server and Geyser, or restores from Vault,
     * or generates a fresh cryptographic key if none exists yet.
     */
    fun synchronizeOrGenerateKeys(keyFileName: String = "key.pem"): Boolean {
        val geyserKey = paths.geyserFloodgateKeyFile(keyFileName)
        val serverKey = paths.serverFloodgateKeyFile()

        val serverBytes = readKeyBytesIfValid(serverKey)
        val geyserBytes = readKeyBytesIfValid(geyserKey)
        val vaultBytes = runCatching {
            tokenVault.getSecret(VAULT_ALIAS_FLOODGATE_KEY)?.let { Base64.decode(it, Base64.NO_WRAP) }
        }.getOrNull()?.takeIf { it.size >= MIN_KEY_BYTES }

        val authoritative = serverBytes ?: geyserBytes ?: vaultBytes ?: generateRandomFloodgateKeyBytes()
        writeOwnerOnlyKey(serverKey, authoritative)
        writeOwnerOnlyKey(geyserKey, authoritative)
        backupKeyToVault(authoritative)
        logManager.logInfo("Floodgate", "Synchronized Floodgate key.pem securely (${authoritative.size} bytes; key contents redacted)")
        return true
    }

    fun importExternalKeyBytes(rawKeyBytes: ByteArray, keyFileName: String = "key.pem"): Boolean {
        if (rawKeyBytes.size < MIN_KEY_BYTES) {
            return false
        }
        val geyserKey = paths.geyserFloodgateKeyFile(keyFileName)
        writeOwnerOnlyKey(geyserKey, rawKeyBytes)
        backupKeyToVault(rawKeyBytes)
        logManager.logInfo("Floodgate", "Imported external Floodgate key into Geyser directory (contents redacted)")
        return true
    }

    fun regenerateKeys(keyFileName: String = "key.pem") {
        val fresh = generateRandomFloodgateKeyBytes()
        writeOwnerOnlyKey(paths.serverFloodgateKeyFile(), fresh)
        writeOwnerOnlyKey(paths.geyserFloodgateKeyFile(keyFileName), fresh)
        backupKeyToVault(fresh)
        logManager.logInfo("Floodgate", "Generated fresh Floodgate key.pem pair and mirrored to TokenVault")
    }

    fun clearKeys(keyFileName: String = "key.pem") {
        paths.serverFloodgateKeyFile().delete()
        paths.geyserFloodgateKeyFile(keyFileName).delete()
        runCatching { tokenVault.deleteSecret(VAULT_ALIAS_FLOODGATE_KEY) }
        logManager.logWarn("Floodgate", "Cleared Floodgate keys from local storage and TokenVault")
    }

    private fun backupKeyToVault(bytes: ByteArray) {
        runCatching {
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            tokenVault.putSecret(VAULT_ALIAS_FLOODGATE_KEY, encoded)
        }.onFailure { err ->
            logManager.logWarn("Floodgate", "Could not mirror key backup to Keystore TokenVault: ${err.message}")
        }
    }

    private fun writeOwnerOnlyKey(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }

    private fun readKeyBytesIfValid(file: File): ByteArray? {
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return bytes.takeIf { it.size >= MIN_KEY_BYTES }
    }

    private fun generateRandomFloodgateKeyBytes(): ByteArray {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    companion object {
        const val VAULT_ALIAS_FLOODGATE_KEY = "star_bridge_floodgate_key_pem"
        private const val MIN_KEY_BYTES = 16
    }
}
