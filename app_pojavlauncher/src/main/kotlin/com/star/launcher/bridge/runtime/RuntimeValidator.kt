package com.star.launcher.bridge.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.star.launcher.bridge.model.RuntimeCheckDetails
import com.star.launcher.bridge.storage.BridgeStoragePaths
import net.kdt.pojavlaunch.Architecture
import net.kdt.pojavlaunch.multirt.MultiRTUtils
import net.kdt.pojavlaunch.multirt.Runtime
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket

data class InstalledRuntimeCandidate(
    val name: String,
    val javaVersion: Int,
    val arch: String?,
    val rootDir: File,
    val hasLibJvm: Boolean,
    val isArchCompatible: Boolean
)

interface BridgeRuntimeEnvironment {
    fun listCandidates(): List<InstalledRuntimeCandidate>
    fun devicePrimaryAbi(): String
    fun availableRamMb(): Long
    fun probeSocketBind(): Boolean
}

class AndroidBridgeRuntimeEnvironment(
    context: Context
) : BridgeRuntimeEnvironment {
    private val appContext = context.applicationContext

    override fun listCandidates(): List<InstalledRuntimeCandidate> {
        val folder = MultiRTUtils.getRuntimeFolder()
        val runtimes: List<Runtime> = runCatching { MultiRTUtils.getInstalledRuntimes() }.getOrDefault(emptyList())
        return runtimes.map { rt ->
            val fresh = runCatching { MultiRTUtils.forceReread(rt.name) }.getOrDefault(rt)
            val rtDir = File(folder, fresh.name)
            val arch = fresh.arch
            val hasJvm = MultiRTUtils.hasJvmLibrary(rtDir, arch)
            val archOk = !arch.isNullOrBlank() && Architecture.isRuntimeArchCompatible(arch)
            InstalledRuntimeCandidate(
                name = fresh.name,
                javaVersion = fresh.javaVersion,
                arch = arch,
                rootDir = rtDir,
                hasLibJvm = hasJvm,
                isArchCompatible = archOk
            )
        }
    }

    override fun devicePrimaryAbi(): String =
        Build.SUPPORTED_ABIS?.firstOrNull() ?: Architecture.archAsString(Architecture.getDeviceArchitecture())

    override fun availableRamMb(): Long {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am != null) {
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            if (info.availMem > 0L) {
                return info.availMem / (1024L * 1024L)
            }
        }
        return (java.lang.Runtime.getRuntime().maxMemory() / (1024L * 1024L)).coerceAtLeast(512L)
    }

    override fun probeSocketBind(): Boolean = runCatching {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { tcp ->
            DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { udp ->
                tcp.localPort > 0 && udp.localPort > 0
            }
        }
    }.getOrDefault(false)
}

/**
 * Validates whether an isolated Java runtime can host Geyser Standalone (Java 21+)
 * or a local Minecraft Java server without mutating or assuming compatibility with the
 * client's active Minecraft runtime.
 */
