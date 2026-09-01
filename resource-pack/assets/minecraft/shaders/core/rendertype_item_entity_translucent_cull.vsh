#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>
#moj_import <utils.glsl>

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
uniform mat3 IViewRotMat;
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

// The quad only supplies a 3x3 micro-carrier. Each cell is nearly transparent
// and the Fabulous pass rebuilds the exact payload from those faint bits.
#define HALFMARKER tmp.z / 64.0

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
    // Keep the invisible carrier close to the camera and well inside the
    // frame. The lighting passes reverse both scales before illumination, so
    // the actual light remains at its exact, fixed 3D source.
    float axisScale = 0.0625;
    float depthScale = 0.25;
    if (mode == 0) {
        return vec3(0.0, 0.0, 0.25);
    }
    if (mode == 1) {
        return vec3(
            -source.x * axisScale,
            -source.y * axisScale,
            -source.z * depthScale
        );
    }
    if (mode == 2) {
        return vec3(
            -source.z * axisScale,
            source.y * axisScale,
            source.x * depthScale
        );
    }
    if (mode == 3) {
        return vec3(
            source.z * axisScale,
            source.y * axisScale,
            -source.x * depthScale
        );
    }
    if (mode == 4) {
        return vec3(
            source.x * axisScale,
            -source.z * axisScale,
            source.y * depthScale
        );
    }
    if (mode == 5) {
        return vec3(
            source.x * axisScale,
            source.z * axisScale,
            -source.y * depthScale
        );
    }
    return vec3(
        source.x * axisScale,
        source.y * axisScale,
        source.z * depthScale
    );
}

vec3 packOffscreenColor(
    vec3 color,
    int mode,
    int projectionCode,
    int expansionCode
) {
    ivec3 color4 = ivec3(floor(clamp(color, 0.0, 1.0) * 15.0 + 0.5));
    // Bit 23 remains zero and identifies an offscreen light. The remaining
    // 23 bits preserve every requested field without relying on depth-buffer
    // precision: mode 3, projection 4, expansion 4 and RGB 4+4+4.
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

void main() {
    vertexDistance = fog_distance(ModelViewMat, IViewRotMat * Position, FogShape);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color) * texelFetch(Sampler2, UV2 / 16, 0);
    texCoord0 = UV0;
    texCoord1 = UV1;
    normal = ProjMat * ModelViewMat * vec4(Normal, 0.0);

    vec4 tmpcol = texture(Sampler0, UV0);
    vec4 tmp = ModelViewMat * vec4(Position, 1.0);
    bool gui = isGUI(ProjMat);

    int encodedValue = markerValue(Color.rgb);
    bool encodedTechnicalCarrier = isCameraFlash(encodedValue)
        || isSourceLight(encodedValue);
    // OptiFine can quantize the atlas alpha by one or two 8-bit steps while
    // rebuilding the item model. The payload signature is therefore checked
    // independently instead of treating every low-alpha item as a light.
    bool markerAlpha = abs(tmpcol.a - LIGHTALPHA) <= LIGHTALPHATOLERANCE;
    // The dedicated carrier texture is neutral white. Compare its chroma and
    // brightness relative to alpha so OptiFine premultiplication is accepted,
    // while the black translucent edge pixels of held items are rejected.
    float markerTextureFloor = tmpcol.a * 0.5;
    float markerTexturePeak = max(max(tmpcol.r, tmpcol.g), tmpcol.b);
    float markerTextureBase = min(min(tmpcol.r, tmpcol.g), tmpcol.b);
    bool markerTextureCarrier = markerTexturePeak >= markerTextureFloor
        && markerTextureBase >= markerTexturePeak * 0.75;
    // Do not infer a first-person render from fog distances. OptiFine's Fog:
    // OFF mode supplies NO_FOG with equal start/end values for world entities,
    // which made every ItemDisplay look like a hand item and removed all
    // StrobeLights markers before the Fabulous post chain.
    marker = float(
        !gui
        && encodedTechnicalCarrier
        && markerAlpha
        && markerTextureCarrier
    );

    if (marker > 0.0) {
        // Do not multiply the payload by the atlas RGB. OptiFine may
        // premultiply that texel, but the custom tint still owns the marker.
        vertexColor = vec4(Color.rgb, 1.0);

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
