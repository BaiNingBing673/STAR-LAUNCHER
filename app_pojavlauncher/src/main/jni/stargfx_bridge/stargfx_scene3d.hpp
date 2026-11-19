#ifndef STARGFX_SCENE3D_HPP
#define STARGFX_SCENE3D_HPP

#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

namespace sgfx_bridge {

struct SceneVertex {
    float px, py, pz;
    float u, v;
    float r, g, b;
};

struct ScenePushConstants {
    float mvp[16];
    float tint[4];
};

static_assert(sizeof(SceneVertex) == 32, "SceneVertex must be 32 bytes");
static_assert(sizeof(ScenePushConstants) == 80, "ScenePushConstants must fit 128B push constant limit");

inline constexpr uint32_t kScene3DVertSpv[] = {
    0x07230203U, 0x00010000U, 0x000d000aU, 0x0000002dU, 0x00000000U, 0x00020011U, 0x00000001U, 0x0006000bU,
    0x00000001U, 0x4c534c47U, 0x6474732eU, 0x3035342eU, 0x00000000U, 0x0003000eU, 0x00000000U, 0x00000001U,
    0x000b000fU, 0x00000000U, 0x00000004U, 0x6e69616dU, 0x00000000U, 0x0000000dU, 0x00000019U, 0x00000025U,
    0x00000027U, 0x0000002aU, 0x0000002bU, 0x00030003U, 0x00000002U, 0x000001c2U, 0x000a0004U, 0x475f4c47U,
    0x4c474f4fU, 0x70635f45U, 0x74735f70U, 0x5f656c79U, 0x656e696cU, 0x7269645fU, 0x69746365U, 0x00006576U,
    0x00080004U, 0x475f4c47U, 0x4c474f4fU, 0x6e695f45U, 0x64756c63U, 0x69645f65U, 0x74636572U, 0x00657669U,
    0x00040005U, 0x00000004U, 0x6e69616dU, 0x00000000U, 0x00060005U, 0x0000000bU, 0x505f6c67U, 0x65567265U,
    0x78657472U, 0x00000000U, 0x00060006U, 0x0000000bU, 0x00000000U, 0x505f6c67U, 0x7469736fU, 0x006e6f69U,
    0x00070006U, 0x0000000bU, 0x00000001U, 0x505f6c67U, 0x746e696fU, 0x657a6953U, 0x00000000U, 0x00070006U,
    0x0000000bU, 0x00000002U, 0x435f6c67U, 0x4470696cU, 0x61747369U, 0x0065636eU, 0x00070006U, 0x0000000bU,
    0x00000003U, 0x435f6c67U, 0x446c6c75U, 0x61747369U, 0x0065636eU, 0x00030005U, 0x0000000dU, 0x00000000U,
    0x00040005U, 0x00000011U, 0x68737550U, 0x00000000U, 0x00040006U, 0x00000011U, 0x00000000U, 0x0070766dU,
    0x00050006U, 0x00000011U, 0x00000001U, 0x746e6974U, 0x00000000U, 0x00030005U, 0x00000013U, 0x00006370U,
    0x00040005U, 0x00000019U, 0x736f5061U, 0x00000000U, 0x00030005U, 0x00000025U, 0x00765576U, 0x00030005U,
    0x00000027U, 0x00765561U, 0x00040005U, 0x0000002aU, 0x6c6f4376U, 0x00000000U, 0x00040005U, 0x0000002bU,
    0x6c6f4361U, 0x00000000U, 0x00050048U, 0x0000000bU, 0x00000000U, 0x0000000bU, 0x00000000U, 0x00050048U,
    0x0000000bU, 0x00000001U, 0x0000000bU, 0x00000001U, 0x00050048U, 0x0000000bU, 0x00000002U, 0x0000000bU,
    0x00000003U, 0x00050048U, 0x0000000bU, 0x00000003U, 0x0000000bU, 0x00000004U, 0x00030047U, 0x0000000bU,
    0x00000002U, 0x00040048U, 0x00000011U, 0x00000000U, 0x00000005U, 0x00050048U, 0x00000011U, 0x00000000U,
    0x00000023U, 0x00000000U, 0x00050048U, 0x00000011U, 0x00000000U, 0x00000007U, 0x00000010U, 0x00050048U,
    0x00000011U, 0x00000001U, 0x00000023U, 0x00000040U, 0x00030047U, 0x00000011U, 0x00000002U, 0x00040047U,
    0x00000019U, 0x0000001eU, 0x00000000U, 0x00040047U, 0x00000025U, 0x0000001eU, 0x00000000U, 0x00040047U,
    0x00000027U, 0x0000001eU, 0x00000001U, 0x00040047U, 0x0000002aU, 0x0000001eU, 0x00000001U, 0x00040047U,
    0x0000002bU, 0x0000001eU, 0x00000002U, 0x00020013U, 0x00000002U, 0x00030021U, 0x00000003U, 0x00000002U,
    0x00030016U, 0x00000006U, 0x00000020U, 0x00040017U, 0x00000007U, 0x00000006U, 0x00000004U, 0x00040015U,
    0x00000008U, 0x00000020U, 0x00000000U, 0x0004002bU, 0x00000008U, 0x00000009U, 0x00000001U, 0x0004001cU,
    0x0000000aU, 0x00000006U, 0x00000009U, 0x0006001eU, 0x0000000bU, 0x00000007U, 0x00000006U, 0x0000000aU,
    0x0000000aU, 0x00040020U, 0x0000000cU, 0x00000003U, 0x0000000bU, 0x0004003bU, 0x0000000cU, 0x0000000dU,
    0x00000003U, 0x00040015U, 0x0000000eU, 0x00000020U, 0x00000001U, 0x0004002bU, 0x0000000eU, 0x0000000fU,
    0x00000000U, 0x00040018U, 0x00000010U, 0x00000007U, 0x00000004U, 0x0004001eU, 0x00000011U, 0x00000010U,
    0x00000007U, 0x00040020U, 0x00000012U, 0x00000009U, 0x00000011U, 0x0004003bU, 0x00000012U, 0x00000013U,
    0x00000009U, 0x00040020U, 0x00000014U, 0x00000009U, 0x00000010U, 0x00040017U, 0x00000017U, 0x00000006U,
    0x00000003U, 0x00040020U, 0x00000018U, 0x00000001U, 0x00000017U, 0x0004003bU, 0x00000018U, 0x00000019U,
    0x00000001U, 0x0004002bU, 0x00000006U, 0x0000001bU, 0x3f800000U, 0x00040020U, 0x00000021U, 0x00000003U,
    0x00000007U, 0x00040017U, 0x00000023U, 0x00000006U, 0x00000002U, 0x00040020U, 0x00000024U, 0x00000003U,
    0x00000023U, 0x0004003bU, 0x00000024U, 0x00000025U, 0x00000003U, 0x00040020U, 0x00000026U, 0x00000001U,
    0x00000023U, 0x0004003bU, 0x00000026U, 0x00000027U, 0x00000001U, 0x00040020U, 0x00000029U, 0x00000003U,
    0x00000017U, 0x0004003bU, 0x00000029U, 0x0000002aU, 0x00000003U, 0x0004003bU, 0x00000018U, 0x0000002bU,
    0x00000001U, 0x00050036U, 0x00000002U, 0x00000004U, 0x00000000U, 0x00000003U, 0x000200f8U, 0x00000005U,
    0x00050041U, 0x00000014U, 0x00000015U, 0x00000013U, 0x0000000fU, 0x0004003dU, 0x00000010U, 0x00000016U,
    0x00000015U, 0x0004003dU, 0x00000017U, 0x0000001aU, 0x00000019U, 0x00050051U, 0x00000006U, 0x0000001cU,
    0x0000001aU, 0x00000000U, 0x00050051U, 0x00000006U, 0x0000001dU, 0x0000001aU, 0x00000001U, 0x00050051U,
    0x00000006U, 0x0000001eU, 0x0000001aU, 0x00000002U, 0x00070050U, 0x00000007U, 0x0000001fU, 0x0000001cU,
    0x0000001dU, 0x0000001eU, 0x0000001bU, 0x00050091U, 0x00000007U, 0x00000020U, 0x00000016U, 0x0000001fU,
    0x00050041U, 0x00000021U, 0x00000022U, 0x0000000dU, 0x0000000fU, 0x0003003eU, 0x00000022U, 0x00000020U,
    0x0004003dU, 0x00000023U, 0x00000028U, 0x00000027U, 0x0003003eU, 0x00000025U, 0x00000028U, 0x0004003dU,
    0x00000017U, 0x0000002cU, 0x0000002bU, 0x0003003eU, 0x0000002aU, 0x0000002cU, 0x000100fdU, 0x00010038U,
};

inline constexpr uint32_t kScene3DFragSpv[] = {
    0x07230203U, 0x00010000U, 0x000d000aU, 0x00000035U, 0x00000000U, 0x00020011U, 0x00000001U, 0x0006000bU,
    0x00000001U, 0x4c534c47U, 0x6474732eU, 0x3035342eU, 0x00000000U, 0x0003000eU, 0x00000000U, 0x00000001U,
    0x0008000fU, 0x00000004U, 0x00000004U, 0x6e69616dU, 0x00000000U, 0x00000011U, 0x00000015U, 0x0000001aU,
    0x00030010U, 0x00000004U, 0x00000007U, 0x00030003U, 0x00000002U, 0x000001c2U, 0x000a0004U, 0x475f4c47U,
    0x4c474f4fU, 0x70635f45U, 0x74735f70U, 0x5f656c79U, 0x656e696cU, 0x7269645fU, 0x69746365U, 0x00006576U,
    0x00080004U, 0x475f4c47U, 0x4c474f4fU, 0x6e695f45U, 0x64756c63U, 0x69645f65U, 0x74636572U, 0x00657669U,
    0x00040005U, 0x00000004U, 0x6e69616dU, 0x00000000U, 0x00040005U, 0x00000009U, 0x65786574U, 0x0000006cU,
    0x00040005U, 0x0000000dU, 0x78655475U, 0x00000000U, 0x00030005U, 0x00000011U, 0x00765576U, 0x00050005U,
    0x00000015U, 0x4374756fU, 0x726f6c6fU, 0x00000000U, 0x00040005U, 0x0000001aU, 0x6c6f4376U, 0x00000000U,
    0x00040005U, 0x0000001eU, 0x68737550U, 0x00000000U, 0x00040006U, 0x0000001eU, 0x00000000U, 0x0070766dU,
    0x00050006U, 0x0000001eU, 0x00000001U, 0x746e6974U, 0x00000000U, 0x00030005U, 0x00000020U, 0x00006370U,
    0x00040047U, 0x0000000dU, 0x00000022U, 0x00000000U, 0x00040047U, 0x0000000dU, 0x00000021U, 0x00000000U,
    0x00040047U, 0x00000011U, 0x0000001eU, 0x00000000U, 0x00040047U, 0x00000015U, 0x0000001eU, 0x00000000U,
    0x00040047U, 0x0000001aU, 0x0000001eU, 0x00000001U, 0x00040048U, 0x0000001eU, 0x00000000U, 0x00000005U,
    0x00050048U, 0x0000001eU, 0x00000000U, 0x00000023U, 0x00000000U, 0x00050048U, 0x0000001eU, 0x00000000U,
    0x00000007U, 0x00000010U, 0x00050048U, 0x0000001eU, 0x00000001U, 0x00000023U, 0x00000040U, 0x00030047U,
    0x0000001eU, 0x00000002U, 0x00020013U, 0x00000002U, 0x00030021U, 0x00000003U, 0x00000002U, 0x00030016U,
    0x00000006U, 0x00000020U, 0x00040017U, 0x00000007U, 0x00000006U, 0x00000004U, 0x00040020U, 0x00000008U,
    0x00000007U, 0x00000007U, 0x00090019U, 0x0000000aU, 0x00000006U, 0x00000001U, 0x00000000U, 0x00000000U,
    0x00000000U, 0x00000001U, 0x00000000U, 0x0003001bU, 0x0000000bU, 0x0000000aU, 0x00040020U, 0x0000000cU,
    0x00000000U, 0x0000000bU, 0x0004003bU, 0x0000000cU, 0x0000000dU, 0x00000000U, 0x00040017U, 0x0000000fU,
    0x00000006U, 0x00000002U, 0x00040020U, 0x00000010U, 0x00000001U, 0x0000000fU, 0x0004003bU, 0x00000010U,
    0x00000011U, 0x00000001U, 0x00040020U, 0x00000014U, 0x00000003U, 0x00000007U, 0x0004003bU, 0x00000014U,
    0x00000015U, 0x00000003U, 0x00040017U, 0x00000016U, 0x00000006U, 0x00000003U, 0x00040020U, 0x00000019U,
    0x00000001U, 0x00000016U, 0x0004003bU, 0x00000019U, 0x0000001aU, 0x00000001U, 0x00040018U, 0x0000001dU,
    0x00000007U, 0x00000004U, 0x0004001eU, 0x0000001eU, 0x0000001dU, 0x00000007U, 0x00040020U, 0x0000001fU,
    0x00000009U, 0x0000001eU, 0x0004003bU, 0x0000001fU, 0x00000020U, 0x00000009U, 0x00040015U, 0x00000021U,
    0x00000020U, 0x00000001U, 0x0004002bU, 0x00000021U, 0x00000022U, 0x00000001U, 0x00040020U, 0x00000023U,
    0x00000009U, 0x00000007U, 0x00040015U, 0x00000028U, 0x00000020U, 0x00000000U, 0x0004002bU, 0x00000028U,
    0x00000029U, 0x00000003U, 0x00040020U, 0x0000002aU, 0x00000007U, 0x00000006U, 0x00040020U, 0x0000002dU,
    0x00000009U, 0x00000006U, 0x00050036U, 0x00000002U, 0x00000004U, 0x00000000U, 0x00000003U, 0x000200f8U,
    0x00000005U, 0x0004003bU, 0x00000008U, 0x00000009U, 0x00000007U, 0x0004003dU, 0x0000000bU, 0x0000000eU,
    0x0000000dU, 0x0004003dU, 0x0000000fU, 0x00000012U, 0x00000011U, 0x00050057U, 0x00000007U, 0x00000013U,
    0x0000000eU, 0x00000012U, 0x0003003eU, 0x00000009U, 0x00000013U, 0x0004003dU, 0x00000007U, 0x00000017U,
    0x00000009U, 0x0008004fU, 0x00000016U, 0x00000018U, 0x00000017U, 0x00000017U, 0x00000000U, 0x00000001U,
    0x00000002U, 0x0004003dU, 0x00000016U, 0x0000001bU, 0x0000001aU, 0x00050085U, 0x00000016U, 0x0000001cU,
    0x00000018U, 0x0000001bU, 0x00050041U, 0x00000023U, 0x00000024U, 0x00000020U, 0x00000022U, 0x0004003dU,
    0x00000007U, 0x00000025U, 0x00000024U, 0x0008004fU, 0x00000016U, 0x00000026U, 0x00000025U, 0x00000025U,
    0x00000000U, 0x00000001U, 0x00000002U, 0x00050085U, 0x00000016U, 0x00000027U, 0x0000001cU, 0x00000026U,
    0x00050041U, 0x0000002aU, 0x0000002bU, 0x00000009U, 0x00000029U, 0x0004003dU, 0x00000006U, 0x0000002cU,
    0x0000002bU, 0x00060041U, 0x0000002dU, 0x0000002eU, 0x00000020U, 0x00000022U, 0x00000029U, 0x0004003dU,
    0x00000006U, 0x0000002fU, 0x0000002eU, 0x00050085U, 0x00000006U, 0x00000030U, 0x0000002cU, 0x0000002fU,
    0x00050051U, 0x00000006U, 0x00000031U, 0x00000027U, 0x00000000U, 0x00050051U, 0x00000006U, 0x00000032U,
    0x00000027U, 0x00000001U, 0x00050051U, 0x00000006U, 0x00000033U, 0x00000027U, 0x00000002U, 0x00070050U,
    0x00000007U, 0x00000034U, 0x00000031U, 0x00000032U, 0x00000033U, 0x00000030U, 0x0003003eU, 0x00000015U,
    0x00000034U, 0x000100fdU, 0x00010038U,
};

inline constexpr char kScene3DVertEssl[] = R"(#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in vec3 aCol;

layout(std140) uniform Push {
    mat4 mvp;
    vec4 tint;
};

out vec2 vUv;
out vec3 vCol;

void main() {
    gl_Position = mvp * vec4(aPos, 1.0);
    vUv = aUv;
    vCol = aCol;
}
)";

