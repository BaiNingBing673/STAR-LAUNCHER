# STAR Launcher — Complete Rebranding Audit (`Phase A`)

> **Audit Date**: 2026-11-15  
> **Old Product Name**: Aether Launcher (`Aether`)  
> **New Product Name**: STAR Launcher (`STAR`)  
> **Slogans**: *"Your Minecraft. Your Universe."* / *"Play Beyond Limits."*  
> **Scope**: Non-destructive repository-wide inspection prior to any code, identity, asset, or GitHub changes.

---

## 1. Executive Summary & Critical Environment Findings

### 1.1 Workspace Location Discovery
- `D:\Aether Launcher` (and its NTFS junction `D:\AetherLauncher`) currently contains **only** `.gradle/` and `.idea/` metadata.
- The actual Android Gradle project and Git repository live at **`D:\what\AetherLauncher`**:
  - Git branch: `v3_openjdk` (HEAD `166af615d` from upstream `Amethyst-Android`), with uncommitted Phase 0–3 Aether changes across Java, Kotlin, XML, Gradle, and `docs/`.
  - Git remote `origin`: `https://github.com/AngelAuraMC/Amethyst-Android.git`.
  - GitHub CLI (`gh`): **Not installed** on this machine.

### 1.2 Quantitative Audit Summary
- **146 files** in `D:\what\AetherLauncher` (excluding `.git`, `build`, `.gradle`, `.idea`, `.kotlin`, `.cxx`, binary blobs) match `aether` (case-insensitive) by file path and/or content:
  - **Gradle & Manifest**: `app_pojavlauncher/build.gradle`, `app_pojavlauncher/src/main/AndroidManifest.xml`
  - **Room Schema Export**: `app_pojavlauncher/schemas/dev.aetherlauncher.data.db.AetherDatabase/1.json`
  - **Java Integration Bridge**: 11 files in `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/`
  - **Kotlin Application Source**: 58 files in `app_pojavlauncher/src/main/kotlin/dev/aetherlauncher/`
  - **Kotlin Unit/Robolectric Tests**: 21 files in `app_pojavlauncher/src/test/kotlin/dev/aetherlauncher/`
  - **Android Resources**: `res/layout/activity_pojav_launcher.xml` + **43** localized `res/values*/strings.xml` files
  - **Documentation**: **10** Markdown files in `docs/`
- **Legacy Upstream User-Facing Strings Remaining**:
  - **29** `res/values*/strings.xml` files (including base `values/strings.xml`) still reference `PojavLauncher` or `Pojav Modpack Installer` in user-visible dialogs/notifications (`storage_required`, `modpack_install_notification_title`, `notification_permission_dialog_text`).
  - `Tools.java` (`APP_NAME = "Amethyst"`, `DIR_GAME_HOME` legacy API <29 fallback `/games/Amethyst`), `PojavApplication.java` (`"Amethyst crash report\n"`), `MainActivity.java` (Sodium warning log/message referencing `Amethyst-Android`), and `README.md`, `dataremoval.md`, `settings.gradle`, `.github/` still carry `Amethyst` / `AngelAuraMC` branding.
- **Aether Lab / Compatibility Passport / Doctor / Pulse Gap**:
  - A full-repository search found **0 occurrences** of `Aether Lab`, `Compatibility Passport`, `Aether Doctor`, or `Aether Pulse` (the only matches for `pulse` are upstream SDL3 `PulseAudio` comments in `SDL_hints.h`).
  - Existing compatibility and diagnostics capabilities live in `CompatibilityManager`, `DeviceCapabilityProbe`, `DiagnosticsManager`, `DiagnosticExporter`, and `CrossProcessSpoolBridge`, with no `Aether Lab` UI screen or PRD document present in the repository.

---

## 2. Application Identity, Package & Compatibility Analysis

