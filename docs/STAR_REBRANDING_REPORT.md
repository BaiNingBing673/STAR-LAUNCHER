# STAR Launcher — Complete Rebranding & Identity Migration Report

> **Product Identity**: **STAR Launcher** (`STAR`) — *"Your Minecraft. Your Universe."*
> **Permanent Android Identity**: `com.star.launcher` (`com.star.launcher.debug` for debug builds)
> **Repository Root**: `D:\what\AetherLauncher` (branch `v3_openjdk`)

---

## 1. Executive Summary

STAR Launcher has been rebranded from **Aether Launcher** (`dev.aetherlauncher`) to **STAR Launcher** (`com.star.launcher`) across build configuration, Android manifest, Kotlin/Java packages, Compose visual identity, launcher icons, XML resources, localized strings, and project documentation, while preserving the underlying **Amethyst-Android / PojavLauncher / Boardwalk** native Minecraft Java execution engine, upstream license notices, Room database schema (`aether_launcher.db`), instance directories (`aether_instances/`), and Android Keystore vault identifiers (`aether_token_vault_v1`).

---

## 2. Package & Android Identity Decisions

| Component | Before | After | Status / Rationale |
| --- | --- | --- | --- |
| **Application ID (Release)** | `dev.aetherlauncher.app` | `com.star.launcher` | Migrated in `app_pojavlauncher/build.gradle` |
| **Application ID (Debug)** | `dev.aetherlauncher.app.debug` | `com.star.launcher.debug` | Migrated via `applicationIdSuffix ".debug"` |
| **Gradle `namespace`** | `net.kdt.pojavlaunch` | `net.kdt.pojavlaunch` | **Preserved** so upstream JNI `.so` bindings, C/C++ native entrypoints, XML `R`, and legacy `BuildConfig` references remain intact |
| **Kotlin Package Root** | `dev.aetherlauncher.*` | `com.star.launcher.*` | All `58` main `.kt` files + `21` unit test `.kt` files migrated to `src/*/kotlin/com/star/launcher/` |
| **Java Caller Imports** | `dev.aetherlauncher.*` | `com.star.launcher.*` | Updated across `9` integration Java files under `net.kdt.pojavlaunch.*` |
| **Provider Authorities** | `dev.aetherlauncher.scoped.gamefolder` / `.controlfolder` | `com.star.launcher.scoped.gamefolder` / `.controlfolder` (+ `.debug`) | Migrated in `build.gradle` `resValue` & verified in compiled `AndroidManifest.xml` |
| **IPC Intent Extras** | `dev.aetherlauncher.extra.*` | `com.star.launcher.extra.*` | Updated in `PojavLaunchBridge.kt` |
| **Display Name / Short Name** | `Aether` | `STAR Launcher` / `STAR` (+ `(Debug)`) | Updated in `build.gradle` & `res/values/strings.xml` |
| **Room Database Schema** | `schemas/dev.aetherlauncher.data.db.AetherDatabase/1.json` | `schemas/com.star.launcher.data.db.AetherDatabase/1.json` | Migrated schema export directory matching `com.star.launcher.data.db.AetherDatabase` |
| **Room DB Filename** | `aether_launcher.db` | `aether_launcher.db` | **Preserved** for schema/data compatibility |
| **Internal Storage & Vault** | `aether_instances/`, `aether_downloads/`, `aether_spool/`, `aether_vault`, `aether_token_vault_v1`, `CN=AetherTokenVault`, `.aetherpart`, `amethyst://` | Unchanged | **Preserved** per backward-compatibility requirements |

> **Upgrade Note**: Because `applicationId` changed from `dev.aetherlauncher.app` to `com.star.launcher`, Android treats `com.star.launcher` as a distinct application and cannot upgrade in place over existing `dev.aetherlauncher.app` installs. A safe manual backup and restore workflow is documented in [`docs/MIGRATION_GUIDE.md`](MIGRATION_GUIDE.md).

---

## 3. Summary of Modified & Created Files

### 3.1 Build, Manifest & Package Structure
- `settings.gradle`: Updated `rootProject.name='STAR-Launcher'`.
- `app_pojavlauncher/build.gradle`: Migrated `applicationId` and `debug`/`release` `resValue` definitions (`app_name`, `app_short_name`, `application_package`, `storageProviderAuthorities`, `shareProviderAuthority`).
- `app_pojavlauncher/src/main/AndroidManifest.xml`: Updated `com.star.launcher.ui.dashboard.AetherDashboardActivity` declaration.
- **Kotlin Sources & Tests**: Migrated `58` files from `src/main/kotlin/dev/aetherlauncher/**` to `src/main/kotlin/com/star/launcher/**`, and `21` files from `src/test/kotlin/dev/aetherlauncher/**` to `src/test/kotlin/com/star/launcher/**`.
- **Java Engine Integration (`9` files)**: `ExitActivity.java`, `FatalErrorActivity.java`, `LauncherActivity.java`, `MainActivity.java`, `NewJREUtil.java`, `TestStorageActivity.java`, `Tools.java`, `ContextAwareDoneListener.java`, `NotificationDownloadListener.java` (imports/class references updated to `com.star.launcher.*`).

