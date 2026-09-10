#version 150

#moj_import <minecraft:utils.glsl>

uniform sampler2D DiffuseSampler;
uniform sampler2D DiffuseDepthSampler;
uniform float Range;

in vec2 texCoord;

out vec4 outColor;

void main() {
    outColor = vec4(0.0);
    float depth = texture(DiffuseDepthSampler, texCoord).r;

    if (depth < LIGHTDEPTH) {
        depth = LinearizeDepth(depth / LIGHTDEPTH);
        if (depth < Range) {
            ivec4 anchorBytes = ivec4(floor(
                texture(DiffuseSampler, texCoord) * 255.0 + 0.5
            ));
            bool strobeCarrier = abs(anchorBytes.a - 5) <= 1
                && all(lessThanEqual(abs(anchorBytes.rgb - ivec3(2)), ivec3(1)));
            bool trpCarrier = anchorBytes.a >= 7 && anchorBytes.a <= 22;
            int expectedTrpRgb = int(floor(float(anchorBytes.a) * 0.4 + 0.5));
            trpCarrier = trpCarrier && all(lessThanEqual(
                abs(anchorBytes.rgb - ivec3(expectedTrpRgb)),
                ivec3(1)
            ));
            bool validCarrier = strobeCarrier || trpCarrier;
            int encodedValue = 0;
            int payloadIndex = 0;
            for (int y = -1; y <= 1; y += 1) {
                for (int x = -1; x <= 1; x += 1) {
                    if (x == 0 && y == 0) {
                        continue;
                    }
                    ivec4 cellBytes = ivec4(floor(texture(
                        DiffuseSampler,
                        texCoord + vec2(float(x), float(y)) / vec2(textureSize(
                            DiffuseSampler,
                            0
                        ))
                    ) * 255.0 + 0.5));
                    bool validAlpha = payloadIndex < 4
                        ? cellBytes.a >= 2 && cellBytes.a <= 17
                        : cellBytes.a == 2;
                    vec3 expectedBits = step(vec3(float(cellBytes.a) * 0.25),
                        vec3(cellBytes.rgb));
                    vec3 expectedRgb = expectedBits * float(cellBytes.a) * 0.5;
                    validCarrier = validCarrier && validAlpha
                        && all(lessThanEqual(abs(vec3(cellBytes.rgb) - expectedRgb), vec3(0.51)));
                    int triplet = int(expectedBits.r)
                        | (int(expectedBits.g) << 1)
                        | (int(expectedBits.b) << 2);
                    encodedValue |= triplet << (payloadIndex * 3);
                    payloadIndex += 1;
                }
            }
            if (validCarrier) {
                // Alpha 1 is a native StrobeLights source. TRP uses the sixteen
                // non-zero byte values to transport the low radius nibble.
                float sourceMetadata = strobeCarrier
                    ? 1.0 : float(anchorBytes.a - 6) / 255.0;
                outColor = vec4(
                    float((encodedValue >> 16) & 255),
                    float((encodedValue >> 8) & 255),
                    float(encodedValue & 255),
                    sourceMetadata * 255.0
                ) / 255.0;
            }
        }
    }
}
