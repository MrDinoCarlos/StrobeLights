#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:utils.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec2 texCoord2;
in vec4 glpos;
in float marker;

out vec4 fragColor;

int markerValue(vec3 color) {
    ivec3 bytes = ivec3(floor(color * 255.0 + 0.5));
    return (bytes.r << 16) | (bytes.g << 8) | bytes.b;
}

void main() {
    bool gui = isGUI(ProjMat);
    if (marker < 0.5) {
        vec4 color = texture(Sampler0, texCoord0);
#ifdef ALPHA_CUTOUT
        if (color.a < ALPHA_CUTOUT) {
            discard;
        }
#else
        if (color.a < 0.1) {
            discard;
        }
#endif
        color *= vertexColor * ColorModulator;
        fragColor = apply_fog(
            color,
            sphericalVertexDistance,
            cylindricalVertexDistance,
            FogEnvironmentalStart,
            FogEnvironmentalEnd,
            FogRenderDistanceStart,
            FogRenderDistanceEnd,
            FogColor
        );
        fragColor.a = fragColor.a < 0.1 ? 0.1 : fragColor.a;
        if (!gui && gl_FragCoord.z <= LIGHTDEPTH) {
            gl_FragDepth = LIGHTDEPTH + 10e-7;
        } else {
            gl_FragDepth = gl_FragCoord.z;
        }
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

        float centerDepth = gl_FragCoord.z
            - pixelOffset.x * dFdx(gl_FragCoord.z)
            - pixelOffset.y * dFdy(gl_FragCoord.z);
        int cellIndex = (cell.y + 1) * 3 + cell.x + 1;
        int encodedValue = markerValue(vertexColor.rgb);
        if (cellIndex == 4) {
            fragColor = vec4(vec3(0.4), 5.0 / 255.0);
        } else {
            int payloadIndex = cellIndex < 4 ? cellIndex : cellIndex - 1;
            int triplet = (encodedValue >> (payloadIndex * 3)) & 7;
            vec3 bitColor = vec3(
                float(triplet & 1),
                float((triplet >> 1) & 1),
                float((triplet >> 2) & 1)
            );
            fragColor = vec4(bitColor * 0.5, 2.0 / 255.0);
        }
        gl_FragDepth = centerDepth * LIGHTDEPTH;
    }
}
