#version 330

#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>
#moj_import <minecraft:utils.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler0;
uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec2 texCoord2;
out vec4 glpos;
out float marker;

float opz(vec4 pos, float factor, float bias) {
    return (((pos.z / pos.w + 1.0) * 0.5 * factor + bias) * 2.0 - 1.0) * pos.w;
}

int markerValue(vec3 color) {
    ivec3 bytes = ivec3(floor(color * 255.0 + 0.5));
    return (bytes.r << 16) | (bytes.g << 8) | bytes.b;
}

bool isCameraFlash(int encodedValue) {
    return (encodedValue >> 20) == 13 && (encodedValue & 1) == 1;
}

bool isSourceLight(int encodedValue) {
    return (encodedValue >> 20) == 10 && (encodedValue & 15) == 5;
}

vec3 sourceLightColor(int encodedValue) {
    return vec3(
        float((encodedValue >> 12) & 15),
        float((encodedValue >> 8) & 15),
        float((encodedValue >> 4) & 15)
    ) / 15.0;
}

int offscreenMode(vec3 source) {
    if (length(source) < 0.20) {
        return 0;
    }
    vec3 absolute = abs(source);
    if (absolute.z >= absolute.x && absolute.z >= absolute.y) {
        return source.z >= 0.0 ? 6 : 1;
    }
    if (absolute.x >= absolute.y && absolute.x >= absolute.z) {
        return source.x >= 0.0 ? 2 : 3;
    }
    return source.y >= 0.0 ? 4 : 5;
}

vec3 offscreenProxy(vec3 source, int mode) {
    float axisScale = 0.0625;
    float depthScale = 0.25;
    if (mode == 0) {
        return vec3(0.0, 0.0, 0.25);
    }
    if (mode == 1) {
        return vec3(-source.x * axisScale, -source.y * axisScale, -source.z * depthScale);
    }
    if (mode == 2) {
        return vec3(-source.z * axisScale, source.y * axisScale, source.x * depthScale);
    }
    if (mode == 3) {
        return vec3(source.z * axisScale, source.y * axisScale, -source.x * depthScale);
    }
    if (mode == 4) {
        return vec3(source.x * axisScale, -source.z * axisScale, source.y * depthScale);
    }
    if (mode == 5) {
        return vec3(source.x * axisScale, source.z * axisScale, -source.y * depthScale);
    }
    return vec3(source.x * axisScale, source.y * axisScale, source.z * depthScale);
}

vec3 packOffscreenColor(vec3 color, int mode, int projectionCode, int expansionCode) {
    ivec3 color4 = ivec3(floor(clamp(color, 0.0, 1.0) * 15.0 + 0.5));
    int packedValue = ((mode & 7) << 20)
        | ((projectionCode & 15) << 16)
        | ((expansionCode & 15) << 12)
        | (color4.r << 8)
        | (color4.g << 4)
        | color4.b;
    return vec3(
        float((packedValue >> 16) & 255),
        float((packedValue >> 8) & 255),
        float(packedValue & 255)
    ) / 255.0;
}

#define HALFMARKER tmp.z / 64.0

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color)
        * sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;

    vec4 tmpcol = texture(Sampler0, UV0);
    vec4 tmp = ModelViewMat * vec4(Position, 1.0);
    bool gui = isGUI(ProjMat);
    bool markerAlpha = abs(tmpcol.a - LIGHTALPHA) <= LIGHTALPHATOLERANCE;
    float markerTextureFloor = tmpcol.a * 0.5;
    float markerTexturePeak = max(max(tmpcol.r, tmpcol.g), tmpcol.b);
    float markerTextureBase = min(min(tmpcol.r, tmpcol.g), tmpcol.b);
    bool markerTextureCarrier = markerTexturePeak >= markerTextureFloor
        && markerTextureBase >= markerTexturePeak * 0.75;
    marker = float(!gui && markerAlpha && markerTextureCarrier);

    if (marker > 0.0) {
        vertexColor = vec4(Color.rgb, 1.0);
        int encodedValue = markerValue(vertexColor.rgb);
        if (!isCameraFlash(encodedValue)) {
            int lightExpansionCode = 3;
            if (isSourceLight(encodedValue)) {
                lightExpansionCode = (encodedValue >> 16) & 15;
                vertexColor.rgb = sourceLightColor(encodedValue);
            }
            vec3 fixedSource = vec3(tmp.x, tmp.y, -tmp.z);
            int mode = offscreenMode(fixedSource);
            vec3 proxy = offscreenProxy(fixedSource, mode);
            float projectionK = 2.0 / max(abs(ProjMat[1][1]), 0.0001);
            int projectionCode = encodeProjectionK(projectionK);
            tmp.xyz = vec3(proxy.x, proxy.y, -proxy.z);
            vertexColor.rgb = packOffscreenColor(
                vertexColor.rgb,
                mode,
                projectionCode,
                lightExpansionCode
            );
        }

        if (gl_VertexID % 4 == 0) {
            tmp.xy += vec2(-HALFMARKER, HALFMARKER);
            texCoord2 = vec2(0.0, 0.0);
        } else if (gl_VertexID % 4 == 1) {
            tmp.xy += vec2(-HALFMARKER, -HALFMARKER);
            texCoord2 = vec2(0.0, 1.0);
        } else if (gl_VertexID % 4 == 2) {
            tmp.xy += vec2(HALFMARKER, -HALFMARKER);
            texCoord2 = vec2(1.0, 1.0);
        } else {
            tmp.xy += vec2(HALFMARKER, HALFMARKER);
            texCoord2 = vec2(1.0, 0.0);
        }
    }

    tmp = ProjMat * tmp;
    glpos = tmp;
    gl_Position = tmp;
}
