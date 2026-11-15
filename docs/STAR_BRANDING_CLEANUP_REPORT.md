# STAR Launcher — Branding Cleanup & Verification Report

> **Repository Root**: `D:\what\AetherLauncher` (`v3_openjdk` branch, `origin` = `https://github.com/AngelAuraMC/Amethyst-Android.git`)  
> **Permanent Application Identity**: `com.star.launcher` (`.debug` suffix on debug builds)  
> **Audit Companion**: [`docs/STAR_LEGACY_REFERENCE_AUDIT.md`](STAR_LEGACY_REFERENCE_AUDIT.md)

---

## 1. Executive Summary

This cleanup pass audited all legacy `PojavLauncher`, `Pojav`, `Aether Launcher`, `Aether Lab`, `Aether*`, `Amethyst`, `AngelAuraMC`, and `Boardwalk` references across the repository, removed/updated remaining user-facing and misleading branding or process-architecture descriptions, and preserved every internal Gradle/Java/Kotlin/JNI/storage/compatibility identifier and upstream legal/attribution notice.

| Metric | Count | Details |
| --- | ---: | --- |
| **Total baseline legacy references found** | **3,533** | `3,531` text lines + `2` legacy-named binary assets across **438** files |
| **`USER_FACING_BRANDING` items identified** | **21** | `20` code/config/comment/log lines updated + `1` unused legacy asset (`amethyst.png`) flagged |
| **Additional KDoc/comment & log clarifications** | **7** | Refined ambiguous `Aether`/`Pojav`/`Amethyst` KDoc comments mentioning internal symbols (`PojavLaunchBridge`, `AetherDatabase`, etc.) |
| **Documentation files updated** | **7** | `README.md`, `ARCHITECTURE.md`, `ENGINE_DEPENDENCY_MAP.md`, `BUILD_GUIDE.md`, `TASK_TRACKER.md`, `STAR_LAUNCHER_OVERVIEW.md`, `FEATURE_STATUS.md` |
| **Internal technical references intentionally retained** | **3,140** | `INTERNAL_GRADLE_MODULE` (`46`) + `SOURCE_CLASS_OR_PACKAGE` (`2,816`) + `LEGACY_COMPATIBILITY` (`49`) + `TEST_OR_FIXTURE` (`212`) + `GENERATED_OR_THIRD_PARTY` (`17`) |
| **Upstream & legal attribution references retained** | **73** | `LICENSE`, `about_en.txt`, `assets/licenses/*`, `crowdin.yml`, Java/C upstream credits, `UPSTREAM_ATTRIBUTION.md`, `LICENSE_AUDIT.md`, `UPSTREAM_MAP.md`, `ZALITH_REFERENCE.md` |
| **Unclassified / unknown items** | **0** | All 3,533 matches classified in `docs/STAR_LEGACY_REFERENCE_AUDIT.md` |
| **Files touched in this cleanup pass** | **31** | `29` modified + `2` created (`STAR_LEGACY_REFERENCE_AUDIT.md`, `STAR_BRANDING_CLEANUP_REPORT.md`) |

---

## 2. Changes Applied

### 2.1 User-Facing Strings, Build Config, Log Prefixes & KDoc Comments (22 files modified)

