#version 330
#extension GL_ARB_separate_shader_objects : require

#include <strobelights:frame_codec.glsl>

uniform sampler2D SceneSampler;
uniform sampler2D Light0Sampler;
uniform sampler2D Light1Sampler;
uniform sampler2D Light2Sampler;
uniform sampler2D Light3Sampler;
uniform sampler2D Light4Sampler;
uniform sampler2D Light5Sampler;
uniform sampler2D Light6Sampler;
uniform sampler2D Light7Sampler;
layout(location = 0) in vec2 texCoord;
layout(location = 1) flat in vec4 frameFlash;
layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 extent = textureSize(SceneSampler, 0);
    ivec2 pixel = clamp(ivec2(texCoord * vec2(extent)), ivec2(0), extent - 1);
    // Restore only live transport pixels at the lower edge, before the HUD.
    // The seven-pixel lane is inpainted from its immediately adjacent scene row.
    if (pixel.y < 7 && pixel.x < 320 && strobePacketValid(SceneSampler, pixel.x / 5)) pixel.y = 7;
    vec3 base = texelFetch(SceneSampler, pixel, 0).rgb;
    vec3 energy = 2.0 * (texture(Light0Sampler, texCoord).rgb + texture(Light1Sampler, texCoord).rgb
        + texture(Light2Sampler, texCoord).rgb + texture(Light3Sampler, texCoord).rgb
        + texture(Light4Sampler, texCoord).rgb + texture(Light5Sampler, texCoord).rgb
        + texture(Light6Sampler, texCoord).rgb + texture(Light7Sampler, texCoord).rgb);
    vec3 illuminated = base * (vec3(1.0) + energy * 0.65) + energy * 0.22;
    float power = max(max(energy.r, energy.g), energy.b);
    if (power > 0.001) {
        vec3 tint = energy / power;
        float luminance = dot(illuminated, vec3(0.2126, 0.7152, 0.0722));
        vec3 colorized = illuminated * (vec3(0.28) + tint * 0.72) + tint * luminance * 0.12;
        illuminated = mix(illuminated, colorized, min(power, 1.0) * 0.72);
    }
    fragColor = vec4(mix(illuminated, frameFlash.rgb, frameFlash.a), 1.0);
}
