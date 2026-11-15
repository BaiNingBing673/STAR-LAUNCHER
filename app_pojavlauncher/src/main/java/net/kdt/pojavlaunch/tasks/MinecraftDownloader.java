package net.kdt.pojavlaunch.tasks;

import static net.kdt.pojavlaunch.PojavApplication.sExecutorService;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kdt.mcgui.ProgressLayout;
import com.star.launcher.diagnostics.StartupProfiler;

import net.kdt.pojavlaunch.JAssetInfo;
import net.kdt.pojavlaunch.JAssets;
import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.NewJREUtil;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.mirrors.DownloadMirror;
import net.kdt.pojavlaunch.mirrors.MirrorTamperedException;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.value.DependentLibrary;
import net.kdt.pojavlaunch.value.MinecraftClientInfo;
import net.kdt.pojavlaunch.value.MinecraftLibraryArtifact;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class MinecraftDownloader {
    public enum InstallStage {
        FETCHING_METADATA,
        DOWNLOADING_CLIENT_JAR,
        DOWNLOADING_LIBRARIES,
        DOWNLOADING_ASSETS,
        PREPARING_NATIVES,
        VALIDATING,
        COMPLETED
    }

    public interface InstallProgressListener {
        void onStageChanged(@NonNull InstallStage stage, @Nullable String currentFile);
        void onProgress(long downloadedBytes, long totalBytes, int processedFiles, int totalFiles, @Nullable String currentFile);
        void onCompleted();
        void onFailed(@NonNull Throwable error);
        void onCancelled();
    }

    public static final class InstallHandle {
        private final AtomicBoolean mCancelled = new AtomicBoolean(false);
        private final AtomicReference<ThreadPoolExecutor> mActivePool = new AtomicReference<>(null);
        private final AtomicReference<Thread> mRunnerThread = new AtomicReference<>(null);

        public void cancel() {
            mCancelled.set(true);
            ThreadPoolExecutor pool = mActivePool.get();
            if (pool != null) {
                pool.shutdownNow();
            }
            Thread runner = mRunnerThread.get();
            if (runner != null) {
                runner.interrupt();
            }
        }

        public boolean isCancelled() {
            return mCancelled.get();
        }
    }

    private static final double ONE_MEGABYTE = (1024d * 1024d);
    public static final String MINECRAFT_RES = "https://resources.download.minecraft.net/";
    private static final String MAVEN_CENTRAL_REPO1 = "https://repo1.maven.org/maven2/";
    private AtomicReference<Exception> mDownloaderThreadException;
    private ArrayList<DownloaderTask> mScheduledDownloadTasks;
    private HashSet<String> mScheduledTargetPaths;
    private ArrayList<File> mDeclaredNatives;
    private static final Object sPromoteLock = new Object();
    private AtomicLong mProcessedFileCounter;
    private AtomicLong mProcessedSizeCounter; // Total bytes of processed files (passed SHA1 or downloaded)
    private AtomicLong mInternetUsageCounter; // How many bytes downloaded over Internet
    private long mTotalFileCount;
    private long mTotalSize;
    private File mSourceJarFile; // The source client JAR picked during the inheritance process
    private File mTargetJarFile; // The destination client JAR to which the source will be copied to.
    private boolean mUseFileCounter; // Whether a file counter or a size counter should be used for progress
    private final AtomicBoolean mCancelFlag = new AtomicBoolean(false);
    private final AtomicReference<String> mCurrentFile = new AtomicReference<>(null);
    private InstallHandle mInstallHandle;
    private InstallProgressListener mInstallListener;
    private InstallStage mCurrentStage = InstallStage.FETCHING_METADATA;
    private boolean mSkipRuntimeCheckInDownloader = false;
    private boolean mUseLegacyLauncherProfiles = true;
    @Nullable
    private String mExplicitProfileRuntime = null;

    /**
     * Configure an explicit runtime name (e.g. from STAR Launcher's {@code AetherLaunchPayload})
     * so downloader runtime verification does not read or mutate {@code launcher_profiles.json}.
     */
    public void setExplicitRuntimeOverride(@Nullable String runtimeName) {
        this.mUseLegacyLauncherProfiles = false;
        this.mExplicitProfileRuntime = runtimeName;
    }

    private static final ThreadLocal<byte[]> sThreadLocalDownloadBuffer = new ThreadLocal<>();

    private boolean isLocalProfile = false;
    private boolean isOnline;

    /**
     * Start the game version download process on the global executor service.
     * @param activity Activity, used for automatic installation of JRE 17 if needed
     * @param version The JMinecraftVersionList.Version from the version list, if available
     * @param realVersion The version ID (necessary)
     * @param listener The download status listener
     */
    public void start(@Nullable Activity activity, @Nullable JMinecraftVersionList.Version version,
                      @NonNull String realVersion,
                      @NonNull AsyncMinecraftDownloader.DoneListener listener) {
        if(activity != null){
            isLocalProfile = Tools.isLocalProfile(activity);
            isOnline = Tools.isOnline(activity);
            Tools.switchDemo(Tools.isDemoProfile(activity));

        } else {
            isLocalProfile = true;
            Tools.switchDemo(true);
        }

        sExecutorService.execute(() -> {
            final long launchStageStart = StartupProfiler.beginStage();
            try {
                if (realVersion == null || realVersion.trim().isEmpty() || "unknown".equalsIgnoreCase(realVersion.trim())) {
                    throw new IOException("No Minecraft version selected for this instance. Select or configure a valid version (e.g. 1.21.4) before launching.");
                }
                if(isLocalProfile || !isOnline) {
                    String versionMessage = realVersion; // Use provided version unless we find its a modded instance
                    int requiredJavaVersion = version != null && version.javaVersion != null && version.javaVersion.majorVersion > 0
                            ? version.javaVersion.majorVersion : 8;

                    // See if provided version is a modded version and if that version depends on another jar, check for presence of both jar's .json.
                    boolean filesValid = false;
                    try {
                        // This reads the .json associated with the provided version. If it fails, we can assume it's not installed.
                        File providedJsonFile = new File(Tools.DIR_HOME_VERSION + "/" + realVersion + "/" + realVersion + ".json");
                        JMinecraftVersionList.Version providedJson = Tools.GLOBAL_GSON.fromJson(Tools.read(providedJsonFile.getAbsolutePath()), JMinecraftVersionList.Version.class);
                        if (providedJson == null) {
                            throw new RuntimeException("Version metadata JSON could not be parsed for " + realVersion);
                        }
                        if (providedJson.javaVersion != null && providedJson.javaVersion.majorVersion > 0) {
                            requiredJavaVersion = providedJson.javaVersion.majorVersion;
                        }

                        // This checks if running modded version that depends on other jars, so we use that for the error message.
                        File vanillaJsonFile = new File(Tools.DIR_HOME_VERSION + "/" + providedJson.inheritsFrom + "/" + providedJson.inheritsFrom + ".json");
                        versionMessage = providedJson.inheritsFrom != null ? providedJson.inheritsFrom : versionMessage;
                        if (providedJson.inheritsFrom != null && vanillaJsonFile.isFile()) {
                            JMinecraftVersionList.Version vanillaJson = Tools.GLOBAL_GSON.fromJson(Tools.read(vanillaJsonFile.getAbsolutePath()), JMinecraftVersionList.Version.class);
                            if (vanillaJson != null && vanillaJson.javaVersion != null && vanillaJson.javaVersion.majorVersion > 0 && (providedJson.javaVersion == null || providedJson.javaVersion.majorVersion <= 0)) {
                                requiredJavaVersion = vanillaJson.javaVersion.majorVersion;
                            }
                        }

                        // Ensure they're both not some 0 byte corrupted json
                        if (providedJsonFile.length() == 0 || vanillaJsonFile.exists() && vanillaJsonFile.length() == 0){
                            throw new RuntimeException("Minecraft "+versionMessage+ " is needed by " +realVersion); }

                        filesValid = true;
                    } catch (Exception e) {
                        String tryagain = !isOnline ? "Please ensure you have an internet connection" : "Please try again on your Microsoft Account";
                        Tools.showErrorRemote(versionMessage + " is not currently installed. "+ tryagain, e);
                    }
                    if (filesValid) {
                        final long rtCheckStart = StartupProfiler.beginStage();
                        boolean runtimeReady = (activity == null || NewJREUtil.ensureRuntimeReady(
                                activity,
                                requiredJavaVersion,
                                isOnline,
                                mExplicitProfileRuntime,
                                mUseLegacyLauncherProfiles
                        ));
                        StartupProfiler.endStage("launch_runtime_selection", rtCheckStart, "Java " + requiredJavaVersion);
                        if (runtimeReady) {
                            StartupProfiler.endStage("launch_download_and_validate", launchStageStart, realVersion);
                            listener.onDownloadDone();
                        }
                    }
                }else {
                    if (downloadGame(activity, version, realVersion)) {
                        StartupProfiler.endStage("launch_download_and_validate", launchStageStart, realVersion);
                        listener.onDownloadDone();
                    }
                }
            }catch (Exception e) {
                listener.onDownloadFailed(e);
            }
            ProgressLayout.clearProgress(ProgressLayout.DOWNLOAD_MINECRAFT);
        });
    }

    /**
     * Install a Minecraft version without triggering game launch or requiring an online Microsoft session.
     * Supports real-time stage and byte/file progress callbacks as well as safe cancellation.
     */
    @NonNull
    public InstallHandle startInstallOnly(@NonNull Context context,
                                          @Nullable JMinecraftVersionList.Version version,
                                          @NonNull String realVersion,
                                          @NonNull InstallProgressListener listener) {
        InstallHandle handle = new InstallHandle();
        this.mInstallHandle = handle;
        this.mInstallListener = listener;
        this.mSkipRuntimeCheckInDownloader = true;
        this.isLocalProfile = false;
        this.isOnline = Tools.isOnline(context);
        this.mCancelFlag.set(false);
        this.mCurrentStage = InstallStage.FETCHING_METADATA;

        sExecutorService.execute(() -> {
            handle.mRunnerThread.set(Thread.currentThread());
            try {
                if (!Tools.isOnline(context)) {
                    throw new IOException("No internet connection available. Connect to Wi-Fi or mobile data and retry.");
                }
                if (realVersion == null || realVersion.trim().isEmpty() || "unknown".equalsIgnoreCase(realVersion.trim())) {
                    throw new IOException("No Minecraft version selected. Select a valid Minecraft version before installing.");
                }
                checkCancelled();
                boolean success = downloadGame(null, version, realVersion);
                if (isCancelled()) {
                    listener.onCancelled();
                } else if (success) {
                    emitStage(InstallStage.VALIDATING, realVersion + ".json");
                    emitStage(InstallStage.COMPLETED, null);
                    listener.onCompleted();
                } else {
                    listener.onCancelled();
                }
            } catch (CancellationException e) {
                listener.onCancelled();
            } catch (Throwable t) {
                if (isCancelled() || Thread.currentThread().isInterrupted()) {
                    listener.onCancelled();
                } else {
                    listener.onFailed(t);
                }
            } finally {
                handle.mRunnerThread.compareAndSet(Thread.currentThread(), null);
                ProgressLayout.clearProgress(ProgressLayout.DOWNLOAD_MINECRAFT);
            }
        });
        return handle;
    }

    private boolean isCancelled() {
        return mCancelFlag.get()
                || (mInstallHandle != null && mInstallHandle.isCancelled())
                || Thread.currentThread().isInterrupted();
    }

    private void checkCancelled() {
        if (isCancelled()) {
            mCancelFlag.set(true);
            throw new CancellationException("Installation cancelled by user");
        }
    }

    private void emitStage(@NonNull InstallStage stage, @Nullable String currentFile) {
        mCurrentStage = stage;
        if (currentFile != null) {
            mCurrentFile.set(currentFile);
        }
        if (mInstallListener != null) {
            mInstallListener.onStageChanged(stage, currentFile);
        }
    }

    private void emitInstallProgress() {
        if (mInstallListener == null) return;
        long totalBytes = mUseFileCounter || mTotalSize <= 0L ? -1L : mTotalSize;
        long downloadedBytes = mProcessedSizeCounter != null ? mProcessedSizeCounter.get() : 0L;
        int processedFiles = mProcessedFileCounter != null ? (int) mProcessedFileCounter.get() : 0;
        int totalFiles = (int) mTotalFileCount;
        mInstallListener.onProgress(downloadedBytes, totalBytes, processedFiles, totalFiles, mCurrentFile.get());
    }

    /**
     * Download the game version.
     * @param activity Activity, used for automatic installation of JRE 17 if needed
     * @param verInfo The JMinecraftVersionList.Version from the version list, if available
     * @param versionName The version ID (necessary)
     * @throws Exception when an exception occurs in the function body or in any of the downloading threads.
     */
    private boolean downloadGame(Activity activity, JMinecraftVersionList.Version verInfo, String versionName) throws Exception {
        // Put up a dummy progress line, for the activity to start the service and do all the other necessary
        // work to keep the launcher alive. We will replace this line when we will start downloading stuff.
        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0, R.string.newdl_starting);
        SpeedCalculator speedCalculator = new SpeedCalculator();
        emitStage(InstallStage.FETCHING_METADATA, versionName + ".json");
        checkCancelled();

        if (verInfo == null && !createGameJsonPath(versionName).canRead()) {
            AsyncVersionList.ensureReleaseTableLoadedSync();
            String normalized = AsyncMinecraftDownloader.normalizeVersionId(versionName);
            if (normalized != null && !normalized.trim().isEmpty() && !"unknown".equalsIgnoreCase(normalized.trim())) {
                versionName = normalized;
                verInfo = AsyncMinecraftDownloader.getListedVersion(versionName);
            }
        }
        mTargetJarFile = createGameJarPath(versionName);
        mScheduledDownloadTasks = new ArrayList<>();
        mScheduledTargetPaths = new HashSet<>();
        mDeclaredNatives = new ArrayList<>();
        mTotalFileCount = 0;
        mTotalSize = 0;
        mProcessedFileCounter = new AtomicLong(0);
        mProcessedSizeCounter = new AtomicLong(0);
        mInternetUsageCounter = new AtomicLong(0);
        mDownloaderThreadException = new AtomicReference<>(null);
        mUseFileCounter = false;

        if (!downloadAndProcessMetadata(activity, verInfo, versionName)) {
            return false;
        }
        checkCancelled();

        if (!mScheduledDownloadTasks.isEmpty()) {
            InstallStage[] stageOrder = new InstallStage[] {
                    InstallStage.DOWNLOADING_CLIENT_JAR,
                    InstallStage.DOWNLOADING_LIBRARIES,
                    InstallStage.DOWNLOADING_ASSETS
            };
            for (InstallStage stage : stageOrder) {
                checkCancelled();
                List<DownloaderTask> stageTasks = new ArrayList<>();
                for (DownloaderTask task : mScheduledDownloadTasks) {
                    if (task.mStage == stage) {
                        stageTasks.add(task);
                    }
                }
                if (stageTasks.isEmpty()) continue;

                emitStage(stage, stageTasks.get(0).mTargetPath.getName());
                if (!runTaskBatch(stageTasks, speedCalculator)) {
                    return false;
                }
            }
        }

        Exception thrownException = mDownloaderThreadException.get();
        if (thrownException != null) {
            throw thrownException;
        }
        checkCancelled();
        ensureJarFileCopy();
        extractNatives(versionName);
        return true;
    }

    private boolean runTaskBatch(@NonNull List<DownloaderTask> stageTasks,
                                 @NonNull SpeedCalculator speedCalculator) throws Exception {
        ArrayBlockingQueue<Runnable> taskQueue =
                new ArrayBlockingQueue<>(Math.max(1, stageTasks.size()), false);
        ThreadPoolExecutor downloaderPool =
                new ThreadPoolExecutor(4, 4, 500, TimeUnit.MILLISECONDS, taskQueue);
        if (mInstallHandle != null) {
            mInstallHandle.mActivePool.set(downloaderPool);
        }

        for (DownloaderTask scheduledTask : stageTasks) {
            downloaderPool.execute(scheduledTask);
        }
        downloaderPool.shutdown();

        try {
            while (mDownloaderThreadException.get() == null &&
                    !isCancelled() &&
                    !downloaderPool.awaitTermination(50, TimeUnit.MILLISECONDS)) {
                double speed = speedCalculator.feed(mInternetUsageCounter.get()) / ONE_MEGABYTE;
                if (mUseFileCounter) reportProgressFileCounter(speed);
                else reportProgressSizeCounter(speed);
                emitInstallProgress();
            }
            if (isCancelled()) {
                downloaderPool.shutdownNow();
                return false;
            }
            Exception thrownException = mDownloaderThreadException.get();
            if (thrownException != null) {
                downloaderPool.shutdownNow();
                throw thrownException;
            }
            emitInstallProgress();
            return true;
        } catch (InterruptedException e) {
            downloaderPool.shutdownNow();
            mCancelFlag.set(true);
            return false;
        } finally {
            if (mInstallHandle != null) {
                mInstallHandle.mActivePool.compareAndSet(downloaderPool, null);
            }
        }
    }

    private void reportProgressFileCounter(double speed) {
        long dlFileCounter = mProcessedFileCounter.get();
        int progress = mTotalFileCount > 0 ? (int)((dlFileCounter * 100L) / mTotalFileCount) : 0;
        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, progress,
                R.string.newdl_downloading_game_files, dlFileCounter,
                mTotalFileCount, speed);
    }

    private void reportProgressSizeCounter(double speed) {
        long dlFileSize = mProcessedSizeCounter.get();
        double dlSizeMegabytes = (double) dlFileSize / ONE_MEGABYTE;
        double dlTotalMegabytes = (double) mTotalSize / ONE_MEGABYTE;
        int progress = mTotalSize > 0 ? (int)((dlFileSize * 100L) / mTotalSize) : 0;
        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, progress,
                R.string.newdl_downloading_game_files_size, dlSizeMegabytes, dlTotalMegabytes, speed);
    }

    private File createGameJsonPath(String versionId) {
        return new File(Tools.DIR_HOME_VERSION, versionId + File.separator + versionId + ".json");
    }

    private File createGameJarPath(String versionId) {
        return new File(Tools.DIR_HOME_VERSION, versionId + File.separator + versionId + ".jar");
    }

    /**
     * Ensure that there is a copy of the client JAR file in the version folder, if a copy is
     * needed.
     * @throws IOException if the copy fails
     */
    private void ensureJarFileCopy() throws IOException {
        if(mSourceJarFile == null) return;
        if(mSourceJarFile.equals(mTargetJarFile)) return;
        if(mTargetJarFile.exists()) return;
        FileUtils.ensureParentDirectory(mTargetJarFile);
        Log.i("NewMCDownloader", "Copying " + mSourceJarFile.getName() + " to "+mTargetJarFile.getAbsolutePath());
        org.apache.commons.io.FileUtils.copyFile(mSourceJarFile, mTargetJarFile, false);
    }

    private void extractNatives(String versionName) throws IOException {
        if(mDeclaredNatives.isEmpty()) return;
        int totalCount = mDeclaredNatives.size();
        emitStage(InstallStage.PREPARING_NATIVES, mDeclaredNatives.get(0).getName());

        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0,
                R.string.newdl_extracting_native_libraries, 0, totalCount);

        File targetDirectory = new File(Tools.DIR_CACHE, "natives/"+versionName);
        FileUtils.ensureDirectory(targetDirectory);
        NativesExtractor nativesExtractor = new NativesExtractor(targetDirectory);
        int extractedCount = 0;
        for(File source : mDeclaredNatives) {
            checkCancelled();
            mCurrentFile.set(source.getName());
            nativesExtractor.extractFromAar(source);
            extractedCount++;
            ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, extractedCount * 100 / totalCount,
                    R.string.newdl_extracting_native_libraries, extractedCount, totalCount);
            emitInstallProgress();
        }
    }

    private File downloadGameJson(JMinecraftVersionList.Version verInfo) throws IOException, MirrorTamperedException {
        File targetFile = createGameJsonPath(verInfo.id);
        if(verInfo.sha1 == null && targetFile.canRead() && targetFile.isFile())
            return targetFile;
        FileUtils.ensureParentDirectory(targetFile);
        emitStage(InstallStage.FETCHING_METADATA, targetFile.getName());
        try {
            DownloadUtils.ensureSha1(targetFile, LauncherPreferences.PREF_VERIFY_MANIFEST ? verInfo.sha1 : null, () -> {
                checkCancelled();
                ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0,
                        R.string.newdl_downloading_metadata, targetFile.getName());
                File stagingFile = new File(targetFile.getAbsolutePath() + ".aetherpart");
                boolean promoted = false;
                try {
                    DownloadMirror.downloadFileMirrored(DownloadMirror.DOWNLOAD_CLASS_METADATA, verInfo.url, stagingFile);
                    checkCancelled();
                    promoteStagingFile(stagingFile, targetFile);
                    promoted = true;
                } finally {
                    if (!promoted && stagingFile.exists()) {
                        stagingFile.delete();
                    }
                }
                return null;
            });
        }catch (DownloadUtils.SHA1VerificationException e) {
            if(DownloadMirror.isMirrored()) throw new MirrorTamperedException();
            else throw e;
        }
        return targetFile;
    }

    private JAssets downloadAssetsIndex(JMinecraftVersionList.Version verInfo) throws IOException{
        JMinecraftVersionList.AssetIndex assetIndex = verInfo.assetIndex;
        if(assetIndex == null || verInfo.assets == null) return null;
        File targetFile = new File(Tools.ASSETS_PATH, "indexes"+ File.separator + verInfo.assets + ".json");
        FileUtils.ensureParentDirectory(targetFile);
        emitStage(InstallStage.FETCHING_METADATA, targetFile.getName());
        DownloadUtils.ensureSha1(targetFile, assetIndex.sha1, ()-> {
            checkCancelled();
            ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0,
                    R.string.newdl_downloading_metadata, targetFile.getName());
            File stagingFile = new File(targetFile.getAbsolutePath() + ".aetherpart");
            boolean promoted = false;
            try {
                DownloadMirror.downloadFileMirrored(DownloadMirror.DOWNLOAD_CLASS_METADATA, assetIndex.url, stagingFile);
                checkCancelled();
                promoteStagingFile(stagingFile, targetFile);
                promoted = true;
            } finally {
                if (!promoted && stagingFile.exists()) {
                    stagingFile.delete();
                }
            }
            return null;
        });
        return Tools.GLOBAL_GSON.fromJson(Tools.read(targetFile), JAssets.class);
    }
    
    private MinecraftClientInfo getClientInfo(JMinecraftVersionList.Version verInfo) {
        Map<String, MinecraftClientInfo> downloads = verInfo.downloads;
        if(downloads == null) return null;
        return downloads.get("client");
    }

    /**
     * Download (if necessary) and process a version's metadata, scheduling all downloads that this
     * version needs.
     * @param activity Activity, used for automatic installation of JRE 17 if needed
     * @param verInfo The JMinecraftVersionList.Version from the version list, if available
     * @param versionName The version ID (necessary)
     * @return false if JRE17 installation failed, true otherwise
     * @throws IOException if the download of any of the metadata files fails
     */
    private boolean downloadAndProcessMetadata(Activity activity, JMinecraftVersionList.Version verInfo, String versionName) throws IOException, MirrorTamperedException {
        if (versionName == null || versionName.trim().isEmpty() || "unknown".equalsIgnoreCase(versionName.trim())) {
            throw new IOException("No Minecraft version selected (version='" + versionName + "'). Configure a valid Target Version ID before launching.");
        }
        File versionJsonFile = verInfo != null ? null : createGameJsonPath(versionName);
        if (verInfo == null && !versionJsonFile.canRead()) {
            AsyncVersionList.ensureReleaseTableLoadedSync();
            String normalized = AsyncMinecraftDownloader.normalizeVersionId(versionName);
            if (normalized != null && !normalized.trim().isEmpty() && !"unknown".equalsIgnoreCase(normalized.trim())) {
                versionName = normalized;
                verInfo = AsyncMinecraftDownloader.getListedVersion(versionName);
            }
        }
        if(verInfo != null) versionJsonFile = downloadGameJson(verInfo);
        else versionJsonFile = createGameJsonPath(versionName);
        if(versionJsonFile.canRead())  {
            verInfo = Tools.GLOBAL_GSON.fromJson(Tools.read(versionJsonFile), JMinecraftVersionList.Version.class);
        } else {
            throw new IOException("Unable to read Version JSON for version " + versionName);
        }

        boolean deferRuntimeCheckToParent = verInfo.javaVersion == null && Tools.isValidString(verInfo.inheritsFrom);
        if(!deferRuntimeCheckToParent && activity != null && !NewJREUtil.installNewJreIfNeeded(
                activity,
                verInfo,
                mExplicitProfileRuntime,
                mUseLegacyLauncherProfiles
        )){
            return false;
        }

        JAssets assets = downloadAssetsIndex(verInfo);
        if(assets != null) scheduleAssetDownloads(assets);


        MinecraftClientInfo minecraftClientInfo = getClientInfo(verInfo);
        if(minecraftClientInfo != null) scheduleGameJarDownload(minecraftClientInfo, versionName);

        if(verInfo.libraries != null) scheduleLibraryDownloads(verInfo.libraries);

        if(verInfo.logging != null) scheduleLoggingAssetDownloadIfNeeded(verInfo.logging);

        if(Tools.isValidString(verInfo.inheritsFrom)) {
            JMinecraftVersionList.Version inheritedVersion = AsyncMinecraftDownloader.getListedVersion(verInfo.inheritsFrom);
            // Infinite inheritance !?! :noway:
            return downloadAndProcessMetadata(activity, inheritedVersion, verInfo.inheritsFrom);
        }
        return true;
    }

    private void growDownloadList(int addedElementCount) {
        mScheduledDownloadTasks.ensureCapacity(mScheduledDownloadTasks.size() + addedElementCount);
    }

    private void scheduleDownload(File targetFile, int downloadClass, InstallStage stage, String url, String sha1,
                                  long size, boolean skipIfFailed) throws IOException {
        if (mScheduledTargetPaths != null && !mScheduledTargetPaths.add(targetFile.getAbsolutePath())) {
            return;
        }
        FileUtils.ensureParentDirectory(targetFile);
        mTotalFileCount++;
        // Only attempt to check size if we still use the size counter and didn't switch to file counter.
        if(size <= 0 && !mUseFileCounter) {
            size = DownloadMirror.getContentLengthMirrored(downloadClass, url);
        }
        if(size < 0) {
            // If we were unable to get the content length ourselves, we automatically fall back
            // to tracking the progress using the file counter.
            size = 0;
            mUseFileCounter = true;
            Log.i("MinecraftDownloader", "Failed to determine size of "+targetFile.getName()+", switching to file counter");
        }else {
            mTotalSize += size;
        }
        mScheduledDownloadTasks.add(
                new DownloaderTask(targetFile, downloadClass, stage, url, sha1, size, skipIfFailed)
        );
    }

    /**
     * Schedule the download of an AAR library containing the required natives, for later extraction
     * and adding to the library path.
     * @param baseRepository the source Maven repository to download from.
     * @param dependentLibrary the DependentLibrary to get the path from
     * @throws IOException in case if download scheduling fails.
     */
    private void scheduleNativeLibraryDownload(String baseRepository, DependentLibrary dependentLibrary) throws IOException {
        String path = FileUtils.removeExtension(Tools.artifactToPath(dependentLibrary)) + ".aar";
        String downloadUrl = baseRepository + path;
        File targetPath = new File(Tools.DIR_HOME_LIBRARY, path);
        mDeclaredNatives.add(targetPath);
        scheduleDownload(targetPath, DownloadMirror.DOWNLOAD_CLASS_LIBRARIES, InstallStage.DOWNLOADING_LIBRARIES, downloadUrl, null, 0, true);
    }

    private void scheduleLibraryDownloads(DependentLibrary[] dependentLibraries) throws IOException {
        Tools.preProcessLibraries(dependentLibraries);
        growDownloadList(dependentLibraries.length);
        for(DependentLibrary dependentLibrary : dependentLibraries) {
            // Don't download lwjgl, we have our own bundled in.
            if(dependentLibrary.name.startsWith("org.lwjgl")) continue;
            // Special handling for JNA Android natives
            if(dependentLibrary.name.startsWith("net.java.dev.jna:jna:")) {
                scheduleNativeLibraryDownload(MAVEN_CENTRAL_REPO1, dependentLibrary);
            }
            String libArtifactPath = Tools.artifactToPath(dependentLibrary);
            String sha1 = null, url = null;
            long size = 0;
            boolean skipIfFailed = false;
            if(dependentLibrary.downloads != null) {
                if(dependentLibrary.downloads.artifact != null) {
                    MinecraftLibraryArtifact artifact = dependentLibrary.downloads.artifact;
                    sha1 = artifact.sha1;
                    url = artifact.url;
                    size = artifact.size;
                } else {
                    // If the library has a downloads section but doesn't have an artifact in
                    // it, it is likely natives-only, which means it can be skipped.
                    Log.i("NewMCDownloader", "Skipped library " + dependentLibrary.name + " due to lack of artifact");
                    continue;
                }
            }
            if(url == null) {
                url = (dependentLibrary.url == null
                        ? "https://libraries.minecraft.net/"
                        : dependentLibrary.url.replace("http://","https://")) + libArtifactPath;
                skipIfFailed = true;
            }
            if(!LauncherPreferences.PREF_CHECK_LIBRARY_SHA) sha1 = null;
            scheduleDownload(new File(Tools.DIR_HOME_LIBRARY, libArtifactPath),
                    DownloadMirror.DOWNLOAD_CLASS_LIBRARIES,
                    InstallStage.DOWNLOADING_LIBRARIES,
                    url, sha1, size, skipIfFailed
            );
        }
    }
    
    private void scheduleAssetDownloads(JAssets assets) throws IOException {
        Map<String, JAssetInfo> assetObjects = assets.objects;
        if(assetObjects == null) return;
        Set<String> assetNames = assetObjects.keySet();
        growDownloadList(assetNames.size());
        for(String asset : assetNames) {
            JAssetInfo assetInfo = assetObjects.get(asset);
            if(assetInfo == null) continue;
            File targetFile;
            String hashedPath = assetInfo.hash.substring(0, 2) + File.separator + assetInfo.hash;
            String basePath = assets.mapToResources ? Tools.OBSOLETE_RESOURCES_PATH : Tools.ASSETS_PATH;
            if(assets.virtual || assets.mapToResources) {
                targetFile = new File(basePath, asset);
            } else {
                targetFile = new File(basePath, "objects" + File.separator + hashedPath);
            }
            String sha1 = LauncherPreferences.PREF_CHECK_LIBRARY_SHA ? assetInfo.hash : null;
            scheduleDownload(targetFile,
                    DownloadMirror.DOWNLOAD_CLASS_ASSETS,
                    InstallStage.DOWNLOADING_ASSETS,
                    MINECRAFT_RES + hashedPath,
                    sha1,
                    assetInfo.size,
                    false);
        }
    }

    private void scheduleLoggingAssetDownloadIfNeeded(JMinecraftVersionList.LoggingConfig loggingConfig) throws IOException {
        if(loggingConfig.client == null || loggingConfig.client.file == null) return;
        JMinecraftVersionList.FileProperties loggingFileProperties = loggingConfig.client.file;
        File internalLoggingConfig = new File(Tools.DIR_DATA + File.separator + "security",
                loggingFileProperties.id.replace("client", "log4j-rce-patch"));
        if(internalLoggingConfig.exists()) return;
        File destination = new File(Tools.DIR_GAME_NEW, loggingFileProperties.id);
        scheduleDownload(destination,
                DownloadMirror.DOWNLOAD_CLASS_LIBRARIES,
                InstallStage.DOWNLOADING_LIBRARIES,
                loggingFileProperties.url,
                loggingFileProperties.sha1,
                loggingFileProperties.size,
                false);
    }

    private void scheduleGameJarDownload(MinecraftClientInfo minecraftClientInfo, String versionName) throws IOException {
        File clientJar = createGameJarPath(versionName);
        String clientSha1 = LauncherPreferences.PREF_CHECK_LIBRARY_SHA ?
                minecraftClientInfo.sha1 : null;
        growDownloadList(1);
        scheduleDownload(clientJar,
                DownloadMirror.DOWNLOAD_CLASS_LIBRARIES,
                InstallStage.DOWNLOADING_CLIENT_JAR,
                minecraftClientInfo.url,
                clientSha1,
                minecraftClientInfo.size,
                false
        );
        // Store the path of the JAR to copy it into our new version folder later.
        mSourceJarFile = clientJar;
    }

    private static byte[] getLocalBuffer() {
        byte[] tlb = sThreadLocalDownloadBuffer.get();
        if(tlb != null) return tlb;
        tlb = new byte[32768];
        sThreadLocalDownloadBuffer.set(tlb);
        return tlb;
    }

    private static void promoteStagingFile(@NonNull File stagingFile, @NonNull File targetFile) throws IOException {
        synchronized (sPromoteLock) {
            FileUtils.ensureParentDirectory(targetFile);
            if (!stagingFile.exists()) {
                if (targetFile.isFile() && targetFile.length() > 0L) {
                    return;
                }
                throw new FileNotFoundException("Staged file does not exist: " + stagingFile.getAbsolutePath());
            }
            if (targetFile.exists() && !targetFile.delete()) {
                throw new IOException("Failed to replace existing file: " + targetFile.getAbsolutePath());
            }
            if (!stagingFile.renameTo(targetFile)) {
                org.apache.commons.io.FileUtils.copyFile(stagingFile, targetFile, false);
                if (!stagingFile.delete()) {
                    stagingFile.deleteOnExit();
                }
            }
        }
    }

    private final class DownloaderTask implements Runnable, Tools.DownloaderFeedback {
        private final File mTargetPath;
        private final String mTargetUrl;
        private String mTargetSha1;
        private final int mDownloadClass;
        private final InstallStage mStage;
        private final boolean mSkipIfFailed;
        private int mLastCurr;
        private final long mDownloadSize;

        DownloaderTask(File targetPath, int downloadClass, InstallStage stage, String targetUrl, String targetSha1,
                       long downloadSize, boolean skipIfFailed) {
            this.mTargetPath = targetPath;
            this.mStage = stage;
            this.mTargetUrl = targetUrl;
            this.mTargetSha1 = targetSha1;
            this.mDownloadClass = downloadClass;
            this.mDownloadSize = downloadSize;
            this.mSkipIfFailed = skipIfFailed;
        }

        private String downloadSha1() throws IOException {
            String downloadedHash = DownloadMirror.downloadStringMirrored(
                    mDownloadClass, mTargetUrl + ".sha1"
            );
            if(!Tools.isValidString(downloadedHash)) return null;
            // Ensure that we don't have leading/trailing whitespaces before checking hash length
            downloadedHash = downloadedHash.trim();
            // SHA1 is made up of 20 bytes, which means 40 hexadecimal digits, which means 40 chars
            if(downloadedHash.length() != 40) return null;
            return downloadedHash;
        }

        /*
         * Maven repositories usually have the hash of a library near it, like:
         * .../libraryName-1.0.jar
         * .../libraryName.1.0.jar.sha1
         * Since Minecraft libraries are stored in maven repositories, try to use
         * this when downloading libraries without hashes in the json.
         */
        private void tryGetLibrarySha1() throws IOException {
            File sha1CacheDir = new File(Tools.DIR_CACHE + "/sha1hashes");
            File cacheFile = new File(sha1CacheDir.getAbsolutePath() + FileUtils.getFileName(mTargetUrl) + ".sha");

            // Only use cache when its offline. No point in having cache invalidation now!
            if (!isOnline || !LauncherPreferences.PREF_CHECK_LIBRARY_SHA) { // Well not only offlines..this setting speeds up launch times at least!
                try (BufferedReader cacheFileReader = new BufferedReader(new FileReader(cacheFile))) {
                    mTargetSha1 = cacheFileReader.readLine();
                    if (mTargetSha1 != null) {
                        Log.i("MinecraftDownloader", "Reading Hash from cache: " + mTargetSha1 + " from " + cacheFile);
                    } else if (cacheFile.exists()) {
                        Log.i("MinecraftDownloader", "Deleting invalid hash from cache: " + cacheFile);
                        cacheFile.delete();
                    }
                } catch (FileNotFoundException ignored) {
                    mTargetSha1 = null;
                    Log.w("MinecraftDownloader", "Failed to read hash for " + cacheFile);
                }
                return;
            }

            String resultHash = null;
            try {
                resultHash = downloadSha1();
                // The hash is a 40-byte download.
                mInternetUsageCounter.getAndAdd(40);
            } catch (IOException e) {
                Log.i("MinecraftDownloader", "Failed to download hash", e);
                if (cacheFile.exists() && new BufferedReader(new FileReader(cacheFile)).readLine() == null) {
                    Log.i("MinecraftDownloader", "Deleting failed hash download from cache: " + cacheFile);
                    cacheFile.delete();
                }
            }
            if (resultHash != null) {
                Log.i("MinecraftDownloader", "Got hash: " + resultHash + " for " + FileUtils.getFileName(mTargetUrl));
                mTargetSha1 = resultHash;
                if (!sha1CacheDir.exists()) {
                    sha1CacheDir.mkdir(); // If mkdir() fails, something went wrong with initializing /data/data/. mkdirs() isn't used on purpose
                }
                try (FileWriter writeHash = new FileWriter(cacheFile)) {
                    Log.i("MinecraftDownloader", "Saving hash: " + resultHash + " for " + FileUtils.getFileName(mTargetUrl) + " to " + cacheFile);
                    writeHash.write(resultHash);
                }
            }
        }

        @Override
        public void run() {
            try {
                checkCancelled();
                runCatching();
            } catch (CancellationException e) {
                mCancelFlag.set(true);
            } catch (InterruptedIOException e) {
                if (isCancelled()) {
                    mCancelFlag.set(true);
                } else {
                    mDownloaderThreadException.compareAndSet(null, e);
                }
            } catch (Exception e) {
                if (isCancelled()) {
                    mCancelFlag.set(true);
                } else {
                    mDownloaderThreadException.compareAndSet(null, e);
                }
            }
        }

        private void runCatching() throws Exception {
            checkCancelled();
            mCurrentFile.set(mTargetPath.getName());
            if(mDownloadClass == DownloadMirror.DOWNLOAD_CLASS_LIBRARIES && !Tools.isValidString(mTargetSha1)) {
                // If we're downloading a library, try to get sha1 since it might be available as a file
                tryGetLibrarySha1();
            }
            if(Tools.isValidString(mTargetSha1)) {
                verifyFileSha1();
            }else {
                mTargetSha1 = null; // Nullify SHA1 as DownloadUtils.ensureSha1 only checks for null,
                                    // not for string validity
                if(mTargetPath.exists() && mTargetPath.length() > 0L) finishWithoutDownloading();
                else downloadFile();
            }
        }
        
        private void verifyFileSha1() throws Exception {
            if(mTargetPath.isFile() && mTargetPath.canRead() && Tools.compareSHA1(mTargetPath, mTargetSha1)) {
                finishWithoutDownloading();
            } else {
                // Rely on the download function to throw an IOE in case if the file is not
                // writable/not a file/etc...
                downloadFile();
            }
        }
        
        private void downloadFile() throws Exception {
            File stagingFile = new File(mTargetPath.getAbsolutePath() + "." + Thread.currentThread().getId() + ".aetherpart");
            boolean promoted = false;
            try {
                if (stagingFile.exists()) {
                    stagingFile.delete();
                }
                DownloadUtils.ensureSha1(stagingFile, mTargetSha1, () -> {
                    checkCancelled();
                    DownloadMirror.downloadFileMirrored(mDownloadClass, mTargetUrl, stagingFile,
                            getLocalBuffer(), this);
                    checkCancelled();
                    return null;
                });
                checkCancelled();
                promoteStagingFile(stagingFile, mTargetPath);
                promoted = true;
            } catch (CancellationException e) {
                throw e;
            } catch (Exception e) {
                if (isCancelled()) {
                    throw new CancellationException("Download cancelled");
                }
                if(!mSkipIfFailed) throw e;
            } finally {
                if (!promoted && stagingFile.exists()) {
                    stagingFile.delete();
                }
            }
            if (promoted) {
                mProcessedFileCounter.incrementAndGet();
            }
        }

        private void finishWithoutDownloading() {
            mProcessedFileCounter.incrementAndGet();
            mProcessedSizeCounter.addAndGet(mDownloadSize);
        }

        @Override
        public void updateProgress(int curr, int max) {
            checkCancelled();
            mCurrentFile.set(mTargetPath.getName());
            int delta = curr - mLastCurr;
            if (delta > 0) {
                mProcessedSizeCounter.addAndGet(delta);
                mInternetUsageCounter.addAndGet(delta);
            }
            mLastCurr = curr;
        }
    }
}
