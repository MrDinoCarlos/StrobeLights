#version 330
#extension GL_ARB_separate_shader_objects : require

#include <strobelights:frame_codec.glsl>

uniform sampler2D DepthSampler;
layout(location = 0) in vec2 texCoord;
layout(location = 1) flat in vec4 frameLights[8];
layout(location = 9) flat in vec4 frameProjection;
layout(location = 10) flat in vec3 frameOffset;
layout(location = 0) out vec4 fragColor;

vec3 viewPosition(vec2 uv, float depth) {
    float ndc = frameOffset.z > 0.5 ? depth : depth * 2.0 - 1.0;
    float denominator = ndc + frameProjection.z;
    float z = -frameProjection.w / (abs(denominator) < 0.000001 ? -0.000001 : denominator);
    return vec3((uv * 2.0 - 1.0 + frameOffset.xy) * (-z) / frameProjection.xy, z);
}
float visibility(vec3 light, vec3 surface) {
    for (int i = 1; i <= 16; i++) {
        vec3 ray = mix(light, surface, float(i) / 17.0);
        if (ray.z >= -0.05) continue;
        vec2 uv = (ray.xy * frameProjection.xy / (-ray.z) - frameOffset.xy) * 0.5 + 0.5;
        if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) continue;
        float depth = texture(DepthSampler, uv).r;
        if (depth >= 0.999999) continue;
        vec3 blocker = viewPosition(uv, depth);
        float behind = blocker.z - ray.z;
        if (behind > 0.08 && behind < 1.5) return 0.12;
    }
    return 1.0;
}
void main() {
    float depth = texture(DepthSampler, texCoord).r;
    vec3 surface = viewPosition(texCoord, depth);
    vec3 lightSum = vec3(0.0);
    float flash = 0.0;
    vec3 flashColor = vec3(1.0);
    for (int i = 0; i < 8; i++) {
        uint value = uint(abs(frameLights[i].w));
        if (strobeFlash(value)) {
            float strength = float((value >> 13u) & 127u) / 127.0;
            if (strength > flash) {
                flash = strength;
                flashColor = vec3(float((value >> 9u) & 15u), float((value >> 5u) & 15u), float((value >> 1u) & 15u)) / 15.0;
            }
        } else if (strobeSource(value) && depth < 0.999999) {
            vec3 color = vec3(float((value >> 12u) & 15u), float((value >> 8u) & 15u), float((value >> 4u) & 15u)) / 15.0;
            float energy = max(max(color.r, color.g), color.b);
            float size = float(((value >> 16u) & 15u) + 1u) * 0.25;
            float radius = mix(2.5, 18.0, sqrt(energy)) * size;
            float separation = distance(surface, frameLights[i].xyz);
            if (energy > 0.0 && separation < radius) {
                float falloff = pow(max(0.0, 1.0 - separation / radius), 1.4);
                float shadow = frameLights[i].w < 0.0 ? visibility(frameLights[i].xyz, surface) : 1.0;
                lightSum += color * falloff * shadow;
            }
        }
    }
    // RGB occupies [0, 0.5]; alpha is the strongest directional camera flash.
    // Composition uses its color from the corresponding live packet.
    fragColor = vec4(clamp(lightSum * 0.5, 0.0, 1.0), flash);
}
