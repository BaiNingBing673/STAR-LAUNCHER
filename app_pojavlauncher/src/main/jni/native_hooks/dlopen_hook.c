#include "environ/environ.h"
#include "utils.h"
#include "native_hooks.h"
#include "log.h"

#include <bytehook.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
// WARNING: Hooking dlopen and dlsym does not work for all devices, seems to be a conflict with
// the turnip loader, perhaps Dobby would fare better.
extern void *maybe_load_vulkan(void);

typedef void *(*dlopen_func_t)(const char *, int);
typedef int (*dlclose_func_t)(void *);
typedef void *(*dlsym_func_t)(void *, const char *);
typedef void *(*sdl_gl_get_proc_address_t)(const char *);
typedef jint (*JNI_OnLoad_t)(JavaVM *vm, void *reserved);

static _Atomic(void *) s_vulkan_handle = NULL;
static _Atomic(void *) s_sdl_opengl_handle = NULL;
static atomic_int s_sdl_opengl_refs = 0;
static _Atomic(sdl_gl_get_proc_address_t) s_real_sdl_gl_get_proc_address = NULL;

typedef struct {
    const char *library_name;
    const char *library_rename;
    void *handle;
    JNI_OnLoad_t jni_onload;
} LibraryRedirect;

static LibraryRedirect libRedirectInfo[] = {
        { "libSDL3.so", "libSDL3.so" },
        { "libSDL2.so", "libSDL2.so" },
        { NULL, NULL } // Null termination
};

__attribute__((constructor)) static void init() {
    // We can't hook dlopen so we make do with this.
    libRedirectInfo[0].handle = dlopen("libSDL3.so", RTLD_LOCAL | RTLD_LAZY);
    libRedirectInfo[1].handle = dlopen("libSDL2.so", RTLD_LOCAL | RTLD_LAZY);
}

// Loose matching of lib file name to hard coded list
static LibraryRedirect* get_library_redirect_if_needed(const char *filename) {
    if (filename == NULL)
        return NULL;
    const char *basename = strrchr(filename, '/');
    basename = basename ? basename + 1 : filename;
    for (size_t i = 0; libRedirectInfo[i].library_name != NULL; ++i) {
        if (strstr(basename, libRedirectInfo[i].library_name) != NULL) {
            return &libRedirectInfo[i];
        }
    }
    return NULL;
}

void *custom_dlopen(const char *filename, int flags) {
    LibraryRedirect *redirect = get_library_redirect_if_needed(filename);
    const char *target_filename = filename;
    if (redirect != NULL) {
        target_filename = redirect->library_rename;
        LOGI("Redirecting dlopen: %s -> %s", filename, target_filename);
    }

    void *result = BYTEHOOK_CALL_PREV(
            custom_dlopen,
            dlopen_func_t,
            target_filename,
            flags);

    if (redirect != NULL)
        redirect->handle = result;

    BYTEHOOK_POP_STACK();
    return result;
}

static LibraryRedirect *get_redirect_by_handle(void *handle) {
    if (handle == NULL)
        return NULL;

    for (size_t i = 0; libRedirectInfo[i].library_name != NULL; ++i) {
        if (libRedirectInfo[i].handle == handle)
            return &libRedirectInfo[i];
    }

    return NULL;
}

static jint rerouted_jni_onload(
        LibraryRedirect *redirect,
        JavaVM *vm,
        void *reserved) {

    if (redirect == NULL) {
        return JNI_ERR;
    }

    if (pojav_environ != NULL &&
            pojav_environ->dalvikJavaVMPtr == vm &&
            redirect->jni_onload != NULL) {
        return ((JNI_OnLoad_t) redirect->jni_onload)(vm, reserved);
    }

    return JNI_VERSION_1_4;
}

#define DEFINE_JNI_WRAPPER(id)                                              \
    static jint jni_onload_##id(                                            \
            JavaVM *vm,                                                     \
            void *reserved) {                                               \
        return rerouted_jni_onload(&libRedirectInfo[id], vm, reserved);     \
    }

