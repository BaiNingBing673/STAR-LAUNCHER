# Upstream Module Map (`M0.1` / `M0.2`)

> **Repository**: `AngelAuraMC/Amethyst-Android`  
> **Branch**: `v3_openjdk`  
> **Commit**: `166af615dbdca1a507b8c2d379822cca02795132`

---

## 1. Gradle Subprojects (`settings.gradle`)

| Gradle Path | Location | Type | Responsibility | STAR Disposition |
|---|---|---|---|---|
| `:app_pojavlauncher` | `app_pojavlauncher/` | Android Application (`com.android.application`) | Main launcher UI, auth, downloader, profiles, runtime management, game activity (`MainActivity`), input controllers, and JNI bridge (`Android.mk`) | **Retain & Wrap**; integrate STAR Kotlin source-sets alongside existing engine |
| `:jre_lwjgl3glfw` | `jre_lwjgl3glfw/` | Wrapper aggregator project | Registers `:jar` task depending on `lwjgl-3.3.3:jar` and `lwjgl-3.4.1:jar` | **Preserve untouched** |
| `:jre_lwjgl3glfw:lwjgl-3.3.3` | `jre_lwjgl3glfw/lwjgl-3.3.3/` | Java Library (`java`) | Modified LWJGL 3.3.3 GLFW/EGL/OpenGL init + `CallbackBridge` + `PojavRendererInit` merged JAR (`assets/components/lwjgl3/3.3.3/`) | **Preserve untouched** |
| `:jre_lwjgl3glfw:lwjgl-3.4.1` | `jre_lwjgl3glfw/lwjgl-3.4.1/` | Java Library (`java`) | Modified LWJGL 3.4.1 GLFW + SDL3 mouse/init hooks + `CallbackBridge` merged JAR (`assets/components/lwjgl3/3.4.1/`) | **Preserve untouched** |
| `:arc_dns_injector` | `arc_dns_injector/` | Java Library (`java-library`, Java 8) | JVM `-javaagent` patching `InetAddress` DNS resolution on Android (`assets/components/arc_dns_injector/`) | **Preserve untouched** |
| `:forge_installer` | `forge_installer/` | Java Library (`java-library`, Java 8) | Headless Forge GUI installer automation agent (`assets/components/forge_installer/`) | **Preserve untouched** |
| `:MioLibPatcher` | `MioLibPatcher/` (Submodule) | Java Library (`java`, Java 8 toolchain) | Javassist `-javaagent` runtime bytecode patcher for Sodium/Embeddium, OSHI, SQLite, Narrator, VulkanMod, ImGui, ASM (`assets/components/MioLibPatcher/`) | **Preserve untouched** (requires JDK 8 toolchain on build host) |
| `:androidnsbypass` | `androidnsbypass/` (Submodule) | Android Library (`com.android.library`, CMake 3.22.1) | Android linker namespace bypass + BTI trampoline + ELF `DT_SONAME` patcher (`libandroidnsbypass.so`, prefab published) | **Preserve untouched** |

---

## 2. Java Package Map inside `:app_pojavlauncher` (`411` source files total across app)

