package com.star.launcher.engine.adapter

import android.app.Activity
import android.net.Uri
import android.util.Log
import net.kdt.pojavlaunch.PojavApplication
import net.kdt.pojavlaunch.R
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.modloaders.modpacks.api.CommonApi
import net.kdt.pojavlaunch.modloaders.modpacks.api.NotificationDownloadListener
import org.apache.commons.io.IOUtils
import java.io.File
import java.io.FileOutputStream

/**
 * Adapter encapsulating mod installer (.jar) and modpack import (.zip/.mrpack) actions
 * without routing through legacy LauncherActivity.
 */
object ModImportAdapter {

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
        val curseforgeKey = appContext.getString(R.string.curseforge_api_key)
        onStatus("Importing modpack archive...")
        PojavApplication.sExecutorService.execute {
            try {
                val modpackFile = File(Tools.DIR_CACHE, "modpack.zip")
                val inputStream = appContext.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Failed to open selected modpack file")
                inputStream.use { input ->
                    FileOutputStream(modpackFile).use { output ->
                        IOUtils.copy(input, output)
                    }
                }
                val loaderInfo = CommonApi(curseforgeKey).importModpack(modpackFile)
                modpackFile.delete()
                Tools.runOnUiThread {
                    if (loaderInfo == null) {
                        onStatus("Modpack imported successfully")
                    } else {
                        val task = loaderInfo.getDownloadTask(
                            NotificationDownloadListener(appContext, loaderInfo)
                        )
                        PojavApplication.sExecutorService.execute(task)
                        onStatus("Modpack imported; installing loader ${loaderInfo.versionId ?: ""}".trim())
                    }
                }
            } catch (e: Exception) {
                Log.e("ModImportAdapter", "Failed to import modpack", e)
                Tools.runOnUiThread {
                    onError(e)
                    Tools.showError(activity, e)
                }
            }
        }
    }
}
