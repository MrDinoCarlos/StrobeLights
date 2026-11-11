// STROBE_STABLE_SLOT_V1: collision-free, backwards-compatible integration sources.
#ifndef STROBE_STABLE_SLOT_COMMON
#define STROBE_STABLE_SLOT_COMMON
bool strobeStableValue(int value) {
    int bank = (value >> 20) & 15;
    return bank == 6 || bank == 7 || bank == 8 || bank == 9
        || bank == 11 || bank == 12 || bank == 14 || bank == 15;
}
int strobeStableSlot(int value) {
    int tag = (value >> 20) & 15;
    int bank = tag == 8 ? 0 : tag == 9 ? 1 : tag == 11 ? 2 : tag == 12 ? 3
        : tag == 14 ? 4 : tag == 15 ? 5 : tag == 6 ? 6 : 7;
    return bank * 16 + ((value >> 12) & 15);
}
bool strobeStableMetadata(float value) { return value > 0.7 && value < 0.8; }
int strobeStableProjectionK(float value) {
    float normalized = log2(clamp(value, 0.0000152587890625, 256.0) / 0.0000152587890625) / 24.0;
    return int(floor(normalized * 65535.0 + 0.5));
}
vec3 strobeStableRawBytes(int value) {
    return vec3(float((value >> 16) & 255), float((value >> 8) & 255), float(value & 255)) / 255.0;
}
vec3 strobeStableBytes(int value) {
    // Base 250 keeps every packet byte below TRP's reserved 252/253 row tags.
    return vec3(float(value / 62500), float((value / 250) % 250), float(value % 250)) / 255.0;
}
int strobeStableInteger(vec3 colour) {
    ivec3 bytes = ivec3(floor(colour * 255.0 + 0.5));
    return bytes.r * 62500 + bytes.g * 250 + bytes.b;
}
int strobeStablePacketValue(int value) { return (((value >> 16) & 15) << 19) | (strobeStableSlot(value) << 12) | (value & 4095); }
int strobeStableSourceValue(int value) {
    int slot = (value >> 12) & 127;
    int bank = slot / 16;
    int tag = bank == 0 ? 8 : bank == 1 ? 9 : bank == 2 ? 11 : bank == 3 ? 12 : bank == 4 ? 14 : bank == 5 ? 15 : bank == 6 ? 6 : 7;
    return (tag << 20) | (((value >> 19) & 15) << 16) | ((slot & 15) << 12) | (value & 4095);
}
float strobeStablePosition(vec3 colour) { return float(strobeStableInteger(colour)) / 4096.0 - 512.0; }
vec3 strobeStablePacketColour(int index, int value, int projection, vec3 position) {
    if (index == 0) return strobeStableBytes(strobeStablePacketValue(value));
    if (index >= 1 && index <= 3) return strobeStableBytes(int(floor((clamp(position[index - 1], -512.0, 512.0) + 512.0) * 4096.0 + 0.5)));
    if (index == 4) return vec3(float((projection >> 8) & 255), float(projection & 255), 197.0) / 255.0;
    if (index == 5) return strobeStableBytes((strobeStablePacketValue(value) ^ (projection << 7) ^ 0x5AC3A5) & 0x7FFFFF);
    return index == 6 ? vec3(71.0, 163.0, 247.0) / 255.0 : vec3(109.0, 193.0, 37.0) / 255.0;
}
vec3 strobeStableColor(int value) {
    return vec3(float((value >> 8) & 15), float((value >> 4) & 15), float(value & 15)) / 15.0;
}
float strobeStableRadius(int value) { return 0.25 * pow(32.0, float((value >> 16) & 15) / 15.0); }
#endif
