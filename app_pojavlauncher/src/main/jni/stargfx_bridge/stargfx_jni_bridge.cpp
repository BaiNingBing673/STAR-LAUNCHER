#include "sgfx/sgfx.hpp"
#include "stargfx_scene3d.hpp"

#ifndef VK_NO_PROTOTYPES
#define VK_NO_PROTOTYPES 1
#endif
#include <vulkan/vulkan.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_set>
#include <vector>

namespace {

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "STAR_GFX_JNI", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "STAR_GFX_JNI", __VA_ARGS__)

std::mutex g_err_mutex;
std::string g_last_init_error;
SgfxResult g_last_init_code = SGFX_SUCCESS;

void set_last_init_error(SgfxResult code, const std::string& msg) {
    std::lock_guard<std::mutex> lock(g_err_mutex);
    g_last_init_code = code;
    g_last_init_error = msg;
}

std::string json_escape(const std::string& in) {
    std::string out;
    out.reserve(in.size() + 16U);
    for (unsigned char c : in) {
        switch (c) {
        case '\"': out += "\\\""; break;
        case '\\': out += "\\\\"; break;
        case '\n': out += "\\n"; break;
        case '\r': out += "\\r"; break;
        case '\t': out += "\\t"; break;
        default:
            if (c < 0x20U) {
                char buf[8];
                std::snprintf(buf, sizeof(buf), "\\u%04x", static_cast<unsigned>(c));
                out += buf;
            } else {
                out.push_back(static_cast<char>(c));
            }
            break;
        }
    }
    return out;
}

long read_vm_rss_kb() {
    FILE* f = std::fopen("/proc/self/status", "r");
    if (!f) return 0L;
    char line[256]{};
    long rss = 0L;
    while (std::fgets(line, sizeof(line), f)) {
        if (std::sscanf(line, "VmRSS: %ld kB", &rss) == 1) {
            break;
        }
    }
    std::fclose(f);
    return rss;
}

