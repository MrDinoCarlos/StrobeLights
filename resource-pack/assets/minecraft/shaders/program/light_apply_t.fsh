#version 150
 
uniform sampler2D DiffuseSampler;
uniform sampler2D LightMapSampler;
uniform float Intensity;
 
in vec2 texCoord;

out vec4 outColor;

vec3 decodeAlphaHDR(vec4 color) {
    return color.rgb * (1.0 + clamp(color.a - 26.0 / 255.0, 0.0, 1.0) * 3.0 * 255.0 / 224.0);
}
 
 void main(){
    outColor = texture(DiffuseSampler, texCoord);
    vec3 lightColor = texture(LightMapSampler, texCoord).rgb;
    if (outColor.a > 0.0) {
        float rawLightStrength = max(max(lightColor.r, lightColor.g), lightColor.b);
        float lightStrength = clamp(rawLightStrength * Intensity, 0.0, 1.0);
        if (lightStrength > 0.001) {
            vec3 tint = lightColor / max(rawLightStrength, 0.001);
            vec3 baseColor = outColor.rgb;
            float baseLuminance = dot(baseColor, vec3(0.2126, 0.7152, 0.0722));
            vec3 colorized = baseColor * (vec3(0.28) + tint * 0.72)
                + tint * baseLuminance * 0.12;
            outColor.rgb = mix(baseColor, colorized, lightStrength * 0.88);
        }
    }
 }
