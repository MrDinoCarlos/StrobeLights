#version 330

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 DiffuseSize;
    vec2 DiffuseDepthSize;
    vec2 TranslucentSize;
    vec2 TranslucentDepthSize;
    vec2 ItemEntitySize;
    vec2 ItemEntityDepthSize;
    vec2 ParticlesSize;
    vec2 ParticlesDepthSize;
    vec2 CloudsSize;
    vec2 CloudsDepthSize;
    vec2 WeatherSize;
    vec2 WeatherDepthSize;
};

out vec2 texCoord;
out vec2 oneTexel;

void main() {
    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(uv * 2.0 + vec2(-1.0), 0.0, 1.0);
    texCoord = uv;
    oneTexel = 1.0 / OutSize;
}
