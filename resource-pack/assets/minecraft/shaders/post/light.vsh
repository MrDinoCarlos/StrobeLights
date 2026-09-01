#version 330

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 DiffuseDepthSize;
    vec2 LightsSize;
};

uniform sampler2D LightsSampler;

out vec2 texCoord;
flat out vec2 oneTexel;
flat out vec2 oneTexelAux1;
flat out float aspectRatio;
flat out float conversionK;
flat out float count;

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
    oneTexel = 1.0 / DiffuseDepthSize;
    oneTexelAux1 = 1.0 / LightsSize;
    aspectRatio = DiffuseDepthSize.x / DiffuseDepthSize.y;
    texCoord = uv;
    conversionK = tan(70.0 / 360.0 * 3.14159265358979) * 2.0;

    vec4 tmpCount = texture(LightsSampler, vec2(1.0, 0.0) - oneTexelAux1 * 0.5);
    count = 0.0;
    if (tmpCount.a == 69.0 / 255.0) {
        tmpCount.a = 1.0;
        count = float(decodeInt(tmpCount));
    }
}
