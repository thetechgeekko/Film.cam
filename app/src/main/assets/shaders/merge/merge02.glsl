#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
layout(rgba16f, binding = 0) uniform highp readonly image2D inTexture;
layout(rgba16f, binding = 1) uniform highp writeonly image2D outTexture;
#define TILE 2
#define STEP 4
void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    ivec2 tile = xy / STEP;
    // create non uniform grid
    ivec2 uv = xy * STEP;
    vec4 br = vec4(0.0);
    float Z = 0.0;
    for (int i = -TILE; i <= TILE; i++) {
        for (int j = -TILE; j <= TILE; j++) {
            ivec2 uv2 = uv + ivec2(i, j);
            vec4 c = imageLoad(inTexture, uv2);
            br += c;
            Z += 1.0;
        }
    }
    imageStore(outTexture, xy, vec4(br)/Z);
}
