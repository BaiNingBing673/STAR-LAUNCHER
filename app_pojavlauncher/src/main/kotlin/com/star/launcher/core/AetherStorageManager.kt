package com.star.launcher.core

import net.kdt.pojavlaunch.Tools
import java.io.File

/**
 * Manages STAR Launcher-owned directory trees without altering upstream `.minecraft`,
 * `launcher_profiles.json`, or legacy `accounts` JSON files.
 */
class AetherStorageManager(
    private val gameHomeDirProvider: () -> File = { File(Tools.DIR_GAME_HOME) },
    private val appDataDirProvider: () -> File = { File(Tools.DIR_DATA) }
) {
    fun instancesRoot(): File =
        File(gameHomeDirProvider(), "aether_instances").apply { mkdirs() }

    fun resolveInstanceDir(instanceId: String): File {
        val safeId = sanitizeSegment(instanceId)
        return File(instancesRoot(), safeId)
    }

    fun resolveInstanceMinecraftDir(instanceId: String): File =
        File(resolveInstanceDir(instanceId), ".minecraft")

    fun ensureInstanceDirectories(instanceId: String): File =
        resolveInstanceMinecraftDir(instanceId).apply { mkdirs() }

    fun versionsRoot(): File =
        File(gameHomeDirProvider(), ".minecraft/versions")

    fun downloadStagingDir(): File =
        File(appDataDirProvider(), "aether_downloads").apply { mkdirs() }

    /**
     * Cross-process spool directory where `:game` can append redacted session/exit markers
     * for `:launcher` to ingest cleanly into Room without concurrent SQLite writers.
     */
    fun diagnosticSpoolDir(): File =
        File(appDataDirProvider(), "aether_spool").apply { mkdirs() }

    companion object {
        private val unsafeChars = Regex("""[^a-zA-Z0-9._-]""")

        fun sanitizeSegment(raw: String): String {
            require(raw.isNotBlank()) { "Directory segment cannot be blank" }
            val cleaned = unsafeChars.replace(raw.trim(), "_")
            require(cleaned != "." && cleaned != "..") { "Invalid directory segment: $raw" }
            return cleaned
        }
    }
}
