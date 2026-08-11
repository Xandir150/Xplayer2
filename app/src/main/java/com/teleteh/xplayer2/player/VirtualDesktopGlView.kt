package com.teleteh.xplayer2.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Choreographer
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

/**
 * GLSurfaceView that renders the PC Link desktop stream as a plane FIXED IN THE WORLD, counter-
 * rotated every frame by the local head orientation — the "virtual monitor floating in space"
 * view for XR glasses. Sibling of [OuToSbsGlView] with the same external contract: the decoder
 * ([PcStreamDecoder]) renders into the [Surface] this view hands out via
 * [setOnSurfaceReadyListener], wrapping a SurfaceTexture bound to an OES texture.
 *
 * What it draws, per eye (left/right half of the ultrawide SBS panel):
 * a [GRID_COLS]×[GRID_ROWS] subdivided plane at `canvasDistanceM` subtending
 * `canvasAngularWidthDeg` (geometry from [VirtualDesktopMath]), projected with a per-eye
 * symmetric frustum matched to the glasses' physical per-eye FOV ([DEFAULT_EYE_HFOV_DEG] — see
 * [VirtualDesktopMath.projectionMatrix] for why the vertical FOV lands near 23°), viewed through
 * the inverse of the head rotation. There is no camera-side stereo offset: for an `"sbs"` stream
 * the disparity is baked in by the server and each eye samples its half of the frame; a mono
 * stream goes to both eyes whole. Everything outside the plane clears to black — empty space
 * around the desktop.
 *
 * Head tracking comes from an [orientationProvider] returning `[yawDeg, pitchDeg, rollDeg]` in
 * [com.teleteh.xplayer2.data.glasses.HeadOrientationTracker]'s convention (yaw+ = left,
 * pitch+ = down, roll+ = tilt right; verified — see [VirtualDesktopMath]), or null when no IMU is
 * flowing. [recenter] captures the current yaw/pitch as the new "straight ahead".
 *
 * Render pacing — RENDERMODE_WHEN_DIRTY plus two explicit triggers, rather than
 * RENDERMODE_CONTINUOUSLY:
 *  * every decoded frame (SurfaceTexture's onFrameAvailable, same as [OuToSbsGlView]);
 *  * a Choreographer vsync callback that requests a render ONLY while the provider is actually
 *    delivering orientation samples.
 * With the IMU live this renders at panel rate exactly like CONTINUOUSLY would (the view matrix
 * changes every frame, so nothing less is acceptable); but when there is no IMU (non-XREAL
 * glasses, IMU not yet up, stream paused) it decays to rendering only on new video frames —
 * no GPU burned re-drawing a static scene. That idle saving is why WHEN_DIRTY won.
 */
class VirtualDesktopGlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer: DesktopRenderer

    /** Bits per colour channel of the chosen framebuffer: 10 (RGBA1010102) where supported, else 8. */
    private var fbBitsPerChannel = 8

    /** `[yaw, pitch, roll]` degrees, or null when head tracking isn't available right now. */
    @Volatile private var orientationProvider: (() -> FloatArray?)? = null

    private val choreographer = Choreographer.getInstance()
    private var vsyncActive = false
    private val vsyncCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!vsyncActive) return
            // Only spend a render on head motion when there IS head data; new video frames
            // schedule their own renders via onFrameAvailable.
            if (orientationProvider?.invoke() != null) requestRender()
            choreographer.postFrameCallback(this)
        }
    }

    init {
        // Same EGL setup as OuToSbsGlView (proven on-device): prefer a 10-bit framebuffer so
        // gradients (wallpapers!) don't band, ES3 context, TPDF dither in the shader.
        setEGLConfigChooser(object : EGLConfigChooser {
            override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
                val es3Bit = 0x40 // EGL_OPENGL_ES3_BIT_KHR
                fun choose(r: Int, g: Int, b: Int, a: Int): EGLConfig? {
                    val attrsList = intArrayOf(
                        EGL10.EGL_RED_SIZE, r, EGL10.EGL_GREEN_SIZE, g, EGL10.EGL_BLUE_SIZE, b,
                        EGL10.EGL_ALPHA_SIZE, a,
                        EGL10.EGL_RENDERABLE_TYPE, es3Bit,
                        EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT,
                        EGL10.EGL_NONE
                    )
                    val n = IntArray(1)
                    if (!egl.eglChooseConfig(display, attrsList, null, 0, n) || n[0] <= 0) return null
                    val cfgs = arrayOfNulls<EGLConfig>(n[0])
                    egl.eglChooseConfig(display, attrsList, cfgs, n[0], n)
                    val v = IntArray(1)
                    for (c in cfgs) {
                        if (c == null) continue
                        egl.eglGetConfigAttrib(display, c, EGL10.EGL_RED_SIZE, v)
                        if (v[0] == r) return c
                    }
                    return cfgs[0]
                }
                choose(10, 10, 10, 2)?.let { fbBitsPerChannel = 10; return it }
                fbBitsPerChannel = 8
                return choose(8, 8, 8, 8)
                    ?: throw IllegalStateException("No suitable RGBA8888 EGL config")
            }
        })
        setEGLContextClientVersion(3)
        renderer = DesktopRenderer()
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Same contract as [OuToSbsGlView.setOnSurfaceReadyListener]: fires on the main thread. */
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

    /** Head orientation source, polled once per rendered frame (volatile reads, any thread). */
    fun setOrientationProvider(provider: (() -> FloatArray?)?) {
        orientationProvider = provider
        requestRender()
    }

    /** The server's canvas geometry from its `config` message (protocol §2.2). */
    fun setCanvas(angularWidthDeg: Float, distanceM: Float) {
        renderer.canvasAngularWidthDeg = angularWidthDeg.coerceIn(10f, 140f)
        renderer.canvasDistanceM = distanceM.coerceIn(0.5f, 40f)
        renderer.geometryVersion++
        requestRender()
    }

    /** Whether the incoming frame is packed left|right SBS (per-eye halves) or mono (full frame). */
    fun setSourceIsSbs(sbs: Boolean) {
        renderer.sourceIsSbs = sbs
        renderer.geometryVersion++
        requestRender()
    }

    /** Coded frame size, for the canvas aspect (per-eye content is half the width when SBS). */
    fun setVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        renderer.videoWidth = width
        renderer.videoHeight = height
        renderer.geometryVersion++
        requestRender()
    }

    /**
     * Captures the current yaw/pitch as the new zero — the canvas jumps to dead ahead of wherever
     * the user is looking right now. Roll is never re-zeroed (the horizon stays the world's).
     * No-op (offsets cleared) when no head data is flowing.
     */
    fun recenter() {
        val o = orientationProvider?.invoke()
        renderer.yawZeroDeg = o?.get(0) ?: 0f
        renderer.pitchZeroDeg = o?.getOrNull(1) ?: 0f
        requestRender()
    }

    override fun onResume() {
        super.onResume()
        startVsync()
    }

    override fun onPause() {
        stopVsync()
        super.onPause()
    }

    override fun onDetachedFromWindow() {
        stopVsync()
        super.onDetachedFromWindow()
    }

    private fun startVsync() {
        if (vsyncActive) return
        vsyncActive = true
        choreographer.postFrameCallback(vsyncCallback)
    }

    private fun stopVsync() {
        vsyncActive = false
        choreographer.removeFrameCallback(vsyncCallback)
    }

    private inner class DesktopRenderer : Renderer, SurfaceTexture.OnFrameAvailableListener {
        private var textureId = 0
        private var surfaceTexture: SurfaceTexture? = null
        var surface: Surface? = null
            private set
        var onSurfaceReady: ((Surface) -> Unit)? = null

        // Stream/canvas parameters (written from the main thread, read on the GL thread).
        @Volatile var canvasAngularWidthDeg = 45f
        @Volatile var canvasDistanceM = 3f
        @Volatile var sourceIsSbs = false
        @Volatile var videoWidth = 0
        @Volatile var videoHeight = 0
        /** Bumped on any change above; the GL thread rebuilds the vertex buffer when it differs. */
        @Volatile var geometryVersion = 0
        /** Re-center offsets (see [recenter]). */
        @Volatile var yawZeroDeg = 0f
        @Volatile var pitchZeroDeg = 0f

        private var builtGeometryVersion = -1

        private var program = 0
        private var aPosLoc = 0
        private var aTexLoc = 0
        private var uMvpLoc = 0
        private var uTexLoc = 0
        private var uTexMatrixLoc = 0
        private var uScaleLoc = 0
        private var uOffsetLoc = 0

        private val texMatrix = FloatArray(16)
        private val viewProj = FloatArray(16)
        private var projection: FloatArray = VirtualDesktopMath.identity()
        private var projForWidth = 0
        private var projForHeight = 0
        private var surfaceWidth = 0
        private var surfaceHeight = 0

        private val vertexBuffer: FloatBuffer = ByteBuffer
            .allocateDirect((GRID_COLS + 1) * (GRID_ROWS + 1) * 5 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        private val indexBuffer: ShortBuffer = ByteBuffer
            .allocateDirect(GRID_COLS * GRID_ROWS * 6 * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
        private var indexCount = 0

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = buildProgram(VERT, FRAG)
            aPosLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aTexLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
            uMvpLoc = GLES20.glGetUniformLocation(program, "uMvp")
            uTexLoc = GLES20.glGetUniformLocation(program, "uTexture")
            uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
            uScaleLoc = GLES20.glGetUniformLocation(program, "uScale")
            uOffsetLoc = GLES20.glGetUniformLocation(program, "uOffset")

            val ditherAmp = if (fbBitsPerChannel >= 10) 1f / 1023f else 1f / 255f
            android.util.Log.i(TAG, "GL framebuffer = $fbBitsPerChannel-bit/channel")
            GLES20.glUseProgram(program)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uDitherAmp"), ditherAmp)
            GLES20.glUseProgram(0)

            // Index buffer never changes shape — fill it once per context.
            indexBuffer.clear()
            val indices = VirtualDesktopMath.planeIndices(GRID_COLS, GRID_ROWS)
            indexBuffer.put(indices)
            indexBuffer.position(0)
            indexCount = indices.size
            builtGeometryVersion = -1        // vertex data must be rebuilt in the new context
            projForWidth = 0; projForHeight = 0

            textureId = createOesTexture()
            surfaceTexture = SurfaceTexture(textureId).also {
                it.setOnFrameAvailableListener(this)
            }
            surface = Surface(surfaceTexture)
            val surf = surface!!
            Handler(Looper.getMainLooper()).post {
                onSurfaceReady?.invoke(surf)
            }

            GLES20.glDisable(GLES20.GL_DEPTH_TEST)   // single plane; nothing to sort
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glClearColor(0f, 0f, 0f, 1f)      // space around the desktop = black
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceWidth = width
            surfaceHeight = height
            GLES20.glViewport(0, 0, width, height)
        }

        private fun ensureGeometry() {
            val version = geometryVersion
            if (version == builtGeometryVersion) return
            builtGeometryVersion = version
            val aspect = VirtualDesktopMath.contentAspect(videoWidth, videoHeight, sourceIsSbs)
            val verts = VirtualDesktopMath.planeVertices(
                canvasAngularWidthDeg, canvasDistanceM, aspect, GRID_COLS, GRID_ROWS
            )
            vertexBuffer.clear()
            vertexBuffer.put(verts)
            vertexBuffer.position(0)
        }

        private fun ensureProjection() {
            val eyeW = surfaceWidth / 2
            if (eyeW == projForWidth && surfaceHeight == projForHeight) return
            projForWidth = eyeW
            projForHeight = surfaceHeight
            val eyeAspect = if (surfaceHeight > 0) eyeW.toFloat() / surfaceHeight else 16f / 9f
            projection = VirtualDesktopMath.projectionMatrix(DEFAULT_EYE_HFOV_DEG, eyeAspect)
            android.util.Log.i(
                TAG,
                "Per-eye frustum: ${eyeW}x$surfaceHeight, hFov=${DEFAULT_EYE_HFOV_DEG}°, vFov=" +
                    "%.1f°".format(VirtualDesktopMath.verticalFovDeg(DEFAULT_EYE_HFOV_DEG, eyeAspect))
            )
        }

        override fun onDrawFrame(gl: GL10?) {
            surfaceTexture?.let {
                it.updateTexImage()
                it.getTransformMatrix(texMatrix)
            }

            if (surfaceWidth > 0 && surfaceHeight > 0) {
                GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            ensureGeometry()
            ensureProjection()

            // World-fixed: the view is the inverse of the head pose; identity (head-fixed plane
            // dead ahead) until the IMU delivers its first sample.
            val o = orientationProvider?.invoke()
            val view = if (o != null && o.size >= 3) {
                VirtualDesktopMath.viewMatrix(o[0], o[1], o[2], yawZeroDeg, pitchZeroDeg)
            } else {
                VirtualDesktopMath.identity()
            }
            VirtualDesktopMath.multiplyMM(viewProj, projection, view)

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(uTexLoc, 0)
            GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, viewProj, 0)
            GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

            vertexBuffer.position(0)
            GLES20.glEnableVertexAttribArray(aPosLoc)
            GLES20.glVertexAttribPointer(aPosLoc, 3, GLES20.GL_FLOAT, false, 20, vertexBuffer)
            vertexBuffer.position(3)
            GLES20.glEnableVertexAttribArray(aTexLoc)
            GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 20, vertexBuffer)

            val sbs = sourceIsSbs
            val eyeW = surfaceWidth / 2
            for (eye in 0..1) {
                GLES20.glViewport(eye * eyeW, 0, eyeW, surfaceHeight)
                val crop = VirtualDesktopMath.eyeTexTransform(sbs, rightEye = eye == 1)
                GLES20.glUniform2f(uScaleLoc, crop[0], 1f)
                GLES20.glUniform2f(uOffsetLoc, crop[1], 0f)
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)
            }
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)

            GLES20.glDisableVertexAttribArray(aPosLoc)
            GLES20.glDisableVertexAttribArray(aTexLoc)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        }

        override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
            this@VirtualDesktopGlView.requestRender()
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

    companion object {
        private const val TAG = "VirtualDesktopGlView"

        /**
         * Physical per-eye HORIZONTAL FOV assumed for the frustum. The XREAL Air family (and the
         * near-identical RayNeo/VITURE birdbaths) quote ≈46° diagonal per eye at 16:9, which is
         * ≈40° horizontal / ≈23° vertical in tan space — and matching the physical FOV is what
         * makes 1° of head turn move the picture by exactly 1° of panel, i.e. what pins the canvas
         * to the world. Tune here if a device family measurably differs.
         */
        const val DEFAULT_EYE_HFOV_DEG = 40f

        /**
         * Plane tessellation. Flat today, so 1×1 would render identically — the subdivision exists
         * so a cylindrical screen bend later is a vertex-shader tweak on existing geometry.
         */
        const val GRID_COLS = 32
        const val GRID_ROWS = 18
    }
}

