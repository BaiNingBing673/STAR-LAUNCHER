# STAR Launcher Overview

> **Product**: **STAR Launcher** (`STAR`)  
> **Android Application ID**: `com.star.launcher` (`com.star.launcher.debug` for debug builds)  
> **Kotlin Package Root**: `com.star.launcher`  
> **Native Execution Engine Namespace**: `net.kdt.pojavlaunch` (derived from `Amethyst-Android` / `PojavLauncher` / `Boardwalk`)

---

## 1. Product Vision

STAR Launcher brings **Minecraft: Java Edition** to Android devices behind a modern, capability-aware Jetpack Compose interface while building on the proven native Android OpenJDK and LWJGL execution pipeline of **Amethyst-Android**.

Rather than promising universal compatibility across every Android GPU driver and ABI, STAR Launcher aims to:

1. **Isolate instances safely** so different Minecraft versions, loaders, mods, and resource packs never corrupt a shared `.minecraft` folder.
2. **Surface honest hardware capability telemetry** (`DETECTED`, `THEORETICAL`, `TESTED`, and `VERIFIED_RUNTIME`) based on actual EGL/GLES/Vulkan/ABI probes rather than device-name guesses.
3. **Protect credentials and user worlds** using Android Keystore AES-GCM encryption (`TokenVault`), redacted diagnostic logs (`SensitiveLogRedactor`), and non-destructive storage migrations.

---

## 2. How Minecraft: Java Edition Runs on Android

Desktop Minecraft: Java Edition targets desktop Java SE VMs (`x86_64`/`aarch64` glibc) and desktop OpenGL / GLFW / OpenAL APIs. Running it on Android requires translating several runtime layers:

| Layer | Desktop Expectation | Android Execution in STAR Launcher |
| --- | --- | --- |
| **JVM Runtime** | Desktop OpenJDK 8 / 17 / 21 / 25 | Android-compiled OpenJDK (`libjvm.so`, `libjli.so`) loaded via `JREUtils` & `VMLauncher.launchJVM()` inside an isolated `:game` OS process |
| **Windowing & Input** | Desktop GLFW / X11 / Wayland | Custom Android LWJGL `3.3.3` & `3.4.1` builds (`:jre_lwjgl3glfw`), native input ring buffer (`input_bridge_v3.c`), `CallbackBridge`, SDL2/SDL3 hooks, on-screen `ControlLayout`, pointer capture (`API >= 26`), and TouchController proxy |
| **OpenGL / Vulkan Graphics** | Desktop OpenGL (`libGL`) | EGL / OSMesa bridges (`egl_bridge.c`) dispatching to **MobileGlues**, **Krypton Wrapper** (`NG-GL4ES`), **Kopper Zink** (Vulkan), **Zink** (Vulkan/OSMesa), or **LTW** (GLES 3), plus Turnip (`libvulkan_freedreno.so`) injection on `arm64-v8a` Adreno GPUs via `androidnsbypass` |
| **Audio** | Desktop OpenAL | Bundled `OpenAL-Soft` + Google `Oboe` audio backend (`openal-soft-release.aar`) |
| **Java Mod Installers (AWT/Swing)** | Desktop AWT / X11 | Isolated `:gui_installer` process (`JavaGUILauncherActivity`) using Caciocavallo headless/Android AWT (`libpojavexec_awt.so`, `libawt_xawt.so`) |
| **Mod & JVM Compatibility** | Desktop OSHI / ASM / SQLite / DNS | Runtime Java agents (`arc_dns_injector`, `MioLibPatcher`, `forge_installer`) handling Android DNS resolution, Sodium/Embeddium/VulkanMod compatibility, and headless Forge installation |

---

## 3. Navigation & User Experience

`AetherDashboardActivity` (an internal Activity class in `com.star.launcher.ui.dashboard` retained for compatibility) hosts the STAR Launcher dark-first Material 3 interface (`#090B11` deep navy canvas with `#2563EB` / `#5FA8FF` / `#5EE2FF` accents) backed by a persistent navigation stack (`SavedStateHandle`):

| Tab / Route | Primary Capabilities Implemented in Code |
| --- | --- |
| **Home** (`HomeScreen`) | Featured instance banner, Play CTA, account status indicator, runtime health summary, recent sessions & launch diagnostics summary, Discord link integration |
| **Library** (`LibraryScreen`, `InstanceDetailScreen`) | Isolated instance creation, legacy `.minecraft` import, per-instance mod inspection & non-destructive `.jar` <-> `.jar.disabled` toggling (`InstanceContentManager`), local `.jar` GUI installer & `.zip`/`.mrpack` modpack import, per-instance RAM/JVM args/runtime/renderer editing |
| **Versions** (`VersionsScreen`, `VersionInstallScreen`) | Mojang `version_manifest_v2.json` catalog sync (Releases, Snapshots, Historical Betas/Alphas), Fabric & Quilt profile installation, search/filter/sort, download progress tracking, cancellation, SHA-1 validation, and crash-safe `.aetherpart` cleanup |
| **Community** (`CommunityScreen`) | Release advisories card and configurable verified Discord invite URL (`DiscordLinkHelper`) |
| **Settings** (`SettingsScreen`, `RuntimeManagementScreen`, `EnginePreferencesScreen`, `MicrosoftAuthScreen`, `GamepadMapperScreen`) | OpenJDK 8/17/21/25 downloader & `.tar.xz` importer, Microsoft OAuth2 / Xbox Live login & offline profiles, Keystore token vault mirroring, global RAM slider, renderer summary, Custom Controls editor & Gamepad button mapper, Reduced Motion toggle, Hardware & Compatibility readout, redacted diagnostic bundle export & log sharing |

---

## 4. Storage Layout & Data Preservation

STAR Launcher separates Room metadata from heavy game files and keeps legacy internal storage identifiers intact so existing data remains compatible:

- **Room Database**: `aether_launcher.db` (WAL mode, schema v1, `10` tables, owned strictly by the `:launcher` process).
- **Isolated Instances**: `<game_home>/aether_instances/<instanceId>/.minecraft/` (worlds, mods, resource packs, shader packs, options).
- **Scoped Storage (`API >= 29`)**: `/storage/emulated/0/Android/data/com.star.launcher/files/` (or `.debug/files/` for debug builds), exposed to Android file managers via `FolderProvider` (`DocumentsProvider`).
- **Legacy Path Support (`API < 29`)**: `/sdcard/games/Amethyst` (`Tools.DIR_GAME_HOME`) when running on Android 8.1 and earlier.
- **Cross-Process Spool**: `<app_data>/aether_spool/` carries redacted JSON exit records written by the `:game` process back into `:launcher`'s Room database on next startup.
- **Encrypted Credentials**: `<no_backup>/aether_vault/` stores AES-GCM encrypted Microsoft tokens keyed by Android Keystore alias `aether_token_vault_v1`.
