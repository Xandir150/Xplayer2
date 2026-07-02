package com.teleteh.xplayer2.data.glasses

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Talks to XR glasses over USB HID.
 *
 * Detection covers every major brand currently shipping XR glasses with a USB-C
 * tether (the VID/PID list is mirrored from wheaney/XRLinuxDriver). The actual
 * `set display mode` command is only implemented for XREAL right now — see
 * [supportsRemoteSwitch]. For other brands the controller still reports the
 * connection so the UI can show an informative message instead of silently
 * staying empty.
 *
 * **Why not all brands?** Each vendor uses a different protocol:
 *   - XREAL: open MCU protocol over a 64-byte HID report, CRC-32, head 0xFD.
 *     Implemented here (see [GlassesProtocol]).
 *   - VITURE: HID with CRC-16-CCITT and head 0xFF 0xFE. The IMU side has been
 *     reverse-engineered (mgschwan/viture_virtual_display) but the
 *     `set_3d` command id is only available through VITURE's closed-source
 *     libviture_one_sdk (Android: `com.viture.sdk.ArManager.set3D(boolean)`,
 *     distributed as an AAR from viture.com/developer).
 *   - TCL/RayNeo and Rokid: both ship closed-source SDKs only.
 *
 * To enable VITURE switching: drop `VITURE-SDK-x.y.z.aar` under `app/libs/`,
 * add it as a flavor-scoped dependency and wire a separate brand-specific
 * helper from this class.
 */
class GlassesController(private val appContext: Context) {

    enum class ConnectionState { Disconnected, NeedsPermission, Connected }
    enum class Brand { XREAL, VITURE, ROKID, RAYNEO }

    fun interface Listener {
        fun onChanged(state: ConnectionState, currentMode: Int)
    }

    private val usbManager: UsbManager =
        appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var device: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var listener: Listener? = null
    // IMU head-orientation telemetry (XREAL only), started on demand via [setImuStreaming] while the
    // head-gesture menu is shown and stopped otherwise, so it doesn't drain power streaming the gyro
    // continuously. Exposed via [headOrientationDegrees]/[latestImuDebug]; null when not streaming.
    private var imuReader: XrealImuReader? = null
    private val headOrientation = HeadOrientationTracker()
    // Latest raw IMU sample for the glasses debug HUD, as volatile scalars — avoids allocating a
    // FloatArray on every IMU packet (~1 kHz) which was churning the GC.
    @Volatile private var imuGx = 0f
    @Volatile private var imuGy = 0f
    @Volatile private var imuGz = 0f
    @Volatile private var imuAx = 0f
    @Volatile private var imuAy = 0f
    @Volatile private var imuAz = 0f
    @Volatile private var imuTemp = 0f
    @Volatile private var imuHasSample = false
    // VITURE glasses switch 2D/3D through their own SDK (ArManager), not our HID MCU. Created on
    // connect for a VITURE device (the SDK owns the USB link + permission); null otherwise.
    private var viture: VitureController? = null
    private var vitureModeApplied = false
    @Volatile private var vitureStarting = false
    // Best-known CURRENT glasses mode (in-memory only — we no longer persist or force a remembered
    // mode). For VITURE the live SDK state is the truth (see lastMode()); for the others this tracks
    // the last mode we sent THIS session, defaulting to 2D (the panel's power-on state).
    private var lastReportedMode: Int = GlassesProtocol.MCU_DISPLAY_MODE_1920x1080_60
    // A mode the user EXPLICITLY picked while the device was still opening (RayNeo lazy-open): applied
    // once on connect, then cleared. NOT a remembered/persisted preference.
    private var pendingMode: Int? = null
    // The deferred connect-time mode-push posted by openDevice(). Tracked so releaseConnection()/
    // a re-open can cancel exactly THIS post — a blanket removeCallbacksAndMessages(null) also
    // killed unrelated pending posts (setDisplayMode result callbacks, VITURE state updates),
    // silently swallowing their callbacks.
    private var pendingModePush: Runnable? = null
    // Bumped (main thread) after every successful mode WRITE; an async refreshDisplayMode() read
    // only applies its result if no write landed while it was reading (see refreshDisplayMode).
    private var modeWriteSeq = 0
    // Single-flight latch for refreshDisplayMode's reader thread.
    private val refreshInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    // Interfaces we successfully claimed — we only ever release what we actually took.
    private val claimedInterfaces: MutableList<UsbInterface> = mutableListOf()

    @Volatile
    private var registered: Boolean = false

    // How many UI surfaces currently want the glasses link (MainActivity + PlayerActivity each
    // acquire one). The USB connection opens on the first acquire and is only released when the
    // last one lets go — so it survives the MainActivity→PlayerActivity hand-off. Without this,
    // MainActivity.onStop() used to tear the connection down the moment the player took over the
    // glasses, leaving features that read from USB (e.g. the Lazy-3D head-tracking IMU) with no
    // link for the entire playback session.
    private var acquireCount: Int = 0

