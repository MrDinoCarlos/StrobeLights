#ifndef STROBELIGHTS_FRAME_CODEC
#define STROBELIGHTS_FRAME_CODEC

// Four-bit channels survive normalized framebuffer/sRGB round trips. Float bits
// travel losslessly as three 12-bit words instead of quantized world positions.
vec3 strobeWord(uint value) {
    return vec3(float(value & 15u), float((value >> 4u) & 15u), float((value >> 8u) & 15u)) / 15.0;
}
uint strobeUnword(vec3 color) {
    uvec3 digits = uvec3(clamp(floor(color * 15.0 + 0.5), 0.0, 15.0));
    return digits.r | (digits.g << 4u) | (digits.b << 8u);
}
ivec2 strobePacketPixel(int slot, int index) {
    return ivec2(slot * 5 + index % 5, index / 5);
}
uint strobeReadWord(sampler2D image, int slot, int index) {
    return strobeUnword(texelFetch(image, strobePacketPixel(slot, index), 0).rgb);
}
float strobeReadFloat(sampler2D image, int slot, int first) {
    uint bits = strobeReadWord(image, slot, first)
        | (strobeReadWord(image, slot, first + 1) << 12u)
        | ((strobeReadWord(image, slot, first + 2) & 255u) << 24u);
    return uintBitsToFloat(bits);
}
bool strobePacketValid(sampler2D image, int slot) {
    if (textureSize(image, 0).x < 320 || textureSize(image, 0).y < 8) return false;
    return strobeReadWord(image, slot, 0) == 0x7B3u
        && (strobeReadWord(image, slot, 34) & 255u) == uint(slot)
        && strobeReadWord(image, slot, 31) == 0x51Du
        && strobeReadWord(image, slot, 32) == 0x51Du
        && strobeReadWord(image, slot, 33) == 0x51Du;
}
uint strobeReadPayload(sampler2D image, int slot) {
    return strobeReadWord(image, slot, 1) | (strobeReadWord(image, slot, 2) << 12u);
}
vec3 strobeReadPosition(sampler2D image, int slot) {
    return vec3(strobeReadFloat(image, slot, 3), strobeReadFloat(image, slot, 6), strobeReadFloat(image, slot, 9));
}
bool strobeSource(uint value) { return (value >> 20u) == 10u && (value & 15u) == 5u; }
bool strobeFlash(uint value) { return (value >> 20u) == 13u && (value & 1u) == 1u; }
bool strobeShadow(uint value) { return value == 0xAF0005u; }

#endif