inline constexpr char kScene3DFragEssl[] = R"(#version 300 es
precision mediump float;

in vec2 vUv;
in vec3 vCol;

layout(std140) uniform Push {
    mat4 mvp;
    vec4 tint;
};

uniform sampler2D uTex;
out vec4 outColor;

void main() {
    vec4 texel = texture(uTex, vUv);
    outColor = vec4(texel.rgb * vCol * tint.rgb, texel.a * tint.a);
}
)";

inline void build_scene_geometry(std::vector<SceneVertex>& verts, std::vector<uint16_t>& indices) {
    verts = {
        // Front (+Z)
        {-0.6f, -0.6f,  0.6f, 0.f, 0.f, 1.0f, 0.45f, 0.35f},
        { 0.6f, -0.6f,  0.6f, 1.f, 0.f, 1.0f, 0.45f, 0.35f},
        { 0.6f,  0.6f,  0.6f, 1.f, 1.f, 1.0f, 0.65f, 0.50f},
        {-0.6f,  0.6f,  0.6f, 0.f, 1.f, 1.0f, 0.65f, 0.50f},
        // Back (-Z)
        { 0.6f, -0.6f, -0.6f, 0.f, 0.f, 0.30f, 0.85f, 0.95f},
        {-0.6f, -0.6f, -0.6f, 1.f, 0.f, 0.30f, 0.85f, 0.95f},
        {-0.6f,  0.6f, -0.6f, 1.f, 1.f, 0.50f, 0.95f, 1.00f},
        { 0.6f,  0.6f, -0.6f, 0.f, 1.f, 0.50f, 0.95f, 1.00f},
        // Right (+X)
        { 0.6f, -0.6f,  0.6f, 0.f, 0.f, 0.40f, 0.95f, 0.45f},
        { 0.6f, -0.6f, -0.6f, 1.f, 0.f, 0.40f, 0.95f, 0.45f},
        { 0.6f,  0.6f, -0.6f, 1.f, 1.f, 0.60f, 1.00f, 0.65f},
        { 0.6f,  0.6f,  0.6f, 0.f, 1.f, 0.60f, 1.00f, 0.65f},
        // Left (-X)
        {-0.6f, -0.6f, -0.6f, 0.f, 0.f, 0.95f, 0.80f, 0.25f},
        {-0.6f, -0.6f,  0.6f, 1.f, 0.f, 0.95f, 0.80f, 0.25f},
        {-0.6f,  0.6f,  0.6f, 1.f, 1.f, 1.00f, 0.90f, 0.45f},
        {-0.6f,  0.6f, -0.6f, 0.f, 1.f, 1.00f, 0.90f, 0.45f},
        // Top (+Y)
        {-0.6f,  0.6f,  0.6f, 0.f, 0.f, 0.90f, 0.90f, 1.00f},
        { 0.6f,  0.6f,  0.6f, 1.f, 0.f, 0.90f, 0.90f, 1.00f},
        { 0.6f,  0.6f, -0.6f, 1.f, 1.f, 0.70f, 0.80f, 1.00f},
        {-0.6f,  0.6f, -0.6f, 0.f, 1.f, 0.70f, 0.80f, 1.00f},
        // Bottom (-Y)
        {-0.6f, -0.6f, -0.6f, 0.f, 0.f, 0.65f, 0.45f, 0.95f},
        { 0.6f, -0.6f, -0.6f, 1.f, 0.f, 0.65f, 0.45f, 0.95f},
        { 0.6f, -0.6f,  0.6f, 1.f, 1.f, 0.80f, 0.60f, 1.00f},
        {-0.6f, -0.6f,  0.6f, 0.f, 1.f, 0.80f, 0.60f, 1.00f},
        // Floor quad at y = -0.9 (tests depth occlusion with cube)
        {-1.8f, -0.9f,  1.8f, 0.f, 0.f, 0.35f, 0.45f, 0.55f},
        { 1.8f, -0.9f,  1.8f, 2.f, 0.f, 0.35f, 0.45f, 0.55f},
        { 1.8f, -0.9f, -1.8f, 2.f, 2.f, 0.45f, 0.55f, 0.65f},
        {-1.8f, -0.9f, -1.8f, 0.f, 2.f, 0.45f, 0.55f, 0.65f},
    };
    indices.clear();
    for (uint16_t face = 0; face < 7; ++face) {
        const uint16_t base = static_cast<uint16_t>(face * 4U);
        indices.push_back(base + 0U);
        indices.push_back(base + 1U);
        indices.push_back(base + 2U);
        indices.push_back(base + 0U);
        indices.push_back(base + 2U);
        indices.push_back(base + 3U);
    }
}

