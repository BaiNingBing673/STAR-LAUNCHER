# STAR Launcher — Feature Implementation Status

> **Policy**: Every entry below has been verified directly against the current repository source code (`app_pojavlauncher/src/main/kotlin/com/star/launcher/...`, `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/...`, and `app_pojavlauncher/src/main/jni/`).  
> Features are explicitly separated into **IMPLEMENTED**, **PARTIALLY IMPLEMENTED**, and **PLANNED**.

---

## 1. Core Launcher, Instances & Versions

| Feature | Status | Verified Implementation Evidence | Limitations / Notes |
| --- | --- | --- | --- |
| **Unified 5-Tab Compose UI (`Home`, `Library`, `Versions`, `Community`, `Settings`)** | **IMPLEMENTED** | `AetherDashboardActivity.kt`, `AetherNavRoutes.kt`, `HomeScreen.kt`, `LibraryScreen.kt`, `VersionsScreen.kt`, `CommunityScreen.kt`, `SettingsScreen.kt` (internal Compose UI symbols) | Uses responsive `NavigationBar` / `NavigationRail` and `SavedStateHandle` back-stack persistence |
| **Isolated Multi-Instance Profiles** | **IMPLEMENTED** | `InstanceManager.kt`, `AetherStorageManager.kt`, `GameInstanceDao` | Each instance gets internal storage `<game_home>/aether_instances/<id>/.minecraft` (preserved directory name) with dedicated RAM, JVM args, runtime, and renderer IDs |
| **Legacy `.minecraft` Profile Import** | **IMPLEMENTED** | `LibraryScreen.kt` ("Import Legacy"), `InstanceManager.kt` | Reads existing `launcher_profiles.json` entries cleanly |
| **Per-Instance Mod Inspection & Toggle** | **IMPLEMENTED** | `InstanceContentManager.kt`, `InstanceDetailScreen.kt` | Lists `.jar` / `.jar.disabled` mods, toggles without deletion, counts resource packs, shader packs, and saves (`level.dat`) |
| **Mojang Version Catalog (`version_manifest_v2.json`)** | **IMPLEMENTED** | `VersionCatalogService.kt`, `VersionsScreen.kt`, `VersionInstallScreen.kt` | Syncs releases, snapshots, and `old_beta`/`old_alpha`; supports search, filter, sort, cancel, and `.aetherpart` crash cleanup |
| **Fabric & Quilt Profile Installation (Compose UI)** | **IMPLEMENTED** | `VersionCatalogService.kt` (`installFabricOrQuiltProfile`), `FabriclikeUtils.java` | Fetches Fabric/Quilt loader versions and installs version JSON profiles |
| **Local `.jar` Mod Installer & `.zip`/`.mrpack` Modpack Import** | **IMPLEMENTED** | `ModImportAdapter.kt`, `JavaGUILauncherActivity.java`, `CommonApi.java` | SAF picker (`OpenDocumentWithExtension`) launches `.jar` Swing installers or imports `.zip`/`.mrpack` packs |
| **Forge, NeoForge, OptiFine, BTA & LWJGL3ify Installers** | **IMPLEMENTED** (Engine / `.jar`) / **PARTIALLY IMPLEMENTED** (Native Compose UI) | `modloaders/*DownloadTask.java`, `JavaGUILauncherActivity.java`, `:forge_installer` | Full Java/Swing/headless engine installers exist in `net.kdt.pojavlaunch.modloaders` and run via `Install .jar`; dedicated Compose picker dialogs in `VersionsScreen` currently target Vanilla + Fabric/Quilt |
| **Built-In Online Mod / Modpack Store Search (Modrinth / CurseForge in Compose UI)** | **PLANNED** | `ModrinthApi.java` / `CurseforgeApi.java` exist in legacy Java layer (`CURSEFORGE_API_KEY` defaults to `"DUMMY"` if unset) | Compose UI currently supports local `.mrpack`/`.zip` import rather than an interactive online store browser |

---

## 2. Java Runtimes, Graphics Renderers & Launch Pipeline

