#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
precision highp image2D;
uniform highp usampler2D inTexture;
layout(rgba16f, binding = 0) uniform highp writeonly image2D outTexture;


uniform float whiteLevel;
uniform vec4 blackLevel;
uniform float exposure;
uniform bool createDiff;
uniform float noiseS;
uniform float noiseO;
uniform ivec2 border;
uniform int cfaPattern;
#define TILE 2
#define CONCAT 1
#define M_PI 3.1415926535897932384626433832795
#define TILE_AL 16

uint getBayer(ivec2 coords, highp usampler2D tex){
    return texelFetch(tex,coords,0).r;
}

float getBayerNorm(ivec2 coords, highp usampler2D tex){
    return clamp((float(getBayer(coords, tex)) - dot(blackLevel, vec4(0.25)))/(float(whiteLevel)-dot(blackLevel,vec4(0.25))), 0.0, 1.0);
}
vec4 getBayerVec(ivec2 coords, highp usampler2D tex){
    vec4 c0 = vec4(getBayer(coords,tex),getBayer(coords+ivec2(1,0),tex),getBayer(coords+ivec2(0,1),tex),getBayer(coords+ivec2(1,1),tex));
    return clamp((c0 - blackLevel)/(vec4(whiteLevel)-blackLevel), 0.0, 1.0);
}

void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    vec4 bayer = getBayerVec(xy*TILE, inTexture);
    imageStore(outTexture, xy, bayer * vec4(exposure));
}