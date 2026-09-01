#version 330

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 DiffuseSize;
    vec2 ItemEntityDepthSize;
    vec2 ColoredCentersSize;
};

out vec2 texCoord;
flat out vec2 inOneTexel;
flat out float inAspectRatio;
flat out float conversionK;

void main() {
    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(uv * 2.0 + vec2(-1.0), 0.0, 1.0);
    inAspectRatio = DiffuseSize.x / DiffuseSize.y;
    inOneTexel = 1.0 / DiffuseSize;
    texCoord = uv;
    conversionK = tan(70.0 / 360.0 * 3.14159265358979) * 2.0;
}
