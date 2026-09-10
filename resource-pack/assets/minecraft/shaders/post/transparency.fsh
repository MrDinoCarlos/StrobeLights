#version 330

#moj_import <minecraft:utils.glsl>

uniform sampler2D DiffuseSampler;
uniform sampler2D DiffuseDepthSampler;
uniform sampler2D TranslucentSampler;
uniform sampler2D TranslucentDepthSampler;
uniform sampler2D ItemEntitySampler;
uniform sampler2D ItemEntityDepthSampler;
uniform sampler2D ItemEntityLightSampler;
uniform sampler2D ParticlesSampler;
uniform sampler2D ParticlesDepthSampler;
uniform sampler2D WeatherSampler;
uniform sampler2D WeatherDepthSampler;
uniform sampler2D CloudsSampler;
uniform sampler2D CloudsDepthSampler;

const float Test = 0.0;

in vec2 texCoord;
in vec2 oneTexel;


#define NUM_LAYERS 6

vec4 color_layers[NUM_LAYERS];
float depth_layers[NUM_LAYERS];
int index_layers[NUM_LAYERS] = int[NUM_LAYERS](0, 1 ,2, 3, 4, 5);
int active_layers = 0;

out vec4 fragColor;

ivec4 markerBytes(sampler2D sampler, vec2 coord) {
    return ivec4(floor(texture(sampler, coord) * 255.0 + 0.5));
}

bool validTechnicalCenter(sampler2D sampler, vec2 center, vec2 pixelSize) {
    ivec4 anchor = markerBytes(sampler, center);
    bool strobeCenter = abs(anchor.a - 5) <= 1
        && all(lessThanEqual(abs(anchor.rgb - ivec3(2)), ivec3(1)));
    bool trpCenter = anchor.a >= 7 && anchor.a <= 22;
    int expectedTrpRgb = int(floor(float(anchor.a) * 0.4 + 0.5));
    trpCenter = trpCenter && all(lessThanEqual(
        abs(anchor.rgb - ivec3(expectedTrpRgb)),
        ivec3(1)
    ));
    if (!strobeCenter && !trpCenter) {
        return false;
    }
    int payloadIndex = 0;
    for (int y = -1; y <= 1; y += 1) {
        for (int x = -1; x <= 1; x += 1) {
            if (x == 0 && y == 0) {
                continue;
            }
            ivec4 cell = markerBytes(
                sampler,
                center + vec2(float(x), float(y)) * pixelSize
            );
            bool validAlpha = payloadIndex < 4
                ? cell.a >= 2 && cell.a <= 17 : cell.a == 2;
            vec3 expectedBits = step(vec3(float(cell.a) * 0.25), vec3(cell.rgb));
            vec3 expectedRgb = expectedBits * float(cell.a) * 0.5;
            if (!validAlpha
                || any(greaterThan(abs(vec3(cell.rgb) - expectedRgb), vec3(0.51)))) {
                return false;
            }
            payloadIndex += 1;
        }
    }
    return true;
}

bool technicalMarkerPixel(sampler2D sampler, vec2 coord) {
    ivec4 current = markerBytes(sampler, coord);
    // Fast path for ordinary near-camera item geometry.
    if (current.a > 23 || any(greaterThan(current.rgb, ivec3(10)))) {
        return false;
    }
    vec2 pixelSize = 1.0 / vec2(textureSize(sampler, 0));
    for (int y = -1; y <= 1; y += 1) {
        for (int x = -1; x <= 1; x += 1) {
            vec2 center = coord + vec2(float(x), float(y)) * pixelSize;
            if (validTechnicalCenter(sampler, center, pixelSize)) {
                return true;
            }
        }
    }
    return false;
}

int try_insert( sampler2D cSampler, sampler2D dSampler, vec2 coord, int ie ) {
    vec4 color = texture(cSampler, coord);
    if ( color.a == 0.0 ) {
        return 0;
    }

    float depth = texture( dSampler, coord ).r;
    if (ie > 0) {
        if (depth < LIGHTDEPTH && technicalMarkerPixel(cSampler, coord)) {
            if (Test > 0.0) {
                color.rgb = vec3(0.0, 1.0, 0.0);
                depth = 0.0;
            }
            else {
                return 1;
            }
        }
        // The item layer is composed after the world. Light it with the same
        // RGB map so an opaque TRP beam cannot erase an overlapping strobe.
        vec4 encodedLight = texture(ItemEntityLightSampler, coord);
        vec3 itemLight = encodedLight.rgb * (1.0 + clamp(
            encodedLight.a - 26.0 / 255.0, 0.0, 1.0
        ) * 3.0 * 255.0 / 224.0);
        color.rgb += itemLight * color.a * 0.55;
    }
    color_layers[active_layers] = color;
    depth_layers[active_layers] = depth;

    int jj = active_layers++;
    int ii = jj - 1;
    while ( jj > 0 && depth > depth_layers[index_layers[ii]] ) {
        int indexTemp = index_layers[ii];
        index_layers[ii] = index_layers[jj];
        index_layers[jj] = indexTemp;

        jj = ii--;
    }

    return 2;
}

vec3 blend( vec3 dst, vec4 src ) {
    return ( dst * ( 1.0 - src.a ) ) + src.rgb;
}

void main() {
    color_layers[0] = vec4( texture( DiffuseSampler, texCoord ).rgb, 1.0 );
    depth_layers[0] = texture( DiffuseDepthSampler, texCoord ).r;
    active_layers = 1;

    try_insert(CloudsSampler, CloudsDepthSampler, texCoord, 0);
    try_insert(TranslucentSampler, TranslucentDepthSampler, texCoord, 0);
    try_insert(ParticlesSampler, ParticlesDepthSampler, texCoord, 0);
    try_insert(WeatherSampler, WeatherDepthSampler, texCoord, 0);
    if (try_insert(ItemEntitySampler, ItemEntityDepthSampler, texCoord, 1) == 1) {
        if (try_insert(ItemEntitySampler, ItemEntityDepthSampler, texCoord + vec2(0.0, oneTexel.y), 1) == 1) {
            try_insert(ItemEntitySampler, ItemEntityDepthSampler, texCoord + vec2(0.0, -oneTexel.y), 1);
        }
        
    }
    
    vec3 texelAccum = color_layers[index_layers[0]].rgb;
    for ( int ii = 1; ii < active_layers; ++ii ) {
        texelAccum = blend( texelAccum, color_layers[index_layers[ii]] );
    }

    fragColor = vec4( texelAccum.rgb, 1.0 );
}