| Package | Key Classes | Responsibility | Migration Boundary |
|---|---|---|---|
| `net.kdt.pojavlaunch` | `PojavApplication`, `TestStorageActivity`, `LauncherActivity`, `MainActivity`, `JavaGUILauncherActivity`, `MinecraftGLSurface`, `Tools`, `Architecture`, `NewJREUtil`, `Logger` | App bootstrap, activity lifecycle, surface hosting, launch argument construction, architecture detection, JRE bootstrap | **Core engine** (`Tools`, `MainActivity`, `Architecture`) wrapped via `Native Engine Adapter`; `LauncherActivity` superseded by STAR UI in later phases |
| `net.kdt.pojavlaunch.utils` | `JREUtils`, `GLInfoUtils`, `TurnipUtils`, `TouchControllerUtils`, `TouchControllerInputView`, `MCOptionUtils`, `DownloadUtils`, `FileUtils`, `LocaleUtils`, `DateUtils` | JNI declarations, JVM env/launch, EGL GPU probing, Turnip Adreno config, TouchController Unix socket proxy, `options.txt` parser | **Wrap** `JREUtils`, `GLInfoUtils`, `TurnipUtils`; replace `DownloadUtils` with STAR Download Manager |
| `net.kdt.pojavlaunch.authenticator.*` | `MicrosoftBackgroundLogin`, `PresentListener`, `DoneListener` | Microsoft OAuth / XSTS / Minecraft token acquisition | **Refactor** token persistence to Keystore-backed vault (never store plaintext) |
| `net.kdt.pojavlaunch.value` & `value.launcherprofiles` | `MinecraftAccount`, `LauncherProfiles`, `MinecraftProfile`, `DependentLibrary`, `MinecraftLibraryArtifact` | Plaintext account JSON, Mojang `launcher_profiles.json` serialization | **Wrap/Read-Only Import**; replace active storage with Room + encrypted token vault |
| `net.kdt.pojavlaunch.multirt` | `MultiRTUtils`, `Runtime`, `RTMediaManager` | JRE tar.xz extraction, `unpack200`, `release` file parsing, JRE version ranking | **Wrap** via `Runtime Facade` + index in `RuntimeRegistry` |
| `net.kdt.pojavlaunch.tasks` | `AsyncAssetManager`, `AsyncMinecraftDownloader`, `MinecraftDownloader`, `AsyncVersionList` | Asset extraction, Mojang manifest/library/asset downloading, version list caching | Retain `AsyncAssetManager` component unpackers; replace game downloader with `Download Manager` |
| `net.kdt.pojavlaunch.modloaders` | `ModloaderDownloadListener`, `LWJGL3ifyUtils`, `ForgeUtils`, `FabricUtils`, `QuiltUtils`, `OptiFineUtils`, `modpacks/*` | Modloader JSON generation, CurseForge/Modrinth modpack import, LWJGL3ify auto-patching | **Wrap** via `Version Manager` |
| `net.kdt.pojavlaunch.customcontrols` | `ControlLayout`, `CustomControls`, `ControlButton`, `ControlJoystick`, `Touchpad`, `InGameEventProcessor`, `InGUIEventProcessor`, `AndroidPointerCapture`, `Gamepad`, `DirectGamepad`, `TouchCharInput`, `GyroControl` | On-screen controls, virtual mouse, pointer capture, 3-mode gamepad input, IME chat input, gyro camera | **Preserve** and expose configuration hooks through `Input Manager` |
| `net.kdt.pojavlaunch.prefs` & `prefs.screens` | `LauncherPreferences`, `QuickSettingSideDialog`, `LauncherPreferenceFragment`, sub-preference fragments | Static preference globals + XML preference screens | **Gradually supersede** with typed Settings repository backed by Room `AppSettings`, syncing required engine flags to `LauncherPreferences` |
| `net.kdt.pojavlaunch.extra` & `lifecycle` | `ExtraCore`, `ExtraConstants`, `ContextExecutor`, `ContextAwareDoneListener` | Global event bus & context-safe async UI/process handoff | **Retain** for legacy engine callbacks; keep STAR UI reactive via `StateFlow` |
| `net.kdt.pojavlaunch.services` & `progresskeeper` | `GameService`, `ProgressService`, `ProgressServiceKeeper`, `ProgressKeeper` | Foreground services (`:game` keep-alive and download notifications) | **Retain** `GameService`; bridge `Download Manager` into foreground notification service |
| `net.kdt.pojavlaunch.scoped` & `plugins` | `FolderProvider`, `FFmpegPlugin` | `DocumentsProvider` for `.minecraft` access; external FFmpeg plugin APK discovery | **Retain** `FolderProvider` and plugin discovery pattern |
| `org.lwjgl.glfw` & `org.libsdl.app` & `com.oracle.dalvik` | `CallbackBridge`, `SDLActivity`, `SDLSurface`, `VMLauncher` | Dalvik-side GLFW/SDL callbacks and `VMLauncher.launchJVM(String[])` native entry | **Preserve untouched** (JNI contract!) |

