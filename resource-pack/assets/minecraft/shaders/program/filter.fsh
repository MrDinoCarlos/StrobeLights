#version 150

#define CARRIER_DEPTH_BIAS 0.001
#define CARRIER_DEPTH_STEP 0.0000005
#define CARRIER_DEPTH_BUCKET 0.00001
#define CARRIER_DEPTH_LEVELS 16383.0
#define CARRIER_MAX_LINEAR_DEPTH 128.0
#define CARRIER_DEPTH_TOLERANCE 0.00000018

uniform sampler2D DiffuseSampler;
uniform sampler2D DiffuseDepthSampler;
uniform float Range;

in vec2 texCoord;

out vec4 outColor;

void main() {
    outColor = vec4(0.0);
    // Keep the primary post-pass sampler live for Minecraft's EffectInstance.
    // The main color attachment is normalized, so this guard never discards a
    // real scene fragment.
    if (texture(DiffuseSampler, texCoord).a < -1.0) {
        discard;
    }
    float centerDepth = texture(DiffuseDepthSampler, texCoord).r;
    float rawDepthCode = (centerDepth - CARRIER_DEPTH_BIAS)
        / CARRIER_DEPTH_BUCKET;
    int linearDepthCode = int(floor(rawDepthCode + 0.5));
    float carrierDepth = CARRIER_DEPTH_BIAS
        + float(linearDepthCode) * CARRIER_DEPTH_BUCKET;
    float linearDepth = float(linearDepthCode)
        / CARRIER_DEPTH_LEVELS * CARRIER_MAX_LINEAR_DEPTH;
    bool validCarrier = linearDepthCode >= 0
        && linearDepthCode <= int(CARRIER_DEPTH_LEVELS)
        && linearDepth <= Range
        && abs(centerDepth - carrierDepth) <= CARRIER_DEPTH_TOLERANCE;

    int encodedValue = 0;
    int payloadIndex = 0;
    vec2 oneTexel = 1.0 / vec2(textureSize(DiffuseDepthSampler, 0));
    for (int y = -1; y <= 1; y += 1) {
        for (int x = -1; x <= 1; x += 1) {
            if (x == 0 && y == 0) {
                continue;
            }
            float cellDepth = texture(
                DiffuseDepthSampler,
                texCoord + vec2(float(x), float(y)) * oneTexel
            ).r;
            int cellCode = int(floor(
                (cellDepth - carrierDepth) / CARRIER_DEPTH_STEP + 0.5
            )) - 1;
            int triplet = cellCode & 7;
            int cellSignature = cellCode >> 3;
            float expectedDepth = carrierDepth
                + float(cellCode + 1) * CARRIER_DEPTH_STEP;
            validCarrier = validCarrier
                && cellSignature == payloadIndex + 1
                && abs(cellDepth - expectedDepth)
                    <= CARRIER_DEPTH_TOLERANCE;
            encodedValue |= (triplet & 7) << (payloadIndex * 3);
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
