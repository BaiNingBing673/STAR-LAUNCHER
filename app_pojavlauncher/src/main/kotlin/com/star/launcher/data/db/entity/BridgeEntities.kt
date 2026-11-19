package com.star.launcher.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.star.launcher.bridge.model.BridgeAuthMode
import com.star.launcher.bridge.model.BridgeConnectionStatus
import com.star.launcher.bridge.model.BridgeServerProfile
import com.star.launcher.bridge.model.ExternalServerSoftware
import com.star.launcher.bridge.model.GeyserAvailability

/**
 * External or discovered cross-platform server profile persisted by STAR Bedrock Bridge.
 * Deleting a profile removes only this launcher record and never deletes server world files.
 */
@Entity(
    tableName = "bridge_server_profiles",
    indices = [
        Index(value = ["isFavorite", "lastConnectedAtMs"]),
        Index(value = ["javaAddress", "javaPort"])
    ]
)
data class ServerProfileEntity(
    @PrimaryKey val profileId: String,
    val name: String,
    val description: String,
    val iconBase64: String?,
    val javaAddress: String,
    val javaPort: Int,
    val bedrockAddress: String,
    val bedrockPort: Int,
    val serverSoftware: String,
    val geyserAvailability: String,
    val authMode: String,
    val versionCompatibilitySummary: String,
    val connectionStatus: String,
    val isFavorite: Boolean,
    val lastConnectedAtMs: Long?,
    val lastCheckedAtMs: Long?,
    val createdAtMs: Long
) {
    fun toModel(): BridgeServerProfile = BridgeServerProfile(
        profileId = profileId,
        name = name,
        description = description,
        iconBase64 = iconBase64,
        javaAddress = javaAddress,
        javaPort = javaPort,
        bedrockAddress = bedrockAddress,
        bedrockPort = bedrockPort,
        serverSoftware = runCatching { ExternalServerSoftware.valueOf(serverSoftware) }
            .getOrDefault(ExternalServerSoftware.UNKNOWN),
        geyserAvailability = runCatching { GeyserAvailability.valueOf(geyserAvailability) }
            .getOrDefault(GeyserAvailability.UNVERIFIED),
        authMode = runCatching { BridgeAuthMode.valueOf(authMode) }
            .getOrDefault(BridgeAuthMode.ONLINE),
        versionCompatibilitySummary = versionCompatibilitySummary,
        connectionStatus = runCatching { BridgeConnectionStatus.valueOf(connectionStatus) }
            .getOrDefault(BridgeConnectionStatus.UNKNOWN),
        isFavorite = isFavorite,
        lastConnectedAtMs = lastConnectedAtMs,
        lastCheckedAtMs = lastCheckedAtMs,
        createdAtMs = createdAtMs
    )

    companion object {
        fun fromModel(model: BridgeServerProfile): ServerProfileEntity = ServerProfileEntity(
            profileId = model.profileId,
            name = model.name,
            description = model.description,
            iconBase64 = model.iconBase64,
            javaAddress = model.javaAddress,
            javaPort = model.javaPort,
            bedrockAddress = model.bedrockAddress,
            bedrockPort = model.bedrockPort,
            serverSoftware = model.serverSoftware.name,
            geyserAvailability = model.geyserAvailability.name,
            authMode = model.authMode.name,
            versionCompatibilitySummary = model.versionCompatibilitySummary,
            connectionStatus = model.connectionStatus.name,
            isFavorite = model.isFavorite,
            lastConnectedAtMs = model.lastConnectedAtMs,
            lastCheckedAtMs = model.lastCheckedAtMs,
            createdAtMs = model.createdAtMs
        )
    }
}
