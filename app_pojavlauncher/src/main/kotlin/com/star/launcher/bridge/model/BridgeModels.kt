package com.star.launcher.bridge.model

/**
 * Operating mode for STAR Bedrock Bridge.
 */
enum class BridgeMode {
    /** STAR hosts a local Minecraft Java server and manages Geyser Standalone alongside it. */
    LOCAL_HOST,

    /** STAR connects to or checks an external Java server that already exposes Geyser/Bedrock. */
    EXTERNAL_BRIDGE
}

/**
 * Authoritative lifecycle states for the Bridge Controller state machine.
 */
enum class BridgeLifecycleState {
    IDLE,
    VALIDATING,
    INSTALLING_RUNTIME,
    STARTING_SERVER,
    STARTING_GEYSER,
    RUNNING,
    DEGRADED,
    STOPPING,
    ERROR
}

enum class LocalServerSoftware(val displayName: String, val supportsFloodgatePlugin: Boolean) {
    PAPER("PaperMC", supportsFloodgatePlugin = true),
    VANILLA("Vanilla Mojang", supportsFloodgatePlugin = false)
}

enum class ExternalServerSoftware(val displayName: String) {
    PAPER("Paper"),
    SPIGOT("Spigot"),
    PURPUR("Purpur"),
    FABRIC("Fabric"),
    NEOFORGE("NeoForge"),
    FORGE("Forge"),
    VELOCITY("Velocity Proxy"),
    BUNGEECORD("BungeeCord / Waterfall"),
    VANILLA("Vanilla"),
    UNKNOWN("Unknown")
}

enum class BridgeAuthMode(val configValue: String, val displayName: String) {
    ONLINE("online", "Online (Microsoft/Mojang Auth)"),
    FLOODGATE("floodgate", "Floodgate (Bedrock Auth Bridge)"),
    OFFLINE("offline", "Offline (Cracked / Local Dev Only)")
}

enum class BridgeTransportType(val configValue: String, val displayName: String) {
    RAKNET("raknet", "RakNet (UDP + TCP)"),
    NETHERNET("nethernet", "NetherNet (UDP + TCP)")
}

enum class GeyserAvailability {
    DETECTED_COMPATIBLE,
    DETECTED_OUTDATED,
    NOT_DETECTED,
    UNVERIFIED
}

enum class BridgeConnectionStatus {
    ONLINE,
    OFFLINE,
    UNREACHABLE,
    CHECKING,
    UNKNOWN
}

data class LocalServerConfig(
    val enabled: Boolean = false,
    val software: LocalServerSoftware = LocalServerSoftware.PAPER,
    val minecraftVersion: String = "1.21.4",
    val javaPort: Int = DEFAULT_JAVA_PORT,
    val worldName: String = "world",
    val motd: String = "STAR Launcher Cross-Play World",
    val maxPlayers: Int = 10,
    val onlineMode: Boolean = true,
    val enforceSecureProfile: Boolean = true,
    val memoryLimitMb: Int = 1024,
    val eulaAccepted: Boolean = false,
    val customJvmArgs: String = "",
    val preferredRuntimeName: String? = null
) {
    companion object {
        const val DEFAULT_JAVA_PORT = 25565
        const val MIN_MEMORY_MB = 512
        const val MAX_MEMORY_MB = 8192
    }
}

data class GeyserConfig(
    val bedrockAddress: String = "0.0.0.0",
    val bedrockPort: Int = DEFAULT_BEDROCK_PORT,
    val remoteAddress: String = "127.0.0.1",
    val remotePort: Int = LocalServerConfig.DEFAULT_JAVA_PORT,
    val authType: BridgeAuthMode = BridgeAuthMode.ONLINE,
    val floodgateKeyFile: String = "key.pem",
    val motd1: String = "STAR Bedrock Bridge",
    val motd2: String = "Cross-Platform Java + Bedrock World",
    val serverName: String = "STAR Bridge",
    val transport: BridgeTransportType = BridgeTransportType.RAKNET,
    val memoryLimitMb: Int = 384,
    val preferredRuntimeName: String? = null,
    val debugMode: Boolean = false
) {
    companion object {
        const val DEFAULT_BEDROCK_PORT = 19132
        const val MIN_MEMORY_MB = 256
        const val MAX_MEMORY_MB = 2048
    }
}

