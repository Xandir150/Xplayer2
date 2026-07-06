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
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
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

    /** Bits per colour channel of the chosen framebuffer: 10 (RGBA1010102) where supported, else 8. */
    private var fbBitsPerChannel = 8

    init {
        // Prefer a 10-bit framebuffer (RGBA1010102) so HDR/10-bit sources aren't truncated to
        // 8 bits, falling back to RGBA8888. Without an explicit chooser some drivers default to
        // RGB565 (heavy banding). Combined with an ES3 context (guaranteed highp fragment) and
        // the shader dithering below, this removes the colour banding on smooth gradients —
        // the visible quality gap on the glasses/SBS path vs. the direct SurfaceView path.
        setEGLConfigChooser(object : EGLConfigChooser {
            override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
                val es3Bit = 0x40 // EGL_OPENGL_ES3_BIT_KHR
                fun choose(r: Int, g: Int, b: Int, a: Int): EGLConfig? {
                    val attrs = intArrayOf(
                        EGL10.EGL_RED_SIZE, r, EGL10.EGL_GREEN_SIZE, g, EGL10.EGL_BLUE_SIZE, b,
                        EGL10.EGL_ALPHA_SIZE, a,
                        EGL10.EGL_RENDERABLE_TYPE, es3Bit,
                        EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT,
                        EGL10.EGL_NONE
                    )
                    val n = IntArray(1)
                    if (!egl.eglChooseConfig(display, attrs, null, 0, n) || n[0] <= 0) return null
                    val cfgs = arrayOfNulls<EGLConfig>(n[0])
                    egl.eglChooseConfig(display, attrs, cfgs, n[0], n)
                    val v = IntArray(1)
                    for (c in cfgs) {
                        if (c == null) continue
                        egl.eglGetConfigAttrib(display, c, EGL10.EGL_RED_SIZE, v)
                        if (v[0] == r) return c // exact match (avoid an 8-bit config when asking for 10)
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
     * Toggle the depth-based stereo synthesis path. When enabled and a depth map has been
     * pushed via [setDepthMap], the renderer draws the source twice (one per eye) with a
     * per-pixel UV shift derived from depth — synthesising a stereo pair from a flat
     * 2D source. Inspired by nagadomi/nunif (iw3) backward-warp.
     */
    fun setLazy3dStereoEnabled(enabled: Boolean) {
        renderer.lazy3dStereoEnabled.set(enabled)
        requestRender()
    }

    /**
     * Tune the depth-to-disparity mapping.
     * @param divergence overall 3D strength as fraction of frame width (0..0.05 typical)
     * @param convergence screen-plane position in depth (0..1), pixels with d > convergence
     * appear in front of the screen, d < convergence behind it. 0.5 default.
     */
    fun setStereoParams(divergence: Float, convergence: Float) {
        renderer.stereoDivergence = divergence.coerceIn(0f, 0.1f)
        renderer.stereoConvergence = convergence.coerceIn(0f, 1f)
    }

    /**
     * Push a normalised depth map for the upcoming frames. [depthBytes] must be
     * width*height bytes, each in 0..255 (0 = farthest, 255 = nearest). Upload happens on
     * the GL thread; safe to call from any thread.
     */
    fun setDepthMap(depthBytes: ByteArray, width: Int, height: Int) {
        renderer.queueDepthUpdate(depthBytes, width, height)
        requestRender()
    }

    /**
     * Register a callback that fires when the renderer has decoded a fresh source frame
     * and made a downsampled RGBA snapshot available for depth inference. Invocations are
     * paced internally (default ~30 Hz) to keep glReadPixels off the GL critical path.
     *
     * Callback fires on the GL thread.
     */
    fun setOnFrameReadbackListener(listener: ((IntArray, Int, Int, Long) -> Unit)?) {
        renderer.frameReadbackListener = listener
    }

    /**
     * Depth-readback pacing override: minimum interval between the source snapshots handed to
     * the depth worker. The thermal governor lengthens this when the device heats up — fewer
     * readbacks mean fewer NPU/GPU inferences (the worker is latest-only, so nothing queues),
     * while video keeps rendering at full rate with the most recent depth map.
     * [Long.MAX_VALUE] pauses readback entirely (last depth map stays frozen). Clamped so it
     * can never run faster than the default ~30 Hz.
     */
    fun setDepthReadbackIntervalNanos(nanos: Long) {
        renderer.readbackIntervalNanos = nanos.coerceAtLeast(READBACK_INTERVAL_NANOS)
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
        val lazy3dStereoEnabled = AtomicBoolean(false)
        @Volatile var stereoDivergence: Float = 0.013f   // fallback; PlayerActivity sets LAZY3D_DIVERGENCE
        @Volatile var stereoConvergence: Float = 0.5f
        @Volatile var frameReadbackListener: ((IntArray, Int, Int, Long) -> Unit)? = null
        // Current depth-readback pacing; the thermal governor raises it (up to MAX_VALUE =
        // paused) to cool the device. Written from the main thread, read on the GL thread.
        @Volatile var readbackIntervalNanos: Long = READBACK_INTERVAL_NANOS

        private var program = 0
        private var aPosLoc = 0
        private var aTexLoc = 0
        private var uTexLoc = 0
        private var uTexMatrixLoc = 0
        private var uScaleLoc = 0
        private var uOffsetLoc = 0

        // --- Lazy 3D resources ---
        // Second shader program for stereo-from-depth (samples source + depth, shifts UV
        // per pixel by `(depth - convergence) * divergence * eyeSign`).
        private var lazy3dProgram = 0
        private var l3dPosLoc = 0
        private var l3dTexLoc = 0
        private var l3dSourceLoc = 0
        private var l3dDepthLoc = 0
        private var l3dTexMatrixLoc = 0
        private var l3dDivergenceLoc = 0
        private var l3dConvergenceLoc = 0
        private var l3dEyeSignLoc = 0
        private var l3dDepthTexelLoc = 0

        // Depth-refine pass: a joint-bilateral + asymmetric smooth of the raw depth, using the
        // low-res source snapshot (readbackTextureId) as an edge guide. Snaps fuzzy MiDaS depth edges
        // onto real image edges (kills warp halos) and smooths more vertically than horizontally (so
        // depth discontinuities bend instead of tear). Output feeds the warp instead of the raw depth.
        private var bilateralProgram = 0
        private var blPosLoc = 0
        private var blTexLoc = 0
        private var blDepthLoc = 0
        private var blGuideLoc = 0
        private var blTexelLoc = 0
        private var procDepthTex: Int = 0
        private var procDepthFbo: Int = 0
        private var procW: Int = 0
        private var procH: Int = 0

        // Single-channel depth texture (re-uploaded each new inference, default 256x256).
        private var depthTextureId: Int = 0
        private var depthTexWidth: Int = 0
        private var depthTexHeight: Int = 0
        @Volatile private var pendingDepthBytes: ByteArray? = null
        @Volatile private var pendingDepthWidth: Int = 0
        @Volatile private var pendingDepthHeight: Int = 0

        // Off-screen FBO used to snapshot the decoded source at low res (256x256) for the
        // depth model. Avoids reading back from the visible framebuffer (slow + wrong size).
        private var readbackFbo: Int = 0
        private var readbackTextureId: Int = 0
        private val readbackBuffer: ByteBuffer = ByteBuffer
            .allocateDirect(READBACK_SIZE * READBACK_SIZE * 4)
            .order(ByteOrder.nativeOrder())
        private val readbackPixels: IntArray = IntArray(READBACK_SIZE * READBACK_SIZE)
        private var lastReadbackNanos: Long = 0L

        @Volatile private var leftEyeShiftNorm: Float = 0f
        @Volatile private var rightEyeShiftNorm: Float = 0f
        @Volatile var perEyePadFrac: Float = 0f
        @Volatile private var resizeMode: Int = 0
        @Volatile private var videoAspectRatio: Float = 16f / 9f
        private val texMatrix = FloatArray(16)
        // Current on-screen surface size, so each frame can re-assert the full viewport before
        // the visible draw (the depth readback FBO render leaves the viewport at 256×256).
        private var surfaceWidth: Int = 0
        private var surfaceHeight: Int = 0

        // Fullscreen quad (two triangles)
        private val vertexData: FloatBuffer = floatBufferOf(
            // X,  Y,   U,  V (v=0 at bottom, v=1 at top)
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // The EGL context was (re)created — every GL object id from any previous context is now
            // invalid. Programs/textures below are rebuilt explicitly; zero the LAZILY-created depth
            // ids so they get reallocated on next use instead of a stale id surviving a pause/resume
            // (which would make the size-unchanged fast-paths render to dead handles).
            depthTextureId = 0; depthTexWidth = 0; depthTexHeight = 0
            procDepthTex = 0; procDepthFbo = 0; procW = 0; procH = 0

            program = buildProgram(VERT, FRAG)
            aPosLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aTexLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
            uTexLoc = GLES20.glGetUniformLocation(program, "uTexture")
            uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
            uScaleLoc = GLES20.glGetUniformLocation(program, "uScale")
            uOffsetLoc = GLES20.glGetUniformLocation(program, "uOffset")

            // Lazy 3D shader program — separate so the hot non-3D path is undisturbed.
            lazy3dProgram = buildProgram(VERT, FRAG_LAZY3D)
            l3dPosLoc = GLES20.glGetAttribLocation(lazy3dProgram, "aPosition")
            l3dTexLoc = GLES20.glGetAttribLocation(lazy3dProgram, "aTexCoord")
            l3dSourceLoc = GLES20.glGetUniformLocation(lazy3dProgram, "uSource")
            l3dDepthLoc = GLES20.glGetUniformLocation(lazy3dProgram, "uDepth")
            l3dTexMatrixLoc = GLES20.glGetUniformLocation(lazy3dProgram, "uTexMatrix")
            l3dDivergenceLoc = GLES20.glGetUniformLocation(lazy3dProgram, "uDivergence")
            l3dConvergenceLoc = GLES20.glGetUniformLocation(lazy3dProgram, "uConvergence")
            l3dEyeSignLoc = GLES20.glGetUniformLocation(lazy3dProgram, "uEyeSign")
            l3dDepthTexelLoc = GLES20.glGetUniformLocation(lazy3dProgram, "uDepthTexel")

            // Depth-refine (joint-bilateral) program — runs at depth resolution into an off-screen FBO.
            bilateralProgram = buildProgram(VERT, FRAG_DEPTH_REFINE)
            blPosLoc = GLES20.glGetAttribLocation(bilateralProgram, "aPosition")
            blTexLoc = GLES20.glGetAttribLocation(bilateralProgram, "aTexCoord")
            blDepthLoc = GLES20.glGetUniformLocation(bilateralProgram, "uDepth")
            blGuideLoc = GLES20.glGetUniformLocation(bilateralProgram, "uGuide")
            blTexelLoc = GLES20.glGetUniformLocation(bilateralProgram, "uTexel")

            // Dither amplitude = one framebuffer LSB. Set once per program (uniform state persists).
            val ditherAmp = if (fbBitsPerChannel >= 10) 1f / 1023f else 1f / 255f
            android.util.Log.i("OuToSbsGlView", "GL framebuffer = $fbBitsPerChannel-bit/channel (dither LSB $ditherAmp)")
            GLES20.glUseProgram(program)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uDitherAmp"), ditherAmp)
            GLES20.glUseProgram(lazy3dProgram)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(lazy3dProgram, "uDitherAmp"), ditherAmp)
            GLES20.glUseProgram(0)

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

            // Off-screen FBO for the per-frame readback used to feed the depth model.
            // Allocated once, reused every readback.
            setupReadbackFbo()

            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
        }

        private fun setupReadbackFbo() {
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            readbackTextureId = tex[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, readbackTextureId)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, READBACK_SIZE, READBACK_SIZE, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val fbo = IntArray(1)
            GLES20.glGenFramebuffers(1, fbo, 0)
            readbackFbo = fbo[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, readbackFbo)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, readbackTextureId, 0
            )
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                android.util.Log.w("OuToSbsGlView", "Readback FBO not complete: 0x${status.toString(16)}")
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }

        /**
         * Queue a depth-map upload from any thread; the GL thread picks it up on the
         * next [onDrawFrame] before the lazy3d draw call. We don't allocate / bind GL
         * objects here because there's no guarantee we're on the GL thread.
         */
        fun queueDepthUpdate(bytes: ByteArray, w: Int, h: Int) {
            pendingDepthBytes = bytes
            pendingDepthWidth = w
            pendingDepthHeight = h
        }

        /** Uploads any pending depth map to [depthTextureId]; returns true iff a new map was uploaded. */
        private fun consumePendingDepth(): Boolean {
            val bytes = pendingDepthBytes ?: return false
            val w = pendingDepthWidth
            val h = pendingDepthHeight
            pendingDepthBytes = null
            if (w <= 0 || h <= 0 || bytes.size < w * h) return false

            if (depthTextureId == 0 || w != depthTexWidth || h != depthTexHeight) {
                if (depthTextureId != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(depthTextureId), 0)
                }
                val t = IntArray(1)
                GLES20.glGenTextures(1, t, 0)
                depthTextureId = t[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextureId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                depthTexWidth = w
                depthTexHeight = h
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, w, h, 0,
                    GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(bytes)
                )
            } else {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextureId)
                GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h,
                    GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(bytes)
                )
            }
            return true
        }

        /** (Re)allocate the depth-refine FBO + its RGBA8 colour texture at [w]x[h]. */
        private fun ensureProcDepth(w: Int, h: Int) {
            if (procDepthFbo != 0 && procW == w && procH == h) return
            if (procDepthTex != 0) { GLES20.glDeleteTextures(1, intArrayOf(procDepthTex), 0); procDepthTex = 0 }
            if (procDepthFbo != 0) { GLES20.glDeleteFramebuffers(1, intArrayOf(procDepthFbo), 0); procDepthFbo = 0 }
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            procDepthTex = t[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, procDepthTex)
            // RGBA8 (LUMINANCE isn't colour-renderable as an FBO attachment); we read/write .r only.
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val f = IntArray(1)
            GLES20.glGenFramebuffers(1, f, 0)
            procDepthFbo = f[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, procDepthFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, procDepthTex, 0)
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                android.util.Log.w("OuToSbsGlView", "Depth-refine FBO incomplete: 0x${status.toString(16)} — using raw depth")
                GLES20.glDeleteTextures(1, intArrayOf(procDepthTex), 0); procDepthTex = 0
                GLES20.glDeleteFramebuffers(1, intArrayOf(procDepthFbo), 0); procDepthFbo = 0
                procW = 0; procH = 0
                return
            }
            procW = w; procH = h
        }

        /**
         * Joint-bilateral + asymmetric smooth of the freshly-uploaded raw depth into [procDepthTex],
         * guided by the low-res source snapshot. Run only when a new depth map arrived. Self-contained:
         * binds its own FBO/program/textures and restores the viewport + default texture unit after.
         */
        private fun refineDepth() {
            if (bilateralProgram == 0 || depthTextureId == 0 || readbackTextureId == 0) return
            if (depthTexWidth <= 0 || depthTexHeight <= 0) return
            ensureProcDepth(depthTexWidth, depthTexHeight)
            if (procDepthFbo == 0) return

            val savedViewport = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, savedViewport, 0)
            val savedProgram = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, savedProgram, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, procDepthFbo)
            GLES20.glViewport(0, 0, procW, procH)
            GLES20.glUseProgram(bilateralProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextureId)
            GLES20.glUniform1i(blDepthLoc, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, readbackTextureId)
            GLES20.glUniform1i(blGuideLoc, 1)
            GLES20.glUniform2f(blTexelLoc, 1f / procW, 1f / procH)
            vertexData.position(0)
            GLES20.glEnableVertexAttribArray(blPosLoc)
            GLES20.glVertexAttribPointer(blPosLoc, 2, GLES20.GL_FLOAT, false, 16, vertexData)
            vertexData.position(2)
            GLES20.glEnableVertexAttribArray(blTexLoc)
            GLES20.glVertexAttribPointer(blTexLoc, 2, GLES20.GL_FLOAT, false, 16, vertexData)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            // Leave GL state exactly as we found it so the visible draw is never affected
            // (truly self-contained): disable our attrib arrays, unbind both 2D units, restore the
            // framebuffer, viewport and program.
            GLES20.glDisableVertexAttribArray(blPosLoc)
            GLES20.glDisableVertexAttribArray(blTexLoc)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)        // unit 1 (currently active)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)        // unit 0
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3])
            GLES20.glUseProgram(savedProgram[0])
        }

        /**
         * Render the current SurfaceTexture frame into the small readback FBO, then
         * glReadPixels into a Java IntArray and hand it to the listener. Paced — at most
         * one capture every [readbackIntervalNanos] (default ≈30 Hz; lengthened by the
         * thermal governor when the device runs hot). Called from onDrawFrame before the
         * visible draw, so it never delays a real frame more than the readback itself
         * takes (~1 ms at 256x256).
         */
        private fun maybeReadbackFrame() {
            val listener = frameReadbackListener ?: return
            val now = System.nanoTime()
            if (now - lastReadbackNanos < readbackIntervalNanos) return
            lastReadbackNanos = now

            // Save the on-screen viewport: rendering into the readback FBO changes the (global)
            // GL viewport to 256×256, and if we don't put it back the following on-screen draw
            // squeezes the whole picture into a tiny square at the bottom-left.
            val savedViewport = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, savedViewport, 0)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, readbackFbo)
            GLES20.glViewport(0, 0, READBACK_SIZE, READBACK_SIZE)
            // Re-use the main passthrough shader (uniform uScale=1, uOffset=0)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(uTexLoc, 0)
            GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
            GLES20.glUniform2f(uScaleLoc, 1f, 1f)
            GLES20.glUniform2f(uOffsetLoc, 0f, 0f)
            vertexData.position(0)
            GLES20.glEnableVertexAttribArray(aPosLoc)
            GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 16, vertexData)
            vertexData.position(2)
            GLES20.glEnableVertexAttribArray(aTexLoc)
            GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 16, vertexData)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            readbackBuffer.rewind()
            GLES20.glReadPixels(
                0, 0, READBACK_SIZE, READBACK_SIZE,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readbackBuffer
            )
            readbackBuffer.rewind()
            for (i in readbackPixels.indices) {
                val r = readbackBuffer.get().toInt() and 0xFF
                val g = readbackBuffer.get().toInt() and 0xFF
                val b = readbackBuffer.get().toInt() and 0xFF
                val a = readbackBuffer.get().toInt() and 0xFF
                readbackPixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            // Restore the on-screen viewport clobbered by the FBO render above.
            GLES20.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3])

            // Defensive copy so the worker can hold this snapshot while the next readback
            // overwrites our own internal `readbackPixels`.
            listener.invoke(readbackPixels.copyOf(), READBACK_SIZE, READBACK_SIZE, now)
        }

        /**
         * Stereo from depth: split the viewport, draw each eye via the lazy3d shader, which
         * shifts source UVs by `(depth - convergence) * divergence * eyeSign`. The depth
         * texture must have been uploaded via [queueDepthUpdate] at least once.
         */
        private fun drawStereoFromDepth() {
            val viewport = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)
            val eyeW = viewport[2] / 2

            // Per-eye letterbox so the aspect-ratio control works under Lazy 3D too. The lazy3d
            // quad spans 0..1 UV, so drawing into a smaller rect just scales the whole source +
            // depth into it; the uncovered margin stays black from the per-frame clear. Auto
            // (resizeMode 0) keeps filling the full half, as before.
            val targetAspect = if (resizeMode == 0) 0f else getTargetAspectRatio(resizeMode, videoAspectRatio)
            val leftRect = if (resizeMode == 0) FitRect(viewport[0], viewport[1], eyeW, viewport[3])
                else calculateFitRect(viewport[0], viewport[1], eyeW, viewport[3], targetAspect)
            val rightRect = if (resizeMode == 0) FitRect(viewport[0] + eyeW, viewport[1], eyeW, viewport[3])
                else calculateFitRect(viewport[0] + eyeW, viewport[1], eyeW, viewport[3], targetAspect)

            GLES20.glUseProgram(lazy3dProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(l3dSourceLoc, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            // Prefer the joint-bilateral-refined depth; fall back to the raw upload until it exists.
            val depthTex = if (procDepthTex != 0) procDepthTex else depthTextureId
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTex)
            GLES20.glUniform1i(l3dDepthLoc, 1)
            GLES20.glUniformMatrix4fv(l3dTexMatrixLoc, 1, false, texMatrix, 0)
            GLES20.glUniform1f(l3dDivergenceLoc, stereoDivergence)
            GLES20.glUniform1f(l3dConvergenceLoc, stereoConvergence)
            GLES20.glUniform2f(l3dDepthTexelLoc, 1f / maxOf(depthTexWidth, 1), 1f / maxOf(depthTexHeight, 1))

            vertexData.position(0)
            GLES20.glEnableVertexAttribArray(l3dPosLoc)
            GLES20.glVertexAttribPointer(l3dPosLoc, 2, GLES20.GL_FLOAT, false, 16, vertexData)
            vertexData.position(2)
            GLES20.glEnableVertexAttribArray(l3dTexLoc)
            GLES20.glVertexAttribPointer(l3dTexLoc, 2, GLES20.GL_FLOAT, false, 16, vertexData)

            // Left eye — sign -1 → samples to the right of the pixel (foreground floats left).
            GLES20.glViewport(leftRect.x, leftRect.y, leftRect.width, leftRect.height)
            GLES20.glUniform1f(l3dEyeSignLoc, -1f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            // Right eye — sign +1.
            GLES20.glViewport(rightRect.x, rightRect.y, rightRect.width, rightRect.height)
            GLES20.glUniform1f(l3dEyeSignLoc, 1f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])

            // Restore default active texture so other paths aren't accidentally affected.
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceWidth = width
            surfaceHeight = height
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            surfaceTexture?.let {
                it.updateTexImage()
                it.getTransformMatrix(texMatrix)
            }

            // Pick up any new depth map pushed from the depth worker since the last frame,
            // and snapshot the current decoded frame into the readback FBO so the worker
            // can run depth on it. Both are no-ops when Lazy 3D is off.
            val lazy3d = lazy3dStereoEnabled.get()
            if (lazy3d) {
                // Capture the source guide (frame N) first, then upload + refine the latest available
                // depth (computed from ~frame N-1 — async, never blocking on inference). The bilateral
                // edge-snap thus uses the freshest pixels; on fast motion the ~1-frame guide/depth
                // offset can slightly mis-snap edges — an accepted real-time trade-off (conservative
                // disparity + the forgiving luma-range weight keep it minor).
                maybeReadbackFrame()
                if (consumePendingDepth()) refineDepth()
            }

            // Always (re)assert the full on-screen viewport before the visible draw. The depth
            // readback renders into a 256×256 FBO; any stale/sub-full viewport here would shrink
            // the whole picture into a corner. Cheap insurance that makes that bug impossible.
            if (surfaceWidth > 0 && surfaceHeight > 0) {
                GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
            }

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(uTexLoc, 0)

            // Lazy 3D depth path takes priority over OU/SBS/duplicate — when on AND a depth
            // map has actually arrived, draw synthesised stereo. Until the first depth map
            // is available we fall through to the normal path so the picture is never blank.
            if (lazy3d && depthTextureId != 0) {
                drawStereoFromDepth()
            } else if (sbsEnabled.get()) {
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
precision highp float;
varying vec2 vTexCoord;
uniform samplerExternalOES uTexture;
uniform mat4 uTexMatrix;
uniform vec2 uScale;
uniform vec2 uOffset;
// One framebuffer LSB (1/255 at 8-bit, 1/1023 at 10-bit). Amplitude of the triangular-PDF
// dither below, which breaks up the 8-bit colour banding on smooth gradients (skies, shadows).
uniform float uDitherAmp;
float h21(vec2 p) {
  vec3 q = fract(vec3(p.xyx) * 0.1031);
  q += dot(q, q.yzx + 33.33);
  return fract((q.x + q.y) * q.z);
}
vec3 ditherTPDF(vec2 fc) {
  vec3 a = vec3(h21(fc), h21(fc + 11.3), h21(fc + 23.7));
  vec3 b = vec3(h21(fc + 5.1), h21(fc + 17.9), h21(fc + 31.5));
  return a + b - 1.0; // triangular PDF in [-1, 1] per channel
}
void main() {
  // Apply SurfaceTexture transform first to account for decoder orientation,
  // then crop to top/bottom half in the transformed texture space
  vec2 tc = (uTexMatrix * vec4(vTexCoord, 0.0, 1.0)).xy;
  vec2 finalTc = tc * uScale + uOffset;
  vec3 col = texture2D(uTexture, finalTc).rgb;
  gl_FragColor = vec4(col + ditherTPDF(gl_FragCoord.xy) * uDitherAmp, 1.0);
}
"""

/**
 * Lazy 3D stereo-from-depth shader.
 *
 * Adapted from the backward-warp idea in nagadomi/nunif (iw3): for each output pixel,
 * we sample the source at a UV shifted by `(depth - convergence) * divergence * eyeSign`.
 * Foreground pixels (depth ≈ 1) shift more, background (depth ≈ 0) shift little; left and
 * right eyes shift in opposite directions, giving the brain a stereo cue.
 *
 * Edge dilation: depth maps from monocular networks tend to be slightly fuzzy at object
 * boundaries, which manifests as a "halo" of background pixels bleeding around foreground
 * silhouettes after warp. A 3-tap max filter (here, also iw3-style) expands the foreground
 * mask by one texel so the silhouette wins, hiding most of the halo without needing
 * forward-warp hole-filling.
 *
 * Convergence is the depth value that maps to zero disparity (the "screen plane"): pixels
 * with d > convergence pop out, d < convergence sink in.
 */
private const val FRAG_LAZY3D = """
#extension GL_OES_EGL_image_external : require
precision highp float;
varying vec2 vTexCoord;
uniform samplerExternalOES uSource;
uniform sampler2D uDepth;
uniform mat4 uTexMatrix;
uniform float uDivergence;
uniform float uConvergence;
uniform float uEyeSign;     // -1 left, +1 right
uniform vec2 uDepthTexel;   // 1/depthSize; step for the foreground-dilation taps
uniform float uDitherAmp;   // one framebuffer LSB; amplitude of the anti-banding dither
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
  vec2 tcSrc = (uTexMatrix * vec4(vTexCoord, 0.0, 1.0)).xy;
  // Horizontal foreground dilation (max over neighbours): widens the nearer object's
  // silhouette so it covers the disocclusion band the warp opens along edges — the main
  // source of the "smear/halo at object borders". Span ±3 depth-texels ≈ the max disparity
  // at the current divergence, so the foreground edge wins instead of stretched background.
  float dpx = uDepthTexel.x;
  float d = texture2D(uDepth, vTexCoord).r;
  d = max(d, texture2D(uDepth, vTexCoord + vec2(dpx, 0.0)).r);
  d = max(d, texture2D(uDepth, vTexCoord - vec2(dpx, 0.0)).r);
  d = max(d, texture2D(uDepth, vTexCoord + vec2(2.0 * dpx, 0.0)).r);
  d = max(d, texture2D(uDepth, vTexCoord - vec2(2.0 * dpx, 0.0)).r);
  d = max(d, texture2D(uDepth, vTexCoord + vec2(3.0 * dpx, 0.0)).r);
  d = max(d, texture2D(uDepth, vTexCoord - vec2(3.0 * dpx, 0.0)).r);
  // Signed depth around the screen plane: + = nearer (pops out), - = farther (recedes).
  float dn = d - uConvergence;
  // Asymmetric comfort: recession (behind the screen) is the less comfortable direction on
  // far-focus glasses AND opens the widest disocclusions, so compress it relative to pop-out.
  if (dn < 0.0) dn *= 0.7;
  float disparity = dn * uDivergence * uEyeSign;
  // Hard safety clamp on per-eye shift (fraction of width) so a depth outlier can't tear the
  // image. ±0.045 leaves headroom for the boosted per-model divergence (V-Model runs at
  // ~0.02 divergence; the old ±0.03 would have silently eaten part of that gain).
  disparity = clamp(disparity, -0.045, 0.045);
  vec2 sampleUv = tcSrc + vec2(disparity, 0.0);
  vec3 col = texture2D(uSource, sampleUv).rgb;
  gl_FragColor = vec4(col + ditherTPDF(gl_FragCoord.xy) * uDitherAmp, 1.0);
}
"""

/**
 * Depth-refine shader: a 5×5 joint-bilateral on the raw depth, guided by the low-res source
 * snapshot, run once per new inference into an off-screen FBO at depth resolution.
 *
 *  - Range weight uses the *guide* luma, so depth only blends across pixels of similar colour →
 *    fuzzy MiDaS depth edges snap onto the real image edges, killing the warp's halo/ghosting.
 *  - Spatial weight is asymmetric (wider vertical SY than horizontal SX) → smooths depth more
 *    vertically, so horizontal depth discontinuities bend into gradients (fewer tears/holes after
 *    warp) without smearing horizontal disparity.
 */
private const val FRAG_DEPTH_REFINE = """
precision highp float;
varying vec2 vTexCoord;
uniform sampler2D uDepth;   // raw normalised depth (.r)
uniform sampler2D uGuide;   // low-res source snapshot (RGBA) — edge guide
uniform vec2 uTexel;        // 1/depthSize
const float SX = 1.2;       // horizontal spatial sigma (texels)
const float SY = 2.2;       // vertical spatial sigma — wider (asymmetric smooth)
const float SR = 0.10;      // luma range sigma — joint-bilateral edge-snap
float luma(vec2 uv) {
  vec3 c = texture2D(uGuide, uv).rgb;
  return dot(c, vec3(0.299, 0.587, 0.114));
}
void main() {
  float cL = luma(vTexCoord);
  float sumW = 0.0;
  float sumD = 0.0;
  for (int dy = -2; dy <= 2; dy++) {
    for (int dx = -2; dx <= 2; dx++) {
      vec2 off = vec2(float(dx), float(dy)) * uTexel;
      float dd = texture2D(uDepth, vTexCoord + off).r;
      float ll = luma(vTexCoord + off);
      float ws = exp(-(float(dx * dx) / (2.0 * SX * SX) + float(dy * dy) / (2.0 * SY * SY)));
      float wr = exp(-((ll - cL) * (ll - cL)) / (2.0 * SR * SR));
      float w = ws * wr;
      sumW += w;
      sumD += w * dd;
    }
  }
  gl_FragColor = vec4(vec3(clamp(sumD / max(sumW, 1e-4), 0.0, 1.0)), 1.0);
}
"""

private const val READBACK_SIZE = 256
// Default (and fastest) readback pacing, ~30 Hz. The runtime interval can only be lengthened
// from here — see setDepthReadbackIntervalNanos (thermal throttling).
private const val READBACK_INTERVAL_NANOS = 33_000_000L