// Vertex shader: places the world-space plane through view+projection, and computes the video
// texture coordinate — SurfaceTexture transform first, then the per-eye horizontal crop, exactly
// like OuToSbsGlView's fragment path. Both transforms are affine, so per-vertex evaluation with
// linear interpolation is exact.
private const val VERT = """
uniform mat4 uMvp;
uniform mat4 uTexMatrix;
uniform vec2 uScale;
uniform vec2 uOffset;
attribute vec4 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;
void main() {
  gl_Position = uMvp * aPosition;
  vec2 tc = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
  vTexCoord = tc * uScale + uOffset;
}
"""

// Fragment shader: sample the decoded desktop + the same triangular-PDF dither as OuToSbsGlView
// (desktops are full of smooth gradients — wallpapers band exactly like skies do).
private const val FRAG = """
#extension GL_OES_EGL_image_external : require
precision highp float;
varying vec2 vTexCoord;
uniform samplerExternalOES uTexture;
uniform float uDitherAmp;
float h21(vec2 p) {
  vec3 q = fract(vec3(p.xyx) * 0.1031);
  q += dot(q, q.yzx + 33.33);
  return fract((q.x + q.y) * q.z);
}
vec3 ditherTPDF(vec2 fc) {
  vec3 a = vec3(h21(fc), h21(fc + 11.3), h21(fc + 23.7));
  vec3 b = vec3(h21(fc + 5.1), h21(fc + 17.9), h21(fc + 31.5));
  return a + b - 1.0;
}
void main() {
  vec3 col = texture2D(uTexture, vTexCoord).rgb;
  gl_FragColor = vec4(col + ditherTPDF(gl_FragCoord.xy) * uDitherAmp, 1.0);
}
"""