| Property | Current Value | Proposed STAR Value | Decision / Compatibility Impact |
|---|---|---|---|
| **Gradle `namespace`** | `net.kdt.pojavlaunch` (`app_pojavlauncher/build.gradle:79`) | **KEEP `net.kdt.pojavlaunch`** | **Critical**: JNI native libraries (`libpojavexec.so`, `libpojavexec_awt.so`) and upstream `R` references depend on `net.kdt.pojavlaunch`. Changing `namespace` breaks JNI/engine compatibility. |
| **Android `applicationId`** | `dev.aetherlauncher.app` (`.debug` suffix for debug) | `com.star.launcher` (`.debug` for debug) — **REQUIRES APPROVAL** | Changing `applicationId` creates a distinct Android app package: side-by-side install works, but existing `dev.aetherlauncher.app` installs cannot update in place and get a new `/Android/data/` sandbox. |
| **Provider Authorities** | `dev.aetherlauncher.app.scoped.gamefolder` & `.controlfolder` (`+.debug`) | Tied to `applicationId` (`${applicationId}.scoped.*` or `com.star.launcher.scoped.*`) | Must stay aligned with `applicationId` so `FolderProvider` does not collide if both apps are installed. |
| **`app_name` / `app_short_name`** | `Aether` / `Aether (Debug)` | `STAR Launcher` / `STAR` (Debug: `STAR (Debug)`) | **REBRAND** in `build.gradle` `resValue`s and `res/values/strings.xml`. |
| **Kotlin Package Root** | `dev.aetherlauncher.*` (58 main + 18 test files) | **KEEP `dev.aetherlauncher.*` OR defer** | Per rebranding rules (*"Do not rename every Java/Kotlin package just for cosmetic reasons"*), keeping `dev.aetherlauncher.*` avoids unnecessary churn and preserves Room schema `1.json` continuity. Internal UI/user-facing class/string branding is updated cleanly. |
| **Firebase Configuration** | None (`google-services.json` absent) | N/A | No Firebase project or package coupling exists. |
| **Deep Links & Schemes** | `ImportControlActivity` (`content://` JSON/text) | No custom scheme to change | `Tools.LAUNCHERPROFILES_RTPREFIX = "amethyst://"` is an internal profile game-dir prefix (not an Android URI scheme) and **must remain** `"amethyst://"` for engine/profile compatibility. |
| **Database Name** | `aether_launcher.db` (`AetherDatabase.kt:62`) | **PRESERVE or Safe-Migrate** | Renaming blindly would orphan existing Room DB data (instances, settings, versions, sessions). Recommend keeping `aether_launcher.db` or implementing a one-time pre-open file copy/rename migration (`aether_launcher.db` -> `star_launcher.db` if old exists and new does not). |
| **Keystore Vault** | Dir `noBackupFilesDir/aether_vault`, KeyAlias `aether_token_vault_v1`, X500 `CN=AetherTokenVault` | **PRESERVE** (or read-fallback) | Changing `DEFAULT_KEY_ALIAS` or `vaultDir` without a migration decrypt+re-encrypt step would invalidate stored encrypted Microsoft OAuth tokens. **Keep internal alias/dir** to guarantee zero token loss. |
| **Storage Subdirectories** | `aether_instances/`, `aether_downloads/`, `aether_spool/`, `aether_exports/`, `.aetherpart` | **PRESERVE or Safe-Migrate** | Changing `aether_instances/` without migrating existing directories and Room `game_dir_path` rows would hide user worlds/mods/saves! Recommend keeping `aether_instances/` (or implementing an explicit backward-compatible migration). |
| **Intent Extras & Nav Keys** | `dev.aetherlauncher.extra.*`, `aether_route`, `aether_initial_route`, `aether_nav_back_stack` | Keep internal or update atomically across `:launcher`/`:game` | Internal-only IPC keys; safe to update atomically or keep as-is. |

---

## 3. Complete Reference Inventory & Proposed Actions

### 3.1 Build Configuration & Manifest (`2` files)
| File | References Found | Proposed Action |
|---|---|---|
| `app_pojavlauncher/build.gradle` | L106 `applicationId "dev.aetherlauncher.app"`; L128–132 & L152–156 `resValue`s for `app_name`, `app_short_name`, `application_package`, `storageProviderAuthorities`, `shareProviderAuthority`; L285, L301 comments | **REBRAND** `app_name` -> `"STAR Launcher"` / `"STAR (Debug)"`, `app_short_name` -> `"STAR"` / `"STAR (Debug)"`. Update `applicationId` & authorities to `com.star.launcher` **only if approved in Phase D**. |
| `app_pojavlauncher/src/main/AndroidManifest.xml` | L66 `dev.aetherlauncher.ui.dashboard.AetherDashboardActivity`; L35–38 `icon`, `roundIcon`, `label`; L53/63/68 `app_short_name`; L128 `@string/storageProviderAuthorities` | Keep `AetherDashboardActivity` class reference (or update if renamed); verify launcher/short labels & new STAR icons resolve cleanly. |
| `settings.gradle` | L17 `rootProject.name='Amethyst'` | **REBRAND** to `rootProject.name='STAR-Launcher'` (or keep if Gradle composite/CI expects it; safe to update). |

