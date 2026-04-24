#version 300 es
precision highp float;

// Input texture (linear RGB 32f)
uniform sampler2D uInputImage;
// HALD CLUT texture
uniform sampler2D uHaldClut;
// Grain texture (tileable, grayscale)
uniform sampler2D uGrainTexture;
// Optional skin tones CLUT
uniform sampler2D uSkinClut;

// CLUT level (8.0, 16.0, or 32.0 for 512/1024/2048 CLUTs)
uniform float uClutLevel;

// Emulation controls
uniform float uEmulationStrength;   // 0.0-1.0
uniform float uSaturation;          // -1.0 to +1.0
uniform float uContrast;            // -1.0 to +1.0
uniform float uTemperature;         // -1.0 to +1.0
uniform float uTint;                // -1.0 to +1.0
uniform float uFade;                // 0.0-1.0 (lift blacks)
uniform float uMute;                // 0.0-1.0 (desaturate vibrant areas)

// Physical textures
uniform float uGrainLevel;          // 0.0-1.0
uniform float uGrainSize;           // 0.5-2.0 (texture scale)
uniform float uHalation;            // 0.0-1.0
uniform float uBloom;               // 0.0-1.0
uniform float uAberration;          // 0.0-1.0

// Tone curve (highlights, midtones, shadows)
uniform vec3 uToneCurve;

// Exposure and dynamic range
uniform float uExposureComp;        // -3.0 to +3.0 EV
uniform float uIsoFactor;           // 1.0 (ISO100) → 5.3 (ISO3200)
uniform int uDynamicRange;          // 0=High, 1=Medium, 2=Low

// Special modes
uniform bool uApplySkinTones;       // Enable skin tone preservation blend
uniform float uSkinMaskThreshold;   // Luminance threshold for skin detection

// Output
out vec4 fragColor;

in vec2 vTexCoord;

// Constants
const float PI = 3.14159265359;
const vec3 LUMINANCE_WEIGHTS = vec3(0.299, 0.587, 0.114);
const vec3 WARM_TINT = vec3(1.15, 0.92, 0.85);

// ============================================================================
// Utility Functions
// ============================================================================

float getLuminance(vec3 color) {
    return dot(color, LUMINANCE_WEIGHTS);
}

vec3 rgbToHsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

vec3 hsvToRgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

// ============================================================================
// HALD CLUT Application (3D LUT via 2D texture)
// ============================================================================

vec3 applyHaldClut(vec3 color, sampler2D clut, float level) {
    // Clamp input color to [0,1]
    color = clamp(color, 0.0, 1.0);
    
    // Calculate CLUT dimensions
    float clutSize = level * level;  // e.g., 8*8=64 for 512px CLUT
    float cellSize = 1.0 / clutSize;
    
    // Scale color by CLUT level
    vec3 scaledColor = color * (level - 1.0);
    
    // Get integer coordinates
    vec3 id = floor(scaledColor);
    vec3 fracPos = fract(scaledColor);
    
    // Calculate 2D UV coordinates in the HALD CLUT
    // HALD layout: each slice is level×level, arranged in a level×level grid
    
    // Bounding box IDs for trilinear interpolation
    vec3 id0 = id;
    vec3 id1 = min(id + 1.0, level - 1.0);
    
    // Function to convert 3D CLUT coordinate to 2D UV
    auto haldCoord = vec3(float(level), float(level * level), 1.0 / float(level * level));
    
    vec2 uv0 = vec2(
        (id0.x + id0.z * level) / (level * level),
        id0.y / level
    ) + vec2(cellSize * 0.5);
    
    vec2 uv1 = vec2(
        (id1.x + id1.z * level) / (level * level),
        id1.y / level
    ) + vec2(cellSize * 0.5);
    
    // Sample CLUT at bounding points
    vec3 c000 = texture(clut, vec3(uv0.x, uv0.y, 0.0)).rgb;
    vec3 c100 = texture(clut, vec3(uv1.x, uv0.y, 0.0)).rgb;
    
    // Trilinear interpolation weight
    float w = fracPos.x;
    
    // Simple bilinear interpolation (simplified for performance)
    vec3 result = mix(c000, c100, w);
    
    return result;
}