// This is a bit magicky yeah
DEFINE_JNI_WRAPPER(0)
DEFINE_JNI_WRAPPER(1)

static JNI_OnLoad_t get_jni_onload_wrapper(
        LibraryRedirect *redirect) {
    if (redirect == &libRedirectInfo[0]) {
        return jni_onload_0;
    }

    if (redirect == &libRedirectInfo[1]) {
        return jni_onload_1;
    }

    return NULL;
}

static const char *path_basename(const char *path) {
    if (path == NULL) {
        return NULL;
    }
    const char *slash = strrchr(path, '/');
    return slash != NULL ? slash + 1 : path;
}

static bool is_vulkan_library(const char *filename) {
    const char *base = path_basename(filename);
    if (base == NULL) {
        return false;
    }
    return strcmp(base, "libvulkan.so") == 0 || strcmp(base, "libvulkan.so.1") == 0;
}

static bool is_sdl_opengl_library(const char *filename) {
    const char *target = getenv("SDL_OPENGL_LIBRARY");
    if (filename == NULL || target == NULL || target[0] == '\0') {
        return false;
    }
    if (strcmp(filename, target) == 0) {
        return true;
    }
    const char *base_file = path_basename(filename);
    const char *base_target = path_basename(target);
    return base_file != NULL && base_target != NULL && strcmp(base_file, base_target) == 0;
}

static bool sdl3_caller_filter(const char *caller_path_name, void *arg) {
    (void) arg;
    return caller_path_name != NULL && strstr(caller_path_name, "libSDL3.so") != NULL;
}

static void *custom_sdl3_dlopen(const char *filename, int flags) {
    if (is_vulkan_library(filename)) {
        // Route SDL3's libvulkan.so load through maybe_load_vulkan() so SDL_Vulkan_* and LWJGL VK
        // share the same Turnip/VULKAN_PTR handle and identical vkGetInstanceProcAddr address.
        void *vk_handle = maybe_load_vulkan();
        if (vk_handle != NULL) {
            atomic_store(&s_vulkan_handle, vk_handle);
            LOGI("Redirected SDL3 Vulkan dlopen(%s) -> %p", filename, vk_handle);
            BYTEHOOK_POP_STACK();
            return vk_handle;
        }
    }

    void *result = BYTEHOOK_CALL_PREV(
            custom_sdl3_dlopen,
            dlopen_func_t,
            filename,
            flags);

    if (result != NULL && is_sdl_opengl_library(filename)) {
        void *prev = atomic_load(&s_sdl_opengl_handle);
        if (prev == result) {
            atomic_fetch_add(&s_sdl_opengl_refs, 1);
        } else {
            atomic_store(&s_sdl_opengl_handle, result);
            atomic_store(&s_sdl_opengl_refs, 1);
            LOGI("Captured SDL3 OpenGL dlopen(%s) -> %p", filename, result);
        }
    }

    BYTEHOOK_POP_STACK();
    return result;
}

static int custom_sdl3_dlclose(void *handle) {
    if (handle != NULL && handle == atomic_load(&s_vulkan_handle)) {
        // Keep the launcher's cached VULKAN_PTR alive across SDL_Vulkan_UnloadLibrary.
        BYTEHOOK_POP_STACK();
        return 0;
    }
    if (handle != NULL && handle == atomic_load(&s_sdl_opengl_handle)) {
        // SDL_EGL_LoadLibrary may dlopen+dlclose the GL library multiple times while probing paths;
        // retain s_sdl_opengl_handle until the final reference is unloaded.
        if (atomic_fetch_sub(&s_sdl_opengl_refs, 1) <= 1) {
            atomic_store(&s_sdl_opengl_handle, NULL);
            atomic_store(&s_sdl_opengl_refs, 0);
        }
    }

    int result = BYTEHOOK_CALL_PREV(
            custom_sdl3_dlclose,
            dlclose_func_t,
            handle);

    BYTEHOOK_POP_STACK();
    return result;
}

