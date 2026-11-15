package com.star.launcher.compat

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import com.star.launcher.data.db.entity.DeviceInfoEntity
import net.kdt.pojavlaunch.Architecture
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.utils.GLInfoUtils
import java.io.File
import java.security.MessageDigest

enum class GpuFamily {
    ADRENO,
    MALI,
    XCLIPSE,
    POWERVR,
    UNKNOWN
}

data class DeviceCapabilitiesSnapshot(
    val deviceFingerprint: String,
    val cpuAbiPrimary: String,
    val supportedAbis: List<String>,
    val is64Bit: Boolean,
    val androidApiLevel: Int,
    val gpuVendor: String,
    val gpuRenderer: String,
    val glVersion: String,
    val glesMajorVersion: Int,
    val gpuFamily: GpuFamily,
    val hasVulkan: Boolean,
    val vulkanVersion: String?,
    val hasAdrenoKgslNode: Boolean,
    val totalRamMb: Long,
    val probedAt: Long,
    val cpuCoreCount: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
    val cpuMaxFreqMhz: Int = 0,
    val cpuHardwareModel: String = Build.HARDWARE ?: "unknown",
    val availableRamMb: Long = 0L,
    val screenWidthPx: Int = 0,
    val screenHeightPx: Int = 0,
    val supportedRefreshRatesHz: List<Int> = emptyList(),
    val hasThermalSensor: Boolean = false,
    val thermalCelsiusMax: Float? = null
) {
    fun toDeviceInfoEntity(): DeviceInfoEntity = DeviceInfoEntity(
        deviceFingerprint = deviceFingerprint,
        cpuAbiPrimary = cpuAbiPrimary,
        supportedAbisJson = supportedAbis.joinToString(prefix = "[", postfix = "]") { "\"$it\"" },
        is64Bit = is64Bit,
        androidApiLevel = androidApiLevel,
        gpuVendor = gpuVendor,
        gpuRenderer = gpuRenderer,
        glVersion = glVersion,
        glesMajorVersion = glesMajorVersion,
        hasVulkan = hasVulkan,
        vulkanVersion = vulkanVersion,
        totalRamMb = totalRamMb,
        probedAt = probedAt
    )
}

interface DeviceCapabilityProbe {
    fun probe(): DeviceCapabilitiesSnapshot
}

/**
 * Classifies GPU architecture strictly from EGL vendor/renderer strings rather than device model names.
 */
fun classifyGpuFamily(vendor: String, renderer: String): GpuFamily {
    val v = vendor.lowercase()
    val r = renderer.lowercase()
    return when {
        r.contains("adreno") || v.contains("qualcomm") -> GpuFamily.ADRENO
        r.contains("mali") || v.contains("arm") -> GpuFamily.MALI
        r.contains("xclipse") || v.contains("samsung") && r.contains("rdna") -> GpuFamily.XCLIPSE
        r.contains("powervr") || v.contains("imagination") -> GpuFamily.POWERVR
        else -> GpuFamily.UNKNOWN
    }
}

