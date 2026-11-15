# STAR Launcher — Mods Architecture

> **Package Root**: `com.star.launcher.mods`  
> **UI Integration**: `DashboardTab.MODS`, `AetherRoute.ModDetail`, `ModsScreen`, `ModDetailScreen`

---

## 1. Module Breakdown

```text
com.star.launcher.mods
├── api/
│   ├── CurseForgeModels.kt          # Official CurseForge v1 DTOs & domain models
│   ├── CurseForgeCredentialProvider.kt # TokenVault + Proxy configuration (zero bundled keys)
│   ├── CurseForgeHttpClient.kt      # HttpURLConnection transport with redaction & 429/error mapping
│   └── CurseForgeRepository.kt      # Search, featured, categories, mod details, files, download URLs
├── compat/
│   ├── InstanceEnvironmentResolver.kt # Resolves base MC version + loader + loader version from GameInstanceEntity
│   └── ModCompatibilityEngine.kt    # Evaluates file/dependency compatibility -> Compatible / PotentiallyCompatible / Incompatible / Unknown
├── deps/
│   └── ModDependencyResolver.kt     # BFS resolution of Required vs Optional vs Incompatible deps, cycle detection, installed check
├── download/
│   └── ModDownloadInstaller.kt      # Storage preflight, .part temp staging, progress Flow, cancel/retry, SHA-1/MD5/size verify, safe move
└── inventory/
    ├── JarModMetadataParser.kt      # Reads fabric.mod.json, quilt.mod.json, mods.toml, neoforge.mods.toml, mcmod.info
    └── InstalledModManager.kt       # Instance-scoped mods/ inventory, duplicate/suspicious detection, safe disable/enable, confirm remove, update check
```

---

## 2. Database Integration (`AetherDatabase` v1 -> v2)

`InstalledModEntity` (table `installed_mods`, foreign key to `game_instances.instanceId` with `CASCADE` delete):
- `recordId` (PK, e.g. `"$instanceId:$fileName"`)
- `instanceId` (FK -> `game_instances.instanceId`)
- `fileName` (e.g. `sodium-fabric-0.6.5.jar` or `.jar.disabled`)
- `curseForgeModId` (nullable Long — present when installed via CurseForge)
- `curseForgeFileId` (nullable Long)
- `displayName`
- `installedVersionLabel`
- `mcVersionHint`
- `loaderType` (`ModLoaderType`)
- `sha1` (nullable String)
- `fileSizeBytes` (Long)
- `allowModDistribution` (Boolean?)
- `websiteUrl` (nullable String)
- `installedAt` (Long)

`MIGRATION_1_2` creates `installed_mods` and its indices non-destructively. Schema snapshot `2.json` is exported to `app_pojavlauncher/schemas/`.

---

## 3. Compatibility Engine Rules

Never claims full compatibility solely from Minecraft version match:
1. **Incompatible**:
   - File loader set is known and does NOT match the instance's loader (e.g. Fabric file on Forge instance, or any modded file on Vanilla instance).
   - File MC versions are known and do NOT include the instance's base Minecraft version.
   - An explicit `Incompatible` (`relationType = 5`) dependency is installed in the instance or planned for installation.
   - A `RequiredDependency` (`relationType = 3`) is missing and unresolvable / incompatible.
2. **Compatible**:
   - Instance has a known MC version AND a known mod loader (`FORGE`, `NEOFORGE`, `FABRIC`, `QUILT`), AND the file explicitly declares both the exact MC version and that loader, AND no missing required dependencies or conflicts are detected.
3. **PotentiallyCompatible**:
   - Loader matches and MC version matches major/minor family (e.g., `1.21` vs `1.21.1` when patch-compatible or file lists compatible loader without exact patch), OR file has optional/unverified dependencies, OR instance loader version was inferred rather than manifest-verified.
4. **Unknown**:
   - Instance has no Minecraft version selected, OR file metadata omits gameVersions/loader tags, OR compatibility cannot be verified locally.

All UI surfaces clearly separate:
- **API Metadata**: data returned directly by CurseForge (`gameVersions`, `downloadCount`, `dependencies`, `releaseType`).
- **STAR Analysis**: compatibility verdict, inferred loader/version from filename/JAR manifest, and dependency install plan.

---

## 4. Instance Isolation & File Safety

- All operations operate strictly on `selectedInstance.gameDirPath` (`<gameDirPath>/mods`).
- **Disable mod**: renames `<name>.jar` -> `<name>.jar.disabled` (preserving original JAR bytes and any config files).
- **Enable mod**: renames `<name>.jar.disabled` -> `<name>.jar` (failing safely if target already exists).
- **Duplicate & suspicious detection**: flags multiple JARs sharing the same mod ID (from JAR manifest), duplicate filenames across enabled/disabled states, zero-byte files, non-JAR files disguised in `mods/`, or multiple files mapped to the same `curseForgeModId`.
- **Overwrite prevention**: `ModDownloadInstaller` refuses to silently overwrite an existing file in `mods/` unless the user explicitly confirms replacing/updating that mod.
- **Background execution**: Downloads run on `Dispatchers.IO` with `.part` temp files in `AetherStorageManager.downloadStagingDir()` and are verified (file length + SHA-1/MD5) before atomic move into `mods/`.
