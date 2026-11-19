package net.kdt.pojavlaunch.modloaders.modpacks.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.imagecache.ModIconCache;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.ZipUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.zip.ZipFile;

public class ModpackInstaller {

    public static ModLoader installModpack(ModDetail modDetail, int selectedVersion, InstallFunction installFunction) throws IOException {
        return installModpack(modDetail, selectedVersion, null, installFunction);
    }

    public static ModLoader installModpack(
            ModDetail modDetail,
            int selectedVersion,
            Map<String, String> downloadHeaders,
            InstallFunction installFunction
    ) throws IOException {
        String versionUrl = modDetail.versionUrls[selectedVersion];
        if (versionUrl == null || versionUrl.trim().isEmpty()) {
            throw new CurseForgeException(
                    CurseForgeException.ErrorCode.DOWNLOAD_URL_MISSING,
                    "[" + CurseForgeException.ErrorCode.DOWNLOAD_URL_MISSING.name() + "] No official download URL is available for '"
                            + modDetail.title + "'."
            );
        }
        String versionHash = modDetail.versionHashes[selectedVersion];
        String modpackName = (modDetail.title.toLowerCase(Locale.ROOT) + " " + modDetail.versionNames[selectedVersion])
                .trim().replaceAll("[\\\\/:*?\"<>| \\t\\n]", "_" );
        if (versionHash != null) {
            modpackName += "_" + versionHash;
        }
        if (modpackName.length() > 255){
            modpackName = modpackName.substring(0,255);
        }

        // Build a new minecraft instance, folder first

        // Get the modpack file
        File modpackFile = new File(Tools.DIR_CACHE, modpackName + ".cf"); // Cache File
        ModLoader modLoaderInfo;
        try {
            byte[] downloadBuffer = new byte[8192];
            DownloadUtils.ensureSha1(modpackFile, versionHash, (Callable<Void>) () -> {
                DownloadUtils.downloadFileMonitored(versionUrl, modpackFile, downloadBuffer,
                        new DownloaderProgressWrapper(R.string.modpack_download_downloading_metadata,
                                ProgressLayout.INSTALL_MODPACK),
                        downloadHeaders,
                        -1L);
                return null;
            });

            // Install the modpack
            modLoaderInfo = installFunction.installModpack(modpackFile, new File(Tools.DIR_GAME_HOME, "custom_instances/"+modpackName));

        } finally {
            //noinspection ResultOfMethodCallIgnored It's cache, who cares
            modpackFile.delete();
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
        }
        if(modLoaderInfo == null) {
            return null;
        }

        // Create the instance
        MinecraftProfile profile = new MinecraftProfile();
        profile.gameDir = "./custom_instances/" + modpackName;
        profile.name = modDetail.title;
        profile.lastVersionId = modLoaderInfo.getVersionId();
        profile.icon = ModIconCache.getBase64Image(modDetail.getIconCacheTag());


        LauncherProfiles.mainProfileJson.profiles.put(modpackName, profile);
        LauncherProfiles.write();

        return modLoaderInfo;
    }

    public static ModLoader importModpack(File modpackFile, int apiSource, InstallFunction installFunction) throws IOException, NoSuchAlgorithmException {
        return importModpack(modpackFile, apiSource, null, installFunction);
    }

    public static ModLoader importModpack(File modpackFile, int apiSource, String precomputedSha1, InstallFunction installFunction) throws IOException, NoSuchAlgorithmException {
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 1, R.string.import_modpack_start);
        // modpackFile is deleted by caller in finally, no need to delete here.
        if (modpackFile == null || !modpackFile.isFile()) throw new IOException("Can't open modpack file, try again?");
        String manifestFileName;
        switch (apiSource) {
            case Constants.SOURCE_CURSEFORGE:
                manifestFileName = "manifest.json";
                break;
            case Constants.SOURCE_MODRINTH:
                manifestFileName = "modrinth.index.json";
                break;
            default:
                throw new UnsupportedOperationException("Unknown API source: " + apiSource);
        }
        // Read Manifest JSON safely with try-with-resources so the large ZipFile handle is never leaked
        JsonObject manifestFile;
        try (ZipFile zip = new ZipFile(modpackFile);
             InputStream manifestStream = ZipUtils.getEntryStream(zip, manifestFileName)) {
            manifestFile = JsonParser.parseString(Tools.read(manifestStream)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Corrupt Modpack manifest file: " + e.getMessage(), e);
        }

        // Parse the JSON to prepare for instance creation
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 1, R.string.import_modpack_json);
        String modpackName = "";
        String modpackVersion = "";
        String modpackMcVersion = "";

