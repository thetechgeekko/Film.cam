#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
layout(rgba16ui, binding = 0) uniform highp readonly image2D inTexture;
layout(r16ui, binding = 1) uniform highp writeonly image2D outTexture;
uniform float whiteLevel;
#define TILE 2
#define CONCAT 1

float getBayer(ivec2 coords){
    return float(imageLoad(inTexture,coords).r)/whiteLevel;
}

vec4 getBayerVec(ivec2 coords){
    return vec4(getBayer(coords),getBayer(coords+ivec2(1,0)),getBayer(coords+ivec2(0,1)),getBayer(coords+ivec2(1,1)));
}

void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    vec4 bayer = getBayerVec(xy);
    imageStore(outTexture, xy*TILE, vec4(bayer[0]));
}
