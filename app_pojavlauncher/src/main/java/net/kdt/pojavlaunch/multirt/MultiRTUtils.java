package net.kdt.pojavlaunch.multirt;

import static net.kdt.pojavlaunch.Architecture.getDeviceArchitecture;
import static net.kdt.pojavlaunch.Tools.NATIVE_LIB_DIR;
import static org.apache.commons.io.FileUtils.listFiles;

import android.system.Os;
import android.util.Log;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.Architecture;
import net.kdt.pojavlaunch.NewJREUtil.ExternalRuntime;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.MathUtils;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

public class MultiRTUtils {

    public enum RuntimeValidationStatus {
        VALID,
        MISSING,
        CORRUPTED,
        ARCHITECTURE_MISMATCH,
        JAVA_VERSION_TOO_LOW
    }

    public static final class RuntimeValidationReport {
        public final String name;
        public final Runtime runtime;
        public final RuntimeValidationStatus status;
        public final String reason;

        public RuntimeValidationReport(String name, Runtime runtime, RuntimeValidationStatus status, String reason) {
            this.name = name;
            this.runtime = runtime;
            this.status = status;
            this.reason = reason;
        }
    }

    public static final class RuntimeSelectionResult {
        public final Runtime selectedRuntime;
        public final List<RuntimeValidationReport> reports;
        public final String errorMessage;

        public RuntimeSelectionResult(Runtime selectedRuntime, List<RuntimeValidationReport> reports, String errorMessage) {
            this.selectedRuntime = selectedRuntime;
            this.reports = reports;
            this.errorMessage = errorMessage;
        }
    }

    private static final HashMap<String,Runtime> sCache = new HashMap<>();

    private static final String JAVA_VERSION_STR = "JAVA_VERSION=\"";
    private static final String OS_ARCH_STR = "OS_ARCH=\"";

    public static File getRuntimeFolder() {
        String base = Tools.MULTIRT_HOME;
        if (base == null) {
            base = (Tools.DIR_DATA != null ? Tools.DIR_DATA : System.getProperty("java.io.tmpdir")) + "/runtimes";
        }
        return new File(base);
    }

    public static void clearCache() {
        sCache.clear();
    }

    public static List<Runtime> getInstalledRuntimes() {
        File runtimeFolder = getRuntimeFolder();
        if(!runtimeFolder.exists() && !runtimeFolder.mkdirs()) {
            throw new RuntimeException("Failed to create runtime directory: " + runtimeFolder.getAbsolutePath());
        }

        ArrayList<Runtime> runtimes = new ArrayList<>();
        File[] files = runtimeFolder.listFiles();
        if(files != null) for(File f : files) {
            if (f.isDirectory()) {
                runtimes.add(read(f.getName()));
            }
        }
        else throw new RuntimeException("The runtime directory does not exist: " + runtimeFolder.getAbsolutePath());

        return runtimes;
    }

    public static List<Runtime> getInstalledCompatibleRuntimes() {
        ArrayList<Runtime> compatible = new ArrayList<>();
        for (Runtime runtime : getInstalledRuntimes()) {
            RuntimeValidationReport report = inspectRuntime(runtime.name, 0, true);
            if (report.status == RuntimeValidationStatus.VALID) {
                compatible.add(report.runtime);
            }
        }
        return compatible;
    }

    public static boolean hasJvmLibrary(File runtimeDir, String arch) {
        if (runtimeDir == null || !runtimeDir.isDirectory()) return false;
        File libDir = new File(runtimeDir, "lib");
        if (!libDir.isDirectory()) return false;

        if (new File(libDir, "server/libjvm.so").isFile() || new File(libDir, "client/libjvm.so").isFile()) {
            return true;
        }
        if (arch != null && !arch.isEmpty()) {
            String archSpec = Architecture.archAsInt(arch) == Architecture.ARCH_X86 ? "i386/i486/i586" : arch;
            for (String candidate : archSpec.split("/")) {
                File archLib = new File(libDir, candidate);
                if (new File(archLib, "server/libjvm.so").isFile() || new File(archLib, "client/libjvm.so").isFile()) {
                    return true;
                }
            }
        }
        return new File(runtimeDir, "bin/java").isFile();
    }

    public static boolean hasValidRuntimeStructure(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        File dir = new File(getRuntimeFolder(), name);
        if (!dir.isDirectory()) return false;
        Runtime runtime = forceReread(name);
        if (runtime.versionString == null || runtime.javaVersion <= 0 || runtime.arch == null) {
            return false;
        }
        return hasJvmLibrary(dir, runtime.arch);
    }

    public static RuntimeValidationReport inspectRuntime(String name, int requiredJavaVersion) {
        return inspectRuntime(name, requiredJavaVersion, true);
    }

