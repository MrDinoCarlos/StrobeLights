#version 150

#moj_import <utils.glsl>

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
            bool validCarrier = abs(anchorBytes.a - 5) <= 1
                && all(lessThanEqual(abs(anchorBytes.rgb - ivec3(2)), ivec3(1)));
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
                    validCarrier = validCarrier
                        && abs(cellBytes.a - 2) <= 1
                        && all(greaterThanEqual(cellBytes.rgb, ivec3(0)))
                        && all(lessThanEqual(cellBytes.rgb, ivec3(2)));
                    int triplet = (cellBytes.r >= 1 ? 1 : 0)
                        | (cellBytes.g >= 1 ? 2 : 0)
                        | (cellBytes.b >= 1 ? 4 : 0);
                    encodedValue |= triplet << (payloadIndex * 3);
                    payloadIndex += 1;
                }
            }
            if (validCarrier) {
                outColor = vec4(
                    float((encodedValue >> 16) & 255),
                    float((encodedValue >> 8) & 255),
                    float(encodedValue & 255),
                    255.0
                ) / 255.0;
            }
        }
    }
}
