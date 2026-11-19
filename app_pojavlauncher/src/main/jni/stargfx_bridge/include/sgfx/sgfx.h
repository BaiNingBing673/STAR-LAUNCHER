#ifndef SGFX_SGFX_H
#define SGFX_SGFX_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(SGFX_BUILD_SHARED) && defined(_WIN32)
    #if defined(SGFX_EXPORT_SYMBOLS)
        #define SGFX_API __declspec(dllexport)
    #else
        #define SGFX_API __declspec(dllimport)
    #endif
#elif defined(SGFX_BUILD_SHARED)
    #define SGFX_API __attribute__((visibility("default")))
#else
    #define SGFX_API
#endif

#define SGFX_API_VERSION_MAJOR 0
#define SGFX_API_VERSION_MINOR 1
#define SGFX_API_VERSION_PATCH 0

/* Forward declarations for Android native types without leaking platform headers */
struct ANativeWindow;
struct AHardwareBuffer;

/* Opaque Core Objects */
typedef struct SgfxInstance_T*    SgfxInstance;
typedef struct SgfxDevice_T*      SgfxDevice;
typedef struct SgfxSurface_T*     SgfxSurface;
typedef struct SgfxCommandList_T* SgfxCommandList;
typedef struct SgfxRenderGraph_T* SgfxRenderGraph;

/* Generation-indexed packed 64-bit resource handles (0 == invalid) */
typedef uint64_t SgfxBuffer;
typedef uint64_t SgfxTexture;
typedef uint64_t SgfxSampler;
typedef uint64_t SgfxShader;
typedef uint64_t SgfxPipeline;
typedef uint64_t SgfxResourceSet;
typedef uint64_t SgfxFence;

#define SGFX_NULL_HANDLE ((uint64_t)0ULL)
#define SGFX_MAX_COLOR_ATTACHMENTS 8U
#define SGFX_MAX_VERTEX_ATTRIBUTES 8U

typedef enum SgfxResult {
    SGFX_SUCCESS = 0,
    SGFX_ERROR_INVALID_ARGUMENT = -1,
    SGFX_ERROR_OUT_OF_MEMORY = -2,
    SGFX_ERROR_INVALID_HANDLE = -3,
    SGFX_ERROR_BACKEND_UNAVAILABLE = -4,
    SGFX_ERROR_DEVICE_LOST = -5,
    SGFX_ERROR_UNSUPPORTED = -6,
    SGFX_ERROR_VALIDATION = -7,
} SgfxResult;

typedef enum SgfxBackendType {
    SGFX_BACKEND_AUTO = 0,
    SGFX_BACKEND_VULKAN = 1,
    SGFX_BACKEND_OPENGLES = 2,
    SGFX_BACKEND_MOCK = 3, /* Host testing / deterministic graph verification */
} SgfxBackendType;

typedef enum SgfxGpuVendor {
    SGFX_VENDOR_UNKNOWN = 0,
    SGFX_VENDOR_QUALCOMM_ADRENO = 1,
    SGFX_VENDOR_ARM_MALI = 2,
    SGFX_VENDOR_IMAGINATION_POWERVR = 3,
    SGFX_VENDOR_AMD_XCLIPSE = 4,
    SGFX_VENDOR_SOFTWARE = 5,
} SgfxGpuVendor;

typedef enum SgfxLogLevel {
    SGFX_LOG_TRACE = 0,
    SGFX_LOG_INFO = 1,
    SGFX_LOG_WARN = 2,
    SGFX_LOG_ERROR = 3,
} SgfxLogLevel;

