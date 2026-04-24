#define LAYOUT //
LAYOUT
precision highp float;
precision highp sampler2D;
//uniform highp sampler2D diffTexture;
layout(rgba16f, binding = 0) uniform highp readonly image2D diffTexture;
layout(rgba16f, binding = 1) uniform highp readonly image2D baseTexture;
layout(rgba16f, binding = 2) uniform highp writeonly image2D outTexture;
uniform float whiteLevel;
uniform vec4 blackLevel;
uniform float noiseS;
uniform float noiseO;
uniform int cfaPattern;

shared float s_prev[12][12];    // 8x8 block + 1 pixel border
shared float s_curr[12][12];    // 8x8 block + 1 pixel border

const float EPSILON = 0.1;
const int MAX_ITERATIONS = 5;
const float EPS = 1e-6;
const int DELTA = 2;
/*uint getBayerSimple(ivec2 coords, highp usampler2D tex){
    return texelFetch(tex,coords,0).r;
}

vec4 getBayerVec(ivec2 coords, highp usampler2D tex){
    vec4 c0 = vec4(getBayerSimple(coords,tex),getBayerSimple(coords+ivec2(1,0),tex),getBayerSimple(coords+ivec2(0,1),tex),getBayerSimple(coords+ivec2(1,1),tex));
    return clamp((c0 - blackLevel)/(vec4(whiteLevel)-blackLevel), 0.0, 1.0);
}*/

// Calculate spatial and temporal derivatives
void calculateDerivatives(ivec2 lid, out vec2 spatial, out float temporal) {
    // Calculate centered derivatives
    lid = clamp(lid, ivec2(0), ivec2(9));
    float dx_prev = s_prev[lid.y + 1][lid.x + 2] - s_prev[lid.y + 1][lid.x];
    float dy_prev = s_prev[lid.y + 2][lid.x + 1] - s_prev[lid.y][lid.x + 1];
    float dx_curr = s_curr[lid.y + 1][lid.x + 2] - s_curr[lid.y + 1][lid.x];
    float dy_curr = s_curr[lid.y + 2][lid.x + 1] - s_curr[lid.y][lid.x + 1];
    float dx = (dx_prev + dx_curr) * 0.5;
    float dy = (dy_prev + dy_curr) * 0.5;
    //float dx = (s_curr[lid.y + 1][lid.x + 2] - s_curr[lid.y + 1][lid.x]) * 0.5;
    //float dy = (s_curr[lid.y + 2][lid.x + 1] - s_curr[lid.y][lid.x + 1]) * 0.5;
    float dt = s_curr[lid.y + 1][lid.x + 1] - s_prev[lid.y + 1][lid.x + 1];

    spatial = vec2(dx, dy);
    temporal = dt;
}

void loadSharedMemory(ivec2 gid, ivec2 lid) {
    // Load 16x16 block plus borders
    for(int dy = -DELTA; dy <= DELTA; dy++) {
        for(int dx = -DELTA; dx <= DELTA; dx++) {
            ivec2 pos = gid + ivec2(dx, dy);
            ivec2 shared_pos = lid + ivec2(dx + DELTA, dy + DELTA);

            // Clamp coordinates to image bounds
            pos = clamp(pos, ivec2(0), imageSize(outTexture) - ivec2(1));

            // Load luminance values
            s_prev[shared_pos.y][shared_pos.x] = dot(imageLoad(baseTexture, pos), vec4(0.25));
            s_curr[shared_pos.y][shared_pos.x] = dot(imageLoad(diffTexture, pos),vec4(0.25));
            //s_curr[shared_pos.y][shared_pos.x] = dot(texelFetch(diffTexture, pos,0),vec4(0.25));
        }
    }
}


void loadSharedMemoryGreen(ivec2 gid, ivec2 lid) {
    ivec2 shift = ivec2(0,0);
    if(cfaPattern == 0 || cfaPattern == 3) {
        shift = ivec2(0,1);
    }
    // Load 16x16 block plus borders
    for(int dy = -DELTA; dy <= DELTA; dy++) {
        for(int dx = -DELTA; dx <= DELTA; dx++) {
            ivec2 pos = gid + ivec2(dx+dy, dx-dy) + shift;
            ivec2 shared_pos = lid + ivec2(dx + DELTA, dy + DELTA);
            vec4 bayer = imageLoad(baseTexture,pos/2);
            ivec2 pos2 = pos%2;
            // Clamp coordinates to image bounds
            pos = clamp(pos, ivec2(0), imageSize(outTexture) - ivec2(1));

            // Load luminance values
            s_prev[shared_pos.y][shared_pos.x] = bayer[pos2.x+pos2.y*2];
            s_curr[shared_pos.y][shared_pos.x] = imageLoad(diffTexture, pos/2)[pos.x%2+pos.y%2*2];
            //s_curr[shared_pos.y][shared_pos.x] = dot(texelFetch(diffTexture, pos,0),vec4(0.25));
        }
    }
}