inline void build_checker_texture_rgba8(uint32_t size, std::vector<uint8_t>& out_rgba) {
    out_rgba.resize(static_cast<size_t>(size) * static_cast<size_t>(size) * 4U);
    for (uint32_t y = 0; y < size; ++y) {
        for (uint32_t x = 0; x < size; ++x) {
            const bool checker = (((x / 8U) + (y / 8U)) & 1U) == 0U;
            const size_t idx = (static_cast<size_t>(y) * size + x) * 4U;
            const uint8_t grad_x = static_cast<uint8_t>((x * 255U) / (size > 1U ? size - 1U : 1U));
            const uint8_t grad_y = static_cast<uint8_t>((y * 255U) / (size > 1U ? size - 1U : 1U));
            if (checker) {
                out_rgba[idx + 0U] = 240U;
                out_rgba[idx + 1U] = static_cast<uint8_t>(140U + (grad_x / 3U));
                out_rgba[idx + 2U] = static_cast<uint8_t>(120U + (grad_y / 3U));
            } else {
                out_rgba[idx + 0U] = static_cast<uint8_t>(55U + (grad_x / 4U));
                out_rgba[idx + 1U] = static_cast<uint8_t>(95U + (grad_y / 4U));
                out_rgba[idx + 2U] = 175U;
            }
            out_rgba[idx + 3U] = 255U;
        }
    }
}