static void *custom_SDL_GL_GetProcAddress(const char *proc) {
    sdl_gl_get_proc_address_t real_fn = atomic_load(&s_real_sdl_gl_get_proc_address);
    if (proc == NULL || real_fn == NULL) {
        return NULL;
    }

    void *gl_handle = atomic_load(&s_sdl_opengl_handle);
    if (gl_handle != NULL) {
        // Resolve exported symbols from SDL_OPENGL_LIBRARY first (matching LWJGL's patched GL lookup)
        // so SFPEW/MobileGlues wrappers are never bypassed by RTLD_DEFAULT or EGL >= 1.5 lookup order.
        void *sym = dlsym(gl_handle, proc);
        if (sym != NULL) {
            return sym;
        }

        typedef void *(*gl_get_proc_t)(const char *);
        static const char *const kProcAddressSymbols[] = {
                "glXGetProcAddress",
                "glXGetProcAddressARB",
                "eglGetProcAddress"
        };
        for (size_t i = 0; i < sizeof(kProcAddressSymbols) / sizeof(kProcAddressSymbols[0]); ++i) {
            gl_get_proc_t gpa = (gl_get_proc_t) dlsym(gl_handle, kProcAddressSymbols[i]);
            if (gpa != NULL) {
                sym = gpa(proc);
                if (sym != NULL) {
                    return sym;
                }
                break;
            }
        }
    }

    return real_fn(proc);
}

void *custom_dlsym(void *handle, const char *symbol) {
    void *result = BYTEHOOK_CALL_PREV(
            custom_dlsym,
            dlsym_func_t,
            handle,
            symbol);
    LibraryRedirect *redirect = get_redirect_by_handle(handle);
    if (redirect != NULL && symbol != NULL && strstr(redirect->library_name, "libSDL3") != NULL) {
        if (strcmp(symbol, "JNI_OnLoad") == 0) {
            redirect->jni_onload = (JNI_OnLoad_t) result;
            // This outputs in the minecraft logs
            LOGI("STAR Launcher: Intercepted dlsym on SDL3 JNI_OnLoad: %p", result);
            BYTEHOOK_POP_STACK();
            return get_jni_onload_wrapper(redirect);
        }
        if (strcmp(symbol, "SDL_GL_GetProcAddress") == 0 && result != NULL) {
            atomic_store(&s_real_sdl_gl_get_proc_address, (sdl_gl_get_proc_address_t) result);
            LOGI("STAR Launcher: Intercepted dlsym on SDL3 SDL_GL_GetProcAddress: %p", result);
            BYTEHOOK_POP_STACK();
            return (void *) &custom_SDL_GL_GetProcAddress;
        }
    }
    BYTEHOOK_POP_STACK();
    return result;
}

void create_dlopen_hooks(bytehook_hook_all_t bytehook_hook_all_p,
                         bytehook_hook_partial_t bytehook_hook_partial_p) {
    // Only scope dlopen/dlclose hooks to libSDL3.so so Turnip's namespace loader is never affected.
    bytehook_stub_t stub_sdl3_dlopen = NULL;
    bytehook_stub_t stub_sdl3_dlclose = NULL;
    if (bytehook_hook_partial_p != NULL) {
        stub_sdl3_dlopen = bytehook_hook_partial_p(
                &sdl3_caller_filter,
                NULL,
                NULL,
                "dlopen",
                &custom_sdl3_dlopen,
                NULL,
                NULL);
        stub_sdl3_dlclose = bytehook_hook_partial_p(
                &sdl3_caller_filter,
                NULL,
                NULL,
                "dlclose",
                &custom_sdl3_dlclose,
                NULL,
                NULL);
    }
    bytehook_stub_t stub_dlsym =
            bytehook_hook_all_p(NULL, "dlsym", &custom_dlsym, NULL, NULL);
    LOGI("Successfully initialized dlopen hooks, stub: %p %p %p",
         stub_sdl3_dlopen, stub_sdl3_dlclose, stub_dlsym);
}