    public static RuntimeValidationReport inspectRuntime(String name, int requiredJavaVersion, boolean forceRefresh) {
        if (name == null || name.trim().isEmpty()) {
            return new RuntimeValidationReport("", null, RuntimeValidationStatus.MISSING, "No runtime selected");
        }
        File dir = new File(getRuntimeFolder(), name);
        if (!dir.exists() || !dir.isDirectory()) {
            sCache.remove(name);
            return new RuntimeValidationReport(name, null, RuntimeValidationStatus.MISSING,
                    "Runtime '" + name + "' is not installed at " + dir.getAbsolutePath());
        }
        Runtime runtime = forceRefresh ? forceReread(name) : read(name);
        if (runtime.versionString == null || runtime.javaVersion <= 0 || runtime.arch == null) {
            return new RuntimeValidationReport(name, runtime, RuntimeValidationStatus.CORRUPTED,
                    "Runtime '" + name + "' is missing valid release metadata (JAVA_VERSION/OS_ARCH)");
        }
        if (!hasJvmLibrary(dir, runtime.arch)) {
            return new RuntimeValidationReport(name, runtime, RuntimeValidationStatus.CORRUPTED,
                    "Runtime '" + name + "' is incomplete or corrupted (missing libjvm.so / bin/java)");
        }
        if (!Architecture.isRuntimeArchCompatible(runtime.arch)) {
            String deviceArch = Architecture.archAsString(Architecture.getDeviceArchitecture());
            return new RuntimeValidationReport(name, runtime, RuntimeValidationStatus.ARCHITECTURE_MISMATCH,
                    "Runtime '" + name + "' architecture '" + runtime.arch + "' is incompatible with device architecture '" + deviceArch + "'");
        }
        if (requiredJavaVersion > 0 && runtime.javaVersion < requiredJavaVersion) {
            return new RuntimeValidationReport(name, runtime, RuntimeValidationStatus.JAVA_VERSION_TOO_LOW,
                    "Runtime '" + name + "' provides Java " + runtime.javaVersion + ", but Java " + requiredJavaVersion + "+ is required");
        }
        return new RuntimeValidationReport(name, runtime, RuntimeValidationStatus.VALID,
                "Valid Java " + runtime.javaVersion + " (" + runtime.arch + ")");
    }

    public static RuntimeSelectionResult selectRuntimeForLaunch(String preferredRuntimeName, int targetJavaVersion) {
        ArrayList<RuntimeValidationReport> reports = new ArrayList<>();
        String deviceArch = Architecture.archAsString(Architecture.getDeviceArchitecture());

        if (preferredRuntimeName != null && !preferredRuntimeName.trim().isEmpty()) {
            RuntimeValidationReport preferredReport = inspectRuntime(preferredRuntimeName, targetJavaVersion, true);
            if (preferredReport.status == RuntimeValidationStatus.VALID) {
                reports.add(preferredReport);
                return new RuntimeSelectionResult(preferredReport.runtime, reports, "");
            }
            reports.add(preferredReport);
        }

        ArrayList<Runtime> validCandidates = new ArrayList<>();
        for (Runtime installed : getInstalledRuntimes()) {
            if (preferredRuntimeName != null && preferredRuntimeName.equals(installed.name)) {
                continue;
            }
            RuntimeValidationReport report = inspectRuntime(installed.name, targetJavaVersion, false);
            reports.add(report);
            if (report.status == RuntimeValidationStatus.VALID) {
                validCandidates.add(report.runtime);
            }
        }

        MathUtils.RankedValue<Runtime> nearest = MathUtils.findNearestPositive(
                targetJavaVersion, validCandidates, runtime -> runtime.javaVersion);
        if (nearest != null && nearest.value != null) {
            return new RuntimeSelectionResult(nearest.value, reports, "");
        }

        StringBuilder msg = new StringBuilder();
        msg.append("No compatible Java ").append(targetJavaVersion)
                .append("+ runtime found for device ABI '").append(deviceArch).append("'. ");
        if (reports.isEmpty()) {
            msg.append("No Java runtimes are installed in ")
                    .append(getRuntimeFolder().getAbsolutePath()).append(". ");
        } else {
            msg.append("Inspected runtimes: ");
            for (int i = 0; i < reports.size(); i++) {
                if (i > 0) msg.append("; ");
                msg.append(reports.get(i).reason);
            }
            msg.append(". ");
        }
        msg.append("Install a compatible Java runtime in Settings -> Java Runtimes.");
        return new RuntimeSelectionResult(null, reports, msg.toString());
    }