int read_kgsl_busy_pct() {
    FILE* f = std::fopen("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "r");
    if (!f) return -1;
    char buf[64]{};
    int val = -1;
    if (std::fgets(buf, sizeof(buf), f)) {
        int tmp = -1;
        if (std::sscanf(buf, "%d", &tmp) == 1) {
            val = tmp;
        }
    }
    std::fclose(f);
    return val;
}

std::vector<std::string> find_mapped_libs(const char* needle) {
    std::vector<std::string> found;
    FILE* f = std::fopen("/proc/self/maps", "r");
    if (!f) return found;
    char line[1024]{};
    while (std::fgets(line, sizeof(line), f)) {
        if (std::strstr(line, needle) != nullptr) {
            const char* slash = std::strchr(line, '/');
            if (slash != nullptr) {
                std::string path(slash);
                while (!path.empty() && (path.back() == '\n' || path.back() == '\r' || path.back() == ' ')) {
                    path.pop_back();
                }
                if (std::find(found.begin(), found.end(), path) == found.end()) {
                    found.push_back(path);
                }
            }
        }
    }
    std::fclose(f);
    return found;
}

struct StarGfxSession {
    SgfxInstance instance = nullptr;
    SgfxDevice device = nullptr;
    SgfxSurface surface = nullptr;
    SgfxRenderGraph rg = nullptr;
    ANativeWindow* window = nullptr;

    sgfx::BufferHandle vbo;
    sgfx::BufferHandle ibo;
    sgfx::TextureHandle tex;
    sgfx::SamplerHandle sampler;
    sgfx::TextureHandle depth_tex;
    sgfx::TextureHandle offscreen_color;
    sgfx::TextureHandle offscreen_depth;
    sgfx::ShaderHandle vs;
    sgfx::ShaderHandle fs;
    sgfx::PipelineHandle swap_pipeline;
    sgfx::PipelineHandle offscreen_pipeline;

    uint32_t width = 1280U;
    uint32_t height = 720U;
    static constexpr uint32_t kOffscreenSize = 256U;
    uint32_t index_count = 0U;
    SgfxBackendType active_backend = SGFX_BACKEND_AUTO;
    std::mutex mutex;
};

struct DrawCtx {
    StarGfxSession* session = nullptr;
    SgfxPipeline pipeline = SGFX_NULL_HANDLE;
    uint32_t width = 0U;
    uint32_t height = 0U;
    float angle_rad = 0.0f;
};

void encode_scene_pass(SgfxCommandList cmd, void* user_data) {
    auto* ctx = static_cast<DrawCtx*>(user_data);
    StarGfxSession* s = ctx->session;
    const SgfxViewport vp{0.0f, 0.0f, static_cast<float>(ctx->width), static_cast<float>(ctx->height), 0.0f, 1.0f};
    const SgfxRect2D sc{0, 0, ctx->width, ctx->height};

    sgfx_bridge::ScenePushConstants pc{};
    const float aspect = static_cast<float>(ctx->width) / static_cast<float>(ctx->height > 0U ? ctx->height : 1U);
    const bool flip_y = (s->active_backend == SGFX_BACKEND_VULKAN);
    sgfx_bridge::build_orbit_mvp(aspect, ctx->angle_rad, flip_y, pc.mvp);
    pc.tint[0] = 1.0f;
    pc.tint[1] = 1.0f;
    pc.tint[2] = 1.0f;
    pc.tint[3] = 1.0f;

    sgfx_cmd_bind_pipeline(cmd, ctx->pipeline);
    sgfx_cmd_set_viewport(cmd, &vp);
    sgfx_cmd_set_scissor(cmd, &sc);
    sgfx_cmd_bind_vertex_buffer(cmd, 0U, s->vbo.get(), 0U);
    sgfx_cmd_bind_index_buffer(cmd, s->ibo.get(), 0U, SGFX_INDEX_UINT16);
    sgfx_cmd_bind_texture(cmd, 0U, s->tex.get(), s->sampler.get());
    sgfx_cmd_push_constants(cmd, 0U, sizeof(pc), &pc);
    sgfx_cmd_draw_indexed(cmd, s->index_count, 1U, 0U, 0, 0U);
}

void sgfx_jni_log(SgfxLogLevel level, const char* cat, const char* msg, void*) {
    const int prio = (level == SGFX_LOG_ERROR) ? ANDROID_LOG_ERROR
                   : ((level == SGFX_LOG_WARN) ? ANDROID_LOG_WARN : ANDROID_LOG_INFO);
    __android_log_print(prio, "STAR_GFX", "[%s] %s", cat ? cat : "", msg ? msg : "");
}

void destroy_session_unlocked(StarGfxSession* s) {
    if (s == nullptr) return;
    s->offscreen_pipeline.reset();
    s->swap_pipeline.reset();
    s->fs.reset();
    s->vs.reset();
    s->offscreen_depth.reset();
    s->offscreen_color.reset();
    s->depth_tex.reset();
    s->sampler.reset();
    s->tex.reset();
    s->ibo.reset();
    s->vbo.reset();
    if (s->rg != nullptr) {
        sgfx_destroy_render_graph(s->rg);
        s->rg = nullptr;
    }
    if (s->surface != nullptr) {
        sgfx_destroy_surface(s->surface);
        s->surface = nullptr;
    }
    if (s->device != nullptr) {
        sgfx_destroy_device(s->device);
        s->device = nullptr;
    }
    if (s->instance != nullptr) {
        sgfx_destroy_instance(s->instance);
        s->instance = nullptr;
    }
    if (s->window != nullptr) {
        ANativeWindow_release(s->window);
        s->window = nullptr;
    }
}

std::string probe_vulkan_json() {
    std::ostringstream oss;
    void* vk = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!vk) {
        oss << "{\"available\":false,\"error\":\"dlopen(libvulkan.so) failed\"}";
        return oss.str();
    }
    auto gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(vk, "vkGetInstanceProcAddr"));
    if (!gipa) {
        dlclose(vk);
        oss << "{\"available\":false,\"error\":\"vkGetInstanceProcAddr missing\"}";
        return oss.str();
    }

    auto enumVer = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(gipa(VK_NULL_HANDLE, "vkEnumerateInstanceVersion"));
    auto enumInstExts = reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(gipa(VK_NULL_HANDLE, "vkEnumerateInstanceExtensionProperties"));
    auto createInst = reinterpret_cast<PFN_vkCreateInstance>(gipa(VK_NULL_HANDLE, "vkCreateInstance"));

    uint32_t inst_ver = VK_API_VERSION_1_0;
    if (enumVer) enumVer(&inst_ver);

    uint32_t ext_cnt = 0U;
    std::vector<VkExtensionProperties> inst_exts;
    if (enumInstExts && enumInstExts(nullptr, &ext_cnt, nullptr) == VK_SUCCESS && ext_cnt > 0U) {
        inst_exts.resize(ext_cnt);
        enumInstExts(nullptr, &ext_cnt, inst_exts.data());
    }

    VkApplicationInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    ai.pApplicationName = "STAR_Probe";
    ai.applicationVersion = 1U;
    ai.pEngineName = "STAR_GFX";
    ai.engineVersion = 1U;
    ai.apiVersion = inst_ver;

    VkInstanceCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo = &ai;

    VkInstance inst = VK_NULL_HANDLE;
    if (!createInst || createInst(&ci, nullptr, &inst) != VK_SUCCESS || inst == VK_NULL_HANDLE) {
        dlclose(vk);
        oss << "{\"available\":false,\"error\":\"vkCreateInstance failed\"}";
        return oss.str();
    }

    auto destroyInst = reinterpret_cast<PFN_vkDestroyInstance>(gipa(inst, "vkDestroyInstance"));
    auto enumPds = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(gipa(inst, "vkEnumeratePhysicalDevices"));
    auto getPdProps = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(gipa(inst, "vkGetPhysicalDeviceProperties"));
    auto enumDevExts = reinterpret_cast<PFN_vkEnumerateDeviceExtensionProperties>(gipa(inst, "vkEnumerateDeviceExtensionProperties"));
    auto getQ = reinterpret_cast<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(gipa(inst, "vkGetPhysicalDeviceQueueFamilyProperties"));

    uint32_t pd_cnt = 0U;
    std::vector<VkPhysicalDevice> pds;
    if (enumPds && enumPds(inst, &pd_cnt, nullptr) == VK_SUCCESS && pd_cnt > 0U) {
        pds.resize(pd_cnt);
        enumPds(inst, &pd_cnt, pds.data());
    }

    oss << "{\"available\":" << (pd_cnt > 0U ? "true" : "false")
        << ",\"instanceApiVersion\":\"" << VK_VERSION_MAJOR(inst_ver) << "."
        << VK_VERSION_MINOR(inst_ver) << "." << VK_VERSION_PATCH(inst_ver) << "\""
        << ",\"physicalDeviceCount\":" << pd_cnt
        << ",\"instanceExtensionCount\":" << inst_exts.size();

    if (!pds.empty() && getPdProps) {
        VkPhysicalDeviceProperties props{};
        getPdProps(pds[0], &props);
        uint32_t dev_ext_cnt = 0U;
        std::vector<VkExtensionProperties> dev_exts;
        if (enumDevExts && enumDevExts(pds[0], nullptr, &dev_ext_cnt, nullptr) == VK_SUCCESS && dev_ext_cnt > 0U) {
            dev_exts.resize(dev_ext_cnt);
            enumDevExts(pds[0], nullptr, &dev_ext_cnt, dev_exts.data());
        }
        uint32_t q_cnt = 0U;
        if (getQ) getQ(pds[0], &q_cnt, nullptr);

        char ver_buf[32], drv_buf[48], vid_buf[16], did_buf[16];
        std::snprintf(ver_buf, sizeof(ver_buf), "%u.%u.%u",
                      VK_VERSION_MAJOR(props.apiVersion), VK_VERSION_MINOR(props.apiVersion), VK_VERSION_PATCH(props.apiVersion));
        std::snprintf(drv_buf, sizeof(drv_buf), "%u.%u.%u (0x%08X)",
                      VK_VERSION_MAJOR(props.driverVersion), VK_VERSION_MINOR(props.driverVersion), VK_VERSION_PATCH(props.driverVersion), props.driverVersion);
        std::snprintf(vid_buf, sizeof(vid_buf), "0x%04X", props.vendorID);
        std::snprintf(did_buf, sizeof(did_buf), "0x%04X", props.deviceID);

        oss << ",\"deviceName\":\"" << json_escape(props.deviceName) << "\""
            << ",\"vendorId\":\"" << vid_buf << "\""
            << ",\"deviceId\":\"" << did_buf << "\""
            << ",\"deviceApiVersion\":\"" << ver_buf << "\""
            << ",\"driverVersion\":\"" << drv_buf << "\""
            << ",\"queueFamilyCount\":" << q_cnt
            << ",\"deviceExtensionCount\":" << dev_exts.size()
            << ",\"deviceExtensionsSample\":[";
        for (size_t i = 0U; i < std::min<size_t>(dev_exts.size(), 24U); ++i) {
            if (i > 0U) oss << ",";
            oss << "\"" << json_escape(dev_exts[i].extensionName) << "\"";
        }
        oss << "]";
    }
    oss << "}";
    if (destroyInst) destroyInst(inst, nullptr);
    dlclose(vk);
    return oss.str();
}

