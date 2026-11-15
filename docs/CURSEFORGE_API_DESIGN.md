# STAR Launcher — CurseForge API & Security Design

> **Base URL**: `https://api.curseforge.com`  
> **Game ID**: `432` (Minecraft Java Edition)  
> **Primary Class ID**: `6` (Mods)

---

## 1. Approved Integration Model & API Key Security

CurseForge's Core API Terms of Service prohibit distributing production Studio/App API keys inside open-source client binaries or Android APKs where keys can be extracted via static analysis (`strings`, `aapt`, `apktool`, memory dumps).

Accordingly, STAR Launcher **never** embeds a CurseForge API key in Kotlin/Java source code, `res/values`, `BuildConfig`, or the distributed APK, and **never** reads legacy `R.string.curseforge_api_key`.

### Supported Credential Modes (`CurseForgeAuthConfig`)

| Mode | How It Works | Production Suitability |
| --- | --- | --- |
| **1. Authenticated Backend Proxy** *(Recommended for release builds)* | App calls a configured STAR-operated proxy base URL (`https://...`) using a narrow REST contract and short-lived client/session bearer token. The proxy holds `CURSEFORGE_API_KEY` server-side, enforces rate limits, and forwards `x-api-key` only for allowlisted endpoints. | Production-approved model once backend proxy + CurseForge Studio key are provisioned. |
| **2. Developer / Self-Hosted Vault Key** *(Local testing & power users)* | User or developer supplies their own CurseForge Core API key at runtime via the in-app **API Configuration** dialog. Stored encrypted in `TokenVault` (`AndroidKeystoreTokenVault`, AES-256-GCM in `noBackupFilesDir`), never in Room, `SharedPreferences`, or logs. | Suitable for developer testing and self-hosted builds without bundling secrets in APKs. |
| **3. Unconfigured** *(Default out-of-the-box state)* | No proxy URL or Vault key is configured. Discover clearly displays a **Configuration Required** state explaining the credential model and offering direct setup; **My Mods** (local instance inventory, enable/disable, remove, local metadata inspection) remains fully functional. | Safe default; never fakes results or downloads. |

### Narrow Backend Proxy Contract

If deploying a STAR backend proxy, expose only the following allowlisted read-only endpoints (rejecting arbitrary path forwarding and stripping client-supplied `x-api-key`):

| Proxy Route | Upstream CurseForge Route | Allowed Query Params |
| --- | --- | --- |
| `GET /cf/v1/mods/search` | `GET /v1/mods/search` | `gameId=432`, `classId=6`, `categoryId`, `gameVersion`, `searchFilter`, `sortField`, `sortOrder`, `modLoaderType`, `index`, `pageSize` (capped `<= 50`) |
| `GET /cf/v1/mods/{modId}` | `GET /v1/mods/{modId}` | — |
| `GET /cf/v1/mods/{modId}/description` | `GET /v1/mods/{modId}/description` | — |
| `GET /cf/v1/mods/{modId}/files` | `GET /v1/mods/{modId}/files` | `gameVersion`, `modLoaderType`, `index`, `pageSize` (capped `<= 50`) |
| `GET /cf/v1/mods/{modId}/files/{fileId}` | `GET /v1/mods/{modId}/files/{fileId}` | — |
| `GET /cf/v1/mods/{modId}/files/{fileId}/download-url` | `GET /v1/mods/{modId}/files/{fileId}/download-url` | — |
| `GET /cf/v1/categories` | `GET /v1/categories` | `gameId=432`, `classId=6` |
| `GET /cf/v1/minecraft/version` | `GET /v1/minecraft/version` | — |
| `GET /cf/v1/minecraft/modloader` | `GET /v1/minecraft/modloader` | `version`, `includeAll` |

Proxy security requirements:
- Require client authentication (e.g., short-lived signed app/device attestation or user token, not an open unauthenticated relay).
- Enforce per-IP and per-client rate limiting and response caching for categories/versions/search.
- Never echo `x-api-key` in upstream error payloads or headers.

### Redaction Rules

- `SensitiveLogRedactor` and `CurseForgeHttpClient` scrub `x-api-key`, `Authorization`, and `apiKey` query/header fragments from all exceptions, logs, and Room diagnostic entries.

---

## 2. Official CurseForge Endpoints Integrated

| Endpoint | Purpose in STAR Launcher | Notes |
| --- | --- | --- |
| `GET /v1/mods/search` | Discover search, featured (`sortField=1`), popularity (`sortField=2`), recently updated (`sortField=3`), total downloads (`sortField=6`), name (`sortField=4`), category/MC version/loader filtering, pagination (`index`, `pageSize`) | `gameId=432`, `classId=6` (Mods) |
| `GET /v1/mods/{modId}` | Mod detail metadata (authors, summary, logo, screenshots, downloadCount, links, `allowModDistribution`, `latestFiles`) | Distinguished from STAR-inferred compatibility in UI |
| `GET /v1/mods/{modId}/description` | Full HTML mod description (sanitized to readable plain/structured text) | Optional enrichment on Detail view |
| `GET /v1/mods/{modId}/files` | Paginated file list filtered by `gameVersion` and `modLoaderType` | Exposes `releaseType` (`1=Release, 2=Beta, 3=Alpha`) for accurate file filtering |
| `GET /v1/mods/{modId}/files/{fileId}` | Single file metadata (hashes, `fileLength`, dependencies, `isAvailable`) | Used for update checks and dependency resolution |
| `GET /v1/mods/{modId}/files/{fileId}/download-url` | Official download URL resolution | Strictly honors `allowModDistribution` and `403`/empty URLs without CDN fallback |
| `GET /v1/categories?gameId=432&classId=6` | Dynamic mod category list | Cached in memory per session |
| `GET /v1/minecraft/version` | Official CurseForge Minecraft version list | Populates MC version filter |
| `GET /v1/minecraft/modloader` | Official CurseForge loader version index | Supports loader metadata & validation |

---

## 3. Distribution Restriction Compliance

- If `mod.allowModDistribution == false`, or `file.downloadUrl` is null/blank and `GET /v1/mods/{modId}/files/{fileId}/download-url` returns HTTP `403` / empty `data`:
  - STAR Launcher **refuses** to fabricate `edge.forgecdn.net` or other CDN URLs.
  - The UI marks the file as **Restricted by Author** and offers a button to open the official `websiteUrl` in the user's browser for manual download.

---

## 4. Error Handling & Rate Limiting

`CurseForgeApiError` classifies failures without leaking credentials:
- `NotConfigured`: Missing proxy URL or Vault key.
- `Unauthorized` (HTTP 401/403): Invalid/rejected API key or distribution restricted (for `/download-url`).
- `RateLimited(retryAfterSeconds)` (HTTP 429): Parses `Retry-After` header and surfaces backoff notice.
- `NotFound` (HTTP 404): Mod or file unavailable.
- `NetworkOffline` / `Timeout`: Clean offline/retryable error state.
- `ServerError(code)` (HTTP 5xx) / `ParseError`: Non-crashing structured error card with retry.
