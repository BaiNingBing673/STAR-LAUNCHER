package net.kdt.pojavlaunch.modloaders;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.utils.DownloadUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

public class ForgeDownloadTask implements Runnable, Tools.DownloaderFeedback {
    private String mDownloadUrl;
    private String mFullVersion;
    private String mLoaderVersion;
    private String mGameVersion;
    private final ModloaderDownloadListener mListener;
    public ForgeDownloadTask(ModloaderDownloadListener listener, String forgeVersion) {
        this.mListener = listener;
        this.mDownloadUrl = ForgeUtils.getInstallerUrl(forgeVersion);
        this.mFullVersion = forgeVersion;
    }

    public ForgeDownloadTask(ModloaderDownloadListener listener, String gameVersion, String loaderVersion) {
        this.mListener = listener;
        this.mLoaderVersion = loaderVersion;
        this.mGameVersion = gameVersion;
    }
    @Override
    public void run() {
        if(determineDownloadUrl()) {
            downloadForge();
        }
        ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
    }

    @Override
    public void updateProgress(int curr, int max) {
        int progress100 = (int)(((float)curr / (float)max)*100f);
        ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, progress100, R.string.forge_dl_progress, mFullVersion);
    }

    private void downloadForge() {
        ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.forge_dl_progress, mFullVersion);
        File destinationFile = new File(Tools.DIR_CACHE, "forge-installer.jar");
        File stagingFile = new File(Tools.DIR_CACHE, "forge-installer.jar.part");
        try {
            byte[] buffer = new byte[16384];
            DownloadUtils.downloadFileMonitored(mDownloadUrl, stagingFile, buffer, this);
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(stagingFile)) {
                if (zip.getEntry("install_profile.json") == null && zip.getEntry("META-INF/MANIFEST.MF") == null) {
                    throw new IOException("Downloaded Forge installer is missing expected metadata entries.");
                }
            }
            if (destinationFile.exists()) destinationFile.delete();
            if (!stagingFile.renameTo(destinationFile)) {
                org.apache.commons.io.FileUtils.copyFile(stagingFile, destinationFile);
                stagingFile.delete();
            }
            mListener.onDownloadFinished(destinationFile);
        }catch (FileNotFoundException e) {
            stagingFile.delete();
            mListener.onDataNotAvailable();
        } catch (IOException e) {
            stagingFile.delete();
            mListener.onDownloadError(e);
        }
    }

    public boolean determineDownloadUrl() {
        if(mDownloadUrl != null && mFullVersion != null) return true;
        ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.forge_dl_searching);
        try {
            if(!findVersion()) {
                mListener.onDataNotAvailable();
                return false;
            }
        }catch (IOException e) {
            mListener.onDownloadError(e);
            return false;
        }
        return true;
    }

    public boolean findVersion() throws IOException {
        List<String> forgeVersions = ForgeUtils.downloadForgeVersions();
        if(forgeVersions == null) return false;
        String versionStart = mGameVersion+"-"+mLoaderVersion;
        for(String versionName : forgeVersions) {
            if(!versionName.startsWith(versionStart)) continue;
            mFullVersion = versionName;
            mDownloadUrl = ForgeUtils.getInstallerUrl(mFullVersion);
            return true;
        }
        return false;
    }

}
