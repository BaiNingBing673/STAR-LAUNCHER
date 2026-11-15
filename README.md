<div align="center">

<img src="app_pojavlauncher/src/main/assets/star_logo.png" width="128" height="128" alt="STAR Launcher logo">

# STAR Launcher

**Your Minecraft. Your Universe.**

[![Platform](https://img.shields.io/badge/Android-5.0%2B%20(API%2021%E2%80%9337)-2563EB?style=flat-square)](#supported-android-versions--architectures)
[![License](https://img.shields.io/badge/License-LGPL--3.0-5FA8FF?style=flat-square)](LICENSE)
[![Package](https://img.shields.io/badge/Application%20ID-com.star.launcher-0D111A?style=flat-square)](#application-identity)

*A modern, capability-aware Minecraft: Java Edition launcher for Android — built with Kotlin, Jetpack Compose, and Room on top of the proven [Amethyst-Android](https://github.com/AngelAuraMC/Amethyst-Android) (`PojavLauncher` & `Boardwalk`) native execution engine.*

</div>

---

## Table of Contents

- [Introduction & Product Vision](#introduction--product-vision)
- [Minecraft: Java Edition on Android](#minecraft-java-edition-on-android)
- [Features](#features)
- [Screenshots](#screenshots)
- [Supported Android Versions & Architectures](#supported-android-versions--architectures)
- [Application Identity](#application-identity)
- [Installation & First-Time Setup](#installation--first-time-setup)
- [Minecraft Version & Java Runtime Management](#minecraft-version--java-runtime-management)
- [Renderer Configuration](#renderer-configuration)
- [Mod & Instance Management](#mod--instance-management)
- [STAR Lab](#star-lab)
- [Building from Source](#building-from-source)
- [Project Architecture Overview](#project-architecture-overview)
- [Known Limitations](#known-limitations)
- [Troubleshooting](#troubleshooting)
- [Roadmap](#roadmap)
- [License & Legal Notices](#license--legal-notices)
- [Original Project Attribution & Credits](#original-project-attribution--credits)
- [Documentation Index](#documentation-index)

---

## Introduction & Product Vision

**STAR Launcher** (`STAR`) is an Android launcher for **Minecraft: Java Edition** (`com.star.launcher`, Kotlin package root `com.star.launcher`).

STAR Launcher preserves the battle-tested OpenJDK, LWJGL, and native graphics execution pipeline inherited from **Amethyst-Android** while introducing:

- **Instance-level isolation** so worlds, mods, configs, JVM heap settings, runtimes, and renderers never collide across Minecraft versions.
- **Hardware-probed compatibility reporting** based on actual CPU ABIs, OpenGL ES/Vulkan extensions, and sysfs nodes rather than fragile device-model whitelists.
- **Encrypted credential storage and redacted diagnostics** to protect user accounts and simplify bug triage.

> **Transparency Note**: STAR Launcher is **not** written from scratch. It extends and wraps the upstream `Amethyst-Android` execution engine (`net.kdt.pojavlaunch` + JNI/C/C++ bridges). See [Original Project Attribution & Credits](#original-project-attribution--credits) and [`docs/UPSTREAM_ATTRIBUTION.md`](docs/UPSTREAM_ATTRIBUTION.md).

---

## Minecraft: Java Edition on Android

Desktop Minecraft expects desktop Java SE runtimes, GLFW windowing, desktop OpenGL, and OpenAL. STAR Launcher executes unmodified Java Edition clients on Android across three OS processes:

1. **Java VM Execution (`:game`)**: Loads Android-compiled OpenJDK (`libjvm.so` / `libjli.so`) via `JREUtils` and `VMLauncher.launchJVM()`, paired with custom Android LWJGL `3.3.3`/`3.4.1` bindings and runtime Java agents (`arc_dns_injector`, `MioLibPatcher`).
2. **Graphics Translation (`egl_bridge.c` / `linkerhook`)**: Dispatches rendering to MobileGlues, Krypton Wrapper (`NG-GL4ES`), Kopper Zink (Vulkan), OSMesa Zink, or LTW, with `androidnsbypass` injecting bundled Turnip (`libvulkan_freedreno.so`) on `arm64-v8a` Adreno GPUs.
3. **Input & Windowing**: Routes touch controls, pointer capture (`API >= 26`), SDL2/SDL3 controllers, virtual mouse, and TouchController proxy events through `input_bridge_v3.c` and `CallbackBridge`.
4. **Swing Mod Installers (`:gui_installer`)**: Runs standalone `.jar` installers (such as Forge or OptiFine) via Caciocavallo and `libpojavexec_awt.so`.

See [`docs/STAR_LAUNCHER_OVERVIEW.md`](docs/STAR_LAUNCHER_OVERVIEW.md) for more detail.

---

## Features

All capabilities below are verified directly against the codebase (full breakdown in [`docs/FEATURE_STATUS.md`](docs/FEATURE_STATUS.md)).

### Implemented

- **6-Tab Material 3 Compose Hub (`AetherDashboardActivity` internal Activity)**: `Home`, `Library`, `Versions`, `Mods`, `Community`, and `Settings` with adaptive `NavigationBar`/`NavigationRail`, `UiPerformanceProfileManager` tier scaling, and `SavedStateHandle` navigation stack persistence.
- **Isolated Instance Management (`InstanceManager`, `LibraryScreen`, `InstanceDetailScreen`)**: Per-instance `.minecraft` directories (under the preserved internal directory `aether_instances/<id>/.minecraft`), per-instance RAM allocation, custom JVM arguments, runtime & renderer bindings, featured instance pinning, and legacy `.minecraft` profile import.
- **Version Catalog & Installer (`VersionCatalogService`, `VersionInstallCoordinator`, `ModLoaderInstaller`)**: Syncs Mojang `version_manifest_v2.json` (releases, snapshots, historical betas/alphas), installs Fabric, Quilt, and legacy Forge profiles headlessly (and hands off processor-based Forge 1.13+ / NeoForge installers to `:gui_installer`), tracks byte/file progress, validates SHA-1 hashes, and cleans interrupted `.aetherpart` downloads.
- **In-App Mods Hub (`ModsScreen`, `ModDetailScreen`, `ModsController`, `CurseForgeRepository`, `ModrinthFallbackCatalog`)**: Browse, filter, and install mods directly into isolated instance `mods/` directories with CurseForge API integration, automatic keyless Modrinth v2 fallback catalog support, dependency resolution (`ModDependencyResolver`), compatibility checks (`ModCompatibilityEngine`), in-app mod-loader installation, and local `.jar` metadata inspection (`JarModMetadataParser`, `InstalledModManager`).
- **OpenJDK Runtime Manager (`RuntimeManagementScreen`, `MultiRTUtils`, `NewJREUtil`)**: Validates `libjvm.so` and ABI match, downloads External OpenJDK **8, 17, 21, and 25** builds (where device ABI supports them), or imports local `.tar.xz` archives.
- **Renderer Filtering, Adaptive Graphics & MobileGlues Tuning (`EnginePreferencesScreen`, `PojavRendererFacade`, `AdaptiveGraphicsManager`)**: Probes hardware support before listing renderers, tunes settings by device capability tier, and exposes MobileGlues controls (GLSL cache size, ANGLE backend toggle, AMD FSR 1 upscaling, experimental GL extension flags).
- **Accounts & Hardware-Backed Encryption (`AccountRepository`, `TokenVault`, `MicrosoftAuthScreen`)**: Microsoft OAuth2 + Xbox Live WebView login, offline local profiles (3–16 chars), and AES-GCM secret mirroring via Android Keystore (`aether_token_vault_v1`).
- **STAR Touch HUD & Controls (`StarTouchHudOverlay`, `HudCustomizationScreen`, `CustomControlsActivity`, `GamepadMapperScreen`)**: Customizable in-game touch HUD (`StarJoystickView`, `StarHudButtonView`, `StarHudLayoutSolver`), classic custom controls editor, `.json` control import/export, gamepad button remapper, and scoped file exposure via `FolderProvider` (`com.star.launcher.scoped.gamefolder`).
- **STAR Live Lyrics & Audio Overlay (`StarLiveLyricsOverlay`, `LiveLyricsSettingsScreen`, `StarMusicPlayerService`, `LrcLibClient`)**: In-game synchronized lyrics overlay (`KineticLyricsView`, `MiniVisualizerView`), external media session tracking (`StarMediaNotificationListener`), LrcLib provider lookup, and built-in music playback service.
- **Diagnostics, Performance Overlay & Cross-Process Spooling (`DiagnosticExporter`, `CrashReportCoordinator`, `PerformanceMonitorOverlay`, `StartupProfiler`, `CrossProcessSpoolBridge`)**: Redacted `star-support-*.txt` export, `SensitiveLogRedactor`, live frame/memory performance overlay, and atomic `:game` -> `:launcher` JSON exit spooling.

### Partially Implemented / Planned

- **Compatibility Passport**: Capability probing (`DETECTED`), rule evaluation (`THEORETICAL`), and real-run verification (`VERIFIED_RUNTIME`) are **implemented** in backend + `Settings` / `InstanceDetail` badges; curated `TESTED` matrix data (`EmptyTestedMatrixProvider` stub) and a dedicated full-screen Passport browser are **planned**.
- **STAR Doctor**: Automated log redaction, native library checks (`verifyEngineLibrariesPresent`), crash report coordination (`CrashReportCoordinator`), and support report export (`SettingsScreen`) are **implemented**; interactive one-tap remediation cards are **planned**.
- **STAR Pulse**: Local session/exit recording (`GameSessions` + `CrossProcessSpoolBridge`) is **implemented**; `PerformanceMonitorOverlay` currently runs in **Debug builds only** inside the launcher process (`AetherDashboardActivity`), while production/in-game overlay metrics and opt-in telemetry are **planned**.
- **Gamepad Mapper**: Compose `GamepadMapperScreen` provides controller preset inspection (`android_gamepad_remapper` / `Gamepad` handle physical input in the engine); interactive Compose physical-button capture is **planned**.

---

## Screenshots

No verified in-app device screenshots are committed in this repository yet. Below are the target screen captures awaiting hardware testing:

| Screen | Status |
| --- | --- |
| **Home Dashboard** (Featured instance, Play CTA, runtime health) | `[Placeholder — Awaiting On-Device Capture]` |
| **Game Library & Instance Details** (Isolated instances & mod toggles) | `[Placeholder — Awaiting On-Device Capture]` |
| **Versions Catalog & Download Progress** (Mojang / Fabric / Quilt) | `[Placeholder — Awaiting On-Device Capture]` |
| **Settings, Runtimes & STAR Lab Compatibility Matrix** | `[Placeholder — Awaiting On-Device Capture]` |
| **In-Game Surface & Custom On-Screen Controls** | `[Placeholder — Awaiting On-Device Capture]` |

---

## Supported Android Versions & Architectures

Verified from [`app_pojavlauncher/build.gradle`](app_pojavlauncher/build.gradle), [`AndroidManifest.xml`](app_pojavlauncher/src/main/AndroidManifest.xml), and [`Application.mk`](app_pojavlauncher/src/main/jni/Application.mk):

| Property | Configuration | Notes |
| --- | --- | --- |
| **Minimum Android Version** | **Android 5.0 Lollipop** (`minSdkVersion 21`) | API `< 29` uses legacy external storage permissions; API `>= 29` uses scoped app storage |
| **Target / Compile SDK** | `targetSdkVersion 34` (Android 14) / `compileSdk = 37` | `androidnsbypass` compiles against API `36.1` |
| **Graphics API** | OpenGL ES `2.0+` (`<uses-feature glEsVersion="0x00020000"/>`) | OpenGL ES `3.0+` required for MobileGlues, LTW, and Krypton Wrapper; Vulkan required for Zink renderers |
| **Supported CPU ABIs** | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` | 32-bit `x86` cannot download OpenJDK 21/25; Turnip (`libvulkan_freedreno.so`) targets `arm64-v8a` only |

---

## Application Identity

| Identifier | Release Value | Debug Value |
| --- | --- | --- |
| **Application ID** | `com.star.launcher` | `com.star.launcher.debug` |
| **Kotlin Package Root** | `com.star.launcher` | `com.star.launcher` |
| **Native / Upstream Namespace** | `net.kdt.pojavlaunch` | `net.kdt.pojavlaunch` |
| **Scoped Game Provider** | `com.star.launcher.scoped.gamefolder` | `com.star.launcher.scoped.gamefolder.debug` |
| **Scoped Controls Provider** | `com.star.launcher.scoped.controlfolder` | `com.star.launcher.scoped.controlfolder.debug` |

> **Upgrading from older `dev.aetherlauncher.app` development builds**: Android cannot upgrade in place across different `applicationId`s. Follow [`docs/MIGRATION_GUIDE.md`](docs/MIGRATION_GUIDE.md) to manually back up your worlds and instances before installing `com.star.launcher`. Database filename (`aether_launcher.db`) and instance directories (`aether_instances/`) remain compatible.

---

## Installation & First-Time Setup

Download the latest universal release APK (`STAR-Launcher-v3.0.1.apk`, `versionCode 10000002`) from [GitHub Releases](https://github.com/BaiNingBing673/STAR-LAUNCHER/releases), verify its `.sha256` checksum and signing certificate before installing on your Android device (**Android 5.0+ / API 21+**), or build from source (see [`docs/BUILDING.md`](docs/BUILDING.md)).

```bash
sha256sum -c STAR-Launcher-v3.0.1.apk.sha256
apksigner verify --print-certs --verbose STAR-Launcher-v3.0.1.apk
```

> **Updating from `v3.0.0` (`versionCode 10000001`)**: `v3.0.0` was mistakenly signed with `debug.keystore` due to an upstream fallback fixed in `3.0.1`. Back up your worlds (`saves/`), mods, and configs and perform a clean reinstall when moving from `v3.0.0` to a production-signed `3.0.1+` build.

### First-Time Setup Guide

1. **Storage & Initialization**: On first launch, `TestStorageActivity` (the `:launcher` process entry Activity) checks storage access, routes to `MissingStorageActivity` when required on older Android versions, extracts bundled engine components (`AsyncAssetManager`), and opens `AetherDashboardActivity` (or legacy `LauncherActivity` when selected).
2. **Add an Account (`Settings -> Accounts & Authentication`)**:
   - Tap **Microsoft Login** to authenticate a licensed Minecraft: Java Edition account via Microsoft OAuth2 / Xbox Live (`MicrosoftAuthScreen`), **or**
   - Enter an offline username (3–16 characters) for local offline testing.
3. **Provision a Java Runtime (`Settings -> Java Runtimes` or `Runtime Management`)**:
   - Download OpenJDK **8**, **17**, **21**, or **25** appropriate for your device ABI and target Minecraft version, or import a `.tar.xz` JRE package.
   - *(General mapping: MC `<= 1.16.5` -> Java 8; MC `1.17–1.20.4` -> Java 17; MC `1.20.5+` -> Java 21+.)*
4. **Install a Minecraft Version (`Versions`)**:
   - Sync the Mojang catalog and install a release or snapshot, or install a **Fabric** / **Quilt** / **Forge** / **NeoForge** loader profile (via `Mods` or `Versions`).
5. **Create an Instance (`Library -> New`)**:
   - Name your instance, assign its installed version, choose RAM allocation and renderer, then tap **Play**.

---

## Minecraft Version & Java Runtime Management

- **Version Catalog (`VersionsScreen`, `VersionCatalogService`)**: Downloads `version_manifest_v2.json`, filters by **All / Installed / Not Installed / Releases / Snapshots / Modded**, and sorts by **Newest / Oldest / Alphabetical / Recently Played**.
- **Safe Staging**: Version downloads use single-flight locking (`VersionInstallCoordinator`), SHA-1 verification, cancel/retry recovery, and automatic `.aetherpart` staging cleanup after process interruption.
- **Runtime Validation (`RuntimeManagementScreen`, `MultiRTUtils`)**: Checks each runtime's `libjvm.so`, CPU architecture match, and Java major version before launch, preventing launch failures caused by mismatched JRE binaries.

---

## Renderer Configuration

`Tools.getCompatibleRenderers()` and `PojavRendererFacade` filter available renderers at runtime based on device OpenGL ES version, Vulkan capability, and bundled `.so` binaries:

| Renderer ID (`headings_array.xml`) | Display Name | Backend & Requirements |
| --- | --- | --- |
| `opengles_mobileglues` | SFPEW / MobileGlues (OpenGL ES) | Default high-speed GL wrapper on OpenGL ES 3.0+; configurable in `Engine Preferences` (GLSL cache, ANGLE mode, FSR 1 upscaling, experimental GL extensions) |
| `opengles2` | Krypton Wrapper | `NG-GL4ES` (`libng_gl4es.so` + OpenGL ES 3.0+) |
| `opengles3_desktopgl_zink_kopper` | Kopper Zink (Vulkan) | Mesa Kopper-Zink over Vulkan (`arm64-v8a` Adreno benefits from bundled Turnip injection; non-Adreno depends on vendor Vulkan drivers) |
| `vulkan_zink` | Zink (Vulkan) | OSMesa Vulkan-Zink (requires Vulkan + `libOSMesa.so`) |
| `opengles3_ltw` | LTW (OpenGL ES 3) | Optional dependency (`libltw.so` + OpenGL ES 3.0+; hidden automatically if `libltw.so` is absent) |

---

## Mod & Instance Management

- **Isolated Instance Folders**: Created under `<game_home>/aether_instances/<instanceId>/.minecraft/` so mod sets (`mods/`), configs, shader packs, resource packs, and worlds (`saves/`) stay independent per profile.
- **Per-Instance Mod Toggling (`InstanceContentManager`)**: Inspects `.jar` and `.jar.disabled` mods inside each instance and toggles mods cleanly without deleting files.
- **Mod Installers & Modpacks (`ModImportAdapter`)**:
  - **Install `.jar`**: Opens an Android file picker and runs `.jar` installers (Forge, NeoForge, OptiFine, etc.) inside the isolated `:gui_installer` AWT process (`JavaGUILauncherActivity`).
  - **Modpack**: Imports local `.zip` or `.mrpack` modpack archives picked from device storage. *(Note: CurseForge API calls require providing `CURSEFORGE_API_KEY` at build time; otherwise only local `.mrpack`/`.zip` import without CurseForge API resolution is active.)*

---

## STAR Lab

**STAR Lab** groups STAR Launcher's hardware capability, diagnostic, and session-tracking subsystems under one architecture ([`docs/STAR_LAB.md`](docs/STAR_LAB.md)):

1. **Compatibility Passport** *(Backend & Badges Implemented; Matrix Stub)*:
   - `AndroidDeviceCapabilityProbe` reads ABI list, 64-bit status, RAM, EGL/GLES version, GPU vendor/renderer, Vulkan support/version, and Adreno KGSL nodes.
   - `CompatibilityEvaluator` scores targets separately across **`DETECTED`**, **`THEORETICAL`**, **`TESTED`** (currently `EmptyTestedMatrixProvider` stub), and **`VERIFIED_RUNTIME`** tiers.
2. **STAR Doctor** *(Backend & Export Implemented; UI Advisor Planned)*:
   - `SensitiveLogRedactor` scrubs JWTs, Bearer headers, and `--accessToken`/`--uuid` tokens.
   - `DiagnosticExporter` exports `star-support-*.txt` support reports and `PojavNativeEngineAdapter` checks core native `.so` presence (`libpojavexec.so`, `libpojavexec_awt.so`, `libexithook.so`).
3. **STAR Pulse** *(Local Cross-Process Spool Implemented; Telemetry HUD Planned)*:
   - `CrossProcessSpoolBridge` safely passes `:game` session exit status, exit code, and GL initialization flags back to `:launcher`'s Room DB (`aether_launcher.db`) without risking cross-process SQLite corruption.

---

## Building from Source

Full verified guide: [`docs/BUILDING.md`](docs/BUILDING.md).

### Development Environment Requirements

- **Gradle**: `9.6.1` wrapper (`./gradlew` / `.\gradlew.bat`), **AGP**: `9.3.1`
- **JDK 21** (`JAVA_HOME`) for Gradle, Kotlin, Compose, and unit tests
- **JDK 8** installed on the host for Gradle toolchain auto-detection (`:MioLibPatcher` targets Java 8)
- **Android SDK**: Platforms `android-37` (`app_pojavlauncher`), `android-36.1` (`androidnsbypass`), `NDK 27.3.13750724`, `CMake 3.22.1`
- **Spaceless Directory Path**: Clone/build in a path without spaces (e.g. `D:\STARLauncher` on Windows) to prevent `ndk-build` path failures (`android/ndk#1400`)

### Build & Test Commands

```bash
git clone --recursive https://github.com/BaiNingBing673/STAR-LAUNCHER.git STAR-Launcher
cd STAR-Launcher

# Ensure STAR's custom MioLibPatcher transformers are applied on fresh clones
if [ ! -f MioLibPatcher/src/main/java/com/mio/libpatcher/transformer/VulkanSurfaceTransformer.java ]; then
  git -C MioLibPatcher apply ../patches/miolibpatcher-star.patch
fi

# Run MioLibPatcher + Android JVM/Robolectric tests
./gradlew :MioLibPatcher:test :app_pojavlauncher:testDebugUnitTest --console=plain

# Assemble debug APK -> app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk
./gradlew :app_pojavlauncher:assembleDebug --console=plain
```

*(Note: Production release builds via `:app_pojavlauncher:assembleRelease` require `STAR_KEYSTORE_FILE`, `STAR_KEYSTORE_PASSWORD`, `STAR_KEY_ALIAS`, and `STAR_KEY_PASSWORD`; Gradle fails cleanly rather than falling back to `debug.keystore`. Pre-built JRE 8 binaries under `app_pojavlauncher/src/main/assets/components/jre/` are not committed to Git—you can build without them and download OpenJDK 8/17/21/25 in-app via Runtime Management.)*

---

## Project Architecture Overview

- **Multi-Module Gradle Build**: `:app_pojavlauncher` (retained internal Android application module name, namespace `net.kdt.pojavlaunch`, permanent `applicationId` `com.star.launcher`), `:androidnsbypass` (CMake linker bypass + ARM64 BTI trampoline), `:MioLibPatcher` (Javassist bytecode mod compatibility agent), `:arc_dns_injector`, `:forge_installer`, and `:jre_lwjgl3glfw` (`3.3.3` & `3.4.1`).
- **Android Process Model** (`PojavApplication` runs as the `Application` class in each process):
  - **`:launcher`** (`<application android:process=":launcher">`): Default process hosting `TestStorageActivity`, `AetherDashboardActivity`, `LauncherActivity`, `MissingStorageActivity`, `ImportControlActivity`, `FatalErrorActivity`, `ShowErrorActivity`, `ExitActivity`, `CustomControlsActivity`, `FolderProvider`, and `ProgressService`. Flushes Room WAL checkpoints and terminates cleanly before game launch.
  - **`:game`**: Isolated gameplay process hosting `MainActivity` and foreground `GameService`, running OpenJDK, LWJGL, and native renderers; emits atomic JSON telemetry spools (`aether_spool/`) ingested by `:launcher` on next start.
  - **`:gui_installer`**: Isolated process hosting `JavaGUILauncherActivity` for Swing-based mod/installer JAR execution.
- **Persistence**: `AetherDatabase` (`aether_launcher.db`, 10 Room entities, WAL mode) + hardware-backed Android Keystore `TokenVault` (`aether_token_vault_v1`). Internal class/file identifiers prefixed with `Pojav`/`Aether` are inherited or compatibility-pinned symbols rather than user-facing branding.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), [`docs/ENGINE_DEPENDENCY_MAP.md`](docs/ENGINE_DEPENDENCY_MAP.md), and [`docs/DATABASE_SCHEMA.md`](docs/DATABASE_SCHEMA.md).

---

## Known Limitations

- **32-bit Virtual Address Ceiling (`armeabi-v7a` / `x86`)**: ~3 GB user-space limit restricts Java heap (`-Xmx`) for modern heavily modded packs; `x86` cannot download OpenJDK 21/25.
- **GPU Driver Diversity**: Bundled Turnip Vulkan driver (`libvulkan_freedreno.so`) targets `arm64-v8a` Adreno GPUs only; Mali, Xclipse, and PowerVR GPUs rely on OEM Vulkan/GLES drivers with varying extension support.
- **Minecraft Launcher UI Scope**: Some advanced mod-loader workflows (Forge/OptiFine Swing installers) execute inside `:gui_installer` via local `.jar` files rather than an integrated Compose mod browser.
- **Clean Build Task Order**: Directly running `./gradlew clean` with existing CMake/ndk-build prefab caches can fail between `:androidnsbypass:clean` and `:app_pojavlauncher:externalNativeBuildCleanDebug`; deleting `build/` and `.cxx/` folders cleanly avoids this (see [`docs/BUILDING.md`](docs/BUILDING.md)).

---

## Troubleshooting

- **Missing Java Runtime at Launch**: Open **Settings -> Runtime Management**, download a compatible OpenJDK version (8/17/21/25) or import a `.tar.xz` JRE, and verify the status indicator is valid (`libjvm.so` + ABI match).
- **App Crash or Mod Issue**: Export a scrubbed report from **Settings -> Hardware & Compatibility -> Export Diagnostics** (`star-support-*.txt`) or share `latestlog.txt` (located under `/storage/emulated/0/Android/data/com.star.launcher/files/` on Android 10+).
- **Windows `ndk-build` Fails Immediately**: Ensure your project directory path contains zero spaces.
- **CurseForge API Disabled Warning**: Setting `CURSEFORGE_API_KEY` or `app_pojavlauncher/curseforge_key.txt` is optional and only needed if building with CurseForge API access enabled.

---

## Roadmap

Based on currently incomplete items verified in [`docs/FEATURE_STATUS.md`](docs/FEATURE_STATUS.md) and [`CHANGELOG.md`](CHANGELOG.md):

- Populate a curated read-only `TESTED` **Compatibility Passport** matrix (`TestedMatrixProvider`) covering verified SoC/GPU/API/renderer combinations.
- Build dedicated **STAR Lab** UI screens (interactive Compatibility Passport details, automated **STAR Doctor** crash-pattern advisor, and release-enabled **STAR Pulse** performance metrics).
- Expand native Compose version/modpack workflows (dedicated Modrinth search/filter tab alongside `ModrinthFallbackCatalog`, interactive Forge/OptiFine progress feedback).
- Run physical Android UI/launch regression verification and capture official hardware screenshots.

---

## License & Legal Notices

- Root license: **GNU Lesser General Public License v3.0 (LGPLv3)** ([`LICENSE`](LICENSE)).
- Upstream license audit caveat: `app_pojavlauncher/src/main/jni/bigcoreaffinity.c` (linked into `libpojavexec.so`) declares **GNU GPLv3**, and a few bundled legacy binaries (`SimpleFPEWrapper-release.aar`, `lwjgl-lwjglx.jar`, `ExagearApacheCommons.jar`, `libtwitchsdk.so`) currently lack explicit in-archive license files (see [`docs/UPSTREAM_ATTRIBUTION.md`](docs/UPSTREAM_ATTRIBUTION.md) and [`docs/LICENSE_AUDIT.md`](docs/LICENSE_AUDIT.md) for details before distributing binaries).
- Privacy & Data Removal: See [`GPLAY_PRIVACY_POLICY`](GPLAY_PRIVACY_POLICY), [`PRIVACY_POLICY_ru`](PRIVACY_POLICY_ru), and [`dataremoval.md`](dataremoval.md).

---

## Original Project Attribution & Credits

STAR Launcher stands on the work of the Android Minecraft Java launcher community and open-source graphics contributors:

- **[Amethyst-Android](https://github.com/AngelAuraMC/Amethyst-Android)** (`AngelAuraMC`, `v3_openjdk`) — Core Android execution engine, JNI bridges, multi-runtime system, and renderer plumbing.
- **[PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher)** (`Tran Khanh Duy` / `ArtDeell` & PojavLauncherTeam) — Foundational Android launcher architecture and upstream copyright notices (`app_pojavlauncher/src/main/assets/about_*.txt`).
- **[Boardwalk](https://github.com/zhuowei/Boardwalk)** (`zhuowei`) — Original Android JVM Minecraft launcher concept.
- **[ZalithLauncher2](https://github.com/ZalithLauncher/ZalithLauncher2)** — Architectural and UX reference only (`GPLv3`; zero code copied).
- **Core Native & Java Ecosystem**: MobileGlues (`LGPL-2.1`), NG-GL4ES / Krypton Wrapper (`MIT`), Mesa Kopper-Zink & Turnip (`MIT`/`Apache-2.0`), Google ANGLE (`BSD-3-Clause`), SDL2/SDL3 (`zlib`), OpenAL-Soft & Oboe (`LGPL-2.0`/`Apache-2.0`), LWJGL 3 (`BSD-3-Clause`), OpenJDK Multiarch & Caciocavallo (`GPLv2+CE`), ByteHook (`MIT`), `androidnsbypass` (`MIT`/`BSD-2-Clause`), `MioLibPatcher`, TouchController, and MCHeads (`mc-heads.net`).

Full attribution breakdown: [`docs/UPSTREAM_ATTRIBUTION.md`](docs/UPSTREAM_ATTRIBUTION.md).

---

## Documentation Index

| Document | Topic |
| --- | --- |
| [`docs/STAR_LAUNCHER_OVERVIEW.md`](docs/STAR_LAUNCHER_OVERVIEW.md) | Vision, Android execution pipeline, UI hub overview, storage layout |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 3-process Android architecture, Kotlin/JNI boundaries, Room & Keystore architecture |
| [`docs/ENGINE_DEPENDENCY_MAP.md`](docs/ENGINE_DEPENDENCY_MAP.md) | Engine bridge dependency map and cross-process launch/telemetry flow |
| [`docs/BUILDING.md`](docs/BUILDING.md) | Verified toolchain, JDK 8+21 setup, NDK/CMake config, Gradle commands |
| [`docs/FEATURE_STATUS.md`](docs/FEATURE_STATUS.md) | Implemented vs Partially Implemented vs Planned verification matrix |
| [`docs/STAR_LAB.md`](docs/STAR_LAB.md) | Compatibility Passport, STAR Doctor diagnostics, STAR Pulse spooling |
| [`docs/UPSTREAM_ATTRIBUTION.md`](docs/UPSTREAM_ATTRIBUTION.md) | Upstream lineage, third-party licenses, and compliance review items |
| [`docs/MIGRATION_GUIDE.md`](docs/MIGRATION_GUIDE.md) | Safe manual backup & migration from `dev.aetherlauncher.app` to `com.star.launcher` |
| [`docs/DATABASE_SCHEMA.md`](docs/DATABASE_SCHEMA.md) | Room SQLite schema reference (`aether_launcher.db`) |
| [`docs/COMPATIBILITY_STRATEGY.md`](docs/COMPATIBILITY_STRATEGY.md) | Hardware capability detection rules & 4-tier verdict design |
| [`docs/LICENSE_AUDIT.md`](docs/LICENSE_AUDIT.md) | Full binary, submodule, and Maven dependency license audit |
| [`docs/STAR_LEGACY_REFERENCE_AUDIT.md`](docs/STAR_LEGACY_REFERENCE_AUDIT.md) | Repository-wide legacy reference audit, classification, and migration plan |
| [`docs/STAR_BRANDING_CLEANUP_REPORT.md`](docs/STAR_BRANDING_CLEANUP_REPORT.md) | STAR Launcher branding cleanup summary, verification, and build/test results |
