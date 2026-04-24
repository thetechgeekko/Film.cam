package com.thetechgeekko.filmcam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import com.thetechgeekko.filmcam.gpu.GlTextureLoader
import com.thetechgeekko.filmcam.capture.CaptureController
import com.thetechgeekko.filmcam.model.FilmSettings
import com.thetechgeekko.filmcam.pipeline.FilmProcessor
import com.thetechgeekko.filmcam.settings.SettingsManager
import com.thetechgeekko.filmcam.ui.MinimalCameraScreen
import com.thetechgeekko.filmcam.utils.ClutLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Main Camera Activity for Film.cam
 * Zerocam-aligned: no live preview filters, gesture-driven UI, haptic feedback
 */
class CameraActivity : ComponentActivity() {
    
    private lateinit var captureController: CaptureController
    private lateinit var filmProcessor: FilmProcessor
    private lateinit var clutLoader: ClutLoader
    private lateinit var settingsManager: SettingsManager
    private lateinit var vibrator: Vibrator
    private lateinit var textureLoader: GlTextureLoader
    
    private var currentSettings by mutableStateOf(FilmSettings())
    private var isDRSEnabled by mutableStateOf(false)
    private var isProcessing by mutableStateOf(false)
    private var showDevelopingAnimation by mutableStateOf(false)
    
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            initializeCamera()
        } else {
            // Handle permission denied
            finish()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize components
        val cameraManager = getSystemService<CameraManager>() ?: run {
            finish()
            return
        }
        
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService<VibratorManager>()
            vibratorManager?.defaultVibrator ?: getSystemService()
        } else {
            @Suppress("DEPRECATION")
            getSystemService()
        } ?: run {
            finish()
            return
        }
        
        settingsManager = SettingsManager(this)
        clutLoader = ClutLoader(this)
        textureLoader = GlTextureLoader()
        filmProcessor = FilmProcessor(this, textureLoader)
        captureController = CaptureController(this, cameraManager)
        
        // Load saved settings
        currentSettings = settingsManager.loadCurrentSettings()
        isDRSEnabled = settingsManager.isDRSEnabled()
        
        // Check and request camera permission
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                initializeCamera()
            }
            else -> {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        
        setContent {
            MinimalCameraScreen(
                filmSettings = currentSettings,
                onSettingsChange = { newSettings ->
                    currentSettings = newSettings
                    settingsManager.saveCurrentSettings(newSettings)
                    captureController.startPreview(newSettings)
                },
                onDRSToggle = {
                    val newEnabled = !isDRSEnabled
                    isDRSEnabled = newEnabled
                    settingsManager.setDRSEnabled(newEnabled)
                    provideHapticFeedback(HapticPattern.HDRX_ON)
                },
                onCapture = {
                    performCapture(currentSettings, isDRSEnabled)
                },
                availableEmulations = clutLoader.loadEmulations()
            )
        }
    }
    
    /**
     * Initialize camera after permission granted
     */
    private fun initializeCamera() {
        // Get back camera ID
        val cameraManager = getSystemService<CameraManager>() ?: return
        val cameraId = cameraManager.cameraIdList.find { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == 
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.firstOrNull() ?: return
        
        // Calculate optimal resolution (24MP ceiling)
        val targetResolution = calculateOptimalResolution()
        
        captureController.openCamera(cameraId, object : CaptureController.CameraStateCallback {
            override fun onOpened(camera: android.hardware.camera2.CameraDevice) {
                captureController.createCaptureSession(
                    jpegWidth = targetResolution.first,
                    jpegHeight = targetResolution.second,
                    saveRaw = currentSettings.saveRaw,
                    callback = object : CaptureController.SessionCallback {
                        override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                            captureController.startPreview(currentSettings)
                        }
                        
                        override fun onConfigureFailed(message: String) {
                            // Handle session config failure
                        }
                    }
                )
            }
            
            override fun onDisconnected() {
                // Handle camera disconnection
            }
            
            override fun onError(error: Int) {
                // Handle camera error
            }
        })
    }
    
    /**
     * Calculate optimal resolution based on 24MP ceiling
     */
    private fun calculateOptimalResolution(): Pair<Int, Int> {
        // Target ~6000x4000 for 24MP (3:2 aspect)
        // Or auto-bin to ~4000x3000 for 12MP in low light
        return Pair(6000, 4000)
    }
    
    /**
     * Perform capture with optional DRS
     */
    private fun performCapture(settings: FilmSettings, drs: Boolean) {
        if (isProcessing) return
        
        isProcessing = true
        showDevelopingAnimation = false
        
        provideHapticFeedback(HapticPattern.CAPTURE)
        
        lifecycleScope.launch {
            try {
                // Trigger capture
                captureController.capture(settings, drs)
                
                // Wait for image availability (simplified - actual implementation uses callbacks)
                withContext(Dispatchers.Default) {
                    // Simulate processing delay
                    kotlinx.coroutines.delay(100)
                    
                    // Process through film pipeline
                    // In actual implementation, this receives the captured file from callback
                    processCapturedImage(settings, drs)
                }
                
                // Show developing animation
                withContext(Dispatchers.Main) {
                    showDevelopingAnimation = true
                }
                
                // Auto-return after animation
                kotlinx.coroutines.delay(1200)
                
                withContext(Dispatchers.Main) {
                    showDevelopingAnimation = false
                    isProcessing = false
                }
                
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    showDevelopingAnimation = false
                }
            }
        }
    }
    
    /**
     * Process captured image through film pipeline
     */
    private suspend fun processCapturedImage(settings: FilmSettings, drs: Boolean) {
        withContext(Dispatchers.Default) {
            try {
                // Load CLUT for selected emulation
                val clutBitmap = clutLoader.loadClut(settings.emulation.path)
                
                // Load grain texture
                val grainBitmap = clutLoader.loadGrainTexture(
                    when (settings.emulation.defaults.grainType) {
                        com.thetechgeekko.filmcam.model.GrainType.SUPERFINE -> com.thetechgeekko.filmcam.model.GrainType.SUPERFINE
                        com.thetechgeekko.filmcam.model.GrainType.FINE -> com.thetechgeekko.filmcam.model.GrainType.FINE
                        com.thetechgeekko.filmcam.model.GrainType.MEDIUM -> com.thetechgeekko.filmcam.model.GrainType.MEDIUM
                        com.thetechgeekko.filmcam.model.GrainType.COARSE -> com.thetechgeekko.filmcam.model.GrainType.COARSE
                    }
                )
                
                // Apply film pipeline (actual implementation processes the captured file)
                // filmProcessor.process(capturedFile, settings, clutBitmap, grainBitmap)
                
                // Write EXIF with emulation name
                // exifWriter.writeEmulationName(outputFile, settings.emulation.id)
                
            } catch (e: Exception) {
                // Handle processing error
            }
        }
    }
    
    /**
     * Provide haptic feedback based on pattern type
     */
    private fun provideHapticFeedback(pattern: HapticPattern) {
        when (pattern) {
            HapticPattern.ISO_TICK -> {
                vibrator.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
            }
            HapticPattern.EV_TICK -> {
                vibrator.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
            }
            HapticPattern.DR_TICK -> {
                vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            }
            HapticPattern.STOCK_HIGHLIGHT -> {
                vibrator.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
            }
            HapticPattern.CAPTURE -> {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            }
            HapticPattern.HDRX_ON -> {
                // Triple short haptic
                repeat(3) {
                    vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
                    kotlinx.coroutines.runBlocking {
                        kotlinx.coroutines.delay(50)
                    }
                }
            }
            HapticPattern.HDRX_OFF -> {
                // Single long haptic
                vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            }
            HapticPattern.SUCCESS -> {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(50, 50, 50), intArrayOf(100, 0, 100), -1))
            }
            HapticPattern.ERROR -> {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(100, 50, 100), intArrayOf(200, 0, 200), -1))
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        captureController.close()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        filmProcessor.release()
        clutLoader.clearCache()
    }
}

/**
 * Haptic feedback patterns matching mood.camera
 */
enum class HapticPattern {
    ISO_TICK,
    EV_TICK,
    DR_TICK,
    STOCK_HIGHLIGHT,
    CAPTURE,
    HDRX_ON,
    HDRX_OFF,
    SUCCESS,
    ERROR
}