### 3.2 Upstream Java Layer (`11` files in `app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/`)
| File | References Found | Proposed Action |
|---|---|---|
| `ExitActivity.java` | Imports `AetherDashboardActivity`; `mReturnedToAether`, `returnToAether()` | Update internal helper naming only if `AetherDashboardActivity` is aliased/renamed; preserve launch return behavior. |
| `FatalErrorActivity.java` | Imports & restarts `AetherDashboardActivity` | Preserve crash recovery routing. |
| `LauncherActivity.java` | `mAetherHubButton`, `R.id.aether_hub_button`, `EXTRA_AETHER_ROUTE` (`"aether_route"`) | Rename UI hub button/label references cleanly if layout ID updated; preserve route extras. |
| `MainActivity.java` | `AetherLaunchPayload`, `mAetherPayload`, `PojavLaunchBridge`, `CrossProcessSpoolBridge`, L503 `"Amethyst-Android"` sodium warning log | Keep engine launch pipeline untouched; update any user-visible toast/dialog branding if surfaced. |
| `NewJREUtil.java` | `instanceof AetherDashboardActivity` -> `openRuntimeManagement()` | Preserve runtime recovery hook; keep upstream `AngelAuraMC/angelauramc-openjdk-build` JRE download URL intact (functional dependency!). |
| `TestStorageActivity.java` | Launches `AetherDashboardActivity.createIntent(this)` | Preserve startup handoff. |
| `Tools.java` | L1545–1550 `AetherDashboardActivity` mod installer hook; L117 `APP_NAME = "Amethyst"`; L128 `LAUNCHERPROFILES_RTPREFIX = "amethyst://"`; L132/162 `games/Amethyst` (API <29); L641/648 `Amethyst-Android` logs | **KEEP** `LAUNCHERPROFILES_RTPREFIX = "amethyst://"` and `games/Amethyst` (API <29) for strict backward compatibility with existing game data; update `APP_NAME` carefully only after verifying `DownloadUtils.USER_AGENT` and `-Dnet.minecraft.clientmodname=` impact (or set `APP_NAME = "STAR"` if safe). |
| `PojavApplication.java` | L42 `"Amethyst crash report\n"`, L28 `CRASH_REPORT_TAG = "PojavCrashReport"` | **REBRAND** crash header to `"STAR Launcher crash report\n"` (preserve `latestcrash.txt` path). |
| `fragments/MainMenuFragment.java` | L66 Toast `"Configure your Discord link in Aether Community"` | **REBRAND** Toast to `"Configure your Discord link in STAR Community"`. |
| `lifecycle/ContextAwareDoneListener.java` | `AetherContainer.flushWalIfInitialized()`, `mAetherLaunchExtras` | Preserve WAL flush & launch handoff behavior. |
| `modloaders/modpacks/api/NotificationDownloadListener.java` | `AetherDashboardActivity.createRouteIntent(mContext, "VERSIONS")` | Preserve notification tap navigation. |
| `tasks/MinecraftDownloader.java` | L470, L501, L878 `".aetherpart"` staging extension | **KEEP** `".aetherpart"` (or clean both `.aetherpart` and `.starpart`) to ensure interrupted downloads clean up cleanly. |

### 3.3 Kotlin Application Layer (`58` files under `app_pojavlauncher/src/main/kotlin/dev/aetherlauncher/`)

Per Section 3 (*"Do not rename every Java/Kotlin package just for cosmetic reasons"*), the `dev.aetherlauncher.*` package namespace and Room `1.json` schema identity are preserved unless a full package refactor is explicitly requested. User-facing branding, UI visual tokens, and brand-visible strings inside these files will be updated:

