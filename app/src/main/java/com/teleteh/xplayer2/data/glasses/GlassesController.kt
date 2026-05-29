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
    // The user's chosen display mode is sticky: seeded from persisted prefs, not the hardware.
    private var lastReportedMode: Int = savedMode()
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
                        openDevice(dev)
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

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun register() {
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

    fun currentState(): ConnectionState = when {
        connection != null -> ConnectionState.Connected
        device != null -> ConnectionState.NeedsPermission
        else -> ConnectionState.Disconnected
    }

    /** Returns the brand of the currently attached glasses, or null if none. */
    fun currentBrand(): Brand? = (device ?: findAttachedGlasses())?.matchedDevice()?.brand

    /** Human-readable model string for the currently attached glasses, or null. */
    fun currentModel(): String? = (device ?: findAttachedGlasses())?.matchedDevice()?.model

    /** True only for brands whose protocol we actually speak. Other brands need their vendor SDK. */
    fun supportsRemoteSwitch(): Boolean = currentBrand() == Brand.XREAL

    /**
     * Send a `Write display mode` MCU command to the connected glasses.
     *
     * No-op (returns false) if either:
     *   - no glasses are connected, or
     *   - the connected glasses' brand isn't supported for remote switching yet.
     *
     * The caller is expected to gate UI on [currentState] and [supportsRemoteSwitch] first.
     */
    fun setDisplayMode(mode: Int): Boolean {
        if (!supportsRemoteSwitch()) return false
        val ok = sendMcuCommand(GlassesProtocol.MCU_MSG_W_DISP_MODE, byteArrayOf(mode.toByte()))
        if (ok) {
            lastReportedMode = mode
            // Remember the choice so we can re-assert it on the next (re)connect.
            prefs.edit().putInt(KEY_DISPLAY_MODE, mode).apply()
            notifyState()
        }
        return ok
    }

    fun lastMode(): Int = lastReportedMode

    /** The display mode the user last selected (persisted), or the 2D default if none. */
    private fun savedMode(): Int =
        prefs.getInt(KEY_DISPLAY_MODE, GlassesProtocol.MCU_DISPLAY_MODE_1920x1080_60)

    /**
     * Re-send the persisted display mode to the connected glasses so the panel returns to the
     * mode the user chose. Called automatically a beat after every (re)connect, and can be
     * called when the external panel powers back on (proximity sensor) without a full USB
     * re-enumeration. No-op if disconnected or the brand isn't remotely switchable.
     */
    fun reapplySavedMode(): Boolean = setDisplayMode(savedMode())

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
        if (usbManager.hasPermission(dev)) {
            openDevice(dev)
        } else {
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
                Log.d(TAG, "Skipping non-HID interface $i (class=0x${intf.interfaceClass.toString(16)}) — leaving it to the kernel")
                continue
            }
            // force=true is safe here: the only thing competing for an XREAL HID interface is
            // Android's generic kernel HID handler, not the audio / display HALs. Those live on
            // different interfaces (class != HID) which we skipped above.
            val ok = conn.claimInterface(intf, true)
            if (ok) {
                claimedInterfaces.add(intf)
                Log.d(TAG, "Claimed HID interface $i (subclass=0x${intf.interfaceSubclass.toString(16)})")
            } else {
                Log.w(TAG, "Failed to claim HID interface $i — MCU commands may not reach the glasses")
            }
        }
        device = dev
        connection = conn
        // The chosen display mode is sticky across reconnects: the picker remembers the user's
        // choice rather than reading the hardware, and we actively push that mode to the glasses
        // a beat after enumeration so they switch to it on their own. This covers both
        // unplug/replug and the proximity sensor re-powering the panel when the goggles are put
        // back on. (We can't read the mode back reliably across all firmware, hence push-not-poll.)
        lastReportedMode = savedMode()
        Log.i(TAG, "Glasses connected: ${dev.deviceName}, ${claimedInterfaces.size} HID interface(s) claimed; restoring saved mode 0x${lastReportedMode.toString(16)}")
        notifyState()
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({ reapplySavedMode() }, MODE_PUSH_DELAY_MS)
    }

    private fun releaseConnection() {
        mainHandler.removeCallbacksAndMessages(null)
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
                    val sent = conn.bulkTransfer(ep, packet, GlassesProtocol.MCU_PACKET_SIZE, 1000)
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

    private data class SupportedDevice(val vid: Int, val pid: Int, val brand: Brand, val model: String)

    companion object {
        private const val TAG = "GlassesController"
        private const val PREFS = "glasses_prefs"
        private const val KEY_DISPLAY_MODE = "display_mode"
        // Small delay after enumeration before pushing the saved mode — gives the MCU time to
        // come up so the command isn't dropped on the floor right after attach.
        private const val MODE_PUSH_DELAY_MS = 700L

        // VID/PID list mirrored from wheaney/XRLinuxDriver (covers every model that ships at the time
        // of writing). Brand identification is exposed via [currentBrand]; remote switching is only
        // implemented for XREAL right now — see the class-level kdoc for the protocol situation.
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

            // VITURE — detection only. Switching requires libviture_one_sdk.
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

            // TCL / RayNeo — detection only. Switching requires the closed RayNeo SDK.
            SupportedDevice(0x1bbb, 0xaf50, Brand.RAYNEO, "NXTWEAR / Air series"),

            // Rokid — detection only. Switching requires the closed Rokid SDK.
            // (Rokid VID is published as the literal "ROKID_GLASS_VID" macro in the upstream driver;
            //  the public source doesn't currently expose the numeric value, so until we can grep a
            //  paired glasses' descriptor block we leave the entry as a stub. Open an issue if you
            //  have a Rokid Max/Air handy and we'll fill the VID in.)
        )
    }
}
