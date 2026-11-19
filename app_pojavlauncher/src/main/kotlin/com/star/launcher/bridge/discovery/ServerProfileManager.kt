package com.star.launcher.bridge.discovery

import com.star.launcher.bridge.model.BridgeAuthMode
import com.star.launcher.bridge.model.BridgeConnectionStatus
import com.star.launcher.bridge.model.BridgeServerProfile
import com.star.launcher.bridge.model.ExternalServerSoftware
import com.star.launcher.bridge.model.GeyserAvailability
import com.star.launcher.bridge.model.GeyserConfig
import com.star.launcher.bridge.model.LocalServerConfig
import com.star.launcher.data.db.dao.ServerProfileDao
import com.star.launcher.data.db.entity.ServerProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class EndpointValidationResult(
    val isValid: Boolean,
    val issues: List<String> = emptyList()
)

/**
 * Manages CRUD, duplication, favorites, endpoint validation, and JSON import/export
 * for external cross-platform server profiles.
 * Never deletes any Minecraft world or server directory when removing a profile.
 */
class ServerProfileManager(
    private val dao: ServerProfileDao,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    fun observeProfiles(): Flow<List<BridgeServerProfile>> =
        dao.observeAll().map { entities -> entities.map { it.toModel() } }

    suspend fun listProfiles(): List<BridgeServerProfile> =
        dao.getAll().map { it.toModel() }

    suspend fun getProfile(profileId: String): BridgeServerProfile? =
        dao.getById(profileId)?.toModel()

    fun validateEndpoints(
        name: String,
        javaAddress: String,
        javaPort: Int,
        bedrockAddress: String,
        bedrockPort: Int
    ): EndpointValidationResult {
        val issues = mutableListOf<String>()
        if (name.isBlank()) issues += "Server name cannot be blank"
        if (javaAddress.isBlank() || javaAddress.contains(' ')) {
            issues += "Java endpoint address is invalid"
        }
        if (javaPort !in 1..65535) {
            issues += "Java port must be between 1 and 65535"
        }
        if (bedrockAddress.isBlank() || bedrockAddress.contains(' ')) {
            issues += "Bedrock endpoint address is invalid"
        }
        if (bedrockPort !in 1..65535) {
            issues += "Bedrock port must be between 1 and 65535"
        }
        return EndpointValidationResult(issues.isEmpty(), issues)
    }

    suspend fun addProfile(
        name: String,
        description: String = "",
        javaAddress: String,
        javaPort: Int = LocalServerConfig.DEFAULT_JAVA_PORT,
        bedrockAddress: String = javaAddress,
        bedrockPort: Int = GeyserConfig.DEFAULT_BEDROCK_PORT,
        serverSoftware: ExternalServerSoftware = ExternalServerSoftware.UNKNOWN,
        authMode: BridgeAuthMode = BridgeAuthMode.ONLINE
    ): BridgeServerProfile {
        val validation = validateEndpoints(name, javaAddress, javaPort, bedrockAddress, bedrockPort)
        require(validation.isValid) { validation.issues.joinToString("; ") }
        val profile = BridgeServerProfile(
            profileId = idGenerator(),
            name = name.trim(),
            description = description.trim(),
            javaAddress = javaAddress.trim(),
            javaPort = javaPort,
            bedrockAddress = bedrockAddress.trim(),
            bedrockPort = bedrockPort,
            serverSoftware = serverSoftware,
            authMode = authMode,
            createdAtMs = clockMillis()
        )
        dao.upsert(ServerProfileEntity.fromModel(profile))
        return profile
    }

    suspend fun updateProfile(profile: BridgeServerProfile): BridgeServerProfile {
        val validation = validateEndpoints(
            profile.name,
            profile.javaAddress,
            profile.javaPort,
            profile.bedrockAddress,
            profile.bedrockPort
        )
        require(validation.isValid) { validation.issues.joinToString("; ") }
        val cleaned = profile.copy(
            name = profile.name.trim(),
            description = profile.description.trim(),
            javaAddress = profile.javaAddress.trim(),
            bedrockAddress = profile.bedrockAddress.trim()
        )
        dao.upsert(ServerProfileEntity.fromModel(cleaned))
        return cleaned
    }

    suspend fun deleteProfile(profileId: String) {
        // Only removes the Room profile entry; never deletes external or local server data.
        dao.deleteById(profileId)
    }

    suspend fun toggleFavorite(profileId: String): BridgeServerProfile? {
        val existing = getProfile(profileId) ?: return null
        val updated = existing.copy(isFavorite = !existing.isFavorite)
        dao.upsert(ServerProfileEntity.fromModel(updated))
        return updated
    }

    suspend fun duplicateProfile(profileId: String): BridgeServerProfile? {
        val existing = getProfile(profileId) ?: return null
        val copy = existing.copy(
            profileId = idGenerator(),
            name = "${existing.name} (Copy)",
            isFavorite = false,
            lastConnectedAtMs = null,
            createdAtMs = clockMillis()
        )
        dao.upsert(ServerProfileEntity.fromModel(copy))
        return copy
    }

    suspend fun markConnected(profileId: String) {
        val existing = getProfile(profileId) ?: return
        val updated = existing.copy(
            lastConnectedAtMs = clockMillis(),
            connectionStatus = BridgeConnectionStatus.ONLINE
        )
        dao.upsert(ServerProfileEntity.fromModel(updated))
    }

    suspend fun exportProfilesJson(): String {
        val profiles = listProfiles()
        val array = JSONArray()
        profiles.forEach { p ->
            array.put(
                JSONObject().apply {
                    put("name", p.name)
                    put("description", p.description)
                    put("javaAddress", p.javaAddress)
                    put("javaPort", p.javaPort)
                    put("bedrockAddress", p.bedrockAddress)
                    put("bedrockPort", p.bedrockPort)
                    put("serverSoftware", p.serverSoftware.name)
                    put("authMode", p.authMode.name)
                    put("isFavorite", p.isFavorite)
                }
            )
        }
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("exportedAtMs", clockMillis())
            put("profiles", array)
        }.toString(2)
    }

    suspend fun importProfilesJson(rawJson: String): List<BridgeServerProfile> {
        val root = JSONObject(rawJson)
        val array = root.optJSONArray("profiles") ?: return emptyList()
        val imported = mutableListOf<BridgeServerProfile>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim()
            val javaAddr = obj.optString("javaAddress").trim()
            val javaPort = obj.optInt("javaPort", LocalServerConfig.DEFAULT_JAVA_PORT)
            val bedrockAddr = obj.optString("bedrockAddress", javaAddr).trim()
            val bedrockPort = obj.optInt("bedrockPort", GeyserConfig.DEFAULT_BEDROCK_PORT)
            if (!validateEndpoints(name, javaAddr, javaPort, bedrockAddr, bedrockPort).isValid) {
                continue
            }
            val software = runCatching {
                ExternalServerSoftware.valueOf(obj.optString("serverSoftware", "UNKNOWN"))
            }.getOrDefault(ExternalServerSoftware.UNKNOWN)
            val authMode = runCatching {
                BridgeAuthMode.valueOf(obj.optString("authMode", "ONLINE"))
            }.getOrDefault(BridgeAuthMode.ONLINE)

            val created = BridgeServerProfile(
                profileId = idGenerator(),
                name = name,
                description = obj.optString("description", ""),
                javaAddress = javaAddr,
                javaPort = javaPort,
                bedrockAddress = bedrockAddr,
                bedrockPort = bedrockPort,
                serverSoftware = software,
                geyserAvailability = GeyserAvailability.UNVERIFIED,
                authMode = authMode,
                isFavorite = obj.optBoolean("isFavorite", false),
                createdAtMs = clockMillis()
            )
            dao.upsert(ServerProfileEntity.fromModel(created))
            imported += created
        }
        return imported
    }
}