| File | What Was Changed | Preserved Symbol / Contract |
| --- | --- | --- |
| `.github/FUNDING.yml` | Removed upstream `patreon: angelauramc` funding handle | Comment noting removal |
| `app_pojavlauncher/build.gradle` | Changed fallback `TAG_STRING` `'amethyst-legacy-'` -> `'star-legacy-'`; updated `// Aether M0.4` / `M0.5` comments -> `// STAR Launcher` | Kept `:app_pojavlauncher`, `namespace 'net.kdt.pojavlaunch'`, `applicationId "com.star.launcher"`, `.scoped.*` build fields |
| `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java` | Updated `APP_NAME = "Amethyst"` -> `"STARLauncher"` and Legacy4J log prefix `"Amethyst-Android:"` -> `"STAR Launcher:"` | Kept `LAUNCHERPROFILES_RTPREFIX = "amethyst://"`, `/games/Amethyst`, `URL_HOME` |
| `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/MainActivity.java` | Updated Sodium unsupported-mod log string `"Amethyst-Android"` -> `"STAR Launcher"` | Kept `net.kdt.pojavlaunch.MainActivity` |
| `app_pojavlauncher/src/main/java/org/lwjgl/glfw/CallbackBridge.java` | Updated SDL integration log prefix `"Amethyst-Android:"` -> `"STAR Launcher:"` | Kept native callback symbols |
| `app_pojavlauncher/src/main/jni/native_hooks/dlopen_hook.c` | Updated `LOGI("Amethyst-Android: ...")` -> `LOGI("STAR Launcher: ...")` | Kept ByteHook native hook symbols |
| `app_pojavlauncher/src/main/jni/native_hooks/sdl_hook.c` | Updated `LOGI("Amethyst-Android: ...")` -> `LOGI("STAR Launcher: ...")` | Kept `POJAVEXEC_EGL` / SDL hook symbols |
| `app_pojavlauncher/src/main/kotlin/com/star/launcher/version/VersionInstallCoordinator.kt` | Clarified `VersionDownloadEngine` KDoc (`inherited Amethyst-Android/PojavLauncher download engine`) | Kept `.aetherpart` download temp contract |
| `14` Kotlin files under `app_pojavlauncher/src/main/kotlin/com/star/launcher/` | Cleaned ambiguous legacy `Aether` / `Aether Design` / `Aether's` / `Pojav` phrasing in KDoc & comments (`AccountRepository.kt`, `AetherContainer.kt`, `AetherStorageManager.kt`, `AetherDatabase.kt`, `CoreCatalogEntities.kt`, `AuthAdapter.kt`, `EnginePreferencesAdapter.kt`, `PojavLaunchBridge.kt`, `InstanceManager.kt`, `AetherIcons.kt`, `AetherNavRoutes.kt`, `StatusBadges.kt`, `Theme.kt`, `Type.kt`) | Kept all class/object/method/route/DB/Keystore identifiers untouched |

> **UI Resource Verification**: All `strings.xml` files under `app_pojavlauncher/src/main/res/values*/` contain **0** references to `PojavLauncher`, `Pojav`, `Aether Launcher`, `Aether Lab`, `Amethyst`, `AngelAuraMC`, or `Boardwalk`.

### 2.2 Documentation & Architecture Corrections (7 modified + 2 created)

- **[`README.md`](../README.md)**: Updated Implemented features, First-Time Setup Guide (`TestStorageActivity` -> `MissingStorageActivity` / `AetherDashboardActivity` / `LauncherActivity`), Project Architecture Overview (accurate `:launcher`, `:game`, `:gui_installer` manifest breakdown distinguishing internal symbols from STAR branding), and Documentation Index.
- **[`docs/ARCHITECTURE.md`](ARCHITECTURE.md)**: Replaced simplified process table with exact `AndroidManifest.xml`-verified component inventory (`<application android:name=".PojavApplication" android:process=":launcher">`, all `:launcher` activities/provider/service, `:gui_installer`'s `JavaGUILauncherActivity`, and `:game`'s `MainActivity` + `GameService`).
- **[`docs/ENGINE_DEPENDENCY_MAP.md`](ENGINE_DEPENDENCY_MAP.md)**: Corrected sequence diagram to reflect `TestStorageActivity` -> `AetherDashboardActivity` (or `LauncherActivity`) -> `PojavLaunchBridge` -> `:game` (`MainActivity`, `GameService`).
- **[`docs/BUILD_GUIDE.md`](BUILD_GUIDE.md)**: Clarified historical `D:\Aether Launcher` / `D:\STAR Launcher` space-in-path notes vs current spaceless `D:\what\AetherLauncher`.
- **[`docs/TASK_TRACKER.md`](TASK_TRACKER.md)**: Added historical Phase 0–3 note cross-referencing this cleanup report.
- **[`docs/STAR_LAUNCHER_OVERVIEW.md`](STAR_LAUNCHER_OVERVIEW.md)** & **[`docs/FEATURE_STATUS.md`](FEATURE_STATUS.md)**: Marked `AetherDashboardActivity`, `AetherStorageManager`, `aether_instances/`, and `PojavApplication` explicitly as retained internal symbols, and aligned three-process evidence with `AndroidManifest.xml`.
- **Created**: [`docs/STAR_LEGACY_REFERENCE_AUDIT.md`](STAR_LEGACY_REFERENCE_AUDIT.md) and [`docs/STAR_BRANDING_CLEANUP_REPORT.md`](STAR_BRANDING_CLEANUP_REPORT.md).

