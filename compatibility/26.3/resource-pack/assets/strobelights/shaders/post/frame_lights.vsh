#version 330
#extension GL_ARB_separate_shader_objects : require

#include <strobelights:frame_codec.glsl>

uniform sampler2D SceneSampler;
layout(std140) uniform FrameConfig { int FirstSlot; };

layout(location = 0) out vec2 texCoord;
layout(location = 1) flat out vec4 frameLights[8];
layout(location = 9) flat out vec4 frameProjection;
layout(location = 10) flat out vec3 frameOffset;

void main() {
    texCoord = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(texCoord * 2.0 - 1.0, 0.0, 1.0);
    frameProjection = vec4(1.0, 1.0, -1.0, -0.1);
    frameOffset = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        int slot = FirstSlot + i;
        frameLights[i] = vec4(0.0);
        if (!strobePacketValid(SceneSampler, slot)) continue;
        uint value = strobeReadPayload(SceneSampler, slot);
        if ((!strobeSource(value) && !strobeFlash(value) && !strobeIntegration(value)) || strobeShadow(value)) continue;
        vec3 position = strobeReadPosition(SceneSampler, slot);
        if (any(isnan(position)) || any(isinf(position)) || any(greaterThan(abs(position), vec3(4096.0)))) continue;
        bool occlusion = false;
        if (strobeSource(value)) for (int j = 0; j < 64; j++) {
            if (!strobePacketValid(SceneSampler, j) || !strobeShadow(strobeReadPayload(SceneSampler, j))) continue;
            if (distance(position, strobeReadPosition(SceneSampler, j)) < 0.02) occlusion = true;
        }
        frameLights[i] = vec4(position, float(value) * (occlusion ? -1.0 : 1.0));
        frameProjection = vec4(strobeReadFloat(SceneSampler, slot, 12), strobeReadFloat(SceneSampler, slot, 15),
            strobeReadFloat(SceneSampler, slot, 18), strobeReadFloat(SceneSampler, slot, 21));
        frameOffset = vec3(strobeReadFloat(SceneSampler, slot, 24), strobeReadFloat(SceneSampler, slot, 27),
            float((strobeReadWord(SceneSampler, slot, 34) >> 8u) & 1u));
    }
}