typedef enum SgfxFormat {
    SGFX_FORMAT_UNDEFINED = 0,
    SGFX_FORMAT_R8_UNORM,
    SGFX_FORMAT_R8G8_UNORM,
    SGFX_FORMAT_R8G8B8A8_UNORM,
    SGFX_FORMAT_R8G8B8A8_SRGB,
    SGFX_FORMAT_B8G8R8A8_UNORM,
    SGFX_FORMAT_B8G8R8A8_SRGB,
    SGFX_FORMAT_R10G10B10A2_UNORM,
    SGFX_FORMAT_R16G16B16A16_SFLOAT,
    SGFX_FORMAT_D16_UNORM,
    SGFX_FORMAT_D24_UNORM_S8_UINT,
    SGFX_FORMAT_D32_SFLOAT,
} SgfxFormat;

typedef enum SgfxBufferUsage {
    SGFX_BUFFER_USAGE_NONE = 0,
    SGFX_BUFFER_USAGE_VERTEX = 1 << 0,
    SGFX_BUFFER_USAGE_INDEX = 1 << 1,
    SGFX_BUFFER_USAGE_UNIFORM = 1 << 2,
    SGFX_BUFFER_USAGE_STORAGE = 1 << 3,
    SGFX_BUFFER_USAGE_INDIRECT = 1 << 4,
    SGFX_BUFFER_USAGE_TRANSFER_SRC = 1 << 5,
    SGFX_BUFFER_USAGE_TRANSFER_DST = 1 << 6,
} SgfxBufferUsage;

typedef enum SgfxMemoryDomain {
    SGFX_MEMORY_GPU_ONLY = 0,
    SGFX_MEMORY_CPU_TO_GPU_STREAM = 1,
    SGFX_MEMORY_GPU_TO_CPU_READBACK = 2,
} SgfxMemoryDomain;

typedef enum SgfxTextureUsage {
    SGFX_TEXTURE_USAGE_NONE = 0,
    SGFX_TEXTURE_USAGE_SAMPLED = 1 << 0,
    SGFX_TEXTURE_USAGE_COLOR_TARGET = 1 << 1,
    SGFX_TEXTURE_USAGE_DEPTH_STENCIL_TARGET = 1 << 2,
    SGFX_TEXTURE_USAGE_TRANSIENT = 1 << 3, /* Hints LAZILY_ALLOCATED / on-chip tile memory */
    SGFX_TEXTURE_USAGE_STORAGE = 1 << 4,   /* Keep unset on normal targets to preserve AFBC/UBWC */
    SGFX_TEXTURE_USAGE_TRANSFER_SRC = 1 << 5,
    SGFX_TEXTURE_USAGE_TRANSFER_DST = 1 << 6,
} SgfxTextureUsage;

typedef enum SgfxShaderStage {
    SGFX_STAGE_VERTEX = 1 << 0,
    SGFX_STAGE_FRAGMENT = 1 << 1,
    SGFX_STAGE_COMPUTE = 1 << 2,
} SgfxShaderStage;

typedef enum SgfxLoadOp {
    SGFX_LOAD_DONT_CARE = 0,
    SGFX_LOAD_LOAD = 1,
    SGFX_LOAD_CLEAR = 2,
} SgfxLoadOp;

typedef enum SgfxStoreOp {
    SGFX_STORE_DONT_CARE = 0,
    SGFX_STORE_STORE = 1,
    SGFX_STORE_TRANSIENT = 2,
} SgfxStoreOp;

typedef enum SgfxSurfaceTransform {
    SGFX_TRANSFORM_IDENTITY = 0,
    SGFX_TRANSFORM_ROTATE_90 = 1,
    SGFX_TRANSFORM_ROTATE_180 = 2,
    SGFX_TRANSFORM_ROTATE_270 = 3,
} SgfxSurfaceTransform;

typedef enum SgfxIndexType {
    SGFX_INDEX_UINT16 = 0,
    SGFX_INDEX_UINT32 = 1,
} SgfxIndexType;

typedef enum SgfxVertexFormat {
    SGFX_VERTEX_FORMAT_FLOAT2 = 0,
    SGFX_VERTEX_FORMAT_FLOAT3 = 1,
    SGFX_VERTEX_FORMAT_FLOAT4 = 2,
} SgfxVertexFormat;

