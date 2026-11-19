#ifndef SGFX_SGFX_HPP
#define SGFX_SGFX_HPP

#include "sgfx.h"
#include <utility>

namespace sgfx {

template <typename HandleT, void (*DestroyFn)(SgfxDevice, HandleT)>
class ScopedResource {
public:
    ScopedResource() = default;
    ScopedResource(SgfxDevice device, HandleT handle) noexcept : device_(device), handle_(handle) {}
    ~ScopedResource() { reset(); }

    ScopedResource(const ScopedResource&) = delete;
    ScopedResource& operator=(const ScopedResource&) = delete;

    ScopedResource(ScopedResource&& other) noexcept
        : device_(std::exchange(other.device_, nullptr)),
          handle_(std::exchange(other.handle_, SGFX_NULL_HANDLE)) {}

    ScopedResource& operator=(ScopedResource&& other) noexcept {
        if (this != &other) {
            reset();
            device_ = std::exchange(other.device_, nullptr);
            handle_ = std::exchange(other.handle_, SGFX_NULL_HANDLE);
        }
        return *this;
    }

    void reset() noexcept {
        if (device_ != nullptr && handle_ != SGFX_NULL_HANDLE) {
            DestroyFn(device_, handle_);
        }
        handle_ = SGFX_NULL_HANDLE;
    }

    [[nodiscard]] HandleT get() const noexcept { return handle_; }
    [[nodiscard]] explicit operator bool() const noexcept { return handle_ != SGFX_NULL_HANDLE; }

private:
    SgfxDevice device_ = nullptr;
    HandleT handle_ = SGFX_NULL_HANDLE;
};

using BufferHandle   = ScopedResource<SgfxBuffer, sgfx_destroy_buffer>;
using TextureHandle  = ScopedResource<SgfxTexture, sgfx_destroy_texture>;
using SamplerHandle  = ScopedResource<SgfxSampler, sgfx_destroy_sampler>;
using ShaderHandle   = ScopedResource<SgfxShader, sgfx_destroy_shader>;
using PipelineHandle = ScopedResource<SgfxPipeline, sgfx_destroy_pipeline>;

} // namespace sgfx

#endif // SGFX_SGFX_HPP
