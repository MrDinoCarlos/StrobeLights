#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D LightsSampler;

in vec2 texCoord;
flat in vec2 oneTexelLights;
flat in float lightCount;

out vec4 outColor;

int packedColor(vec3 color) {
    ivec3 bytes = ivec3(floor(color * 255.0 + 0.5));
    return (bytes.r << 16) | (bytes.g << 8) | bytes.b;
}

bool isCameraFlash(int encodedValue) {
    return (encodedValue >> 20) == 13 && (encodedValue & 1) == 1;
}

void main() {
    outColor = texture(DiffuseSampler, texCoord);
    float strongest = 0.0;
    vec3 flashColor = vec3(1.0);

    for (int i = 0; i < int(lightCount); i += 1) {
        vec3 encoded = texture(
            LightsSampler,
            (vec2(float(i), 3.0) + 0.5) * oneTexelLights
        ).rgb;
        int encodedValue = packedColor(encoded);
        if (isCameraFlash(encodedValue)) {
            float strength = float((encodedValue >> 13) & 127) / 127.0;
            if (strength > strongest) {
                strongest = strength;
                flashColor = vec3(
                    float((encodedValue >> 9) & 15),
                    float((encodedValue >> 5) & 15),
                    float((encodedValue >> 1) & 15)
                ) / 15.0;
            }
        }
    }

    outColor.rgb = mix(outColor.rgb, flashColor, clamp(strongest, 0.0, 1.0));

    outColor.a = 1.0;
}
