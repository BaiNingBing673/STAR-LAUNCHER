# Phase 0 Task Tracker (`M0.1–M0.7`)

> Tracks every Phase 0 milestone with objective, files, dependencies, implementation steps, tests, acceptance criteria, rollback procedure, and verification status.
>
> [!NOTE]
> **Historical Phase 0–3 Record**: This document preserves the historical Phase 0 implementation log created during the earlier Aether/STAR transition (prior to finalizing the `com.star.launcher` permanent identity and STAR Launcher branding cleanup). See [`docs/STAR_BRANDING_CLEANUP_REPORT.md`](STAR_BRANDING_CLEANUP_REPORT.md) for the current audit and cleanup state.

---

## Status Summary

| Milestone | Title | Owner Role | Status | Verification |
|---|---|---|---|---|
| **M0.1** | Repository audit | Architect, Native, Graphics, License Reviewer | **COMPLETED** | Verified against commit `166af615` + baseline `gradlew` run |
| **M0.2** | Architecture documentation | Architect + All Reviewers | **COMPLETED** | 10 documents authored in `docs/` |
| **M0.3** | STAR application identity | Android Engineer | **COMPLETED** | Verified `dev.aetherlauncher.app.debug`, `STAR (Debug)`, distinct provider authorities, native `.so`s via `aapt2` |
| **M0.4** | Design system foundation | Android Engineer | **COMPLETED** | `compileDebugKotlin` & `ThemeTokensTest` (2/2) passed cleanly with `minSdk 21` |
| **M0.5** | Database foundation | Android Engineer | **COMPLETED** | Room schema `1.json` exported; `AetherDatabaseTest` (2/2) & `TokenVaultTest` (2/2) passed |
| **M0.6** | Engine adapter interfaces | Architect + Native + Graphics | **COMPLETED** | `EngineAdapterMappingTest` (3/3) passed; upstream `Tools`/`MultiRTUtils`/`CallbackBridge`/`JNI` untouched |
| **M0.7** | Compatibility diagnostics foundation | Graphics + QA + Android | **COMPLETED** | `CompatibilityManagerTest` (5/5) & `DiagnosticsManagerTest` (2/2) passed; debug APK assembled cleanly |

---

## Phase 1–3 Status Summary

| Milestone | Title | Scope & Deliverables | Status | Verification |
|---|---|---|---|---|
| **M1.1–M1.5** | Core Integration & Dashboard Hub | `AetherStorageManager`, `AetherSettingsRepository`, `AetherContainer`, `VersionManager`, `DownloadManager`, `InstanceManager`, `CrossProcessSpoolBridge`, WAL flush hook, `AetherDashboardActivity` | **COMPLETED** | Built cleanly (`app_pojavlauncher-debug.apk`), unit & Robolectric suites verified (`26/26` in Phase 1) |
| **M2.1–M2.4** | Launch Handoff, Account Vault & Compose Workflows | `PojavLaunchBridge`, `Tools.getGameDirPath` absolute guard, `ContextAwareDoneListener` extras + `MainActivity` transient profile/RAM/spool hook, `AccountRepository` + Keystore `TokenVault`, 5-tab Compose UI + `CreateInstanceDialog` / `InstanceSettingsDialog` | **COMPLETED** | `BUILD SUCCESSFUL in 56s`, `32/32` unit tests across 14 suites |
| **M3.1–M3.4** | Version Catalog, Content Manager & Diagnostic Exporter | `VersionCatalogService` (Mojang v2 + Fabric/Quilt profile installer), `InstanceContentManager` (mod/pack/world inspector + `.jar.disabled` toggle), `DiagnosticExporter` (redacted support report), UI & tracker updates | **COMPLETED** | Verified with `VersionCatalogServiceTest`, `InstanceContentManagerTest`, `DiagnosticExporterTest` |

---

## Detailed Milestone Breakdown

### M0.1 — Repository Audit
- **Objective**: Inspect repository structure, Git state/submodules, Gradle scripts, JNI/C/C++ engine, renderers, input pipeline, dependencies, licenses, and run the baseline Gradle build.
- **Files Involved**: Root config (`settings.gradle`, `build.gradle`, `gradle.properties`, `.gitmodules`, `LICENSE`, `README.md`), `app_pojavlauncher/build.gradle`, `AndroidManifest.xml`, `jni/Android.mk`, `libs/*`, `assets/*`, subprojects (`jre_lwjgl3glfw`, `forge_installer`, `arc_dns_injector`, `MioLibPatcher`, `androidnsbypass`), and isolated reference `ZalithLauncher2`.
- **Dependencies**: None.
- **Implementation Steps**: Read-only inspection + `.\gradlew.bat --version` + `.\gradlew.bat :app_pojavlauncher:assembleDebug`.
- **Tests**: Baseline build probe executed; first meaningful error captured (`:MioLibPatcher:compileJava` missing JDK 8 toolchain).
- **Acceptance Criteria**: All 10 audit points in Section 1–2 answered from direct file inspection; baseline build output recorded verbatim.
- **Rollback Procedure**: Read-only audit; no repo code modified.

