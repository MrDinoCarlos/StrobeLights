#version 330

#moj_import <minecraft:utils.glsl>

uniform sampler2D DiffuseDepthSampler;
uniform sampler2D LightsSampler;
uniform sampler2D CompareDepthSampler;
const float Range = 128.0;

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

vec4 encodeAlphaHDR(vec4 color) {
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
    return (encodedValue >> 23) == 0;
}

vec3 offscreenLightColor(int encodedValue) {
    return vec3(
        float((encodedValue >> 8) & 15),
        float((encodedValue >> 4) & 15),
        float(encodedValue & 15)
    ) / 15.0;
}

vec3 reconstructOffscreenLight(vec3 proxyCoord, int encodedValue) {
    proxyCoord.z = max(0.0, proxyCoord.z - 0.25);
    int mode = (encodedValue >> 20) & 7;
    float axisInverse = 16.0;
    float depthInverse = 4.0;
    if (mode == 0) {
        return proxyCoord;
    }
    if (mode == 1) {
        return vec3(
            -proxyCoord.x * axisInverse,
            -proxyCoord.y * axisInverse,
            -proxyCoord.z * depthInverse
        );
    }
    if (mode == 2) {
        return vec3(
            proxyCoord.z * depthInverse,
            proxyCoord.y * axisInverse,
            -proxyCoord.x * axisInverse
        );
    }
    if (mode == 3) {
        return vec3(
            -proxyCoord.z * depthInverse,
            proxyCoord.y * axisInverse,
            proxyCoord.x * axisInverse
        );
    }
    if (mode == 4) {
        return vec3(
            proxyCoord.x * axisInverse,
            proxyCoord.z * depthInverse,
            -proxyCoord.y * axisInverse
        );
    }
    if (mode == 5) {
        return vec3(
            proxyCoord.x * axisInverse,
            -proxyCoord.z * depthInverse,
            proxyCoord.y * axisInverse
        );
    }
    return vec3(
        proxyCoord.x * axisInverse,
        proxyCoord.y * axisInverse,
        proxyCoord.z * depthInverse
    );
}

void main() {
    outColor = vec4(0.0);
    float oDepth = texture(DiffuseDepthSampler, texCoord).r;
    float compDepth = texture(CompareDepthSampler, texCoord).r;
    float depth = LinearizeDepth(oDepth);
    if (oDepth < compDepth && depth < Range + 64.0) {
        vec4 aggColor = vec4(0.0, 0.0, 0.0, 1.0);

        vec2 pixCoord = texCoord;
        vec2 screenCoord = (pixCoord - vec2(0.5)) * vec2(aspectRatio, 1.0);

        for (int i = 0; i < int(count); i += 1) {
            vec4 xvec = texture(LightsSampler, (vec2(float(i), 0.0) + 0.5) * oneTexelAux1);
            vec4 yvec = texture(LightsSampler, (vec2(float(i), 1.0) + 0.5) * oneTexelAux1);
            vec4 zvec = texture(LightsSampler, (vec2(float(i), 2.0) + 0.5) * oneTexelAux1);
            vec3 lightWorldCoord = vec3(decodeInt(xvec), decodeInt(yvec), decodeInt(zvec)) / FIXEDPOINT;
            vec3 lightColor = texture(LightsSampler, (vec2(float(i), 3.0) + 0.5) * oneTexelAux1).rgb;
            int expansionCode = int(floor(texture(
                LightsSampler,
                (vec2(float(i), 4.0) + 0.5) * oneTexelAux1
            ).r * 15.0 + 0.5));
            float sourceMetadata = texture(
                LightsSampler,
                (vec2(float(i), 5.0) + 0.5) * oneTexelAux1
            ).r;
            bool trpLight = sourceMetadata > 0.0 && sourceMetadata < 0.5;
            int encodedValue = markerValue(lightColor);
            if (isCameraFlash(encodedValue)) {
                continue;
            }
            bool offscreenLight = isOffscreenLight(encodedValue);
            ivec2 projectionBytes = ivec2(floor(texture(LightsSampler,
                (vec2(float(i), 6.0) + 0.5) * oneTexelAux1).rg * 255.0 + 0.5));
            float markerConversionK = decodeExactProjectionK(
                (projectionBytes.r << 8) | projectionBytes.g);
            if (offscreenLight) {
                lightWorldCoord = reconstructOffscreenLight(lightWorldCoord, encodedValue);
                lightColor = offscreenLightColor(encodedValue);
            }
            vec3 worldCoord = vec3(
                screenCoord * markerConversionK * depth,
                depth
            );
            float encodedIntensity = clamp(
                max(max(lightColor.r, lightColor.g), lightColor.b),
                0.0,
                1.0
            );
            float lightRadius;
            float falloffPower;
            float lightBoost;
            vec3 emittedColor;
            if (trpLight) {
                int radiusLowCode = int(clamp(
                    floor(sourceMetadata * 255.0 + 0.5) - 1.0,
                    0.0,
                    15.0
                ));
                int radiusBand = expansionCode * 16 + radiusLowCode;
                lightRadius = 0.01 * pow(6400.0, float(radiusBand) / 255.0);
                float intensityBand = floor(encodedIntensity * 15.0 + 0.5);
                float trpIntensity = intensityBand <= 0.0 ? 0.0
                    : exp2((intensityBand - 15.0) * (16.0 / 14.0));
                emittedColor = encodedIntensity > 0.0001
                    ? lightColor / encodedIntensity * trpIntensity : vec3(0.0);
                falloffPower = 2.0;
                lightBoost = 1.0;
            } else {
                lightRadius = mix(
                    MIN_LIGHTR,
                    LIGHTR,
                    pow(encodedIntensity, RADIUS_CURVE)
                ) * decodeExpansionScale(expansionCode);
                emittedColor = lightColor;
                falloffPower = FALLOFF_POWER;
                lightBoost = LIGHT_BOOST;
            }
            float lightDist = length(worldCoord - lightWorldCoord);
            if (lightDist < lightRadius) {
                float rangeFade = clamp(Range - length(lightWorldCoord), 0.0, 6.0) / 6.0;
                float radialFalloff = pow(
                    clamp(1.0 - lightDist / lightRadius, 0.0, 1.0),
                    falloffPower
                );
                aggColor.rgb += radialFalloff * emittedColor * lightBoost
                    * rangeFade;
            }
        }
        outColor.rgb = aggColor.rgb;
    }
}