// Simplified CLUT lookup (bilinear, single sample per channel)
vec3 applyHaldClutSimple(vec3 color, sampler2D clut, float level) {
    color = clamp(color, 0.0, 1.0);
    
    float clutSize = level * level;
    float cellSize = 1.0 / clutSize;
    
    // Scale and add 0.5 for center sampling
    vec3 scaledColor = color * (level - 1.0) + 0.5;
    
    // Convert 3D position to 2D UV
    float z = floor(scaledColor.z);
    float x = scaledColor.x + z * level;
    float y = scaledColor.y;
    
    vec2 uv = vec2(x / (level * level), y / level);
    
    return texture(clut, uv).rgb;
}

// ============================================================================
// Color Adjustments
// ============================================================================

vec3 adjustTemperature(vec3 color, float temp) {
    // Temperature: negative = cooler (blue), positive = warmer (orange)
    vec3 warmColor = vec3(1.0, 0.93, 0.85);
    vec3 coolColor = vec3(0.85, 0.93, 1.0);
    
    if (temp > 0.0) {
        return mix(color, color * warmColor, temp * 0.3);
    } else {
        return mix(color, color * coolColor, -temp * 0.3);
    }
}

vec3 adjustTint(vec3 color, float tint) {
    // Tint: negative = green, positive = magenta
    if (tint > 0.0) {
        color.r += tint * 0.15;
        color.b += tint * 0.05;
    } else {
        color.g -= tint * 0.15;
    }
    return clamp(color, 0.0, 1.0);
}

vec3 adjustSaturation(vec3 color, float saturation) {
    float lum = getLuminance(color);
    vec3 gray = vec3(lum);
    return mix(gray, color, 1.0 + saturation);
}

vec3 adjustContrast(vec3 color, float contrast) {
    // Contrast adjustment around midpoint 0.5
    float factor = (1.0 + contrast) / (1.0 - contrast);
    if (factor < 0.0) factor = 0.0;
    
    vec3 adjusted = (color - 0.5) * factor + 0.5;
    return clamp(adjusted, 0.0, 1.0);
}

vec3 applyFade(vec3 color, float fade) {
    // Fade: lift blacks toward gray
    vec3 faded = color + vec3(fade * 0.15);
    return clamp(faded, 0.0, 1.0);
}

vec3 applyMute(vec3 color, float mute) {
    // Mute: desaturate vibrant areas more than dull areas
    float sat = getLuminance(color) > 0.5 ? 1.0 : 0.5;
    float lum = getLuminance(color);
    float vibrancy = smoothstep(0.3, 0.7, lum) * (1.0 - abs(lum - 0.5) * 2.0);
    
    vec3 hsv = rgbToHsv(color);
    hsv.y *= (1.0 - mute * vibrancy * 0.5);
    return hsvToRgb(hsv);
}

// ============================================================================
// Tone Curve (Highlights/Midtones/Shadows)
// ============================================================================

vec3 applyToneCurve(vec3 color, vec3 curve) {
    // curve.r = highlights adjustment (-1.0 to +1.0)
    // curve.g = midtones adjustment
    // curve.b = shadows adjustment
    
    vec3 result = color;
    
    // Shadows (dark areas)
    float shadowWeight = smoothstep(0.0, 0.3, 1.0 - getLuminance(color));
    result += curve.b * shadowWeight * 0.15;
    
    // Midtones
    float midWeight = smoothstep(0.2, 0.5, getLuminance(color)) * 
                      smoothstep(0.8, 0.5, getLuminance(color));
    result += curve.g * midWeight * 0.15;
    
    // Highlights (bright areas)
    float highlightWeight = smoothstep(0.7, 1.0, getLuminance(color));
    result += curve.r * highlightWeight * 0.15;
    
    return clamp(result, 0.0, 1.0);
}

// ============================================================================
// Halation Effect (highlight bloom with chromatic offset)
// ============================================================================

