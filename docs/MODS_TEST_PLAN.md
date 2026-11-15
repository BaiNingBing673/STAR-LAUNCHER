# STAR Launcher — CurseForge Mods Integration Test Plan

This document specifies automated and manual verification coverage for the native **Mods** tab (`DashboardTab.MODS`, `AetherRoute.ModDetail`, and `com.star.launcher.mods.*`).

---

## 1. Automated Unit & Robolectric Test Suites

| Test Suite | Target Component(s) | Cases Covered |
|---|---|---|
| `CurseForgeApiAndSecurityTest` | `CurseForgeCredentialProvider`, `CurseForgeRepository`, `SensitiveLogRedactor` | Search without credentials (`NotConfigured`, 0 network calls); valid Keystore Vault key (`x-api-key` + `gameId=432` + `classId=6` + filters); Backend Proxy routing (`/cf/v1/*`, Bearer token, no `x-api-key`); HTTP 429 `Retry-After` parsing; HTTP 401/5xx error mapping without secret leaks; empty search results & pagination (`hasMore`); mod detail, sanitized HTML description, categories, MC versions, mod loaders; `allowModDistribution == false` & HTTP 403 `/download-url` restriction without CDN URL fabrication. |
| `ModCompatibilityAndDepsTest` | `InstanceEnvironmentResolver`, `ModCompatibilityEngine`, `ModDependencyResolver` | Manifest-verified loader & MC version resolution vs `versionId` inference; `COMPATIBLE` only when loader + MC match; MC-only match downgraded to `POTENTIALLY_COMPATIBLE`; incompatible loader & Vanilla instance blocked (`canInstall = false`); `UNKNOWN` on missing environment; BFS dependency planning; optional dependencies excluded from auto-download; cycle detection safeguard; already-installed dependency skip; unresolved required dependency explanation & install block. |
| `ModDownloadAndInventoryTest` | `ModDownloadInstaller`, `JarModMetadataParser`, `InstalledModManager` | SHA-1 mismatch rejection & `.part` cleanup; interrupted download IO failure rollback; user cancellation + staging cleanup; insufficient storage preflight before download; duplicate mod overwrite prevention (`allowOverwrite = false`); wrong-instance directory isolation; `fabric.mod.json` parsing; `.jar` <-> `.jar.disabled` reversible toggle; duplicate + suspicious non-JAR detection; explicit confirmation-only delete; CurseForge update check without auto-install. |
| `AetherDatabaseMigrationTest` | `AetherDatabase`, `AetherDatabase.MIGRATION_1_2` | Upgrades real SQLite schema v1 database preserving existing `game_instances` rows while creating `installed_mods` table, indices, and foreign key cascade. |
| `ModsNavigationAndUiTest` | `AetherRoute`, `AetherDashboardActivity`, `ModsController` | `DashboardTab.MODS` & `AetherRoute.ModDetail` encode/decode and back-stack handling across `recreate()`; `ModsController` initial `UNCONFIGURED` state and live selected-instance filter synchronization. |

---

## 2. Live API Verification Scope (Credential-Gated)

Because no CurseForge API key or backend proxy URL is hardcoded or configured in the build environment:
- Official HTTP transport wiring (`UrlConnectionCurseForgeTransport`), pagination parsing, error mapping, and header construction are verified via automated tests with fake `CurseForgeTransport` / `HttpTransport` doubles.
- End-to-end live requests against `https://api.curseforge.com` remain credential-gated until the developer configures a CurseForge Keystore Vault key or deploys the narrow backend proxy described in `docs/CURSEFORGE_API_DESIGN.md`.

---

## 3. Manual / Device QA Checklist (With Approved Key or Proxy)

1. Open **Mods** tab without credentials -> confirm API Unconfigured card appears with zero fake results.
2. Configure developer key in Keystore Vault (or proxy URL) -> confirm Featured & Browse feeds populate with thumbnails, download counts, and loader tags.
3. Open a mod with third-party distribution disabled -> confirm `Manual Download Only` opens CurseForge browser page instead of fabricating CDN links.
4. Select a Fabric 1.20.1 instance vs a Vanilla/NeoForge instance -> confirm incompatible loaders show `Incompatible` and disable `Install`.
5. Install a mod with required + optional dependencies -> verify plan lists required dependencies cleanly, leaves optional dependencies un-downloaded, displays real byte progress, and places `.jar` inside `<gameDirPath>/mods/`.
6. Toggle mod off/on in **My Mods** -> confirm `.jar.disabled` rename preserves the file; remove only after dialog confirmation.
