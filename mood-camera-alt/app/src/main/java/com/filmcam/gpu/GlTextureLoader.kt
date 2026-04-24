package com.filmcam.gpu

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

/**
 * GPU texture loader for Film.cam pipeline
 * Handles CLUT textures, grain textures, and intermediate framebuffer objects
 * Implements LRU caching for CLUTs (max 3 active textures in VRAM)
 */
class GlTextureLoader {
    
    private val egl10: EGL10 = EGLContext.getEGL() as EGL10
    private var eglDisplay: EGLDisplay = EGL10.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL10.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL10.EGL_NO_SURFACE
    
    // LRU cache for CLUT textures (max 3 in VRAM per spec)
    private val clutCache = object : LruCache<String, Int>(3) {
        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: Int?) {
            oldValue?.let { textureId ->
                deleteTexture(textureId)
            }
        }
    }
    
    // Grain texture cache (4 types, all can stay loaded)
    private val grainTextures = mutableMapOf<String, Int>()
    
    /**
     * Initialize EGL context for off-screen rendering
     */
    fun initialize() {
        if (eglDisplay != EGL10.EGL_NO_DISPLAY) return
        
        eglDisplay = egl10.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL10.EGL_NO_DISPLAY) {
            throw RuntimeException("Could not get EGL display")
        }
        
        val version = intArrayOf(0, 0)
        if (!egl10.eglInitialize(eglDisplay, version)) {
            throw RuntimeException("Could not initialize EGL")
        }
        
        val configSpec = intArrayOf(
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_ALPHA_SIZE, 8,
            EGL10.EGL_RENDERABLE_TYPE, 0x0004, // EGL_OPENGL_ES2_BIT
            EGL10.EGL_NONE
        )
        
        val numConfigs = intArrayOf(0)
        egl10.eglChooseConfig(eglDisplay, configSpec, null, 0, numConfigs)
        
        val configs = arrayOfNulls<EGLConfig>(numConfigs[0])
        egl10.eglChooseConfig(eglDisplay, configSpec, configs, numConfigs[0], numConfigs)
        
        val config = configs.firstOrNull() ?: throw RuntimeException("No suitable EGL config found")
        
        eglContext = egl10.eglCreateContext(eglDisplay, config, EGL10.EGL_NO_CONTEXT, 
            intArrayOf(EGL10.EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE))
        
        // Create dummy pbuffer surface for off-screen rendering
        val surfaceAttribs = intArrayOf(EGL10.EGL_NONE)
        eglSurface = egl10.eglCreatePbufferSurface(eglDisplay, config, 
            intArrayOf(EGL10.EGL_WIDTH, 64, EGL10.EGL_HEIGHT, 64, EGL10.EGL_NONE))
        
        if (!egl10.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("Could not make EGL context current")
        }
    }
    
    /**
     * Load HALD CLUT from asset path with validation
     * @param assetPath Path in assets/filmcam/cluts/
     * @return Texture ID or -1 on failure
     */
    suspend fun loadClut(assetPath: String): Int = withContext(Dispatchers.Default) {
        // Check cache first
        clutCache[assetPath]?.let { return@withContext it }
        
        try {
            val bitmap = BitmapFactory.decodeStream(
                javaClass.classLoader?.getResourceAsStream("assets/$assetPath")
                    ?: throw IllegalArgumentException("CLUT not found: $assetPath")
            )
            
            // Validate dimensions (must be square power-of-2)
            if (bitmap.width != bitmap.height) {
                throw IllegalArgumentException("CLUT must be square: ${bitmap.width}x${bitmap.height}")
            }
            
            val level = when (bitmap.width) {
                512 -> 8.0f
                1024 -> 16.0f
                2048 -> 32.0f
                else -> throw IllegalArgumentException("CLUT must be 512, 1024, or 2048 pixels")
            }
            
            val textureId = createTexture()
            bindTexture(textureId, GLES20.GL_TEXTURE_2D)
            
            // Set texture parameters for CLUT sampling
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            
            // Load bitmap into texture
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
            
            // Store in cache
            clutCache.put(assetPath, textureId)
            
            textureId
        } catch (e: Exception) {
            e.printStackTrace()
            -1
        }
    }
    
    /**
     * Load grain texture from assets
     * @param grainType GrainType enum value
     * @return Texture ID
     */
    suspend fun loadGrainTexture(grainType: String): Int = withContext(Dispatchers.Default) {
        grainTextures[grainType]?.let { return@withContext it }
        
        try {
            val bitmap = BitmapFactory.decodeStream(
                javaClass.classLoader?.getResourceAsStream("assets/filmcam/grains/$grainType")
                    ?: throw IllegalArgumentException("Grain texture not found: $grainType")
            )
            
            val textureId = createTexture()
            bindTexture(textureId, GLES20.GL_TEXTURE_2D)
            
            // Set texture parameters for tiling
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
            
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            
            // Generate mipmaps for grain scaling
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            bitmap.recycle()
            
            grainTextures[grainType] = textureId
            textureId
        } catch (e: Exception) {
            e.printStackTrace()
            -1
        }
    }
    
    /**
     * Create Framebuffer Object (FBO) for intermediate processing passes
     * @param width Output width
     * @param height Output height
     * @param useFloat32 Use 32-bit float texture for HDR processing
     * @return FBO handle
     */
    fun createFbo(width: Int, height: Int, useFloat32: Boolean = true): Int {
        val fbo = intArrayOf(0)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        
        // Create color attachment texture
        val texture = intArrayOf(0)
        GLES20.glGenTextures(1, texture, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
        
        if (useFloat32) {
            // Use RGB32F for linear HDR processing
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0,
                GLES20.GL_RGB32F,
                width, height, 0,
                GLES20.GL_RGB,
                GLES20.GL_FLOAT,
                null
            )
        } else {
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0,
                GLES20.GL_RGBA,
                width, height, 0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                null
            )
        }
        
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        
        // Attach to FBO
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            texture[0],
            0
        )
        
        // Check FBO completeness
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("FBO incomplete: $status")
        }
        
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        fbo[0]
    }
    
    /**
     * Create OpenGL texture ID
     */
    private fun createTexture(): Int {
        val textureId = intArrayOf(0)
        GLES20.glGenTextures(1, textureId, 0)
        return textureId[0]
    }
    
    /**
     * Bind texture and set default parameters
     */
    private fun bindTexture(textureId: Int, target: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, textureId)
    }
    
    /**
     * Delete texture and free GPU memory
     */
    fun deleteTexture(textureId: Int) {
        if (textureId > 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        }
    }
    
    /**
     * Delete FBO and associated textures
     */
    fun deleteFbo(fboId: Int) {
        if (fboId > 0) {
            // Get attached texture ID
            val params = intArrayOf(0)
            GLES20.glGetFramebufferAttachmentParameteriv(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME,
                params,
                0
            )
            val textureId = params[0]
            if (textureId > 0) {
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            }
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
        }
    }
    
    /**
     * Clear all caches and release GPU resources
     */
    fun release() {
        // Clear CLUT cache (triggers entryRemoved which deletes textures)
        clutCache.evictAll()
        
        // Delete grain textures
        grainTextures.values.forEach { deleteTexture(it) }
        grainTextures.clear()
        
        // Release EGL context
        if (eglSurface != EGL10.EGL_NO_SURFACE) {
            egl10.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL10.EGL_NO_SURFACE
        }
        if (eglContext != EGL10.EGL_NO_CONTEXT) {
            egl10.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL10.EGL_NO_CONTEXT
        }
        if (eglDisplay != EGL10.EGL_NO_DISPLAY) {
            egl10.eglTerminate(eglDisplay)
            eglDisplay = EGL10.EGL_NO_DISPLAY
        }
    }
    
    /**
     * Make EGL context current for rendering
     */
    fun makeCurrent() {
        egl10.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }
    
    /**
     * Swap buffers (for debugging/display)
     */
    fun swapBuffers() {
        egl10.eglSwapBuffers(eglDisplay, eglSurface)
    }
}
