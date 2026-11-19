package com.star.launcher.engine.adapter

import android.app.Activity
import android.net.Uri
import android.util.Log
import com.star.launcher.core.AetherContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.kdt.pojavlaunch.PojavApplication
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.modloaders.modpacks.api.NotificationDownloadListener
import java.io.File

/**
 * Adapter encapsulating mod installer (.jar) and streaming large modpack import (.zip/.mrpack)
 * actions without routing through legacy LauncherActivity or blocking the UI thread.
 */
object ModImportAdapter {
    private const val TAG = "ModImportAdapter"
    private val importScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun launchModInstallerJar(activity: Activity, uri: Uri) {
        Tools.launchModInstaller(activity, uri)
    }

    fun importModpackFromUri(
        activity: Activity,
        uri: Uri,
        onStatus: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val appContext = activity.applicationContext
        val container = AetherContainer.get(appContext)
        val manager = container.modpackImportManager
        if (manager.progress.value.isActive) {
            onStatus("Another modpack import is already running. Cancel it before starting a new one.")
            return
        }

        onStatus("Starting streaming modpack import...")
        importScope.launch {
            try {
                Tools.initStorageConstants(appContext)
                val instancesRoot = File(Tools.DIR_GAME_HOME, "custom_instances")
                val cacheRoot = Tools.DIR_CACHE ?: appContext.cacheDir
                val result = manager.importFromUri(
                    context = appContext,
                    uri = uri,
                    instancesRoot = instancesRoot,
                    stagingCacheRoot = cacheRoot,
                    onRegisterInstance = { imported ->
                        val versionToUse = imported.loaderInfo?.versionId?.takeIf { it.isNotBlank() }
                            ?: imported.minecraftVersion
                        container.instanceManager.registerExternalInstance(
                            name = imported.modpackName,
                            gameDirPath = imported.instanceDir.absolutePath,
                            versionId = versionToUse
                        )
                    }
                )
                Tools.runOnUiThread {
                    val loaderInfo = result.loaderInfo
                    if (loaderInfo == null) {
                        onStatus("Imported modpack '${result.modpackName}' (${result.minecraftVersion}) successfully")
                    } else {
                        val task = loaderInfo.getDownloadTask(
                            NotificationDownloadListener(appContext, loaderInfo)
                        )
                        PojavApplication.sExecutorService.execute(task)
                        onStatus(
                            "Imported '${result.modpackName}'; installing loader ${loaderInfo.versionId ?: ""}".trim()
                        )
                    }
                }
            } catch (_: CancellationException) {
                Tools.runOnUiThread {
                    onStatus("Modpack import cancelled")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import modpack", e)
                Tools.runOnUiThread {
                    onError(e)
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        Tools.showError(activity, e)
                    }
                }
            }
        }
    }
}
