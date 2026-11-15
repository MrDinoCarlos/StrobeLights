#include <strobelights:frame_codec.glsl>

layout(location = 8) flat in int strobeFrameMarker;
layout(location = 9) flat in uint strobeFrameValue;
layout(location = 10) flat in vec3 strobeFramePosition;
layout(location = 11) flat in int strobeFrameSlot;
layout(location = 12) flat in vec4 strobeFrameProjection;
layout(location = 13) flat in vec2 strobeFrameProjectionOffset;

vec3 strobeFramePixel() {
    ivec2 pixel = ivec2(gl_FragCoord.xy) - ivec2(strobeFrameSlot * 5, 0);
    int index = pixel.y * 5 + pixel.x;
    if (index == 0) return strobeWord(0x7B3u);
    if (index == 1) return strobeWord(strobeFrameValue & 4095u);
    if (index == 2) return strobeWord(strobeFrameValue >> 12u);
    if (index == 34) {
        #ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
        return strobeWord(uint(strobeFrameSlot) | 256u);
        #else
        return strobeWord(uint(strobeFrameSlot));
        #endif
    }
    if (index >= 3 && index <= 29) {
        int component = (index - 3) / 3;
        float datum = component < 3 ? strobeFramePosition[component]
            : component < 7 ? strobeFrameProjection[component - 3]
            : strobeFrameProjectionOffset[component - 7];
        uint bits = floatBitsToUint(datum);
        return strobeWord((bits >> uint(((index - 3) % 3) * 12)) & 4095u);
    }
    return strobeWord(0x51Du);
}
