package com.star.launcher.engine.adapter

import android.content.Context
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.multirt.MultiRTUtils
import net.kdt.pojavlaunch.multirt.Runtime
import org.lwjgl.glfw.CallbackBridge
import java.io.File

/**
 * Pure mapper from upstream [Runtime] to immutable [RuntimeDescriptor].
 */
fun mapUpstreamRuntime(runtime: Runtime): RuntimeDescriptor = RuntimeDescriptor(
    runtimeId = runtime.name,
    displayName = runtime.name,
    majorJavaVersion = runtime.javaVersion,
    arch = runtime.arch ?: "unknown",
    versionString = runtime.versionString,
    isInternal = runtime.name == "Internal"
)

/**
 * Derives capability flags from upstream renderer IDs without altering upstream arrays.
 */
fun mapRendererSpec(rendererId: String, displayName: String): RendererSpec = RendererSpec(
    rendererId = rendererId,
    displayName = displayName,
    requiresVulkan = rendererId.contains("vulkan", ignoreCase = true) ||
        rendererId.contains("zink", ignoreCase = true),
    requiresGles3 = rendererId.contains("ltw", ignoreCase = true) ||
        rendererId.contains("opengles3", ignoreCase = true) ||
        rendererId.contains("mobileglues", ignoreCase = true)
)

class PojavRuntimeFacade : RuntimeFacade {
    override fun listInstalledRuntimes(): List<RuntimeDescriptor> =
        MultiRTUtils.getInstalledCompatibleRuntimes().map(::mapUpstreamRuntime)

    override fun resolveRuntime(runtimeId: String): RuntimeDescriptor? {
        val report = MultiRTUtils.inspectRuntime(runtimeId, 0, true)
        val runtime = report.runtime
        return if (report.status == MultiRTUtils.RuntimeValidationStatus.VALID && runtime != null) {
            mapUpstreamRuntime(runtime)
        } else {
            null
        }
    }

    override fun isCompatibleWithJavaVersion(runtime: RuntimeDescriptor, requiredJavaMajor: Int): Boolean =
        runtime.majorJavaVersion >= requiredJavaMajor && runtime.majorJavaVersion > 0
}

class PojavRendererFacade(context: Context) : RendererFacade {
    private val appContext = context.applicationContext

    override fun listSupportedRenderers(): List<RendererSpec> {
        val list = Tools.getCompatibleRenderers(appContext)
        return list.rendererIds.mapIndexed { idx, id ->
            val label = list.rendererDisplayNames.getOrNull(idx) ?: id
            mapRendererSpec(id, label)
        }
    }

    override fun isRendererCompatible(rendererId: String): Boolean =
        Tools.checkRendererCompatible(appContext, rendererId)
}

class PojavInputManager : InputManager {
    override fun dispatchKey(
        keycode: Int,
        keychar: Char,
        scancode: Int,
        modifiers: Int,
        isDown: Boolean
    ) {
        CallbackBridge.sendKeycode(keycode, keychar, scancode, modifiers, isDown)
    }

    override fun dispatchCursor(x: Float, y: Float) {
        CallbackBridge.sendCursorPos(x, y)
    }

    override fun dispatchMouseButton(button: Int, isDown: Boolean, x: Float, y: Float) {
        CallbackBridge.putMouseEventWithCoords(button, isDown, x, y)
    }

    override fun isCursorGrabbed(): Boolean = CallbackBridge.isGrabbing()
}

class PojavSessionBridge(
    private val onEvent: (EngineSessionEvent) -> Unit = {}
) : SessionBridge {
    override fun latestLogFile(): File = File(Tools.DIR_GAME_HOME, "latestlog.txt")

    override fun sessionLogDirectory(): File = File(Tools.DIR_GAME_HOME)

    override fun recordEvent(event: EngineSessionEvent) {
        onEvent(event)
    }
}

class PojavNativeEngineAdapter(
    private val nativeLibDirProvider: () -> String? = { Tools.NATIVE_LIB_DIR }
) : NativeEngineAdapter {

    override fun verifyEngineLibrariesPresent(): List<String> {
        val dirPath = nativeLibDirProvider() ?: return REQUIRED_CORE_LIBRARIES
        val dir = File(dirPath)
        if (!dir.isDirectory) return REQUIRED_CORE_LIBRARIES
        return REQUIRED_CORE_LIBRARIES.filterNot { libName ->
            File(dir, libName).isFile
        }
    }

    override fun validateLaunchSpec(spec: EngineLaunchSpec): List<String> {
        val issues = mutableListOf<String>()
        if (spec.instanceId.isBlank()) issues += "instanceId cannot be blank"
        if (spec.versionId.isBlank() || spec.versionId.equals("unknown", ignoreCase = true)) {
            issues += "versionId cannot be blank or unknown"
        }
        if (spec.allocatedRamMb < MIN_RAM_MB) {
            issues += "allocatedRamMb (${spec.allocatedRamMb}) must be at least $MIN_RAM_MB MB"
        }
        if (spec.runtime.majorJavaVersion <= 0) {
            issues += "runtime ${spec.runtime.runtimeId} reports unknown Java major version"
        }
        return issues
    }

    companion object {
        const val MIN_RAM_MB = 256
        val REQUIRED_CORE_LIBRARIES = listOf(
            "libpojavexec.so",
            "libpojavexec_awt.so",
            "libexithook.so"
        )
    }
}