    /**
     *
     * @return Java versions which are not installed but are present in {@link ExternalRuntime}
     */
    public static List<ExternalRuntime> getRuntimesToDownload() {
        List<ExternalRuntime> runtimesToDownload = new ArrayList<>();
        ExternalRuntime[] downloadableRuntimes = ExternalRuntime.values();
        for (ExternalRuntime downloadableruntime : downloadableRuntimes) {
            if(getExactJreName(downloadableruntime.majorVersion) == null){
                // x86 isn't supported anymore for JRE21+
                if (!(getDeviceArchitecture() == Architecture.ARCH_X86 && downloadableruntime.majorVersion >= 21))
                    runtimesToDownload.add(downloadableruntime);
            }
        }
        return runtimesToDownload;
    }

    public static String getExactJreName(int majorVersion) {
        List<Runtime> runtimes = getInstalledCompatibleRuntimes();
        for(Runtime r : runtimes)
            if(r.javaVersion == majorVersion) return r.name;

        return null;
    }

    public static String getNearestJreName(int majorVersion) {
        List<Runtime> runtimes = getInstalledCompatibleRuntimes();
        MathUtils.RankedValue<Runtime> nearestRankedRuntime = MathUtils.findNearestPositive(majorVersion, runtimes, (runtime)->runtime.javaVersion);
        if(nearestRankedRuntime == null) return null;
        Runtime nearestRuntime = nearestRankedRuntime.value;
        if(nearestRuntime == null) return null;
        return nearestRuntime.name;
    }

    public static void installRuntimeNamed(String nativeLibDir, InputStream runtimeInputStream, String name) throws IOException {
        File runtimeFolder = getRuntimeFolder();
        File dest = new File(runtimeFolder,"/"+name);
        if(dest.exists()) FileUtils.deleteDirectory(dest);
        uncompressTarXZ(runtimeInputStream,dest);
        runtimeInputStream.close();
        unpack200(nativeLibDir,runtimeFolder.getAbsolutePath() + "/" + name);
        ProgressLayout.clearProgress(ProgressLayout.UNPACK_RUNTIME);
        forceReread(name);
    }

    public static void postPrepare(String name) throws IOException {
        File dest = new File(getRuntimeFolder(),"/" + name);
        if(!dest.exists()) return;
        Runtime runtime = read(name);
        String libFolder = "lib";
        if(runtime.arch != null && new File(dest,libFolder + "/" + runtime.arch).exists()) libFolder = libFolder + "/" + runtime.arch;
        File ftIn = new File(dest, libFolder + "/libfreetype.so.6");
        File ftOut = new File(dest, libFolder + "/libfreetype.so");
        if (ftIn.exists() && (!ftOut.exists() || ftIn.length() != ftOut.length())) {
            if(!ftIn.renameTo(ftOut)) throw new IOException("Failed to rename freetype");
        }

        // Refresh libraries
        copyDummyNativeLib("libawt_xawt.so", dest, libFolder);
    }

    public static void installRuntimeNamedBinpack(InputStream universalFileInputStream, InputStream platformBinsInputStream, String name, String binpackVersion) throws IOException {
        File runtimeFolder = getRuntimeFolder();
        File dest = new File(runtimeFolder,"/"+name);
        if(dest.exists()) FileUtils.deleteDirectory(dest);
        installRuntimeNamedNoRemove(universalFileInputStream,dest);
        installRuntimeNamedNoRemove(platformBinsInputStream,dest);

        unpack200(NATIVE_LIB_DIR,runtimeFolder.getAbsolutePath() + "/" + name);

        File binpack_verfile = new File(runtimeFolder,"/"+name+"/pojav_version");
        FileOutputStream fos = new FileOutputStream(binpack_verfile);
        fos.write(binpackVersion.getBytes());
        fos.close();

        ProgressLayout.clearProgress(ProgressLayout.UNPACK_RUNTIME);

        forceReread(name);
    }


    public static String readInternalRuntimeVersion(String name) {
        File versionFile = new File(getRuntimeFolder(),"/" + name + "/pojav_version");
        try {
            if (versionFile.exists()) {
                return Tools.read(versionFile.getAbsolutePath());
            }else{
                return null;
            }
        }catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void removeRuntimeNamed(String name) throws IOException {
        File dest = new File(getRuntimeFolder(),"/"+name);
        if(dest.exists()) {
            FileUtils.deleteDirectory(dest);
            sCache.remove(name);
        }
    }

    public static File getRuntimeHome(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new RuntimeException("No Java runtime selected!");
        }
        File dest = new File(getRuntimeFolder(), name);
        Log.i("MultiRTUtils", "Dest exists? "+dest.exists());
        RuntimeValidationReport report = inspectRuntime(name, 0, true);
        if (report.status == RuntimeValidationStatus.MISSING || report.status == RuntimeValidationStatus.CORRUPTED) {
            throw new RuntimeException("Selected runtime is broken: " + report.reason);
        }
        if (report.status == RuntimeValidationStatus.ARCHITECTURE_MISMATCH) {
            throw new RuntimeException("Selected runtime architecture mismatch: " + report.reason);
        }
        return dest;
    }

