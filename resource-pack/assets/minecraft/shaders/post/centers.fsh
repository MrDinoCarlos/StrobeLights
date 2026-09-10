#version 330

uniform sampler2D DiffuseSampler;

in vec2 texCoord;
flat in vec2 oneTexel;

out vec4 outColor;

bool sameEncodedMarker(vec4 reference, vec4 candidate) {
    return all(lessThanEqual(
        abs(reference - candidate),
        vec4(1.5 / 255.0)
    ));
}

void main() {
    outColor = texture(DiffuseSampler, texCoord);
    vec4 c1 = texture(DiffuseSampler, texCoord + vec2(oneTexel.x, 0.0));
    vec4 c2 = texture(DiffuseSampler, texCoord + vec2(0.0, oneTexel.y));
    vec4 c3 = texture(DiffuseSampler, texCoord + vec2(oneTexel.x, -oneTexel.y));
    vec4 c4 = texture(DiffuseSampler, texCoord + vec2(oneTexel.x, oneTexel.y));
    // Collapse extra framebuffer texels only when they belong to the exact
    // same payload. Neighboring strobes with different colors stay separate.
    if (sameEncodedMarker(outColor, c1)
        || sameEncodedMarker(outColor, c2)
        || sameEncodedMarker(outColor, c3)
        || sameEncodedMarker(outColor, c4)) {
        outColor = vec4(0.0);
    }
}
