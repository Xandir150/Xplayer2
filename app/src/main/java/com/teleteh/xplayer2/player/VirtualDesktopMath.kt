package com.teleteh.xplayer2.player

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Pure geometry/matrix math for the PC Link world-fixed virtual desktop ([VirtualDesktopGlView]).
 * No GL, no Android types — everything here runs (and is unit-tested) on the plain JVM.
 *
 * Conventions — all deliberate, all load-bearing:
 *
 * **World space** is right-handed OpenGL: +X right, +Y up, +Z toward the viewer. The viewer sits
 * at the origin looking down −Z; the desktop canvas is a plane centred at (0, 0, −distance).
 * The camera never translates (the glasses are 3-DoF: rotation only), so `canvasDistanceM` has no
 * effect on the rendered picture by itself — a 45° canvas at 1 m and at 100 m project identically
 * under pure rotation. It is kept in the geometry so the numbers stay physical (and so any later
 * positional/IPD offset gets correct absolute scale); today the depth percept comes entirely from
 * the disparity the server bakes into an `"sbs"` stream.
 *
 * **Head angles** come from [com.teleteh.xplayer2.data.glasses.HeadOrientationTracker], whose
 * convention was verified against its consumers (MainActivity's head-as-D-pad comments, confirmed
 * on-device by that shipped feature):
 *  - `yawDeg`   > 0 = head turned LEFT   (gz+ = left turn rate),
 *  - `pitchDeg` > 0 = head looking DOWN  (gx+ = downward nod rate),
 *  - `rollDeg`  > 0 = head tilted RIGHT  (gy+ = right-tilt rate → "OK" gesture).
 * The tracker integrates each axis independently (not a composed 3-D rotation), so no Euler order
 * reproduces it exactly under combined motion; we compose intrinsic yaw → pitch → roll
 * (`R_head = Ry(yaw) · Rx(pitch) · Rz(roll)`), the standard heading/attitude/bank order that
 * matches how a head actually moves (turn, then nod, then tilt) and degrades gracefully.
 *
 * **View matrix** = the inverse (transpose) of the head rotation: the world is drawn counter-
 * rotated so the canvas appears fixed in space while the physical panel moves with the head.
 *
 * All matrices are column-major float[16], GL-uniform-ready.
 */
object VirtualDesktopMath {

    // ---------------------------------------------------------------------------------------------
    // 4x4 column-major helpers (column-major: m[col * 4 + row], matching OpenGL)
    // ---------------------------------------------------------------------------------------------

    fun identity(): FloatArray = FloatArray(16).also { m ->
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
    }

