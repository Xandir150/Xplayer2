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
 * and renders OU (Over-Under) frames as SBS (Side-By-Side) on screen when enabled.
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
            // Ensure callback is on main thread
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
        // Clamp to safe range then forward to renderer on GL thread
        val l = left.coerceIn(-0.25f, 0.25f)
        val r = right.coerceIn(-0.25f, 0.25f)
        queueEvent {
            renderer.setEyeShiftNormalized(l, r)
        }
        requestRender()
    }

    /**
     * Convenience: set per-eye vertical shift in pixels relative to a reference full-frame height.
     */
    fun setEyeVerticalShiftPx(leftPx: Float, rightPx: Float, referenceHeightPx: Float) {
        if (referenceHeightPx <= 0f) return
        setEyeVerticalShiftNormalized(leftPx / referenceHeightPx, rightPx / referenceHeightPx)
    }

    /**
     * Apply opposite shifts to left/right eyes with a single positive amount (in pixels):
     * bottom-half eye goes DOWN, top-half eye goes UP. Respects current swapEyes mapping.
     */
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
     * The same positive amount is applied, but for the eye sampling the top half the bar is added on TOP,
     * and for the eye sampling the bottom half the bar is added on BOTTOM. This preserves 16:9 per eye
     * when the OU source lacks top/bottom black bars.
     */
    fun setPerEyeLetterboxPx(amountPx: Float, referenceHeightPx: Float) {
        if (referenceHeightPx <= 0f) return
        val frac = (amountPx / referenceHeightPx).coerceIn(0f, 0.25f)
        queueEvent {
            renderer.perEyePadFrac = frac
        }
        requestRender()
    }

    private inner class OuToSbsRenderer : Renderer, SurfaceTexture.OnFrameAvailableListener {
        private var textureId: Int = 0
        private var surfaceTexture: SurfaceTexture? = null
        var surface: Surface? = null
            private set

        var onSurfaceReady: ((Surface) -> Unit)? = null
        val sbsEnabled = AtomicBoolean(false)
        val swapEyes = AtomicBoolean(false)
        val duplicateMonoToSbs = AtomicBoolean(false)

        private var program = 0
        private var aPosLoc = 0
        private var aTexLoc = 0
        private var uTexLoc = 0
        private var uTexMatrixLoc = 0
        private var uScaleLoc = 0
        private var uOffsetLoc = 0
        // Normalized per-eye vertical shift (in full texture space, 0..1). Positive value intends to lower the image on screen.
        // These are applied symmetrically depending on whether the eye samples from top or bottom half.
        @Volatile private var leftEyeShiftNorm: Float = 0f
        @Volatile private var rightEyeShiftNorm: Float = 0f
        // Fraction of per-eye vertical letterbox relative to full-frame height (0..~0.25)
        @Volatile var perEyePadFrac: Float = 0f
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

            vertexData.position(0)
            GLES20.glEnableVertexAttribArray(aPosLoc)
            GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 16, vertexData)

            vertexData.position(2)
            GLES20.glEnableVertexAttribArray(aTexLoc)
            GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 16, vertexData)

            if (sbsEnabled.get()) {
                // Map OU -> SBS. Default: Left <- BOTTOM, Right <- TOP
                val swap = swapEyes.get()
                val leftFromTop = if (swap) true else false
                val rightFromTop = if (swap) false else true
                drawHalf(left = true, fromTopHalf = leftFromTop)
                drawHalf(left = false, fromTopHalf = rightFromTop)
            } else {
                // Mono: either full screen, or duplicate into left/right halves for stereo displays
                if (duplicateMonoToSbs.get()) {
                    drawMonoIntoHalf(left = true)
                    drawMonoIntoHalf(left = false)
                } else {
                    drawFull()
                }
            }

            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        }

        private fun drawFull() {
            // Use full texture (scale=1,1 offset=0,0)
            GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
            GLES20.glUniform2f(uScaleLoc, 1f, 1f)
            GLES20.glUniform2f(uOffsetLoc, 0f, 0f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun drawMonoIntoHalf(left: Boolean) {
            val viewport = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)
            val x = if (left) viewport[0] else viewport[0] + viewport[2] / 2
            val y = viewport[1]
            val w = viewport[2] / 2
            val h = viewport[3]
            // Draw full texture into the half viewport
            GLES20.glViewport(x, y, w, h)
            GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
            GLES20.glUniform2f(uScaleLoc, 1f, 1f)
            GLES20.glUniform2f(uOffsetLoc, 0f, 0f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        }

        private fun drawHalf(left: Boolean, fromTopHalf: Boolean) {
            // Adjust viewport to left or right half
            // Viewport will be set by caller via glViewport; here we change temporarily
            // We need current viewport size; since we don't have it, compute via glGetIntegerv
            val viewport = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)
            val x = if (left) viewport[0] else viewport[0] + viewport[2] / 2
            val y = viewport[1]
            val w = viewport[2] / 2
            val h = viewport[3]
            // Apply per-eye letterbox by shrinking the half-viewport vertically and anchoring:
            // - Top-half eye: anchor to TOP (bar appears at BOTTOM)
            // - Bottom-half eye: anchor to BOTTOM (bar appears at TOP)
            // Use provided per-eye pad fraction (relative to source half height) scaled to current half-viewport
            val pad = (perEyePadFrac * h).toInt().coerceAtMost(h - 1)
            // Top-half anchored to top: keep top edge, so shift y up by pad
            // Bottom-half anchored to bottom: keep bottom edge, so y stays
            val yAdj = if (fromTopHalf) y + pad else y
            val hAdj = h - pad
            GLES20.glViewport(x, yAdj, w, hAdj)

            // Apply SurfaceTexture transform and crop to top/bottom half via uniforms
            // With v origin at bottom: top half starts at 0.5, bottom half at 0.0
            GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
            GLES20.glUniform2f(uScaleLoc, 1f, 0.5f)
            // Choose per-eye vertical shift. Convention: positive shift lowers the image on screen.
            val shift = if (left) leftEyeShiftNorm else rightEyeShiftNorm
            // For bottom half, increasing offset samples higher in the source, making the image appear lower on screen.
            // For top half, decreasing offset samples lower in the source, likewise lowering image on screen.
            val base = if (fromTopHalf) 0.5f else 0f
            val offsetY = if (fromTopHalf) base - shift else base + shift
            GLES20.glUniform2f(uOffsetLoc, 0f, offsetY)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // Restore full viewport for next draw step
            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
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
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
            )
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
void main() {
  // Apply SurfaceTexture transform first to account for decoder orientation,
  // then crop to top/bottom half in the transformed texture space
  vec2 tc = (uTexMatrix * vec4(vTexCoord, 0.0, 1.0)).xy;
  tc = tc * uScale + uOffset;
  gl_FragColor = texture2D(uTexture, tc);
}
"""
