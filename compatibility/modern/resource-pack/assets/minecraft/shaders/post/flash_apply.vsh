#version 330

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 DiffuseSize;
    vec2 LightsSize;
};

uniform sampler2D LightsSampler;

out vec2 texCoord;
flat out vec2 oneTexelLights;
flat out float lightCount;

int decodeInt(vec4 ivec) {
    ivec.rgb *= 255.0;
    int num = 0;
    num += int(ivec.r);
    num += int(ivec.g) * 255;
    num += int(ivec.b) * 255 * 255;
    return num * int(floor(4.0 * (ivec.a - 0.75) + 0.5));
}

void main() {
    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(uv * 2.0 + vec2(-1.0), 0.0, 1.0);
    texCoord = uv;
    oneTexelLights = 1.0 / LightsSize;
    vec4 tmpCount = texture(LightsSampler, vec2(1.0, 0.0) - oneTexelLights * 0.5);
    lightCount = 0.0;
    if (tmpCount.a == 69.0 / 255.0) {
        tmpCount.a = 1.0;
        lightCount = float(decodeInt(tmpCount));
    }
}
