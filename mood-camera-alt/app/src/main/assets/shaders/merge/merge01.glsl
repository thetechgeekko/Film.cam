#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
layout(rgba16f, binding = 0) uniform highp readonly image2D inTexture;
layout(rgba16f, binding = 1) uniform highp writeonly image2D outTexture;
#define TILE 2
#define CONCAT 1
#define STEP 4
#define SUBSTEP 16
void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    ivec2 tile = xy / STEP;
    // create non uniform grid
    ivec2 uv = SUBSTEP*((xy * STEP)/SUBSTEP) + xy % SUBSTEP;
    vec4 t1 = imageLoad(inTexture, uv)-imageLoad(inTexture, uv+ivec2(1, 0));
    vec4 t2 = imageLoad(inTexture, uv+ivec2(STEP/2, 0))-imageLoad(inTexture, uv+ivec2(STEP/2+1, 0));
    vec4 t3 = imageLoad(inTexture, uv+ivec2(0, STEP/2))-imageLoad(inTexture, uv+ivec2(1, STEP/2));
    if(length(t1) > length(t2)) {
        uv += ivec2(STEP/2, 0);
    }
    if(length(t2) > length(t3)) {
        uv += ivec2(0, STEP/2);
    }
    // estimate the noise level
    vec4 avrg = vec4(0.0);
    float Z = 0.0001;
    vec4 center = imageLoad(inTexture, uv);
    // use SNN to estimate avrg
    for (int i = -TILE; i <= TILE; i++) {
        for (int j = -TILE; j <= TILE; j++) {
            if(i == 0 && j == 0) continue;
            vec4 f0 = imageLoad(inTexture, ivec2(uv + ivec2(i, j)));
            vec4 f1 = imageLoad(inTexture, ivec2(uv + ivec2(-i, j)));
            vec4 f2 = imageLoad(inTexture, ivec2(uv + ivec2(i, -j)));
            vec4 f3 = imageLoad(inTexture, ivec2(uv + ivec2(-i, -j)));
            float w0 = length(f0 - center);
            float w1 = length(f1 - center);
            float w2 = length(f2 - center);
            float w3 = length(f3 - center);
            float mn = min(min(w0, w1), min(w2, w3));
            w0 = mn == w0 ? 1.0 : 0.0;
            w1 = mn == w1 ? 1.0 : 0.0;
            w2 = mn == w2 ? 1.0 : 0.0;
            w3 = mn == w3 ? 1.0 : 0.0;
            avrg += w0 * f0 + w1 * f1 + w2 * f2 + w3 * f3;
            Z += w0 + w1 + w2 + w3;
        }
    }
    avrg /= Z;
    //avrg /= 9.0;
    vec4 diff = abs(center - avrg);
    vec4 nl = vec4(dot(avrg, vec4(0.0,0.5,0.5,0.0)), diff.r, (diff.g+diff.b)/2.0, diff.a);
    imageStore(outTexture, xy, vec4(nl));
}
