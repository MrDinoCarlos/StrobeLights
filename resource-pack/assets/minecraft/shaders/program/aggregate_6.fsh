#version 150

#define BIG 1000000
#define FIXEDPOINT 1000.0
#define NEAR 0.05
#define FAR 1024.0
#define LIGHTDEPTH 0.025

// Post programs in 1.20.1 cannot import include files. These helpers mirror
// the core-shader include without relying on unsupported preprocessing.
float LinearizeDepth(float depth) {
    float z = depth * 2.0 - 1.0;
    return 2.0 * (NEAR * FAR) / (FAR + NEAR - z * (FAR - NEAR));
}

float decodeProjectionK(int code) {
    if (code <= 0) return 0.125;
    if (code == 1) return 0.160;
    if (code == 2) return 0.200;
    if (code == 3) return 0.250;
    if (code == 4) return 0.315;
    if (code == 5) return 0.400;
    if (code == 6) return 0.500;
    if (code == 7) return 0.630;
    if (code == 8) return 0.800;
    if (code == 9) return 1.000;
    if (code == 10) return 1.200;
    if (code == 11) return 1.400;
    if (code == 12) return 1.600;
    if (code == 13) return 2.000;
    if (code == 14) return 2.400;
    return 3.000;
}

uniform sampler2D DiffuseSampler;
uniform sampler2D ItemEntitySampler;
uniform sampler2D ItemEntityDepthSampler;
uniform sampler2D ColoredCentersSampler;
uniform vec2 InSize;
uniform float Step;
uniform float Test;

flat in vec2 inOneTexel;
flat in float inAspectRatio;
flat in float conversionK;

out vec4 outColor;

int intmod(int i, int base) {
    return i - (i / base * base);
}

vec4 encodeInt(int i) {
    float a = 1.0;
    if (i < 0) {
        i *= -1;
        a = 0.5;
    }
    int r = intmod(i, 255);
    i = i / 255;
    int g = intmod(i, 255);
    i = i / 255;
    int b = intmod(i, 255);
    return vec4(float(r) / 255.0, float(g) / 255.0, float(b) / 255.0, a);
}

int markerValue(vec3 color) {
    ivec3 bytes = ivec3(floor(color * 255.0 + 0.5));
    return (bytes.r << 16) | (bytes.g << 8) | bytes.b;
}

bool isOffscreenLight(int encodedValue) {
    return (encodedValue >> 23) == 0;
}