| Feature | Status | Verified Implementation Evidence | Limitations / Notes |
| --- | --- | --- | --- |
| **OpenJDK Multi-Runtime Management (Java 8, 17, 21, 25)** | **IMPLEMENTED** | `RuntimeManagementScreen.kt`, `RuntimeDetailScreen.kt`, `PojavRuntimeFacade`, `MultiRTUtils.java`, `NewJREUtil.java` | Validates `libjvm.so` & ABI match; downloads External JRE builds or imports `.tar.xz`; 32-bit `x86` cannot provision JRE 21/25 |
| **Renderer Discovery & Selection** | **IMPLEMENTED** | `Tools.getCompatibleRenderers`, `headings_array.xml`, `PojavRendererFacade`, `EnginePreferencesScreen.kt` | Filters by GLES major version, Vulkan support, and `.so` binary presence (`opengles2` Krypton Wrapper, `opengles_mobileglues`, `opengles3_desktopgl_zink_kopper`, `vulkan_zink`, `opengles3_ltw`) |
| **MobileGlues Renderer Tuning** | **IMPLEMENTED** | `EnginePreferencesScreen.kt`, `LauncherPreferences.java`, `strings.xml` | Configures GLSL cache size, ANGLE toggle, error filtering, AMD FSR 1 upscaling, and experimental GL extensions |
| **Adreno Turnip Injection & Linker Namespace Bypass** | **IMPLEMENTED** | `libvulkan_freedreno.so` (`arm64-v8a`), `liblinkerhook.so`, `:androidnsbypass` | Applies to `arm64-v8a` devices; Mali/Xclipse/PowerVR rely on system Vulkan/GLES drivers |
| **Three-Process Isolation (`:launcher`, `:game`, `:gui_installer`)** | **IMPLEMENTED** | `AndroidManifest.xml`, `PojavApplication.java`, `ContextAwareDoneListener.java`, `PojavLaunchBridge.kt`, `MainActivity.java`, `GameService.java`, `JavaGUILauncherActivity.java` | `PojavApplication` runs per process; `:launcher` flushes Room WAL checkpoints and exits before `:game` (`MainActivity`, `GameService`) initializes OpenJDK/LWJGL; `:gui_installer` isolates `JavaGUILauncherActivity` |

---

## 3. Accounts, Security & Storage

| Feature | Status | Verified Implementation Evidence | Limitations / Notes |
| --- | --- | --- | --- |
| **Microsoft OAuth2 + Xbox Live Authentication** | **IMPLEMENTED** | `MicrosoftAuthScreen.kt`, `AuthAdapter.kt`, `MicrosoftBackgroundLogin.java` | Embedded WebView OAuth2 flow + 5-step Xbox/Minecraft authorization progress |
| **Local Offline Accounts** | **IMPLEMENTED** | `AccountRepository.kt`, `SettingsScreen.kt` | Supports 3–16 character local profiles |
| **Android Keystore AES-GCM Token Vault** | **IMPLEMENTED** | `TokenVault.kt`, `AccountRepository.kt` | Encrypts sensitive `accessToken` and `msaRefreshToken` under Keystore alias `aether_token_vault_v1` |
| **Scoped Storage & `DocumentsProvider`** | **IMPLEMENTED** | `FolderProvider.java`, `AndroidManifest.xml`, `TestStorageActivity.java` | Exposes scoped files under `com.star.launcher.scoped.gamefolder[.debug]` (`API >= 29`) or legacy storage (`API < 29`) |
| **Custom On-Screen Controls & Gamepad Remapper** | **IMPLEMENTED** | `CustomControlsActivity.java`, `GamepadMapperScreen.kt`, `ImportControlActivity.java` | Interactive button/joystick layout editor, JSON import/export, TouchController proxy, SDL/DirectGamepad remapping |

---

## 4. STAR Lab (`Compatibility Passport`, `STAR Doctor`, `STAR Pulse`)

| Module | Status | What Exists in Code Today | What Is Not Yet Built |
| --- | --- | --- | --- |
| **Compatibility Passport** | **PARTIALLY IMPLEMENTED** (Backend & Badges Ready) | `AndroidDeviceCapabilityProbe.kt` (ABI, 64-bit, RAM, EGL/GLES, Vulkan, Adreno KGSL), `CompatibilityEvaluator.kt` (4 strict tiers: `DETECTED`, `THEORETICAL`, `TESTED`, `VERIFIED_RUNTIME`), `CompatibilityTierBadge` in `SettingsScreen.kt` & `InstanceDetailScreen.kt` | `EmptyTestedMatrixProvider` is currently a stub with no bundled QA/community `TESTED` matrix data; no dedicated full-screen Passport explorer UI yet |
| **STAR Doctor** | **PARTIALLY IMPLEMENTED** (Diagnostics Backend & Exporter Ready) | `DiagnosticsManager.kt`, `SensitiveLogRedactor.kt` (strips JWTs, Bearer tokens, `--accessToken`, `--uuid`), `DiagnosticExporter.kt` (`star-support-*.txt` redacted export), `PojavNativeEngineAdapter.verifyEngineLibrariesPresent()`, `MultiRTUtils.inspectRuntime()` | No dedicated interactive "STAR Doctor" automated crash-cause wizard / one-tap fix advisor screen yet |
| **STAR Pulse** | **PARTIALLY IMPLEMENTED** (Local Session Spool Only) / **PLANNED** (Telemetry & Performance HUD) | `CrossProcessSpoolBridge.kt`, `GameSessionEntity`, `DiagnosticLogEntity` capture local launch/exit/crash & GL init records safely across `:game` -> `:launcher` | Live in-game performance overlay, frame-time metrics dashboard, and opt-in anonymized compatibility telemetry sync are planned |
