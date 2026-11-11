#moj_import <strobelights:stable_slot_common.glsl>
flat out float strobeStableMarker;
flat out vec3 strobeStableViewPosition;
flat out vec3 strobeStablePayload;
out vec2 strobeStableCoordinate;

bool strobeStableSlotVertex(vec3 position, vec4 colour, vec2 uv, sampler2D carrierTexture, mat4 projection, mat4 modelView) {
    strobeStableMarker = 0.0;
    int value = (int(floor(colour.r * 255.0 + 0.5)) << 16)
        | (int(floor(colour.g * 255.0 + 0.5)) << 8) | int(floor(colour.b * 255.0 + 0.5));
    ivec4 texel = ivec4(floor(texture(carrierTexture, uv) * 255.0 + 0.5));
    if (abs(projection[2][3]) < 0.000001 || !strobeStableValue(value)
        || !all(equal(texel, ivec4(37, 211, 83, 5)))) return false;
    vec4 source = modelView * vec4(position, 1.0);
    strobeStableViewPosition = vec3(source.x, source.y, -source.z);
    strobeStablePayload = colour.rgb;
    strobeStableMarker = 1.0;
    int slot = strobeStableSlot(value);
    int corner = gl_VertexID % 4;
    vec2 coordinate = corner == 0 ? vec2(0.0, 0.0) : corner == 1 ? vec2(0.0, 1.0)
        : corner == 2 ? vec2(1.0, 1.0) : vec2(1.0, 0.0);
    strobeStableCoordinate = coordinate;
    // Reserve small packets at the upper edge, away from both v5 side lanes.
    // XYZ travels in the packet rather than being inferred from these pixels.
    vec2 center = vec2(-0.93 + (float(slot % 32) + 0.5) * (1.86 / 32.0), 0.965 - float(slot / 32) * 0.05);
    vec2 cornerOffset = vec2(coordinate.x * 2.0 - 1.0, 1.0 - coordinate.y * 2.0);
    gl_Position = vec4(center + cornerOffset * vec2(0.03, 0.04), 0.0, 1.0);
    return true;
}
