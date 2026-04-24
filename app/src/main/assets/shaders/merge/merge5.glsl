#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
uniform highp usampler2D inTexture;
layout(rgba16f, binding = 0) uniform highp readonly image2D diffTexture;
layout(rgba16f, binding = 1) uniform highp writeonly image2D outTexture;
layout(rgba8, binding = 2) uniform highp writeonly image2D hotPixTexture;
uniform float whiteLevel;
uniform vec4 blackLevel;
uniform bool start;
uniform bool last;
uniform float noiseS;
uniform float noiseO;
uniform float weight;
uniform float exposure;
#import median

uint getBayer(ivec2 coords, highp usampler2D tex){
    return texelFetch(tex,coords,0).r;
}

vec4 getBayerVec(ivec2 coords, highp usampler2D tex){
    vec4 c0 = vec4(getBayer(coords,tex),getBayer(coords+ivec2(1,0),tex),getBayer(coords+ivec2(0,1),tex),getBayer(coords+ivec2(1,1),tex));
    return clamp((c0 - blackLevel)/(vec4(whiteLevel)-blackLevel), 0.0, 1.0);
}

void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    vec2 uv = vec2(xy) / vec2(imageSize(outTexture)) + vec2(0.5) / vec2(imageSize(outTexture));
    //vec2 uv = vec2(xy) / vec2(imageSize(outTexture));
    vec4 base;
    if(!start){
        base = imageLoad(diffTexture, xy);
        vec4 diff = getBayerVec(xy, inTexture)*exposure;
        vec4 bayer = getBayerVec(xy, inTexture);
        base = mix(base, base + diff, weight);
    } else {
        base = getBayerVec(xy, inTexture);
    }
    if(last){
        vec4 avr[9];
        for (int i = 0; i < 9; i++) {
            avr[i] = imageLoad(diffTexture, xy + ivec2(i % 3 - 1, i / 3 - 1));
        }
        vec4 pix = median9(avr);
        bool hotPix = false;
        bvec4 hotPixVec = greaterThan(pix - avr[4], vec4(noiseS + noiseO)/2.0);
        imageStore(hotPixTexture, xy, vec4(hotPixVec));
        return;
    }
    imageStore(outTexture, xy, base);
}