        switch (apiSource) {
            case Constants.SOURCE_CURSEFORGE:
                try {
                    modpackName = manifestFile.get("name").getAsString();
                    modpackVersion = manifestFile.has("version") && !manifestFile.get("version").isJsonNull()
                            ? manifestFile.get("version").getAsString()
                            : "1.0";
                    modpackMcVersion = manifestFile.get("minecraft").getAsJsonObject().get("version").getAsString();
                } catch (RuntimeException ignored) {}
                break;
            case Constants.SOURCE_MODRINTH:
                try {
                    modpackName = manifestFile.get("name").getAsString();
                    modpackVersion = manifestFile.has("versionId") && !manifestFile.get("versionId").isJsonNull()
                            ? manifestFile.get("versionId").getAsString()
                            : "1.0";
                    modpackMcVersion = manifestFile.get("dependencies").getAsJsonObject().get("minecraft").getAsString();
                } catch (RuntimeException ignored) {}
                break;
            default:
                throw new UnsupportedOperationException("Unknown API source: " + apiSource);
        }
        if (modpackName.isBlank() || modpackMcVersion.isBlank()) {
            throw new IOException("Corrupt Modpack manifest file: missing required name or Minecraft version.");
        }
        if (modpackVersion.isBlank()) {
            modpackVersion = "1.0";
        }

        String hash;
        if (precomputedSha1 != null && !precomputedSha1.isBlank()) {
            hash = precomputedSha1;
        } else {
            // Hash the ZIP File with cancellation checks when not already computed during streaming copy
            MessageDigest algorithm = MessageDigest.getInstance("SHA-1");
            long fileSize = modpackFile.length();
            long readSize = 0;
            byte[] buffer = new byte[65536];
            //noinspection IOStreamConstructor
            try (DigestInputStream hashingStream = new DigestInputStream(new FileInputStream(modpackFile), algorithm)) {
                while (true) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("Modpack import cancelled");
                    }
                    int n = hashingStream.read(buffer);
                    if (n == -1) break;
                    readSize += n;
                    String readMB = fileSize > 0 ? String.format(Locale.US, "%.2f", readSize / (1024.0 * 1024.0)) : "unknown";
                    String totalMB = fileSize > 0 ? String.format(Locale.US, "%.2f", fileSize / (1024.0 * 1024.0)) : "unknown";
                    int progress = fileSize > 0 ? (int) ((readSize * 100L) / fileSize) : 0;
                    ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, progress, R.string.import_modpack_hash, readMB, totalMB);
                }
            }
            byte[] digest = algorithm.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            hash = sb.toString();
        }
        String shortHash = hash.length() > 12 ? hash.substring(0, 12) : hash;
        String profileFolderName = String.join(" ", modpackName, modpackVersion, "for", modpackMcVersion, shortHash);
        profileFolderName = profileFolderName.trim().replaceAll("[\\\\/:*?\"<>| \\t\\n]", "_");
        if (profileFolderName.length() > 120) {
            profileFolderName = profileFolderName.substring(0, 120);
        }

        File customInstancesRoot = new File(Tools.DIR_GAME_HOME, "custom_instances");
        File targetDir = new File(customInstancesRoot, profileFolderName);
        File stagingDir = new File(customInstancesRoot, "." + profileFolderName + ".staging_" + System.currentTimeMillis());

        ModLoader modLoaderInfo;
        try {
            modLoaderInfo = installFunction.installModpack(modpackFile, stagingDir);
            if (!targetDir.exists()) {
                if (!stagingDir.renameTo(targetDir)) {
                    throw new IOException("Failed to finalize modpack installation directory: " + targetDir.getAbsolutePath());
                }
            } else {
                // Target already existed; install into a unique folder to preserve existing user data
                File uniqueTarget = new File(customInstancesRoot, profileFolderName + "_" + System.currentTimeMillis());
                if (!stagingDir.renameTo(uniqueTarget)) {
                    throw new IOException("Failed to finalize modpack installation directory: " + uniqueTarget.getAbsolutePath());
                }
                targetDir = uniqueTarget;
                profileFolderName = uniqueTarget.getName();
            }
        } catch (IOException | RuntimeException e) {
            deleteRecursivelyQuietly(stagingDir);
            throw e;
        } finally {
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
        }

        // Safely load LauncherProfiles before writing so mainProfileJson is never null
        try {
            LauncherProfiles.load();
            if (LauncherProfiles.mainProfileJson != null && LauncherProfiles.mainProfileJson.profiles != null) {
                MinecraftProfile profile = MinecraftProfile.getDefaultProfile();
                profile.gameDir = "./custom_instances/" + profileFolderName;
                profile.name = modpackName;
                if (!modpackMcVersion.isBlank()) profile.lastVersionId = modpackMcVersion;
                if (modLoaderInfo != null && modLoaderInfo.getVersionId() != null)
                    profile.lastVersionId = modLoaderInfo.getVersionId();
                LauncherProfiles.mainProfileJson.profiles.put(profileFolderName, profile);
                LauncherProfiles.write();
            }
        } catch (Exception ignored) {
            // Legacy profile write is best-effort when running from STAR's Room-backed InstanceManager
        }

        return modLoaderInfo;
    }

    private static void deleteRecursivelyQuietly(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursivelyQuietly(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

interface InstallFunction {
        ModLoader installModpack(File modpackFile, File instanceDestination) throws IOException;
    }
}
