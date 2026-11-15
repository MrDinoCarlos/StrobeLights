#ifndef STROBELIGHTS_FRAME_CODEC
#define STROBELIGHTS_FRAME_CODEC

const int STROBE_NATIVE_SLOTS = 64;
const int STROBE_INTEGRATION_SLOTS = 128;
const int STROBE_FRAME_SLOTS = STROBE_NATIVE_SLOTS + STROBE_INTEGRATION_SLOTS;

// Four-bit channels survive normalized framebuffer/sRGB round trips. Float bits
// travel losslessly as three 12-bit words instead of quantized world positions.
vec3 strobeWord(uint value) {
    return vec3(float(value & 15u), float((value >> 4u) & 15u), float((value >> 8u) & 15u)) / 15.0;
}
uint strobeUnword(vec3 color) {
    uvec3 digits = uvec3(clamp(floor(color * 15.0 + 0.5), 0.0, 15.0));
    return digits.r | (digits.g << 4u) | (digits.b << 8u);
}
ivec2 strobePacketOrigin(int slot) {
    return ivec2((slot % STROBE_NATIVE_SLOTS) * 5, (slot / STROBE_NATIVE_SLOTS) * 7);
}
ivec2 strobePacketPixel(int slot, int index) {
    return strobePacketOrigin(slot) + ivec2(index % 5, index / 5);
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
    if (slot < 0 || slot >= STROBE_FRAME_SLOTS) return false;
    if (textureSize(image, 0).x < 320 || textureSize(image, 0).y < strobePacketOrigin(slot).y + 8) return false;
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

// Input ABI used by XmasLights: eight banks of sixteen stable source slots.
bool strobeIntegration(uint value) {
    uint tag = value >> 20u;
    return tag == 8u || tag == 9u || tag == 11u || tag == 12u
        || tag == 14u || tag == 15u || tag == 6u || tag == 7u;
}
int strobeIntegrationSlot(uint value) {
    uint tag = value >> 20u;
    int bank = tag == 8u ? 0 : tag == 9u ? 1 : tag == 11u ? 2 : tag == 12u ? 3
        : tag == 14u ? 4 : tag == 15u ? 5 : tag == 6u ? 6 : 7;
    return STROBE_NATIVE_SLOTS + bank * 16 + int((value >> 12u) & 15u);
}
vec3 strobeDecodeAccumulation(vec4 encoded) { return encoded.rgb * max(1.0, encoded.a * 255.0); }
vec4 strobeEncodeAccumulation(vec3 energy) {
    float scale = clamp(ceil(max(max(energy.r, energy.g), energy.b)), 1.0, 255.0);
    return vec4(clamp(energy / scale, 0.0, 1.0), scale / 255.0);
}

#endif
