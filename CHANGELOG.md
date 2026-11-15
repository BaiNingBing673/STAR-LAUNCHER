# Changelog

All notable changes to **STAR Launcher** (`com.star.launcher`) on branch `v3_openjdk` are documented in this file.

## [3.0.1] - 2026-11-15 (`versionCode 10000002`)

### Added
- **In-App Mod Loader Auto-Installer (`ModLoaderInstaller`, `ModsController`, `ModsScreen`, `ModDetailScreen`)**:
  - Added compatibility pre-flight blocking for Vanilla instances that require a mod loader before installing mods, with one-tap loader installation triggers directly inside Discover & Mod Detail flows.
  - Added Fabric, Quilt, and legacy Forge headless profile/library installation with byte progress tracking and SHA-1 checks while preserving user worlds, `saves/`, `resourcepacks/`, `config/`, and `options.txt`.
  - Added detection for Forge 1.13+ and NeoForge installers declaring `install_profile.json` `processors`, checking that base Minecraft client files exist and handing off cleanly to `JavaGUILauncherActivity` (`:gui_installer`).
  - Added explicit `ModsController` connection status states (`CONFIGURATION_REQUIRED`, `CONNECTED`, `CONNECTION_FAILED`) replacing ambiguous unconfigured messaging, alongside automatic `ModrinthFallbackCatalog` support when no CurseForge key is configured.
- **Settings About / Build Metadata Card (`SettingsScreen.kt`)**:
  - Displays `BuildConfig.VERSION_NAME`, `BuildConfig.VERSION_CODE`, build type, `applicationId`, and minimum Android version in-app.
- **Reproducible `MioLibPatcher` Overlay (`patches/miolibpatcher-star.patch`)**:
  - Exported STAR's 5 custom bytecode transformers (`GLTransformer`, `GlShaderUniformNameTransformer`, `GlSingleWindowTransformer`, `MinecraftSurfaceResumeTransformer`, `VulkanSurfaceTransformer`) and test runner dependency into a tracked patch applied cleanly during CI builds.

### Security & Signing Hardening
- **Eliminated Silent Debug Signing Fallback (`app_pojavlauncher/build.gradle`)**:
  - `release` and `gplay` builds now refuse to fall back silently to `debug.keystore` and fail with an explicit error unless production signing credentials (`STAR_KEYSTORE_FILE`, `STAR_KEYSTORE_PASSWORD`, `STAR_KEY_ALIAS`, `STAR_KEY_PASSWORD`) are supplied.
- **Android Auto Backup & Device Extraction Rules (`AndroidManifest.xml`, `backup_rules.xml`, `data_extraction_rules.xml`)**:
  - Excluded `accounts/`, `aether_vault/`, `aether_launcher.db*`, and fallback prefs from cloud Auto Backup and device-to-device extraction.
- **Hardened GitHub Actions Pipeline (`.github/workflows/android.yml`)**:
  - Added least-privilege `permissions: contents: read`, automated `:MioLibPatcher:test` & `:app_pojavlauncher:testDebugUnitTest` execution, tag-to-version matching, `aapt2` metadata validation, `apksigner` certificate verification (blocking debug-signed release APKs), native ABI checks, `.sha256` checksums (replacing MD5), and log artifacts.

### Fixed
- **Mods Hub State & Instance Sync (`ModsController.kt`, `ModsScreen.kt`, `ModDetailScreen.kt`)**:
  - Fixed instance loader detection edge cases and compatibility re-evaluation when switching instances or finishing a loader installation.
- **Removed Fake Vanilla Stub Manifest (`ModLoaderInstaller.kt`)**:
  - Stopped writing partial synthetic `<mcVersion>.json` files when base Minecraft files are absent; `ModLoaderInstaller` now verifies real base client files before handing off processor-based installers and uses `BuildConfig.VERSION_NAME` in HTTP `User-Agent`.
- **Background Music Foreground Compliance (`StarMusicPlayerService.kt`)**:
  - Guaranteed immediate `startForeground()` registration upon `onStartCommand()` across all service intents (including `ACTION_STOP` / empty playlists) to prevent Android 12+ `ForegroundServiceDidNotStartInTimeException`.
  - Moved album artwork `BitmapFactory` decoding off the main thread onto a dedicated background executor with 512px bounds sampling and removed unsafe `.recycle()` calls while notification/MediaSession IPC may hold references.

### Documentation
- Reconciled `README.md`, `docs/FEATURE_STATUS.md`, `docs/BUILDING.md`, and `docs/LICENSE_AUDIT.md` with verified implementation classifications, SHA-256 verification steps, and unresolved binary/license audit items.

---

## [3.0.0] - 2026-11-15 (`versionCode 10000001`)
- Initial `v3_openjdk` STAR Launcher rebranding and Material 3 Compose dashboard release (`STAR-Launcher-v3.0.0.apk`).
- **Signing Advisory:** The published `v3.0.0` APK on GitHub was signed with `debug.keystore` (`SHA-256: d0d0886a0d7e3486e6627f9b09424530b6d7be14f8c2cc33`) due to the silent fallback fixed in `3.0.1`. Users upgrading from `v3.0.0` to a production-signed `3.0.1+` build will need a clean reinstall (backing up worlds/saves/profiles first) because Android forbids in-place updates across different signing certificates.
