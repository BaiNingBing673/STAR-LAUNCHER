# Upstream Attribution, Credits & License Notices

> STAR Launcher is **not** written from scratch. Its Android JVM launch pipeline, native C/C++ graphics/input bridges, and mod-loader plumbing are built upon **Amethyst-Android**, **PojavLauncher**, and **Boardwalk**, alongside third-party native graphics libraries and open-source dependencies.

---

## 1. Upstream Project Lineage

| Project | Role in STAR Launcher | License | Attribution Note |
| --- | --- | --- | --- |
| **[Amethyst-Android](https://github.com/AngelAuraMC/Amethyst-Android)** (`AngelAuraMC`, branch `v3_openjdk`, base commit `166af615d`) | Core Android execution engine (`net.kdt.pojavlaunch.*`), native JNI C/C++ layer (`libpojavexec.so`, `libexithook.so`, `liblinkerhook.so`), bundled renderer wrappers, and Gradle subprojects | **GNU LGPLv3** ([`LICENSE`](../LICENSE)) | Original engine architecture, process lifecycle, renderers, and controllers preserved |
| **[PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher)** | Upstream ancestor project initiated by Tran Khanh Duy (`ArtDeell`, PojavLauncherTeam) | **GNU LGPLv3** / historical **GPLv3** note in [`about_en.txt`](../app_pojavlauncher/src/main/assets/about_en.txt) | Retains copyright notices and Crowdin community translation credits |
| **[Boardwalk](https://github.com/zhuowei/Boardwalk)** (`zhuowei`) | Foundational Android JVM Minecraft launcher lineage | Apache-2.0 / GPLv2 (upstream) | Credited in `about_en.txt` and top-level lineage |
| **[ZalithLauncher2](https://github.com/ZalithLauncher/ZalithLauncher2)** | **External UX & architectural reference only** (Room schema separation, coroutine task queue patterns) | **GNU GPLv3** | **Zero source code copied**; clean-room Kotlin implementation inside `com.star.launcher` |

---

## 2. Submodules & Companion Projects

| Module | Source | License | Notes |
| --- | --- | --- | --- |
| **`androidnsbypass`** | `alexytomi/androidnsbypass` | Dual **MIT** + **BSD-2-Clause** (Billy Laws) | Enables linker namespace bypass and BTI trampolines for Turnip driver loading |
| **`MioLibPatcher`** | `AngelAuraMC/MioLibPatcher` | Uses `org.javassist:javassist:3.29.2-GA` (MPL 1.1 / LGPL 2.1 / Apache 2.0) | Submodule repo contains no standalone `LICENSE` file; runtime bytecode agent for Sodium/OSHI/SQLite/VulkanMod |
| **`forge_installer`** | In-repo Gradle subproject | Inherited (`LGPLv3`); uses `org.json:json` (JSON License) | Headless Forge GUI installer agent (`app_pojavlauncher/src/main/assets/components/forge_installer/`) |
| **`arc_dns_injector`** | In-repo Gradle subproject | Inherited (`LGPLv3`) | JVM `-javaagent` DNS resolution helper for Android |
| **`jre_lwjgl3glfw`** | Custom LWJGL `3.3.3` & `3.4.1` Java stubs | **BSD-3-Clause** (LWJGL3 upstream) + repo hooks | Merges GLFW/EGL/SDL3 bridge Java bindings |

---

## 3. Native Renderers, Media & Runtime Libraries

| Component | Upstream Project | License (Verified in Repo Notices) |
| --- | --- | --- |
| **MobileGlues** | `MobileGL-Dev/MobileGlues` | **GNU LGPL-2.1** (`mobileglues-lgplv2.1-license.txt`) |
| **Krypton Wrapper** | `BZLZHH/NG-GL4ES` / GL4ES | **MIT License** |
| **Kopper / Zink / Mesa** | Mesa 3D & Swung Kopper Zink | **MIT / X11 / Khronos** (`mesa-licenses.rst`) |
| **Turnip (`libvulkan_freedreno.so`)** | Mesa Freedreno Turnip / Khronos | **MIT / Apache-2.0** |
| **ANGLE** | Google ANGLE (`chromium.googlesource.com/angle/angle`) | **BSD-3-Clause** ([`ANGLE_LICENSE`](../app_pojavlauncher/src/main/assets/licenses/ANGLE_LICENSE)) |
| **SDL2-compat & SDL3** | `libsdl-org/SDL` | **zlib License** (`sdl2-compat-zlib-license.txt`, `sdl3-zlib-license.txt`) |
| **LWJGL 3** | `LWJGL/lwjgl3` (`AngelAuraMC/lwjgl3`) | **BSD-3-Clause** (+ FreeType, GLFW, Blendish notices) |
| **OpenAL-Soft & Oboe** | `kcat/openal-soft`, `google/oboe`, `pffft` | **GNU LGPL-2.0** (`OPENAL-SOFT_GPL2` — mislabeled GPLv2 in upstream file header) + **Apache-2.0** (`OBOE_APACHE2`) + `PFFFT_LICENSE` |
| **OpenJDK & Caciocavallo** | OpenJDK Multiarch (`AngelAuraMC/angelauramc-openjdk-build`), Caciocavallo | **GNU GPLv2 + Classpath Exception** |
| **ByteHook** | `bytedance/bhook` | **MIT License** |
| **Zstd-JNI** | Zstandard JNI | **BSD-2-Clause** (JNI) + **BSD-3 / GPLv2** dual (zstd) |
| **TouchController Proxy** | `top.fifthlight.touchcontroller:proxy-client-android` | LGPLv3 / MIT |
| **Dear ImGui Java** | `imgui-java` | **MIT License** |
| **JNA, Gson, Apache Commons, Checkerboard, ExtendedView, Virtual Joystick, Gamepad Remapper** | Respective upstream authors | Apache-2.0 / LGPLv2.1+ / MIT (see [`LICENSE_AUDIT.md`](LICENSE_AUDIT.md)) |
| **Player Skin Avatars** | `MCHeads` (`https://mc-heads.net`) | Attribution retained in `about_*.txt` |

---

## 4. License Compliance & Open Audit Items

All copyright headers, root [`LICENSE`](../LICENSE), `assets/licenses/*`, and `assets/about_*.txt` files are preserved. Before publishing compiled release APKs publicly, note the following items audited in [`LICENSE_AUDIT.md`](LICENSE_AUDIT.md):

1. **`bigcoreaffinity.c` (`app_pojavlauncher/src/main/jni/bigcoreaffinity.c:5`)**: Declares `#define _GNU_SOURCE // we are GNU GPLv3` inside `libpojavexec.so`, whereas root [`LICENSE`](../LICENSE) is **GNU LGPLv3**. Binary distributors must clarify or resolve GPLv3 vs LGPLv3 compatibility and satisfy copyleft source-offer requirements.
2. **Artifacts Without In-Archive License Metadata**: `SimpleFPEWrapper-release.aar`, `ExagearApacheCommons.jar`, `lwjgl-lwjglx.jar`, and `libtwitchsdk.so` (`armeabi-v7a`) require upstream provenance confirmation prior to public Play/F-Droid distribution.
3. **`org.json:json` (`forge_installer`)**: Uses the JSON License, which includes a non-standard field-of-use clause ("Good, not Evil").