class RuntimeValidator(
    private val paths: BridgeStoragePaths,
    private val environment: BridgeRuntimeEnvironment
) {
    fun validateForGeyser(
        preferredRuntimeName: String? = null,
        requiredMemoryMb: Int = 384
    ): RuntimeCheckDetails = validateRuntime(
        roleLabel = "Geyser Standalone",
        requiredJavaMajor = GEYSER_MIN_JAVA_VERSION,
        preferredRuntimeName = preferredRuntimeName,
        requiredMemoryMb = requiredMemoryMb,
        targetDir = paths.geyserDir()
    )

    fun validateForLocalServer(
        minecraftVersion: String,
        preferredRuntimeName: String? = null,
        requiredMemoryMb: Int = 1024
    ): RuntimeCheckDetails {
        val reqJava = requiredJavaForMinecraftServer(minecraftVersion)
        return validateRuntime(
            roleLabel = "Local Java Server ($minecraftVersion)",
            requiredJavaMajor = reqJava,
            preferredRuntimeName = preferredRuntimeName,
            requiredMemoryMb = requiredMemoryMb,
            targetDir = paths.serverDir()
        )
    }

    fun validateRuntime(
        roleLabel: String,
        requiredJavaMajor: Int,
        preferredRuntimeName: String?,
        requiredMemoryMb: Int,
        targetDir: File
    ): RuntimeCheckDetails {
        val issues = mutableListOf<String>()
        val deviceAbi = environment.devicePrimaryAbi()
        val candidates = environment.listCandidates()
        val availableRam = environment.availableRamMb()
        val socketOk = environment.probeSocketBind()

        val storageWritable = checkStorageWritable(targetDir)
        val freeStorageMb = (targetDir.usableSpace / (1024L * 1024L)).coerceAtLeast(0L)

        if (!storageWritable) {
            issues += "Bridge storage directory is not writable: ${targetDir.absolutePath}"
        }
        if (freeStorageMb in 1 until MIN_REQUIRED_STORAGE_MB) {
            issues += "Low free storage (${freeStorageMb} MB); at least $MIN_REQUIRED_STORAGE_MB MB recommended"
        }
        if (!socketOk) {
            issues += "Unable to bind loopback TCP/UDP sockets on this device"
        }
        if (availableRam < requiredMemoryMb) {
            issues += "Available RAM (${availableRam} MB) is below requested ${requiredMemoryMb} MB for $roleLabel"
        }

        val preferred = preferredRuntimeName?.takeIf { it.isNotBlank() }?.let { pref ->
            candidates.firstOrNull { it.name == pref }
        }
        if (preferredRuntimeName != null && preferredRuntimeName.isNotBlank() && preferred == null) {
            issues += "Preferred runtime '$preferredRuntimeName' is not installed"
        }

        val compatibleCandidates = candidates.filter {
            it.hasLibJvm && it.isArchCompatible && it.javaVersion >= requiredJavaMajor
        }.sortedBy { it.javaVersion }

        val chosen = when {
            preferred != null && preferred.hasLibJvm && preferred.isArchCompatible && preferred.javaVersion >= requiredJavaMajor ->
                preferred
            else -> compatibleCandidates.firstOrNull()
        }

        if (chosen == null) {
            if (candidates.isEmpty()) {
                issues += "No OpenJDK runtimes installed; Java $requiredJavaMajor+ is required for $roleLabel"
            } else {
                val maxInstalled = candidates.maxOfOrNull { it.javaVersion } ?: 0
                if (preferred != null && !preferred.isArchCompatible) {
                    issues += "Runtime '${preferred.name}' ABI '${preferred.arch}' does not match device ABI '$deviceAbi'"
                } else if (preferred != null && !preferred.hasLibJvm) {
                    issues += "Runtime '${preferred.name}' is missing libjvm.so"
                } else if (maxInstalled < requiredJavaMajor) {
                    issues += "Highest installed Java runtime is Java $maxInstalled, but $roleLabel requires Java $requiredJavaMajor+"
                } else {
                    issues += "No installed runtime meets ABI ($deviceAbi), libjvm.so, and Java $requiredJavaMajor+ requirements"
                }
            }
        }

        val isCompatible = chosen != null &&
            storageWritable &&
            socketOk &&
            availableRam >= requiredMemoryMb

        val summary = if (isCompatible && chosen != null) {
            "Compatible: ${chosen.name} (Java ${chosen.javaVersion}, ${chosen.arch}), ${availableRam} MB free RAM"
        } else {
            issues.firstOrNull() ?: "Runtime validation failed for $roleLabel"
        }

        return RuntimeCheckDetails(
            isCompatible = isCompatible,
            selectedRuntimeName = chosen?.name,
            selectedJavaVersion = chosen?.javaVersion ?: (preferred?.javaVersion ?: 0),
            requiredJavaVersion = requiredJavaMajor,
            deviceArch = deviceAbi,
            runtimeArch = chosen?.arch ?: preferred?.arch,
            hasLibJvm = chosen?.hasLibJvm ?: (preferred?.hasLibJvm ?: false),
            availableMemoryMb = availableRam,
            requiredMemoryMb = requiredMemoryMb,
            storageWritable = storageWritable,
            freeStorageMb = freeStorageMb,
            socketBindSupported = socketOk,
            summary = summary,
            issues = issues
        )
    }

    private fun checkStorageWritable(dir: File): Boolean = runCatching {
        if (!dir.exists() && !dir.mkdirs()) return false
        val probe = File(dir, ".write_probe")
        probe.writeText("ok")
        val ok = probe.isFile && probe.readText() == "ok"
        probe.delete()
        ok
    }.getOrDefault(false)

    companion object {
        /** Official GeyserMC minimum Java version (Java 21+). */
        const val GEYSER_MIN_JAVA_VERSION = 21
        private const val MIN_REQUIRED_STORAGE_MB = 64L

        fun requiredJavaForMinecraftServer(mcVersion: String): Int {
            val parts = mcVersion.trim().split('.')
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return 21
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: return 21
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
            return when {
                major > 1 -> 21
                minor > 20 || (minor == 20 && patch >= 5) -> 21
                minor >= 18 -> 17
                minor == 17 -> 16
                else -> 8
            }
        }
    }
}
