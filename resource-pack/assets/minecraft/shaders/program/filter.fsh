#version 150

#define NEAR 0.05
#define FAR 1024.0
#define LIGHTDEPTH 0.025

// Minecraft 1.20.1's post-program loader (and OptiFine I6's wrapper around it)
// does not preprocess shader include directives. Keep the shared subset inline.
float LinearizeDepth(float depth) {
    float z = depth * 2.0 - 1.0;
    return 2.0 * (NEAR * FAR) / (FAR + NEAR - z * (FAR - NEAR));
}

uniform sampler2D DiffuseSampler;
uniform sampler2D DiffuseDepthSampler;
uniform float Range;
uniform vec2 InSize;

in vec2 texCoord;

out vec4 outColor;

bool leftGuard(vec4 color) {
    ivec4 bytes = ivec4(floor(color * 255.0 + 0.5));
    return all(equal(bytes, ivec4(194, 69, 253, 255)));
}

bool rightGuard(vec4 color) {
    ivec4 bytes = ivec4(floor(color * 255.0 + 0.5));
    return all(equal(bytes, ivec4(61, 186, 2, 255)));
}

void main() {
    outColor = vec4(0.0);
    float depth = texture(DiffuseDepthSampler, texCoord).r;

    if (depth < LIGHTDEPTH) {
        depth = LinearizeDepth(depth / LIGHTDEPTH);
        if (depth < Range) {
            vec2 oneTexel = 1.0 / InSize;
            vec4 candidate = texture(DiffuseSampler, texCoord);
            vec4 guardBefore = texture(
                DiffuseSampler,
                texCoord - vec2(oneTexel.x, 0.0)
            );
            vec4 guardAfter = texture(
                DiffuseSampler,
                texCoord + vec2(oneTexel.x, 0.0)
            );
            if (candidate.a >= 254.5 / 255.0
                && leftGuard(guardBefore)
                && rightGuard(guardAfter)) {
                outColor = vec4(candidate.rgb, 1.0);
            }
        }
    }
}
