package com.teleteh.xplayer2.data.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Decides whether the connected glasses can be controlled, and applies the settings.
 *
 * Control exists only for glasses that answer on the XREAL One control port. Nothing is shown for
 * any other model (Air-series glasses, other brands, a plain display): the probe fails,
 * [State.available] stays false and the UI that depends on it never appears.
 *
 * The glasses report neither their brightness nor their dimmer level, so those two show the last
 * value this app sent. The display mode is read back from the glasses when connecting.
 *
 * All state changes happen on the main thread.
 */
class XrealOneController private constructor(
    context: Context,
    private val factory: XrealOneSocketFactory,
) {
    enum class Failure { COMMAND_REJECTED }

    data class State(
        /** True once the glasses answered a control request. The only switch for showing controls. */
        val available: Boolean = false,
        val glassesId: String? = null,
        /** "XREAL One Pro" when the USB identity says so, otherwise just "XREAL". */
        val name: String = "XREAL",
        val displayConfiguration: XrealOneDisplayConfiguration? = null,
        val brightness: Int? = null,
        val dimmer: XrealOneDimmer? = null,
        val applying: Boolean = false,
        val failure: Failure? = null,
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(
        State(
            brightness = prefs.getInt(KEY_BRIGHTNESS, -1).takeIf { it in XrealOneBrightness.RANGE },
            dimmer = XrealOneDimmer.fromValue(prefs.getInt(KEY_DIMMER, -1)),
        )
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private var session: XrealOneSession? = null
    private var probeJob: Job? = null
    private var pendingCommands = 0
    private var started = false

    // region eligibility

    /** Starts watching for glasses. Idempotent. */
    fun start() {
        if (started) return
        started = true
        val display = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        display.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = refresh()
            override fun onDisplayRemoved(displayId: Int) = refresh()
            override fun onDisplayChanged(displayId: Int) = Unit
        }, mainHandler)
        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(appContext, object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = refresh()
        }, usbFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registerEthernetCallback()
        refresh()
    }

    /**
     * The USB network can come up long after the display does. Whenever an Ethernet network
     * appears the probe gets another chance, even after its first series of attempts has ended.
     */
    private fun registerEthernetCallback() {
        try {
            val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivity.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Ethernet network available: $network")
                    mainHandler.post { refresh() }
                }

                override fun onLost(network: Network) {
                    mainHandler.post { refresh() }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "cannot watch Ethernet networks", e)
        }
    }

    /** Re-evaluates whether glasses that may be an XREAL One are attached, and reacts. */
    fun refresh() {
        val eligible = isEligible(
            GlassesPresence.present(appContext),
            GlassesController.attachedModels(appContext),
        )
        if (eligible) startProbing() else disconnect()
    }

    // endregion

    // region settings

    fun setDisplayConfiguration(value: XrealOneDisplayConfiguration) =
        apply({ it.setDisplayConfiguration(value) }) { _state.update { s -> s.copy(displayConfiguration = value) } }

    fun setBrightness(level: Int) {
        val clamped = level.coerceIn(XrealOneBrightness.RANGE)
        apply({ it.setBrightness(clamped) }) {
            prefs.edit().putInt(KEY_BRIGHTNESS, clamped).apply()
            _state.update { s -> s.copy(brightness = clamped) }
        }
    }

    fun setDimmer(value: XrealOneDimmer) = apply({ it.setDimmer(value) }) {
        prefs.edit().putInt(KEY_DIMMER, value.value).apply()
        _state.update { s -> s.copy(dimmer = value) }
    }

    fun dismissFailure() = _state.update { it.copy(failure = null) }

    // endregion

    // region connection

    private fun startProbing() {
        if (probeJob?.isActive == true || _state.value.available) return
        probeJob = scope.launch {
            for (wait in PROBE_SCHEDULE_MS) {
                delay(wait)
                if (probeOnce()) break
            }
        }
    }

    /** One connection attempt. Returns true when the glasses answered. */
    private suspend fun probeOnce(): Boolean {
        val candidate = XrealOneSession(factory)
        try {
            val id = candidate.open()
            // A failed read is not fatal: the controls work without knowing the current mode.
            val configuration = try {
                candidate.displayConfiguration()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (!currentCoroutineContext().isActive) {
                candidate.close()
                return true
            }
            session = candidate
            val model = GlassesController.attachedModels(appContext)[GlassesController.Brand.XREAL]
            _state.update {
                it.copy(
                    available = true,
                    glassesId = id,
                    name = model?.let { m -> "XREAL $m" } ?: "XREAL",
                    displayConfiguration = configuration,
                    failure = null,
                )
            }
            Log.i(TAG, "XREAL One control channel is up: id=$id mode=$configuration")
            return true
        } catch (e: CancellationException) {
            candidate.close()
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "XREAL One probe failed: ${e.message}")
            candidate.close()
            return false
        }
    }

    private fun disconnect() {
        probeJob?.cancel()
        probeJob = null
        session?.close()
        session = null
        pendingCommands = 0
        _state.update {
            it.copy(available = false, glassesId = null, name = "XREAL", displayConfiguration = null,
                applying = false, failure = null)
        }
    }

    /** The link broke while the glasses are still attached: drop it and look for them again. */
    private fun linkLost() {
        disconnect()
        refresh()
    }

    private fun apply(command: suspend (XrealOneSession) -> Unit, onSuccess: () -> Unit) {
        val current = session ?: return
        if (!_state.value.available) return
        pendingCommands++
        _state.update { it.copy(applying = true, failure = null) }
        scope.launch {
            try {
                command(current)
                if (session !== current) return@launch
                onSuccess()
                finishCommand()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (session !== current) return@launch
                finishCommand()
                if ((e as? XrealOneError)?.isLinkLoss == true) {
                    linkLost()
                } else {
                    _state.update { it.copy(failure = Failure.COMMAND_REJECTED) }
                }
            }
        }
    }

    private fun finishCommand() {
        pendingCommands = maxOf(pendingCommands - 1, 0)
        _state.update { it.copy(applying = pendingCommands > 0) }
    }

    // endregion

    companion object {
        private const val TAG = "XrealOneController"
        private const val PREFS = "xreal_one_prefs"
        private const val KEY_BRIGHTNESS = "brightness"
        private const val KEY_DIMMER = "dimmer"

        /**
         * Waits before each connection attempt, about 30 s in all. The USB network adapter can
         * come up a few seconds after the display does.
         */
        val PROBE_SCHEDULE_MS = listOf(0L, 2_000L, 3_000L, 5_000L, 5_000L, 5_000L, 5_000L, 5_000L)

        /**
         * Glasses are worth probing when something is attached, unless the USB identity says they
         * are another brand or an XREAL Air (which has no control port). An unreadable identity (a
         * plain dongle, newer hardware) must not rule the glasses out: the probe itself is the gate.
         */
        fun isEligible(presentOnPhone: Boolean, models: Map<GlassesController.Brand, String>): Boolean {
            if (!presentOnPhone) return false
            if (models.isEmpty()) return true
            val xreal = models[GlassesController.Brand.XREAL] ?: return false
            return !xreal.startsWith("Air")
        }

        @Volatile private var instance: XrealOneController? = null

        fun get(context: Context): XrealOneController = instance ?: synchronized(this) {
            instance ?: XrealOneController(context, XrealOneNetwork(context)).also { instance = it }
        }
    }
}
