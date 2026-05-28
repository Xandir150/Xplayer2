package com.teleteh.xplayer2.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLSurfaceView that accepts video frames from ExoPlayer via SurfaceTexture
 * and renders them with optional Over-Under → Side-By-Side conversion.
 *
 * Resize modes:
 *  0 = Auto (use source aspect)
 *  1 = 16:9
 *  2 = 4:3
 *  3 = 21:9
 *  4 = 32:9
 *  5 = 1:1
 *  6 = 2.39:1
 */
class OuToSbsGlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer: OuToSbsRenderer

    init {
        setEGLContextClientVersion(2)
        renderer = OuToSbsRenderer()
        setRenderer(renderer)
        // Render only when we receive a new video frame
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setOnSurfaceReadyListener(listener: (Surface) -> Unit) {
        renderer.onSurfaceReady = listener
        renderer.surface?.let { surf ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                listener(surf)
            } else {
                Handler(Looper.getMainLooper()).post { listener(surf) }
            }
        }
    }

    fun setSbsEnabled(enabled: Boolean) {
        renderer.sbsEnabled.set(enabled)
        requestRender()
    }

    /**
     * Marks the source as already side-by-side (instead of the default Over-Under).
     * When SBS mode is enabled and source is also SBS, the renderer samples left/right
     * halves of the texture rather than top/bottom.
     */
    fun setSourceIsSbs(enabled: Boolean) {
        renderer.sourceIsSbs.set(enabled)
        requestRender()
    }

    /**
     * When SBS is disabled, optionally duplicate the mono frame side-by-side (left and right halves).
     * Useful for stereo displays to watch 2D content.
     */
    fun setDuplicateMonoToSbs(enabled: Boolean) {
        renderer.duplicateMonoToSbs.set(enabled)
        requestRender()
    }

    fun setSwapEyes(enabled: Boolean) {
        renderer.swapEyes.set(enabled)
        requestRender()
    }

    /**
     * Set per-eye vertical shift in normalized texture space (0..1 of full texture height).
     * Positive value lowers the image on screen for that eye.
     */
    fun setEyeVerticalShiftNormalized(left: Float, right: Float) {
        val l = left.coerceIn(-0.25f, 0.25f)
        val r = right.coerceIn(-0.25f, 0.25f)
        queueEvent {
            renderer.setEyeShiftNormalized(l, r)
        }
        requestRender()
    }

    fun setEyeVerticalShiftPx(leftPx: Float, rightPx: Float, referenceHeightPx: Float) {
        if (referenceHeightPx <= 0f) return
        setEyeVerticalShiftNormalized(leftPx / referenceHeightPx, rightPx / referenceHeightPx)
    }

    fun setOppositeVerticalShiftPx(amountPx: Float, referenceHeightPx: Float) {
        if (referenceHeightPx <= 0f) return
        val amt = (amountPx / referenceHeightPx).coerceIn(0f, 0.25f)
        queueEvent {
            val swap = renderer.swapEyes.get()
            val leftFromTop = if (swap) true else false
            val rightFromTop = !leftFromTop
            val leftShift = if (leftFromTop) -amt else amt
            val rightShift = if (rightFromTop) -amt else amt
            renderer.setEyeShiftNormalized(leftShift, rightShift)
        }
        requestRender()
    }

    /**
     * Set per-eye letterbox padding as a pixel amount relative to a reference full-frame height.
     */
    fun setPerEyeLetterboxPx(amountPx: Float, referenceHeightPx: Float) {
        if (referenceHeightPx <= 0f) return
        val frac = (amountPx / referenceHeightPx).coerceIn(0f, 0.25f)
        queueEvent {
            renderer.perEyePadFrac = frac
        }
        requestRender()
    }

    /** 0 = Auto, 1 = 16:9, 2 = 4:3, 3 = 21:9, 4 = 32:9, 5 = 1:1, 6 = 2.39:1 */
    fun updateResizeMode(mode: Int) {
        renderer.updateResizeMode(mode)
        requestRender()
    }

    /** Tells the renderer the source video pixel dimensions so Auto mode preserves aspect. */
    fun updateVideoAspectRatio(width: Int, height: Int) {
        renderer.updateVideoAspectRatio(width, height)
        requestRender()
    }

    /**
     * Set a small parallax offset to apply to every sampled texel, in normalized
     * texture units (positive X shifts the picture right on screen, positive Y shifts up).
     * Drive this from [com.teleteh.xplayer2.data.glasses.HeadPoseTracker] to fake depth via
     * head-tracking when the "Lazy 3D" feature is active. Pass 0/0 to disable.
     */
    fun setParallaxOffset(x: Float, y: Float) {
        renderer.setParallaxOffset(x, y)
        requestRender()
    }

    private inner class OuToSbsRenderer : Renderer, SurfaceTexture.OnFrameAvailableListener {
        private var textureId: Int = 0
        private var surfaceTexture: SurfaceTexture? = null
        var surface: Surface? = null
            private set

        var onSurfaceReady: ((Surface) -> Unit)? = null
        val sbsEnabled = AtomicBoolean(false)
        val sourceIsSbs = AtomicBoolean(false)
        val swapEyes = AtomicBoolean(false)
        val duplicateMonoToSbs = AtomicBoolean(false)
        @Volatile var parallaxX: Float = 0f
        @Volatile var parallaxY: Float = 0f

        private var program = 0
        private var aPosLoc = 0
        private var aTexLoc = 0
        private var uTexLoc = 0
        private var uTexMatrixLoc = 0
        private var uScaleLoc = 0
        private var uOffsetLoc = 0
        private var uParallaxLoc = 0

        @Volatile private var leftEyeShiftNorm: Float = 0f
        @Volatile private var rightEyeShiftNorm: Float = 0f
        @Volatile var perEyePadFrac: Float = 0f
        @Volatile private var resizeMode: Int = 0
        @Volatile private var videoAspectRatio: Float = 16f / 9f
        private val texMatrix = FloatArray(16)

        // Fullscreen quad (two triangles)
        private val vertexData: FloatBuffer = floatBufferOf(
            // X,  Y,   U,  V (v=0 at bottom, v=1 at top)
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = buildProgram(VERT, FRAG)
            aPosLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aTexLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
            uTexLoc = GLES20.glGetUniformLocation(program, "uTexture")
            uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
            uScaleLoc = GLES20.glGetUniformLocation(program, "uScale")
            uOffsetLoc = GLES20.glGetUniformLocation(program, "uOffset")
            uParallaxLoc = GLES20.glGetUniformLocation(program, "uParallax")

            textureId = createOesTexture()
            surfaceTexture = SurfaceTexture(textureId).also {
                it.setOnFrameAvailableListener(this)
            }
            surface = Surface(surfaceTexture)
            val surf = surface!!
            // Post callback to main thread to avoid touching ExoPlayer on GL thread
            Handler(Looper.getMainLooper()).post {
                onSurfaceReady?.invoke(surf)
            }

            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            surfaceTexture?.let {
                it.updateTexImage()
                it.getTransformMatrix(texMatrix)
            }

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(uTexLoc, 0)

            if (sbsEnabled.get()) {
                if (sourceIsSbs.get()) {
                    drawSbsSource()
                } else {
                    drawOuToSbs()
                }
            } else {
                if (duplicateMonoToSbs.get()) {
                    drawMonoToSbs()
                } else {
                    drawFullScreen()
                }
            }

            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        }

        private fun drawOuToSbs() {
            val swap = swapEyes.get()
            val leftFromTop = if (swap) true else false
            val rightFromTop = if (swap) false else true
            drawEyeFromOu(left = true, fromTopHalf = leftFromTop)
            drawEyeFromOu(left = false, fromTopHalf = rightFromTop)
        }

        private fun drawSbsSource() {
            val swap = swapEyes.get()
            drawEyeFromSbs(left = true, useRightHalf = swap)
            drawEyeFromSbs(left = false, useRightHalf = !swap)
        }

        private fun drawFullScreen() {
            val viewport = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)
            // Auto (resizeMode == 0): stretch to the whole viewport, no aspect-preserving fit
            // — this matches the original pre-aspect-button behaviour which "was almost OK".
            if (resizeMode == 0) {
                drawTexture(1f, 1f, 0f, 0f)
                return
            }
            val targetAspect = getTargetAspectRatio(resizeMode, videoAspectRatio)
            val fit = calculateFitRect(viewport[0], viewport[1], viewport[2], viewport[3], targetAspect)
            GLES20.glViewport(fit.x, fit.y, fit.width, fit.height)
            drawTexture(1f, 1f, 0f, 0f)
            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        }

        private fun drawMonoToSbs() {
            val viewport = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)
            val eyeWidth = viewport[2] / 2

            if (resizeMode == 0) {
                // Auto: stretch full mono frame into each half viewport (legacy behaviour).
                GLES20.glViewport(viewport[0], viewport[1], eyeWidth, viewport[3])
                drawTexture(1f, 1f, 0f, 0f)
                GLES20.glViewport(viewport[0] + eyeWidth, viewport[1], eyeWidth, viewport[3])
                drawTexture(1f, 1f, 0f, 0f)
                GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
                return
            }

            val targetAspect = getTargetAspectRatio(resizeMode, videoAspectRatio)
            val l = calculateFitRect(viewport[0], viewport[1], eyeWidth, viewport[3], targetAspect)
            GLES20.glViewport(l.x, l.y, l.width, l.height)
            drawTexture(1f, 1f, 0f, 0f)

            val r = calculateFitRect(viewport[0] + eyeWidth, viewport[1], eyeWidth, viewport[3], targetAspect)
            GLES20.glViewport(r.x, r.y, r.width, r.height)
            drawTexture(1f, 1f, 0f, 0f)

            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        }

        private fun drawEyeFromOu(left: Boolean, fromTopHalf: Boolean) {
            val viewport = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)

            val eyeWidth = viewport[2] / 2
            val eyeX = if (left) viewport[0] else viewport[0] + eyeWidth

            // Compute the per-eye target rect.
            //   - Auto (mode == 0): just fill the half-viewport, like the pre-button behaviour. Most
            //     stereo OU content is meant to be vertically stretched 2× on display, so a "do
            //     nothing" Auto looks correct for the common case.
            //   - Explicit aspect (16:9, 4:3, ...): letterbox the half-viewport down to that aspect.
            //     The per-eye native aspect for OU is sourceAspect × 2; we pass it as the fallback
            //     so getTargetAspectRatio can use it when needed, but we don't apply ×2 in Auto.
            val rect = if (resizeMode == 0) {
                FitRect(eyeX, viewport[1], eyeWidth, viewport[3])
            } else {
                val targetAspect = getTargetAspectRatio(resizeMode, perEyeAspectFromOu(videoAspectRatio))
                calculateFitRect(eyeX, viewport[1], eyeWidth, viewport[3], targetAspect)
            }

            val pad = (perEyePadFrac * rect.height).toInt().coerceAtMost(rect.height - 1)
            val yAdj = if (fromTopHalf) rect.y + pad else rect.y
            val hAdj = rect.height - pad

            GLES20.glViewport(rect.x, yAdj, rect.width, hAdj)

            val shift = if (left) leftEyeShiftNorm else rightEyeShiftNorm
            val base = if (fromTopHalf) 0.5f else 0f
            val offsetY = if (fromTopHalf) base - shift else base + shift

            drawTexture(1f, 0.5f, 0f, offsetY)

            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        }

        private fun drawEyeFromSbs(left: Boolean, useRightHalf: Boolean) {
            val viewport = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)

            val eyeWidth = viewport[2] / 2
            val eyeX = if (left) viewport[0] else viewport[0] + eyeWidth

            val rect = if (resizeMode == 0) {
                FitRect(eyeX, viewport[1], eyeWidth, viewport[3])
            } else {
                // SBS source per-eye native aspect is sourceAspect / 2.
                val targetAspect = getTargetAspectRatio(resizeMode, perEyeAspectFromSbs(videoAspectRatio))
                calculateFitRect(eyeX, viewport[1], eyeWidth, viewport[3], targetAspect)
            }

            GLES20.glViewport(rect.x, rect.y, rect.width, rect.height)

            val offsetX = if (useRightHalf) 0.5f else 0f
            drawTexture(0.5f, 1f, offsetX, 0f)

            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        }

        private fun drawTexture(scaleX: Float, scaleY: Float, offsetX: Float, offsetY: Float) {
            GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
            GLES20.glUniform2f(uScaleLoc, scaleX, scaleY)
            GLES20.glUniform2f(uOffsetLoc, offsetX, offsetY)
            GLES20.glUniform2f(uParallaxLoc, parallaxX, parallaxY)
            vertexData.position(0)
            GLES20.glEnableVertexAttribArray(aPosLoc)
            GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 16, vertexData)
            vertexData.position(2)
            GLES20.glEnableVertexAttribArray(aTexLoc)
            GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 16, vertexData)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun calculateFitRect(x: Int, y: Int, width: Int, height: Int, targetAspect: Float): FitRect {
            if (width <= 0 || height <= 0 || targetAspect <= 0f) {
                return FitRect(x, y, width, height)
            }
            val viewAspect = width.toFloat() / height.toFloat()
            return if (viewAspect > targetAspect) {
                val newWidth = (height * targetAspect).toInt().coerceAtLeast(1)
                val offsetX = (width - newWidth) / 2
                FitRect(x + offsetX, y, newWidth, height)
            } else {
                val newHeight = (width / targetAspect).toInt().coerceAtLeast(1)
                val offsetY = (height - newHeight) / 2
                FitRect(x, y + offsetY, width, newHeight)
            }
        }

        // For OU sources, per-eye image is full_width × half_height so its native aspect is 2× source aspect.
        private fun perEyeAspectFromOu(sourceAspect: Float): Float = sourceAspect * 2f

        // For SBS sources, per-eye image is half_width × full_height so its native aspect is source aspect / 2.
        private fun perEyeAspectFromSbs(sourceAspect: Float): Float = sourceAspect / 2f

        private fun getTargetAspectRatio(mode: Int, fallbackAspect: Float): Float = when (mode) {
            1 -> 16f / 9f
            2 -> 4f / 3f
            3 -> 21f / 9f
            4 -> 32f / 9f
            5 -> 1f / 1f
            6 -> 2.39f
            else -> fallbackAspect
        }

        fun updateResizeMode(mode: Int) {
            resizeMode = mode
        }

        fun setParallaxOffset(x: Float, y: Float) {
            parallaxX = x
            parallaxY = y
        }

        fun updateVideoAspectRatio(width: Int, height: Int) {
            if (width > 0 && height > 0) {
                videoAspectRatio = width.toFloat() / height.toFloat()
            }
        }

        fun setEyeShiftNormalized(left: Float, right: Float) {
            leftEyeShiftNorm = left
            rightEyeShiftNorm = right
        }

        override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
            // Called on Binder thread; request render on GL thread
            this@OuToSbsGlView.requestRender()
        }

        private fun createOesTexture(): Int {
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            return tex[0]
        }

        private fun buildProgram(vertSrc: String, fragSrc: String): Int {
            val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertSrc)
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vs)
            GLES20.glAttachShader(prog, fs)
            GLES20.glLinkProgram(prog)
            val status = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(prog)
                GLES20.glDeleteProgram(prog)
                throw RuntimeException("GL link error: $log")
            }
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            return prog
        }

        private fun compileShader(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw RuntimeException("GL compile error: $log")
            }
            return shader
        }
    }
}

