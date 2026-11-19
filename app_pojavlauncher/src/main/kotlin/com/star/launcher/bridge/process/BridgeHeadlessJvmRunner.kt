package com.star.launcher.bridge.process

import android.content.Context
import android.os.Build
import android.system.Os
import com.oracle.dalvik.VMLauncher
import net.kdt.pojavlaunch.Logger
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.multirt.MultiRTUtils
import net.kdt.pojavlaunch.multirt.Runtime
import net.kdt.pojavlaunch.utils.JREUtils
import java.io.File
import java.util.TimeZone

/**
 * Launches a headless OpenJDK JVM (Geyser Standalone or local Minecraft Java server)
 * inside an isolated Android Service process without initializing LWJGL, OpenGL, or AWT.
 */
object BridgeHeadlessJvmRunner {
    fun launchHeadlessJar(
        serviceContext: Context,
        runtimeName: String,
        jarFile: File,
        workingDir: File,
        logFile: File,
        memoryMb: Int,
        extraJvmArgs: List<String> = emptyList(),
        programArgs: List<String> = emptyList(),
        onExit: (code: Int, isSignal: Boolean) -> Unit
    ): Int {
        Tools.initStorageConstants(serviceContext)
        val runtime: Runtime = MultiRTUtils.forceReread(runtimeName)
        val runtimeHome = MultiRTUtils.getRuntimeHome(runtime.name).absolutePath
        if (!MultiRTUtils.hasJvmLibrary(File(runtimeHome), runtime.arch)) {
            throw IllegalStateException("Runtime '${runtime.name}' is missing libjvm.so at $runtimeHome")
        }

        workingDir.mkdirs()
        logFile.parentFile?.mkdirs()
        if (!logFile.exists()) {
            logFile.createNewFile()
        } else {
            logFile.writeText("", Charsets.UTF_8)
        }

        JREUtils.relocateLibPath(runtime, runtimeHome)
        val serverLib = File("$runtimeHome/${Tools.DIRNAME_HOME_JRE}/server/libjvm.so")
        val jvmLibDir = "$runtimeHome/${Tools.DIRNAME_HOME_JRE}/${if (serverLib.exists()) "server" else "client"}"
        val fullLdPath = "$jvmLibDir:${JREUtils.LD_LIBRARY_PATH}"
        JREUtils.jvmLibraryPath = jvmLibDir

        val env = linkedMapOf(
            "JAVA_HOME" to runtimeHome,
            "HOME" to workingDir.absolutePath,
            "TMPDIR" to (Tools.DIR_CACHE?.absolutePath ?: serviceContext.cacheDir.absolutePath),
            "LD_LIBRARY_PATH" to fullLdPath,
            "PATH" to "$runtimeHome/bin:${Os.getenv("PATH") ?: ""}",
            "DALVIK_APPLICATION" to (Tools.jObjectToString(serviceContext.applicationContext) ?: ""),
            "DALVIK_JAVAVM" to Tools.getJavaVMPointer().toString()
        )
        env.forEach { (k, v) ->
            if (v.isNotEmpty()) {
                runCatching { Os.setenv(k, v, true) }
            }
        }
        JREUtils.setLdLibraryPath(fullLdPath)

        // Load core headless JVM libraries (skip OpenAL/GL/AWT graphics libraries)
        JREUtils.dlopen(JREUtils.findInLdLibPath("libjli.so"))
        if (!JREUtils.dlopen("libjvm.so")) {
            JREUtils.dlopen("$jvmLibDir/libjvm.so")
        }
        JREUtils.dlopen(JREUtils.findInLdLibPath("libverify.so"))
        JREUtils.dlopen(JREUtils.findInLdLibPath("libjava.so"))
        JREUtils.dlopen(JREUtils.findInLdLibPath("libnet.so"))
        JREUtils.dlopen(JREUtils.findInLdLibPath("libnio.so"))
        for (libFile in JREUtils.locateLibs(File(runtimeHome, Tools.DIRNAME_HOME_JRE))) {
            val name = libFile.name
            if (name.contains("awt") || name.contains("fontmanager") || name.contains("jawt")) {
                continue
            }
            JREUtils.dlopen(libFile.absolutePath)
        }

        BridgeJvmExitBridge.onExitListener = { _, code, isSignal ->
            onExit(code, isSignal)
        }
        BridgeJvmNative.setupHeadlessExit(serviceContext.applicationContext)
        JREUtils.initializeHooks()
        BridgeJvmNative.openStdinPipe()
        Logger.begin(logFile.absolutePath)
        JREUtils.chdir(workingDir.absolutePath)

        val resolvPath = File(Tools.DIR_DATA, "resolv.conf").absolutePath
        val args = mutableListOf(
            "java",
            "-Djava.awt.headless=true",
            "-Djava.home=$runtimeHome",
            "-Djava.io.tmpdir=${Tools.DIR_CACHE?.absolutePath ?: serviceContext.cacheDir.absolutePath}",
            "-Duser.home=${workingDir.absolutePath}",
            "-Duser.timezone=${TimeZone.getDefault().id}",
            "-Dos.name=Linux",
            "-Dos.version=Android-${Build.VERSION.RELEASE}",
            "-Dext.net.resolvPath=$resolvPath",
            "-Dlog4j2.formatMsgNoLookups=true",
            "-Djdk.lang.Process.launchMechanism=FORK",
            "-XX:ActiveProcessorCount=${java.lang.Runtime.getRuntime().availableProcessors().coerceAtLeast(1)}",
            "-Xms${(memoryMb / 2).coerceAtLeast(128)}M",
            "-Xmx${memoryMb}M"
        )
        args.addAll(extraJvmArgs.filter { it.isNotBlank() })
        args.add("-jar")
        args.add(jarFile.absolutePath)
        args.addAll(programArgs)

        return VMLauncher.launchJVM(args.toTypedArray())
    }
}
