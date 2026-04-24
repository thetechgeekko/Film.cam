precision highp float;
precision highp sampler2D;
uniform highp sampler2D inTexture;
uniform highp sampler2D alignmentTexture;
uniform float whiteLevel;
uniform vec4 blackLevel;
uniform int yOffset;
#define WHITE_LEVEL 0.0
#define BLACK_LEVEL 0.0
#define TILE 2
#define CONCAT 1
out uint Output;


uvec4 getBayerVec(ivec2 coords){
    return uvec4(clamp(texelFetch(inTexture, coords, 0),0.0,1.0) * (vec4(WHITE_LEVEL)-vec4(BLACK_LEVEL)) + vec4(BLACK_LEVEL));
}
/*uvec4 getAlignmentVec(ivec2 coords){
    return uvec4(clamp(texelFetch(alignmentTexture, coords, 0),0.0,1.0) * (vec4(WHITE_LEVEL)-vec4(BLACK_LEVEL)) + vec4(BLACK_LEVEL));
}*/

uvec4 getAlignmentVec(ivec2 coords){
    vec2 value = texelFetch(alignmentTexture, coords, 0).xy;
    float dist = length(value);
    return uvec4(clamp(vec4(dist),0.0,1.0) * (vec4(WHITE_LEVEL)-vec4(BLACK_LEVEL)) + vec4(BLACK_LEVEL));
}


void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    xy += ivec2(0, yOffset);
    ivec2 alignSize = textureSize(alignmentTexture, 0);
    int x = xy.x%TILE;
    int y = xy.y%TILE;
    uvec4 bayer = getBayerVec((xy/TILE));
    Output = bayer[x + y*TILE];
    /*if (xy.x < alignSize.x && xy.y < alignSize.y){
        uvec4 alignment = getAlignmentVec(xy);
        Output = alignment[x + y*TILE];
    }*/
}