private data class FitRect(val x: Int, val y: Int, val width: Int, val height: Int)

private fun floatBufferOf(vararg floats: Float): FloatBuffer =
    ByteBuffer.allocateDirect(floats.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floats)
            position(0)
        }

private const val VERT = """
attribute vec4 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;
void main() {
  gl_Position = aPosition;
  vTexCoord = aTexCoord;
}
"""

private const val FRAG = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTexCoord;
uniform samplerExternalOES uTexture;
uniform mat4 uTexMatrix;
uniform vec2 uScale;
uniform vec2 uOffset;
// "Lazy 3D" parallax: small per-frame texel shift driven from the goggles' IMU.
// Driven by HeadPoseTracker via OuToSbsGlView.setParallaxOffset(). Zero when the
// feature is off, so this shader is identical to the original in that case.
uniform vec2 uParallax;
void main() {
  // Apply SurfaceTexture transform first to account for decoder orientation,
  // then crop to top/bottom half in the transformed texture space
  vec2 tc = (uTexMatrix * vec4(vTexCoord, 0.0, 1.0)).xy;
  // Apply just enough overscan zoom to cover the current parallax magnitude so the
  // shifted picture stays edge-to-edge. When the IMU isn't active (uParallax == 0)
  // the factor is exactly 1.0 — i.e. behaviour is identical to the original shader.
  float pmag = length(uParallax);
  float overscan = 1.0 - clamp(pmag * 1.6, 0.0, 0.1);
  vec2 zoomed = (tc - 0.5) * overscan + 0.5;
  vec2 finalTc = zoomed * uScale + uOffset + uParallax * uScale;
  gl_FragColor = texture2D(uTexture, finalTc);
}
"""