### M0.2 — Architecture Documentation
- **Objective**: Produce all 10 Phase 0 planning and architecture documents under `docs/`.
- **Files Involved**: `docs/ARCHITECTURE.md`, `UPSTREAM_MAP.md`, `ZALITH_REFERENCE.md`, `ENGINE_DEPENDENCY_MAP.md`, `LICENSE_AUDIT.md`, `COMPATIBILITY_STRATEGY.md`, `DATABASE_SCHEMA.md`, `BUILD_GUIDE.md`, `TESTING_STRATEGY.md`, `TASK_TRACKER.md`.
- **Dependencies**: `M0.1`.
- **Implementation Steps**: Author documents grounded in audited files, Room entity specifications, compatibility rules, and milestone plans.
- **Tests**: Cross-check all cited paths, symbols, dependencies, and build outputs against repo files.
- **Acceptance Criteria**: All 10 files exist under `docs/` with consistent diagrams, schemas, and traceability.
- **Rollback Procedure**: `git clean -fd docs/` (or delete `docs/`).

### M0.3 — STAR Application Identity
- **Objective**: Establish STAR Launcher app identity (`applicationId`, app name, icons/branding strings, non-conflicting `FileProvider`/`DocumentsProvider` authorities, Gradle local environment baseline) while **preserving** `namespace 'net.kdt.pojavlaunch'` and all JNI symbol signatures.
- **Files Involved**: `app_pojavlauncher/build.gradle`, `app_pojavlauncher/src/main/res/values*/strings.xml`, `AndroidManifest.xml` (authorities/labels only), `gradle.properties` / local JDK 8+21 environment setup.
- **Dependencies**: `M0.1`, `M0.2`, local JDK 8 + JDK 21 + CMake `3.22.1` + spaceless path junction.
- **Implementation Steps**:
  1. Fix build environment (JDK 8 + JDK 21 + CMake 3.22.1) and verify baseline `:app_pojavlauncher:assembleDebug` succeeds **before** branding edits; archive baseline APK.
  2. Update `applicationId` to STAR ID (e.g., `dev.aetherlauncher.app`), debug suffix, `app_name`, `storageProviderAuthorities`, `shareProviderAuthority`.
  3. Keep `namespace 'net.kdt.pojavlaunch'` and package names of JNI-bound classes (`PojavApplication`, `MainActivity`, `JREUtils`, `Tools`, `CallbackBridge`, `VMLauncher`) untouched.
- **Tests**: Clean `:app_pojavlauncher:assembleDebug`; verify `libpojavexec.so` JNI symbol lookup and APK install side-by-side with Amethyst.
- **Acceptance Criteria**: Debug APK builds cleanly, installs under STAR applicationId, reaches `LauncherActivity`, and passes smoke launch check.
- **Rollback Procedure**: `git checkout -- app_pojavlauncher/build.gradle app_pojavlauncher/src/main/res/ app_pojavlauncher/src/main/AndroidManifest.xml`.

### M0.4 — Design System Foundation
- **Objective**: Enable Kotlin + Jetpack Compose + Material 3 inside `:app_pojavlauncher` source sets (without deleting existing XML/Fragment UI) and establish STAR color, typography, spacing, and status badge tokens.
- **Files Involved**: `build.gradle`, `app_pojavlauncher/build.gradle`, new `app_pojavlauncher/src/main/kotlin/com/star/launcher/ui/theme/*` (`Color.kt`, `Type.kt`, `Theme.kt`, `Components.kt`).
- **Dependencies**: `M0.3`.
- **Implementation Steps**:
  1. Add Kotlin Android & Compose compiler plugin compatible with AGP 9.3.1 / Gradle 9.6.1 (`android.builtInKotlin` check).
  2. Add Compose BOM + Material 3 dependencies.
  3. Implement `AetherTheme` and reusable status chips (for `DETECTED`, `THEORETICAL`, `TESTED`, `VERIFIED_RUNTIME` badges).
- **Tests**: Compile Kotlin + Java mixed sources (`:app_pojavlauncher:compileDebugKotlin`); Compose UI unit/render smoke test.
- **Acceptance Criteria**: Mixed Java/Kotlin + Compose compiles cleanly alongside existing XML activities without breaking `nonTransitiveRClass=false` or NDK build.
- **Rollback Procedure**: Revert `build.gradle` changes and remove `src/main/kotlin/com/star/launcher/ui/theme/`.

