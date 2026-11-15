# ZalithLauncher2 Architectural & UX Reference (`M0.2`)

> **Reference Repository**: `ZalithLauncher/ZalithLauncher2` (`main` @ `24e3528c6b2e1c5db1b25a5c9d22487c8915e785`)  
> **License**: GNU GPLv3 (`LICENSE`) — **Strict Rule**: Do NOT copy Zalith source code into STAR Launcher. This document captures architectural and UX patterns only.

---

## 1. High-Level Stack Comparison

| Dimension | Amethyst-Android (Upstream Engine) | ZalithLauncher2 (Reference Only) | STAR Launcher Plan |
|---|---|---|---|
| **UI Framework** | XML Views + Fragments + `PreferenceFragmentCompat` | Jetpack Compose + Material 3 + `androidx.navigation3` (`NavBackStack`, `NavDisplay`) | Adopt Compose + Material 3 for STAR UI while keeping `:game` surface bridge intact |
| **DI & State** | Static globals (`Tools`, `LauncherPreferences`, `ExtraCore`) | `@HiltAndroidApp` (`ZLApplication`), `ViewModel` + `MutableStateFlow` | Adopt ViewModel + unidirectional `StateFlow`; introduce lightweight DI/AppContainer |
| **Database** | None (flat JSON files + `SharedPreferences`) | Room (`AppDatabase` v2 with `Account`, `AuthServer`, `GamePath` + manual `Migration`) | Adopt Room (`AetherDatabase` with 10 normalized entities, FKs, indexes, `exportSchema = true`) |
| **Credential Storage** | Plaintext JSON (`accounts/<user>.json`) | Plaintext columns in Room (`accounts` table) | **Improve on both**: Store no plaintext tokens in Room or JSON; use Android Keystore-backed encryption |
| **Instance / Path Isolation** | Single `.minecraft` root + `launcher_profiles.json` | Multi-root `GamePathManager` + per-version `VersionsManager` + `VersionConfig` | Adopt isolated instance directories + explicit `GameInstances` / `GameVersions` Room metadata |
| **Task / Download Engine** | `PojavApplication.sExecutorService` + `ProgressLayout` static bars | `TaskSystem` (`CoroutineScope`, `StateFlow<List<Task>>`, `ConcurrentHashMap<Job>`, `TaskKeepAlive` ref-counted foreground service) | Adopt reactive coroutine `Download Manager` + `TaskKeepAlive` style service ref-counting + persisted `DownloadTasks` |
| **Settings System** | `LauncherPreferences` static fields + XML prefs | Typed `SettingsRegistry` + `AbstractSettingUnit<T>` (`boolSetting`, `intSetting`, `enumSetting`, MMKV-backed reactive units) | Adopt typed reactive setting descriptors backed by Room `AppSettings` / DataStore |
| **Plugin & Renderer Extensibility** | Hardcoded `R.array.renderer_values` + `FFmpegPlugin` package query | Modular `game/plugin` (`ApkPluginManager` parsing APK metadata for external drivers, renderers, natives, FFmpeg) | Support internal `RendererProfiles` first, with clean extension points for capability-verified external plugins later |
| **Game Activity** | `MainActivity` (XML `FrameLayout` + `MinecraftGLSurface` + `DrawerLayout`) | `VMActivity` (Compose host wrapping `AndroidView(TextureView)` + Compose overlay menus, mouse, IME, control layers) | **Keep Amethyst's `MainActivity` + `MinecraftGLSurface` in Phase 0–1** to avoid breaking input/SDL3/TouchController/GLES invariants |

---

## 2. Key Architectural Patterns Worth Adopting (Conceptually)

1. **Reactive `TaskSystem` with Reference-Counted Foreground Keep-Alive**:
   - In `ZalithLauncher2` (`coroutine/TaskSystem.kt`), tasks are represented as observable objects in a `StateFlow<List<Task>>`, launched with structured `Job` tracking, deduplicated by `task.id`, and paired with `TaskKeepAlive.acquire()` / `release()` in `invokeOnCompletion` so background downloads survive screen-off without leaking foreground notifications.
   - **STAR adaptation**: Pair this pattern with Room-persisted `DownloadTasks` so interrupted downloads can inspect status, resume/retry, and surface progress in any screen.

2. **Isolated `GamePath` + Per-Version `VersionConfig` Model**:
   - `ZalithLauncher2` (`game/path/GamePathManager.kt`, `game/version/installed/VersionsManager.kt`) separates storage roots (`GamePath`) from installed versions (`Version`, `VersionConfig`), uses a `Mutex` around directory refreshes, and clears stale per-version ViewModel stores (`VersionViewModelStores.clearStale`).
   - **STAR adaptation**: Model `GameInstances` and `GameVersions` explicitly in Room with foreign keys to `RendererProfiles` and `RuntimeRegistry`, avoiding Amethyst's single shared `.minecraft/mods` collision problem.

3. **Typed `SettingsRegistry` Units**:
   - `ZalithLauncher2` (`setting/SettingsRegistry.kt`) declares settings declaratively with bounds (`min..max`), default values, enum legacy name mapping, and reactive UI state.
   - **STAR adaptation**: Define typed setting descriptors synced with Room `AppSettings` and pushed into `Native Engine Adapter` right before launch.

4. **Dedicated Crash & Diagnostic UX (`crashlogs/`)**:
   - Separating launcher crashes (`SplashException` / `ZLApplication`) from game/JVM exit diagnostics with structured log viewers improves supportability over Amethyst's raw `latestlog.txt` share intent.

5. **Multi-Module UI Building Blocks (`LayerController`, `ColorPicker`, `CardGrid`, `InputMap`)**:
   - Clean separation of reusable Compose widgets from game execution logic keeps UI iteration fast without touching JNI/rendering code.

---

## 3. Patterns Explicitly NOT Adopted

- **Do NOT store auth tokens in plaintext in Room**: `ZalithLauncher2`'s `Account.kt` stores `accessToken`, `refreshToken`, and `otherPassword` directly as unencrypted SQLite columns. STAR requires encrypted token storage via Android Keystore.
- **Do NOT replace `MainActivity` / `MinecraftGLSurface` prematurely**: Amethyst's `MainActivity` and `MinecraftGLSurface` contain battle-tested hooks for `SDL3`, `TouchController`, `AndroidPointerCapture` historical batching, `DirectGamepad`, `ControlLayout` swipeable buttons, and `AWT`/IME insets. Replacing that surface layer during Phase 0–1 introduces high regression risk.
- **Do NOT adopt GPLv3-licensed code**: `ZalithLauncher2` is GPLv3 while `Amethyst-Android` root `LICENSE` is LGPLv3 (with mixed upstream components). Reference concepts only; write clean-room STAR Kotlin implementations.
