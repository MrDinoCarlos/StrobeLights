#version 150

#define CARRIER_SCENE_DEPTH_LIMIT 0.05

// EffectInstance does not preprocess imports for post programs on 1.20.1.

uniform sampler2D DiffuseSampler;
uniform sampler2D DiffuseDepthSampler;
uniform sampler2D TranslucentSampler;
uniform sampler2D TranslucentDepthSampler;
uniform sampler2D ItemEntitySampler;
uniform sampler2D ItemEntityDepthSampler;
uniform sampler2D ParticlesSampler;
uniform sampler2D ParticlesDepthSampler;
uniform sampler2D WeatherSampler;
uniform sampler2D WeatherDepthSampler;
uniform sampler2D CloudsSampler;
uniform sampler2D CloudsDepthSampler;

in vec2 texCoord;
in vec2 oneTexel;


#define NUM_LAYERS 6

vec4 color_layers[NUM_LAYERS];
float depth_layers[NUM_LAYERS];
int index_layers[NUM_LAYERS] = int[NUM_LAYERS](0, 1 ,2, 3, 4, 5);
int active_layers = 0;

out vec4 fragColor;

float resolveSceneDepth(sampler2D depthSampler, vec2 coord) {
    float depth = texture(depthSampler, coord).r;
    if (depth >= CARRIER_SCENE_DEPTH_LIMIT) {
        return depth;
    }
    float replacement = 1.0;
    vec2 offsetX = vec2(oneTexel.x * 2.0, 0.0);
    vec2 offsetY = vec2(0.0, oneTexel.y * 2.0);
    float candidate = texture(depthSampler, coord + offsetX).r;
    if (candidate >= CARRIER_SCENE_DEPTH_LIMIT) replacement = min(replacement, candidate);
    candidate = texture(depthSampler, coord - offsetX).r;
    if (candidate >= CARRIER_SCENE_DEPTH_LIMIT) replacement = min(replacement, candidate);
    candidate = texture(depthSampler, coord + offsetY).r;
    if (candidate >= CARRIER_SCENE_DEPTH_LIMIT) replacement = min(replacement, candidate);
    candidate = texture(depthSampler, coord - offsetY).r;
    if (candidate >= CARRIER_SCENE_DEPTH_LIMIT) replacement = min(replacement, candidate);
    return replacement;
}

int try_insert( sampler2D cSampler, sampler2D dSampler, vec2 coord ) {
    vec4 color = texture(cSampler, coord);
    if ( color.a == 0.0 ) {
        return 0;
    }

    float depth = texture( dSampler, coord ).r;
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
    depth_layers[0] = resolveSceneDepth(DiffuseDepthSampler, texCoord);
    active_layers = 1;

    try_insert(CloudsSampler, CloudsDepthSampler, texCoord);
    try_insert(TranslucentSampler, TranslucentDepthSampler, texCoord);
    try_insert(ParticlesSampler, ParticlesDepthSampler, texCoord);
    try_insert(WeatherSampler, WeatherDepthSampler, texCoord);
    try_insert(ItemEntitySampler, ItemEntityDepthSampler, texCoord);
    
    vec3 texelAccum = color_layers[index_layers[0]].rgb;
    for ( int ii = 1; ii < active_layers; ++ii ) {
        texelAccum = blend( texelAccum, color_layers[index_layers[ii]] );
    }

    fragColor = vec4( texelAccum.rgb, 1.0 );
}
