#version 150

#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:utils.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in vec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler0;
uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;
uniform float FogStart;
uniform float FogEnd;

uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

uniform vec4 ColorModulator;

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec2 texCoord1;
out vec2 texCoord2;
out vec4 normal;
out vec4 glpos;
out float marker;
out float scale;

#define HALFMARKER tmp.z / 240.0

float opz(vec4 pos, float factor, float bias) {
    return (((pos.z / pos.w + 1.0) * 0.5 * factor + bias) * 2.0 - 1.0) * pos.w;
}

int markerValue(vec3 color) {
    ivec3 bytes = ivec3(floor(color * 255.0 + 0.5));
    return (bytes.r << 16) | (bytes.g << 8) | bytes.b;
}

bool isEncodedTechnicalMarker(int encodedValue) {
    bool cameraFlash = (encodedValue >> 20) == 13 && (encodedValue & 1) == 1;
    bool legacyProxy = (encodedValue >> 21) == 6 && (encodedValue & 3) == 2;
    return cameraFlash || legacyProxy;
}

int offscreenMode(vec3 source) {
    if (length(source) < 0.20) {
        return 0;
    }
    vec3 absolute = abs(source);
    if (absolute.z >= absolute.x && absolute.z >= absolute.y && source.z < 0.0) {
        return 1;
    }
    if (absolute.x >= absolute.y) {
        return source.x >= 0.0 ? 2 : 3;
    }
    if (absolute.y > 0.0) {
        return source.y >= 0.0 ? 4 : 5;
    }
    return 1;
}

vec3 offscreenProxy(vec3 source, int mode) {
    float axisScale = 0.25;
    if (mode == 0) {
        return vec3(0.22, 0.0, 0.75);
    }
    if (mode == 1) {
        return vec3(-source.x * axisScale, -source.y * axisScale, -source.z);
    }
    if (mode == 2) {
        return vec3(-source.z * axisScale, source.y * axisScale, source.x);
    }
    if (mode == 3) {
        return vec3(source.z * axisScale, source.y * axisScale, -source.x);
    }
    if (mode == 4) {
        return vec3(source.x * axisScale, -source.z * axisScale, source.y);
    }
    return vec3(source.x * axisScale, source.z * axisScale, -source.y);
}

vec3 packOffscreenColor(vec3 color, int mode) {
    ivec3 color4 = ivec3(floor(clamp(color, 0.0, 1.0) * 15.0 + 0.5));
    int packedValue = (6 << 21)
        | ((mode & 7) << 18)
        | (15 << 14)
        | (color4.r << 10)
        | (color4.g << 6)
        | (color4.b << 2)
        | 2;
    return vec3(
        float((packedValue >> 16) & 255),
        float((packedValue >> 8) & 255),
        float(packedValue & 255)
    ) / 255.0;
}

void main() {
    vertexDistance = fog_distance(Position, FogShape);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color) * texelFetch(Sampler2, UV2 / 16, 0);
    texCoord0 = UV0;
    texCoord1 = UV1;
    normal = ProjMat * ModelViewMat * vec4(Normal, 0.0);

    vec4 tmpcol = texture(Sampler0, UV0);
    vec4 tmp = ModelViewMat * vec4(Position, 1.0);
    bool hand = isHand(FogStart, FogEnd);
    bool gui = isGUI(ProjMat);

    marker = float(!hand && !gui && (tmpcol.a == LIGHTALPHA));

    if (marker > 0.0) {
        vertexColor = vec4(tmpcol.rgb, 1.0) * Color;

        int encodedValue = markerValue(vertexColor.rgb);
        if (!isEncodedTechnicalMarker(encodedValue)) {
            vec4 projectedSource = ProjMat * tmp;
            vec2 ndc = projectedSource.xy / max(abs(projectedSource.w), 0.0001);
            bool sourceOnScreen = projectedSource.w > 0.0
                && abs(ndc.x) <= 0.92
                && abs(ndc.y) <= 0.92;
            vec3 fixedSource = vec3(tmp.x, tmp.y, -tmp.z);
            if (!sourceOnScreen || length(fixedSource) < 0.20) {
                int mode = offscreenMode(fixedSource);
                vec3 proxy = offscreenProxy(fixedSource, mode);
                tmp.xyz = vec3(proxy.x, proxy.y, -proxy.z);
                vertexColor.rgb = packOffscreenColor(vertexColor.rgb, mode);
            }
        }
        
        if (gl_VertexID % 4 == 0) {
            tmp.xy += vec2(-HALFMARKER, HALFMARKER);
            texCoord2 = vec2(0.0, 0.0);
        }
        else if (gl_VertexID % 4 == 1) {
            tmp.xy += vec2(-HALFMARKER, -HALFMARKER);
            texCoord2 = vec2(0.0, 1.0);
        }
        else if (gl_VertexID % 4 == 2) {
            tmp.xy += vec2(HALFMARKER, -HALFMARKER);
            texCoord2 = vec2(1.0, 1.0);
        }
        else {
            tmp.xy += vec2(HALFMARKER, HALFMARKER);
            texCoord2 = vec2(1.0, 0.0);
        }
        
        scale = abs(HALFMARKER * ProjMat[1][1] / tmp.z);
    }

    tmp = ProjMat * tmp;
    glpos = tmp;
    gl_Position = tmp;

}
