package com.thetechgeekko.filmcam.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.thetechgeekko.filmcam.model.*
import org.json.JSONObject
import java.io.File

/**
 * Loads and validates film emulation CLUTs from metadata.json
 */
class ClutLoader(private val context: Context) {
    
    private val emulations = mutableListOf<FilmEmulation>()
    private val loadedBitmaps = mutableMapOf<String, Bitmap>()
    
    companion object {
        private const val METADATA_FILE = "filmcam/metadata.json"
        private const val MAX_CACHED_CLUTS = 3
        
        /**
         * Validate CLUT dimensions
         * Must be square power-of-2 (512/1024/2048)
         */
        fun validateClutDimensions(bitmap: Bitmap): Boolean {
            val width = bitmap.width
            val height = bitmap.height
            
            // Must be square
            if (width != height) return false
            
            // Must be power of 2
            if (width and (width - 1) != 0) return false
            
            // Must be valid HALD size (512, 1024, or 2048)
            return width in listOf(512, 1024, 2048)
        }
        
        /**
         * Get HALD level from texture width
         */
        fun getHaldLevel(width: Int): Float {
            return when (width) {
                512 -> 8.0f
                1024 -> 16.0f
                2048 -> 32.0f
                else -> 8.0f
            }
        }
    }
    
    /**
     * Load all emulations from metadata.json
     */
    fun loadEmulations(): List<FilmEmulation> {
        if (emulations.isNotEmpty()) return emulations
        
        try {
            val jsonString = context.assets.open(METADATA_FILE).use { inputStream ->
                inputStream.bufferedReader().use { it.readText() }
            }
            
            val json = JSONObject(jsonString)
            val emulationsArray = json.getJSONArray("emulations")
            
            for (i in 0 until emulationsArray.length()) {
                val emulJson = emulationsArray.getJSONObject(i)
                
                val id = emulJson.getString("id")
                val path = emulJson.getString("path")
                val categoryStr = emulJson.getString("category")
                val defaultsJson = emulJson.getJSONObject("defaults")
                val paramsJson = emulJson.getJSONObject("parameters")
                
                val category = EmulationCategory.valueOf(categoryStr.uppercase())
                
                val defaults = DefaultParameters(
                    grainType = GrainType.valueOf(
                        defaultsJson.getString("grainType").uppercase()
                    ),
                    halationEnabled = defaultsJson.getBoolean("halationEnabled"),
                    defaultDR = DynamicRange.valueOf(
                        defaultsJson.getString("defaultDR").uppercase()
                    ),
                    description = defaultsJson.optString("description", "")
                )
                
                val toneCurveJson = paramsJson.getJSONObject("toneCurve")
                val toneCurve = ToneCurve(
                    highlights = toneCurveJson.getDouble("high").toFloat(),
                    midtones = toneCurveJson.getDouble("mid").toFloat(),
                    shadows = toneCurveJson.getDouble("shadow").toFloat()
                )
                
                val parameters = EmulationParameters(
                    emulationStrength = paramsJson.getDouble("emulationStrength").toFloat(),
                    saturation = paramsJson.getDouble("saturation").toFloat(),
                    contrast = paramsJson.getDouble("contrast").toFloat(),
                    temperature = paramsJson.getDouble("temperature").toFloat(),
                    tint = paramsJson.getDouble("tint").toFloat(),
                    fade = paramsJson.getDouble("fade").toFloat(),
                    mute = paramsJson.getDouble("mute").toFloat(),
                    grainLevel = paramsJson.getDouble("grainLevel").toFloat(),
                    grainSize = paramsJson.getDouble("grainSize").toFloat(),
                    halation = paramsJson.getDouble("halation").toFloat(),
                    bloom = paramsJson.getDouble("bloom").toFloat(),
                    aberration = paramsJson.getDouble("aberration").toFloat(),
                    toneCurve = toneCurve,
                    dynamicRange = DynamicRange.valueOf(
                        paramsJson.getString("dynamicRange").uppercase()
                    )
                )
                
                val emulation = FilmEmulation(
                    id = id,
                    path = path,
                    category = category,
                    defaults = defaults,
                    parameters = parameters
                )
                
                emulations.add(emulation)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Return empty list on error
        }
        
        return emulations
    }
    
    /**
     * Load a CLUT bitmap with caching
     */
    fun loadClut(path: String): Bitmap? {
        // Check cache first
        loadedBitmaps[path]?.let { return it }
        
        try {
            val assetManager = context.assets
            val inputStream = assetManager.open("filmcam/$path")
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            
            // Validate dimensions
            if (!validateClutDimensions(bitmap)) {
                bitmap.recycle()
                throw IllegalArgumentException("Invalid CLUT dimensions: ${bitmap.width}x${bitmap.height}")
            }
            
            // Cache management (LRU)
            if (loadedBitmaps.size >= MAX_CACHED_CLUTS) {
                // Remove oldest entry
                val oldestKey = loadedBitmaps.keys.first()
                loadedBitmaps.remove(oldestKey)?.recycle()
            }
            
            loadedBitmaps[path] = bitmap
            return bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    
    /**
     * Load grain texture
     */
    fun loadGrainTexture(grainType: GrainType): Bitmap? {
        val path = "filmcam/grains/${grainType.assetName}"
        
        return try {
            val inputStream = context.assets.open(path)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Load skin tones CLUT
     */
    fun loadSkinTonesClut(): Bitmap? {
        return loadClut("cluts/skinTones.png")
    }
    
    /**
     * Get emulation by ID
     */
    fun getEmulationById(id: String): FilmEmulation? {
        return emulations.find { it.id == id }
    }
    
    /**
     * Get emulations by category
     */
    fun getEmulationsByCategory(category: EmulationCategory): List<FilmEmulation> {
        return emulations.filter { it.category == category }
    }
    
    /**
     * Clear cached bitmaps
     */
    fun clearCache() {
        loadedBitmaps.values.forEach { it.recycle() }
        loadedBitmaps.clear()
    }
    
    /**
     * Get CLUT file for validation
     */
    fun getClutFile(path: String): File {
        return File(context.filesDir, "filmcam/$path")
    }
}
