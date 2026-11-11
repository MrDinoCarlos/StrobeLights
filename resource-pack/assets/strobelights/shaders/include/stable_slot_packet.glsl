#moj_import <strobelights:stable_slot_common.glsl>
ivec4 strobeStableTexel(sampler2D image, vec2 coord) {
    return ivec4(floor(texture(image, coord) * 255.0 + 0.5));
}
bool strobeStablePacket(sampler2D image, vec2 center) {
    if (!all(equal(strobeStableTexel(image, center), ivec4(29, 177, 239, 255)))) return false;
    vec2 pixel = 1.0 / vec2(textureSize(image, 0));
    int packedValue = strobeStableInteger(texture(image, center + vec2(-1.0, -1.0) * pixel).rgb);
    if (packedValue < 0 || packedValue > 8388607) return false;
    int value = strobeStableSourceValue(packedValue);
    ivec4 projectionBytes = strobeStableTexel(image, center + vec2(1.0, 0.0) * pixel);
    if (!strobeStableValue(value) || projectionBytes.b != 197 || projectionBytes.a != 255) return false;
    int projection = (projectionBytes.r << 8) | projectionBytes.g;
    int index = 0;
    for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
        if (x == 0 && y == 0) continue;
        ivec4 sampleBytes = strobeStableTexel(image, center + vec2(float(x), float(y)) * pixel);
        if (sampleBytes.a != 255) return false;
        if (index >= 1 && index <= 3) {
            if (sampleBytes.r * 62500 + sampleBytes.g * 250 + sampleBytes.b > 4194304) return false;
        } else {
            ivec3 expected = ivec3(floor(strobeStablePacketColour(index, value, projection, vec3(0.0)) * 255.0 + 0.5));
            if (!all(equal(sampleBytes.rgb, expected))) return false;
        }
        index++;
    }
    return true;
}