| Group | Files | User-Facing / Brand References | Proposed Action |
|---|---|---|---|
| **Theme & Visual Identity** (`ui/theme/`) | `Color.kt`, `Theme.kt`, `Type.kt`, `StatusBadges.kt` | `AetherColors` (currently violet `#8B7CFF` + blue `#5FA8FF` + cyan `#5EE2FF`, bg `#090B11`), `AetherTheme`, `AetherTypography`, `AetherMonoStyle` | **REBRAND VISUALS**: Shift accent emphasis from violet audio-player styling to deep navy/charcoal (`#090B11` / `#0E1320`) + electric blue/cyan (`#38BDF8` / `#5FA8FF` / `#5EE2FF`). Provide `StarTheme` / `StarColors` aliases (or keep compatibility aliases if needed by tests). |
| **UI Components** (`ui/components/`) | `AetherNavigation.kt`, `AetherArtworkPanel.kt`, `AetherIcons.kt`, `AetherButtons.kt`, `AetherStates.kt`, `AetherSurfaceCard.kt`, `AetherVersionComponents.kt` | `AetherBrandMark` (diamond + 3 audio waveform bars), TopAppBar `"AETHER"` / `"MINECRAFT JAVA EDITION"`, `AetherSoundwaveStrip`, `"AetherHome"` etc. vector names | **REBRAND VISUALS**: Replace `AetherBrandMark` with geometric shooting-star emblem derived from user's uploaded logo; update TopAppBar title `"AETHER"` -> `"STAR"` with subtle `"YOUR MINECRAFT. YOUR UNIVERSE."` / `"MINECRAFT JAVA EDITION"`; replace `"audio-spectrum"` waveform motif in `AetherArtworkPanel.kt` with subtle star-trail / geometric horizon cues; replace `AccentViolet` primary button tint with electric blue/cyan. |
| **Screens** (`ui/screens/`) | `HomeScreen.kt`, `CommunityScreen.kt`, `SettingsScreen.kt`, `EnginePreferencesScreen.kt`, `MicrosoftAuthScreen.kt`, `LibraryScreen.kt`, `VersionsScreen.kt`, `VersionInstallScreen.kt`, `InstanceDetailScreen.kt`, `RuntimeManagementScreen.kt`, `RuntimeDetailScreen.kt`, `GamepadMapperScreen.kt`, `AetherSubscreenComponents.kt` | `HomeScreen.kt`: `"Aether Launcher"`, `"Aether release notes and runtime advisories"`, `"Aether Runtime Safety & Modular Core"`, `"Aether Community & Discord"`; `CommunityScreen.kt`: `"Updates for Aether Launcher & Minecraft Java on Android"`, `"Aether Visual Redesign & Runtime Recovery"`; `EnginePreferencesScreen.kt`: `"Native Aether configuration..."`, `"Open Aether Runtime Manager"`; `MicrosoftAuthScreen.kt`: `"Aether OAuth2 & Xbox Live bridge"`; `SettingsScreen.kt`: `"Disables decorative waveform motion..."`, About/Diagnostics labels | **REBRAND** every user-facing string from `Aether` / `Aether Launcher` to `STAR` / `STAR Launcher`; update reduced-motion copy (`"Disables decorative star-trail motion and stage transitions"`); add clean STAR branding & slogan in About/Settings header/footer without adding unrequested features. |
| **Dashboard & Navigation** (`ui/dashboard/`, `ui/navigation/`) | `AetherDashboardActivity.kt`, `AetherDashboardDialogs.kt`, `AetherDashboardViewModel.kt`, `DiscordLinkHelper.kt`, `AetherNavRoutes.kt` | Activity class `AetherDashboardActivity`, `"aether_initial_route"`, `"aether_nav_back_stack"`, `"aether_nav_initialized"`, KDoc/comments | Keep class/package names intact (safe internal identifiers) to preserve AndroidManifest/Java bridge stability; update any user-visible status messages if present. |
| **Core, DB, Security & Compat** (`core/`, `data/`, `compat/`, `account/`, `instance/`, `version/`, `download/`) | `AetherContainer.kt`, `AetherSettingsRepository.kt`, `AetherStorageManager.kt`, `AetherDatabase.kt`, `AetherConverters.kt`, `AetherDaos.kt`, `CoreCatalogEntities.kt`, `InstanceDiagnosticsEntities.kt`, `DbEnums.kt`, `TokenVault.kt`, `AccountRepository.kt`, `CompatibilityManager.kt`, `DeviceCapabilityProbe.kt`, `InstanceContentManager.kt`, `InstanceManager.kt`, `VersionCatalogService.kt`, `VersionInstallCoordinator.kt`, `VersionManager.kt`, `VersionModels.kt`, `DownloadManager.kt` | `"aether_launcher.db"`, `"aether_instances"`, `"aether_downloads"`, `"aether_spool"`, `"aether_vault"`, `"aether_token_vault_v1"`, `"CN=AetherTokenVault"`, `"AetherLauncher/1.0"` User-Agent, `".aetherpart"`, `VersionInstallCoordinator.kt` L205 diagnostic string | **PRESERVE** storage folder names, DB filename, and Keystore alias to guarantee **100% backward compatibility** (zero data/token/instance loss). Update HTTP User-Agent `"AetherLauncher/1.0"` -> `"STARLauncher/1.0"` and user-facing diagnostic copy. |
| **Diagnostics** (`diagnostics/`) | `DiagnosticsManager.kt`, `DiagnosticExporter.kt`, `CrossProcessSpoolBridge.kt` | `DiagnosticExporter.kt`: `"aether_exports"`, `"aether-support-<ts>.txt"`, `"=== AETHER LAUNCHER DIAGNOSTIC REPORT ==="` | **REBRAND** report header to `"=== STAR LAUNCHER DIAGNOSTIC REPORT ==="` and export filename to `"star-support-<ts>.txt"` (directory `"star_exports"` or keep `"aether_exports"` with compatibility). |
| **Engine Adapter** (`engine/adapter/`) | `AuthAdapter.kt`, `EngineContracts.kt`, `EnginePreferencesAdapter.kt`, `ModImportAdapter.kt`, `PojavEngineAdapters.kt`, `PojavLaunchBridge.kt` | `AetherLaunchPayload`, `dev.aetherlauncher.extra.*`, KDoc referencing Aether/Pojav | Keep internal contract & adapter behavior intact; update KDoc/comments where appropriate. |

