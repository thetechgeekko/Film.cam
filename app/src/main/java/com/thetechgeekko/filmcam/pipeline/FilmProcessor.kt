package com.thetechgeekko.filmcam.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
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
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(
            GLES30.GL_FRAGMENT_SHADER,
            context.assets.open("shaders/filmcam/film_pipeline.frag").bufferedReader().use { it.readText() }
        )
        
        programId = GLES30.glCreateProgram()
        GLES30.glAttachShader(programId, vertexShader)
        GLES30.glAttachShader(programId, fragmentShader)
        GLES30.glLinkProgram(programId)
        
        val linkStatus = intArrayOf(0)
        GLES30.glGetProgramiv(programId, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES30.GL_TRUE) {
            val errorLog = GLES30.glGetProgramInfoLog(programId)
            throw RuntimeException("Failed to link shader program: $errorLog")
        }
        
        // Get all uniform locations
        uInputImageLoc = GLES30.glGetUniformLocation(programId, "uInputImage")
        uHaldClutLoc = GLES30.glGetUniformLocation(programId, "uHaldClut")
        uGrainTextureLoc = GLES30.glGetUniformLocation(programId, "uGrainTexture")
        uSkinClutLoc = GLES30.glGetUniformLocation(programId, "uSkinClut")
        uClutLevelLoc = GLES30.glGetUniformLocation(programId, "uClutLevel")
        uEmulationStrengthLoc = GLES30.glGetUniformLocation(programId, "uEmulationStrength")
        uSaturationLoc = GLES30.glGetUniformLocation(programId, "uSaturation")
        uContrastLoc = GLES30.glGetUniformLocation(programId, "uContrast")
        uTemperatureLoc = GLES30.glGetUniformLocation(programId, "uTemperature")
        uTintLoc = GLES30.glGetUniformLocation(programId, "uTint")
        uFadeLoc = GLES30.glGetUniformLocation(programId, "uFade")
        uMuteLoc = GLES30.glGetUniformLocation(programId, "uMute")
        uGrainLevelLoc = GLES30.glGetUniformLocation(programId, "uGrainLevel")
        uGrainSizeLoc = GLES30.glGetUniformLocation(programId, "uGrainSize")
        uHalationLoc = GLES30.glGetUniformLocation(programId, "uHalation")
        uBloomLoc = GLES30.glGetUniformLocation(programId, "uBloom")
        uAberrationLoc = GLES30.glGetUniformLocation(programId, "uAberration")
        uToneCurveLoc = GLES30.glGetUniformLocation(programId, "uToneCurve")
        uExposureCompLoc = GLES30.glGetUniformLocation(programId, "uExposureComp")
        uIsoFactorLoc = GLES30.glGetUniformLocation(programId, "uIsoFactor")
        uDynamicRangeLoc = GLES30.glGetUniformLocation(programId, "uDynamicRange")
        uApplySkinTonesLoc = GLES30.glGetUniformLocation(programId, "uApplySkinTones")
        uSkinMaskThresholdLoc = GLES30.glGetUniformLocation(programId, "uSkinMaskThreshold")
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
        GLES30.glGenVertexArrays(1, vao, 0)
        vaoId = vao[0]
        GLES30.glBindVertexArray(vaoId)
        
        // Create VBO
        val vbo = intArrayOf(0)
        GLES30.glGenBuffers(1, vbo, 0)
        vboId = vbo[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, vertexBuffer, GLES30.GL_STATIC_DRAW)
        
        // Position attribute (location 0)
        positionHandle = 0
        GLES30.glVertexAttribPointer(positionHandle, 4, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(positionHandle)
        
        GLES30.glBindVertexArray(0)
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
        GLES30.glUseProgram(programId)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glViewport(0, 0, width, height)
        
        // Bind textures to texture units
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTextureId)
        GLES30.glUniform1i(uInputImageLoc, 0)
        
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, clutTextureId)
        GLES30.glUniform1i(uHaldClutLoc, 1)
        
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, grainTextureId)
        GLES30.glUniform1i(uGrainTextureLoc, 2)
        
        // Set uniforms
        GLES30.glUniform1f(uClutLevelLoc, FilmSettings.getClutLevel(512)) // Will be dynamic based on actual CLUT
        
        GLES30.glUniform1f(uEmulationStrengthLoc, settings.emulationStrength)
        GLES30.glUniform1f(uSaturationLoc, settings.saturation)
        GLES30.glUniform1f(uContrastLoc, settings.contrast)
        GLES30.glUniform1f(uTemperatureLoc, settings.temperature)
        GLES30.glUniform1f(uTintLoc, settings.tint)
        GLES30.glUniform1f(uFadeLoc, settings.fade)
        GLES30.glUniform1f(uMuteLoc, settings.mute)
        
        GLES30.glUniform1f(uGrainLevelLoc, settings.grainLevel)
        GLES30.glUniform1f(uGrainSizeLoc, settings.grainSize)
        GLES30.glUniform1f(uHalationLoc, settings.halation)
        GLES30.glUniform1f(uBloomLoc, settings.bloom)
        GLES30.glUniform1f(uAberrationLoc, settings.aberration)
        
        GLES30.glUniform3f(
            uToneCurveLoc,
            settings.toneCurve.highlights,
            settings.toneCurve.midtones,
            settings.toneCurve.shadows
        )
        
        GLES30.glUniform1f(uExposureCompLoc, settings.exposureComp)
        GLES30.glUniform1f(uIsoFactorLoc, FilmSettings.calculateIsoFactor(settings.isoSim))
        GLES30.glUniform1i(uDynamicRangeLoc, settings.dynamicRange.ordinal)
        
        GLES30.glUniform1i(uApplySkinTonesLoc, if (settings.emulation.id == "skinTones") 1 else 0)
        GLES30.glUniform1f(uSkinMaskThresholdLoc, 0.3f) // Skin luminance threshold
        
        // Draw fullscreen quad
        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glUseProgram(0)
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
        GLES30.glGenTextures(1, textureId, 0)
        
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId[0])
        
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        
        return textureId[0]
    }
    
    /**
     * Read framebuffer pixels to Bitmap
     */
    private fun readFramebufferToBitmap(width: Int, height: Int, fboId: Int): Bitmap {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        
        val pixelBuffer = ByteBuffer.allocate(width * height * 4)
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixelBuffer)
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        pixelBuffer.rewind()
        
        // Flip vertically (OpenGL reads from bottom-left)
        val flippedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(flippedBitmap)
        canvas.scale(1f, -1f, width / 2f, height / 2f)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        bitmap.recycle()
        
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        
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
        val shaderId = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shaderId, source)
        GLES30.glCompileShader(shaderId)
        
        val compileStatus = intArrayOf(0)
        GLES30.glGetShaderiv(shaderId, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] != GLES30.GL_TRUE) {
            val errorLog = GLES30.glGetShaderInfoLog(shaderId)
            GLES30.glDeleteShader(shaderId)
            throw RuntimeException("Failed to compile shader: $errorLog")
        }
        
        return shaderId
    }
    
    /**
     * Release all GPU resources
     */
    fun release() {
        if (programId > 0) {
            GLES30.glDeleteProgram(programId)
            programId = 0
        }
        if (vaoId > 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(vaoId), 0)
            vaoId = 0
        }
        if (vboId > 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(vboId), 0)
            vboId = 0
        }
        textureLoader.release()
    }
}
