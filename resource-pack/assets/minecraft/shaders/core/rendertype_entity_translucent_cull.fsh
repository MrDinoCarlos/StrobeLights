#version 150

#moj_import <fog.glsl>
#moj_import <utils.glsl>

uniform sampler2D Sampler0;

uniform mat4 ProjMat;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec2 texCoord1;
in vec2 texCoord2;
in vec4 normal;
in vec4 glpos;
flat in float marker;
flat in vec4 markerPayload;
flat in float carrierLinearDepth;
in float scale;

out vec4 fragColor;

// Leather horse armor is rendered through entity_translucent_cull in 1.20.1,
// which targets minecraft:main instead of minecraft:item_entity. Preserve the
// scene color with zero-alpha blending and use a tiny, structured depth code as
// an invisible side channel for the Fabulous post pass.
#define CARRIER_DEPTH_BIAS 0.001
#define CARRIER_DEPTH_STEP 0.0000005
#define CARRIER_DEPTH_BUCKET 0.00001
#define CARRIER_DEPTH_LEVELS 16383.0
#define CARRIER_MAX_LINEAR_DEPTH 128.0

int markerValue(vec3 color) {
    ivec3 bytes = ivec3(floor(color * 255.0 + 0.5));
    return (bytes.r << 16) | (bytes.g << 8) | bytes.b;
}

void main() {
    if (marker < 0.5) {
        vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
        if (color.a < 0.1) {
            discard;
        }
        fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
        gl_FragDepth = gl_FragCoord.z;
    } else {
        vec2 uvDx = dFdx(texCoord2);
        vec2 uvDy = dFdy(texCoord2);
        mat2 uvPerPixel = mat2(uvDx, uvDy);
        if (abs(determinant(uvPerPixel)) < 0.000000000001) {
            discard;
        }

        vec2 pixelOffset = inverse(uvPerPixel) * (texCoord2 - vec2(0.5));
        ivec2 cell = ivec2(floor(pixelOffset + vec2(0.5)));
        if (abs(cell.x) > 1 || abs(cell.y) > 1) {
            discard;
        }

        int cellIndex = (cell.y + 1) * 3 + cell.x + 1;
        int encodedValue = markerValue(markerPayload.rgb);
        int linearDepthCode = int(floor(
            clamp(
                carrierLinearDepth / CARRIER_MAX_LINEAR_DEPTH,
                0.0,
                1.0
            ) * CARRIER_DEPTH_LEVELS + 0.5
        ));
        float carrierDepth = CARRIER_DEPTH_BIAS
            + float(linearDepthCode) * CARRIER_DEPTH_BUCKET;
        if (cellIndex == 4) {
            gl_FragDepth = carrierDepth;
        } else {
            int payloadIndex = cellIndex < 4 ? cellIndex : cellIndex - 1;
            int triplet = (encodedValue >> (payloadIndex * 3)) & 7;
            int cellCode = ((payloadIndex + 1) << 3) | triplet;
            gl_FragDepth = carrierDepth
                + float(cellCode + 1) * CARRIER_DEPTH_STEP;
        }
        fragColor = vec4(0.0);
    }
}
