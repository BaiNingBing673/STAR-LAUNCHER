package com.star.launcher.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import net.kdt.pojavlaunch.BuildConfig
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.prefs.LauncherPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Unified crash reporting helper across `:launcher` and `:game`.
 * Writes both `latestcrash.txt` (for FatalErrorActivity) and bounded historical
 * crash archives under `crash-reports/` without ever hiding exceptions.
 */
object CrashReportCoordinator {

    private const val TAG = "PojavCrashReport"
    private const val MAX_ARCHIVED_CRASH_FILES = 20

    @JvmStatic
    fun recordUnhandledCrash(
        context: Context,
        thread: Thread?,
        throwable: Throwable,
        storagePermAllowed: Boolean
    ): File {
        val baseDir = when {
            storagePermAllowed && !Tools.DIR_GAME_HOME.isNullOrBlank() -> File(Tools.DIR_GAME_HOME)
            !Tools.DIR_DATA.isNullOrBlank() -> File(Tools.DIR_DATA)
            else -> context.filesDir
        }
        val latestCrashFile = File(baseDir, "latestcrash.txt")
        val reportText = buildCrashReport(thread, throwable)

        try {
            latestCrashFile.parentFile?.mkdirs()
            latestCrashFile.writeText(reportText)
        } catch (ioErr: Throwable) {
            Log.e(TAG, "Failed writing latestcrash.txt", ioErr)
        }

        try {
            val crashDir = File(baseDir, "crash-reports").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val archived = File(crashDir, "crash-$stamp.txt")
            archived.writeText(reportText)
            pruneOldCrashReports(crashDir)
        } catch (ioErr: Throwable) {
            Log.e(TAG, "Failed writing archived crash report", ioErr)
        }

        Log.e(TAG, "Unhandled exception on thread=${thread?.name ?: "unknown"}", throwable)
        return latestCrashFile
    }

    @JvmStatic
    fun buildCrashReport(thread: Thread?, throwable: Throwable): String {
        val stack = runCatching { Log.getStackTraceString(throwable) }
            .getOrElse { throwable.stackTraceToString() }
        val redactedStack = SensitiveLogRedactor.redact(stack)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.US).format(Date())
        return buildString {
            appendLine("STAR Launcher crash report")
            appendLine(" - Time: $timestamp")
            appendLine(" - Device: ${Build.PRODUCT} ${Build.MODEL} (${Build.SUPPORTED_ABIS.joinToString()})")
            appendLine(" - Android version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine(" - Launcher version: ${BuildConfig.VERSION_NAME}")
            appendLine(" - Thread: ${thread?.name ?: "unknown"}")
            appendLine(" - Renderer: ${Tools.LOCAL_RENDERER ?: "unset"} | FPS Limit: ${LauncherPreferences.PREF_FPS_LIMIT} | RAM: ${LauncherPreferences.PREF_RAM_ALLOCATION} MB")
            appendLine(" - Crash stack trace:")
            appendLine(redactedStack)
            appendLine()
            appendLine(StartupProfiler.formatSummaryReport())
        }
    }

    @JvmStatic
    fun listRecentCrashReports(context: Context): List<File> {
        val candidates = mutableListOf<File>()
        val gameHome = Tools.DIR_GAME_HOME?.takeIf { it.isNotBlank() }?.let { File(it, "crash-reports") }
        val dataHome = Tools.DIR_DATA?.takeIf { it.isNotBlank() }?.let { File(it, "crash-reports") }
        val filesHome = File(context.filesDir, "crash-reports")
        listOfNotNull(gameHome, dataHome, filesHome).distinctBy { it.absolutePath }.forEach { dir ->
            dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.let { candidates.addAll(it) }
        }
        return candidates.sortedByDescending { it.lastModified() }
    }

    private fun pruneOldCrashReports(crashDir: File) {
        val files = crashDir.listFiles { f -> f.isFile && f.name.endsWith(".txt") } ?: return
        if (files.size <= MAX_ARCHIVED_CRASH_FILES) return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_ARCHIVED_CRASH_FILES)
            .forEach { it.delete() }
    }
}
