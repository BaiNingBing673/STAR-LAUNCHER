package com.star.launcher.engine.adapter

import java.io.File

data class RuntimeDescriptor(
    val runtimeId: String,
    val displayName: String,
    val majorJavaVersion: Int,
    val arch: String,
    val versionString: String?,
    val isInternal: Boolean
)

data class RendererSpec(
    val rendererId: String,
    val displayName: String,
    val requiresVulkan: Boolean,
    val requiresGles3: Boolean
)

data class InputProfileSpec(
    val controlMapPath: String?,
    val mouseSpeedMultiplier: Float = 1.0f,
    val disableGestures: Boolean = false
)

data class EngineLaunchSpec(
    val instanceId: String,
    val versionId: String,
    val gameDirPath: String,
    val runtime: RuntimeDescriptor,
    val renderer: RendererSpec,
    val inputProfile: InputProfileSpec,
    val allocatedRamMb: Int,
    val customJvmArgs: String? = null
)

sealed interface EngineSessionEvent {
    val sessionId: String
    val timestampMs: Long

    data class Preparing(
        override val sessionId: String,
        override val timestampMs: Long,
        val instanceId: String
    ) : EngineSessionEvent

    data class Running(
        override val sessionId: String,
        override val timestampMs: Long
    ) : EngineSessionEvent

    data class Exited(
        override val sessionId: String,
        override val timestampMs: Long,
        val exitCode: Int
    ) : EngineSessionEvent
}

interface RuntimeFacade {
    fun listInstalledRuntimes(): List<RuntimeDescriptor>
    fun resolveRuntime(runtimeId: String): RuntimeDescriptor?
    fun isCompatibleWithJavaVersion(runtime: RuntimeDescriptor, requiredJavaMajor: Int): Boolean
}

interface RendererFacade {
    fun listSupportedRenderers(): List<RendererSpec>
    fun isRendererCompatible(rendererId: String): Boolean
}

interface InputManager {
    fun dispatchKey(keycode: Int, keychar: Char, scancode: Int, modifiers: Int, isDown: Boolean)
    fun dispatchCursor(x: Float, y: Float)
    fun dispatchMouseButton(button: Int, isDown: Boolean, x: Float, y: Float)
    fun isCursorGrabbed(): Boolean
}

interface SessionBridge {
    fun latestLogFile(): File
    fun sessionLogDirectory(): File
    fun recordEvent(event: EngineSessionEvent)
}

interface NativeEngineAdapter {
    fun verifyEngineLibrariesPresent(): List<String>
    fun validateLaunchSpec(spec: EngineLaunchSpec): List<String>
}
