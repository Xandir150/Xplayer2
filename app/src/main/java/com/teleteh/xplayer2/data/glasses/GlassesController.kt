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
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Talks to XR glasses over USB HID using the MCU protocol from [GlassesProtocol].
 *
 * The controller owns a lazily-created connection, watches USB attach/detach
 * events and surfaces a simple connection state + a "set display mode"
 * operation that the main activity can wire to a toolbar button.
 *
 * Currently recognises:
 *  - XREAL Air / Air 2 / Air 2 Pro / Air 2 Ultra (VID 0x3318, PID 0x0432)
 *
 * VITURE One/Pro glasses speak a different protocol (closed SDK) and are not
 * supported by this controller yet — but the device list below is a single
 * place to extend when those VIDs/PIDs become available.
 */
class GlassesController(private val appContext: Context) {

    enum class ConnectionState { Disconnected, NeedsPermission, Connected }

    fun interface Listener {
        fun onChanged(state: ConnectionState, currentMode: Int)
    }

    private val usbManager: UsbManager =
        appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private var device: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var listener: Listener? = null
    private var lastReportedMode: Int = GlassesProtocol.MCU_DISPLAY_MODE_1920x1080_60

    @Volatile
    private var registered: Boolean = false

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

    /**
     * Send a `Write display mode` MCU command to the connected glasses. No-op if no glasses are connected
     * (the caller is expected to gate UI on [currentState] first). Returns true if the bulk transfer succeeded.
     */
    fun setDisplayMode(mode: Int): Boolean {
        val ok = sendMcuCommand(GlassesProtocol.MCU_MSG_W_DISP_MODE, byteArrayOf(mode.toByte()))
        if (ok) {
            lastReportedMode = mode
            notifyState()
        }
        return ok
    }

    fun lastMode(): Int = lastReportedMode

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
        // Claim every interface — we only know empirically which one carries the MCU OUT endpoint.
        for (i in 0 until dev.interfaceCount) {
            conn.claimInterface(dev.getInterface(i), true)
        }
        device = dev
        connection = conn
        Log.i(TAG, "Glasses connected: ${dev.deviceName}")
        notifyState()
    }

    private fun releaseConnection() {
        val c = connection
        val d = device
        if (c != null && d != null) {
            for (i in 0 until d.interfaceCount) {
                try { c.releaseInterface(d.getInterface(i)) } catch (_: Exception) { }
            }
            try { c.close() } catch (_: Exception) { }
        }
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

    private fun UsbDevice.isSupportedGlasses(): Boolean =
        SUPPORTED_DEVICES.any { it.vid == vendorId && it.pid == productId }

    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else
            @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private data class SupportedDevice(val vid: Int, val pid: Int)

    companion object {
        private const val TAG = "GlassesController"

        private val SUPPORTED_DEVICES = listOf(
            // XREAL Air series. Verified against XREAL Air 2 Pro (xspace).
            SupportedDevice(vid = 0x3318, pid = 0x0432),
        )
    }
}