void main() {
    float width = ceil(InSize.x / Step);
    float width2 = ceil(width / Step);
    float height = ceil(InSize.y / (Step));
    float height2 = ceil(height / (Step));
    vec2 pos = gl_FragCoord.xy - 0.5;
    float targetNum = pos.x + 1.0;

    vec2 samplepos = vec2(2.0 * width + 2.0 * width2, 0.0);
    float tmpCounter = 0.0;
    float status = 0.0;
    int px = 0;
    int py = 0;
    for (int iter = 0; iter < int(width2); iter += 1) {
        float l0count = texture(DiffuseSampler, (vec2(samplepos.x + float(iter), 0.0) + 0.5) / InSize).r * 255.0;
        if (tmpCounter + l0count >= targetNum) {
            status = 1.0;
            px = iter;
            iter = BIG;
        } else {
            tmpCounter += l0count;
        }
    }

    outColor = vec4(encodeInt(int(tmpCounter)).rgb, 69.0 / 255.0);

    if (status == 1.0) {
        samplepos = vec2(2.0 * width + width2 + float(px), 0.0);
        for (int iter = 0; iter < int(height2); iter += 1) {
            float l1count = texture(DiffuseSampler, (vec2(samplepos.x, float(iter)) + 0.5) / InSize).r * 255.0;
            if (tmpCounter + l1count >= targetNum) {
                status = 2.0;
                py = iter;
                iter = BIG;
            } else {
                tmpCounter += l1count;
            }
        }
    }

    if (status == 2.0) {
        py *= int(Step);
        samplepos = vec2(2.0 * width + float(px), float(py));
        for (int iter = 0; iter < int(Step); iter += 1) {
            float l2count = texture(DiffuseSampler, (vec2(samplepos.x, samplepos.y + float(iter)) + 0.5) / InSize).r * 255.0;
            if (tmpCounter + l2count >= targetNum) {
                status = 3.0;
                py += iter;
                iter = BIG;
            } else {
                tmpCounter += l2count;
            }
        }
    }

    if (status == 3.0) {
        px *= int(Step);
        samplepos = vec2(width + float(px), float(py));
        for (int iter = 0; iter < int(Step); iter += 1) {
            float l3count = texture(DiffuseSampler, (vec2(samplepos.x + float(iter), samplepos.y) + 0.5) / InSize).r * 255.0;
            if (px + iter < int(width) && tmpCounter + l3count >= targetNum) {
                status = 4.0;
                px += iter;
                iter = BIG;
            } else {
                tmpCounter += l3count;
            }
        }
    }

    if (status == 4.0) {
        py *= int(Step);
        samplepos = vec2(float(px), float(py));
        for (int iter = 0; iter < int(Step); iter += 1) {
            float l4count = texture(DiffuseSampler, (vec2(samplepos.x, samplepos.y + float(iter)) + 0.5) / InSize).r * 255.0;
            if (tmpCounter + l4count >= targetNum) {
                status = 5.0;
                py += iter;
                iter = BIG;
            } else {
                tmpCounter += l4count;
            }
        }
    }

    if (status == 5.0) {
        vec4 sampleColor = vec4(0.0);
        px *= int(Step);
        samplepos = vec2(float(px), float(py));
        for (int iter = 0; iter < int(Step); iter += 1) {
            sampleColor = texture(ColoredCentersSampler, (vec2(samplepos.x + float(iter), samplepos.y) + 0.5) / InSize);
            float isLight = step(0.5 / 255.0, sampleColor.a);
            if (tmpCounter + isLight == targetNum) {
                px += iter;
                iter = BIG;
            } else {
                tmpCounter += isLight;
            }
        }

        samplepos = vec2(px, py);
        samplepos = (samplepos + 0.5) * inOneTexel;
        float lightDepth = LinearizeDepth(
            texture(ItemEntityDepthSampler, samplepos).r / LIGHTDEPTH
        );
        int projectionHigh = int(floor(texture(ItemEntitySampler,
            samplepos - vec2(2.0 * inOneTexel.x, 0.0)).r * 255.0 + 0.5));
        int projectionLow = int(floor(texture(ItemEntitySampler,
            samplepos + vec2(2.0 * inOneTexel.x, 0.0)).r * 255.0 + 0.5));
        int projectionCode16 = (projectionHigh << 8) | projectionLow;
        vec2 sourceNdc = samplepos * 2.0 - 1.0;
        samplepos = (samplepos - vec2(0.5)) * vec2(inAspectRatio, 1.0);
        int encodedValue = markerValue(sampleColor.rgb);
        float markerConversionK = conversionK;
        int expansionCode = 3;
        if (isOffscreenLight(encodedValue)) {
            int projectionCode = (encodedValue >> 16) & 15;
            expansionCode = (encodedValue >> 12) & 15;
            markerConversionK = decodeProjectionK(projectionCode);
        }
        vec3 lightWorldCoord = vec3(
            samplepos * markerConversionK * lightDepth,
            lightDepth
        );
        if (isOffscreenLight(encodedValue)) {
            bool trpLight = sampleColor.a > 0.0 && sampleColor.a < 0.5;
            float lane = trpLight ? 0.45 : -0.45;
            lightWorldCoord = vec3((sourceNdc.x - lane) / 0.75,
                sourceNdc.y / 1.5, 1.0) * lightDepth;
        }

        if (pos.y == 0.0) {
            outColor = encodeInt(int(lightWorldCoord.x * FIXEDPOINT));
        } else if (pos.y == 1.0) {
            outColor = encodeInt(int(lightWorldCoord.y * FIXEDPOINT));
        } else if (pos.y == 2.0) {
            outColor = encodeInt(int(lightWorldCoord.z * FIXEDPOINT));
        } else if (pos.y == 3.0) {
            outColor = sampleColor;
        } else if (pos.y == 4.0) {
            outColor = vec4(float(expansionCode) / 15.0, 0.0, 0.0, 1.0);
        } else if (pos.y == 5.0) {
            outColor = vec4(sampleColor.a, 0.0, 0.0, 1.0);
        } else {
            outColor = vec4(float((projectionCode16 >> 8) & 255),
                float(projectionCode16 & 255), 0.0, 255.0) / 255.0;
        }

        if (Test > 0.5 && outColor.a == 0.0) {
            outColor += vec4(0.0, 0.2, 0.0, 1.0);
        }
    }
}
