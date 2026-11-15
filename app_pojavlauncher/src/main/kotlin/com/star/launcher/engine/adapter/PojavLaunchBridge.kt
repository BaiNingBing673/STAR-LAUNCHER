package com.star.launcher.engine.adapter

import android.content.Intent
import android.os.Bundle
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile
import java.io.File

data class AetherLaunchPayload(
    val instanceId: String,
    val sessionId: String,
    val instanceName: String,
    val versionId: String,
    val gameDirPath: String,
    val runtimeId: String?,
    val rendererId: String?,
    val allocatedRamMb: Int,
    val customJvmArgs: String?,
    val controlMapPath: String?,
    val accountName: String?,
    val useAngle: Boolean = false
)

/**
 * Bridges immutable STAR Launcher launch specs across the `:launcher` -> `:game` process boundary
 * via Intent extras and constructs an in-memory [MinecraftProfile] without mutating `launcher_profiles.json`.
 */
object PojavLaunchBridge {
    const val EXTRA_INSTANCE_ID = "com.star.launcher.extra.INSTANCE_ID"
    const val EXTRA_SESSION_ID = "com.star.launcher.extra.SESSION_ID"
    const val EXTRA_INSTANCE_NAME = "com.star.launcher.extra.INSTANCE_NAME"
    const val EXTRA_VERSION_ID = "com.star.launcher.extra.VERSION_ID"
    const val EXTRA_GAME_DIR_PATH = "com.star.launcher.extra.GAME_DIR_PATH"
    const val EXTRA_RUNTIME_ID = "com.star.launcher.extra.RUNTIME_ID"
    const val EXTRA_RENDERER_ID = "com.star.launcher.extra.RENDERER_ID"
    const val EXTRA_ALLOCATED_RAM_MB = "com.star.launcher.extra.ALLOCATED_RAM_MB"
    const val EXTRA_CUSTOM_JVM_ARGS = "com.star.launcher.extra.CUSTOM_JVM_ARGS"
    const val EXTRA_CONTROL_MAP_PATH = "com.star.launcher.extra.CONTROL_MAP_PATH"
    const val EXTRA_ACCOUNT_NAME = "com.star.launcher.extra.ACCOUNT_NAME"
    const val EXTRA_USE_ANGLE = "com.star.launcher.extra.USE_ANGLE"

    @JvmStatic
    fun fromSpec(
        spec: EngineLaunchSpec,
        sessionId: String,
        instanceName: String,
        accountName: String? = null,
        useAngle: Boolean = false
    ): AetherLaunchPayload = AetherLaunchPayload(
        instanceId = spec.instanceId,
        sessionId = sessionId,
        instanceName = instanceName,
        versionId = spec.versionId,
        gameDirPath = spec.gameDirPath,
        runtimeId = spec.runtime.runtimeId.takeIf { it.isNotBlank() && it != "Internal" },
        rendererId = spec.renderer.rendererId.takeIf { it.isNotBlank() },
        allocatedRamMb = spec.allocatedRamMb.coerceAtLeast(PojavNativeEngineAdapter.MIN_RAM_MB),
        customJvmArgs = spec.customJvmArgs?.takeIf { it.isNotBlank() },
        controlMapPath = spec.inputProfile.controlMapPath?.takeIf { it.isNotBlank() },
        accountName = accountName?.takeIf { it.isNotBlank() },
        useAngle = useAngle
    )

    @JvmStatic
    fun toBundle(payload: AetherLaunchPayload): Bundle = Bundle().apply {
        putString(EXTRA_INSTANCE_ID, payload.instanceId)
        putString(EXTRA_SESSION_ID, payload.sessionId)
        putString(EXTRA_INSTANCE_NAME, payload.instanceName)
        putString(EXTRA_VERSION_ID, payload.versionId)
        putString(EXTRA_GAME_DIR_PATH, payload.gameDirPath)
        payload.runtimeId?.let { putString(EXTRA_RUNTIME_ID, it) }
        payload.rendererId?.let { putString(EXTRA_RENDERER_ID, it) }
        putInt(EXTRA_ALLOCATED_RAM_MB, payload.allocatedRamMb)
        payload.customJvmArgs?.let { putString(EXTRA_CUSTOM_JVM_ARGS, it) }
        payload.controlMapPath?.let { putString(EXTRA_CONTROL_MAP_PATH, it) }
        payload.accountName?.let { putString(EXTRA_ACCOUNT_NAME, it) }
        putBoolean(EXTRA_USE_ANGLE, payload.useAngle)
    }

