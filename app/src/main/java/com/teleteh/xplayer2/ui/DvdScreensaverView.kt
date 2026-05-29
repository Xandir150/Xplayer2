package com.teleteh.xplayer2.ui

import android.content.Context
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Retro "bouncing DVD logo" idle screensaver for the external (glasses) display: a coloured oval
 * with a big "DVD" and a small "XPlayer2" wordmark drifts around and ricochets off the edges,
 * switching colour on every bounce. Opaque black background, so simply showing it also clears
 * whatever frozen video frame was underneath when playback paused/ended.
 *
 * Animation runs only while [start]ed, so it costs nothing while hidden during playback. When the
 * view sits inside [SbsMirrorLayout] it's duplicated per-eye automatically — it just bounces
 * within its own bounds and needs no stereo awareness.
 */
class DvdScreensaverView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var logoW = 0f
    private var logoH = 0f
    private var x = 0f
    private var y = 0f
    private var vx = 0f
    private var vy = 0f
    private var running = false
    private var lastNanos = 0L
    private var colorIdx = 0

    /**
     * Supplies the current head orientation as [yawDeg, pitchDeg, rollDeg], or null when there's
     * no IMU telemetry. When non-null, a world-anchored grid platform is drawn that counter-rotates
     * to try to stay put as the head moves; when null, nothing extra is drawn (just the bouncer).
     */
    var orientationProvider: (() -> FloatArray?)? = null

    private val camera = Camera()
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x6600E5FF.toInt() // translucent cyan
    }

    private val ovalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dvdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textScaleX = 1.4f   // stretched wide — squished, cheesy DVD-logo look
        textSkewX = -0.25f  // forward italic slant like the real logo
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        textScaleX = 1.2f
        textSkewX = -0.18f
        letterSpacing = 0.10f
    }

    // Classic over-saturated screensaver palette; cycles on each wall hit.
    private val palette = intArrayOf(
        0xFF00E5FF.toInt(), // cyan
        0xFFFF4081.toInt(), // pink
        0xFFFFD740.toInt(), // amber
        0xFF69F0AE.toInt(), // green
        0xFFE040FB.toInt(), // purple
        0xFFFF6E40.toInt(), // deep orange
    )

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            step()
            invalidate()
            postOnAnimation(this)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        ovalPaint.color = palette[0]
    }

    /** Begin animating. Safe to call repeatedly. */
    fun start() {
        if (running) return
        running = true
        lastNanos = 0L
        postOnAnimation(frame)
    }

    /** Stop animating (no further frames scheduled). */
    fun stop() {
        running = false
        removeCallbacks(frame)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        logoW = w * 0.07f          // ~1/3 of the old size
        logoH = logoW * 0.40f      // flatter / more squished ellipse
        dvdPaint.textSize = logoH * 0.58f
        subPaint.textSize = logoH * 0.26f
        gridPaint.strokeWidth = (w * 0.0022f).coerceAtLeast(1.5f)
        // Start centred with a gentle diagonal drift (~9 s to cross the width).
        x = (w - logoW) / 2f
        y = (h - logoH) / 2f
        val speed = w * 0.11f
        vx = speed
        vy = speed * 0.62f
    }

    private fun step() {
        val now = System.nanoTime()
        if (lastNanos == 0L) { lastNanos = now; return }
        val dt = ((now - lastNanos) / 1_000_000_000f).coerceAtMost(0.05f)
        lastNanos = now
        if (logoW <= 0f || width <= 0 || height <= 0) return
        x += vx * dt
        y += vy * dt
        var bounced = false
        if (x <= 0f) { x = 0f; vx = -vx; bounced = true }
        else if (x + logoW >= width) { x = width - logoW; vx = -vx; bounced = true }
        if (y <= 0f) { y = 0f; vy = -vy; bounced = true }
        else if (y + logoH >= height) { y = height - logoH; vy = -vy; bounced = true }
        if (bounced) {
            colorIdx = (colorIdx + 1) % palette.size
            ovalPaint.color = palette[colorIdx]
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas) // opaque black background
        if (logoW <= 0f) return
        // World-anchored grid platform, drawn only when head telemetry is available.
        orientationProvider?.invoke()?.let { o -> drawPlatform(canvas, o[0], o[1], o[2]) }
        canvas.drawOval(RectF(x, y, x + logoW, y + logoH), ovalPaint)
        val cx = x + logoW / 2f
        val dvdBaseline = (y + logoH * 0.44f) - (dvdPaint.ascent() + dvdPaint.descent()) / 2f
        canvas.drawText("DVD", cx, dvdBaseline, dvdPaint)
        val subBaseline = (y + logoH * 0.74f) - (subPaint.ascent() + subPaint.descent()) / 2f
        canvas.drawText("XPlayer2", cx, subBaseline, subPaint)
    }

    /**
     * Draw a wireframe grid "floor" centred in the view, given a floor-like base tilt and then
     * counter-rotated by the head orientation so it tries to stay put in space as the head turns
     * (turn head right → grid rotates left, etc.). Pure 2D/Camera trick, not real 6-DoF.
     */
    private fun drawPlatform(canvas: Canvas, yaw: Float, pitch: Float, roll: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val half = width * 0.22f
        canvas.save()
        canvas.translate(cx, cy)
        camera.save()
        // Counter-rotate a FRONTAL grid by the inverse of the head pose so it tries to stay fixed
        // in space, with no baked-in floor tilt (that made yaw read as a sideways tilt). Clean
        // mapping: pitch (nod) → tilt around horizontal, yaw (turn) → swing around vertical,
        // roll (head side-tilt) → spin in-plane.
        camera.rotateX(-pitch.coerceIn(-70f, 70f))
        camera.rotateY(-yaw.coerceIn(-70f, 70f))
        camera.rotateZ(-roll.coerceIn(-70f, 70f))
        camera.applyToCanvas(canvas)
        camera.restore()
        val n = 8
        val step = half * 2f / n
        for (i in 0..n) {
            val d = -half + i * step
            canvas.drawLine(-half, d, half, d, gridPaint)
            canvas.drawLine(d, -half, d, half, gridPaint)
        }
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }
}