typedef enum SgfxFilterMode {
    SGFX_FILTER_NEAREST = 0,
    SGFX_FILTER_LINEAR = 1,
} SgfxFilterMode;

typedef enum SgfxAddressMode {
    SGFX_ADDRESS_REPEAT = 0,
    SGFX_ADDRESS_CLAMP_TO_EDGE = 1,
} SgfxAddressMode;

typedef struct SgfxVertexAttribute {
    uint32_t location;
    SgfxVertexFormat format;
    uint32_t offset;
} SgfxVertexAttribute;

typedef struct SgfxSamplerDesc {
    SgfxFilterMode min_filter;
    SgfxFilterMode mag_filter;
    SgfxAddressMode address_u;
    SgfxAddressMode address_v;
    const char* debug_name;
} SgfxSamplerDesc;

typedef void (*SgfxLogCallback)(SgfxLogLevel level, const char* category, const char* message, void* user_data);

typedef struct SgfxInstanceDesc {
    const char* app_name;
    uint32_t app_version;
    SgfxBackendType preferred_backend;
    bool enable_validation;
    bool enable_profiler;
    SgfxLogCallback log_callback;
    void* log_user_data;
} SgfxInstanceDesc;

typedef struct SgfxDeviceProbeOverride {
    uint32_t vendor_id;
    uint32_t device_id;
    uint32_t driver_version;
    uint32_t api_version;
    const char* device_name;
} SgfxDeviceProbeOverride;

typedef struct SgfxDeviceDesc {
    uint32_t frames_in_flight; /* 0 defaults to 2 */
    const SgfxDeviceProbeOverride* probe_override; /* Optional; for host simulation/testing */
} SgfxDeviceDesc;

typedef struct SgfxGpuCapabilities {
    SgfxBackendType active_backend;
    SgfxGpuVendor vendor;
    uint32_t vendor_id;
    uint32_t device_id;
    uint32_t driver_version;
    char device_name[128];
    uint32_t max_tile_color_bpp; /* e.g., 128 or 256 bpp for TBDR subpass merge */
    uint32_t max_push_constant_bytes;
    bool supports_subpass_merge;
    bool supports_afbc_or_ubwc;
    bool supports_lazily_allocated;
    bool supports_fp16_arithmetic;
    bool supports_graphics_pipeline_library;
    bool supports_async_compute;
    bool recommend_gles_fallback;
} SgfxGpuCapabilities;

typedef struct SgfxBufferDesc {
    uint64_t size_bytes;
    uint32_t usage_flags;
    SgfxMemoryDomain memory_domain;
    const char* debug_name;
} SgfxBufferDesc;

typedef struct SgfxTextureDesc {
    uint32_t width;
    uint32_t height;
    uint32_t depth;
    uint32_t mip_levels;
    uint32_t array_layers;
    uint32_t sample_count;
    SgfxFormat format;
    uint32_t usage_flags;
    const char* debug_name;
} SgfxTextureDesc;

typedef struct SgfxShaderDesc {
    SgfxShaderStage stage;
    const void* bytecode;
    size_t bytecode_size;
    const char* entry_point;
    const char* debug_name;
} SgfxShaderDesc;

typedef struct SgfxGraphicsPipelineDesc {
    SgfxShader vertex_shader;
    SgfxShader fragment_shader;
    SgfxFormat color_formats[SGFX_MAX_COLOR_ATTACHMENTS];
    uint32_t color_attachment_count;
    SgfxFormat depth_stencil_format;
    uint32_t sample_count;
    bool depth_test_enable;
    bool depth_write_enable;
    bool blend_enable;
    const char* debug_name;
    /* Optional vertex buffer input layout & texture sampling binding (zero-init keeps legacy behavior) */
    uint32_t vertex_stride;
    SgfxVertexAttribute vertex_attributes[SGFX_MAX_VERTEX_ATTRIBUTES];
    uint32_t vertex_attribute_count;
    bool has_sampled_texture;
    bool cull_back_enable;
} SgfxGraphicsPipelineDesc;

