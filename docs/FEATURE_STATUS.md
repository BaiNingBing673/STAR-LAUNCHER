# STAR Launcher Feature Verification & Status Matrix

Every capability listed below has been audited directly against source files in `app_pojavlauncher/src/` and test suites in `app_pojavlauncher/src/test/` and `MioLibPatcher/src/test/` as of `3.0.1` (`versionCode = 10000002`).

> **Important Honesty Note:** Automated JVM and Robolectric unit tests verify core business logic, Room persistence, JSON/LRC parsing, dependency resolution, and mock installer flows. **Physical Android device testing (end-to-end live game launch, OpenGL/Vulkan rendering, touch gameplay, media notification listener, and hardware Keystore tests) is NOT VERIFIED in this environment.**

---

## Classification Key

1. **Fully implemented and verified** *(implemented in UI + backend; verified by compilation/static audit and JVM/Robolectric tests where available)*
2. **Implemented but partially functional** *(working core path with known gaps, debug-only gating, or secondary flows incomplete)*
3. **UI implemented, backend incomplete**
4. **Backend implemented, UI incomplete**
5. **Planned / not implemented**
6. **Unable to verify** *(implementation exists, but runtime behavior on physical Android hardware could not be tested here)*

---

## Feature Matrix

| Feature | Classification | Code Evidence | Notes & Limitations |
|---|---|---|---|
| **6-Tab Compose Hub & Navigation** | **1 — Fully implemented and verified** *(on-device visual QA: 6)* | `AetherDashboardActivity.kt`, `AetherNavRoutes.kt`, `AetherNavigation.kt`, `AetherDashboardViewModel.kt` | `Home`, `Library`, `Versions`, `Mods`, `Community`, `Settings` tabs with `SavedStateHandle` back-stack persistence. |
| **Instance Management** | **1 — Fully implemented and verified** | `InstanceManager.kt`, `InstanceContentManager.kt`, `LibraryScreen.kt`, `InstanceDetailScreen.kt` | Isolated `aether_instances/<id>/.minecraft/` folders, non-destructive `launcher_profiles.json` import, RAM/JVM/runtime/renderer bindings, single-flight prep lock. |
| **Minecraft Version Installation** | **1 — Fully implemented and verified** *(live download/launch: 6)* | `VersionCatalogService.kt`, `VersionInstallCoordinator.kt`, `VersionManager.kt`, `VersionInstallScreen.kt` | Mojang `version_manifest_v2.json` sync, SHA-1 verification, `.aetherpart` staging cleanup, parent inheritance chain checks. |
| **Mods Hub (Discover & My Mods)** | **1 — Fully implemented and verified** *(live API/device UI: 6)* | `ModsScreen.kt`, `ModDetailScreen.kt`, `ModsController.kt`, `ModDependencyResolver.kt`, `ModDownloadInstaller.kt`, `InstalledModManager.kt`, `ModLoaderInstaller.kt` | Browse/filter/search, dependency resolution, SHA verification, `.jar` enable/disable/delete/update checks, and Fabric/Quilt/legacy-Forge/processor-based Forge/NeoForge loader handoff (`JavaGUILauncherActivity`). |
| **CurseForge Integration** | **1 — Fully implemented and verified** *(live key test depends on user/CI key)* | `CurseForgeCredentialProvider.kt`, `CurseForgeRepository.kt`, `TokenVault.kt` | AES-GCM encrypted key/proxy storage, `BuildConfig.CURSEFORGE_API_KEY` optional injection, rate-limit & distribution-restriction handling, zero dummy keys. |
| **Modrinth Integration** | **2 — Implemented but partially functional** | `ModrinthFallbackCatalog.kt`, `ModrinthApi.java`, `CommonApi.java`, `ModImportAdapter.kt` | Keyless Modrinth v2 fallback catalog automatically powers Mods Hub when CurseForge credentials are absent or rejected, and `.mrpack` modpacks import via `CommunityScreen` / `ModpackInstaller`. Dedicated standalone Modrinth filter/sort tab in Compose Discover is not separate from the fallback flow. |
| **Mod & Modpack Management** | **1 — Fully implemented and verified** *(device `.jar`/`.mrpack` execution: 6)* | `ModImportAdapter.kt`, `ModpackInstaller.java`, `CommunityScreen.kt`, `InstalledModManager.kt` | `.jar` mod installer handoff, CurseForge `.zip` & Modrinth `.mrpack` import, and `My Mods` inventory scanning (`JarModMetadataParser`). |
| **Runtime Management (OpenJDK)** | **1 — Fully implemented and verified** *(device execution: 6)* | `RuntimeManagementScreen.kt`, `RuntimeDetailScreen.kt`, `MultiRTUtils.java`, `NewJREUtil.java` | Multi-runtime validation (`libjvm.so` + ABI check), OpenJDK 8/17/21/25 download & local `.tar.xz` SAF import. |
| **Account & Token Security** | **1 — Fully implemented and verified** *(hardware Keystore ops: 6)* | `TokenVault.kt`, `AccountRepository.kt`, `MicrosoftAuthScreen.kt`, `AndroidManifest.xml`, `backup_rules.xml`, `data_extraction_rules.xml` | Android Keystore AES-256-GCM vault (`noBackupFilesDir`), offline & Microsoft auth, token redaction in `DiagnosticExporter`, Auto Backup exclusions added in `3.0.1`. |
| **STAR Touch HUD** | **1 — Fully implemented and verified** *(live gameplay touch: 6)* | `StarTouchHudOverlay.java`, `StarJoystickView.kt`, `StarHudButtonView.kt`, `StarHudInputManager.kt` | Native floating joystick, dynamic left zone, custom multi-button overlay, GrabListener camera/GUI awareness. |
| **Custom HUD Layout Persistence** | **1 — Fully implemented and verified** | `StarHudConfig.kt`, `StarHudConfigStore.kt`, `StarHudLayoutSolver.kt`, `HudCustomizationScreen.kt` | Versioned JSON profiles, named layout save/load/reset, snap-to-grid/guide solver, staged gameplay Edit Controller (`mDraftConfig`). |
| **STAR Live Lyrics** | **1 — Fully implemented and verified** *(device playback/listener: 6)* | `StarLiveLyricsOverlay.kt`, `LiveLyricsController.kt`, `LrcParser.kt`, `LrcLibClient.kt`, `StarMusicPlayerService.kt`, `StarMediaNotificationListener.kt`, `LiveLyricsSettingsScreen.kt` | Activity-owned Dynamic Island inside `MainActivity` (no `SYSTEM_ALERT_WINDOW`), LRC/ELRC parser, built-in foreground media service (hardened in `3.0.1`), and notification session listener. |
| **Gamepad Mapper** | **2 — Implemented but partially functional** | `GamepadMapperScreen.kt`, upstream `Gamepad.java`, `CustomControlsActivity.java` | Compose `GamepadMapperScreen` previews controller preset/axis mappings; full interactive live physical-controller remapping relies on upstream engine input handling. |
| **STAR Pulse / Performance Overlay** | **2 — Implemented but partially functional** | `CrossProcessSpoolBridge.kt`, `DiagnosticsManager.kt`, `PerformanceMonitorOverlay.kt`, `PerformanceTelemetryTracker.kt` | `:game` -> `:launcher` JSON session/crash spooling is always active. `PerformanceMonitorOverlay` is **Debug-only** (`BuildConfig.DEBUG`) in the launcher process (`AetherDashboardActivity`), not active in release builds or inside `:game` (`MainActivity`). Cloud telemetry sync is **5 — Planned**. |
| **STAR Doctor (Diagnostics & Repair)** | **2 — Implemented but partially functional** (`Backend`: 1 / `Interactive Repair UI`: 5) | `DiagnosticExporter.kt`, `DiagnosticsManager.kt`, `CrashReportCoordinator.kt`, `SettingsScreen.kt` | Redacted `star-support-*.txt` export, missing native `.so` audit, and `latestlog.txt` sharing work from `SettingsScreen`; guided one-tap repair advisor UI is **Planned**. |
| **Compatibility Passport** | **2 — Implemented but partially functional** | `DeviceCapabilityProbe.kt`, `CompatibilityEvaluator.kt`, `TestedMatrixProvider.kt`, `StatusBadges.kt` | Live hardware probing (`DETECTED`), rule evaluation (`THEORETICAL`), and runtime verification (`VERIFIED_RUNTIME`) work; `TestedMatrixProvider` uses `EmptyTestedMatrixProvider` stub (`TESTED` curated database is **5 — Planned**). |