    private val permissionAction = "com.teleteh.xplayer2.USB_PERMISSION"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val dev = intent.usbDevice() ?: return
                    if (!dev.isSupportedGlasses()) return
                    Log.i(TAG, "Glasses attached: ${dev.deviceName}")
                    attachOrRequestPermission(dev)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val dev = intent.usbDevice() ?: return
                    if (dev != device) return
                    Log.i(TAG, "Glasses detached: ${dev.deviceName}")
                    releaseConnection()
                    notifyState()
                }
                permissionAction -> {
                    val dev = intent.usbDevice() ?: return
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        if (dev.matchedDevice()?.brand == Brand.VITURE) {
                            // VITURE: never open/claim the device ourselves — the SDK owns it.
                            // Now that the grant exists, (re)init the SDK.
                            device = dev
                            startViture()
                            notifyState()
                        } else {
                            openDevice(dev)
                        }
                    } else {
                        Log.w(TAG, "USB permission denied for ${dev.deviceName}")
                        notifyState()
                    }
                }
            }
        }
    }

    fun setListener(l: Listener?) {
        listener = l
        notifyState()
    }

    // XREAL host devices (Beam / Beam Pro) ARE the glasses' host: the glasses are the device's own
    // display, and the host's own services + the temple button drive their display mode. We must NOT
    // claim the glasses' HID interfaces or send MCU mode commands there — doing so hijacks control
    // from the host and breaks the picture (mode conflict, one-eye, black). On such hosts the
    // controller stays passive and the app runs as a plain player; the host handles the glasses + 3D.
    // (A phone is never branded XREAL, so the normal phone + USB-glasses path is unaffected.)
    private val hostDrivesGlasses: Boolean =
        Build.MANUFACTURER.equals("XREAL", ignoreCase = true) ||
            Build.BRAND.equals("XREAL", ignoreCase = true)

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun register() {
        if (hostDrivesGlasses) {
            Log.i(TAG, "XREAL host (${Build.MANUFACTURER}/${Build.MODEL}) drives the glasses itself — controller stays passive (no HID claim / mode push)")
            notifyState()
            return
        }
        // Ref-counted: a second acquirer just bumps the count; the receiver and USB connection
        // are already up from the first.
        acquireCount++
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(permissionAction)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        registered = true
        // Hot-plug recovery: glasses might already be plugged in.
        findAttachedGlasses()?.let { attachOrRequestPermission(it) }
        notifyState()
    }

    fun unregister() {
        // Ref-counted: only the last release actually tears down the receiver + connection.
        if (acquireCount > 0) acquireCount--
        if (acquireCount > 0) return
        if (!registered) return
        try { appContext.unregisterReceiver(receiver) } catch (_: Exception) { }
        registered = false
        releaseConnection()
    }

    /** Returns true if at least one supported pair of glasses is plugged in (independent of permission). */
    fun isGlassesAttached(): Boolean = findAttachedGlasses() != null

    /**
     * Quiet re-probe (call on resume): finish bringing up an attached device we ALREADY have
     * permission for — e.g. the grant landed while the activity was paused, or a VITURE SDK init
     * failed transiently and never retried. Never prompts, so it's safe to call every onResume.
     */
    fun reprobeIfPermitted() {
        if (hostDrivesGlasses) return
        val dev = device ?: findAttachedGlasses() ?: return
        val matched = dev.matchedDevice() ?: return
        if (!usbManager.hasPermission(dev)) return
        when (matched.brand) {
            Brand.VITURE -> if (viture?.isReady() != true) {
                device = dev
                startViture()
            }
            Brand.RAYNEO -> Unit   // lazy by design — only opens from an explicit mode switch
            else -> if (connection == null) openDevice(dev)
        }
    }

    /**
     * Re-fire the system USB permission prompt for the attached glasses — the user's recovery
     * path when the chip is stuck on "needs permission" (e.g. they dismissed the first prompt).
     * No-op when there's nothing attached or permission already exists (then [reprobeIfPermitted]
     * is the right call — the picker does both).
     */
    fun requestPermissionAgain() {
        val dev = device ?: findAttachedGlasses() ?: return
        if (!usbManager.hasPermission(dev)) requestUsbPermission(dev) else reprobeIfPermitted()
    }

    fun currentState(): ConnectionState = when {
        currentBrand() == Brand.VITURE -> when {
            viture?.isReady() == true -> ConnectionState.Connected
            device != null -> ConnectionState.NeedsPermission
            else -> ConnectionState.Disconnected
        }
        // RayNeo is detected but self-managed: we open it lazily (only when the user switches mode
        // from the picker), so "present" counts as Connected — we never sit in NeedsPermission and
        // nag, and we never auto-prompt on attach.
        currentBrand() == Brand.RAYNEO -> ConnectionState.Connected
        connection != null -> ConnectionState.Connected
        device != null -> ConnectionState.NeedsPermission
        else -> ConnectionState.Disconnected
    }

    /** The matched table entry (brand/model/capabilities) for the attached glasses, or null. */
    private fun currentMatched(): SupportedDevice? = (device ?: findAttachedGlasses())?.matchedDevice()

    /** Returns the brand of the currently attached glasses, or null if none. */
    fun currentBrand(): Brand? = currentMatched()?.brand

    /** Human-readable model string for the currently attached glasses, or null. */
    fun currentModel(): String? = currentMatched()?.model

    /** True only for a RayNeo model we have a verified USB 2D/3D toggle for (gated by VID/PID). */
    private fun rayneoToggleCapable(): Boolean =
        currentMatched()?.let { it.brand == Brand.RAYNEO && it.rayneoToggle } == true

    /**
     * True only for brands/models whose 2D/3D switching we can actually drive: XREAL (HID MCU),
     * VITURE (SDK), and a RayNeo model with a verified HID toggle. Every other RayNeo (and Rokid)
     * is detected but self-managed via its own buttons, so it returns false here.
     */
    fun supportsRemoteSwitch(): Boolean =
        currentBrand() == Brand.XREAL || currentBrand() == Brand.VITURE || rayneoToggleCapable()

    /**
     * Send a `Write display mode` MCU/SDK command to the connected glasses.
     *
     * Does blocking USB I/O (XREAL/RayNeo: `bulkTransfer`/`controlTransfer` with up to ~1000ms
     * timeouts) or calls into a closed-source SDK whose blocking behaviour we don't control
     * (VITURE) — so the actual work runs on a background thread; [callback] (if given) fires on
     * the main thread once it's done. This used to run synchronously on the CALLER's thread,
     * which for a menu click meant blocking the UI thread for up to ~1s right inside input
     * dispatch — a plausible contributor to "Input dispatching timed out" ANRs, worse on slow
     * devices (this app also targets Android TV boxes) or a repeated tap before the UI caught up.
     *
     * No-op (callback invoked with false, nothing sent) if either no glasses are connected, or
     * the connected glasses' brand isn't supported for remote switching yet. The caller is
     * expected to gate UI on [currentState] and [supportsRemoteSwitch] first.
     */
    fun setDisplayMode(mode: Int, callback: ((Boolean) -> Unit)? = null) {
        val brand = currentBrand()
        Thread({
            val ok = when (brand) {
                // XREAL: HID MCU "write display mode".
                Brand.XREAL -> sendMcuCommand(GlassesProtocol.MCU_MSG_W_DISP_MODE, byteArrayOf(mode.toByte()))
                // VITURE: SDK binary 2D/3D toggle — any SBS mode means "3D on".
                Brand.VITURE -> viture?.set3d(GlassesProtocol.is3DMode(mode)) ?: false
                // RayNeo (verified models only): reverse-engineered HID 2D/3D toggle — any SBS mode = 3D.
                // Only send once we've actually opened the device (via requestRayneoControl from the
                // picker); automatic re-applies while still passive are a no-op so they never trigger
                // a USB prompt.
                Brand.RAYNEO -> if (connection != null) sendRayneoDisplayMode(GlassesProtocol.is3DMode(mode)) else false
                else -> false
            }
            mainHandler.post {
                if (ok) {
                    // Bump the write sequence so an in-flight refreshDisplayMode() read that
                    // STARTED before this write can't post its stale pre-write mode over us.
                    modeWriteSeq++
                    lastReportedMode = mode   // in-memory only — reflects what we just sent; not persisted
                    notifyState()
                }
                callback?.invoke(ok)
            }
        }, "GlassesSetMode").start()
    }

    fun lastMode(): Int = when (currentBrand()) {
        // For VITURE the truth is the SDK's live 3D state; map it to a representative mode const.
        Brand.VITURE -> if (viture?.is3dOn() == true) GlassesProtocol.MCU_DISPLAY_MODE_3840x1080_90_SBS
            else GlassesProtocol.MCU_DISPLAY_MODE_1920x1080_60
        else -> lastReportedMode
    }

    /**
     * Ask the XREAL panel what display mode it's ACTUALLY in right now (MCU "read display mode"),
     * instead of trusting [lastReportedMode] — which only reflects the last write WE sent and can
     * drift from reality (a write can silently fail to take on the panel, or the mode changed via
     * the glasses' own temple-button menu). Does blocking USB I/O, so it runs off the main thread;
     * [lastReportedMode] and the [Listener] update on the main thread once the reply lands (or not,
     * if the panel doesn't answer — some firmwares don't implement the read command).
     * No-op for VITURE (whose [lastMode] already reads the live SDK state) and RayNeo (no read
     * command reverse-engineered — see [sendRayneoDisplayMode]).
     */
    fun refreshDisplayMode() {
        if (currentBrand() != Brand.XREAL) return
        val conn = connection ?: return
        val dev = device ?: return
        // Single-flight: onStart + onResume + display-change events fire back-to-back and each
        // used to spawn its own reader thread; concurrent readers on the same IN endpoint eat
        // each other's replies (wasted retries). One in-flight query serves them all.
        if (!refreshInFlight.compareAndSet(false, true)) return
        val seqAtStart = modeWriteSeq
        Thread({
            try {
                // One retry: right after a mode switch the MCU can be mid-negotiation and miss the
                // first read (times out / doesn't answer yet) — a short pause + second try catches
                // it without the caller needing its own retry logic.
                var mode = queryDisplayModeBlocking(dev, conn)
                if (mode == null) {
                    try { Thread.sleep(250) } catch (_: InterruptedException) {}
                    mode = queryDisplayModeBlocking(dev, conn)
                }
                if (mode != null) {
                    mainHandler.post {
                        // A write that landed while we were reading is newer than what we read —
                        // don't clobber it with the pre-write panel state. Re-query instead: the
                        // caller's own post-write refresh may have been swallowed by the
                        // single-flight latch while THIS read was still in flight, so without the
                        // re-query nobody would ever read back the post-write panel state.
                        if (modeWriteSeq != seqAtStart) {
                            mainHandler.postDelayed({ refreshDisplayMode() }, 150)
                            return@post
                        }
                        if (mode != lastReportedMode) {
                            Log.i(TAG, "Glasses display mode refresh: 0x${lastReportedMode.toString(16)} -> 0x${mode.toString(16)}")
                        }
                        lastReportedMode = mode
                        notifyState()
                    }
                }
            } finally {
                refreshInFlight.set(false)
            }
        }, "GlassesModeQuery").start()
    }

    /**
     * Send the MCU "read display mode" request and read back one reply packet on the MCU
     * interface's IN endpoint. Returns the parsed mode byte, or null if the panel didn't answer
     * in time / the reply didn't parse (unknown firmware reply shape — logged for diagnosis).
     */
    private fun queryDisplayModeBlocking(dev: UsbDevice, conn: UsbDeviceConnection): Int? {
        if (!sendMcuCommand(GlassesProtocol.MCU_MSG_R_DISP_MODE, ByteArray(0))) return null
        for (i in 0 until dev.interfaceCount) {
            if (i != GlassesProtocol.MCU_INTERFACE_ID && i != 4) continue
            val intf = dev.getInterface(i)
            for (j in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(j)
                if (ep.direction == UsbConstants.USB_DIR_IN && ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                    val buf = ByteArray(GlassesProtocol.MCU_PACKET_SIZE)
                    val read = try { conn.bulkTransfer(ep, buf, buf.size, MCU_READ_TIMEOUT_MS) } catch (_: Throwable) { -1 }
                    if (read <= 0) continue
                    Log.i(TAG, "MCU disp-mode reply (iface $i, $read bytes): " +
                        buf.copyOfRange(0, read).joinToString(" ") { "%02x".format(it) })
                    // Mirrors the write layout (see sendMcuCommand): head(1) checksum(4) len(2)
                    // timestamp(8) msgid(2) reserved(5) data(<=42), data starting at offset 22.
                    // EMPIRICALLY (logged on-device): the data field's shape varies — sometimes a
                    // single byte (the mode itself), sometimes a longer field where the mode sits
                    // one byte in (e.g. "00 09 00 00 00" for mode 0x09) — so hunt the data bytes
                    // for the first one that's a KNOWN mode value rather than trust a fixed offset.
                    val packetLen = (buf[5].toInt() and 0xFF) or ((buf[6].toInt() and 0xFF) shl 8)
                    val dataLen = (packetLen - 17).coerceIn(0, (read - 22).coerceAtLeast(0))
                    for (k in 0 until dataLen) {
                        val b = buf[22 + k].toInt() and 0xFF
                        if (b in KNOWN_DISPLAY_MODES) return b
                    }
                }
            }
        }
        return null
    }


    /**
     * Hand the live USB connection + device to a feature that needs to read from a
     * different endpoint (e.g. [XrealImuReader]). Returns null until the user grants
     * USB permission and we successfully claim the HID interfaces.
     */
    fun connectionForFeature(): Pair<UsbDevice, UsbDeviceConnection>? {
        val d = device ?: return null
        val c = connection ?: return null
        return d to c
    }

    // --- internals ---

    private fun findAttachedGlasses(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { it.isSupportedGlasses() }

    private fun attachOrRequestPermission(dev: UsbDevice) {
        device = dev
        val matched = dev.matchedDevice()
        if (matched?.brand == Brand.VITURE) {
            // VITURE: the SDK owns the USB device (we never claim HID ourselves), but we drive
            // the PERMISSION through our own machinery. Relying on the SDK's internal prompt +
            // self-re-init proved unreliable in the field (Samsung Fold3/Flip6: the user grants
            // the dialog, the SDK never re-inits, and the app sits in NeedsPermission forever) —
            // so init the SDK only once the grant actually exists; the grant broadcast below
            // re-enters here via startViture().
            if (usbManager.hasPermission(dev)) startViture() else requestUsbPermission(dev)
            notifyState()
            return
        }
        // RayNeo: never PROMPT for USB on attach (the glasses self-manage 2D/3D via the temple-button
        // combo, and they're dropped from the manifest USB filter so plugging in stays silent). BUT the
        // panel reverts to 2D on every disconnect, so if the user already granted USB access earlier for
        // a toggle-capable model (Air 3s Pro), silently re-open and re-assert their saved 2D/3D mode on
        // reconnect — no new prompt. Without an existing grant we stay fully passive and open lazily only
        // when the user actually picks a mode (see [requestRayneoControl]).
        if (matched?.brand == Brand.RAYNEO) {
            if (matched.rayneoToggle && usbManager.hasPermission(dev)) {
                Log.i(TAG, "RayNeo ${matched.model} reattached with USB permission — re-asserting saved mode")
                openDevice(dev)
            } else {
                Log.i(TAG, "RayNeo ${matched.model} attached — passive (no auto permission; opens lazily on mode switch)")
                notifyState()
            }
            return
        }
        if (usbManager.hasPermission(dev)) {
            openDevice(dev)
        } else {
            requestUsbPermission(dev)
        }
    }

    /** Fire the system USB-permission prompt for [dev]; the grant comes back to [receiver]. */
    private fun requestUsbPermission(dev: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(
            appContext, 0,
            Intent(permissionAction).setPackage(appContext.packageName),
            flags
        )
        usbManager.requestPermission(dev, pi)
        notifyState()
    }

    /**
     * Lazily bring up control of a toggle-capable RayNeo (Air 3s Pro) the first time the user picks a
     * 2D/3D mode in the glasses menu. Persists the chosen [mode] and opens the device (requesting USB
     * permission if needed); the open path re-applies the saved mode, so the HID command is sent once
     * access is granted. No-op (false) for any RayNeo we don't have a verified toggle for. This is the
     * ONLY place RayNeo asks for USB permission — never on attach — so plugging the glasses in is silent.
     */
    fun requestRayneoControl(mode: Int): Boolean {
        if (!rayneoToggleCapable()) return false
        val dev = device ?: findAttachedGlasses() ?: return false
        pendingMode = mode        // apply this exact pick once the device opens (no persistence)
        lastReportedMode = mode
        if (usbManager.hasPermission(dev)) openDevice(dev) else requestUsbPermission(dev)
        return true
    }

    private fun openDevice(dev: UsbDevice) {
        val conn = usbManager.openDevice(dev)
        if (conn == null) {
            Log.e(TAG, "openDevice failed for ${dev.deviceName}")
            device = null
            notifyState()
            return
        }
        // CRITICAL: only claim HID interfaces. XREAL Air is a composite USB device that also
        // exposes a USB Audio Class interface (stereo over USB) and a display interface — if we
        // touch those at all (even with force=false) we risk yanking them away from Android's
        // audio / display drivers, killing system-wide audio. The HID interfaces, on the other
        // hand, the kernel always grabs first with the generic HID driver, so we DO need
        // force=true there to take over — otherwise bulkTransfer on the MCU endpoint silently
        // fails and the 2D/3D mode toggle stops working.
        claimedInterfaces.clear()
        for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            if (intf.interfaceClass != UsbConstants.USB_CLASS_HID) {
                continue
            }
            // force=true is safe here: the only thing competing for an XREAL HID interface is
            // Android's generic kernel HID handler, not the audio / display HALs. Those live on
            // different interfaces (class != HID) which we skipped above.
            val ok = conn.claimInterface(intf, true)
            if (ok) {
                claimedInterfaces.add(intf)
            } else {
                Log.w(TAG, "Failed to claim HID interface $i — MCU commands may not reach the glasses")
            }
        }
        device = dev
        connection = conn
        // We no longer force a remembered mode on connect — it sometimes restored the WRONG mode.
        // The panel powers on in 2D, so reflect that; apply ONLY a mode the user explicitly picked
        // while the device was still opening (RayNeo lazy-open), then forget it.
        lastReportedMode = GlassesProtocol.MCU_DISPLAY_MODE_1920x1080_60
        Log.i(TAG, "Glasses connected: ${dev.deviceName}, ${claimedInterfaces.size} HID interface(s) claimed")
        notifyState()
        pendingModePush?.let { mainHandler.removeCallbacks(it) }
        val pending = pendingMode
        pendingMode = null
        val push = Runnable {
            pendingModePush = null
            // Confirm the panel's ACTUAL mode once the MCU has had time to come up — a write can
            // silently fail to take, or the panel could already be in a different mode than our
            // 2D power-on assumption above (e.g. it never actually powered off). setDisplayMode is
            // async now, so chain the refresh through its callback rather than firing both at once
            // (which could read back before the write actually lands).
            if (pending != null) setDisplayMode(pending) { refreshDisplayMode() }
            else refreshDisplayMode()
        }
        pendingModePush = push
        mainHandler.postDelayed(push, MODE_PUSH_DELAY_MS)
        // IMU is started on demand (setImuStreaming) only while the head-gesture menu is shown,
        // so it doesn't burn power streaming continuously while connected.
    }

    /**
     * Bring up the VITURE SDK for an attached VITURE device. init()/3D-state changes return
     * asynchronously via the callback, so we (re)apply the saved 2D/3D choice once the SDK reports
     * ready — whether that's immediately or after the user grants USB permission.
     */
    private fun startViture() {
        if (vitureStarting) return
        val vc = viture ?: VitureController(appContext).also { created ->
            // init() / 3D-state changes return on the SDK's callback thread — funnel them onto
            // the main thread and (re)apply the saved 2D/3D choice once the SDK reports ready.
            created.setListener { mainHandler.post { onVitureStateChanged(created) } }
            viture = created
        }
        if (vc.isReady()) { onVitureStateChanged(vc); return }
        // init() does blocking USB I/O — run it off the main thread so attach doesn't jank.
        vitureStarting = true
        Thread({
            val code = vc.init()
            Log.i(TAG, "VITURE init=$code")
            mainHandler.post {
                vitureStarting = false
                onVitureStateChanged(vc)
            }
        }, "VitureInit").start()
    }

    /** Runs on the main thread when VITURE init/3D state changes: just refresh UI. We no longer force
     *  a remembered mode — the panel keeps its current state and lastMode() reads the live SDK 3D
     *  state, so the picker title reflects reality; the user switches from the menu. */
    private fun onVitureStateChanged(vc: VitureController) {
        notifyState()
    }

    private fun releaseConnection() {
        stopImuTelemetry()
        // VITURE's ArManager.release() does blocking native USB/teardown work. releaseConnection()
        // runs on the MAIN thread (onStop / USB-detach), and on hosts that sandbox the app so the
        // SDK's USB / netlink-uevent access is SELinux-denied (e.g. a RayNeo Pocket TV / SEI
        // Android-TV box) that native teardown blocks or spins — which froze the UI into a
        // black-screen ANR ("Application does not have a focused window") when leaving for the file
        // picker. Hand it off to a detached thread, mirroring the off-main init() in startViture(),
        // so the main thread never waits on it (a wedged SDK thread leaks, but the UI stays alive).
        val vc = viture
        viture = null
        if (vc != null) Thread({ try { vc.release() } catch (_: Throwable) {} }, "VitureRelease").start()
        vitureModeApplied = false
        vitureStarting = false
        // Cancel only OUR deferred connect-time mode push. A blanket
        // removeCallbacksAndMessages(null) here used to also delete pending setDisplayMode
        // result posts (their callbacks silently never fired) and VITURE state updates.
        pendingModePush?.let { mainHandler.removeCallbacks(it) }
        pendingModePush = null
        val c = connection
        if (c != null) {
            for (intf in claimedInterfaces) {
                try { c.releaseInterface(intf) } catch (_: Exception) { }
            }
            try { c.close() } catch (_: Exception) { }
        }
        claimedInterfaces.clear()
        connection = null
        device = null
    }

    /**
     * Start streaming IMU samples into [headOrientation] (XREAL only). The start command does
     * blocking USB I/O, so it runs off the main thread. No-op if already running or not XREAL.
     */
    private fun startImuTelemetry() {
        if (imuReader != null) return
        if (currentBrand() != Brand.XREAL) return
        val dev = device ?: return
        val conn = connection ?: return
        headOrientation.reset()
        imuHasSample = false
        val reader = XrealImuReader(dev, conn)
        imuReader = reader
        Thread({
            val ok = reader.start { gx, gy, gz, ax, ay, az, temp, t ->
                headOrientation.accumulate(gx, gy, gz, t)
                imuGx = gx; imuGy = gy; imuGz = gz
                imuAx = ax; imuAy = ay; imuAz = az; imuTemp = temp
                imuHasSample = true
            }
            if (ok) {
                Log.i(TAG, "IMU telemetry streaming")
            } else {
                Log.w(TAG, "IMU telemetry failed to start")
                if (imuReader === reader) imuReader = null
            }
        }, "GlassesImuStart").start()
    }

    private fun stopImuTelemetry() {
        val r = imuReader ?: return
        imuReader = null
        Thread({ try { r.stop() } catch (_: Throwable) {} }, "GlassesImuStop").start()
    }

    /**
     * Current head orientation as [yawDeg, pitchDeg, rollDeg], or null when no IMU telemetry is
     * flowing (not XREAL / not started / no samples yet). World-anchored visuals (the screensaver
     * platform) draw only when this is non-null.
     */
    fun headOrientationDegrees(): FloatArray? {
        if (imuReader == null || !headOrientation.hasSamples()) return null
        return floatArrayOf(headOrientation.yawDeg, headOrientation.pitchDeg, headOrientation.rollDeg)
    }

    /** Latest raw IMU sample [gx,gy,gz, ax,ay,az, tempRaw] for the debug HUD, or null. Allocates
     *  only when polled (~20 Hz), not per IMU packet. */
    fun latestImuDebug(): FloatArray? =
        if (imuReader != null && imuHasSample)
            floatArrayOf(imuGx, imuGy, imuGz, imuAx, imuAy, imuAz, imuTemp)
        else null

    /** Stream the IMU only while it's actually needed (head-gesture menu shown) to save power. */
    fun setImuStreaming(enable: Boolean) {
        if (enable) startImuTelemetry() else stopImuTelemetry()
    }

    private fun sendMcuCommand(msgId: Int, data: ByteArray): Boolean {
        val conn = connection ?: return false
        val dev = device ?: return false

        // Packet layout: head(1) + checksum(4) + length(2) + timestamp(8) + msgid(2) + reserved(5) + data(<=42)
        val packet = ByteArray(GlassesProtocol.MCU_PACKET_SIZE)
        packet[0] = GlassesProtocol.MCU_PACKET_HEAD
        val dataLen = minOf(data.size, 42)
        val packetLen = 17 + dataLen
        packet[5] = (packetLen and 0xFF).toByte()
        packet[6] = ((packetLen shr 8) and 0xFF).toByte()
        // timestamp 7..14 left zero
        packet[15] = (msgId and 0xFF).toByte()
        packet[16] = ((msgId shr 8) and 0xFF).toByte()
        data.copyInto(packet, 22, 0, dataLen)

        val checksum = GlassesProtocol.calculateCrc32(packet.copyOfRange(5, 5 + packetLen))
        packet[1] = (checksum and 0xFF).toByte()
        packet[2] = ((checksum shr 8) and 0xFF).toByte()
        packet[3] = ((checksum shr 16) and 0xFF).toByte()
        packet[4] = ((checksum shr 24) and 0xFF).toByte()

        // Pick the first interrupt OUT endpoint on the candidate MCU interfaces.
        for (i in 0 until dev.interfaceCount) {
            if (i != GlassesProtocol.MCU_INTERFACE_ID && i != 4) continue
            val intf = dev.getInterface(i)
            for (j in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(j)
                if (ep.direction == UsbConstants.USB_DIR_OUT &&
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                    // try/catch: this now runs on background threads that can race a USB detach —
                    // some OEM stacks throw (rather than return -1) on a connection closed mid-call,
                    // and an uncaught throw on a bare Thread takes down the whole process.
                    val sent = try {
                        conn.bulkTransfer(ep, packet, GlassesProtocol.MCU_PACKET_SIZE, 1000)
                    } catch (t: Throwable) {
                        Log.w(TAG, "MCU command send threw: ${t.message}"); -1
                    }
                    if (sent == GlassesProtocol.MCU_PACKET_SIZE) {
                        Log.i(TAG, "MCU command 0x${msgId.toString(16)} sent via interface $i")
                        return true
                    } else {
                        Log.w(TAG, "MCU command short send: $sent bytes")
                    }
                }
            }
        }
        Log.w(TAG, "No suitable MCU OUT endpoint found")
        return false
    }

    /**
     * RayNeo Air HID display-mode toggle — verified models only (double-gated by [rayneoToggleCapable]
     * so the frame can only ever reach a known RayNeo VID/PID, never another device).
     *
     * Sends one 64-byte HID output report — magic 0x66, command (0x06 = 3D SBS on / 0x07 = 2D),
     * value 0, zero-padded, NO checksum — first as a HID SET_REPORT class request (bmRequestType
     * 0x21, bRequest 0x09, wValue 0x0200 = Output report id 0), then, if that's refused, as an
     * interrupt/bulk OUT write on the HID interface's OUT endpoint. Everything is wrapped so a
     * misbehaving or unexpected device can only ever make this return false, never throw/crash.
     * Protocol: verncat/RayNeo-Air-3S-Pro-OpenVR (MIT), cross-checked against RayNeo's FXR headers.
     */
    private fun sendRayneoDisplayMode(on: Boolean): Boolean {
        if (!rayneoToggleCapable()) return false
        val conn = connection ?: return false
        val dev = device ?: return false
        return try {
            val frame = ByteArray(GlassesProtocol.RAYNEO_FRAME_SIZE)
            frame[0] = GlassesProtocol.RAYNEO_SEND_MAGIC
            frame[1] = if (on) GlassesProtocol.RAYNEO_CMD_DISPLAY_3D else GlassesProtocol.RAYNEO_CMD_DISPLAY_2D
            // frame[2] (value) and the remaining 61 bytes stay 0.
            val hid = (0 until dev.interfaceCount)
                .map { dev.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
            if (hid == null) {
                Log.w(TAG, "RayNeo: no HID interface to send display toggle")
                return false
            }
            // 1) HID SET_REPORT (Output report, id 0) on the HID interface.
            val rc = conn.controlTransfer(0x21, 0x09, 0x0200, hid.id, frame, frame.size, 500)
            if (rc == frame.size) {
                Log.i(TAG, "RayNeo display ${if (on) "3D" else "2D"} via SET_REPORT (iface ${hid.id})")
                return true
            }
            // 2) Fallback: interrupt/bulk OUT on the HID interface's OUT endpoint.
            for (j in 0 until hid.endpointCount) {
                val ep = hid.getEndpoint(j)
                if (ep.direction == UsbConstants.USB_DIR_OUT) {
                    val sent = conn.bulkTransfer(ep, frame, frame.size, 500)
                    if (sent == frame.size) {
                        Log.i(TAG, "RayNeo display ${if (on) "3D" else "2D"} via OUT endpoint")
                        return true
                    }
                }
            }
            Log.w(TAG, "RayNeo display toggle: SET_REPORT rc=$rc and no OUT endpoint accepted it")
            false
        } catch (t: Throwable) {
            Log.w(TAG, "RayNeo display toggle threw — ignored", t)
            false
        }
    }

    private fun notifyState() {
        listener?.onChanged(currentState(), lastReportedMode)
    }

    private fun UsbDevice.isSupportedGlasses(): Boolean = matchedDevice() != null

    private fun UsbDevice.matchedDevice(): SupportedDevice? =
        SUPPORTED_DEVICES.firstOrNull { it.vid == vendorId && it.pid == productId }

    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else
            @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)

    // [rayneoToggle] = we have a verified per-model USB HID command to flip this RayNeo's 2D/3D
    // (see [sendRayneoDisplayMode]). Only models we've actually confirmed get it; every other RayNeo
    // stays passive (detect + hint, user uses the temple-button combo) so we never poke an unverified
    // device with a command that might mean something else on its firmware.
    private data class SupportedDevice(
        val vid: Int,
        val pid: Int,
        val brand: Brand,
        val model: String,
        val rayneoToggle: Boolean = false,
    )

    companion object {
        private const val TAG = "GlassesController"
        private const val PREFS = "glasses_prefs"
        private const val KEY_DISPLAY_MODE = "display_mode"
        // Small delay after enumeration before pushing the saved mode — gives the MCU time to
        // come up so the command isn't dropped on the floor right after attach.
        private const val MODE_PUSH_DELAY_MS = 700L
        // Short: this blocks a background thread and the caller (menu/UI open) is waiting on it.
        private const val MCU_READ_TIMEOUT_MS = 300
        // Every value GlassesProtocol.MCU_DISPLAY_MODE_* defines — used to pick the mode byte out
        // of a "read display mode" reply whose data field's shape isn't fixed (see queryDisplayModeBlocking).
        private val KNOWN_DISPLAY_MODES = setOf(
            GlassesProtocol.MCU_DISPLAY_MODE_1920x1080_60,
            GlassesProtocol.MCU_DISPLAY_MODE_3840x1080_60_SBS,
            GlassesProtocol.MCU_DISPLAY_MODE_3840x1080_72_SBS,
            GlassesProtocol.MCU_DISPLAY_MODE_1920x1080_72,
            GlassesProtocol.MCU_DISPLAY_MODE_1920x1080_60_SBS,
            GlassesProtocol.MCU_DISPLAY_MODE_3840x1080_90_SBS,
            GlassesProtocol.MCU_DISPLAY_MODE_1920x1080_90,
            GlassesProtocol.MCU_DISPLAY_MODE_1920x1080_120,
        )

        /**
         * Static "are any supported XR glasses plugged in?" USB scan — needs NO controller instance,
         * so PlayerActivity can use it even when reached directly via a shared link (before the main
         * screen created its controller). Used to decide whether to offer Lazy 3D.
         */
        fun anyAttached(context: Context): Boolean = try {
            val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
            usb.deviceList.values.any { d ->
                SUPPORTED_DEVICES.any { it.vid == d.vendorId && it.pid == d.productId }
            }
        } catch (_: Throwable) { false }

        // VID/PID list mirrored from wheaney/XRLinuxDriver (covers every model that ships at the time
        // of writing). Brand identification is exposed via [currentBrand]; remote 2D/3D switching is
        // implemented for XREAL (HID MCU) and VITURE (bundled VITURE One SDK — see VitureController).
        private val SUPPORTED_DEVICES = listOf(
            // XREAL Air series (open MCU protocol, fully supported here)
            SupportedDevice(0x3318, 0x0424, Brand.XREAL, "Air"),
            SupportedDevice(0x3318, 0x0428, Brand.XREAL, "Air 2"),
            SupportedDevice(0x3318, 0x0432, Brand.XREAL, "Air 2 Pro"),
            SupportedDevice(0x3318, 0x0426, Brand.XREAL, "Air 2 Ultra"),
            SupportedDevice(0x3318, 0x0435, Brand.XREAL, "One Pro"),
            SupportedDevice(0x3318, 0x0436, Brand.XREAL, "One Pro"),
            SupportedDevice(0x3318, 0x0437, Brand.XREAL, "One"),
            SupportedDevice(0x3318, 0x0438, Brand.XREAL, "One"),
            SupportedDevice(0x3318, 0x043e, Brand.XREAL, "One S"),

            // VITURE — 2D/3D switching via the bundled VITURE One SDK (see VitureController).
            SupportedDevice(0x35ca, 0x1011, Brand.VITURE, "One"),
            SupportedDevice(0x35ca, 0x1013, Brand.VITURE, "One"),
            SupportedDevice(0x35ca, 0x1017, Brand.VITURE, "One"),
            SupportedDevice(0x35ca, 0x1015, Brand.VITURE, "One Lite"),
            SupportedDevice(0x35ca, 0x101b, Brand.VITURE, "One Lite"),
            SupportedDevice(0x35ca, 0x1019, Brand.VITURE, "Pro"),
            SupportedDevice(0x35ca, 0x101d, Brand.VITURE, "Pro"),
            SupportedDevice(0x35ca, 0x1131, Brand.VITURE, "Luma"),
            SupportedDevice(0x35ca, 0x1121, Brand.VITURE, "Luma Pro"),
            SupportedDevice(0x35ca, 0x1141, Brand.VITURE, "Luma Pro"),
            SupportedDevice(0x35ca, 0x1101, Brand.VITURE, "Luma Ultra"),
            SupportedDevice(0x35ca, 0x1104, Brand.VITURE, "Luma Ultra"),
            SupportedDevice(0x35ca, 0x1151, Brand.VITURE, "Luma Cyber"),
            SupportedDevice(0x35ca, 0x1201, Brand.VITURE, "Beast"),

            // TCL / RayNeo Air series — USB-C DisplayPort glasses that self-manage 2D/3D via a
            // physical temple-button combo (Volume + Brightness together). No vendor Android SDK
            // exists for them, so there's no .so and no Play 16 KB concern. The Air 3s Pro additionally
            // accepts a reverse-engineered HID display-mode toggle (verncat/RayNeo-Air-3S-Pro-OpenVR,
            // MIT) which we drive from [sendRayneoDisplayMode] — flagged per-PID so it's the ONLY
            // RayNeo we ever send that command to. Other RayNeo PIDs stay passive (detect + hint).
            SupportedDevice(0x1bbb, 0xaf50, Brand.RAYNEO, "Air 3s Pro", rayneoToggle = true),

            // Rokid — detection only. Switching requires the closed Rokid SDK.
            // (Rokid VID is published as the literal "ROKID_GLASS_VID" macro in the upstream driver;
            //  the public source doesn't currently expose the numeric value, so until we can grep a
            //  paired glasses' descriptor block we leave the entry as a stub. Open an issue if you
            //  have a Rokid Max/Air handy and we'll fill the VID in.)
        )
    }
}