### 3.4 Unit & Robolectric Tests (`21` files under `app_pojavlauncher/src/test/kotlin/dev/aetherlauncher/`)
- All 21 test files (`AccountRepositoryTest`, `CompatibilityManagerTest`, `AetherCoreTest`, `AetherDatabaseTest`, `TokenVaultTest`, `CrossProcessSpoolBridgeTest`, `DiagnosticExporterTest`, `DiagnosticsManagerTest`, `DownloadManagerTest`, `EngineAdapterMappingTest`, `PojavLaunchBridgeTest`, `InstanceContentManagerTest`, `InstanceManagerTest`, `RuntimeSelectionTest`, `AetherDashboardStateTest`, `AetherRedesignTest`, `AetherNavigationRegressionTest`, `ThemeTokensTest`, `VersionCatalogServiceTest`, `VersionManagerRedesignTest`, `VersionManagerTest`) live under `dev.aetherlauncher.*`.
- **Action**: Update assertions only where rebranded output strings/tokens change (e.g., `DiagnosticExporterTest` header check, `ThemeTokensTest` color checks, UI copy checks), and run the full unit test suite to verify zero regressions.

### 3.5 Android Resources (`res/`)
| Resource | Current Content | Proposed Action |
|---|---|---|
| `res/values/strings.xml` | L4 `app_name`=`Aether`; L24 `error_fatal`=`Aether has unexpectedly crashed`; L248 `lazy_service_default_title`=`Aether Foreground Service`; L458 `app_short_name`=`Aether`; L278, L345, L377 `PojavLauncher` / `Pojav Modpack Installer` | **REBRAND** to `STAR Launcher` / `STAR`, `STAR has unexpectedly crashed`, `STAR Foreground Service`, and replace residual `PojavLauncher` / `Pojav Modpack Installer` user-facing copy with `STAR Launcher` / `STAR Modpack Installer`. |
| **42** localized `res/values-*/strings.xml` with `Aether` + **28** localized `strings.xml` with `Pojav` | Localized `error_fatal`, `lazy_service_default_title`, `storage_required`, `modpack_install_notification_title`, `notification_permission_dialog_text` | **REBRAND** `Aether` -> `STAR` and `PojavLauncher` / `Pojav` -> `STAR Launcher` / `STAR` across all localized `strings.xml` files. |
| `res/layout/activity_pojav_launcher.xml` | L20 `@+id/aether_hub_button`, L24 `contentDescription="Aether Hub"` | **REBRAND** `contentDescription="STAR Hub"` (keep or cleanly update view ID `@+id/star_hub_button` in layout + `LauncherActivity.java`). |
| `res/layout/item_simple_list_1.xml` | L23 `tools:text="PojavLauncher version: Foxglove"` | **REBRAND** design-time preview text to `tools:text="STAR Launcher"`. |
| `res/values/colors.xml` | L6 `minebutton_color`=`#9649b8` (purple), L7–11 `#181818` / `#242424` | **REBRAND** legacy activity button/bar accents from purple `#9649b8` / neutral gray to STAR deep navy `#090B11` & electric blue/cyan `#38BDF8` / `#1D6FE8`. |