---

## 3. Intentionally Retained References & Remaining Risky Items

Per strict engineering constraints, internal identifiers, persisted paths, JNI symbols, test fixtures, and upstream legal credits were retained. Full line-level details and future migration plans appear in [`docs/STAR_LEGACY_REFERENCE_AUDIT.md`](STAR_LEGACY_REFERENCE_AUDIT.md).

### Remaining Risky / Follow-Up References

1. **`app_pojavlauncher/src/main/assets/amethyst.png` (1.28 MB)**: Legacy unused Amethyst banner asset (not referenced by code, XML, or `README.md`, which uses `star_logo.png`). Left untouched during non-destructive pass; safe to delete in a follow-up PR.
2. **`app_pojavlauncher/src/main/assets/about_en.txt`**: Upstream PojavLauncher/Boardwalk GPLv3 & Crowdin credit text file (not loaded by current UI code; preserved for upstream attribution).
3. **`Tools.URL_HOME` (`https://wiki.angelauramc.dev`) & `NewJREUtil.java` (`AngelAuraMC/angelauramc-openjdk-build`)**: External upstream endpoints still used by legacy `MainMenuFragment.java` and JRE runtime provisioning; must not be changed until STAR Launcher hosts verified replacement endpoints.
4. **Workspace Split Note (`d:\Aether Launcher` vs `D:\what\AetherLauncher`)**: Windows workspace `d:\Aether Launcher` contains only `.gradle/` and `.idea/` metadata; all tracked project files live in `D:\what\AetherLauncher`.

---

## 4. Verification Results (Post-Cleanup)

### 4.1 Gradle Build & Unit Tests

Executed from `D:\what\AetherLauncher` with `JAVA_HOME=D:\tools\jdks\temurin-21` and `ANDROID_HOME=D:\tools\android-sdk`:

```powershell
.\gradlew.bat :app_pojavlauncher:testDebugUnitTest :app_pojavlauncher:assembleDebug --console=plain
```

- **Result**: `BUILD SUCCESSFUL in 1m 35s` (`104` actionable tasks: `43` executed, `8` from cache, `53` up-to-date)
- **Native / NDK Build**: `buildNdkBuildDebug` compiled cleanly across all 4 ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`), including updated `dlopen_hook.c` and `sdl_hook.c`.
- **Unit Tests (`:app_pojavlauncher:testDebugUnitTest`)**:
  - **21** test suites executed
  - **63** tests run, **0** failures, **0** errors, **0** skipped
- **Debug APK Output**: `app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk` (`152,661,873` bytes)

### 4.2 Static & Structure Verification

- **Gradle Module Graph**: All 8 declared modules in `settings.gradle` (`:jre_lwjgl3glfw`, `:jre_lwjgl3glfw:lwjgl-3.3.3`, `:jre_lwjgl3glfw:lwjgl-3.4.1`, `:app_pojavlauncher`, `:arc_dns_injector`, `:MioLibPatcher`, `:forge_installer`, `:androidnsbypass`) verified present.
- **UI Strings (`res/values*/strings.xml`)**: **0** legacy branding hits remaining.
- **Markdown Links**: Checked across all `README.md` and `docs/*.md` files with **0** broken local links after report generation.