vec3 applyHalation(vec3 color, float halationStrength, vec2 texCoord, sampler2D inputTex) {
    if (halationStrength <= 0.0) return color;
    
    float luminance = getLuminance(color);
    
    // Only affect highlights above threshold
    float highlightMask = smoothstep(0.85, 1.0, luminance);
    
    if (highlightMask < 0.01) return color;
    
    // Chromatic offset for halation (R and B channels shift differently)
    float offset = 0.003 * halationStrength;
    
    // Sample neighboring pixels with chromatic offset
    vec3 halationColor = vec3(
        texture(inputTex, texCoord + vec2(offset, 0.0)).r,
        texture(inputTex, texCoord).g,
        texture(inputTex, texCoord - vec2(offset, 0.0)).b
    );
    
    // Apply warm tint to halation
    halationColor *= WARM_TINT;
    
    // Radial blur simulation (simplified)
    vec2 centerDir = texCoord - 0.5;
    float radialDist = length(centerDir);
    vec2 radialOffset = normalize(centerDir) * offset * radialDist * 2.0;
    
    vec3 radialBlur = vec3(
        texture(inputTex, texCoord + radialOffset * 1.2).r,
        texture(inputTex, texCoord).g,
        texture(inputTex, texCoord - radialOffset * 0.8).b
    );
    
    halationColor = mix(halationColor, radialBlur, 0.5);
    
    // Additive blend at low opacity
    vec3 halationResult = color + halationColor * highlightMask * halationStrength * 0.15;
    
    return clamp(halationResult, 0.0, 1.0);
}

// ============================================================================
// Bloom Diffusion (soft Gaussian-like blur on highlights)
// ============================================================================

vec3 applyBloom(vec3 color, float bloomStrength, vec2 texCoord, sampler2D inputTex) {
    if (bloomStrength <= 0.0) return color;
    
    float luminance = getLuminance(color);
    float highlightMask = smoothstep(0.7, 1.0, luminance);
    
    if (highlightMask < 0.01) return color;
    
    // Simple box blur for diffusion effect
    vec2 pixelSize = vec2(1.0 / 2048.0, 1.0 / 2048.0); // Approximate, will be overridden
    float blurRadius = 2.0 * bloomStrength;
    
    vec3 blurSum = vec3(0.0);
    float totalWeight = 0.0;
    
    for (float x = -blurRadius; x <= blurRadius; x++) {
        for (float y = -blurRadius; y <= blurRadius; y++) {
            float weight = 1.0 - length(vec2(x, y)) / (blurRadius * 1.5);
            weight = max(weight, 0.0);
            blurSum += texture(inputTex, texCoord + vec2(x, y) * pixelSize).rgb * weight;
            totalWeight += weight;
        }
    }
    
    vec3 blurred = blurSum / max(totalWeight, 1.0);
    
    // Additive blend
    vec3 bloomResult = color + blurred * highlightMask * bloomStrength * 0.1;
    
    return clamp(bloomResult, 0.0, 1.0);
}

// ============================================================================
// Chromatic Aberration (color fringing at edges)
// ============================================================================

vec3 applyChromaticAberration(vec3 color, float aberrationStrength, vec2 texCoord, sampler2D inputTex) {
    if (aberrationStrength <= 0.0) return color;
    
    // Distance from center
    vec2 centerDir = texCoord - 0.5;
    float dist = length(centerDir) * 2.0;
    
    // Radial shift amount
    float shift = aberrationStrength * 0.002 * dist;
    
    vec2 direction = normalize(centerDir);
    
    // Shift R and B channels in opposite directions
    vec3 aberrationColor = vec3(
        texture(inputTex, texCoord - direction * shift).r,
        color.g,
        texture(inputTex, texCoord + direction * shift).b
    );
    
    return clamp(aberrationColor, 0.0, 1.0);
}

// ============================================================================
// Film Grain (luminance-aware masking)
// ============================================================================

vec3 applyFilmGrain(vec3 color, float grainLevel, float grainSize, vec2 texCoord, 
                    sampler2D grainTex, float isoFactor) {
    if (grainLevel <= 0.0) return color;
    
    // Get luminance for masking
    float luminance = getLuminance(color);
    
    // Luminance mask: reduce grain in extreme shadows and highlights
    float grainMask = smoothstep(0.15, 0.85, luminance);
    grainMask = 0.5 + grainMask * 0.5; // Range: 0.5 to 1.0
    
    // ISO-linked scaling
    float effectiveGrainLevel = grainLevel * (1.0 + (isoFactor - 1.0) * 0.3);
    
    // Sample grain texture with size scaling
    vec2 grainUv = texCoord * grainSize;
    vec4 grainSample = texture(grainTex, grainUv);
    
    // Grain is stored as RGBA, use alpha or luminance
    float grainValue = grainSample.a > 0.0 ? grainSample.a : getLuminance(grainSample.rgb);
    
    // Center grain around 0.5
    grainValue = (grainValue - 0.5) * 2.0;
    
    // Apply grain
    vec3 grainNoise = vec3(grainValue) * effectiveGrainLevel * grainMask * 0.5;
    
    return clamp(color + grainNoise, 0.0, 1.0);
}

