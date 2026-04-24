package com.thetechgeekko.filmcam.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.opengl.GLES20
import android.util.Log
import com.filmcam.gpu.GlTextureLoader
import com.filmcam.model.FilmSettings
import com.filmcam.model.FilmEmulation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Core film processing pipeline implementing mood.camera feature parity
 * Strict processing order: CLUT → Color Adjust → Tone Curve → Halation → Bloom → 
 *                         Chromatic Aberration → Linear→sRGB → Grain (LAST)
 * 
 * All processing happens in linear RGB space with zero sharpening, zero noise reduction
 */
class FilmProcessor(
    private val context: Context,
    private val textureLoader: GlTextureLoader
) {
    
    companion object {
        private const val TAG = "FilmProcessor"
        
        // Shader sources
        private const val VERTEX_SHADER = """
            #version 300 es
            in vec4 aPosition;
            in vec2 aTexCoord;
            out vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """
    }
    
    private var programId: Int = 0
    private var positionHandle: Int = 0
    private var texCoordHandle: Int = 0
    private var vaoId: Int = 0
    private var vboId: Int = 0
    
    // Uniform locations
    private var uInputImageLoc: Int = 0
    private var uHaldClutLoc: Int = 0
    private var uGrainTextureLoc: Int = 0
    private var uSkinClutLoc: Int = 0
    private var uClutLevelLoc: Int = 0
    private var uEmulationStrengthLoc: Int = 0
    private var uSaturationLoc: Int = 0
    private var uContrastLoc: Int = 0
    private var uTemperatureLoc: Int = 0
    private var uTintLoc: Int = 0
    private var uFadeLoc: Int = 0
    private var uMuteLoc: Int = 0
    private var uGrainLevelLoc: Int = 0
    private var uGrainSizeLoc: Int = 0
    private var uHalationLoc: Int = 0
    private var uBloomLoc: Int = 0
    private var uAberrationLoc: Int = 0
    private var uToneCurveLoc: Int = 0
    private var uExposureCompLoc: Int = 0
    private var uIsoFactorLoc: Int = 0
    private var uDynamicRangeLoc: Int = 0
    private var uApplySkinTonesLoc: Int = 0
    private var uSkinMaskThresholdLoc: Int = 0
    
    /**
     * Initialize shader program and uniform locations
     */
    fun initialize() {
        textureLoader.initialize()
        createShaderProgram()
        setupQuad()
    }
    
    /**
     * Create OpenGL shader program from film_pipeline.frag
     */
    private fun createShaderProgram() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(
            GLES20.GL_FRAGMENT_SHADER,
            context.assets.open("shaders/filmcam/film_pipeline.frag").bufferedReader().use { it.readText() }
        )
        
        programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)
        
        val linkStatus = intArrayOf(0)
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val errorLog = GLES20.glGetProgramInfoLog(programId)
            throw RuntimeException("Failed to link shader program: $errorLog")
        }
        
        // Get all uniform locations
        uInputImageLoc = GLES20.glGetUniformLocation(programId, "uInputImage")
        uHaldClutLoc = GLES20.glGetUniformLocation(programId, "uHaldClut")
        uGrainTextureLoc = GLES20.glGetUniformLocation(programId, "uGrainTexture")
        uSkinClutLoc = GLES20.glGetUniformLocation(programId, "uSkinClut")
        uClutLevelLoc = GLES20.glGetUniformLocation(programId, "uClutLevel")
        uEmulationStrengthLoc = GLES20.glGetUniformLocation(programId, "uEmulationStrength")
        uSaturationLoc = GLES20.glGetUniformLocation(programId, "uSaturation")
        uContrastLoc = GLES20.glGetUniformLocation(programId, "uContrast")
        uTemperatureLoc = GLES20.glGetUniformLocation(programId, "uTemperature")
        uTintLoc = GLES20.glGetUniformLocation(programId, "uTint")
        uFadeLoc = GLES20.glGetUniformLocation(programId, "uFade")
        uMuteLoc = GLES20.glGetUniformLocation(programId, "uMute")
        uGrainLevelLoc = GLES20.glGetUniformLocation(programId, "uGrainLevel")
        uGrainSizeLoc = GLES20.glGetUniformLocation(programId, "uGrainSize")
        uHalationLoc = GLES20.glGetUniformLocation(programId, "uHalation")
        uBloomLoc = GLES20.glGetUniformLocation(programId, "uBloom")
        uAberrationLoc = GLES20.glGetUniformLocation(programId, "uAberration")
        uToneCurveLoc = GLES20.glGetUniformLocation(programId, "uToneCurve")
        uExposureCompLoc = GLES20.glGetUniformLocation(programId, "uExposureComp")
        uIsoFactorLoc = GLES20.glGetUniformLocation(programId, "uIsoFactor")
        uDynamicRangeLoc = GLES20.glGetUniformLocation(programId, "uDynamicRange")
        uApplySkinTonesLoc = GLES20.glGetUniformLocation(programId, "uApplySkinTones")
        uSkinMaskThresholdLoc = GLES20.glGetUniformLocation(programId, "uSkinMaskThreshold")
    }
    
    /**
     * Setup fullscreen quad for processing
     */
    private fun setupQuad() {
        // Vertex positions and texture coordinates for fullscreen quad
        val vertices = floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f
        )
        
        val vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
            .position(0)
        
        // Create VAO
        val vao = intArrayOf(0)
        GLES20.glGenVertexArrays(1, vao, 0)
        vaoId = vao[0]
        GLES20.glBindVertexArray(vaoId)
        
        // Create VBO
        val vbo = intArrayOf(0)
        GLES20.glGenBuffers(1, vbo, 0)
        vboId = vbo[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertices.size * 4, vertexBuffer, GLES20.GL_STATIC_DRAW)
        
        // Position attribute (location 0)
        positionHandle = 0
        GLES20.glVertexAttribPointer(positionHandle, 4, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        
        GLES20.glBindVertexArray(0)
    }
    
    /**
     * Process captured image through film pipeline
     * @param inputImage Camera2 Image or Bitmap
     * @param settings Film settings with emulation and parameters
     * @return Processed JPEG bytes
     */
    suspend fun processImage(
        inputImage: Any,
        settings: FilmSettings
    ): ByteArray = withContext(Dispatchers.Default) {
        try {
            textureLoader.makeCurrent()
            
            // Convert input to texture
            val inputBitmap = when (inputImage) {
                is Bitmap -> inputImage
                is Image -> imageToBitmap(inputImage)
                else -> throw IllegalArgumentException("Unsupported input type")
            }
            
            // Load CLUT texture
            val clutTextureId = textureLoader.loadClut(settings.emulation.path)
            if (clutTextureId < 0) {
                throw RuntimeException("Failed to load CLUT: ${settings.emulation.path}")
            }
            
            // Load grain texture
            val grainTextureId = textureLoader.loadGrainTexture(
                settings.emulation.defaults.grainType.assetName
            )
            
            // Create FBO for output
            val width = inputBitmap.width
            val height = inputBitmap.height
            val fboId = textureLoader.createFbo(width, height, useFloat32 = true)
            
            // Load input bitmap as texture
            val inputTextureId = loadBitmapAsTexture(inputBitmap)
            
            // Render with film pipeline
            renderFilmPipeline(
                inputTextureId = inputTextureId,
                clutTextureId = clutTextureId,
                grainTextureId = grainTextureId,
                width = width,
                height = height,
                settings = settings,
                fboId = fboId
            )
            
            // Read back processed pixels
            val processedBitmap = readFramebufferToBitmap(width, height, fboId)
            
            // Compress to JPEG
            val jpegBytes = compressToJpeg(processedBitmap)
            
            // Cleanup
            textureLoader.deleteTexture(inputTextureId)
            textureLoader.deleteFbo(fboId)
            inputBitmap.recycle()
            processedBitmap.recycle()
            
            jpegBytes
        } catch (e: Exception) {
            Log.e(TAG, "Film processing failed", e)
            throw e
        }
    }
    
    /**
     * Render the complete film pipeline shader
     */
    private fun renderFilmPipeline(
        inputTextureId: Int,
        clutTextureId: Int,
        grainTextureId: Int,
        width: Int,
        height: Int,
        settings: FilmSettings,
        fboId: Int
    ) {
        GLES20.glUseProgram(programId)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glViewport(0, 0, width, height)
        
        // Bind textures to texture units
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTextureId)
        GLES20.glUniform1i(uInputImageLoc, 0)
        
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, clutTextureId)
        GLES20.glUniform1i(uHaldClutLoc, 1)
        
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, grainTextureId)
        GLES20.glUniform1i(uGrainTextureLoc, 2)
        
        // Set uniforms
        GLES20.glUniform1f(uClutLevelLoc, FilmSettings.getClutLevel(512)) // Will be dynamic based on actual CLUT
        
        GLES20.glUniform1f(uEmulationStrengthLoc, settings.emulationStrength)
        GLES20.glUniform1f(uSaturationLoc, settings.saturation)
        GLES20.glUniform1f(uContrastLoc, settings.contrast)
        GLES20.glUniform1f(uTemperatureLoc, settings.temperature)
        GLES20.glUniform1f(uTintLoc, settings.tint)
        GLES20.glUniform1f(uFadeLoc, settings.fade)
        GLES20.glUniform1f(uMuteLoc, settings.mute)
        
        GLES20.glUniform1f(uGrainLevelLoc, settings.grainLevel)
        GLES20.glUniform1f(uGrainSizeLoc, settings.grainSize)
        GLES20.glUniform1f(uHalationLoc, settings.halation)
        GLES20.glUniform1f(uBloomLoc, settings.bloom)
        GLES20.glUniform1f(uAberrationLoc, settings.aberration)
        
        GLES20.glUniform3f(
            uToneCurveLoc,
            settings.toneCurve.highlights,
            settings.toneCurve.midtones,
            settings.toneCurve.shadows
        )
        
        GLES20.glUniform1f(uExposureCompLoc, settings.exposureComp)
        GLES20.glUniform1f(uIsoFactorLoc, FilmSettings.calculateIsoFactor(settings.isoSim))
        GLES20.glUniform1i(uDynamicRangeLoc, settings.dynamicRange.ordinal)
        
        GLES20.glUniform1i(uApplySkinTonesLoc, if (settings.emulation.id == "skinTones") 1 else 0)
        GLES20.glUniform1f(uSkinMaskThresholdLoc, 0.3f) // Skin luminance threshold
        
        // Draw fullscreen quad
        GLES20.glBindVertexArray(vaoId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindVertexArray(0)
        
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glUseProgram(0)
    }
    
    /**
     * Convert Android Image to Bitmap
     */
    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width
        
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        
        // Crop to actual dimensions if needed
        return if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        } else {
            bitmap
        }
    }
    
    /**
     * Load Bitmap as OpenGL texture
     */
    private fun loadBitmapAsTexture(bitmap: Bitmap): Int {
        val textureId = intArrayOf(0)
        GLES20.glGenTextures(1, textureId, 0)
        
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId[0])
        
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        
        return textureId[0]
    }
    
    /**
     * Read framebuffer pixels to Bitmap
     */
    private fun readFramebufferToBitmap(width: Int, height: Int, fboId: Int): Bitmap {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        
        val pixelBuffer = ByteBuffer.allocate(width * height * 4)
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer)
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        pixelBuffer.rewind()
        
        // Flip vertically (OpenGL reads from bottom-left)
        val flippedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(flippedBitmap)
        canvas.scale(1f, -1f, width / 2f, height / 2f)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        bitmap.recycle()
        
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        
        return flippedBitmap
    }
    
    /**
     * Compress Bitmap to JPEG bytes
     */
    private fun compressToJpeg(bitmap: Bitmap): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
        return outputStream.toByteArray()
    }
    
    /**
     * Load shader from source string
     */
    private fun loadShader(type: Int, source: String): Int {
        val shaderId = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shaderId, source)
        GLES20.glCompileShader(shaderId)
        
        val compileStatus = intArrayOf(0)
        GLES20.glGetShaderiv(shaderId, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] != GLES20.GL_TRUE) {
            val errorLog = GLES20.glGetShaderInfoLog(shaderId)
            GLES20.glDeleteShader(shaderId)
            throw RuntimeException("Failed to compile shader: $errorLog")
        }
        
        return shaderId
    }
    
    /**
     * Release all GPU resources
     */
    fun release() {
        if (programId > 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
        if (vaoId > 0) {
            GLES20.glDeleteVertexArrays(1, intArrayOf(vaoId), 0)
            vaoId = 0
        }
        if (vboId > 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(vboId), 0)
            vboId = 0
        }
        textureLoader.release()
    }
}