fun computeDeviceFingerprint(
    cpuAbiPrimary: String,
    androidApiLevel: Int,
    gpuVendor: String,
    gpuRenderer: String,
    vulkanVersion: String?
): String {
    val raw = "$cpuAbiPrimary|$androidApiLevel|$gpuVendor|$gpuRenderer|${vulkanVersion ?: "no-vk"}"
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

class AndroidDeviceCapabilityProbe(
    context: Context,
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) : DeviceCapabilityProbe {

    private val appContext = context.applicationContext

    @Volatile
    private var cachedSnapshot: DeviceCapabilitiesSnapshot? = null

    override fun probe(): DeviceCapabilitiesSnapshot {
        cachedSnapshot?.let { return it }
        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty().ifEmpty { listOf("unknown") }
        val primaryAbi = abis.first()
        val is64Bit = Architecture.is64BitsDevice() || abis.any { it == "arm64-v8a" || it == "x86_64" }
        val apiLevel = Build.VERSION.SDK_INT

        val glInfo = runCatching { GLInfoUtils.getGlInfo() }.getOrNull()
        val vendor = glInfo?.vendor ?: "<Unknown>"
        val renderer = glInfo?.renderer ?: "<Unknown>"
        val glesMajor = glInfo?.glesMajorVersion ?: 2
        val glVersion = "OpenGL ES $glesMajor"

        val pm = appContext.packageManager
        val hasVulkan = runCatching { Tools.checkVulkanSupport(pm) }.getOrDefault(false)
        val vulkanVersion = if (hasVulkan && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            pm.systemAvailableFeatures
                .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
                ?.version
                ?.let(::formatVulkanVersion)
        } else {
            null
        }

        val hasKgsl = File("/sys/class/kgsl/kgsl-3d0/gpu_model").exists()
        val memInfo = readMemoryInfo()
        val totalRamMb = memInfo.first
        val availableRamMb = memInfo.second
        val cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val cpuMaxFreqMhz = readCpuMaxFreqMhz(cpuCores)
        val cpuModel = readCpuHardwareModel()
        val (screenW, screenH, refreshRates) = readDisplayMetricsAndRates()
        val (hasThermal, maxTempC) = readThermalInfo()
        val family = classifyGpuFamily(vendor, renderer)
        val fingerprint = computeDeviceFingerprint(primaryAbi, apiLevel, vendor, renderer, vulkanVersion)

        return DeviceCapabilitiesSnapshot(
            deviceFingerprint = fingerprint,
            cpuAbiPrimary = primaryAbi,
            supportedAbis = abis,
            is64Bit = is64Bit,
            androidApiLevel = apiLevel,
            gpuVendor = vendor,
            gpuRenderer = renderer,
            glVersion = glVersion,
            glesMajorVersion = glesMajor,
            gpuFamily = family,
            hasVulkan = hasVulkan,
            vulkanVersion = vulkanVersion,
            hasAdrenoKgslNode = hasKgsl,
            totalRamMb = totalRamMb,
            probedAt = clockMillis(),
            cpuCoreCount = cpuCores,
            cpuMaxFreqMhz = cpuMaxFreqMhz,
            cpuHardwareModel = cpuModel,
            availableRamMb = availableRamMb,
            screenWidthPx = screenW,
            screenHeightPx = screenH,
            supportedRefreshRatesHz = refreshRates,
            hasThermalSensor = hasThermal,
            thermalCelsiusMax = maxTempC
        ).also { cachedSnapshot = it }
    }

    private fun readMemoryInfo(): Pair<Long, Long> {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L to 0L
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return (memInfo.totalMem / (1024L * 1024L)) to (memInfo.availMem / (1024L * 1024L))
    }

    private fun readCpuMaxFreqMhz(coreCount: Int): Int {
        var maxKhz = 0L
        for (idx in 0 until coreCount.coerceAtMost(16)) {
            val path = File("/sys/devices/system/cpu/cpu$idx/cpufreq/cpuinfo_max_freq")
            if (path.isFile && path.canRead()) {
                val khz = runCatching { path.readText().trim().toLongOrNull() }.getOrNull() ?: 0L
                if (khz > maxKhz) maxKhz = khz
            }
        }
        return (maxKhz / 1000L).toInt()
    }

    private fun readCpuHardwareModel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val soc = Build.SOC_MODEL
            if (!soc.isNullOrBlank() && soc != Build.UNKNOWN) return soc
        }
        return Build.HARDWARE ?: "unknown"
    }

    private fun readDisplayMetricsAndRates(): Triple<Int, Int, List<Int>> {
        return runCatching {
            val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            val metrics = appContext.resources.displayMetrics
            val w = display?.mode?.physicalWidth?.takeIf { it > 0 } ?: metrics.widthPixels
            val h = display?.mode?.physicalHeight?.takeIf { it > 0 } ?: metrics.heightPixels
            val rates = if (display != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                buildList {
                    add(display.mode.refreshRate.toInt())
                    display.supportedModes.forEach { mode -> add(mode.refreshRate.toInt()) }
                }.filter { it > 0 }.distinct().sorted()
            } else {
                listOf(60)
            }
            Triple(w, h, rates)
        }.getOrElse {
            val metrics = appContext.resources.displayMetrics
            Triple(metrics.widthPixels, metrics.heightPixels, listOf(60))
        }
    }

    private fun readThermalInfo(): Pair<Boolean, Float?> {
        val dir = File("/sys/class/thermal")
        val zones = dir.listFiles { f -> f.name.startsWith("thermal_zone") } ?: return false to null
        var maxCelsius: Float? = null
        for (zone in zones.take(12)) {
            val tempFile = File(zone, "temp")
            if (!tempFile.isFile || !tempFile.canRead()) continue
            val raw = runCatching { tempFile.readText().trim().toFloatOrNull() }.getOrNull() ?: continue
            val celsius = if (raw > 200f) raw / 1000f else raw
            if (celsius in 10f..110f) {
                maxCelsius = if (maxCelsius == null) celsius else maxOf(maxCelsius, celsius)
            }
        }
        return true to maxCelsius
    }

    private fun formatVulkanVersion(packed: Int): String {
        val major = (packed ushr 22) and 0x3FF
        val minor = (packed ushr 12) and 0x3FF
        val patch = packed and 0xFFF
        return "$major.$minor.$patch"
    }
}