// ============================================================================
// Skin Tone Preservation (optional blend)
// ============================================================================

vec3 applySkinTonePreservation(vec3 color, vec3 clutColor, float skinThreshold) {
    // Detect skin tones via luminance and hue
    vec3 hsv = rgbToHsv(color);
    
    // Skin tone hue range: roughly 0.05 to 0.15 (orange/yellow)
    float skinHue = (hsv.x > 0.03 && hsv.x < 0.15) ? 1.0 : 0.0;
    float skinSat = smoothstep(0.1, 0.6, hsv.y);
    float skinLum = smoothstep(0.2, 0.8, hsv.z) * smoothstep(0.8, 0.4, hsv.z);
    
    float skinMask = skinHue * skinSat * skinLum;
    
    // Blend between original CLUT result and skin-preserving CLUT
    return mix(clutColor, color, skinMask * 0.5);
}

// ============================================================================
// Dynamic Range Adjustment
// ============================================================================

vec3 applyDynamicRange(vec3 color, int drMode) {
    // drMode: 0=High (preserve DR), 1=Medium, 2=Low (crush shadows, clip highlights)
    
    if (drMode == 0) {
        // High DR: minimal change, preserve highlights/shadows
        return color;
    } else if (drMode == 1) {
        // Medium DR: slight contrast boost
        return adjustContrast(color, 0.05);
    } else {
        // Low DR: crush shadows, clip highlights for slide-film look
        vec3 result = adjustContrast(color, 0.15);
        result = pow(result, vec3(1.1)); // Slight gamma adjustment
        return clamp(result, 0.0, 1.0);
    }
}

// ============================================================================
// Linear to sRGB Conversion
// ============================================================================

vec3 linearToSrgb(vec3 linear) {
    vec3 cutoff = vec3(0.0031308);
    vec3 below = linear * 12.92;
    vec3 above = 1.055 * pow(linear, vec3(1.0 / 2.4)) - 0.055;
    return mix(above, below, lessThan(linear, cutoff));
}

// ============================================================================
// Main Processing Pipeline
// ============================================================================

void main() {
    // Step 1: Sample input texture (already in linear space)
    vec3 color = texture(uInputImage, vTexCoord).rgb;
    
    // Step 2: Apply exposure compensation
    color *= pow(2.0, uExposureComp);
    
    // Step 3: Apply HALD CLUT (film emulation)
    vec3 clutColor = applyHaldClutSimple(color, uHaldClut, uClutLevel);
    
    // Blend CLUT result with original based on strength
    color = mix(color, clutColor, uEmulationStrength);
    
    // Optional: Skin tone preservation
    if (uApplySkinTones) {
        color = applySkinTonePreservation(color, clutColor, uSkinMaskThreshold);
    }
    
    // Step 4: Color science adjustments (in linear space)
    color = adjustTemperature(color, uTemperature);
    color = adjustTint(color, uTint);
    color = adjustSaturation(color, uSaturation);
    color = adjustContrast(color, uContrast);
    color = applyFade(color, uFade);
    color = applyMute(color, uMute);
    
    // Step 5: Tone curve
    color = applyToneCurve(color, uToneCurve);
    
    // Step 6: Dynamic range adjustment
    color = applyDynamicRange(color, uDynamicRange);
    
    // Step 7: Halation pass (highlights only)
    color = applyHalation(color, uHalation, vTexCoord, uInputImage);
    
    // Step 8: Bloom diffusion
    color = applyBloom(color, uBloom, vTexCoord, uInputImage);
    
    // Step 9: Chromatic aberration
    color = applyChromaticAberration(color, uAberration, vTexCoord, uInputImage);
    
    // Step 10: Convert to sRGB BEFORE grain (grain should be in display space)
    color = linearToSrgb(color);
    
    // Step 11: Film grain (applied LAST, in sRGB space)
    color = applyFilmGrain(color, uGrainLevel, uGrainSize, vTexCoord, uGrainTexture, uIsoFactor);
    
    // Final clamp
    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
