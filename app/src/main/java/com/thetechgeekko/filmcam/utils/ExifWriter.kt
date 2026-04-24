package com.thetechgeekko.filmcam.utils

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import com.thetechgeekko.filmcam.model.FilmSettings
import java.io.File

/**
 * EXIF writer for Film.cam
 * Writes film emulation name to EXIF UserComment for library searchability
 */
class ExifWriter(private val context: Context) {
    
    companion object {
        private const val TAG = "FilmCamExif"
        
        // EXIF tags we'll write
        private const val TAG_USER_COMMENT = "UserComment"
        private const val TAG_IMAGE_DESCRIPTION = "ImageDescription"
        private const val TAG_SOFTWARE = "Software"
        private const val TAG_MAKER_NOTE = "MakerNote"
    }
    
    /**
     * Write film emulation metadata to JPEG file
     */
    fun writeEmulationMetadata(
        file: File,
        settings: FilmSettings,
        orientation: Int = ExifInterface.ORIENTATION_NORMAL
    ): Boolean {
        return try {
            val exif = ExifInterface(file.path)
            
            // Write emulation name to UserComment (mood.camera parity)
            val emulationName = settings.emulation.displayName
            exif.setAttribute(TAG_USER_COMMENT, "Film.cam: $emulationName")
            
            // Write image description with key parameters
            val description = buildString {
                append("Film.cam | ${settings.emulation.id}")
                append(" | ISO${settings.isoSim}")
                append(" | EV${settings.exposureComp}")
                if (settings.hdrEnabled) append(" | DRS")
            }
            exif.setAttribute(TAG_IMAGE_DESCRIPTION, description)
            
            // Write software identifier
            exif.setAttribute(TAG_SOFTWARE, "Film.cam v1.0")
            
            // Write orientation
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            
            // Optional: Write custom maker note with JSON-encoded settings
            // This allows full preset recovery later
            val presetJson = createPresetJson(settings)
            exif.setAttribute(TAG_MAKER_NOTE, presetJson)
            
            // Save changes
            exif.saveAttributes()
            
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * Create JSON string of preset parameters for MakerNote
     */
    private fun createPresetJson(settings: FilmSettings): String {
        return buildString {
            append("{")
            append("\"emulation\":\"${settings.emulation.id}\"")
            append(",\"strength\":${settings.emulationStrength}")
            append(",\"saturation\":${settings.saturation}")
            append(",\"contrast\":${settings.contrast}")
            append(",\"temperature\":${settings.temperature}")
            append(",\"tint\":${settings.tint}")
            append(",\"fade\":${settings.fade}")
            append(",\"mute\":${settings.mute}")
            append(",\"grainLevel\":${settings.grainLevel}")
            append(",\"grainSize\":${settings.grainSize}")
            append(",\"halation\":${settings.halation}")
            append(",\"bloom\":${settings.bloom}")
            append(",\"aberration\":${settings.aberration}")
            append(",\"toneCurve\":{\"h\":${settings.toneCurve.highlights},\"m\":${settings.toneCurve.midtones},\"s\":${settings.toneCurve.shadows}}")
            append(",\"dynamicRange\":\"${settings.dynamicRange.name}\"")
            append(",\"hdr\":${settings.hdrEnabled}")
            append("}")
        }
    }
    
    /**
     * Read emulation name from existing JPEG
     */
    fun readEmulationName(file: File): String? {
        return try {
            val exif = ExifInterface(file.path)
            val userComment = exif.getAttribute(TAG_USER_COMMENT)
            
            // Parse "Film.cam: Portra" format
            if (userComment?.startsWith("Film.cam:") == true) {
                userComment.substringAfter("Film.cam:").trim()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Read full preset from MakerNote JSON
     */
    fun readPreset(file: File): String? {
        return try {
            val exif = ExifInterface(file.path)
            exif.getAttribute(TAG_MAKER_NOTE)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Batch write metadata to multiple files
     */
    fun writeBatchMetadata(
        files: List<File>,
        settings: FilmSettings,
        orientation: Int = ExifInterface.ORIENTATION_NORMAL
    ): Int {
        var successCount = 0
        
        files.forEach { file ->
            if (writeEmulationMetadata(file, settings, orientation)) {
                successCount++
            }
        }
        
        return successCount
    }
}