inline void mat4_mul(const float a[16], const float b[16], float out[16]) {
    float tmp[16]{};
    for (int col = 0; col < 4; ++col) {
        for (int row = 0; row < 4; ++row) {
            tmp[col * 4 + row] =
                a[0 * 4 + row] * b[col * 4 + 0] +
                a[1 * 4 + row] * b[col * 4 + 1] +
                a[2 * 4 + row] * b[col * 4 + 2] +
                a[3 * 4 + row] * b[col * 4 + 3];
        }
    }
    std::memcpy(out, tmp, sizeof(tmp));
}

inline void build_orbit_mvp(float aspect, float angle_rad, bool flip_y_for_vk, float out_mvp[16]) {
    const float fov = 1.04719755f; // 60 deg
    const float f = 1.0f / std::tan(fov * 0.5f);
    const float zn = 0.1f, zf = 50.0f;
    float proj[16]{};
    proj[0] = f / (aspect > 0.01f ? aspect : 1.0f);
    proj[5] = flip_y_for_vk ? -f : f;
    proj[10] = -(zf + zn) / (zf - zn);
    proj[11] = -1.0f;
    proj[14] = -(2.0f * zf * zn) / (zf - zn);

    const float eye[3] = {2.5f * std::sin(angle_rad), 1.35f, 2.5f * std::cos(angle_rad)};
    const float center[3] = {0.0f, -0.1f, 0.0f};
    float fvec[3] = {center[0] - eye[0], center[1] - eye[1], center[2] - eye[2]};
    const float flen = std::sqrt(fvec[0] * fvec[0] + fvec[1] * fvec[1] + fvec[2] * fvec[2]);
    fvec[0] /= flen; fvec[1] /= flen; fvec[2] /= flen;

    const float up[3] = {0.0f, 1.0f, 0.0f};
    float s[3] = {
        fvec[1] * up[2] - fvec[2] * up[1],
        fvec[2] * up[0] - fvec[0] * up[2],
        fvec[0] * up[1] - fvec[1] * up[0]
    };
    const float slen = std::sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2]);
    s[0] /= slen; s[1] /= slen; s[2] /= slen;

    float u[3] = {
        s[1] * fvec[2] - s[2] * fvec[1],
        s[2] * fvec[0] - s[0] * fvec[2],
        s[0] * fvec[1] - s[1] * fvec[0]
    };

    float view[16]{};
    view[0] = s[0];  view[4] = s[1];  view[8]  = s[2];  view[12] = -(s[0]*eye[0] + s[1]*eye[1] + s[2]*eye[2]);
    view[1] = u[0];  view[5] = u[1];  view[9]  = u[2];  view[13] = -(u[0]*eye[0] + u[1]*eye[1] + u[2]*eye[2]);
    view[2] = -fvec[0]; view[6] = -fvec[1]; view[10] = -fvec[2]; view[14] = (fvec[0]*eye[0] + fvec[1]*eye[1] + fvec[2]*eye[2]);
    view[15] = 1.0f;

    const float ca = std::cos(angle_rad * 0.5f), sa = std::sin(angle_rad * 0.5f);
    float model[16]{};
    model[0] = ca; model[2] = -sa;
    model[5] = 1.0f;
    model[8] = sa; model[10] = ca;
    model[15] = 1.0f;

    float pv[16]{};
    mat4_mul(proj, view, pv);
    mat4_mul(pv, model, out_mvp);
}

} // namespace sgfx_bridge

#endif // STARGFX_SCENE3D_HPP
