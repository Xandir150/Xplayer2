package com.teleteh.xplayer2.ui

import android.content.Context
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
     * no IMU telemetry. When non-null, the violet head-pointer dot is drawn; when null, nothing
     * extra is drawn (just the bouncer).
     */
    var orientationProvider: (() -> FloatArray?)? = null

    // Violet head-pointer dot driven by the goggles' IMU. Moves WITH the head (turn right -> dot
    // right, nod down -> dot down). Flip a sign below if an axis reads inverted on the glasses;
    // cursorRangeDeg maps the head angle to ~the view edge.
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFE040FB.toInt() // violet
    }
    private var cursorYawSign = 1f     // set -1f if turning head right moves the dot left
    private var cursorPitchSign = 1f   // set -1f if nodding down moves the dot up
    private val cursorRangeDeg = 25f

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
        // Head-pointer dot, drawn only when head telemetry exists.
        val orient = orientationProvider?.invoke()
        canvas.drawOval(RectF(x, y, x + logoW, y + logoH), ovalPaint)
        val cx = x + logoW / 2f
        val dvdBaseline = (y + logoH * 0.44f) - (dvdPaint.ascent() + dvdPaint.descent()) / 2f
        canvas.drawText("DVD", cx, dvdBaseline, dvdPaint)
        val subBaseline = (y + logoH * 0.74f) - (subPaint.ascent() + subPaint.descent()) / 2f
        canvas.drawText("XPlayer2", cx, subBaseline, subPaint)
        orient?.let { o -> drawCursor(canvas, o[0], o[1]) } // pointer dot on top
    }

    /**
     * Head-pointer dot: maps yaw -> X and pitch -> Y so the dot follows where the head points.
     * This is the axis-verification step before wiring real gestures — the grid above stays
     * world-anchored, this dot moves with the head. Drawn only when telemetry is present.
     */
    private fun drawCursor(canvas: Canvas, yaw: Float, pitch: Float) {
        val nx = (cursorYawSign * yaw / cursorRangeDeg).coerceIn(-1f, 1f)
        val ny = (cursorPitchSign * pitch / cursorRangeDeg).coerceIn(-1f, 1f)
        val px = width / 2f + nx * (width * 0.45f)
        val py = height / 2f + ny * (height * 0.45f)
        canvas.drawCircle(px, py, (width * 0.012f).coerceAtLeast(6f), cursorPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }
}
