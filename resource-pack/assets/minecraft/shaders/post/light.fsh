#version 150

#moj_import <minecraft:utils.glsl>

uniform sampler2D DiffuseDepthSampler;
uniform sampler2D LightsSampler;
uniform float Range;

in vec2 texCoord;
flat in vec2 oneTexel;
flat in vec2 oneTexelAux1;
flat in float aspectRatio;
flat in float conversionK;
flat in float count;

out vec4 outColor;

int decodeInt(vec4 ivec) {
    ivec.rgb *= 255.0;
    int num = 0;
    num += int(ivec.r);
    num += int(ivec.g) * 255;
    num += int(ivec.b) * 255 * 255;
    return num * int(floor(4.0 * (ivec.a - 0.75) + 0.5));
}

vec4 decodeAlphaHDR(vec4 color) {
    return vec4(color.rgb * (1.0 + clamp(color.a - 26.0 / 255.0, 0.0, 1.0) * 3.0 * 255.0 / 224.0), 1.0);
}

vec4 encodeAlphaHDR(vec3 color) {
    float me = clamp(max(max(color.r, color.g), color.b), 1.0, 4.0);
    return vec4(color.rgb / me, 26.0 / 255.0 + (me - 1.0) * 224.0 / 255.0 / 3.0);
}

int markerValue(vec3 color) {
    ivec3 bytes = ivec3(floor(color * 255.0 + 0.5));
    return (bytes.r << 16) | (bytes.g << 8) | bytes.b;
}

bool isCameraFlash(int encodedValue) {
    return (encodedValue >> 20) == 13 && (encodedValue & 1) == 1;
}

bool isOffscreenLight(int encodedValue) {
    return (encodedValue >> 21) == 6 && (encodedValue & 3) == 2;
}

vec3 offscreenLightColor(int encodedValue) {
    vec3 color = vec3(
        float((encodedValue >> 10) & 15),
        float((encodedValue >> 6) & 15),
        float((encodedValue >> 2) & 15)
    ) / 15.0;
    float intensity = float((encodedValue >> 14) & 15) / 15.0;
    return color * intensity;
}

vec3 reconstructOffscreenLight(vec3 proxyCoord, int encodedValue) {
    int mode = (encodedValue >> 18) & 7;
    float inverseScale = 4.0;
    if (mode == 0) {
        return proxyCoord;
    }
    if (mode == 1) {
        return vec3(
            -proxyCoord.x * inverseScale,
            -proxyCoord.y * inverseScale,
            -proxyCoord.z
        );
    }
    if (mode == 2) {
        return vec3(
            proxyCoord.z,
            proxyCoord.y * inverseScale,
            -proxyCoord.x * inverseScale
        );
    }
    if (mode == 3) {
        return vec3(
            -proxyCoord.z,
            proxyCoord.y * inverseScale,
            proxyCoord.x * inverseScale
        );
    }
    if (mode == 4) {
        return vec3(
            proxyCoord.x * inverseScale,
            proxyCoord.z,
            -proxyCoord.y * inverseScale
        );
    }
    if (mode == 5) {
        return vec3(
            proxyCoord.x * inverseScale,
            -proxyCoord.z,
            proxyCoord.y * inverseScale
        );
    }
    return vec3(
        proxyCoord.x * inverseScale,
        proxyCoord.y * inverseScale,
        proxyCoord.z
    );
}

void main() {
    outColor = vec4(0.0);
    float depth = LinearizeDepth(texture(DiffuseDepthSampler, texCoord).r);
    if (depth < Range + LIGHTR) {
        vec4 aggColor = vec4(0.0, 0.0, 0.0, 1.0);

        vec2 pixCoord = texCoord;
        vec2 screenCoord = (pixCoord - vec2(0.5)) * vec2(aspectRatio, 1.0);
        float conversion = conversionK * depth;
        vec3 worldCoord = vec3(screenCoord * conversion, depth);

        for (int i = 0; i < int(count); i += 1) {
            vec4 xvec = texture(LightsSampler, (vec2(float(i), 0.0) + 0.5) * oneTexelAux1);
            vec4 yvec = texture(LightsSampler, (vec2(float(i), 1.0) + 0.5) * oneTexelAux1);
            vec4 zvec = texture(LightsSampler, (vec2(float(i), 2.0) + 0.5) * oneTexelAux1);
            vec3 lightWorldCoord = vec3(decodeInt(xvec), decodeInt(yvec), decodeInt(zvec)) / FIXEDPOINT;
            vec3 lightColor = texture(LightsSampler, (vec2(float(i), 3.0) + 0.5) * oneTexelAux1).rgb;
            int encodedValue = markerValue(lightColor);
            if (isCameraFlash(encodedValue)) {
                continue;
            }
            bool offscreenLight = isOffscreenLight(encodedValue);
            if (offscreenLight) {
                lightWorldCoord = reconstructOffscreenLight(lightWorldCoord, encodedValue);
                lightColor = offscreenLightColor(encodedValue);
            }
            float encodedIntensity = clamp(
                max(max(lightColor.r, lightColor.g), lightColor.b),
                0.0,
                1.0
            );
            float lightRadius = mix(
                MIN_LIGHTR,
                LIGHTR,
                pow(encodedIntensity, RADIUS_CURVE)
            );
            float lightDist = length(worldCoord - lightWorldCoord);
            if (lightDist < lightRadius) {
                float rangeFade = clamp(Range - length(lightWorldCoord), 0.0, 6.0) / 6.0;
                float radialFalloff = pow(
                    clamp(1.0 - lightDist / lightRadius, 0.0, 1.0),
                    FALLOFF_POWER
                );
                aggColor.rgb += radialFalloff * lightColor * LIGHT_BOOST
                    * rangeFade;
            }
        }
        outColor = encodeAlphaHDR(aggColor.rgb);
    }
}