### 3.2 Visual Rebranding & App Icons
- **Launcher Icon Set (Generated from user-supplied shooting-star comet logo)**:
  - `res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_foreground.webp` (transparent comet emblem centered in adaptive safe zone)
  - `res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_background.webp` (deep navy `#090B11` with subtle electric-blue center glow)
  - `res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.webp` & `ic_launcher_round.webp` (legacy rounded-square and circular icons)
  - `res/mipmap-anydpi-v26/ic_launcher.xml` & `ic_launcher_round.xml` (added `<monochrome android:drawable="@drawable/ic_launcher_monochrome"/>`)
  - `res/values/ic_launcher_background.xml` (`#090B11`)
  - `res/drawable/ic_launcher_monochrome.webp`, `res/drawable/notif_icon.png`, `res/drawable/ic_pojav_full.webp`
  - `assets/star_logo.png` (512x512 transparent emblem)
- **Theme & UI Components**:
  - `ui/theme/Color.kt` & `res/values/colors.xml`: Shifted accent hierarchy from purple/violet (`#9649b8`, `#8B7CFF`, `#9C8FFF`) to deep navy (`#090B11` / `#10141F`) with electric blue & cyan (`#2563EB`, `#5FA8FF`, `#38BDF8`, `#5EE2FF`).
  - `ui/components/AetherNavigation.kt`: Replaced diamond audio-waveform brand mark with geometric shooting-star comet `Canvas` mark and updated header text to `"STAR"` / `"YOUR MINECRAFT. YOUR UNIVERSE."`.
  - `ui/components/AetherArtworkPanel.kt`: Replaced audio-spectrum/violet wording & backdrop preset with cosmic electric-blue/cyan presets and star-trail accent strip.
  - `ui/components/AetherButtons.kt`: Updated primary Play CTA gradient to electric blue -> cyan.

### 3.3 User-Facing Copy & Localized Resources
- **`res/values/strings.xml` + 42 localized `values-*/strings.xml`** (`145` lines across `43` files): Rebranded `app_name`, `app_short_name`, `error_fatal`, `lazy_service_default_title`, `storage_required`, `modpack_install_notification_title`, `notification_permission_dialog_text`, `forge_dl_no_installer`, and `create_profile_modded_versions` from `Aether`/`PojavLauncher`/`Pojav` to `STAR`/`STAR Launcher`.
- **Layouts & Java UI**:
  - `res/layout/activity_pojav_launcher.xml` (`"STAR Hub"`)
  - `res/layout/item_simple_list_1.xml` (`tools:text="STAR Launcher version: Foxglove"`)
  - `PojavApplication.java` (`"STAR Launcher crash report\n"`)
  - `MainMenuFragment.java` (`"Configure your Discord link in STAR Community"`)
- **Compose Screens & Services**:
  - `HomeScreen.kt`, `CommunityScreen.kt`, `SettingsScreen.kt`, `EnginePreferencesScreen.kt`, `MicrosoftAuthScreen.kt`, `DiagnosticExporter.kt` (`star-support-*.txt`, `=== STAR LAUNCHER DIAGNOSTIC REPORT ===`), `DownloadManager.kt` (`User-Agent: STARLauncher/1.0`).

### 3.4 Documentation & Templates
- Created [`docs/STAR_REBRANDING_AUDIT.md`](STAR_REBRANDING_AUDIT.md), [`docs/MIGRATION_GUIDE.md`](MIGRATION_GUIDE.md), and [`docs/STAR_REBRANDING_REPORT.md`](STAR_REBRANDING_REPORT.md).
- Updated [`README.md`](../README.md), [`dataremoval.md`](../dataremoval.md), [`.github/ISSUE_TEMPLATE/bug_report.yml`](../.github/ISSUE_TEMPLATE/bug_report.yml), and `9` project docs under `docs/` (`ARCHITECTURE.md`, `BUILD_GUIDE.md`, `COMPATIBILITY_STRATEGY.md`, `DATABASE_SCHEMA.md`, `LICENSE_AUDIT.md`, `TASK_TRACKER.md`, `TESTING_STRATEGY.md`, `UPSTREAM_MAP.md`, `ZALITH_REFERENCE.md`) while preserving Amethyst/Pojav/Boardwalk/Zalith attributions and licenses.

---

## 4. GitHub Repository Migration Status

- **Remote Preserved**: `origin` remains unchanged at `https://github.com/AngelAuraMC/Amethyst-Android.git` per updated instruction (`Keep the existing GitHub remote unchanged`).
- No repository rename, push, force-push, or visibility change was performed.

---

## 5. Build, Unit Test & APK Verification Results

### 5.1 Unit Tests (`:app_pojavlauncher:testDebugUnitTest`)
- **Command**: `.\gradlew.bat :app_pojavlauncher:testDebugUnitTest :app_pojavlauncher:assembleDebug --console=plain` (after cold deletion of `app_pojavlauncher/build`, `app_pojavlauncher/.cxx`, `androidnsbypass/build`, `androidnsbypass/.cxx`)
- **Result**: `BUILD SUCCESSFUL in 1m 57s` (`104 actionable tasks`)
- **Test Summary**: `21` test suites, `63` tests run, `0` failures, `0` errors, `0` skipped.

