precision highp float;
precision highp int;
layout(rgba16f, binding = 0) uniform highp readonly image2D inTexture;
layout(rgba16f, binding = 1) uniform highp writeonly image2D outTexture;
#define LAYOUT //
#define OUTSET 0,0
#define TILE 3
#define NOISEO 0.0
#define NOISES 0.0
#define IMPULSE 7.0
#import median
LAYOUT
void main() {
    ivec2 xyIn = ivec2(gl_GlobalInvocationID.xy);
    ivec2 maxcoords = ivec2(0,0);
    vec3 maxval = vec3(0.0);
    vec3 avr[9];
    for (int i = -1; i <= 1; i++) {
        for (int j = -1; j <= 1; j++) {
            avr[(i+1)*3+j+1] = imageLoad(inTexture, xyIn + ivec2(i, j)).rgb;
            maxval = max(maxval, avr[(i+1)*3+j+1]);
        }
    }
    float noise = sqrt(length(avr[4])*NOISES + NOISEO);
    avr[0] = median9(avr);
    if((max(maxval.r,max(maxval.g,maxval.b)))/(max(avr[0].r,max(avr[0].g,avr[0].b))+0.0001) > IMPULSE
    || (max(avr[0].r,max(avr[0].g,avr[0].b)))/(max(maxval.r,max(maxval.g,maxval.b))+0.0001) > IMPULSE
    )
    imageStore(outTexture, xyIn, vec4(avr[0], 1.0));
}