typedef struct SgfxClearColor {
    float r, g, b, a;
} SgfxClearColor;

typedef struct SgfxColorAttachmentDesc {
    SgfxTexture texture;
    SgfxLoadOp load_op;
    SgfxStoreOp store_op;
    SgfxClearColor clear_color;
} SgfxColorAttachmentDesc;

typedef struct SgfxDepthAttachmentDesc {
    SgfxTexture texture;
    SgfxLoadOp depth_load_op;
    SgfxStoreOp depth_store_op;
    float clear_depth;
    uint32_t clear_stencil;
} SgfxDepthAttachmentDesc;

typedef struct SgfxViewport {
    float x, y, width, height, min_depth, max_depth;
} SgfxViewport;

typedef struct SgfxRect2D {
    int32_t x, y;
    uint32_t width, height;
} SgfxRect2D;

typedef void (*SgfxRenderPassEncodeFn)(SgfxCommandList cmd, void* user_data);

typedef struct SgfxRenderPassDesc {
    const char* name;
    SgfxRect2D render_area;
    SgfxColorAttachmentDesc color_attachments[SGFX_MAX_COLOR_ATTACHMENTS];
    uint32_t color_attachment_count;
    SgfxDepthAttachmentDesc depth_attachment;
    bool has_depth_attachment;
    /* Input textures sampled inside fragment shader (may trigger TBDR subpass merge) */
    const SgfxTexture* input_textures;
    uint32_t input_texture_count;
    bool has_side_effects; /* e.g., presents to swapchain or writes exported buffer */
    SgfxRenderPassEncodeFn encode_fn;
    void* user_data;
} SgfxRenderPassDesc;

typedef struct SgfxRenderGraphStats {
    uint32_t declared_passes;
    uint32_t culled_passes;
    uint32_t executed_passes;
    uint32_t merged_subpass_groups;
    uint32_t emitted_barriers;
    uint32_t total_draw_calls;
} SgfxRenderGraphStats;

typedef struct SgfxFrameMetrics {
    uint64_t frame_index;
    uint32_t live_buffers;
    uint32_t live_textures;
    uint32_t live_pipelines;
    uint32_t pending_deferred_destroys;
    uint64_t arena_bytes_allocated;
    SgfxRenderGraphStats last_graph_stats;
} SgfxFrameMetrics;

typedef enum SgfxThermalStatus {
    SGFX_THERMAL_NONE = 0,
    SGFX_THERMAL_LIGHT = 1,
    SGFX_THERMAL_MODERATE = 2,
    SGFX_THERMAL_SEVERE = 3,
    SGFX_THERMAL_CRITICAL = 4,
} SgfxThermalStatus;

typedef struct SgfxPacingConfig {
    uint32_t target_fps;
    bool enable_adaptive_resolution;
    float min_render_scale;
    float max_render_scale;
} SgfxPacingConfig;

typedef struct SgfxPacingStats {
    double avg_fps;
    double low_1_percent_fps;
    double p50_ms;
    double p95_ms;
    double p99_ms;
    double frame_time_variance_ms2;
    uint32_t stutter_count;
    float recommended_render_scale;
    SgfxThermalStatus thermal_status;
    bool adpf_active;
} SgfxPacingStats;

