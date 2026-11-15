# STAR Launcher v3.0.1 Release Notes

**Package:** `com.star.launcher`  
**Version:** `3.0.1` (`versionCode = 10000002`)  
**Target Branch:** `v3_openjdk`  
**Minimum Android:** Android 5.0 Lollipop (API 21) | Target SDK 34  
**Supported ABIs:** `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`

---

## ⚠️ Important Signing & Update Advisory

In `v3.0.0` (`versionCode 10000001`), a Gradle configuration fallback caused `assembleRelease` to silently sign the published `STAR-Launcher-v3.0.0.apk` with the Android `debug.keystore` (`SHA-256: d0d0886a0d7e3486e6627f9b09424530b6d7be14f8c2cc33`).

In **`3.0.1`**, that silent fallback has been **completely removed**. Release builds now fail cleanly unless the production STAR signing keystore credentials are explicitly provided, and CI verifies with `apksigner` that the release APK is not debug-signed.

> **Update Note for `v3.0.0` Users:** Because Android does not allow installing a production-signed APK directly over an APK signed with `debug.keystore`, users currently running the debug-signed `v3.0.0` APK must back up their Minecraft instances (`saves/`, `mods/`, `config/`) and uninstall `v3.0.0` before installing `v3.0.1`.

---

## New Features & Improvements

- **In-App Mod Loader Auto-Installer (`ModLoaderInstaller`, `ModsController`, `ModsScreen`, `ModDetailScreen`)**:
  - Vanilla instances attempting to install Fabric/Forge/NeoForge/Quilt mods now display clear loader-install actions instead of dead-end errors.
  - Supports automated Fabric, Quilt, and legacy Forge profile/library installation with SHA-1 verification and full preservation of `saves/`, `resourcepacks/`, `config/`, and `options.txt`.
  - Automatically detects Forge 1.13+ and NeoForge `install_profile.json` processors and hands off safely to `:gui_installer` (`JavaGUILauncherActivity`) when base client files are present.
- **Modrinth Keyless Fallback & Connection Diagnostics (`CurseForgeRepository`, `ModrinthFallbackCatalog`)**:
  - Clarified Mods Hub credential/connection states (`CONFIGURATION_REQUIRED`, `CONNECTED`, `CONNECTION_FAILED`) while preserving automatic keyless Modrinth v2 fallback browsing.
- **In-App Build & Version Panel (`SettingsScreen`)**:
  - Displays `BuildConfig.VERSION_NAME`, `BuildConfig.VERSION_CODE`, build type, `applicationId`, and minimum Android API level directly inside Settings.
- **Reproducible `MioLibPatcher` Patch (`patches/miolibpatcher-star.patch`)**:
  - Tracks STAR's 5 custom bytecode transformers (`GLTransformer`, `GlShaderUniformNameTransformer`, `GlSingleWindowTransformer`, `MinecraftSurfaceResumeTransformer`, `VulkanSurfaceTransformer`) in Git so clean CI clones build consistently.

## Bug Fixes

- Fixed `ModLoaderInstaller` previously marking processor-based Forge/NeoForge installs complete without running `install_profile.json` client processors, and removed synthetic partial vanilla JSON stub creation.
- Fixed `StarMusicPlayerService` foreground-service startup race (`ForegroundServiceDidNotStartInTimeException` risk on Android 12+ when stopped early or started with an empty playlist).
- Updated `ModLoaderInstaller` HTTP `User-Agent` header to track `BuildConfig.VERSION_NAME`.

## Performance & Stability Changes

- Moved `StarMusicPlayerService` album artwork `BitmapFactory` file decoding off the main thread onto a dedicated background executor with 512px bounds sampling, and removed unsafe eager `.recycle()` calls while notification/MediaSession IPC references may still be in flight.

## Security & CI Hardening

- Removed silent debug signing fallback on `release` / `gplay` Gradle build types; added explicit pre-package credential validation.
- Added `@xml/backup_rules` and `@xml/data_extraction_rules` to exclude `accounts/`, `aether_vault/`, `aether_launcher.db*`, and fallback prefs from Android Auto Backup and device-to-device backups.
- Upgraded `.github/workflows/android.yml` to run `:MioLibPatcher:test` and `:app_pojavlauncher:testDebugUnitTest`, verify `aapt2` metadata, reject debug-signed release APKs via `apksigner`, check all 4 native ABI folders, emit `.sha256` checksums instead of `.md5`, validate release tags against Gradle version, and use least-privilege `contents: read` permissions (with `contents: write` scoped strictly to draft release publishing on `v*` tags).

## Known Limitations

- **Physical Android Hardware Testing:** End-to-end device runtime tests (gameplay launch, touch HUD, notification media listener, Keystore hardware encryption) are **NOT VERIFIED** in automated CI/JVM build environments (JVM + Robolectric tests + APK/signature inspection only).
- **STAR Doctor / STAR Pulse / Compatibility Passport UI:** Interactive STAR Doctor repair wizards and curated `TESTED` Passport entries (`TestedMatrixProvider`) remain planned; `PerformanceMonitorOverlay` is Debug-only in the launcher process.
- **Unresolved Third-Party License/Binary Provenance:** See [`docs/LICENSE_AUDIT.md`](./LICENSE_AUDIT.md) (`bigcoreaffinity.c`, `SimpleFPEWrapper-release.aar`, `ExagearApacheCommons.jar`, `lwjgl-lwjglx.jar`, `libtwitchsdk.so`, `MioLibPatcher`, and `org.json:json:20230618`).

## Verification & Checksums

Verify any downloaded APK's SHA-256 checksum and signing certificate before installing:

```bash
sha256sum -c STAR-Launcher-v3.0.1.apk.sha256
apksigner verify --print-certs --verbose STAR-Launcher-v3.0.1.apk
```