---

## 3. Native C/C++ Modules (`app_pojavlauncher/src/main/jni/Android.mk`)

| `LOCAL_MODULE` | Output `.so` | Sources | Responsibility |
|---|---|---|---|
| `pojavexec` | `libpojavexec.so` | `jre_launcher.c`, `egl_bridge.c`, `input_bridge_v3.c`, `stdio_is.c`, `utils.c`, `bigcoreaffinity.c`, `environ/environ.c`, `ctxbridges/*`, `jvm_hooks/*` | JVM `JLI_Launch` host, EGL/OSMesa context bridge, cross-VM input ring buffer & `RegisterNatives`, stdout/stderr logger pipe, CPU affinity, JVM `RegisterNatives` hooks |
| `exithook` | `libexithook.so` | `native_hooks/exit_hook.c`, `chmod_hook.c`, `sdl_hook.c`, `dlopen_hook.c` | ByteHook PLT hooks for `exit`/`atexit`, `chmod`/`fchmod`, `dlopen` redirects (Turnip/OSMesa/AWT), and `libSDL3.so` `JNI_OnLoad` + joystick hooks |
| `linkerhook` | `liblinkerhook.so` | `driver_helper/internal_android_dlopen_hook/turnip/hook.c` | Hooks `android_dlopen_ext` / `android_load_sphal_library` with `androidnsbypass` (`-z global`) to inject Turnip `libvulkan_freedreno.so` on `arm64-v8a` |
| `glxshim` | `libglxshim.so` | `driver_helper/glxshim/glxshim.c` | Exports `glXGetProcAddress[ARB]` delegating to `eglGetProcAddress` for Kopper-Zink |
| `pojavexec_awt` | `libpojavexec_awt.so` | `awt_bridge.c` | Bridges Caciocavallo `CTCScreen` RGB framebuffer and AWT input/clipboard to `JavaGUILauncherActivity` |
| `awt_headless` | *(build stub, deleted at L111)* | *(none)* | Compile-time link target for `awt_xawt`; deleted post-link so JRE's own `libawt_headless.so` loads |
| `awt_xawt` | `libawt_xawt.so` | `awt_xawt/xawt_fake.c` | Headless X11-less `libawt_xawt.so` stub (`45` no-op AWT/FontConfig/GTK JNI stubs) |

---

## 4. Bundled Prebuilt Libraries & Assets

- **`app_pojavlauncher/src/main/jniLibs/`**:
  - `libunpack200.so` (all 4 ABIs), `libtwitchsdk.so` (`armeabi-v7a`), `libvulkan_freedreno.so` (`arm64-v8a`), `libVkLayer_khronos_timeline_semaphore.so` (`arm64-v8a`).
- **`app_pojavlauncher/libs/`**:
  - AARs (all 4 ABIs): `angle-release.aar`, `imgui-java-release.aar`, `kopper-zink-release.aar`, `lwjgl-3.3.3-natives-release.aar`, `lwjgl-3.4.1-natives-release.aar`, `MobileGlues-release.aar`, `openal-soft-release.aar`, `SDL-release.aar`, `SimpleFPEWrapper-release.aar`, `spirv-cross-natives.aar`, `zstd-jni-release.aar`.
  - JARs: `ExagearApacheCommons.jar`, `exp4j-0.4.9-SNAPSHOT.jar`, `gson-2.8.6.jar`.
- **`app_pojavlauncher/src/main/assets/components/`**:
  - `caciocavallo/` (Java 8 AWT), `caciocavallo17/` (Java 17+ AWT), `lwjgl3/3.3.3/`, `lwjgl3/3.4.1/`, `arc_dns_injector/`, `forge_installer/`, `MioLibPatcher/`, `methods_injector_agent/`, `security/`.
  - **Note**: `components/jre/` (Java 8) is **not committed to Git**; upstream CI downloads it from `AngelAuraMC/angelauramc-openjdk-build`, while Java 8/17/21/25 can also be downloaded on-device via `NewJREUtil.ExternalRuntime`.