data class BridgeAdvancedConfig(
    val autoCheckUpdates: Boolean = true,
    val autoCrashRecovery: Boolean = true,
    val maxCrashRestarts: Int = 2,
    val diagnosticLogRetentionLines: Int = 800,
    val debugMode: Boolean = false,
    val preferIpv6: Boolean = false,
    val allowExternalLanConnections: Boolean = true
)

data class RuntimeCheckDetails(
    val isCompatible: Boolean,
    val selectedRuntimeName: String?,
    val selectedJavaVersion: Int,
    val requiredJavaVersion: Int,
    val deviceArch: String,
    val runtimeArch: String?,
    val hasLibJvm: Boolean,
    val availableMemoryMb: Long,
    val requiredMemoryMb: Int,
    val storageWritable: Boolean,
    val freeStorageMb: Long,
    val socketBindSupported: Boolean,
    val summary: String,
    val issues: List<String> = emptyList()
)

data class NetworkEndpointsSnapshot(
    val localhostAddress: String = "127.0.0.1",
    val lanIpv4Addresses: List<String> = emptyList(),
    val lanIpv6Addresses: List<String> = emptyList(),
    val isLanConnected: Boolean = false,
    val javaPort: Int = LocalServerConfig.DEFAULT_JAVA_PORT,
    val bedrockPort: Int = GeyserConfig.DEFAULT_BEDROCK_PORT,
    val bedrockTransport: BridgeTransportType = BridgeTransportType.RAKNET,
    val javaPortAvailable: Boolean = true,
    val bedrockPortAvailable: Boolean = true,
    val portConflictReason: String? = null,
    val publicAddressGuidance: String = "Public internet play requires explicit router port forwarding (TCP for Java, UDP+TCP for Bedrock) or a user-managed tunnel. STAR never opens router ports automatically."
) {
    val primaryLanIpv4: String?
        get() = lanIpv4Addresses.firstOrNull()
}

data class BridgeHealthSnapshot(
    val serverProcessAlive: Boolean = false,
    val serverPid: Int? = null,
    val serverRssMb: Int = 0,
    val geyserProcessAlive: Boolean = false,
    val geyserPid: Int? = null,
    val geyserRssMb: Int = 0,
    val activeJavaPlayers: Int = 0,
    val activeBedrockPlayers: Int = 0,
    val maxPlayers: Int = 10,
    val javaEndpointReachable: Boolean = false,
    val bedrockEndpointReachable: Boolean = false,
    val javaLatencyMs: Long? = null,
    val bedrockLatencyMs: Long? = null,
    val startedAtMs: Long? = null,
    val lastHeartbeatMs: Long? = null,
    val restartAttempts: Int = 0
) {
    val totalPlayers: Int
        get() = (activeJavaPlayers + activeBedrockPlayers).coerceAtLeast(0)
}

data class InstalledArtifactMetadata(
    val projectId: String,
    val version: String,
    val buildNumber: Int,
    val fileName: String,
    val sha256: String,
    val installedAtMs: Long,
    val jarPath: String,
    val previousVersion: String? = null,
    val previousJarPath: String? = null
)

data class GeyserReleaseInfo(
    val projectId: String,
    val version: String,
    val buildNumber: Int,
    val channel: String,
    val artifactName: String,
    val downloadUrl: String,
    val sha256: String,
    val publishedAt: String? = null
)

data class PaperBuildInfo(
    val minecraftVersion: String,
    val buildNumber: Int,
    val fileName: String,
    val downloadUrl: String,
    val sha256: String
)

