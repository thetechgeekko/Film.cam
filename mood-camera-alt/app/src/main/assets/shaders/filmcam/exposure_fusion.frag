#version 300 es
precision highp float;

// HDRx Exposure Fusion Shader
// Blends multiple exposures using luminance-weighted blending
// No tone mapping applied - preserves full dynamic range

uniform sampler2D uUnderexposed;  // -1.5 EV (highlight detail)
uniform sampler2D uNormalExposed; // 0 EV (midtones)
uniform sampler2D uOverexposed;   // +1.5 EV (shadow detail)

uniform float uMotionMask;        // 0.0 = no motion, 1.0 = full motion (fallback to single frame)
uniform vec2 uPixelSize;          // 1.0 / texture dimensions

out vec4 fragColor;
in vec2 vTexCoord;

const vec3 LUMINANCE_WEIGHTS = vec3(0.299, 0.587, 0.114);

float getLuminance(vec3 color) {
    return dot(color, LUMINANCE_WEIGHTS);
}

// Calculate luminance-based weights for each exposure
vec3 calculateWeights(float lum) {
    // Normalize luminance to [0, 1]
    lum = clamp(lum, 0.0, 1.0);
    
    // Underexposed weight: favors highlights (bright areas)
    // High luminance = use underexposed to preserve highlight detail
    float wUnder = smoothstep(0.4, 0.9, lum);
    
    // Overexposed weight: favors shadows (dark areas)
    // Low luminance = use overexposed to recover shadow detail
    float wOver = smoothstep(0.6, 0.1, lum);
    
    // Normal exposed weight: favors midtones
    float wNormal = smoothstep(0.2, 0.5, lum) * smoothstep(0.8, 0.5, lum);
    
    // Ensure weights sum to 1.0
    float totalWeight = wUnder + wNormal + wOver + 0.001;
    
    return vec3(wUnder / totalWeight, wNormal / totalWeight, wOver / totalWeight);
}

void main() {
    // Sample all three exposures
    vec3 under = texture(uUnderexposed, vTexCoord).rgb;
    vec3 normal = texture(uNormalExposed, vTexCoord).rgb;
    vec3 over = texture(uOverexposed, vTexCoord).rgb;
    
    // Calculate luminance from normal exposure for weight calculation
    float lum = getLuminance(normal);
    
    // Get fusion weights based on luminance
    vec3 weights = calculateWeights(lum);
    
    // Apply motion mask: if motion detected, blend toward normal exposure only
    // This prevents ghosting artifacts
    weights = mix(weights, vec3(0.0, 1.0, 0.0), uMotionMask);
    
    // Re-normalize after motion mask
    float totalWeight = weights.r + weights.g + weights.b + 0.001;
    weights /= totalWeight;
    
    // Fuse exposures
    vec3 fused = under * weights.r + normal * weights.g + over * weights.b;
    
    // Output in linear space (no tone mapping)
    fragColor = vec4(fused, 1.0);
}
