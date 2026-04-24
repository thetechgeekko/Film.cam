package com.thetechgeekko.filmcam.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault

/**
 * Film emulation categories matching mood.camera organization
 */
@Serializable
enum class EmulationCategory {
    FILM,
    MONOCHROME,
    ARTISTIC
}

/**
 * Dynamic range settings
 */
@Serializable
enum class DynamicRange {
    HIGH,      // Preserve highlights/shadows via exposure fusion
    MEDIUM,    // Slight contrast boost
    LOW        // Crush shadows, clip highlights for slide-film look
}

/**
 * Aspect ratio options
 */
@Serializable
enum class AspectRatio(val width: Int, val height: Int, val displayName: String) {
    ONE_ONE(1, 1, "1:1"),
    FOUR_THREE(4, 3, "4:3"),
    THREE_TWO(3, 2, "3:2"),
    SIXTEEN_NINE(16, 9, "16:9"),
    SCOPE(235, 100, "2.35:1")
}

/**
 * Grain texture types
 */
@Serializable
enum class GrainType(val assetName: String) {
    SUPERFINE("grain-texture-superfine.png"),
    FINE("grain-texture-fine.png"),
    MEDIUM("grain-texture-medium.png"),
    COARSE("grain-texture-coarse.png")
}

/**
 * Tone curve data class for highlights/midtones/shadows adjustments
 */
@Serializable
data class ToneCurve(
    val highlights: Float = 0f,  // -1.0 to +1.0
    val midtones: Float = 0f,
    val shadows: Float = 0f
)

/**
 * Border style options for custom frames
 */
@Serializable
enum class BorderStyle {
    NONE,
    SOLID,
    DYNAMIC,   // Extract color from image
    OFFSET,    // Polaroid-style asymmetry
    ROUND      // Circular framing with max roundness
}

/**
 * Border settings for post-process overlay
 */
@Serializable
data class BorderSettings(
    val style: BorderStyle = BorderStyle.NONE,
    val color: Int? = null,        // null = dynamic extraction
    val thickness: Float = 0.05f,  // 0.0-1.0 relative to image size
    val offset: Float = 0.0f       // 0.0-1.0 for polaroid asymmetry
)

/**
 * Default parameters for each film emulation
 */
@Serializable
data class DefaultParameters(
    val grainType: GrainType = GrainType.FINE,
    val halationEnabled: Boolean = false,
    val defaultDR: DynamicRange = DynamicRange.HIGH,
    val description: String = ""
)

/**
 * Complete parameter set for a film emulation
 */
@Serializable
data class EmulationParameters(
    val emulationStrength: Float = 1.0f,    // 0.0-1.0
    val saturation: Float = 0f,             // -1.0 to +1.0
    val contrast: Float = 0f,               // -1.0 to +1.0
    val temperature: Float = 0f,            // -1.0 to +1.0
    val tint: Float = 0f,                   // -1.0 to +1.0
    val fade: Float = 0f,                   // 0.0-1.0 (lift blacks)
    val mute: Float = 0f,                   // 0.0-1.0 (desaturate vibrant)
    val grainLevel: Float = 0.3f,           // 0.0-1.0
    val grainSize: Float = 1.0f,            // 0.5-2.0 (scale factor)
    val halation: Float = 0f,               // 0.0-1.0
    val bloom: Float = 0f,                  // 0.0-1.0
    val aberration: Float = 0f,             // 0.0-1.0
    val toneCurve: ToneCurve = ToneCurve(),
    val dynamicRange: DynamicRange = DynamicRange.HIGH
)

/**
 * Film emulation definition loaded from metadata.json
 */
@Serializable
data class FilmEmulation(
    val id: String,
    val path: String,
    val category: EmulationCategory,
    val defaults: DefaultParameters,
    val parameters: EmulationParameters,
    val displayName: String = id.replaceFirstChar { it.uppercase() }
)

/**
 * Complete film settings matching mood.camera Advanced Preset Editor
 */
@Serializable
data class FilmSettings(
    // Core capture settings
    val emulation: FilmEmulation = createDefaultEmulation(),
    val isoSim: Int = 200,
    val exposureComp: Float = 0f,
    val dynamicRange: DynamicRange = DynamicRange.HIGH,
    val aspectRatio: AspectRatio = AspectRatio.FOUR_THREE,
    val focalLengthPreset: Float = 24f, // mm equivalent
    
    // Advanced preset parameters (mood.camera parity)
    val emulationStrength: Float = 1.0f,
    val saturation: Float = 0f,
    val contrast: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val fade: Float = 0f,
    val mute: Float = 0f,
    val grainLevel: Float = 0.3f,
    val grainSize: Float = 1.0f,
    val halation: Float = 0f,
    val bloom: Float = 0f,
    val aberration: Float = 0f,
    val toneCurve: ToneCurve = ToneCurve(),
    
    // Capture modes
    val hdrEnabled: Boolean = false,
    val showGrid: Boolean = false,
    val showLevel: Boolean = false,
    val saveRaw: Boolean = false,  // Hidden "Pro Mode"
    
    // Post-process
    val customBorder: BorderSettings? = null
) {
    companion object {
        fun createDefaultEmulation(): FilmEmulation {
            return FilmEmulation(
                id = "portra",
                path = "cluts/film/portra.png",
                category = EmulationCategory.FILM,
                defaults = DefaultParameters(description = "Soft, airy tones"),
                parameters = EmulationParameters()
            )
        }
        
        /**
         * Calculate ISO factor for grain scaling
         * ISO 100 = 1.0, ISO 3200 = 5.3
         */
        fun calculateIsoFactor(iso: Int): Float {
            return when {
                iso <= 100 -> 1.0f
                iso >= 3200 -> 5.3f
                else -> {
                    // Linear interpolation between ISO 100 and 3200
                    val normalizedIso = (iso - 100).toFloat() / (3200 - 100)
                    1.0f + normalizedIso * (5.3f - 1.0f)
                }
            }
        }
        
        /**
         * Get CLUT level from texture dimensions
         * 512px = 8.0, 1024px = 16.0, 2048px = 32.0
         */
        fun getClutLevel(textureWidth: Int): Float {
            return when (textureWidth) {
                512 -> 8.0f
                1024 -> 16.0f
                2048 -> 32.0f
                else -> 8.0f  // Default to smallest
            }
        }
    }
}