vec4 interpolateBayer(vec2 coords){
    vec4 c00 = imageLoad(diffTexture, ivec2(coords));
    vec4 c10 = imageLoad(diffTexture, ivec2(coords)+ivec2(1,0));
    vec4 c01 = imageLoad(diffTexture, ivec2(coords)+ivec2(0,1));
    vec4 c11 = imageLoad(diffTexture, ivec2(coords)+ivec2(1,1));
    vec2 f = fract(vec2(coords));
    return mix(mix(c00,c10,f.x),mix(c01,c11,f.x),f.y);
}

float bayerDiff(ivec2 coords, ivec2 coords2){
    float p = imageLoad(diffTexture, coords/2)[coords.x%2+coords.y%2*2] - imageLoad(baseTexture,coords2/2)[coords2.x%2+coords2.y%2*2];
    return p;
}

float random (vec2 uv) {
    return fract(sin(dot(uv,vec2(12.9898,78.233)))*43758.5453123);
}


void main() {
    ivec2 xy = ivec2(gl_GlobalInvocationID.xy);
    ivec2 lid = ivec2(gl_LocalInvocationID.xy);
    vec2 uvSize = vec2(1.0)/vec2(imageSize(outTexture));
    vec2 uv = vec2(xy)*uvSize + vec2(0.5)*uvSize;

    loadSharedMemoryGreen(xy*2, lid);
    barrier();
    memoryBarrierShared();
    // Lucas-Kanade iteration variables
    vec2 flow = vec2(0.0);
    mat2 A = mat2(0.0);
    vec2 b = vec2(0.0);
    /*float diffAbs = 0.0;
    float brightness = 0.0;
    for(int wy = -DELTA; wy <= DELTA; wy++) {
        for (int wx = -DELTA; wx <= DELTA; wx++) {
            ivec2 wpos = lid + ivec2(wx + DELTA, wy + DELTA);
            vec2 spatial;
            float temporal;
            calculateDerivatives(wpos, spatial, temporal);
            diffAbs += abs(length(spatial));
            brightness += s_curr[wpos.y][wpos.x];
        }
    }
    diffAbs /= 25.0;
    brightness /= 25.0;
    float noise = sqrt(noiseS * brightness + noiseO);
    float noiseWeight = diffAbs*diffAbs/(noise*noise + diffAbs*diffAbs);
    */

    // Iterate to refine flow estimate
    for(int iter = 0; iter < MAX_ITERATIONS; iter++) {
        A = mat2(0.0);
        b = vec2(0.0);

        // Accumulate over window
        for(int wy = -DELTA; wy <= DELTA; wy++) {
            for(int wx = -DELTA; wx <= DELTA; wx++) {
                ivec2 wpos = lid + ivec2(wx + DELTA, wy + DELTA);

                vec2 spatial;
                float temporal;
                calculateDerivatives(wpos, spatial, temporal);

                // Build system of equations
                A[0][0] += spatial.x * spatial.x;
                A[0][1] += spatial.x * spatial.y;
                A[1][0] += spatial.x * spatial.y;
                A[1][1] += spatial.y * spatial.y;

                b += spatial * (temporal + dot(spatial, flow));
            }
        }

        // Solve 2x2 system using Cramer's rule
        float det = A[0][0] * A[1][1] - A[0][1] * A[1][0];
        if(abs(det) > EPSILON) {
            vec2 delta = vec2(
                (A[1][1] * b.x - A[0][1] * b.y) / det,
                (-A[1][0] * b.x + A[0][0] * b.y) / det
            );

            flow -= delta;

            // Early termination if flow update is small
            if(dot(delta, delta) < EPSILON) {
                break;
            }
        } else {
            // Matrix is singular, cannot solve
            flow = vec2(0.0);
            break;
        }
    }
    flow = vec2((flow.x + flow.y)/2.0, (flow.x - flow.y)/2.0)/sqrt(2.0);
    //flow += noiseWeight * vec2(random(uv + diffAbs/4.0), random(uv - diffAbs/4.0)) * 0.5;
    //vec4 diff = imageLoad(diffTexture, xy-ivec2(flow))-getBayerVec(xy*2,inTexture);
    vec4 diff = interpolateBayer(vec2(xy)-vec2(flow))-imageLoad(baseTexture,xy);//getBayerVec(xy*2,inTexture);
    ivec2 flowDiag = ivec2(round(vec2((flow.x + flow.y)*2.0, (flow.x - flow.y)*2.0)));
    ivec2 flowGreen = ivec2((flowDiag.x + flowDiag.y), (flowDiag.x - flowDiag.y));
    vec2 f = fract(vec2((flow.x + flow.y)*2.0, (flow.x - flow.y)*2.0));
    if(cfaPattern == 0 || cfaPattern == 3) {
        float d[6];
        d[0] = bayerDiff(xy * 2 - flowGreen + ivec2(1,0), xy * 2 + ivec2(1,0));
        d[1] = bayerDiff(xy * 2 - flowGreen + ivec2(1,0) + ivec2(1,1), xy * 2 + ivec2(1,0) + ivec2(1,1));
        d[2] = bayerDiff(xy * 2 - flowGreen + ivec2(1,0) + ivec2(1,-1), xy * 2 + ivec2(1,0) + ivec2(1,-1));

        d[3] = bayerDiff(xy * 2 - flowGreen + ivec2(0,1), xy * 2 + ivec2(0,1));
        d[4] = bayerDiff(xy * 2 - flowGreen + ivec2(0,1) + ivec2(1,1), xy * 2 + ivec2(0,1) + ivec2(1,1));
        d[5] = bayerDiff(xy * 2 - flowGreen + ivec2(0,1) + ivec2(-1,1), xy * 2 + ivec2(0,1) + ivec2(-1,1));
        // interpolate using f
        float dx0 = mix(d[0],d[1],f.x);
        float dy0 = mix(dx0,d[2],f.x);
        float dx1 = mix(d[3],d[4],f.x);
        float dy1 = mix(dx1,d[5],f.x);
        diff[1] = dy0;
        diff[2] = dy1;
        //diff[1] = bayerDiff(xy * 2 - flowGreen + ivec2(1,0), xy * 2 + ivec2(1,0));
        //diff[2] = bayerDiff(xy * 2 - flowGreen + ivec2(0,1), xy * 2 + ivec2(0,1));
    } else {
        //diff[0] = bayerDiff(xy * 2 - flowGreen, xy * 2);
        //diff[3] = bayerDiff(xy * 2 - flowGreen + ivec2(1, 1), xy * 2 + ivec2(1, 1));
        float d[6];
        d[0] = bayerDiff(xy * 2 - flowGreen, xy * 2);
        d[1] = bayerDiff(xy * 2 - flowGreen + ivec2(1,1), xy * 2 + ivec2(1,1));
        d[2] = bayerDiff(xy * 2 - flowGreen + ivec2(1,-1), xy * 2 + ivec2(1,-1));

        d[3] = bayerDiff(xy * 2 - flowGreen + ivec2(1,1), xy * 2 + ivec2(1,1));
        d[4] = bayerDiff(xy * 2 - flowGreen + ivec2(1,1) + ivec2(1,1), xy * 2 + ivec2(1,1) + ivec2(1,1));
        d[5] = bayerDiff(xy * 2 - flowGreen + ivec2(1,1) + ivec2(-1,1), xy * 2 + ivec2(1,1) + ivec2(-1,1));
        // interpolate using f
        float dx0 = mix(d[0],d[1],f.x);
        float dy0 = mix(dx0,d[2],f.x);
        float dx1 = mix(d[3],d[4],f.x);
        float dy1 = mix(dx1,d[5],f.x);
        diff[0] = dy0;
        diff[3] = dy1;
    }
    // Apply linear flow to current frame
    //vec4 diff = interpolateBayer(vec2(xy)-vec2(flow))-getBayerVec(xy*2,inTexture);

    //vec4 diff = imageLoad(diffTexture, xy)-getBayerVec(xy*2,inTexture);
    //vec4 diff = texture(diffTexture, uv+flow*uvSize)-getBayerVec(xy*2,inTexture);
    imageStore(outTexture, xy, diff);
}