### 3.6 Visual & Iconography Assets
| Asset | Current State | Proposed Action |
|---|---|---|
| `res/mipmap-anydpi-v26/ic_launcher.xml` & `ic_launcher_round.xml` | Adaptive icon pointing to `ic_launcher_background` + `ic_launcher_foreground` | **REPLACE** with STAR adaptive icon: deep navy/charcoal background (`#090B11`) + shooting-star geometric foreground derived from the user's provided logo. |
| `res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher*.webp` (20 files) | Existing launcher icon bitmaps | **REGENERATE** all density buckets (`mdpi`..`xxxhdpi`) from the user-provided shooting-star emblem (square, round, foreground, background). |
| `res/values/ic_launcher_background.xml` | `#FFFFFF` | **UPDATE** to deep navy/charcoal `#090B11` so the shooting-star mark has high contrast and crisp adaptive masking. |
| `res/drawable/ic_launcher_monochrome.webp` | Existing monochrome icon | **REGENERATE** clean monochrome shooting-star silhouette for Android 13+ themed icons. |
| `res/drawable/notif_icon.png` | Upstream notification small icon | **REPLACE** with crisp white-on-transparent shooting-star silhouette for foreground service & download notifications. |
| `res/drawable/ic_pojav_full.webp` | Upstream Pojav logo used as `"default"` profile icon (`ProfileIconCache.java:85`) | **REPLACE** with STAR emblem bitmap/vector (or keep fallback only where legally/technically needed). |
| `src/main/assets/amethyst.png` | Upstream Amethyst logo referenced by `README.md` | **ADD** `assets/star_logo.png` (and update `README.md` image link); keep `amethyst.png` only if referenced by any historical/upstream asset, otherwise replace README reference. |
| `src/main/assets/about_en.txt` | Upstream PojavLauncher About text (`PojavLauncher`, GPLv3 link, Crowdin) | **REBRAND** product header to STAR Launcher while **preserving** upstream PojavLauncher/Amethyst/Boardwalk & third-party license credits. |

### 3.7 Documentation & GitHub Templates (`13` files)
| File | References Found | Proposed Action |
|---|---|---|
| `README.md` | Full upstream Angel Aura Amethyst README (`Amethyst-Android`, `amethyst.png`, `AngelAuraMC` badges/links) | **REBRAND** top-level README to **STAR Launcher** (*"Your Minecraft. Your Universe."*), feature overview, build guide, and credits **preserving** Amethyst-Android, PojavLauncher, Boardwalk, and all upstream/library license attributions. |
| `dataremoval.md` | `Angel Aura Amethyst` account removal instructions | **REBRAND** to STAR Launcher account removal instructions. |
| `docs/ARCHITECTURE.md` | `Aether Launcher`, `Aether UI/Core/Database` | **UPDATE** to STAR Launcher branding while noting upstream `Amethyst-Android` engine foundation and `dev.aetherlauncher` legacy package/DB notes if retained. |
| `docs/BUILD_GUIDE.md` | `D:\Aether Launcher`, `D:\AetherLauncher` junction | **UPDATE** path/build notes accurately for STAR Launcher. |
| `docs/COMPATIBILITY_STRATEGY.md` | `Aether **MUST** store...` | **UPDATE** to STAR Launcher & STAR Lab Compatibility Passport alignment. |
| `docs/DATABASE_SCHEMA.md` | `# Aether Database Schema`, `AetherDatabase` | **UPDATE** prose to STAR Launcher while accurately documenting actual `AetherDatabase` / `aether_launcher.db` schema symbol names if preserved. |
| `docs/LICENSE_AUDIT.md` | `Aether Launcher`, `d:\Aether Launcher\LICENSE` | **UPDATE** project name to STAR Launcher; **preserve all** upstream LGPLv3 / GPLv3 / MIT / BSD / Apache license findings verbatim. |
| `docs/TASK_TRACKER.md` | M0.1–M3.4 Aether milestones | **UPDATE** header/status notes with STAR rebranding milestone while preserving accurate historical M0–M3 records. |
| `docs/TESTING_STRATEGY.md` | `Aether Launcher`, `AetherDatabaseTest` | **UPDATE** prose to STAR Launcher. |
| `docs/UPSTREAM_MAP.md` | `Aether Disposition`, `Amethyst-Android` upstream map | **UPDATE** column/prose to STAR Launcher while preserving Amethyst-Android upstream attribution. |
| `docs/ZALITH_REFERENCE.md` | `Aether Launcher Plan`, `Aether adaptation` | **UPDATE** prose to STAR Launcher while preserving GPLv3 non-copying rule and Zalith/Amethyst attribution. |
| `.github/ISSUE_TEMPLATE/bug_report.yml` | References `org.angelauramc.amethyst`, `Amethyst`, `Amethyst version` | **REBRAND** paths & labels to STAR Launcher (`com.star.launcher` / current `applicationId` & `latestlog.txt` path). |
| `.github/ISSUE_TEMPLATE/feature_request.yml` | Generic | Review & keep clean for STAR Launcher. |
| `.github/FUNDING.yml` | `patreon: angelauramc` | **REMOVE or CLEAR** upstream Patreon funding handle so STAR does not solicit under upstream's handle. |
| `.github/workflows/android.yml` | `AngelAuraMC/angelauramc-openjdk-build` JRE artifact download | **KEEP** JRE artifact source (required build dependency unless mirrored), update artifact/workflow naming if desired. |

