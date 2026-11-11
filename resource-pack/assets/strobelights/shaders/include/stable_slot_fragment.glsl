#moj_import <strobelights:stable_slot_common.glsl>
flat in float strobeStableMarker;
flat in vec3 strobeStableViewPosition;
flat in vec3 strobeStablePayload;
in vec2 strobeStableCoordinate;

void strobeStableSlotFragment(out vec4 outputColour, mat4 projection) {
    mat2 uvPerPixel = mat2(dFdx(strobeStableCoordinate), dFdy(strobeStableCoordinate));
    if (abs(determinant(uvPerPixel)) < 0.000000000001) discard;
    vec2 pixelOffset = inverse(uvPerPixel) * (strobeStableCoordinate - vec2(0.5));
    ivec2 cell = ivec2(floor(pixelOffset + vec2(0.501)));
    if (abs(cell.x) > 1 || abs(cell.y) > 1) discard;
    int cellIndex = (cell.y + 1) * 3 + cell.x + 1;
    ivec3 bytes = ivec3(floor(strobeStablePayload * 255.0 + 0.5));
    int value = (bytes.r << 16) | (bytes.g << 8) | bytes.b;
    // Opaque data is independent of previously rendered item geometry. The
    // Fabulous compositor validates and removes these tiny edge packets.
    gl_FragDepth = 0.0;
    if (cellIndex == 4) {
        outputColour = vec4(vec3(29.0, 177.0, 239.0) / 255.0, 1.0);
    } else {
        int index = cellIndex < 4 ? cellIndex : cellIndex - 1;
        int projectionCode = strobeStableProjectionK(2.0 / max(abs(projection[1][1]), 0.0001));
        outputColour = vec4(strobeStablePacketColour(index, value, projectionCode, strobeStableViewPosition), 1.0);
    }
}
