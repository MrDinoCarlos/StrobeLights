#version 330

uniform sampler2D DiffuseSampler;

in vec2 texCoord;
flat in vec2 oneTexel;

out vec4 outColor;

bool sameEncodedMarker(vec3 reference, vec3 candidate) {
    return all(lessThanEqual(
        abs(reference - candidate),
        vec3(1.5 / 255.0)
    ));
}

void main() {
    outColor = texture(DiffuseSampler, texCoord);
    vec3 c1 = texture(DiffuseSampler, texCoord + vec2(oneTexel.x, 0.0)).rgb;
    vec3 c2 = texture(DiffuseSampler, texCoord + vec2(0.0, oneTexel.y)).rgb;
    vec3 c3 = texture(DiffuseSampler, texCoord + vec2(oneTexel.x, -oneTexel.y)).rgb;
    vec3 c4 = texture(DiffuseSampler, texCoord + vec2(oneTexel.x, oneTexel.y)).rgb;
    // Collapse extra framebuffer texels only when they belong to the exact
    // same payload. Neighboring strobes with different colors stay separate.
    if (sameEncodedMarker(outColor.rgb, c1)
        || sameEncodedMarker(outColor.rgb, c2)
        || sameEncodedMarker(outColor.rgb, c3)
        || sameEncodedMarker(outColor.rgb, c4)) {
        outColor = vec4(0.0);
    }
}