### 5.2 Debug APK Output & `aapt2` Verification
- **Artifact**: `D:\what\AetherLauncher\app_pojavlauncher\build\outputs\apk\debug\app_pojavlauncher-debug.apk`
- **Size**: `152,661,915` bytes
- **SHA-256**: `F61815F42F19972E2D6DBFF60A265C2F467120D6855DA1B6CD8C3247190CF2BD`
- **`aapt2 dump badging`**:
  - `package: name='com.star.launcher.debug' versionCode='10000000' versionName='1.1.7-45-166af615d-debug-1.1.7'`
  - `application-label:'STAR Launcher (Debug)'`
  - `application-icon-*:'res/mipmap-anydpi-v26/ic_launcher.xml'`
  - `launchable-activity: name='net.kdt.pojavlaunch.TestStorageActivity' label='STAR (Debug)'`
  - `native-code`: `arm64-v8a`, `armeabi`, `armeabi-v7a`, `mips`, `mips64`, `x86`, `x86_64` (`110` `.so` entries verified in APK)
- **`aapt2 dump resources` / `dump xmltree AndroidManifest.xml`**:
  - `string/app_name`: `"STAR Launcher (Debug)"`
  - `string/app_short_name`: `"STAR (Debug)"`
  - `string/storageProviderAuthorities`: `"com.star.launcher.scoped.gamefolder.debug"`
  - `string/shareProviderAuthority`: `"com.star.launcher.scoped.controlfolder.debug"`
  - `androidx-startup`: `"com.star.launcher.debug.androidx-startup"`
  - `AetherDashboardActivity`: `"com.star.launcher.ui.dashboard.AetherDashboardActivity"`
  - Packaged assets verified: `assets/star_logo.png`, `assets/components/MioLibPatcher/MioLibPatcher.jar`, `assets/components/arc_dns_injector/arc_dns_injector.jar`, `assets/components/forge_installer/forge_installer.jar`.

### 5.3 Post-Migration Brand Leak Scan
- `res/values*/strings.xml` scan for `aether|pojav|amethyst`: **0** matches.
- `app_pojavlauncher/` source/manifest/gradle/schema scan for `dev.aetherlauncher`: **0** matches.

---

## 6. Remaining Intentional `Aether` / `Amethyst` / `Pojav` References & Rationale

1. **Data & Crypto Compatibility**: `aether_launcher.db`, `aether_instances/`, `aether_downloads/`, `aether_spool/`, `aether_exports/`, `aether_vault`, `aether_token_vault_v1`, `CN=AetherTokenVault`, `.aetherpart`, and `Tools.LAUNCHERPROFILES_RTPREFIX = "amethyst://"` remain untouched to protect user worlds, profiles, and keystore integrity.
2. **Gradle Module `namespace`**: `net.kdt.pojavlaunch` remains unchanged to preserve JNI `.so` symbol resolution (`Java_net_kdt_pojavlaunch_*`), `BuildConfig`, and upstream C/Java interop.
3. **Internal Kotlin/XML Symbol Names**: Classes such as `AetherDashboardActivity`, `AetherDatabase`, `AetherStorageManager`, `AetherContainer`, `AetherColors`, `AetherTheme`, and `AetherIcons` retain their symbol names to avoid high-churn cosmetic refactors while displaying pure STAR branding to users.
4. **Upstream Engine & Attributions**: `PojavCrashReport`, `/games/Amethyst` legacy external path compatibility in `Tools.java`, JNI libraries (`libpojavexec.so`, `libpojavexec_awt.so`), and credits/licenses in `README.md`, `about_en.txt`, and `docs/` preserve upstream attribution.

---

## 7. Unresolved / Pending On-Device Verification

- **No Android Device / Emulator Attached (`adb devices` empty)**: Build-time APK inspection (`aapt2`), unit/Robolectric tests (`63/63` passed), and asset/manifest verification all succeeded locally, **but** live installation via `adb install`, visual UI walkthrough on Android launcher/home screens, DocumentProvider SAF mounting, and end-to-end Minecraft game launching could not be executed in this headless Windows environment without an attached device or emulator.
- **Clean Task Note**: Running `./gradlew clean` directly can fail during `:app_pojavlauncher:externalNativeBuildCleanDebug` if `:androidnsbypass:clean` deletes its prefab `.so` first; cleaning `build/` and `.cxx/` directories or building incrementally avoids this upstream prefab clean ordering issue.
- **STAR Lab Scope Note**: As confirmed during the Phase A audit (`STAR_REBRANDING_AUDIT.md`) and user instruction (`skip 3-4`), `Aether Lab`, `STAR Doctor`, and `STAR Pulse` had no existing feature screens or external PRD in the repository, so no unrequested features were fabricated; only existing hardware/compatibility diagnostics were retained.