std::string probe_gles_json() {
    std::ostringstream oss;
    void* egl = dlopen("libEGL.so", RTLD_NOW | RTLD_LOCAL);
    void* gles = dlopen("libGLESv2.so", RTLD_NOW | RTLD_LOCAL);
    if (!egl || !gles) {
        if (gles) dlclose(gles);
        if (egl) dlclose(egl);
        oss << "{\"available\":false,\"error\":\"dlopen EGL/GLES failed\"}";
        return oss.str();
    }
    using PfnGetDisp = EGLDisplay (*)(EGLNativeDisplayType);
    using PfnInit = EGLBoolean (*)(EGLDisplay, EGLint*, EGLint*);
    using PfnTerm = EGLBoolean (*)(EGLDisplay);
    using PfnQueryStr = const char* (*)(EGLDisplay, EGLint);
    using PfnChooseCfg = EGLBoolean (*)(EGLDisplay, const EGLint*, EGLConfig*, EGLint, EGLint*);
    using PfnCreatePbuf = EGLSurface (*)(EGLDisplay, EGLConfig, const EGLint*);
    using PfnCreateCtx = EGLContext (*)(EGLDisplay, EGLConfig, EGLContext, const EGLint*);
    using PfnMakeCur = EGLBoolean (*)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
    using PfnDestroySurf = EGLBoolean (*)(EGLDisplay, EGLSurface);
    using PfnDestroyCtx = EGLBoolean (*)(EGLDisplay, EGLContext);
    using PfnGlGetStr = const GLubyte* (*)(GLenum);

    auto getDisp = reinterpret_cast<PfnGetDisp>(dlsym(egl, "eglGetDisplay"));
    auto initEgl = reinterpret_cast<PfnInit>(dlsym(egl, "eglInitialize"));
    auto termEgl = reinterpret_cast<PfnTerm>(dlsym(egl, "eglTerminate"));
    auto queryStr = reinterpret_cast<PfnQueryStr>(dlsym(egl, "eglQueryString"));
    auto chooseCfg = reinterpret_cast<PfnChooseCfg>(dlsym(egl, "eglChooseConfig"));
    auto createPbuf = reinterpret_cast<PfnCreatePbuf>(dlsym(egl, "eglCreatePbufferSurface"));
    auto createCtx = reinterpret_cast<PfnCreateCtx>(dlsym(egl, "eglCreateContext"));
    auto makeCur = reinterpret_cast<PfnMakeCur>(dlsym(egl, "eglMakeCurrent"));
    auto destroySurf = reinterpret_cast<PfnDestroySurf>(dlsym(egl, "eglDestroySurface"));
    auto destroyCtx = reinterpret_cast<PfnDestroyCtx>(dlsym(egl, "eglDestroyContext"));
    auto glGetStr = reinterpret_cast<PfnGlGetStr>(dlsym(gles, "glGetString"));

    EGLDisplay dpy = getDisp ? getDisp(EGL_DEFAULT_DISPLAY) : EGL_NO_DISPLAY;
    EGLint maj = 0, min = 0;
    if (dpy == EGL_NO_DISPLAY || !initEgl || !initEgl(dpy, &maj, &min)) {
        dlclose(gles);
        dlclose(egl);
        oss << "{\"available\":false,\"error\":\"eglInitialize failed\"}";
        return oss.str();
    }

    const EGLint cfg_attr[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE
    };
    EGLConfig cfg = nullptr;
    EGLint num_cfg = 0;
    bool ok = chooseCfg && chooseCfg(dpy, cfg_attr, &cfg, 1, &num_cfg) && num_cfg > 0;
    const EGLint pbuf_attr[] = {EGL_WIDTH, 4, EGL_HEIGHT, 4, EGL_NONE};
    EGLSurface surf = ok && createPbuf ? createPbuf(dpy, cfg, pbuf_attr) : EGL_NO_SURFACE;
    const EGLint ctx_attr[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    EGLContext ctx = (surf != EGL_NO_SURFACE) && createCtx ? createCtx(dpy, cfg, EGL_NO_CONTEXT, ctx_attr) : EGL_NO_CONTEXT;

    std::string gl_ver, gl_ven, gl_ren, gl_ext, egl_ver, egl_ext;
    if (queryStr) {
        if (const char* s = queryStr(dpy, EGL_VERSION)) egl_ver = s;
        if (const char* s = queryStr(dpy, EGL_EXTENSIONS)) egl_ext = s;
    }

    bool made_cur = (ctx != EGL_NO_CONTEXT) && makeCur && makeCur(dpy, surf, surf, ctx);
    if (made_cur && glGetStr) {
        if (const GLubyte* s = glGetStr(GL_VERSION)) gl_ver = reinterpret_cast<const char*>(s);
        if (const GLubyte* s = glGetStr(GL_VENDOR)) gl_ven = reinterpret_cast<const char*>(s);
        if (const GLubyte* s = glGetStr(GL_RENDERER)) gl_ren = reinterpret_cast<const char*>(s);
        if (const GLubyte* s = glGetStr(GL_EXTENSIONS)) gl_ext = reinterpret_cast<const char*>(s);
    }

    if (makeCur) makeCur(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (ctx != EGL_NO_CONTEXT && destroyCtx) destroyCtx(dpy, ctx);
    if (surf != EGL_NO_SURFACE && destroySurf) destroySurf(dpy, surf);
    if (termEgl) termEgl(dpy);
    dlclose(gles);
    dlclose(egl);

    oss << "{\"available\":" << (made_cur ? "true" : "false")
        << ",\"eglVersion\":\"" << json_escape(egl_ver) << "\""
        << ",\"glVersion\":\"" << json_escape(gl_ver) << "\""
        << ",\"glVendor\":\"" << json_escape(gl_ven) << "\""
        << ",\"glRenderer\":\"" << json_escape(gl_ren) << "\""
        << ",\"eglExtensionsLength\":" << egl_ext.size()
        << ",\"glExtensionsLength\":" << gl_ext.size()
        << "}";
    return oss.str();
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_star_launcher_stargfx_StarGfxNative_nativeVerifyLibraries(JNIEnv* env, jclass) {
    const auto sgfx_paths = find_mapped_libs("libstargfx.so");
    const auto bridge_paths = find_mapped_libs("libstargfx_bridge.so");
    std::ostringstream oss;
    oss << "{\"apiVersion\":\"" << SGFX_API_VERSION_MAJOR << "." << SGFX_API_VERSION_MINOR << "." << SGFX_API_VERSION_PATCH << "\""
        << ",\"stargfxLoaded\":" << (!sgfx_paths.empty() ? "true" : "false")
        << ",\"bridgeLoaded\":" << (!bridge_paths.empty() ? "true" : "false")
        << ",\"stargfxPath\":\"" << json_escape(sgfx_paths.empty() ? "" : sgfx_paths.front()) << "\""
        << ",\"bridgePath\":\"" << json_escape(bridge_paths.empty() ? "" : bridge_paths.front()) << "\""
        << ",\"pid\":" << static_cast<long>(getpid())
        << "}";
    return env->NewStringUTF(oss.str().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_star_launcher_stargfx_StarGfxNative_nativeProbeSystemGraphics(JNIEnv* env, jclass) {
    std::ostringstream oss;
    oss << "{\"vulkan\":" << probe_vulkan_json()
        << ",\"opengles\":" << probe_gles_json()
        << "}";
    return env->NewStringUTF(oss.str().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_star_launcher_stargfx_StarGfxNative_nativeGetLastInitError(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_err_mutex);
    std::ostringstream oss;
    oss << "{\"code\":" << static_cast<int>(g_last_init_code)
        << ",\"message\":\"" << json_escape(g_last_init_error) << "\"}";
    return env->NewStringUTF(oss.str().c_str());
}

JNIEXPORT jlong JNICALL
Java_com_star_launcher_stargfx_StarGfxNative_nativeCreateSession(
    JNIEnv* env,
    jclass,
    jstring jBackend,
    jobject jSurface,
    jint width,
    jint height) {
    const char* raw_backend = jBackend ? env->GetStringUTFChars(jBackend, nullptr) : nullptr;
    std::string backend_str = raw_backend ? raw_backend : "auto";
    if (raw_backend) env->ReleaseStringUTFChars(jBackend, raw_backend);

    SgfxBackendType preferred = SGFX_BACKEND_AUTO;
    if (backend_str == "vulkan") preferred = SGFX_BACKEND_VULKAN;
    else if (backend_str == "gles" || backend_str == "opengles") preferred = SGFX_BACKEND_OPENGLES;

    auto* s = new StarGfxSession();
    s->width = static_cast<uint32_t>(std::max(1, static_cast<int>(width)));
    s->height = static_cast<uint32_t>(std::max(1, static_cast<int>(height)));
    if (jSurface != nullptr) {
        s->window = ANativeWindow_fromSurface(env, jSurface);
        if (s->window != nullptr) {
            const int32_t nw = ANativeWindow_getWidth(s->window);
            const int32_t nh = ANativeWindow_getHeight(s->window);
            if (nw > 0) s->width = static_cast<uint32_t>(nw);
            if (nh > 0) s->height = static_cast<uint32_t>(nh);
        }
    }

    SgfxInstanceDesc idesc{};
    idesc.app_name = "STAR_Launcher_SGFX";
    idesc.app_version = 1U;
    idesc.preferred_backend = preferred;
    idesc.enable_validation = false;
    idesc.enable_profiler = true;
    idesc.log_callback = sgfx_jni_log;

    SgfxResult res = sgfx_create_instance(&idesc, &s->instance);
    if (res != SGFX_SUCCESS) {
        set_last_init_error(res, "sgfx_create_instance failed");
        destroy_session_unlocked(s);
        delete s;
        return 0;
    }

    SgfxDeviceDesc ddesc{};
    ddesc.frames_in_flight = 2U;
    res = sgfx_create_device(s->instance, &ddesc, &s->device);
    if (res != SGFX_SUCCESS) {
        set_last_init_error(res, "sgfx_create_device failed for requested backend: " + backend_str);
        destroy_session_unlocked(s);
        delete s;
        return 0;
    }

    SgfxGpuCapabilities caps{};
    sgfx_device_get_capabilities(s->device, &caps);
    s->active_backend = caps.active_backend;

    if (s->window != nullptr) {
        res = sgfx_create_surface_android(s->instance, s->window, &s->surface);
    } else {
        res = sgfx_create_surface_offscreen(s->instance, s->width, s->height, &s->surface);
    }
    if (res != SGFX_SUCCESS) {
        set_last_init_error(res, "sgfx_create_surface failed");
        destroy_session_unlocked(s);
        delete s;
        return 0;
    }
    sgfx_surface_resize(s->surface, s->width, s->height, SGFX_TRANSFORM_IDENTITY);

    res = sgfx_create_render_graph(s->device, &s->rg);
    if (res != SGFX_SUCCESS) {
        set_last_init_error(res, "sgfx_create_render_graph failed");
        destroy_session_unlocked(s);
        delete s;
        return 0;
    }

    std::vector<sgfx_bridge::SceneVertex> verts;
    std::vector<uint16_t> indices;
    sgfx_bridge::build_scene_geometry(verts, indices);
    s->index_count = static_cast<uint32_t>(indices.size());

    const uint64_t vb_bytes = verts.size() * sizeof(sgfx_bridge::SceneVertex);
    const uint64_t ib_bytes = indices.size() * sizeof(uint16_t);
    SgfxBuffer raw_vb = SGFX_NULL_HANDLE, raw_ib = SGFX_NULL_HANDLE;
    SgfxBufferDesc vbd{vb_bytes, SGFX_BUFFER_USAGE_VERTEX, SGFX_MEMORY_CPU_TO_GPU_STREAM, "scene3d_vbo"};
    SgfxBufferDesc ibd{ib_bytes, SGFX_BUFFER_USAGE_INDEX, SGFX_MEMORY_CPU_TO_GPU_STREAM, "scene3d_ibo"};
    if ((res = sgfx_create_buffer(s->device, &vbd, &raw_vb)) != SGFX_SUCCESS ||
        (res = sgfx_upload_buffer(s->device, raw_vb, 0U, vb_bytes, verts.data())) != SGFX_SUCCESS ||
        (res = sgfx_create_buffer(s->device, &ibd, &raw_ib)) != SGFX_SUCCESS ||
        (res = sgfx_upload_buffer(s->device, raw_ib, 0U, ib_bytes, indices.data())) != SGFX_SUCCESS) {
        set_last_init_error(res, "Scene vertex/index buffer creation or upload failed");
        destroy_session_unlocked(s);
        delete s;
        return 0;
    }
    s->vbo = sgfx::BufferHandle(s->device, raw_vb);
    s->ibo = sgfx::BufferHandle(s->device, raw_ib);

    std::vector<uint8_t> tex_pixels;
    sgfx_bridge::build_checker_texture_rgba8(64U, tex_pixels);
    SgfxTexture raw_tex = SGFX_NULL_HANDLE;
    SgfxTextureDesc td{64U, 64U, 1U, 1U, 1U, 1U, SGFX_FORMAT_R8G8B8A8_UNORM, SGFX_TEXTURE_USAGE_SAMPLED | SGFX_TEXTURE_USAGE_TRANSFER_DST, "scene3d_tex"};
    if ((res = sgfx_create_texture(s->device, &td, &raw_tex)) != SGFX_SUCCESS ||
        (res = sgfx_upload_texture_2d(s->device, raw_tex, 64U, 64U, tex_pixels.data(), tex_pixels.size())) != SGFX_SUCCESS) {
        set_last_init_error(res, "Scene texture creation or upload failed");
        destroy_session_unlocked(s);
        delete s;
        return 0;
    }
    s->tex = sgfx::TextureHandle(s->device, raw_tex);

    SgfxSampler raw_smp = SGFX_NULL_HANDLE;
    SgfxSamplerDesc smd{SGFX_FILTER_LINEAR, SGFX_FILTER_LINEAR, SGFX_ADDRESS_REPEAT, SGFX_ADDRESS_REPEAT, "scene3d_smp"};
    if ((res = sgfx_create_sampler(s->device, &smd, &raw_smp)) != SGFX_SUCCESS) {
        set_last_init_error(res, "Sampler creation failed");
        destroy_session_unlocked(s);
        delete s;
        return 0;
    }
    s->sampler = sgfx::SamplerHandle(s->device, raw_smp);

    SgfxTexture raw_depth = SGFX_NULL_HANDLE;
    SgfxTextureDesc dtd{s->width, s->height, 1U, 1U, 1U, 1U, SGFX_FORMAT_D32_SFLOAT, SGFX_TEXTURE_USAGE_DEPTH_STENCIL_TARGET | SGFX_TEXTURE_USAGE_TRANSIENT, "scene3d_depth"};
    if ((res = sgfx_create_texture(s->device, &dtd, &raw_depth)) != SGFX_SUCCESS) {
        set_last_init_error(res, "Depth texture creation failed");
        destroy_session_unlocked(s);
        delete s;
        return 0;
    }
    s->depth_tex = sgfx::TextureHandle(s->device, raw_depth);

    SgfxTexture raw_off_c = SGFX_NULL_HANDLE, raw_off_d = SGFX_NULL_HANDLE;
    SgfxTextureDesc ocd{StarGfxSession::kOffscreenSize, StarGfxSession::kOffscreenSize, 1U, 1U, 1U, 1U,
                        SGFX_FORMAT_R8G8B8A8_UNORM, SGFX_TEXTURE_USAGE_COLOR_TARGET | SGFX_TEXTURE_USAGE_TRANSFER_SRC | SGFX_TEXTURE_USAGE_SAMPLED, "offscreen_color"};
    SgfxTextureDesc odd{StarGfxSession::kOffscreenSize, StarGfxSession::kOffscreenSize, 1U, 1U, 1U, 1U,
                        SGFX_FORMAT_D32_SFLOAT, SGFX_TEXTURE_USAGE_DEPTH_STENCIL_TARGET | SGFX_TEXTURE_USAGE_TRANSIENT, "offscreen_depth"};
    if ((res = sgfx_create_texture(s->device, &ocd, &raw_off_c)) != SGFX_SUCCESS ||
        (res = sgfx_create_texture(s->device, &odd, &raw_off_d)) != SGFX_SUCCESS) {
        set_last_init_error(res, "Offscreen verification texture creation failed");
        destroy_session_unlocked(s);
        delete s;
        return 0;
    }
    s->offscreen_color = sgfx::TextureHandle(s->device, raw_off_c);
    s->offscreen_depth = sgfx::TextureHandle(s->device, raw_off_d);

    SgfxShader raw_vs = SGFX_NULL_HANDLE, raw_fs = SGFX_NULL_HANDLE;
    SgfxShaderDesc vsd{}, fsd{};
    if (s->active_backend == SGFX_BACKEND_VULKAN) {
        vsd = {SGFX_STAGE_VERTEX, sgfx_bridge::kScene3DVertSpv, sizeof(sgfx_bridge::kScene3DVertSpv), "main", "scene3d_vs"};
        fsd = {SGFX_STAGE_FRAGMENT, sgfx_bridge::kScene3DFragSpv, sizeof(sgfx_bridge::kScene3DFragSpv), "main", "scene3d_fs"};
    } else {
        vsd = {SGFX_STAGE_VERTEX, sgfx_bridge::kScene3DVertEssl, sizeof(sgfx_bridge::kScene3DVertEssl) - 1U, "main", "scene3d_vs"};
        fsd = {SGFX_STAGE_FRAGMENT, sgfx_bridge::kScene3DFragEssl, sizeof(sgfx_bridge::kScene3DFragEssl) - 1U, "main", "scene3d_fs"};
    }
    if ((res = sgfx_create_shader(s->device, &vsd, &raw_vs)) != SGFX_SUCCESS ||
        (res = sgfx_create_shader(s->device, &fsd, &raw_fs)) != SGFX_SUCCESS) {
        set_last_init_error(res, "Shader compilation failed");
        destroy_session_unlocked(s);
        delete s;
        return 0;
    }
    s->vs = sgfx::ShaderHandle(s->device, raw_vs);
    s->fs = sgfx::ShaderHandle(s->device, raw_fs);

    SgfxGraphicsPipelineDesc pdesc{};
    pdesc.vertex_shader = s->vs.get();
    pdesc.fragment_shader = s->fs.get();
    pdesc.color_attachment_count = 1U;
    pdesc.color_formats[0] = (s->active_backend == SGFX_BACKEND_VULKAN) ? SGFX_FORMAT_R8G8B8A8_UNORM : SGFX_FORMAT_R8G8B8A8_UNORM;
    pdesc.depth_stencil_format = SGFX_FORMAT_D32_SFLOAT;
    pdesc.sample_count = 1U;
    pdesc.depth_test_enable = true;
    pdesc.depth_write_enable = true;
    pdesc.blend_enable = false;
    pdesc.cull_back_enable = true;
    pdesc.has_sampled_texture = true;
    pdesc.vertex_stride = sizeof(sgfx_bridge::SceneVertex);
    pdesc.vertex_attribute_count = 3U;
    pdesc.vertex_attributes[0] = {0U, SGFX_VERTEX_FORMAT_FLOAT3, 0U};
    pdesc.vertex_attributes[1] = {1U, SGFX_VERTEX_FORMAT_FLOAT2, 12U};
    pdesc.vertex_attributes[2] = {2U, SGFX_VERTEX_FORMAT_FLOAT3, 20U};

    SgfxPipeline raw_pipe = SGFX_NULL_HANDLE, raw_off_pipe = SGFX_NULL_HANDLE;
    if ((res = sgfx_create_graphics_pipeline(s->device, &pdesc, &raw_pipe)) != SGFX_SUCCESS ||
        (res = sgfx_create_graphics_pipeline(s->device, &pdesc, &raw_off_pipe)) != SGFX_SUCCESS) {
        set_last_init_error(res, "Graphics pipeline creation failed");
        destroy_session_unlocked(s);
        delete s;
        return 0;
    }
    s->swap_pipeline = sgfx::PipelineHandle(s->device, raw_pipe);
    s->offscreen_pipeline = sgfx::PipelineHandle(s->device, raw_off_pipe);

    set_last_init_error(SGFX_SUCCESS, "");
    LOGI("STAR GFX session created: backend=%d gpu=%s size=%ux%u",
         static_cast<int>(s->active_backend), caps.device_name, s->width, s->height);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jlong JNICALL
Java_com_star_launcher_stargfx_StarGfxNative_nativeRenderFrame(
    JNIEnv*,
    jclass,
    jlong sessionPtr,
    jfloat angleRad) {
    auto* s = reinterpret_cast<StarGfxSession*>(sessionPtr);
    if (s == nullptr) return -1;
    std::lock_guard<std::mutex> lock(s->mutex);
    if (s->device == nullptr || s->rg == nullptr) return -2;

    const auto t0 = std::chrono::steady_clock::now();
    SgfxResult res = sgfx_begin_frame(s->device);
    if (res != SGFX_SUCCESS) return -static_cast<jlong>(res);

    sgfx_rg_reset(s->rg);
    DrawCtx ctx{s, s->swap_pipeline.get(), s->width, s->height, angleRad};
    SgfxRenderPassDesc pass{};
    pass.name = "stargfx_scene3d_swap";
    pass.render_area = {0, 0, s->width, s->height};
    pass.color_attachment_count = 1U;
    pass.color_attachments[0] = {SGFX_NULL_HANDLE, SGFX_LOAD_CLEAR, SGFX_STORE_STORE, {0.04f, 0.06f, 0.11f, 1.0f}};
    pass.has_depth_attachment = true;
    pass.depth_attachment = {s->depth_tex.get(), SGFX_LOAD_CLEAR, SGFX_STORE_TRANSIENT, 1.0f, 0U};
    pass.has_side_effects = true;
    pass.encode_fn = encode_scene_pass;
    pass.user_data = &ctx;
    sgfx_rg_add_graphics_pass(s->rg, &pass);

    res = sgfx_rg_compile_and_execute(s->rg, s->surface);
    const SgfxResult end_res = sgfx_end_frame(s->device);
    const auto t1 = std::chrono::steady_clock::now();
    if (res != SGFX_SUCCESS || end_res != SGFX_SUCCESS) {
        return -10;
    }
    return std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0).count();
}

JNIEXPORT jstring JNICALL
Java_com_star_launcher_stargfx_StarGfxNative_nativeCaptureOffscreenFrame(
    JNIEnv* env,
    jclass,
    jlong sessionPtr,
    jfloat angleRad,
    jstring jOutPpmPath) {
    auto* s = reinterpret_cast<StarGfxSession*>(sessionPtr);
    if (s == nullptr) {
        return env->NewStringUTF("{\"ok\":false,\"error\":\"null session\"}");
    }
    std::lock_guard<std::mutex> lock(s->mutex);
    const uint32_t sz = StarGfxSession::kOffscreenSize;

    SgfxResult res = sgfx_begin_frame(s->device);
    if (res == SGFX_SUCCESS) {
        sgfx_rg_reset(s->rg);
        DrawCtx ctx{s, s->offscreen_pipeline.get(), sz, sz, angleRad};
        SgfxRenderPassDesc pass{};
        pass.name = "stargfx_scene3d_offscreen";
        pass.render_area = {0, 0, sz, sz};
        pass.color_attachment_count = 1U;
        pass.color_attachments[0] = {s->offscreen_color.get(), SGFX_LOAD_CLEAR, SGFX_STORE_STORE, {0.04f, 0.06f, 0.11f, 1.0f}};
        pass.has_depth_attachment = true;
        pass.depth_attachment = {s->offscreen_depth.get(), SGFX_LOAD_CLEAR, SGFX_STORE_TRANSIENT, 1.0f, 0U};
        pass.has_side_effects = true;
        pass.encode_fn = encode_scene_pass;
        pass.user_data = &ctx;
        sgfx_rg_add_graphics_pass(s->rg, &pass);
        sgfx_rg_set_export_texture(s->rg, s->offscreen_color.get());
        res = sgfx_rg_compile_and_execute(s->rg, nullptr);
        SgfxResult end_res = sgfx_end_frame(s->device);
        if (res == SGFX_SUCCESS) res = end_res;
    }
    if (res != SGFX_SUCCESS) {
        return env->NewStringUTF("{\"ok\":false,\"error\":\"offscreen execute failed\"}");
    }

    std::vector<uint8_t> rgba(static_cast<size_t>(sz) * sz * 4U, 0U);
    res = sgfx_readback_texture_rgba8(s->device, s->offscreen_color.get(), sz, sz, rgba.data(), rgba.size());
    if (res != SGFX_SUCCESS) {
        return env->NewStringUTF("{\"ok\":false,\"error\":\"readback failed\"}");
    }

    const size_t total_px = static_cast<size_t>(sz) * sz;
    size_t non_bg_px = 0U;
    uint64_t hash = 14695981039346656037ULL;
    std::unordered_set<uint32_t> color_buckets;
    for (size_t i = 0U; i < total_px; ++i) {
        const uint8_t r = rgba[i * 4U + 0U];
        const uint8_t g = rgba[i * 4U + 1U];
        const uint8_t b = rgba[i * 4U + 2U];
        hash ^= (static_cast<uint64_t>(r) << 16U) | (static_cast<uint64_t>(g) << 8U) | b;
        hash *= 1099511628211ULL;
        if (r > 20U || g > 25U || b > 40U) {
            ++non_bg_px;
        }
        color_buckets.insert(((r >> 4U) << 8U) | ((g >> 4U) << 4U) | (b >> 4U));
    }

    const size_t center_idx = ((sz / 2U) * sz + (sz / 2U)) * 4U;
    bool ppm_written = false;
    if (jOutPpmPath != nullptr) {
        const char* p = env->GetStringUTFChars(jOutPpmPath, nullptr);
        if (p != nullptr && p[0] != '\0') {
            FILE* fp = std::fopen(p, "wb");
            if (fp != nullptr) {
                std::fprintf(fp, "P6\n%u %u\n255\n", sz, sz);
                for (size_t i = 0U; i < total_px; ++i) {
                    std::fwrite(&rgba[i * 4U], 1U, 3U, fp);
                }
                std::fclose(fp);
                ppm_written = true;
            }
        }
        if (p != nullptr) env->ReleaseStringUTFChars(jOutPpmPath, p);
    }

    std::ostringstream oss;
    oss << "{\"ok\":" << (non_bg_px > 200U && color_buckets.size() >= 4U ? "true" : "false")
        << ",\"width\":" << sz
        << ",\"height\":" << sz
        << ",\"totalPixels\":" << total_px
        << ",\"nonBackgroundPixels\":" << non_bg_px
        << ",\"uniqueColorBuckets\":" << color_buckets.size()
        << ",\"centerRgba\":[" << static_cast<int>(rgba[center_idx]) << "," << static_cast<int>(rgba[center_idx + 1U])
        << "," << static_cast<int>(rgba[center_idx + 2U]) << "," << static_cast<int>(rgba[center_idx + 3U]) << "]"
        << ",\"cornerRgba\":[" << static_cast<int>(rgba[0]) << "," << static_cast<int>(rgba[1])
        << "," << static_cast<int>(rgba[2]) << "," << static_cast<int>(rgba[3]) << "]"
        << ",\"fnv1aHash\":\"" << std::hex << hash << std::dec << "\""
        << ",\"ppmWritten\":" << (ppm_written ? "true" : "false")
        << "}";
    return env->NewStringUTF(oss.str().c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_star_launcher_stargfx_StarGfxNative_nativeResizeSurface(
    JNIEnv* env,
    jclass,
    jlong sessionPtr,
    jobject jSurface,
    jint width,
    jint height) {
    auto* s = reinterpret_cast<StarGfxSession*>(sessionPtr);
    if (s == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(s->mutex);

    const uint32_t new_w = static_cast<uint32_t>(std::max(1, static_cast<int>(width)));
    const uint32_t new_h = static_cast<uint32_t>(std::max(1, static_cast<int>(height)));

    if (jSurface != nullptr) {
        ANativeWindow* next_win = ANativeWindow_fromSurface(env, jSurface);
        if (next_win != s->window) {
            if (s->surface != nullptr) {
                sgfx_destroy_surface(s->surface);
                s->surface = nullptr;
            }
            if (s->window != nullptr) {
                ANativeWindow_release(s->window);
                s->window = nullptr;
            }
            s->window = next_win;
            if (s->window != nullptr) {
                sgfx_create_surface_android(s->instance, s->window, &s->surface);
            } else {
                sgfx_create_surface_offscreen(s->instance, new_w, new_h, &s->surface);
            }
        } else if (next_win != nullptr) {
            ANativeWindow_release(next_win);
        }
    }

    if (new_w != s->width || new_h != s->height) {
        s->width = new_w;
        s->height = new_h;
        s->depth_tex.reset();
        SgfxTexture raw_depth = SGFX_NULL_HANDLE;
        SgfxTextureDesc dtd{s->width, s->height, 1U, 1U, 1U, 1U, SGFX_FORMAT_D32_SFLOAT, SGFX_TEXTURE_USAGE_DEPTH_STENCIL_TARGET | SGFX_TEXTURE_USAGE_TRANSIENT, "scene3d_depth"};
        if (sgfx_create_texture(s->device, &dtd, &raw_depth) == SGFX_SUCCESS) {
            s->depth_tex = sgfx::TextureHandle(s->device, raw_depth);
        }
    }

    if (s->surface != nullptr) {
        sgfx_surface_resize(s->surface, s->width, s->height, sgfx_surface_get_transform(s->surface));
    }
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_star_launcher_stargfx_StarGfxNative_nativeGetDiagnosticsJson(JNIEnv* env, jclass, jlong sessionPtr) {
    auto* s = reinterpret_cast<StarGfxSession*>(sessionPtr);
    if (s == nullptr) {
        return env->NewStringUTF("{\"error\":\"null session\"}");
    }
    std::lock_guard<std::mutex> lock(s->mutex);
    SgfxRuntimeDiagnostics diag{};
    sgfx_get_runtime_diagnostics(s->instance, s->device, s->surface, &diag);
    SgfxFrameMetrics fm{};
    sgfx_get_frame_metrics(s->device, &fm);
    SgfxPacingStats ps{};
    sgfx_device_get_pacing_stats(s->device, &ps);

    const char* bname = (diag.active_backend == SGFX_BACKEND_VULKAN) ? "VULKAN"
                      : ((diag.active_backend == SGFX_BACKEND_OPENGLES) ? "OPENGLES"
                      : ((diag.active_backend == SGFX_BACKEND_MOCK) ? "MOCK" : "AUTO"));

    std::ostringstream oss;
    oss << "{"
        << "\"starGfxVersion\":\"" << diag.api_version_major << "." << diag.api_version_minor << "." << diag.api_version_patch << "\""
        << ",\"instanceInitialized\":" << (diag.instance_initialized ? "true" : "false")
        << ",\"deviceInitialized\":" << (diag.device_initialized ? "true" : "false")
        << ",\"surfaceInitialized\":" << (diag.surface_initialized ? "true" : "false")
        << ",\"shaderCompiled\":" << (diag.shader_compiled ? "true" : "false")
        << ",\"pipelineCreated\":" << (diag.pipeline_created ? "true" : "false")
        << ",\"frameSubmitted\":" << (diag.frame_submitted ? "true" : "false")
        << ",\"framePresented\":" << (diag.frame_presented ? "true" : "false")
        << ",\"activeBackend\":\"" << bname << "\""
        << ",\"actualApiVersion\":\"" << json_escape(diag.actual_graphics_api_version) << "\""
        << ",\"eglVersion\":\"" << json_escape(diag.egl_version) << "\""
        << ",\"gpuVendor\":\"" << json_escape(diag.gpu_vendor) << "\""
        << ",\"gpuRenderer\":\"" << json_escape(diag.gpu_renderer) << "\""
        << ",\"gpuDriverVersion\":\"" << json_escape(diag.gpu_driver_version) << "\""
        << ",\"supportedExtensions\":\"" << json_escape(diag.supported_extensions) << "\""
        << ",\"physicalDeviceCount\":" << diag.physical_device_count
        << ",\"logicalDeviceCreated\":" << (diag.logical_device_created ? "true" : "false")
        << ",\"graphicsQueueFamily\":" << diag.graphics_queue_family
        << ",\"swapchainImageCount\":" << diag.swapchain_image_count
        << ",\"swapchainWidth\":" << diag.swapchain_width
        << ",\"swapchainHeight\":" << diag.swapchain_height
        << ",\"drawCallsSubmitted\":" << diag.draw_calls_submitted
        << ",\"framesPresented\":" << diag.frames_presented
        << ",\"lastShaderCompileUs\":" << diag.last_shader_compile_us
        << ",\"lastPipelineCreateUs\":" << diag.last_pipeline_create_us
        << ",\"frameIndex\":" << fm.frame_index
        << ",\"liveBuffers\":" << fm.live_buffers
        << ",\"liveTextures\":" << fm.live_textures
        << ",\"livePipelines\":" << fm.live_pipelines
        << ",\"avgFps\":" << ps.avg_fps
        << ",\"low1PercentFps\":" << ps.low_1_percent_fps
        << ",\"p50Ms\":" << ps.p50_ms
        << ",\"p95Ms\":" << ps.p95_ms
        << ",\"p99Ms\":" << ps.p99_ms
        << ",\"vmRssKb\":" << read_vm_rss_kb()
        << ",\"kgslBusyPct\":" << read_kgsl_busy_pct()
        << ",\"lastErrorCode\":" << static_cast<int>(diag.last_error_code)
        << ",\"lastErrorMessage\":\"" << json_escape(diag.last_error_message) << "\""
        << "}";
    return env->NewStringUTF(oss.str().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_star_launcher_stargfx_StarGfxNative_nativeRunBenchmark(
    JNIEnv* env,
    jclass,
    jlong sessionPtr,
    jint warmupFrames,
    jint measureFrames) {
    auto* s = reinterpret_cast<StarGfxSession*>(sessionPtr);
    if (s == nullptr || measureFrames <= 0) {
        return env->NewStringUTF("{\"ok\":false,\"error\":\"invalid args\"}");
    }
    std::lock_guard<std::mutex> lock(s->mutex);

    const int warmup = std::max(0, static_cast<int>(warmupFrames));
    const int measure = std::max(1, static_cast<int>(measureFrames));
    std::vector<double> frame_ms;
    std::vector<double> cpu_ms;
    frame_ms.reserve(static_cast<size_t>(measure));
    cpu_ms.reserve(static_cast<size_t>(measure));

    auto prev_t = std::chrono::steady_clock::now();
    for (int f = 0; f < warmup + measure; ++f) {
        const auto c0 = std::chrono::steady_clock::now();
        if (sgfx_begin_frame(s->device) == SGFX_SUCCESS) {
            sgfx_rg_reset(s->rg);
            DrawCtx ctx{s, s->swap_pipeline.get(), s->width, s->height, static_cast<float>(f) * 0.03f};
            SgfxRenderPassDesc pass{};
            pass.name = "stargfx_bench_swap";
            pass.render_area = {0, 0, s->width, s->height};
            pass.color_attachment_count = 1U;
            pass.color_attachments[0] = {SGFX_NULL_HANDLE, SGFX_LOAD_CLEAR, SGFX_STORE_STORE, {0.04f, 0.06f, 0.11f, 1.0f}};
            pass.has_depth_attachment = true;
            pass.depth_attachment = {s->depth_tex.get(), SGFX_LOAD_CLEAR, SGFX_STORE_TRANSIENT, 1.0f, 0U};
            pass.has_side_effects = true;
            pass.encode_fn = encode_scene_pass;
            pass.user_data = &ctx;
            sgfx_rg_add_graphics_pass(s->rg, &pass);
            sgfx_rg_compile_and_execute(s->rg, s->surface);
            sgfx_end_frame(s->device);
        }
        const auto c1 = std::chrono::steady_clock::now();
        const double ft = std::chrono::duration<double, std::milli>(c1 - prev_t).count();
        const double ct = std::chrono::duration<double, std::milli>(c1 - c0).count();
        prev_t = c1;
        if (f >= warmup) {
            frame_ms.push_back(ft);
            cpu_ms.push_back(ct);
        }
    }

    std::vector<double> sorted = frame_ms;
    std::sort(sorted.begin(), sorted.end());
    double sum = 0.0, cpu_sum = 0.0;
    for (double v : sorted) sum += v;
    for (double c : cpu_ms) cpu_sum += c;
    const double mean = sum / static_cast<double>(sorted.size());
    double sq = 0.0;
    for (double v : sorted) sq += (v - mean) * (v - mean);
    const double var = sq / static_cast<double>(sorted.size());
    auto pct = [&](double p) {
        const size_t idx = std::min(sorted.size() - 1U, static_cast<size_t>(std::floor(p * static_cast<double>(sorted.size() - 1U))));
        return sorted[idx];
    };
    const size_t tail = std::max<size_t>(1U, sorted.size() / 100U);
    double tail_sum = 0.0;
    for (size_t i = sorted.size() - tail; i < sorted.size(); ++i) tail_sum += sorted[i];
    const double low1_fps = (tail_sum > 0.0) ? (1000.0 / (tail_sum / static_cast<double>(tail))) : 0.0;

    std::ostringstream oss;
    oss << "{\"ok\":true"
        << ",\"frames\":" << measure
        << ",\"width\":" << s->width
        << ",\"height\":" << s->height
        << ",\"avgFps\":" << (mean > 0.0 ? 1000.0 / mean : 0.0)
        << ",\"low1Fps\":" << low1_fps
        << ",\"p50Ms\":" << pct(0.50)
        << ",\"p95Ms\":" << pct(0.95)
        << ",\"p99Ms\":" << pct(0.99)
        << ",\"varianceMs2\":" << var
        << ",\"avgCpuMs\":" << (cpu_sum / static_cast<double>(cpu_ms.size()))
        << ",\"vmRssKb\":" << read_vm_rss_kb()
        << ",\"kgslBusyPct\":" << read_kgsl_busy_pct()
        << "}";
    return env->NewStringUTF(oss.str().c_str());
}

JNIEXPORT void JNICALL
Java_com_star_launcher_stargfx_StarGfxNative_nativeDestroySession(JNIEnv*, jclass, jlong sessionPtr) {
    auto* s = reinterpret_cast<StarGfxSession*>(sessionPtr);
    if (s == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(s->mutex);
        destroy_session_unlocked(s);
    }
    delete s;
}

} // extern "C"
