package net.kdt.pojavlaunch.tasks;

import static net.kdt.pojavlaunch.PojavApplication.sExecutorService;
import static net.kdt.pojavlaunch.utils.DownloadUtils.downloadString;

import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;

/** Class getting the version list, and that's all really */
public class AsyncVersionList {

    public void getVersionList(@Nullable VersionDoneListener listener, boolean secondPass){
        sExecutorService.execute(() -> {
            JMinecraftVersionList versionList = loadVersionListSync(secondPass);
            if(listener != null)
                listener.onVersionDone(versionList);
        });
    }

    @Nullable
    public static JMinecraftVersionList ensureReleaseTableLoadedSync() {
        JMinecraftVersionList existing = (JMinecraftVersionList) ExtraCore.getValue(ExtraConstants.RELEASE_TABLE);
        if (existing != null && existing.versions != null && existing.versions.length > 0) {
            return existing;
        }
        JMinecraftVersionList loaded = new AsyncVersionList().loadVersionListSync(false);
        if (loaded != null && loaded.versions != null) {
            ExtraCore.setValue(ExtraConstants.RELEASE_TABLE, loaded);
        }
        return loaded;
    }

    @Nullable
    private JMinecraftVersionList loadVersionListSync(boolean secondPass) {
        File versionFile = new File(Tools.DIR_CACHE + "/version_list.json");
        JMinecraftVersionList versionList = null;
        try {
            if (!versionFile.exists() || (System.currentTimeMillis() > versionFile.lastModified() + 86400000)) {
                versionList = downloadVersionList(LauncherPreferences.PREF_VERSION_REPOS);
            }
        } catch (Exception e) {
            Log.e("AsyncVersionList", "Refreshing version list failed :" + e);
            e.printStackTrace();
        }

        // Fallback when no network or not needed
        if (versionList == null && versionFile.exists()) {
            try {
                versionList = Tools.GLOBAL_GSON.fromJson(new JsonReader(new FileReader(versionFile)), JMinecraftVersionList.class);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (JsonIOException | JsonSyntaxException e) {
                e.printStackTrace();
                versionFile.delete();
                if (!secondPass) {
                    versionList = loadVersionListSync(true);
                }
            }
        }
        return versionList;
    }


    @SuppressWarnings("SameParameterValue")
    private JMinecraftVersionList downloadVersionList(String mirror){
        JMinecraftVersionList list = null;
        try{
            Log.i("ExtVL", "Syncing to external: " + mirror);
            String jsonString = downloadString(mirror);
            list = Tools.GLOBAL_GSON.fromJson(jsonString, JMinecraftVersionList.class);
            if (list == null || list.versions == null) return null;
            Log.i("ExtVL","Downloaded the version list, len=" + list.versions.length);

            // Then save the version list
            File versionFile = new File(Tools.DIR_CACHE + "/version_list.json");
            if (versionFile.getParentFile() != null) versionFile.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(versionFile);
            fos.write(jsonString.getBytes());
            fos.close();



        }catch (IOException e){
            Log.e("AsyncVersionList", e.toString());
        }
        return list;
    }

    /** Basic listener, acting as a callback */
    public interface VersionDoneListener{
        void onVersionDone(JMinecraftVersionList versions);
    }

}
