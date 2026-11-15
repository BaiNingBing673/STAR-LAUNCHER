package net.kdt.pojavlaunch.value.launcherprofiles;

import android.util.Log;

import androidx.annotation.NonNull;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class LauncherProfiles {
    public static MinecraftLauncherProfiles mainProfileJson;
    private static final Object PROFILE_LOCK = new Object();

    private static File getProfilesFile() {
        return new File(Tools.GAME_PROFILES_FILE);
    }

    /** Reload the profile from the file, creating a default one if necessary */
    public static void load() {
        synchronized (PROFILE_LOCK) {
            File profilesFile = getProfilesFile();
            if (profilesFile.exists()) {
                try {
                    mainProfileJson = Tools.GLOBAL_GSON.fromJson(
                            Tools.read(profilesFile.getAbsolutePath()),
                            MinecraftLauncherProfiles.class
                    );
                } catch (IOException e) {
                    Log.e(LauncherProfiles.class.toString(), "Failed to load file: ", e);
                    throw new RuntimeException(e);
                }
            } else {
                mainProfileJson = null;
            }

            boolean dirty = false;
            if (mainProfileJson == null) {
                mainProfileJson = new MinecraftLauncherProfiles();
            }
            if (mainProfileJson.profiles == null) {
                mainProfileJson.profiles = new HashMap<>();
            }

            Iterator<Map.Entry<String, MinecraftProfile>> iterator = mainProfileJson.profiles.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, MinecraftProfile> entry = iterator.next();
                if (entry.getKey() == null || entry.getValue() == null) {
                    iterator.remove();
                    dirty = true;
                }
            }

            if (mainProfileJson.profiles.isEmpty()) {
                String defaultKey = UUID.randomUUID().toString();
                mainProfileJson.profiles.put(defaultKey, MinecraftProfile.getDefaultProfile());
                persistCurrentProfileKeyLocked(defaultKey);
                dirty = true;
            }

            // Normalize profile names from mod installers while keeping selection in sync
            if (normalizeProfileIds(mainProfileJson)) {
                dirty = true;
            }

            reconcileCurrentProfileSelectionLocked();

            if (dirty || !profilesFile.exists()) {
                write();
            }
        }
    }

    /** Apply the current configuration into a file */
    public static void write() {
        synchronized (PROFILE_LOCK) {
            if (mainProfileJson == null) {
                mainProfileJson = new MinecraftLauncherProfiles();
            }
            if (mainProfileJson.profiles == null) {
                mainProfileJson.profiles = new HashMap<>();
            }
            File profilesFile = getProfilesFile();
            File parentDir = profilesFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            String serialized = mainProfileJson.toJson();
            File tempFile = new File(profilesFile.getParentFile(), profilesFile.getName() + ".tmp");
            try {
                Tools.write(tempFile.getAbsolutePath(), serialized);
                if (!tempFile.renameTo(profilesFile)) {
                    Tools.write(profilesFile.getAbsolutePath(), serialized);
                    //noinspection ResultOfMethodCallIgnored
                    tempFile.delete();
                }
            } catch (IOException e) {
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
                Log.e(LauncherProfiles.class.toString(), "Failed to write profile file", e);
                throw new RuntimeException(e);
            }
        }
    }

    public static @NonNull MinecraftProfile getCurrentProfile() {
        synchronized (PROFILE_LOCK) {
            if (mainProfileJson == null || mainProfileJson.profiles == null) {
                LauncherProfiles.load();
            }
            String resolvedKey = resolveOrSelectCurrentProfileKeyLocked();
            MinecraftProfile profile = resolvedKey != null ? mainProfileJson.profiles.get(resolvedKey) : null;
            if (profile == null) {
                throw new IllegalStateException("No Minecraft profile exists. Please create or select a profile.");
            }
            return profile;
        }
    }

    public static String getCurrentProfileKey() {
        synchronized (PROFILE_LOCK) {
            if (mainProfileJson == null || mainProfileJson.profiles == null) {
                LauncherProfiles.load();
            }
            return resolveOrSelectCurrentProfileKeyLocked();
        }
    }

    public static boolean selectProfile(String profileKey) {
        synchronized (PROFILE_LOCK) {
            if (profileKey == null || mainProfileJson == null || mainProfileJson.profiles == null) {
                return false;
            }
            MinecraftProfile profile = mainProfileJson.profiles.get(profileKey);
            if (profile == null) {
                return false;
            }
            persistCurrentProfileKeyLocked(profileKey);
            return true;
        }
    }

    public static boolean removeProfile(String profileKey) {
        synchronized (PROFILE_LOCK) {
            if (profileKey == null || mainProfileJson == null || mainProfileJson.profiles == null) {
                return false;
            }
            if (mainProfileJson.profiles.size() <= 1 || !mainProfileJson.profiles.containsKey(profileKey)) {
                return false;
            }
            mainProfileJson.profiles.remove(profileKey);
            reconcileCurrentProfileSelectionLocked();
            write();
            return true;
        }
    }

    /**
     * Insert a new profile into the profile map
     * @param minecraftProfile the profile to insert
     * @return the key under which the profile was inserted
     */
    public static String insertMinecraftProfile(MinecraftProfile minecraftProfile) {
        synchronized (PROFILE_LOCK) {
            if (mainProfileJson == null) {
                mainProfileJson = new MinecraftLauncherProfiles();
            }
            if (mainProfileJson.profiles == null) {
                mainProfileJson.profiles = new HashMap<>();
            }
            String key = getFreeProfileKey();
            mainProfileJson.profiles.put(key, minecraftProfile);
            return key;
        }
    }

    /**
     * Pick an unused normalized key to store a new profile with
     * @return an unused key
     */
    public static String getFreeProfileKey() {
        synchronized (PROFILE_LOCK) {
            if (mainProfileJson == null) {
                mainProfileJson = new MinecraftLauncherProfiles();
            }
            if (mainProfileJson.profiles == null) {
                mainProfileJson.profiles = new HashMap<>();
            }
            Map<String, MinecraftProfile> profileMap = mainProfileJson.profiles;
            String freeKey = UUID.randomUUID().toString();
            while (profileMap.get(freeKey) != null) {
                freeKey = UUID.randomUUID().toString();
            }
            return freeKey;
        }
    }

    private static String getPersistedProfileKeyLocked() {
        if (LauncherPreferences.DEFAULT_PREF == null) return "";
        return LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
    }

    private static void persistCurrentProfileKeyLocked(String key) {
        if (LauncherPreferences.DEFAULT_PREF != null && key != null) {
            LauncherPreferences.DEFAULT_PREF.edit()
                    .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, key)
                    .apply();
        }
    }

    private static String reconcileCurrentProfileSelectionLocked() {
        return resolveOrSelectCurrentProfileKeyLocked();
    }

    private static String resolveOrSelectCurrentProfileKeyLocked() {
        if (mainProfileJson == null || mainProfileJson.profiles == null || mainProfileJson.profiles.isEmpty()) {
            return null;
        }
        String currentKey = getPersistedProfileKeyLocked();
        if (currentKey != null && mainProfileJson.profiles.get(currentKey) != null) {
            return currentKey;
        }
        // Automatically select the first valid profile when the previous reference stopped existing
        for (Map.Entry<String, MinecraftProfile> entry : mainProfileJson.profiles.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                persistCurrentProfileKeyLocked(entry.getKey());
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * For all keys to be UUIDs, effectively isolating profile created by installers
     * This avoids certain profiles to be erased by the installer
     * @return Whether some profiles have been normalized
     */
    private static boolean normalizeProfileIds(MinecraftLauncherProfiles launcherProfiles) {
        if (launcherProfiles == null || launcherProfiles.profiles == null) {
            return false;
        }
        boolean hasNormalized = false;
        ArrayList<String> keys = new ArrayList<>();

        // Detect denormalized keys
        for (String profileKey : launcherProfiles.profiles.keySet()) {
            if (profileKey == null) continue;
            try {
                if (!UUID.fromString(profileKey).toString().equals(profileKey)) keys.add(profileKey);
            } catch (IllegalArgumentException exception) {
                keys.add(profileKey);
                Log.w(LauncherProfiles.class.toString(), "Illegal profile uuid: " + profileKey);
            }
        }

        String persistedKey = getPersistedProfileKeyLocked();

        // Swap the new keys while keeping selection references updated
        for (String profileKey : keys) {
            MinecraftProfile currentProfile = launcherProfiles.profiles.get(profileKey);
            launcherProfiles.profiles.remove(profileKey);
            hasNormalized = true;
            if (currentProfile == null) {
                continue;
            }
            String newKey = insertMinecraftProfile(currentProfile);
            if (profileKey.equals(persistedKey)) {
                persistCurrentProfileKeyLocked(newKey);
                persistedKey = newKey;
            }
        }

        return hasNormalized;
    }
}
