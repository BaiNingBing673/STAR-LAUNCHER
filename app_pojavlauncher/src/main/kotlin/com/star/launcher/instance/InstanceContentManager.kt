package com.star.launcher.instance

import java.io.File

data class InstanceModEntry(
    val fileName: String,
    val displayName: String,
    val isEnabled: Boolean,
    val sizeBytes: Long,
    val lastModifiedMs: Long
)

data class InstanceContentSummary(
    val mods: List<InstanceModEntry>,
    val resourcePackCount: Int,
    val shaderPackCount: Int,
    val worldCount: Int
)

/**
 * Non-destructive inspector and toggle manager scoped strictly to a single instance's `.minecraft` tree.
 * Never touches world saves or deletes files unless explicitly requested.
 */
class InstanceContentManager {

    fun inspectInstanceContent(gameDirPath: String): InstanceContentSummary {
        val root = File(gameDirPath)
        val modsDir = File(root, "mods")
        val mods = if (modsDir.isDirectory) {
            modsDir.listFiles()
                ?.filter { it.isFile && (it.name.endsWith(".jar", ignoreCase = true) || it.name.endsWith(".jar.disabled", ignoreCase = true)) }
                ?.sortedBy { it.name.lowercase() }
                ?.map { file ->
                    val enabled = file.name.endsWith(".jar", ignoreCase = true)
                    val display = if (enabled) {
                        file.name.removeSuffix(".jar")
                    } else {
                        file.name.removeSuffix(".jar.disabled")
                    }
                    InstanceModEntry(
                        fileName = file.name,
                        displayName = display,
                        isEnabled = enabled,
                        sizeBytes = file.length(),
                        lastModifiedMs = file.lastModified()
                    )
                } ?: emptyList()
        } else {
            emptyList()
        }

        val resourceCount = countFiles(File(root, "resourcepacks"))
        val shaderCount = countFiles(File(root, "shaderpacks"))
        val worldCount = File(root, "saves").takeIf { it.isDirectory }
            ?.listFiles()?.count { it.isDirectory && File(it, "level.dat").isFile } ?: 0

        return InstanceContentSummary(
            mods = mods,
            resourcePackCount = resourceCount,
            shaderPackCount = shaderCount,
            worldCount = worldCount
        )
    }

    /**
     * Toggles a mod between `<name>.jar` and `<name>.jar.disabled` without deleting mod data.
     */
    fun toggleModEnabled(gameDirPath: String, modFileName: String): InstanceModEntry? {
        require(!modFileName.contains('/') && !modFileName.contains('\\') && !modFileName.contains("..")) {
            "Invalid mod filename: $modFileName"
        }
        val modsDir = File(gameDirPath, "mods")
        val source = File(modsDir, modFileName)
        if (!source.isFile) return null

        val targetName = when {
            modFileName.endsWith(".jar.disabled", ignoreCase = true) ->
                modFileName.substring(0, modFileName.length - ".disabled".length)
            modFileName.endsWith(".jar", ignoreCase = true) ->
                "$modFileName.disabled"
            else -> return null
        }
        val target = File(modsDir, targetName)
        if (target.exists()) return null
        if (!source.renameTo(target)) return null

        val enabled = target.name.endsWith(".jar", ignoreCase = true)
        return InstanceModEntry(
            fileName = target.name,
            displayName = if (enabled) target.name.removeSuffix(".jar") else target.name.removeSuffix(".jar.disabled"),
            isEnabled = enabled,
            sizeBytes = target.length(),
            lastModifiedMs = target.lastModified()
        )
    }

    private fun countFiles(dir: File): Int {
        if (!dir.isDirectory) return 0
        return dir.listFiles()?.count { !it.name.startsWith(".") } ?: 0
    }
}