data class VanillaServerInfo(
    val minecraftVersion: String,
    val downloadUrl: String,
    val sha1: String,
    val sizeBytes: Long
)

data class BridgeServerProfile(
    val profileId: String,
    val name: String,
    val description: String = "",
    val iconBase64: String? = null,
    val javaAddress: String,
    val javaPort: Int = LocalServerConfig.DEFAULT_JAVA_PORT,
    val bedrockAddress: String,
    val bedrockPort: Int = GeyserConfig.DEFAULT_BEDROCK_PORT,
    val serverSoftware: ExternalServerSoftware = ExternalServerSoftware.UNKNOWN,
    val geyserAvailability: GeyserAvailability = GeyserAvailability.UNVERIFIED,
    val authMode: BridgeAuthMode = BridgeAuthMode.ONLINE,
    val versionCompatibilitySummary: String = "Not checked",
    val connectionStatus: BridgeConnectionStatus = BridgeConnectionStatus.UNKNOWN,
    val isFavorite: Boolean = false,
    val lastConnectedAtMs: Long? = null,
    val lastCheckedAtMs: Long? = null,
    val createdAtMs: Long = System.currentTimeMillis()
)

enum class CompatibilityItemStatus {
    COMPATIBLE,
    WARNING,
    INCOMPATIBLE,
    UNVERIFIED
}

data class CompatibilityCheckItem(
    val component: String,
    val status: CompatibilityItemStatus,
    val summary: String,
    val detail: String
)

data class BridgeCompatibilityReport(
    val items: List<CompatibilityCheckItem>,
    val overallCompatible: Boolean,
    val summaryHeadline: String,
    val serverSideGeyserRequiredNote: String? = null,
    val generatedAtMs: Long = System.currentTimeMillis()
) {
    fun toReadableText(): String = buildString {
        items.forEach { item ->
            val label = when (item.status) {
                CompatibilityItemStatus.COMPATIBLE -> "Compatible"
                CompatibilityItemStatus.WARNING -> "Warning"
                CompatibilityItemStatus.INCOMPATIBLE -> "Incompatible"
                CompatibilityItemStatus.UNVERIFIED -> "Unverified"
            }
            append(item.component).append(": ").append(label)
            if (item.summary.isNotBlank()) {
                append(" (").append(item.summary).append(")")
            }
            append('\n')
        }
        if (!serverSideGeyserRequiredNote.isNullOrBlank()) {
            append('\n').append(serverSideGeyserRequiredNote)
        }
    }.trim()
}

data class BridgeLogEntry(
    val timestampMs: Long,
    val source: String,
    val level: String,
    val message: String
)

data class BridgeStatusSnapshot(
    val mode: BridgeMode = BridgeMode.LOCAL_HOST,
    val lifecycleState: BridgeLifecycleState = BridgeLifecycleState.IDLE,
    val localServerConfig: LocalServerConfig = LocalServerConfig(),
    val geyserConfig: GeyserConfig = GeyserConfig(),
    val advancedConfig: BridgeAdvancedConfig = BridgeAdvancedConfig(),
    val geyserArtifact: InstalledArtifactMetadata? = null,
    val floodgateArtifact: InstalledArtifactMetadata? = null,
    val localServerArtifact: InstalledArtifactMetadata? = null,
    val runtimeValidation: RuntimeCheckDetails? = null,
    val serverRuntimeValidation: RuntimeCheckDetails? = null,
    val networkEndpoints: NetworkEndpointsSnapshot = NetworkEndpointsSnapshot(),
    val health: BridgeHealthSnapshot = BridgeHealthSnapshot(),
    val floodgateKeyPresent: Boolean = false,
    val floodgateKeyBackedUpInVault: Boolean = false,
    val lastError: BridgeDiagnosticError? = null,
    val statusMessage: String = "Bridge idle",
    val activeProfileId: String? = null
)