    public static Runtime forceReread(String name) {
        sCache.remove(name);
        return read(name);
    }

    public static Runtime read(String name) {
        if (name == null || name.trim().isEmpty()) {
            return new Runtime("");
        }
        Runtime returnRuntime = sCache.get(name);
        if(returnRuntime != null) return returnRuntime;
        File runtimeDir = new File(getRuntimeFolder(), name);
        File release = new File(runtimeDir, "release");
        if(!runtimeDir.isDirectory() || !release.isFile()) {
            return new Runtime(name);
        }
        try {
            String content = Tools.read(release.getAbsolutePath());
            String javaVersion = Tools.extractUntilCharacter(content, JAVA_VERSION_STR, '"');
            String osArch = Tools.extractUntilCharacter(content, OS_ARCH_STR, '"');
            if(javaVersion != null && !javaVersion.isEmpty() && osArch != null && !osArch.isEmpty()) {
                String[] javaVersionSplit = javaVersion.split("[.\\-_+u]");
                int javaVersionInt;
                if (javaVersionSplit[0].equals("1") && javaVersionSplit.length > 1) {
                    javaVersionInt = Integer.parseInt(javaVersionSplit[1]);
                } else {
                    javaVersionInt = Integer.parseInt(javaVersionSplit[0]);
                }
                returnRuntime = new Runtime(name, javaVersion, osArch, javaVersionInt);
                sCache.put(name, returnRuntime);
            }else{
                returnRuntime = new Runtime(name);
            }
        }catch(Exception e) {
            returnRuntime = new Runtime(name);
        }
        return returnRuntime;
    }

    /**
     * Unpacks all .pack files into .jar Serves only for java 8, as java 9 brought project jigsaw
     * @param nativeLibraryDir The native lib path, required to execute the unpack200 binary
     * @param runtimePath The path to the runtime to walk into
     */
    private static void unpack200(String nativeLibraryDir, String runtimePath) {

        File basePath = new File(runtimePath);
        Collection<File> files = listFiles(basePath, new String[]{"pack"}, true);

        File workdir = new File(nativeLibraryDir);

        ProcessBuilder processBuilder = new ProcessBuilder().directory(workdir);
        for(File jarFile : files){
            try{
                Process process = processBuilder.command("./libunpack200.so", "-r", jarFile.getAbsolutePath(), jarFile.getAbsolutePath().replace(".pack", "")).start();
                process.waitFor();
            }catch (InterruptedException | IOException e) {
                Log.e("MULTIRT", "Failed to unpack the runtime !");
            }
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static void copyDummyNativeLib(String name, File dest, String libFolder) throws IOException {
        File fileLib = new File(dest, "/"+libFolder + "/" + name);
        FileInputStream is = new FileInputStream(new File(NATIVE_LIB_DIR, name));
        FileOutputStream os = new FileOutputStream(fileLib);
        IOUtils.copy(is, os);
        is.close();
        os.close();
    }

    private static void installRuntimeNamedNoRemove(InputStream runtimeInputStream, File dest) throws IOException {
        uncompressTarXZ(runtimeInputStream,dest);
        runtimeInputStream.close();
    }

    private static void uncompressTarXZ(final InputStream tarFileInputStream, final File dest) throws IOException {
        net.kdt.pojavlaunch.utils.FileUtils.ensureDirectory(dest);

        byte[] buffer = new byte[8192];
        TarArchiveInputStream tarIn = new TarArchiveInputStream(
                new XZCompressorInputStream(tarFileInputStream)
        );
        TarArchiveEntry tarEntry = tarIn.getNextTarEntry();
        // tarIn is a TarArchiveInputStream
        while (tarEntry != null) {

            final String tarEntryName = tarEntry.getName();
            // publishProgress(null, "Unpacking " + tarEntry.getName());
            ProgressLayout.setProgress(ProgressLayout.UNPACK_RUNTIME, 100, R.string.global_unpacking, tarEntryName);

            File destPath = new File(dest, tarEntry.getName());
            net.kdt.pojavlaunch.utils.FileUtils.ensureParentDirectory(destPath);
            if (tarEntry.isSymbolicLink()) {
                try {
                    // android.system.Os
                    // Libcore one support all Android versions
                    Os.symlink(tarEntry.getName(), tarEntry.getLinkName());
                } catch (Throwable e) {
                    Log.e("MultiRT", e.toString());
                }

            } else if (tarEntry.isDirectory()) {
                net.kdt.pojavlaunch.utils.FileUtils.ensureDirectory(destPath);
            } else if (!destPath.exists() || destPath.length() != tarEntry.getSize()) {
                FileOutputStream os = new FileOutputStream(destPath);
                IOUtils.copyLarge(tarIn, os, buffer);
                os.close();
            }
            tarEntry = tarIn.getNextTarEntry();
        }
        tarIn.close();
    }
}
