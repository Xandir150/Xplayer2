package com.teleteh.xplayer2.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * JVM tests for the world-fixed virtual-desktop math ([VirtualDesktopMath]) — no GL context, no
 * Android types. The convention tests encode [com.teleteh.xplayer2.data.glasses.HeadOrientationTracker]'s
 * documented axes (yaw+ = head turned LEFT, pitch+ = looking DOWN, roll+ = tilted RIGHT — the
 * mapping verified on-device by MainActivity's head-as-D-pad), so a silent flip in either the
 * tracker or the view matrix breaks a test rather than the illusion.
 */
class VirtualDesktopMathTest {

    private val eps = 1e-3f

    private fun assertVec(expected: FloatArray, actual: FloatArray, eps: Float = this.eps) {
        for (i in expected.indices) {
            assertEquals("component $i of ${actual.toList()}", expected[i], actual[i], eps)
        }
    }

    // --- plane geometry ------------------------------------------------------------------------

    @Test
    fun `plane at 3m subtending 45deg has the right world size`() {
        val (w, h) = VirtualDesktopMath.planeSize(45f, 3f, 16f / 9f).let { it[0] to it[1] }
        val expectedW = (2.0 * 3.0 * tan(Math.toRadians(22.5))).toFloat()
        assertEquals(expectedW, w, eps)
        assertEquals(expectedW / (16f / 9f), h, eps)
    }

    @Test
    fun `plane corners subtend exactly the angular width from the viewer`() {
        val verts = VirtualDesktopMath.planeVertices(45f, 3f, 16f / 9f, 4, 2)
        // First vertex is the bottom-left corner (x, y, z, u, v).
        val x = verts[0]
        val z = verts[2]
        val subtended = Math.toDegrees(2.0 * atan2(-x.toDouble(), -z.toDouble()))
        assertEquals(45.0, subtended, 1e-3)
    }

    @Test
    fun `vertex grid has expected layout, uv corners and distance`() {
        val cols = 4
        val rows = 3
        val d = 2.5f
        val verts = VirtualDesktopMath.planeVertices(50f, d, 2f, cols, rows)
        assertEquals((cols + 1) * (rows + 1) * 5, verts.size)
        val (w, h) = VirtualDesktopMath.planeSize(50f, d, 2f).let { it[0] to it[1] }
        // First vertex: bottom-left, uv (0,0).
        assertVec(floatArrayOf(-w / 2f, -h / 2f, -d, 0f, 0f), verts.copyOfRange(0, 5))
        // Last vertex: top-right, uv (1,1).
        assertVec(floatArrayOf(w / 2f, h / 2f, -d, 1f, 1f), verts.copyOfRange(verts.size - 5, verts.size))
        // Every vertex sits on the z = -d plane (flat until the cylindrical bend lands).
        for (i in verts.indices step 5) assertEquals(-d, verts[i + 2], eps)
    }

    @Test
    fun `indices cover the grid with in-range vertices`() {
        val cols = 32
        val rows = 18
        val idx = VirtualDesktopMath.planeIndices(cols, rows)
        assertEquals(cols * rows * 6, idx.size)
        val vertexCount = (cols + 1) * (rows + 1)
        for (i in idx) assertTrue("index $i out of range", i >= 0 && i < vertexCount)
        // Each vertex of the interior is referenced by some triangle; spot-check the corners.
        assertTrue(idx.contains(0.toShort()))
        assertTrue(idx.contains((vertexCount - 1).toShort()))
    }

    // --- aspect / texture crop -----------------------------------------------------------------

    @Test
    fun `content aspect halves the width for sbs`() {
        assertEquals(1920f / 1080f, VirtualDesktopMath.contentAspect(3840, 1080, sbs = true), eps)
        assertEquals(3840f / 1080f, VirtualDesktopMath.contentAspect(3840, 1080, sbs = false), eps)
        // Unknown size falls back to 16:9 rather than dividing by zero.
        assertEquals(16f / 9f, VirtualDesktopMath.contentAspect(0, 0, sbs = true), eps)
    }

    @Test
    fun `eye texture crop follows the protocol packing`() {
        // §2.2: "sbs" is left|right packed, left eye = left half.
        assertVec(floatArrayOf(0.5f, 0f), VirtualDesktopMath.eyeTexTransform(sbs = true, rightEye = false))
        assertVec(floatArrayOf(0.5f, 0.5f), VirtualDesktopMath.eyeTexTransform(sbs = true, rightEye = true))
        // Mono: both eyes sample the full frame.
        assertVec(floatArrayOf(1f, 0f), VirtualDesktopMath.eyeTexTransform(sbs = false, rightEye = false))
        assertVec(floatArrayOf(1f, 0f), VirtualDesktopMath.eyeTexTransform(sbs = false, rightEye = true))
    }

    // --- view matrix: the HeadOrientationTracker convention ------------------------------------

    @Test
    fun `zero head pose is the identity view`() {
        val v = VirtualDesktopMath.viewMatrix(0f, 0f, 0f)
        assertTrue(VirtualDesktopMath.approxEquals(VirtualDesktopMath.identity(), v))
    }

    @Test
    fun `turning the head left brings the left world point dead ahead`() {
        // Tracker yaw+ = head turned LEFT. After turning left 90°, the point that was at the
        // viewer's left (-X) must sit straight ahead (0, 0, -d) in view space.
        val v = VirtualDesktopMath.viewMatrix(yawDeg = 90f, pitchDeg = 0f, rollDeg = 0f)
        val p = VirtualDesktopMath.transformPoint(v, -3f, 0f, 0f)
        assertVec(floatArrayOf(0f, 0f, -3f), floatArrayOf(p[0], p[1], p[2]))
    }

    @Test
    fun `turning the head left moves the canvas to the right of view`() {
        // The canvas is world-fixed straight ahead; turn left and it must appear to the RIGHT.
        val v = VirtualDesktopMath.viewMatrix(yawDeg = 90f, pitchDeg = 0f, rollDeg = 0f)
        val p = VirtualDesktopMath.transformPoint(v, 0f, 0f, -3f)
        assertVec(floatArrayOf(3f, 0f, 0f), floatArrayOf(p[0], p[1], p[2]))
    }

    @Test
    fun `looking down brings the low world point dead ahead`() {
        // Tracker pitch+ = looking DOWN. Looking down 30°, the world point 30° below the horizon
        // must land straight ahead.
        val d = 3f
        val v = VirtualDesktopMath.viewMatrix(yawDeg = 0f, pitchDeg = 30f, rollDeg = 0f)
        val below = floatArrayOf(
            0f,
            (-d * kotlin.math.sin(Math.toRadians(30.0))).toFloat(),
            (-d * kotlin.math.cos(Math.toRadians(30.0))).toFloat()
        )
        val p = VirtualDesktopMath.transformPoint(v, below[0], below[1], below[2])
        assertVec(floatArrayOf(0f, 0f, -d), floatArrayOf(p[0], p[1], p[2]))
    }

    @Test
    fun `looking down moves the canvas up in view`() {
        val v = VirtualDesktopMath.viewMatrix(yawDeg = 0f, pitchDeg = 30f, rollDeg = 0f)
        val p = VirtualDesktopMath.transformPoint(v, 0f, 0f, -3f)
        assertTrue("canvas should rise in view when looking down, got y=${p[1]}", p[1] > 0.5f)
        assertTrue(p[2] < 0f)
    }

    @Test
    fun `tilting the head right counter-rotates the world left`() {
        // Tracker roll+ = head tilted RIGHT (right ear down). The world's up axis must then lean
        // LEFT (-X) in view space, so the rendered canvas stays level with the true horizon.
        val v = VirtualDesktopMath.viewMatrix(yawDeg = 0f, pitchDeg = 0f, rollDeg = 20f)
        val up = VirtualDesktopMath.transformPoint(v, 0f, 1f, 0f)
        assertEquals((-kotlin.math.sin(Math.toRadians(20.0))).toFloat(), up[0], eps)
        assertEquals(kotlin.math.cos(Math.toRadians(20.0)).toFloat(), up[1], eps)
        assertEquals(0f, up[2], eps)
    }

    @Test
    fun `view is the exact inverse of the head rotation for combined angles`() {
        val angles = listOf(
            Triple(37f, -12f, 8f),
            Triple(-120f, 45f, -30f),
            Triple(179f, -89f, 90f)
        )
        for ((yaw, pitch, roll) in angles) {
            val v = VirtualDesktopMath.viewMatrix(yaw, pitch, roll)
            val head = VirtualDesktopMath.headMatrix(yaw, pitch, roll)
            val product = FloatArray(16)
            VirtualDesktopMath.multiplyMM(product, v, head)
            assertTrue(
                "V*R != I for ($yaw, $pitch, $roll): ${product.toList()}",
                VirtualDesktopMath.approxEquals(VirtualDesktopMath.identity(), product, 1e-4f)
            )
        }
    }

    // --- re-center -----------------------------------------------------------------------------

    @Test
    fun `recenter offsets make the current pose the new straight ahead`() {
        val v = VirtualDesktopMath.viewMatrix(
            yawDeg = 40f, pitchDeg = -15f, rollDeg = 0f,
            yawZeroDeg = 40f, pitchZeroDeg = -15f
        )
        assertTrue(VirtualDesktopMath.approxEquals(VirtualDesktopMath.identity(), v))
    }

    @Test
    fun `recenter across the yaw wrap seam takes the short way round`() {
        // Zeroed at +170°, drifted to -170°: the physical move was +20° further left (through
        // 180), not 340° right. The canvas must sit 20° to the right of view — i.e. exactly where
        // viewMatrix(20) would put it.
        val wrapped = VirtualDesktopMath.viewMatrix(
            yawDeg = -170f, pitchDeg = 0f, rollDeg = 0f, yawZeroDeg = 170f
        )
        val direct = VirtualDesktopMath.viewMatrix(yawDeg = 20f, pitchDeg = 0f, rollDeg = 0f)
        assertTrue(VirtualDesktopMath.approxEquals(direct, wrapped, 1e-4f))
    }

    @Test
    fun `normalizeDeg wraps into the half-open range`() {
        assertEquals(20f, VirtualDesktopMath.normalizeDeg(-340f), eps)
        assertEquals(-20f, VirtualDesktopMath.normalizeDeg(340f), eps)
        assertEquals(180f, VirtualDesktopMath.normalizeDeg(180f), eps)
        assertEquals(180f, VirtualDesktopMath.normalizeDeg(-180f), eps)
        assertEquals(0f, VirtualDesktopMath.normalizeDeg(720f), eps)
    }

    // --- projection ----------------------------------------------------------------------------

    @Test
    fun `projection maps the horizontal fov edge to the clip boundary`() {
        val hFov = 40f
        val aspect = 1920f / 1080f
        val proj = VirtualDesktopMath.projectionMatrix(hFov, aspect)
        val d = 3f
        val edgeX = (d * tan(Math.toRadians(hFov / 2.0))).toFloat()
        val clip = VirtualDesktopMath.transformPoint(proj, edgeX, 0f, -d)
        assertEquals(1f, clip[0] / clip[3], 1e-3f)

        // Vertical: tan(v/2) = tan(h/2) / aspect.
        val edgeY = (d * tan(Math.toRadians(hFov / 2.0)) / aspect).toFloat()
        val clipY = VirtualDesktopMath.transformPoint(proj, 0f, edgeY, -d)
        assertEquals(1f, clipY[1] / clipY[3], 1e-3f)
    }

    @Test
    fun `vertical fov lands near 23deg for the 40deg by 16-9 eye`() {
        // The physical XREAL-family eye: ≈46° diagonal at 16:9 ⇒ ≈40° x ≈23°. This pins the
        // "vertical FOV is ~23°, not 40-46°" decision — see VirtualDesktopMath.projectionMatrix.
        val vFov = VirtualDesktopMath.verticalFovDeg(40f, 16f / 9f)
        assertEquals(23.1f, vFov, 0.3f)
    }

    @Test
    fun `points in front project with negative w sign convention intact`() {
        val proj = VirtualDesktopMath.projectionMatrix(40f, 16f / 9f)
        val clip = VirtualDesktopMath.transformPoint(proj, 0f, 0f, -3f)
        assertTrue("w must be positive for a point in front", clip[3] > 0f)
        val ndcZ = clip[2] / clip[3]
        assertTrue("ndc z should be inside the frustum, got $ndcZ", ndcZ > -1f && ndcZ < 1f)
    }

    // --- full transform sanity -----------------------------------------------------------------

    @Test
    fun `canvas centre stays centred under recentered gaze`() {
        // Wherever the head points, after recenter the canvas centre must project to NDC (0,0).
        val proj = VirtualDesktopMath.projectionMatrix(40f, 16f / 9f)
        val view = VirtualDesktopMath.viewMatrix(
            yawDeg = 33f, pitchDeg = -7f, rollDeg = 0f, yawZeroDeg = 33f, pitchZeroDeg = -7f
        )
        val vp = FloatArray(16)
        VirtualDesktopMath.multiplyMM(vp, proj, view)
        val clip = VirtualDesktopMath.transformPoint(vp, 0f, 0f, -3f)
        assertEquals(0f, clip[0] / clip[3], eps)
        assertEquals(0f, clip[1] / clip[3], eps)
    }

    @Test
    fun `small head turn shifts the projected centre by the matching angle`() {
        // Turn the head 5° left: the canvas centre must move right by exactly 5° worth of tan
        // space — the 1:1 pixels-per-degree property that pins the canvas to the world.
        val proj = VirtualDesktopMath.projectionMatrix(40f, 16f / 9f)
        val view = VirtualDesktopMath.viewMatrix(yawDeg = 5f, pitchDeg = 0f, rollDeg = 0f)
        val vp = FloatArray(16)
        VirtualDesktopMath.multiplyMM(vp, proj, view)
        val clip = VirtualDesktopMath.transformPoint(vp, 0f, 0f, -3f)
        val ndcX = clip[0] / clip[3]
        val expected = (tan(Math.toRadians(5.0)) / tan(Math.toRadians(20.0))).toFloat()
        assertEquals(expected, ndcX, 1e-3f)
    }

    @Test
    fun `matrix helpers are consistent`() {
        // Rotations are orthonormal: R * Rᵀ-equivalent (negative angle) = identity.
        for (deg in listOf(13f, -77f, 90f, 180f)) {
            for (rot in listOf(
                VirtualDesktopMath.rotationX(deg) to VirtualDesktopMath.rotationX(-deg),
                VirtualDesktopMath.rotationY(deg) to VirtualDesktopMath.rotationY(-deg),
                VirtualDesktopMath.rotationZ(deg) to VirtualDesktopMath.rotationZ(-deg)
            )) {
                val out = FloatArray(16)
                VirtualDesktopMath.multiplyMM(out, rot.first, rot.second)
                assertTrue(VirtualDesktopMath.approxEquals(VirtualDesktopMath.identity(), out, 1e-5f))
            }
        }
        // Column vectors of a rotation stay unit length.
        val r = VirtualDesktopMath.rotationY(37f)
        for (col in 0 until 3) {
            val len = sqrt(r[col * 4] * r[col * 4] + r[col * 4 + 1] * r[col * 4 + 1] + r[col * 4 + 2] * r[col * 4 + 2])
            assertEquals(1f, len, 1e-5f)
        }
    }
}