typedef struct SgfxRuntimeDiagnostics {
    uint32_t api_version_major;
    uint32_t api_version_minor;
    uint32_t api_version_patch;

    bool instance_initialized;
    bool device_initialized;
    bool surface_initialized;
    bool shader_compiled;
    bool pipeline_created;
    bool frame_submitted;
    bool frame_presented;

    SgfxBackendType active_backend;
    char actual_graphics_api_version[64];
    char egl_version[64];
    char gpu_vendor[64];
    char gpu_renderer[128];
    char gpu_driver_version[64];
    char supported_extensions[2048];

    uint32_t physical_device_count;
    bool logical_device_created;
    uint32_t graphics_queue_family;
    uint32_t swapchain_image_count;
    uint32_t swapchain_width;
    uint32_t swapchain_height;

    uint64_t draw_calls_submitted;
    uint64_t frames_presented;
    uint64_t last_shader_compile_us;
    uint64_t last_pipeline_create_us;

    SgfxResult last_error_code;
    char last_error_message[256];
} SgfxRuntimeDiagnostics;

/* Instance & Device */
SGFX_API SgfxResult sgfx_create_instance(const SgfxInstanceDesc* desc, SgfxInstance* out_instance);
SGFX_API void       sgfx_destroy_instance(SgfxInstance instance);

SGFX_API SgfxResult sgfx_create_device(SgfxInstance instance, const SgfxDeviceDesc* desc, SgfxDevice* out_device);
SGFX_API void       sgfx_destroy_device(SgfxDevice device);
SGFX_API SgfxResult sgfx_device_get_capabilities(SgfxDevice device, SgfxGpuCapabilities* out_caps);

/* Surface Lifecycle */
SGFX_API SgfxResult sgfx_create_surface_android(SgfxInstance instance, struct ANativeWindow* window, SgfxSurface* out_surface);
SGFX_API SgfxResult sgfx_create_surface_offscreen(SgfxInstance instance, uint32_t width, uint32_t height, SgfxSurface* out_surface);
SGFX_API void       sgfx_destroy_surface(SgfxSurface surface);
SGFX_API SgfxResult sgfx_surface_resize(SgfxSurface surface, uint32_t width, uint32_t height, SgfxSurfaceTransform transform);
SGFX_API SgfxSurfaceTransform sgfx_surface_get_transform(SgfxSurface surface);

/* Resource Creation, Upload & Deferred Destruction */
SGFX_API SgfxResult sgfx_create_buffer(SgfxDevice device, const SgfxBufferDesc* desc, SgfxBuffer* out_buffer);
SGFX_API SgfxResult sgfx_upload_buffer(SgfxDevice device, SgfxBuffer buffer, uint64_t offset, uint64_t size_bytes, const void* data);
SGFX_API void       sgfx_destroy_buffer(SgfxDevice device, SgfxBuffer buffer);

SGFX_API SgfxResult sgfx_create_texture(SgfxDevice device, const SgfxTextureDesc* desc, SgfxTexture* out_texture);
SGFX_API SgfxResult sgfx_upload_texture_2d(SgfxDevice device, SgfxTexture texture, uint32_t width, uint32_t height, const void* rgba8_data, size_t size_bytes);
SGFX_API SgfxResult sgfx_readback_texture_rgba8(SgfxDevice device, SgfxTexture texture, uint32_t width, uint32_t height, void* out_rgba8, size_t out_size_bytes);
SGFX_API void       sgfx_destroy_texture(SgfxDevice device, SgfxTexture texture);

SGFX_API SgfxResult sgfx_create_sampler(SgfxDevice device, const SgfxSamplerDesc* desc, SgfxSampler* out_sampler);
SGFX_API void       sgfx_destroy_sampler(SgfxDevice device, SgfxSampler sampler);

SGFX_API SgfxResult sgfx_create_shader(SgfxDevice device, const SgfxShaderDesc* desc, SgfxShader* out_shader);
SGFX_API void       sgfx_destroy_shader(SgfxDevice device, SgfxShader shader);

SGFX_API SgfxResult sgfx_create_graphics_pipeline(SgfxDevice device, const SgfxGraphicsPipelineDesc* desc, SgfxPipeline* out_pipeline);
SGFX_API void       sgfx_destroy_pipeline(SgfxDevice device, SgfxPipeline pipeline);

