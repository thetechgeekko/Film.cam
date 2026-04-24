package com.thetechgeekko.filmcam.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.filmcam.model.FilmSettings
import com.filmcam.model.FilmEmulation
import com.filmcam.model.DynamicRange
import com.filmcam.model.AspectRatio
import com.filmcam.model.ToneCurve
import com.filmcam.model.BorderSettings
import com.filmcam.model.BorderStyle
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistent settings manager for Film.cam
 * Handles saving/loading FilmSettings, custom presets, and app preferences
 */
class SettingsManager(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "filmcam_settings"
        private const val KEY_CURRENT_SETTINGS = "current_settings"
        private const val KEY_CUSTOM_PRESETS = "custom_presets"
        private const val KEY_LAST_EMULATION = "last_emulation"
        private const val KEY_HDRX_ENABLED = "drs_enabled"
        private const val KEY_SHOW_GRID = "show_grid"
        private const val KEY_SHOW_LEVEL = "show_level"
        
        private val JSON_FORMAT = Json { 
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * Save current FilmSettings as the active configuration
     */
    fun saveCurrentSettings(settings: FilmSettings) {
        prefs.edit {
            putString(KEY_CURRENT_SETTINGS, JSON_FORMAT.encodeToString(settings))
            putString(KEY_LAST_EMULATION, settings.emulation.id)
            putBoolean(KEY_HDRX_ENABLED, settings.hdrEnabled)
            putBoolean(KEY_SHOW_GRID, settings.showGrid)
            putBoolean(KEY_SHOW_LEVEL, settings.showLevel)
        }
    }
    
    /**
     * Load current FilmSettings from persistent storage
     */
    fun loadCurrentSettings(): FilmSettings {
        val json = prefs.getString(KEY_CURRENT_SETTINGS, null) ?: return FilmSettings()
        
        return try {
            JSON_FORMAT.decodeFromString<FilmSettings>(json)
        } catch (e: Exception) {
            FilmSettings() // Fallback to defaults on parse error
        }
    }
    
    /**
     * Save a custom preset with a user-defined name
     */
    fun saveCustomPreset(name: String, settings: FilmSettings) {
        val presets = loadCustomPresets().toMutableMap()
        presets[name] = settings
        
        prefs.edit {
            putString(KEY_CUSTOM_PRESETS, JSON_FORMAT.encodeToString(presets))
        }
    }
    
    /**
     * Load all custom presets
     */
    fun loadCustomPresets(): Map<String, FilmSettings> {
        val json = prefs.getString(KEY_CUSTOM_PRESETS, null) ?: return emptyMap()
        
        return try {
            JSON_FORMAT.decodeFromString<Map<String, FilmSettings>>(json)
        } catch (e: Exception) {
            emptyMap()
        }
    }
    
    /**
     * Delete a custom preset by name
     */
    fun deleteCustomPreset(name: String) {
        val presets = loadCustomPresets().toMutableMap()
        presets.remove(name)
        
        prefs.edit {
            putString(KEY_CUSTOM_PRESETS, JSON_FORMAT.encodeToString(presets))
        }
    }
    
    /**
     * Get last used emulation ID
     */
    fun getLastEmulationId(): String? {
        return prefs.getString(KEY_LAST_EMULATION, null)
    }
    
    /**
     * Check if DRS is enabled by default
     */
    fun isDRSEnabled(): Boolean {
        return prefs.getBoolean(KEY_HDRX_ENABLED, false)
    }
    
    /**
     * Set DRS enabled state
     */
    fun setDRSEnabled(enabled: Boolean) {
        prefs.edit {
            putBoolean(KEY_HDRX_ENABLED, enabled)
        }
    }
    
    /**
     * Check if grid overlay should be shown
     */
    fun shouldShowGrid(): Boolean {
        return prefs.getBoolean(KEY_SHOW_GRID, false)
    }
    
    /**
     * Set grid overlay visibility
     */
    fun setShowGrid(show: Boolean) {
        prefs.edit {
            putBoolean(KEY_SHOW_GRID, show)
        }
    }
    
    /**
     * Check if level indicator should be shown
     */
    fun shouldShowLevel(): Boolean {
        return prefs.getBoolean(KEY_SHOW_LEVEL, false)
    }
    
    /**
     * Set level indicator visibility
     */
    fun setShowLevel(show: Boolean) {
        prefs.edit {
            putBoolean(KEY_SHOW_LEVEL, show)
        }
    }
    
    /**
     * Export custom preset to JSON string for sharing
     */
    fun exportPresetToJson(settings: FilmSettings): String {
        return JSON_FORMAT.encodeToString(settings)
    }
    
    /**
     * Import custom preset from JSON string
     */
    fun importPresetFromJson(json: String): FilmSettings? {
        return try {
            JSON_FORMAT.decodeFromString<FilmSettings>(json)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Import and save custom preset with auto-generated name
     */
    fun importPreset(json: String, name: String): Boolean {
        val settings = importPresetFromJson(json) ?: return false
        saveCustomPreset(name, settings)
        return true
    }
    
    /**
     * Clear all custom presets
     */
    fun clearAllPresets() {
        prefs.edit {
            remove(KEY_CUSTOM_PRESETS)
        }
    }
    
    /**
     * Reset to factory defaults
     */
    fun resetToDefaults() {
        prefs.edit {
            clear()
        }
    }
}