### M0.5 — Database Foundation
- **Objective**: Implement Room `AetherDatabase` (`version = 1`, `exportSchema = true`), all 10 entities, type converters, DAOs with `@Transaction`, and an Android Keystore-backed `TokenVault` interface (no plaintext tokens, no migration/overwrite of legacy `accounts/*.json` or `launcher_profiles.json` yet).
- **Files Involved**: `app_pojavlauncher/build.gradle` (Room + KSP), `app_pojavlauncher/src/main/kotlin/com/star/launcher/data/db/*` (entities, DAOs, converters, `AetherDatabase`), `app_pojavlauncher/src/main/kotlin/com/star/launcher/data/security/TokenVault.kt`, `app_pojavlauncher/schemas/`.
- **Dependencies**: `M0.4`.
- **Implementation Steps**:
  1. Add Room (`room-runtime`, `room-ktx`, `room-compiler` via KSP, `room-testing`) and `security-crypto` / Tink dependencies.
  2. Implement the 10 entities with exact FKs and indexes from `DATABASE_SCHEMA.md`.
  3. Enable WAL mode and schema export; add `TokenVault` using Android Keystore AES-GCM.
- **Tests**: `AetherDatabaseTest` (in-memory/instrumentation CRUD, FK cascades, uniqueness constraints, transactional rollback) + `TokenVaultTest` verifying ciphertext storage.
- **Acceptance Criteria**: All Room schema exports cleanly with zero Room compiler warnings; tests pass; zero legacy user files touched.
- **Rollback Procedure**: Remove `data/db/` and `data/security/`, revert Room/KSP Gradle additions.

### M0.6 — Engine Adapter Interfaces
- **Objective**: Define clean Kotlin interfaces (`NativeEngineAdapter`, `RuntimeFacade`, `RendererFacade`, `InputManager`, `SessionBridge`) wrapping `Tools`, `JREUtils`, `MultiRTUtils`, `NewJREUtil`, `GLInfoUtils`, `TurnipUtils`, and `CallbackBridge` without modifying C/C++ or JNI signatures.
- **Files Involved**: `app_pojavlauncher/src/main/kotlin/com/star/launcher/engine/adapter/*`.
- **Dependencies**: `M0.5`.
- **Implementation Steps**:
  1. Define immutable data contracts (`EngineLaunchSpec`, `RuntimeDescriptor`, `RendererSpec`, `InputProfileSpec`, `EngineSessionEvent`).
  2. Implement thin Kotlin/Java adapter delegates calling existing `net.kdt.pojavlaunch.*` methods.
  3. Ensure `:game` process boundary invariants (`MainActivity` launch & `latestlog.txt` path) remain preserved.
- **Tests**: Unit tests mocking adapter contracts + mapping verification against `JREUtils.getJavaArgs` / `Tools.getMinecraftClientArgs` expectations.
- **Acceptance Criteria**: Adapter layer compiles cleanly, has zero direct Compose/UI dependencies, and delegates to upstream engine without changing native `.so` behavior.
- **Rollback Procedure**: Delete `engine/adapter/` package; upstream engine remains 100% untouched.

### M0.7 — Compatibility & Diagnostics Foundation
- **Objective**: Implement hardware/driver capability probing (`DeviceCapabilityProbe` -> `DeviceInfo`), four-tier compatibility evaluator (`CompatibilityManager` -> `CompatibilityResults`), and session/log capture pipeline (`DiagnosticsManager` -> `GameSessions`, `DiagnosticLogs` with token redaction).
- **Files Involved**: `app_pojavlauncher/src/main/kotlin/com/star/launcher/compat/*`, `app_pojavlauncher/src/main/kotlin/com/star/launcher/diagnostics/*`.
- **Dependencies**: `M0.5`, `M0.6`.
- **Implementation Steps**:
  1. Implement `DeviceCapabilityProbe` reading CPU ABIs, RAM/32-bit address space, Android API, `GLInfoUtils`, Vulkan support/version, and Adreno KGSL/UBWC flags.
  2. Implement rule evaluator strictly separating `DETECTED`, `THEORETICAL`, `TESTED`, and `VERIFIED_RUNTIME`.
  3. Implement `DiagnosticsManager` capturing session transitions and scrubbing sensitive strings before writing `DiagnosticLogs`.
- **Tests**: Unit tests for compatibility rule evaluation (ARM64 Adreno vs Mali vs x86 32-bit Java 21 block) and log redaction; instrumentation test for `DeviceCapabilityProbe`.
- **Acceptance Criteria**: Probe populates `DeviceInfo` accurately; evaluator outputs deterministic tiered `CompatibilityResults`; log scrubber removes OAuth/session tokens in all test cases.
- **Rollback Procedure**: Remove `compat/` and `diagnostics/` packages.