    @JvmStatic
    fun writeToIntent(intent: Intent, payload: AetherLaunchPayload): Intent {
        intent.putExtras(toBundle(payload))
        return intent
    }

    @JvmStatic
    fun fromIntent(intent: Intent?): AetherLaunchPayload? {
        val extras = intent?.extras ?: return null
        val instanceId = extras.getString(EXTRA_INSTANCE_ID)?.takeIf { it.isNotBlank() } ?: return null
        val sessionId = extras.getString(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() } ?: return null
        val versionId = extras.getString(EXTRA_VERSION_ID)?.takeIf { it.isNotBlank() } ?: return null
        val gameDirPath = extras.getString(EXTRA_GAME_DIR_PATH)?.takeIf { it.isNotBlank() } ?: return null
        return AetherLaunchPayload(
            instanceId = instanceId,
            sessionId = sessionId,
            instanceName = extras.getString(EXTRA_INSTANCE_NAME)?.ifBlank { instanceId } ?: instanceId,
            versionId = versionId,
            gameDirPath = gameDirPath,
            runtimeId = extras.getString(EXTRA_RUNTIME_ID)?.takeIf { it.isNotBlank() },
            rendererId = extras.getString(EXTRA_RENDERER_ID)?.takeIf { it.isNotBlank() },
            allocatedRamMb = extras.getInt(EXTRA_ALLOCATED_RAM_MB, 2048)
                .coerceAtLeast(PojavNativeEngineAdapter.MIN_RAM_MB),
            customJvmArgs = extras.getString(EXTRA_CUSTOM_JVM_ARGS)?.takeIf { it.isNotBlank() },
            controlMapPath = extras.getString(EXTRA_CONTROL_MAP_PATH)?.takeIf { it.isNotBlank() },
            accountName = extras.getString(EXTRA_ACCOUNT_NAME)?.takeIf { it.isNotBlank() },
            useAngle = extras.getBoolean(EXTRA_USE_ANGLE, false)
        )
    }

    /**
     * Converts an isolated instance path into `amethyst://<relpath>` when inside `gameHomeDir`,
     * or preserves the canonical absolute path for external storage paths.
     */
    @JvmStatic
    fun formatGameDirForProfile(gameDirPath: String, gameHomeDir: String? = Tools.DIR_GAME_HOME): String {
        val normalizedHome = gameHomeDir?.trimEnd('/', '\\')
        if (!normalizedHome.isNullOrBlank()) {
            val targetCanonical = File(gameDirPath).path.replace('\\', '/')
            val homeCanonical = File(normalizedHome).path.replace('\\', '/')
            val prefix = "$homeCanonical/"
            if (targetCanonical.startsWith(prefix)) {
                return Tools.LAUNCHERPROFILES_RTPREFIX + targetCanonical.removePrefix(prefix)
            }
        }
        return gameDirPath
    }

    @JvmStatic
    fun toTransientMinecraftProfile(
        payload: AetherLaunchPayload,
        gameHomeDir: String? = Tools.DIR_GAME_HOME
    ): MinecraftProfile = MinecraftProfile().apply {
        name = payload.instanceName
        lastVersionId = payload.versionId
        gameDir = formatGameDirForProfile(payload.gameDirPath, gameHomeDir)
        javaDir = payload.runtimeId
            ?.takeIf { it.isNotBlank() && it != "Internal" }
            ?.let { Tools.LAUNCHERPROFILES_RTPREFIX + it }
        javaArgs = payload.customJvmArgs
        pojavRendererName = payload.rendererId
        useANGLE = payload.useAngle
        controlFile = payload.controlMapPath
    }
}
