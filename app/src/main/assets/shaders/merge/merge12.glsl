#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
layout(rgba16f, binding = 0) uniform highp readonly image2D inTexture;
layout(rgba16f, binding = 1) uniform highp readonly image2D diffTexture;
layout(rgba16f, binding = 2) uniform highp writeonly image2D outTexture;
#define TILE 2
#define CONCAT 1
uniform float weight;
uniform float noiseS;
uniform float noiseO;
shared vec4 motion[12][12];
shared vec2 delta[12][12];
const int DELTA = 2;
const int MAX_ITERATIONS = 5;
const float EPSILON = 0.01;

vec4 spatialDiff(ivec2 xy) {
    vec4 base = (imageLoad(inTexture, xy*2) + imageLoad(inTexture, xy*2 + ivec2(1, 0)) + imageLoad(inTexture, xy*2 + ivec2(0, 1)) + imageLoad(inTexture, xy*2 + ivec2(1, 1)))/4.0;
    vec4 diff = imageLoad(diffTexture, xy);
    vec4 alter = (base + diff);
    vec4 motionResult;
    motionResult.r = length(alter - (imageLoad(inTexture, xy*2)));
    motionResult.g = length(alter - (imageLoad(inTexture, xy*2 + ivec2(2, 0))))*0.5;
    motionResult.b = length(alter - (imageLoad(inTexture, xy*2 + ivec2(0, 2))))*0.5;
    motionResult.a = length(alter - (imageLoad(inTexture, xy*2 + ivec2(2, 2))))*0.5;
    //motionResult.a = 1.0;
    return motionResult;
}

vec2 spatialDelta(ivec2 xy) {
    vec4 base = (imageLoad(inTexture, xy*2) + imageLoad(inTexture, xy*2 + ivec2(1, 0)) + imageLoad(inTexture, xy*2 + ivec2(0, 1)) + imageLoad(inTexture, xy*2 + ivec2(1, 1)))/4.0;
    vec4 diff = imageLoad(diffTexture, xy);
    vec4 alter = base + diff;
    vec2 motionResult;
    motionResult.r = length(imageLoad(inTexture, xy*2) - imageLoad(inTexture, xy*2 + ivec2(2, 0)))/2.0;
    motionResult.g = length(imageLoad(inTexture, xy*2) - imageLoad(inTexture, xy*2 + ivec2(0, 2)))/2.0;
    return motionResult;
}

#define EPS 1e-6
void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    ivec2 localPos = ivec2(gl_LocalInvocationID.xy);
    ivec2 workgroupStart = ivec2(gl_WorkGroupID.xy) * ivec2(gl_WorkGroupSize.xy);
    vec4 base = (imageLoad(inTexture, xy*2) + imageLoad(inTexture, xy*2 + ivec2(1, 0)) + imageLoad(inTexture, xy*2 + ivec2(0, 1)) + imageLoad(inTexture, xy*2 + ivec2(1, 1)))/4.0;
    vec4 diff = imageLoad(diffTexture, xy);
    vec4 alter = base + diff;
    int l = localPos.x + localPos.y * int(gl_WorkGroupSize.x);
    int l1 = min(l*3, 12*12-2);
    int l2 = l1 + 1;
    int l3 = l1 + 2;
    ivec2 shared1 = ivec2(l1 % 12, l1 / 12);
    ivec2 shared2 = ivec2(l2 % 12, l2 / 12);
    ivec2 shared3 = ivec2(l3 % 12, l3 / 12);
    motion[shared1.x][shared1.y] = spatialDiff(workgroupStart + shared1 - ivec2(2,2));
    motion[shared2.x][shared2.y] = spatialDiff(workgroupStart + shared2 - ivec2(2,2));
    motion[shared3.x][shared3.y] = spatialDiff(workgroupStart + shared3 - ivec2(2,2));
    delta[shared1.x][shared1.y] = spatialDelta(workgroupStart + shared1 - ivec2(2,2));
    delta[shared2.x][shared2.y] = spatialDelta(workgroupStart + shared2 - ivec2(2,2));
    delta[shared3.x][shared3.y] = spatialDelta(workgroupStart + shared3 - ivec2(2,2));
    memoryBarrierShared();
    barrier();
    /*mat2 structureTensor;
    for(int i = -2; i <= 2; i++) {
        for(int j = -2; j <= 2; j++) {
            float gradx = delta[localPos.x + i + 2][localPos.y + j + 2].r;
            float grady = delta[localPos.x + i + 2][localPos.y + j + 2].g;
            structureTensor += mat2(gradx*gradx, gradx*grady, gradx*grady, grady*grady);
        }
    }
    structureTensor /= float(25);
    mat2 invCovariance = inverse(structureTensor + mat2(EPS));
    for(int i = -1; i <= 1; i++) {
        for(int j = -1; j <= 1; j++) {
            ivec2 samplePos = 2*xy + ivec2(i, j);
            vec2 offset = vec2(i,j);
            float kernelWeight = exp(-0.5 * dot(offset * invCovariance, offset));
            vec4 sampleStore = imageLoad(inTexture, samplePos);
            imageStore(outTexture, samplePos, mix(sampleStore, alter, kernelWeight));
        }
    }*/
    vec4 deltas = vec4(0.0);
    for (int i = -2; i <= 2; i++) {
        for (int j = -2; j <= 2; j++) {
            //deltas += motion[localPos.x + i + 2][localPos.y + j + 2];
            deltas += spatialDiff(xy + ivec2(i,j));
        }
    }
    deltas /= 25.0;
    float mindelta = min(min(deltas.r, deltas.g), min(deltas.b,deltas.a));
    float maxdelta = max(max(deltas.r, deltas.g), max(deltas.b,deltas.a));
    vec4 r0 = imageLoad(inTexture, xy*2);
    vec4 r1 = imageLoad(inTexture, xy*2 + ivec2(1, 0));
    vec4 r2 = imageLoad(inTexture, xy*2 + ivec2(0, 1));
    vec4 r3 = imageLoad(inTexture, xy*2 + ivec2(1, 1));
    if (deltas.r == mindelta) {
        r0 = alter;
    }
    if (deltas.g == mindelta) {
        r1 = alter;
    }
    if (deltas.b == mindelta) {
        r2 = alter;

    }
    if (deltas.a == mindelta) {
        r3 = alter;
    }
    /*vec4 normDelta = (deltas - mindelta) / (maxdelta - mindelta + EPS);

    imageStore(outTexture, xy*2, mix(r0, alter, normDelta.r));
    imageStore(outTexture, xy*2 + ivec2(1, 0), mix(r1, alter, normDelta.g));
    imageStore(outTexture, xy*2 + ivec2(0, 1), mix(r2, alter, normDelta.b));
    imageStore(outTexture, xy*2 + ivec2(1, 1), mix(r3, alter, normDelta.a));*/
    imageStore(outTexture, xy*2, r0);
    imageStore(outTexture, xy*2 + ivec2(1, 0), r1);
    imageStore(outTexture, xy*2 + ivec2(0, 1), r2);
    imageStore(outTexture, xy*2 + ivec2(1, 1), r3);
}
