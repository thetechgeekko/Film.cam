package com.thetechgeekko.filmcam.capture

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalZeroShutterLag
import com.thetechgeekko.filmcam.model.FilmSettings
import com.thetechgeekko.filmcam.model.AspectRatio
import java.io.File
import java.nio.ByteBuffer

/**
 * Camera2 capture controller for Film.cam
 * Handles RAW/JPEG capture, AE locking, exposure bracketing for DRS
 * Zerocam-aligned: no manual shutter/ISO/focus controls exposed to user
 */
class CaptureController(
    private val context: Context,
    private val cameraManager: CameraManager
) {
    companion object {
        private const val TAG = "FilmCam.CaptureCtrl"
        private const val MAX_RESOLUTION_MP = 24 // 24MP ceiling
        private const val LOW_LIGHT_BINNING_MP = 12 // Auto-bin to 12MP in low light
        
        // Exposure bracketing for DRS
        val DRS_EV_OFFSETS = floatArrayOf(-1.5f, 0f, 1.5f)
    }
    
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var dngImageReader: ImageReader? = null
    
    private val handlerThread = HandlerThread("FilmCamCapture").apply { start() }
    private val mainHandler = Handler(handlerThread.looper)
    
    private var currentSettings: FilmSettings = FilmSettings()
    private var onCaptureListener: ((CaptureResult) -> Unit)? = null
    private var onCaptureFailed: ((String) -> Unit)? = null
    
    // Camera characteristics
    private var cameraCharacteristics: CameraCharacteristics? = null
    private var availableResolutions: List<Size> = emptyList()
    private var isColorBlind: Boolean = false
    private var minIso: Int = 100
    private var maxIso: Int = 3200
    private var focalLengths: FloatArray = floatArrayOf()
    
    /**
     * Open camera with specified camera ID (back/front)
     */
    fun openCamera(cameraId: String, callback: CameraStateCallback) {
        try {
            cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
            
            // Extract capabilities
            val streamConfigMap = cameraCharacteristics?.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )
            availableResolutions = streamConfigMap?.getOutputSizes(ImageFormat.JPEG)?.toList() 
                ?: emptyList()
            
            isColorBlind = cameraCharacteristics?.get(
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
            ) == CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO
            
            val sensitivityRange = cameraCharacteristics?.get(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
            )
            minIso = sensitivityRange?.lower ?: 100
            maxIso = sensitivityRange?.upper ?: 3200
            
            focalLengths = cameraCharacteristics?.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            ) ?: floatArrayOf()
            
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    callback.onOpened(camera)
                    Log.d(TAG, "Camera opened: $cameraId")
                }
                
                override fun onDisconnected(camera: CameraDevice) {
                    callback.onDisconnected()
                    Log.d(TAG, "Camera disconnected")
                }
                
                override fun onError(camera: CameraDevice, error: Int) {
                    callback.onError(error)
                    Log.e(TAG, "Camera error: $error")
                }
            }, mainHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
            onCaptureFailed?.invoke("Camera open failed: ${e.message}")
        }
    }
    
    /**
     * Create capture session with ImageReaders for JPEG and optional DNG
     */
    @OptIn(ExperimentalZeroShutterLag::class)
    fun createCaptureSession(
        jpegWidth: Int,
        jpegHeight: Int,
        saveRaw: Boolean = false,
        callback: SessionCallback
    ) {
        val camera = cameraDevice ?: return
        
        // Create ImageReader for JPEG output
        imageReader = ImageReader.newInstance(jpegWidth, jpegHeight, ImageFormat.JPEG, 3).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                image?.let {
                    processJpegImage(it)
                    it.close()
                }
            }, mainHandler)
        }
        
        // Optional DNG reader for Pro Mode
        if (saveRaw) {
            dngImageReader = ImageReader.newInstance(jpegWidth, jpegHeight, ImageFormat.RAW_SENSOR, 3).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    image?.let {
                        processDngImage(it)
                        it.close()
                    }
                }, mainHandler)
            }
        }
        
        val surfaces = mutableListOf(imageReader!!.surface)
        if (saveRaw) {
            dngImageReader?.surface?.let { surfaces.add(it) }
        }
        
        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                callback.onConfigured(session)
                Log.d(TAG, "Capture session configured")
            }
            
            override fun onConfigureFailed(session: CameraCaptureSession) {
                callback.onConfigureFailed("Session config failed")
                Log.e(TAG, "Capture session configuration failed")
            }
        }, mainHandler)
    }
    
    /**
     * Set up preview/repeating request with current settings
     */
    fun startPreview(settings: FilmSettings) {
        currentSettings = settings
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(imageReader?.surface ?: return)
        
        // Apply exposure compensation
        applyExposureSettings(builder, settings)
        
        // Apply aspect ratio crop
        applyAspectRatioCrop(builder, settings.aspectRatio)
        
        // Apply focal length preset (digital crop)
        applyFocalLengthPreset(builder, settings.focalLengthPreset)
        
        session.setRepeatingRequest(builder.build(), captureCallback, mainHandler)
    }
    
    /**
     * Capture single frame or DRS burst
     */
    fun capture(settings: FilmSettings, drsEnabled: Boolean = false) {
        currentSettings = settings
        
        if (drsEnabled) {
            captureDRSBurst(settings)
        } else {
            captureSingleFrame(settings)
        }
    }
    
    /**
     * Single frame capture with AE lock
     */
    private fun captureSingleFrame(settings: FilmSettings) {
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        builder.addTarget(imageReader?.surface ?: return)
        
        // Lock AE
        builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
        
        // Apply settings
        applyExposureSettings(builder, settings)
        applyAspectRatioCrop(builder, settings.aspectRatio)
        applyFocalLengthPreset(builder, settings.focalLengthPreset)
        
        // Set ISO simulation (via exposure time adjustment)
        applyIsoSimulation(builder, settings.isoSim)
        
        session.capture(builder.build(), captureCallback, mainHandler)
    }
    
    /**
     * DRS burst capture: 3 frames at -1.5EV, 0EV, +1.5EV with AE lock
     */
    private fun captureDRSBurst(settings: FilmSettings) {
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        
        val burstBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        burstBuilder.addTarget(imageReader?.surface ?: return)
        
        // Lock AE for all frames
        burstBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true)
        
        // Apply base settings
        applyExposureSettings(burstBuilder, settings)
        applyAspectRatioCrop(burstBuilder, settings.aspectRatio)
        applyFocalLengthPreset(burstBuilder, settings.focalLengthPreset)
        applyIsoSimulation(burstBuilder, settings.isoSim)
        
        // Create 3 capture requests with different EV offsets
        val captureRequests = mutableListOf<CaptureRequest>()
        
        DRS_EV_OFFSETS.forEach { evOffset ->
            val frameBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            frameBuilder.addTarget(imageReader?.surface ?: return)
            
            // Copy base settings
            frameBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true)
            frameBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 
                (evOffset * 2).toInt()) // Convert to 1/2 EV steps
            
            // Apply other settings
            applyAspectRatioCrop(frameBuilder, settings.aspectRatio)
            applyFocalLengthPreset(frameBuilder, settings.focalLengthPreset)
            applyIsoSimulation(frameBuilder, settings.isoSim)
            
            captureRequests.add(frameBuilder.build())
        }
        
        // Execute burst
        session.captureBurst(captureRequests, burstCallback, mainHandler)
    }
    
    /**
     * Apply exposure compensation from settings
     */
    private fun applyExposureSettings(builder: CaptureRequest.Builder, settings: FilmSettings) {
        val aeComp = settings.exposureComp
        val clampedComp = aeComp.coerceIn(-3f, 3f)
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 
            (clampedComp * 2).toInt()) // 1/2 EV steps
    }
    
    /**
     * Apply ISO simulation by adjusting exposure time
     * Note: Actual ISO is sensor-dependent; we simulate via exposure
     */
    private fun applyIsoSimulation(builder: CaptureRequest.Builder, targetIso: Int) {
        val sensorExposureTime = cameraCharacteristics?.get(
            CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
        )
        
        val exposureRange = sensorExposureTime ?: return
        val minExposure = exposureRange.lower
        val maxExposure = exposureRange.upper
        
        // Calculate exposure time based on simulated ISO
        // Higher ISO = shorter exposure time (brighter image)
        val isoFactor = targetIso.toFloat() / 200f // Base ISO 200
        val targetExposure = (minExposure * isoFactor).toLong().coerceIn(minExposure, maxExposure)
        
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, targetExposure)
    }
    
    /**
     * Apply aspect ratio via SCALER_CROP_REGION
     */
    private fun applyAspectRatioCrop(builder: CaptureRequest.Builder, aspectRatio: AspectRatio) {
        val sensorArray = cameraCharacteristics?.get(
            CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE
        ) ?: return
        
        val sensorWidth = sensorArray.width()
        val sensorHeight = sensorArray.height()
        
        val targetRatio = when (aspectRatio) {
            AspectRatio.ONE_ONE -> 1.0f
            AspectRatio.FOUR_THREE -> 4.0f / 3.0f
            AspectRatio.THREE_TWO -> 3.0f / 2.0f
            AspectRatio.SIXTEEN_NINE -> 16.0f / 9.0f
            AspectRatio.SCOPE -> 2.35f
        }
        
        val sensorRatio = sensorWidth.toFloat() / sensorHeight.toFloat()
        
        val cropRect = if (targetRatio > sensorRatio) {
            // Crop height
            val newHeight = (sensorWidth / targetRatio).toInt()
            val offsetY = (sensorHeight - newHeight) / 2
            android.graphics.Rect(0, offsetY, sensorWidth, offsetY + newHeight)
        } else {
            // Crop width
            val newWidth = (sensorHeight * targetRatio).toInt()
            val offsetX = (sensorWidth - newWidth) / 2
            android.graphics.Rect(offsetX, 0, offsetX + newWidth, sensorHeight)
        }
        
        builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
    }
    
    /**
     * Apply focal length preset via digital crop
     */
    private fun applyFocalLengthPreset(builder: CaptureRequest.Builder, focalMm: Float) {
        // Get base focal length (widest physical lens)
        val baseFocal = focalLengths.minOrNull() ?: 4.0f // Default 4mm
        
        // Calculate crop factor
        val cropFactor = baseFocal / focalMm
        if (cropFactor >= 1.0f) return // No zoom needed
        
        // Apply digital crop via SCALER_CROP_REGION
        val currentCrop = builder.get(CaptureRequest.SCALER_CROP_REGION) ?: return
        
        val newWidth = (currentCrop.width() * cropFactor).toInt()
        val newHeight = (currentCrop.height() * cropFactor).toInt()
        val newX = currentCrop.left + (currentCrop.width() - newWidth) / 2
        val newY = currentCrop.top + (currentCrop.height() - newHeight) / 2
        
        builder.set(CaptureRequest.SCALER_CROP_REGION, 
            android.graphics.Rect(newX, newY, newX + newWidth, newY + newHeight))
    }
    
    /**
     * Process captured JPEG image
     */
    private fun processJpegImage(image: Image) {
        val buffer: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        
        // Save to temp file or pass to processor
        val tempFile = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        tempFile.writeBytes(bytes)
        
        Log.d(TAG, "JPEG captured: ${tempFile.absolutePath}, size: ${bytes.size}")
        onCaptureListener?.invoke(CaptureResult(tempFile, false))
    }
    
    /**
     * Process captured DNG image (Pro Mode)
     */
    private fun processDngImage(image: Image) {
        val buffer: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        
        val tempFile = File(context.cacheDir, "capture_${System.currentTimeMillis()}.dng")
        tempFile.writeBytes(bytes)
        
        Log.d(TAG, "DNG captured: ${tempFile.absolutePath}, size: ${bytes.size}")
        // DNG will be processed alongside JPEG
    }
    
    /**
     * Capture callback for single frames
     */
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            Log.d(TAG, "Capture completed")
        }
        
        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            Log.e(TAG, "Capture failed: ${failure.reason}")
            onCaptureFailed?.invoke("Capture failed: ${failure.reason}")
        }
    }
    
    /**
     * Burst capture callback for DRS
     */
    private val burstCallback = object : CameraCaptureSession.CaptureCallback() {
        private var frameCount = 0
        
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            frameCount++
            if (frameCount >= 3) {
                Log.d(TAG, "DRS burst completed: 3 frames")
                onCaptureListener?.invoke(CaptureResult(null, true)) // DRS flag
            }
        }
        
        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            Log.e(TAG, "DRS burst failed: ${failure.reason}")
            onCaptureFailed?.invoke("DRS failed: ${failure.reason}")
        }
    }
    
    /**
     * Close camera and release resources
     */
    fun close() {
        captureSession?.close()
        captureSession = null
        
        cameraDevice?.close()
        cameraDevice = null
        
        imageReader?.close()
        imageReader = null
        
        dngImageReader?.close()
        dngImageReader = null
        
        handlerThread.quitSafely()
        
        Log.d(TAG, "Camera controller closed")
    }
    
    // Callbacks
    interface CameraStateCallback {
        fun onOpened(camera: CameraDevice)
        fun onDisconnected()
        fun onError(error: Int)
    }
    
    interface SessionCallback {
        fun onConfigured(session: CameraCaptureSession)
        fun onConfigureFailed(message: String)
    }
    
    data class CaptureResult(val jpegFile: File?, val isDRSBurst: Boolean)
}