/* Command Lists (Thread-Safe Per-List Recording) */
SGFX_API SgfxResult sgfx_create_command_list(SgfxDevice device, SgfxCommandList* out_cmd);
SGFX_API void       sgfx_destroy_command_list(SgfxCommandList cmd);
SGFX_API SgfxResult sgfx_cmd_begin(SgfxCommandList cmd);
SGFX_API SgfxResult sgfx_cmd_end(SgfxCommandList cmd);
SGFX_API void       sgfx_cmd_bind_pipeline(SgfxCommandList cmd, SgfxPipeline pipeline);
SGFX_API void       sgfx_cmd_set_viewport(SgfxCommandList cmd, const SgfxViewport* viewport);
SGFX_API void       sgfx_cmd_set_scissor(SgfxCommandList cmd, const SgfxRect2D* scissor);
SGFX_API void       sgfx_cmd_push_constants(SgfxCommandList cmd, uint32_t offset, uint32_t size, const void* data);
SGFX_API void       sgfx_cmd_bind_vertex_buffer(SgfxCommandList cmd, uint32_t slot, SgfxBuffer buffer, uint64_t offset);
SGFX_API void       sgfx_cmd_bind_index_buffer(SgfxCommandList cmd, SgfxBuffer buffer, uint64_t offset, SgfxIndexType index_type);
SGFX_API void       sgfx_cmd_bind_texture(SgfxCommandList cmd, uint32_t slot, SgfxTexture texture, SgfxSampler sampler);
SGFX_API void       sgfx_cmd_draw(SgfxCommandList cmd, uint32_t vertex_count, uint32_t instance_count, uint32_t first_vertex, uint32_t first_instance);
SGFX_API void       sgfx_cmd_draw_indexed(SgfxCommandList cmd, uint32_t index_count, uint32_t instance_count, uint32_t first_index, int32_t vertex_offset, uint32_t first_instance);
SGFX_API void       sgfx_cmd_dispatch(SgfxCommandList cmd, uint32_t group_x, uint32_t group_y, uint32_t group_z);

/* Render Graph & Frame Execution */
SGFX_API SgfxResult sgfx_create_render_graph(SgfxDevice device, SgfxRenderGraph* out_graph);
SGFX_API void       sgfx_destroy_render_graph(SgfxRenderGraph graph);
SGFX_API void       sgfx_rg_reset(SgfxRenderGraph graph);
SGFX_API SgfxResult sgfx_rg_add_graphics_pass(SgfxRenderGraph graph, const SgfxRenderPassDesc* pass_desc);
SGFX_API SgfxResult sgfx_rg_set_export_texture(SgfxRenderGraph graph, SgfxTexture texture);
SGFX_API SgfxResult sgfx_rg_compile_and_execute(SgfxRenderGraph graph, SgfxSurface present_surface);

/* Frame Pacing & Telemetry */
SGFX_API SgfxResult sgfx_begin_frame(SgfxDevice device);
SGFX_API SgfxResult sgfx_end_frame(SgfxDevice device);
SGFX_API SgfxResult sgfx_get_frame_metrics(SgfxDevice device, SgfxFrameMetrics* out_metrics);
SGFX_API SgfxResult sgfx_device_set_pacing_config(SgfxDevice device, const SgfxPacingConfig* config);
SGFX_API SgfxResult sgfx_device_get_pacing_stats(SgfxDevice device, SgfxPacingStats* out_stats);
SGFX_API SgfxResult sgfx_device_set_thermal_override(SgfxDevice device, bool enable_override, SgfxThermalStatus status);
SGFX_API SgfxResult sgfx_get_runtime_diagnostics(SgfxInstance instance, SgfxDevice device, SgfxSurface surface, SgfxRuntimeDiagnostics* out_diag);

#ifdef __cplusplus
}
#endif

#endif /* SGFX_SGFX_H */
