#version 330
#extension GL_ARB_separate_shader_objects : require
#include <strobelights:frame_codec.glsl>
uniform sampler2D SceneSampler;
layout(location = 0) out vec2 texCoord;
layout(location = 1) flat out vec4 frameFlash;
void main() {
    texCoord = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(texCoord * 2.0 - 1.0, 0.0, 1.0);
    frameFlash = vec4(1.0, 1.0, 1.0, 0.0);
    for (int i = 0; i < 64; i++) {
        if (!strobePacketValid(SceneSampler, i)) continue;
        uint value = strobeReadPayload(SceneSampler, i);
        if (!strobeFlash(value)) continue;
        float strength = float((value >> 13u) & 127u) / 127.0;
        if (strength > frameFlash.a) frameFlash = vec4(
            vec3(float((value >> 9u) & 15u), float((value >> 5u) & 15u), float((value >> 1u) & 15u)) / 15.0, strength);
    }
}
