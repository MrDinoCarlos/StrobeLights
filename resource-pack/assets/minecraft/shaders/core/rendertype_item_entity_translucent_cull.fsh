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
in float scale;

out vec4 fragColor;

void main() {
    bool gui = isGUI(ProjMat);


    if (marker < 0.5) {
        vec4 color = texture(Sampler0, texCoord0);
        if (color.a < 0.1) {
            discard;
        }
        color *= vertexColor * ColorModulator;
        fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
        fragColor.a = fragColor.a < 0.1 ? 0.1 : fragColor.a;

        if (!gui && gl_FragCoord.z <= LIGHTDEPTH) {
            gl_FragDepth = LIGHTDEPTH + 10e-7;
        }
        else {
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
        // Transport one payload pixel between two opaque guards. Minecraft's
        // item-entity render type multiplies RGB by source alpha, so the old
        // 2/255 carrier was quantized away on real 1.20.1 framebuffers even
        // though an isolated probe could recover it. Opaque bytes survive the
        // vanilla and OptiFine paths exactly; the transparency compositor
        // removes every carrier pixel through the reserved depth route below.
        if (abs(cell.x) > 1 || cell.y != 0) {
            discard;
        }

        float centerDepth = gl_FragCoord.z
            - pixelOffset.x * dFdx(gl_FragCoord.z)
            - pixelOffset.y * dFdy(gl_FragCoord.z);
        if (cell.x < 0) {
            fragColor = vec4(194.0, 69.0, 253.0, 255.0) / 255.0;
        } else if (cell.x == 0) {
            fragColor = vec4(markerPayload.rgb, 1.0);
        } else {
            fragColor = vec4(61.0, 186.0, 2.0, 255.0) / 255.0;
        }
        gl_FragDepth = centerDepth * LIGHTDEPTH;
    }
}
