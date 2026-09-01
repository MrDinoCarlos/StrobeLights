#version 150

in vec4 Position;

uniform sampler2D LightsSampler;
uniform mat4 ProjMat;
uniform vec2 AuxSize0;

out vec2 texCoord;
flat out vec2 oneTexelLights;
flat out float lightCount;

int decodeInt(vec4 encoded) {
    encoded.rgb *= 255.0;
    int number = int(encoded.r) + int(encoded.g) * 255 + int(encoded.b) * 255 * 255;
    return number * int(floor(4.0 * (encoded.a - 0.75) + 0.5));
}

void main() {
    vec4 outPos = ProjMat * vec4(Position.xy, 0.0, 1.0);
    texCoord = outPos.xy * 0.5 + 0.5;
    oneTexelLights = 1.0 / AuxSize0;

    vec4 encodedCount = texture(
        LightsSampler,
        vec2(1.0, 0.0) - oneTexelLights * 0.5
    );
    lightCount = 0.0;
    if (encodedCount.a == 69.0 / 255.0) {
        encodedCount.a = 1.0;
        lightCount = float(decodeInt(encodedCount));
    }

    gl_Position = vec4(outPos.xy, 0.2, 1.0);
}