---

## 4. "Aether Lab → STAR Lab" Audit

- **Current Codebase Status**: Neither `Aether Lab`, `Compatibility Passport`, `Aether Doctor`, nor `Aether Pulse` exists in `app_pojavlauncher/` or `docs/` yet.
- **Existing Related Capabilities**:
  - `DeviceCapabilityProbe` + `CompatibilityEvaluator` + `CompatibilityManager` -> matches the *Compatibility Passport* domain.
  - `DiagnosticsManager` + `DiagnosticExporter` -> matches the *STAR Doctor* domain.
  - `CrossProcessSpoolBridge` + `GameSessions` telemetry -> matches the *STAR Pulse* domain.
- **Proposed Action**:
  - Update existing hardware/compatibility/diagnostics section titles and documentation terminology cleanly to **STAR Lab** (e.g., Settings "Hardware & Compatibility" -> **STAR Lab · Compatibility & Diagnostics**, referencing *Compatibility Passport*, *STAR Doctor*, and *STAR Pulse* in UI subtitles and `docs/`), **without inventing or implementing new unrequested features**.
  - If an external *Aether Lab PRD* exists outside `D:\what\AetherLauncher`, request its path from the user before Phase E documentation updates.

---

## 5. GitHub Repository Audit

- Current Git remote: `origin -> https://github.com/AngelAuraMC/Amethyst-Android.git`
- Current branch: `v3_openjdk` (with uncommitted local changes; no `Aether-Launcher` remote configured).
- `gh` CLI: Not installed.
- **Action for Phase F**: Do **not** touch `AngelAuraMC/Amethyst-Android.git`. Ask the user to confirm their target private GitHub repository (`Aether-Launcher` vs `STAR-Launcher`) and authentication method before any remote/repo migration.

---

## 6. Proposed Migration Plan & Approval Gates

| Phase | Scope | Destructive / Breaking Risk | Approval Required? |
|---|---|---|---|
| **Phase A** (Done) | Full repository audit + `docs/STAR_REBRANDING_AUDIT.md` | None (read-only + audit doc) | Complete |
| **Phase B** | Review audit findings & confirm open identity/repo decisions | None | **Yes — Decision Gate** |
| **Phase C** | Visual & UI rebranding: generate STAR app icons/notification icon/Compose `StarBrandMark` from user's uploaded logo; update theme palette (`#090B11` + electric blue/cyan `#38BDF8`/`#5EE2FF`); rebrand all UI strings (`HomeScreen`, `CommunityScreen`, `SettingsScreen`, `EnginePreferencesScreen`, `MicrosoftAuthScreen`, `MainMenuFragment`, `strings.xml` across all locales, `PojavApplication`, `DiagnosticExporter`); apply STAR Lab naming where existing compat/diag UI/docs exist | Low (UI & resources only; zero data/engine changes) | Proceed after Phase B sign-off |
| **Phase D** | Application identity migration (`applicationId`: `dev.aetherlauncher.app` -> `com.star.launcher`, provider authorities `.scoped.*`, optional safe DB/storage migration) | **Medium/High** (changes Android app package ID & `/Android/data/` sandbox) | **Explicit Approval Required** |
| **Phase E** | Documentation & `.github/` migration (`README.md`, `dataremoval.md`, `docs/*.md`, `.github/ISSUE_TEMPLATE/*`, `.github/FUNDING.yml`, preserving all upstream Amethyst/Pojav/Boardwalk & license attributions) | Low | Proceed after Phase B sign-off |
| **Phase F** | GitHub repository / remote migration (keeping repo **PRIVATE**, no force-push, no history rewrite) | High (external GitHub repo/remote) | **Explicit Approval Required** |
| **Phase G** | Build `:app_pojavlauncher:assembleDebug`, run unit/Robolectric tests, verify manifest/icons/resources/navigation/Minecraft pipeline, scan for residual Aether branding, produce `docs/STAR_REBRANDING_REPORT.md` | None | Final verification |


---

## 7. Approved Phase D — Permanent Identity Migration Inventory (`com.star.launcher`)

> **Approval Status**: Approved by user on 2026-11-15  
> **Target Identity**: `applicationId = com.star.launcher`, Kotlin package root `com.star.launcher`, provider authorities `com.star.launcher.scoped.*`, display name `STAR Launcher` (`STAR` short name).

