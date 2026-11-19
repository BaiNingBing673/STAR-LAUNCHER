package com.star.launcher.bridge.storage

import net.kdt.pojavlaunch.Tools
import java.io.File

/**
 * Isolated STAR-owned directory layout for Bedrock Bridge artifacts, worlds, configs,
 * logs, and cross-process IPC status files. Never mutates `.minecraft` or `launcher_profiles.json`.
 */
class BridgeStoragePaths(
    private val appDataDirProvider: () -> File = { File(Tools.DIR_DATA) },
    private val gameHomeDirProvider: () -> File = { File(Tools.DIR_GAME_HOME) }
) {
    fun bridgeDataRoot(): File =
        File(appDataDirProvider(), "aether_bridge").apply { mkdirs() }

    fun geyserDir(): File =
        File(bridgeDataRoot(), "geyser").apply { mkdirs() }

    fun geyserJarFile(): File =
        File(geyserDir(), "Geyser-Standalone.jar")

    fun geyserConfigFile(): File =
        File(geyserDir(), "config.yml")

    fun geyserFloodgateKeyFile(fileName: String = "key.pem"): File =
        File(geyserDir(), sanitizeFileName(fileName))

    fun geyserPreviousDir(): File =
        File(geyserDir(), "previous").apply { mkdirs() }

    fun geyserMetadataFile(): File =
        File(geyserDir(), "artifact_meta.json")

    fun serverDir(): File =
        File(gameHomeDirProvider(), "aether_bridge_server").apply { mkdirs() }

    fun serverJarFile(): File =
        File(serverDir(), "server.jar")

    fun serverPropertiesFile(): File =
        File(serverDir(), "server.properties")

    fun serverEulaFile(): File =
        File(serverDir(), "eula.txt")

    fun serverPluginsDir(): File =
        File(serverDir(), "plugins").apply { mkdirs() }

    fun floodgatePluginJarFile(): File =
        File(serverPluginsDir(), "floodgate-spigot.jar")

    fun serverFloodgateConfigDir(): File =
        File(serverPluginsDir(), "floodgate").apply { mkdirs() }

    fun serverFloodgateKeyFile(): File =
        File(serverFloodgateConfigDir(), "key.pem")

    fun serverPreviousDir(): File =
        File(serverDir(), "previous").apply { mkdirs() }

    fun serverMetadataFile(): File =
        File(serverDir(), "artifact_meta.json")

    fun floodgateMetadataFile(): File =
        File(serverFloodgateConfigDir(), "artifact_meta.json")

    fun stagingDir(): File =
        File(bridgeDataRoot(), "staging").apply { mkdirs() }

    fun logsDir(): File =
        File(bridgeDataRoot(), "logs").apply { mkdirs() }

    fun geyserLogFile(): File =
        File(logsDir(), "geyser.log")

    fun serverLogFile(): File =
        File(logsDir(), "server.log")

    fun bridgeEventsLogFile(): File =
        File(logsDir(), "bridge_events.log")

    fun ipcDir(): File =
        File(bridgeDataRoot(), "ipc").apply { mkdirs() }

    fun geyserStatusFile(): File =
        File(ipcDir(), "geyser_status.json")

    fun serverStatusFile(): File =
        File(ipcDir(), "server_status.json")

    fun geyserCommandSpoolFile(): File =
        File(ipcDir(), "geyser_commands.spool")

    fun serverCommandSpoolFile(): File =
        File(ipcDir(), "server_commands.spool")

    fun exportsDir(): File =
        File(bridgeDataRoot(), "exports").apply { mkdirs() }

    fun licenseNoticeFile(): File =
        File(bridgeDataRoot(), "THIRD_PARTY_LICENSES.txt")

    companion object {
        private val unsafeChars = Regex("""[^a-zA-Z0-9._-]""")

        fun sanitizeFileName(raw: String): String {
            val trimmed = raw.trim()
            require(trimmed.isNotBlank()) { "Filename cannot be blank" }
            val cleaned = unsafeChars.replace(trimmed, "_")
            require(cleaned != "." && cleaned != "..") { "Invalid filename: $raw" }
            return cleaned
        }
    }
}
