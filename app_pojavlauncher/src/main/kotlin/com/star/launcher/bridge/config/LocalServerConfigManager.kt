package com.star.launcher.bridge.config

import com.star.launcher.bridge.model.LocalServerConfig
import com.star.launcher.bridge.storage.BridgeStoragePaths
import java.io.File
import java.util.Properties

/**
 * Manages `server.properties` and `eula.txt` for STAR-hosted local Minecraft Java servers.
 * Never bypasses or silently accepts the Mojang EULA without explicit user confirmation.
 */
class LocalServerConfigManager(
    private val paths: BridgeStoragePaths
) {
    fun validate(config: LocalServerConfig): ConfigValidationResult {
        val issues = mutableListOf<String>()
        if (config.javaPort !in 1024..65535) {
            issues += "Java server port ${config.javaPort} must be between 1024 and 65535 on Android"
        }
        if (config.worldName.isBlank() || config.worldName.contains("..") || config.worldName.contains('/') || config.worldName.contains('\\')) {
            issues += "World name '${config.worldName}' is invalid"
        }
        if (config.maxPlayers !in 1..200) {
            issues += "Max players (${config.maxPlayers}) must be between 1 and 200"
        }
        if (config.memoryLimitMb < LocalServerConfig.MIN_MEMORY_MB || config.memoryLimitMb > LocalServerConfig.MAX_MEMORY_MB) {
            issues += "Server memory (${config.memoryLimitMb} MB) must be between ${LocalServerConfig.MIN_MEMORY_MB} and ${LocalServerConfig.MAX_MEMORY_MB} MB"
        }
        if (!config.eulaAccepted) {
            issues += "Mojang Minecraft Server EULA must be explicitly accepted before starting"
        }
        return ConfigValidationResult(issues.isEmpty(), issues)
    }

    fun writeServerConfiguration(config: LocalServerConfig): Pair<File, File> {
        val validation = validate(config)
        require(validation.isValid) {
            "Invalid local Java server configuration: ${validation.issues.joinToString("; ")}"
        }
        val serverDir = paths.serverDir()
        serverDir.mkdirs()

        val propsFile = paths.serverPropertiesFile()
        val props = Properties()
        if (propsFile.isFile) {
            runCatching {
                propsFile.inputStream().use { props.load(it) }
            }
        }
        props.setProperty("server-ip", "")
        props.setProperty("server-port", config.javaPort.toString())
        props.setProperty("level-name", config.worldName.trim())
        props.setProperty("motd", config.motd.replace('\n', ' '))
        props.setProperty("max-players", config.maxPlayers.toString())
        props.setProperty("online-mode", config.onlineMode.toString())
        props.setProperty("enforce-secure-profile", config.enforceSecureProfile.toString())
        props.setProperty("enable-status", "true")
        props.setProperty("allow-flight", "true")
        props.setProperty("view-distance", props.getProperty("view-distance", "8"))
        props.setProperty("simulation-distance", props.getProperty("simulation-distance", "6"))

        val propsTmp = File(serverDir, "server.properties.tmp")
        propsTmp.outputStream().use { out ->
            props.store(out, "STAR Launcher Bedrock Bridge Local Java Server Properties")
        }
        if (!propsTmp.renameTo(propsFile)) {
            propsTmp.copyTo(propsFile, overwrite = true)
            propsTmp.delete()
        }

        val eulaFile = paths.serverEulaFile()
        eulaFile.writeText(
            "# Mojang Minecraft EULA accepted explicitly by user in STAR Launcher\n" +
                "eula=${config.eulaAccepted}\n",
            Charsets.UTF_8
        )

        return propsFile to eulaFile
    }

    fun readServerProperties(fallback: LocalServerConfig = LocalServerConfig()): LocalServerConfig {
        val propsFile = paths.serverPropertiesFile()
        val eulaFile = paths.serverEulaFile()
        val eulaAccepted = if (eulaFile.isFile) {
            eulaFile.readLines().any { it.trim().equals("eula=true", ignoreCase = true) }
        } else {
            fallback.eulaAccepted
        }
        if (!propsFile.isFile) return fallback.copy(eulaAccepted = eulaAccepted)

        val props = Properties()
        runCatching {
            propsFile.inputStream().use { props.load(it) }
        }
        return fallback.copy(
            javaPort = props.getProperty("server-port")?.toIntOrNull() ?: fallback.javaPort,
            worldName = props.getProperty("level-name")?.takeIf { it.isNotBlank() } ?: fallback.worldName,
            motd = props.getProperty("motd") ?: fallback.motd,
            maxPlayers = props.getProperty("max-players")?.toIntOrNull() ?: fallback.maxPlayers,
            onlineMode = props.getProperty("online-mode")?.toBooleanStrictOrNull() ?: fallback.onlineMode,
            enforceSecureProfile = props.getProperty("enforce-secure-profile")?.toBooleanStrictOrNull()
                ?: fallback.enforceSecureProfile,
            eulaAccepted = eulaAccepted
        )
    }
}