### 7.1 Classification of Every `dev.aetherlauncher` & Identity Reference

| Classification | Target / Files | Action | Rationale |
|---|---|---|---|
| **Gradle `applicationId` & `resValue`s** | `app_pojavlauncher/build.gradle` (L106, L128–132, L152–156) | **MIGRATE** to `com.star.launcher` (`.debug`), `STAR Launcher` / `STAR`, `com.star.launcher.scoped.gamefolder`, `com.star.launcher.scoped.controlfolder` | Establishes permanent Android identity and isolated provider authorities. |
| **Gradle `namespace`** | `app_pojavlauncher/build.gradle` (L79), `androidnsbypass/build.gradle.kts` (L6) | **PRESERVE** (`net.kdt.pojavlaunch`, `dev.alexytomi.androidnsbypass`) | Required by JNI native libs (`libpojavexec.so`, `libpojavexec_awt.so`) and upstream `R`/`BuildConfig` bindings. |
| **Manifest Activity & Provider** | `app_pojavlauncher/src/main/AndroidManifest.xml` (L66, L128) | **MIGRATE** L66 to `com.star.launcher.ui.dashboard.AetherDashboardActivity`; L128 uses `@string/storageProviderAuthorities` | Keeps launcher activity registration and `FolderProvider` authority aligned with `com.star.launcher`. |
| **Preference Intent Target** | `app_pojavlauncher/src/main/res/xml/pref_control.xml` (L13) | **AUTO-MIGRATED** via `@string/application_package` | Resolves dynamically to `com.star.launcher` / `com.star.launcher.debug`. |
| **WorkManager / FileProvider / Firebase / Deep Links** | Entire repository | **NONE PRESENT** (verified) | No WorkManager, AndroidX FileProvider, Firebase (`google-services.json`), or custom URI scheme exists; `amethyst://` is an internal profile path prefix and stays untouched. |
| **Java Integration Files (`9` files)** | `ExitActivity.java`, `FatalErrorActivity.java`, `LauncherActivity.java`, `MainActivity.java`, `NewJREUtil.java`, `TestStorageActivity.java`, `Tools.java`, `ContextAwareDoneListener.java`, `NotificationDownloadListener.java` | **MIGRATE** `dev.aetherlauncher.*` imports/FQCNs to `com.star.launcher.*` | Keeps `:launcher`, `:game`, crash recovery, and notification routing wired to the migrated Kotlin packages. |
| **Kotlin Main Package Tree (`58` files)** | `app_pojavlauncher/src/main/kotlin/dev/aetherlauncher/**` -> `.../com/star/launcher/**` | **MIGRATE** directory path, `package` headers, and `import` statements | Moves all Kotlin modules cleanly to `com.star.launcher.*`. |
| **Cross-Process Intent Extras** | `PojavLaunchBridge.kt` (L29–40) | **MIGRATE** `"dev.aetherlauncher.extra.*"` -> `"com.star.launcher.extra.*"` | Internal `:launcher` → `:game` IPC constants within the same APK. |
| **Kotlin Test Package Tree (`21` files)** | `app_pojavlauncher/src/test/kotlin/dev/aetherlauncher/**` -> `.../com/star/launcher/**` | **MIGRATE** directory path, `package`/`import`, and test fixture package strings (`AetherDatabaseTest.kt:79`, `PojavLaunchBridgeTest.kt:29,69`, `RuntimeSelectionTest.kt:280,290`) | Keeps all 21 test suites aligned with `com.star.launcher`. |
| **Room Schema Export (`1` file)** | `app_pojavlauncher/schemas/dev.aetherlauncher.data.db.AetherDatabase/1.json` -> `.../com.star.launcher.data.db.AetherDatabase/1.json` | **MOVE** schema directory to match new package | Preserves identical Room `version = 1` tables, columns, indexes, and `identityHash`. |
| **Persistence, Storage & Vault Keys** | `AetherDatabase.kt` (`"aether_launcher.db"`), `AetherStorageManager.kt` (`"aether_instances"`, `"aether_downloads"`, `"aether_spool"`), `TokenVault.kt` (`"aether_vault"`, `"aether_token_vault_v1"`, `"CN=AetherTokenVault"`), `MinecraftDownloader.java` / `VersionManager.kt` (`".aetherpart"`) | **PRESERVE** verbatim | Guarantees zero loss or corruption of existing databases, instances, worlds, downloads, and Keystore-encrypted tokens. |
| **Git Remote** | `.git/config` (`origin -> AngelAuraMC/Amethyst-Android.git`) | **PRESERVE** | Explicitly requested by user. |
