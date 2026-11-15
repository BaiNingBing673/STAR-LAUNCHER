# STAR Launcher — Safe Manual Backup & Identity Migration Guide

> **Applies to**: Users upgrading from legacy development builds (`dev.aetherlauncher.app` / `dev.aetherlauncher.app.debug`) to the permanent **STAR Launcher** Android identity (`com.star.launcher` / `com.star.launcher.debug`).

---

## 1. Why In-Place Upgrade Is Not Possible

Android identifies every installed app uniquely by its `applicationId`. Because STAR Launcher migrated its permanent `applicationId` from `dev.aetherlauncher.app` to `com.star.launcher`:

- Android treats `com.star.launcher` as a distinct app alongside `dev.aetherlauncher.app`.
- **Both apps can temporarily coexist on the same device** (unless storage permissions or DocumentProvider selections conflict).
- **Do NOT uninstall the old Aether build before backing up your data.** Uninstalling removes app-private internal storage (`/data/data/dev.aetherlauncher.app/...` and Android/data scoped storage unless preserved).

---

## 2. What STAR Launcher Preserves Automatically Inside Its Own Data

When migrating data directories manually, STAR Launcher retains full schema and storage compatibility with legacy Aether files:

| Data Category | Exact Preserved Path / Identifier | Notes |
| --- | --- | --- |
| **Room Database** | `aether_launcher.db` (+ `-wal` / `-shm`) | Version `1` schema preserved; tables & columns intact |
| **Game Instances** | `<game_home>/aether_instances/<instanceId>/.minecraft/` | Worlds (`saves/`), resource packs, shader packs, configs, mods |
| **Legacy Shared Profiles** | `<game_home>/.minecraft/` & `launcher_profiles.json` | Preserved intact |
| **Java Runtimes** | `<filesDir>/runtimes/` (or app internal `components/jre/`) | Re-detected automatically on startup |
| **Keystore Token Vault** | Android Keystore alias `aether_token_vault_v1` | **Device/app-UID bound** — cannot be transferred across different `applicationId`s; sign in again after migration |

---

## 3. Recommended Safe Manual Backup & Migration Procedure

### Step A — Back Up Worlds, Instances & Profiles (Before Uninstalling Old Build)

1. Open your device's file manager (e.g., ZArchiver, MiXplorer, or Android Files app with DocumentsProvider) or connect via USB/ADB.
2. Locate your external scoped game data directory for the old package:
   - Release build: `/storage/emulated/0/Android/data/dev.aetherlauncher.app/files/`
   - Debug build: `/storage/emulated/0/Android/data/dev.aetherlauncher.app.debug/files/`
3. Copy the entire `files/` folder to a safe backup location on your device or PC, e.g.:
   `/storage/emulated/0/Download/STAR_Backup/`
   Verify your worlds exist under:
   - `STAR_Backup/aether_instances/<instance_id>/.minecraft/saves/`
   - `STAR_Backup/.minecraft/saves/` (if you used shared profiles)
   - `STAR_Backup/controlmap/` (custom on-screen controls)

### Step B — Install STAR Launcher (`com.star.launcher`)

1. Install the **STAR Launcher** APK (`com.star.launcher` or `com.star.launcher.debug`).
2. Launch STAR Launcher once so it initializes its scoped directories:
   `/storage/emulated/0/Android/data/com.star.launcher/files/` (or `.debug/files/`), then close STAR Launcher cleanly.

### Step C — Restore Your Worlds, Instances, Mods & Controls

1. Copy your backed-up folders from `/storage/emulated/0/Download/STAR_Backup/` into:
   `/storage/emulated/0/Android/data/com.star.launcher/files/` (or `.debug/files/`).
2. Re-open **STAR Launcher**:
   - **Existing instances**: Re-create instances in **Library** pointing to your restored version, or restore your worlds/mods into the instance directory shown in Instance Settings.
   - **Custom controls**: Re-import any `.json` control layouts from `controlmap/`.
   - **Microsoft Account**: Because Android Keystore hardware/UID isolation prevents transferring encrypted tokens (`aether_token_vault_v1`) between different `applicationId`s, sign in again under **Settings -> Accounts -> Microsoft Sign-In**.

### Step D — Optional Cleanup of Old Build

Only **after** launching Minecraft inside **STAR Launcher** and confirming all your worlds, saves, resource packs, and controls are intact:
- Keep your external `STAR_Backup/` copy for safety, and uninstall the old `dev.aetherlauncher.app` app if you no longer need it.