    /** out = a · b (column-major). [out] may not alias [a] or [b]. */
    fun multiplyMM(out: FloatArray, a: FloatArray, b: FloatArray) {
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var s = 0f
                for (k in 0 until 4) s += a[k * 4 + row] * b[col * 4 + k]
                out[col * 4 + row] = s
            }
        }
    }

    /** Rotation about +X by [deg] (right-handed: +deg tips the forward vector −Z upward). */
    fun rotationX(deg: Float): FloatArray {
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return identity().also { m ->
            m[5] = c; m[9] = -s
            m[6] = s; m[10] = c
        }
    }

    /** Rotation about +Y by [deg] (right-handed: +deg swings the forward vector −Z to the left). */
    fun rotationY(deg: Float): FloatArray {
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return identity().also { m ->
            m[0] = c; m[8] = s
            m[2] = -s; m[10] = c
        }
    }

    /** Rotation about +Z by [deg] (right-handed: +deg turns +X toward +Y, i.e. CCW on screen). */
    fun rotationZ(deg: Float): FloatArray {
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return identity().also { m ->
            m[0] = c; m[4] = -s
            m[1] = s; m[5] = c
        }
    }

    /** Transforms point (x, y, z, 1) by column-major [m]; returns [x', y', z', w']. */
    fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val out = FloatArray(4)
        for (row in 0 until 4) {
            out[row] = m[row] * x + m[4 + row] * y + m[8 + row] * z + m[12 + row]
        }
        return out
    }

    /** Wraps an angle difference into (−180, 180], so a re-center near the ±180 seam stays sane. */
    fun normalizeDeg(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f else if (d <= -180f) d += 360f
        return d
    }

    // ---------------------------------------------------------------------------------------------
    // Canvas plane geometry
    // ---------------------------------------------------------------------------------------------

    /** Aspect ratio of the CONTENT one eye sees: for a packed `"sbs"` frame that's half the width. */
    fun contentAspect(videoWidth: Int, videoHeight: Int, sbs: Boolean): Float {
        if (videoWidth <= 0 || videoHeight <= 0) return 16f / 9f
        val w = if (sbs) videoWidth / 2f else videoWidth.toFloat()
        return w / videoHeight
    }

    /**
     * World-space size of a plane at [distanceM] subtending [angularWidthDeg] horizontally, with
     * height from [contentAspect]. Returns [width, height] in meters.
     */
    fun planeSize(angularWidthDeg: Float, distanceM: Float, contentAspect: Float): FloatArray {
        val halfRad = Math.toRadians((angularWidthDeg.coerceIn(1f, 170f) / 2f).toDouble())
        val width = (2.0 * distanceM * tan(halfRad)).toFloat()
        val height = width / contentAspect.coerceAtLeast(0.05f)
        return floatArrayOf(width, height)
    }

    /**
     * Interleaved vertices (x, y, z, u, v) for a [cols]×[rows] subdivided plane centred at
     * (0, 0, −distance), facing the origin. u=0/v=0 at the world bottom-left (v grows upward,
     * matching [OuToSbsGlView]'s quad; the SurfaceTexture transform matrix handles the flip).
     * The subdivision buys nothing today (the plane is flat) — it exists so a cylindrical bend
     * is later a vertex-shader tweak, not a geometry rewrite.
     */
    fun planeVertices(
        angularWidthDeg: Float,
        distanceM: Float,
        contentAspect: Float,
        cols: Int,
        rows: Int
    ): FloatArray {
        val (w, h) = planeSize(angularWidthDeg, distanceM, contentAspect).let { it[0] to it[1] }
        val out = FloatArray((cols + 1) * (rows + 1) * 5)
        var i = 0
        for (ry in 0..rows) {
            val v = ry.toFloat() / rows
            val y = (v - 0.5f) * h
            for (cx in 0..cols) {
                val u = cx.toFloat() / cols
                out[i++] = (u - 0.5f) * w   // x
                out[i++] = y                // y
                out[i++] = -distanceM       // z
                out[i++] = u                // u
                out[i++] = v                // v
            }
        }
        return out
    }

    /** Triangle indices (CCW) for the [planeVertices] grid: cols·rows·6 entries. */
    fun planeIndices(cols: Int, rows: Int): ShortArray {
        val out = ShortArray(cols * rows * 6)
        var i = 0
        for (ry in 0 until rows) {
            for (cx in 0 until cols) {
                val bl = (ry * (cols + 1) + cx).toShort()
                val br = (bl + 1).toShort()
                val tl = ((ry + 1) * (cols + 1) + cx).toShort()
                val tr = (tl + 1).toShort()
                out[i++] = bl; out[i++] = br; out[i++] = tl
                out[i++] = br; out[i++] = tr; out[i++] = tl
            }
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------
    // View / projection
    // ---------------------------------------------------------------------------------------------

    /**
     * View matrix from the tracker's head angles (see the class doc for the sign convention),
     * minus the re-center offsets ([yawZeroDeg]/[pitchZeroDeg] = the head pose captured as the new
     * "straight at the canvas"; roll is never re-zeroed — the horizon stays the world's).
     *
     * Head rotation (tracker → GL): turning LEFT is +yaw about +Y as-is; looking DOWN is +pitch on
     * the tracker but a NEGATIVE right-handed rotation about +X, so it enters negated; tilting
     * RIGHT is +roll on the tracker but a NEGATIVE rotation about +Z (the head's up vector leans
     * toward +X), so it enters negated too:
     *
     *   R_head = Ry(+yawΔ) · Rx(−pitchΔ) · Rz(−roll)
     *
     * and the view matrix is its inverse, V = R_headᵀ = Rz(+roll) · Rx(+pitchΔ) · Ry(−yawΔ).
     */
    fun viewMatrix(
        yawDeg: Float,
        pitchDeg: Float,
        rollDeg: Float,
        yawZeroDeg: Float = 0f,
        pitchZeroDeg: Float = 0f
    ): FloatArray {
        val yawGl = normalizeDeg(yawDeg - yawZeroDeg)      // + = head turned left
        val pitchGl = -(pitchDeg - pitchZeroDeg)           // tracker + = down → GL − about +X
        val rollGl = -rollDeg                              // tracker + = tilt right → GL − about +Z
        // V = R_headᵀ = Rz(−rollGl) · Rx(−pitchGl) · Ry(−yawGl)
        val zx = FloatArray(16)
        multiplyMM(zx, rotationZ(-rollGl), rotationX(-pitchGl))
        val out = FloatArray(16)
        multiplyMM(out, zx, rotationY(-yawGl))
        return out
    }

    /** Convenience: R_head itself (the forward rotation), used by tests to check V·R = I. */
    fun headMatrix(yawDeg: Float, pitchDeg: Float, rollDeg: Float): FloatArray {
        val yx = FloatArray(16)
        multiplyMM(yx, rotationY(yawDeg), rotationX(-pitchDeg))
        val out = FloatArray(16)
        multiplyMM(out, yx, rotationZ(-rollDeg))
        return out
    }

    /**
     * Symmetric per-eye perspective projection from a HORIZONTAL field of view.
     *
     * Why horizontal, and why 40° by default (see [VirtualDesktopGlView.DEFAULT_EYE_HFOV_DEG]):
     * for the world-fixed illusion the render's pixels-per-degree must match the physical panel's,
     * i.e. the frustum must match the per-eye FOV of the glasses. The XREAL/RayNeo/VITURE family
     * quotes ≈46° *diagonal* per eye at 16:9, which splits (in tan space) into ≈40° horizontal ×
     * ≈23° vertical. So the vertical FOV here deliberately lands near 23°, NOT the 40–46° a
     * head-on reading of the marketing number would suggest — with a 45° vertical frustum every
     * degree of head turn would slide the picture only about half the pixels it physically should,
     * and the canvas would visibly drag with the head instead of standing still.
     *
     * [viewportAspect] is the per-eye viewport (width/height, e.g. 1920/1080 on a 3840×1080 SBS
     * panel); the vertical FOV follows from it: tan(v/2) = tan(h/2) / aspect.
     */
    fun projectionMatrix(
        horizontalFovDeg: Float,
        viewportAspect: Float,
        near: Float = 0.1f,
        far: Float = 100f
    ): FloatArray {
        val tanHalfH = tan(Math.toRadians((horizontalFovDeg.coerceIn(10f, 120f) / 2f).toDouble())).toFloat()
        val tanHalfV = tanHalfH / viewportAspect.coerceAtLeast(0.1f)
        val m = FloatArray(16)
        m[0] = 1f / tanHalfH
        m[5] = 1f / tanHalfV
        m[10] = -(far + near) / (far - near)
        m[11] = -1f
        m[14] = -(2f * far * near) / (far - near)
        return m
    }

    /** Vertical FOV (degrees) implied by [projectionMatrix]'s inputs — for logs/documentation. */
    fun verticalFovDeg(horizontalFovDeg: Float, viewportAspect: Float): Float {
        val tanHalfH = tan(Math.toRadians((horizontalFovDeg / 2f).toDouble()))
        return (2.0 * Math.toDegrees(atan(tanHalfH / viewportAspect))).toFloat()
    }

    // ---------------------------------------------------------------------------------------------
    // Per-eye texture crop
    // ---------------------------------------------------------------------------------------------

    /**
     * Horizontal texture window one eye samples, as [uScale, uOffset] applied AFTER the
     * SurfaceTexture transform (same order as [OuToSbsGlView]): `"sbs"` splits the packed frame —
     * left eye = left half per protocol §2.2 — while mono shows the full frame to both eyes.
     */
    fun eyeTexTransform(sbs: Boolean, rightEye: Boolean): FloatArray =
        if (!sbs) floatArrayOf(1f, 0f)
        else floatArrayOf(0.5f, if (rightEye) 0.5f else 0f)

    /** True when two matrices/vectors agree within [eps] — test helper. */
    fun approxEquals(a: FloatArray, b: FloatArray, eps: Float = 1e-4f): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (abs(a[i] - b[i]) > eps) return false
        return true
    }

    /**
     * Whether a panel of this pixel size expects side-by-side halves — i.e. whether the glasses
     * are in their 3D mode.
     *
     * Measured rather than remembered on purpose: the glasses report the last mode *we* set them
     * to, which is 2D by default and stays wrong for a pair that was already in 3D or a brand
     * that cannot be read back. The panel's own shape cannot be wrong about itself.
     *
     * The two modes are far apart — 3840×1080 is 3.56:1 against 1920×1080's 1.78:1 — so the
     * threshold has an enormous margin either side and no real panel sits near it.
     */
    fun panelIsStereo(widthPx: Int, heightPx: Int): Boolean =
        heightPx > 0 && widthPx.toFloat() / heightPx >= STEREO_PANEL_RATIO

    /** Halfway between a 16:9 panel and a doubled one, in log terms — nothing lands here. */
    private const val STEREO_PANEL_RATIO = 2.5f
}
