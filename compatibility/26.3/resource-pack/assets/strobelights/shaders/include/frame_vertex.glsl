#include <strobelights:frame_codec.glsl>

layout(location = 8) flat out int strobeFrameMarker;
layout(location = 9) flat out uint strobeFrameValue;
layout(location = 10) flat out vec3 strobeFramePosition;
layout(location = 11) flat out int strobeFrameSlot;
layout(location = 12) flat out vec4 strobeFrameProjection;
layout(location = 13) flat out vec2 strobeFrameProjectionOffset;

bool strobeFrameVertex(vec3 position, vec4 tint, vec2 uv, ivec2 brightness, sampler2D carrier) {
    strobeFrameMarker = 0;
    ivec4 signature = ivec4(floor(texture(carrier, uv) * 255.0 + 0.5));
    if (!all(equal(signature, ivec4(37, 211, 83, 255)))) return false;
    uvec3 bytes = uvec3(floor(tint.rgb * 255.0 + 0.5));
    uint value = (bytes.r << 16u) | (bytes.g << 8u) | bytes.b;
    int slot = (brightness.x / 16) + 16 * (brightness.y / 16);
    bool integration = strobeIntegration(value);
    if (integration) slot = strobeIntegrationSlot(value);
    strobeFrameMarker = -1;
    gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
    if ((!strobeSource(value) && !strobeFlash(value) && !integration) || slot >= STROBE_FRAME_SLOTS
        || (!integration && slot >= STROBE_NATIVE_SLOTS)
        || ScreenSize.x < 320.0 || ScreenSize.y < float(strobePacketOrigin(slot).y + 8)
        || abs(ProjMat[2][3]) < 0.00001) return true;

    strobeFrameMarker = 1;
    strobeFrameValue = value;
    strobeFrameSlot = slot;
    // Minecraft applies the item's pose to Position on the CPU. The point
    // carrier has four identical positions, keeping every packet word stable.
    strobeFramePosition = (ModelViewMat * vec4(position, 1.0)).xyz;
    strobeFrameProjection = vec4(ProjMat[0][0], ProjMat[1][1], ProjMat[2][2], ProjMat[3][2]);
    strobeFrameProjectionOffset = vec2(ProjMat[2][0], ProjMat[2][1]);
    int corner = gl_VertexIndex % 4;
    vec2 uvCorner = corner == 0 ? vec2(0, 0) : corner == 1 ? vec2(0, 1)
        : corner == 2 ? vec2(1, 1) : vec2(1, 0);
    vec2 pixel = vec2(strobePacketOrigin(slot)) + vec2(uvCorner.x, 1.0 - uvCorner.y) * vec2(5.0, 7.0);
    // RenderPearl clears depth to zero and uses GREATER: near is one for
    // BOTH clip-depth conventions. Zero would put packets behind the terrain.
    float nearDepth = 0.999999;
    gl_Position = vec4(pixel / ScreenSize * 2.0 - 1.0, nearDepth, 1.0);
    return true;
}
