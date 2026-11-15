package net.kdt.pojavlaunch;

import static net.kdt.pojavlaunch.Architecture.archAsString;
import static net.kdt.pojavlaunch.Architecture.getDeviceArchitecture;
import static net.kdt.pojavlaunch.Tools.NATIVE_LIB_DIR;
import static net.kdt.pojavlaunch.Tools.isOnline;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.multirt.MultiRTConfigDialog;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.MathUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import com.star.launcher.ui.dashboard.AetherDashboardActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class NewJREUtil {
    private static boolean checkInternalRuntime(AssetManager assetManager, InternalRuntime internalRuntime) {
        String launcher_runtime_version;
        String installed_runtime_version = MultiRTUtils.readInternalRuntimeVersion(internalRuntime.name);
        try {
            launcher_runtime_version = Tools.read(assetManager.open(internalRuntime.path+"/version"));
        }catch (IOException exc) {
            //we don't have a runtime included!
            //if we have one installed -> return true -> proceed (no updates but the current one should be functional)
            //if we don't -> return false -> Cannot find compatible Java runtime
            return installed_runtime_version != null;
        }
        // this implicitly checks for null, so it will unpack the runtime even if we don't have one installed
        if(!launcher_runtime_version.equals(installed_runtime_version))
            return unpackInternalRuntime(assetManager, internalRuntime, launcher_runtime_version);
        else return true;
    }

    private static boolean unpackInternalRuntime(AssetManager assetManager, InternalRuntime internalRuntime, String version) {
        try {
            MultiRTUtils.installRuntimeNamedBinpack(
                    assetManager.open(internalRuntime.path+"/universal.tar.xz"),
                    assetManager.open(internalRuntime.path+"/bin-" + archAsString(Tools.DEVICE_ARCHITECTURE) + ".tar.xz"),
                    internalRuntime.name, version);
            MultiRTUtils.postPrepare(internalRuntime.name);
            return true;
        }catch (IOException e) {
            Log.e("NewJREAuto", "Internal JRE unpack failed", e);
            return false;
        }
    }

    private static InternalRuntime getInternalRuntime(Runtime runtime) {
        for(InternalRuntime internalRuntime : InternalRuntime.values()) {
            if(internalRuntime.name.equals(runtime.name)) return internalRuntime;
        }
        return null;
    }

    private static boolean hasInternalRuntimeAsset(AssetManager assetManager, InternalRuntime internalRuntime) {
        try (java.io.InputStream ignored = assetManager.open(internalRuntime.path + "/version")) {
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static MathUtils.RankedValue<Runtime> getNearestInstalledRuntime(int targetVersion) {
        List<Runtime> runtimes = MultiRTUtils.getInstalledCompatibleRuntimes();
        return MathUtils.findNearestPositive(targetVersion, runtimes, (runtime) -> runtime.javaVersion);
    }

    private static MathUtils.RankedValue<InternalRuntime> getNearestInternalRuntime(AssetManager assetManager, int targetVersion) {
        java.util.ArrayList<InternalRuntime> availableInternals = new java.util.ArrayList<>();
        for (InternalRuntime runtime : InternalRuntime.values()) {
            if (hasInternalRuntimeAsset(assetManager, runtime)) {
                availableInternals.add(runtime);
            }
        }
        return MathUtils.findNearestPositive(targetVersion, availableInternals, (runtime) -> runtime.majorVersion);
    }

    public static ExternalRuntime findDownloadableRuntime(int targetVersion) {
        java.util.ArrayList<ExternalRuntime> supported = new java.util.ArrayList<>();
        int deviceArch = getDeviceArchitecture();
        for (ExternalRuntime runtime : ExternalRuntime.values()) {
            if (deviceArch == Architecture.ARCH_X86 && runtime.majorVersion >= 21) {
                continue;
            }
            supported.add(runtime);
        }
        MathUtils.RankedValue<ExternalRuntime> ranked = MathUtils.findNearestPositive(
                targetVersion, supported, (runtime) -> runtime.majorVersion);
        return ranked != null ? ranked.value : null;
    }

    /** @return true if everything is good, false otherwise.  */
    public static boolean installNewJreIfNeeded(Activity activity, JMinecraftVersionList.Version versionInfo) {
        int gameRequiredVersion = (versionInfo != null && versionInfo.javaVersion != null && versionInfo.javaVersion.majorVersion > 0)
                ? versionInfo.javaVersion.majorVersion
                : 8;
        return ensureRuntimeReady(activity, gameRequiredVersion, true);
    }

    /**
     * Ensures a valid, architecture-compatible Java runtime matching {@code gameRequiredVersion}
     * is available before starting Minecraft. Works for both online and local/offline launches.
     */
    public static boolean ensureRuntimeReady(Activity activity, int gameRequiredVersion, boolean allowDownload) {
        int requiredJava = gameRequiredVersion > 0 ? gameRequiredVersion : 8;

        LauncherProfiles.load();
        AssetManager assetManager = activity.getAssets();
        MinecraftProfile minecraftProfile = LauncherProfiles.getCurrentProfile();
        String profileRuntime = Tools.getSelectedRuntime(minecraftProfile);
        MultiRTUtils.RuntimeValidationReport selectedReport =
                MultiRTUtils.inspectRuntime(profileRuntime, requiredJava, true);

        if (selectedReport.status == MultiRTUtils.RuntimeValidationStatus.VALID && selectedReport.runtime != null) {
            InternalRuntime internalRuntime = getInternalRuntime(selectedReport.runtime);
            if (internalRuntime != null) {
                return checkInternalRuntime(assetManager, internalRuntime);
            }
            return true;
        }

        MathUtils.RankedValue<?> nearestInstalledRuntime = getNearestInstalledRuntime(requiredJava);
        MathUtils.RankedValue<?> nearestInternalRuntime = getNearestInternalRuntime(assetManager, requiredJava);

        MathUtils.RankedValue<?> selectedRankedRuntime = MathUtils.objectMin(
                nearestInternalRuntime, nearestInstalledRuntime, (value) -> value.rank
        );

        String downloadFailure = null;
        if (selectedRankedRuntime != null && selectedRankedRuntime.value instanceof InternalRuntime) {
            if (!checkInternalRuntime(assetManager, (InternalRuntime) selectedRankedRuntime.value)) {
                selectedRankedRuntime = nearestInstalledRuntime;
            }
        }

        if (selectedRankedRuntime == null && allowDownload && isOnline(activity)) {
            try {
                ExternalRuntime candidate = findDownloadableRuntime(requiredJava);
                if (candidate != null) {
                    tryDownloadRuntime(activity, candidate.majorVersion);
                }
                selectedRankedRuntime = getNearestInstalledRuntime(requiredJava);
            } catch (RuntimeException downloadErr) {
                Log.e("NewJREAuto", "Automatic runtime download failed", downloadErr);
                downloadFailure = downloadErr.getMessage();
            }
        }

        if (selectedRankedRuntime == null || selectedRankedRuntime.value == null) {
            MultiRTUtils.RuntimeSelectionResult diagnostic =
                    MultiRTUtils.selectRuntimeForLaunch(profileRuntime, requiredJava);
            showRuntimeFail(activity, requiredJava, diagnostic.errorMessage, downloadFailure);
            return false;
        }

        Object selected = selectedRankedRuntime.value;
        String appropriateRuntime;
        InternalRuntime internalRuntime;

        if (selected instanceof Runtime) {
            Runtime selectedRuntime = (Runtime) selected;
            appropriateRuntime = selectedRuntime.name;
            internalRuntime = getInternalRuntime(selectedRuntime);
        } else if (selected instanceof InternalRuntime) {
            internalRuntime = (InternalRuntime) selected;
            appropriateRuntime = internalRuntime.name;
        } else {
            throw new RuntimeException("Unexpected type of selected: " + selected.getClass().getName());
        }

        if (internalRuntime != null && !checkInternalRuntime(assetManager, internalRuntime)) {
            return false;
        }

        if (!MultiRTUtils.inspectRuntime(appropriateRuntime, requiredJava, true)
                .status.equals(MultiRTUtils.RuntimeValidationStatus.VALID)) {
            MultiRTUtils.RuntimeSelectionResult diagnostic =
                    MultiRTUtils.selectRuntimeForLaunch(appropriateRuntime, requiredJava);
            showRuntimeFail(activity, requiredJava, diagnostic.errorMessage, downloadFailure);
            return false;
        }

        if (minecraftProfile != null) {
            minecraftProfile.javaDir = Tools.LAUNCHERPROFILES_RTPREFIX + appropriateRuntime;
            LauncherProfiles.write();
        }
        return true;
    }

    private static void showRuntimeFail(Activity activity, int requiredVersion, String detailMessage, String downloadFailure) {
        StringBuilder message = new StringBuilder();
        message.append(activity.getString(R.string.multirt_nocompatiblert, requiredVersion));
        if (detailMessage != null && !detailMessage.trim().isEmpty()) {
            message.append("\n\n").append(detailMessage);
        }
        if (downloadFailure != null && !downloadFailure.trim().isEmpty()) {
            message.append("\n\nDownload attempt failed: ").append(downloadFailure);
        }
        final String dialogBody = message.toString();
        Tools.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.multirt_config_title)
                    .setMessage(dialogBody)
                    .setPositiveButton(R.string.multirt_config_title, (dialog, which) -> {
                        if (activity instanceof AetherDashboardActivity) {
                            ((AetherDashboardActivity) activity).openRuntimeManagement();
                        } else {
                            MultiRTConfigDialog configDialog = new MultiRTConfigDialog();
                            configDialog.prepare(activity, null);
                            configDialog.show();
                        }
                    })
                    .setNegativeButton(android.R.string.ok, null)
                    .show();
        });
    }

    public static boolean isJavaVersionAvailableForDownload(int version) {
        return findDownloadableRuntime(version) != null;
    }

    private static String getJreSource(int javaVersion, String arch){
        return String.format("https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre%1$s/jre%1$s-android-%2$s.tar.xz", javaVersion, arch);
    }
    /**
     * Downloads and validates the nearest downloadable OpenJDK runtime >= javaVersion.
     */
    private static void tryDownloadRuntime(Context activity, int javaVersion){
        if (!isOnline(activity)) throw new RuntimeException(activity.getString(R.string.multirt_no_internet));
        int deviceArch = getDeviceArchitecture();
        String arch = archAsString(deviceArch);
        ExternalRuntime downloadable = findDownloadableRuntime(javaVersion);
        if (downloadable == null) throw new RuntimeException("No downloadable JRE matches Java " + javaVersion + "+ for " + arch);
        int targetVersion = downloadable.majorVersion;
        if (deviceArch == Architecture.ARCH_X86 && targetVersion >= 21) throw new RuntimeException("x86 is not supported on Java " + targetVersion);
        File outputFile = new File(Tools.DIR_CACHE, String.format("jre%s-android-%s.tar.xz", targetVersion, arch));
        try {
            DownloaderProgressWrapper monitor = new DownloaderProgressWrapper(R.string.newdl_downloading_jre_runtime,
                    ProgressLayout.UNPACK_RUNTIME);
            monitor.extraString = Integer.toString(targetVersion);
            DownloadUtils.downloadFileMonitored(
                    getJreSource(targetVersion, arch),
                    outputFile,
                    null,
                    monitor
            );
            String jreName = downloadable.name;
            MultiRTUtils.installRuntimeNamed(NATIVE_LIB_DIR, new FileInputStream(outputFile), jreName);
            MultiRTUtils.postPrepare(jreName);
            MultiRTUtils.RuntimeValidationReport report = MultiRTUtils.inspectRuntime(jreName, targetVersion, true);
            if (report.status != MultiRTUtils.RuntimeValidationStatus.VALID) {
                MultiRTUtils.removeRuntimeNamed(jreName);
                throw new RuntimeException("Downloaded runtime '" + jreName + "' failed validation: " + report.reason);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to download Java " + targetVersion + " for " + arch, e);
        } finally {
            if (outputFile.exists()) {
                outputFile.delete();
            }
        }
    }

    private enum InternalRuntime {
        JRE_17(17, "Internal-17", "components/jre-new"),
        JRE_21(21, "Internal-21", "components/jre-21"),
        JRE_25(25, "Internal-25", "components/jre-25");
        public final int majorVersion;
        public final String name;
        public final String path;
        InternalRuntime(int majorVersion, String name, String path) {
            this.majorVersion = majorVersion;
            this.name = name;
            this.path = path;
        }
    }

    public enum ExternalRuntime {
        JRE_8(8, "External-8"),
        JRE_17(17, "External-17"),
        JRE_21(21, "External-21"),
        JRE_25(25, "External-25");
        public final int majorVersion;
        public final String name;
        public final String downloadLink;
        public boolean isDownloading = false;

        ExternalRuntime(int majorVersion, String name) {
            this.majorVersion = majorVersion;
            this.name = name;
            this.downloadLink = getJreSource(majorVersion, archAsString(getDeviceArchitecture()));
        }
        public void downloadRuntime(Context activity){
            tryDownloadRuntime(activity, majorVersion);
        }
    }

}