package com.teleteh.xplayer2.data.glasses

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Finds the USB network the glasses create and opens sockets on it.
 *
 * The glasses' address, 169.254.2.1, is link-local: it exists only on the USB adapter. A socket
 * left to Android's default routing would go out over Wi-Fi or mobile data and never arrive, so the
 * socket is bound to the glasses' [Network] before it connects.
 *
 * Whether Android brings that adapter up at all depends on the phone: the kernel needs a CDC-NCM
 * driver and the system has to accept an Ethernet interface without internet. When no such
 * network exists, [connect] fails with [XrealOneError.Unreachable] and the controls stay hidden.
 */
class XrealOneNetwork(context: Context) : XrealOneSocketFactory {
    private val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun connect(host: String, port: Int, timeoutMs: Int): Socket {
        val socket = Socket()
        try {
            val network = findNetwork()
            if (network != null) {
                network.bindSocket(socket)
            } else {
                // No Network object (some phones expose the adapter only as a bare interface): bind
                // to its own address, which at least keeps the packet off Wi-Fi and mobile data.
                val local = findLocalAddress()
                    ?: throw XrealOneError.Unreachable("no USB network with a 169.254.x.x address")
                socket.bind(InetSocketAddress(local, 0))
            }
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            return socket
        } catch (e: Throwable) {
            runCatching { socket.close() }
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun findNetwork(): Network? = try {
        connectivity.allNetworks.firstOrNull { network ->
            val addresses = connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().map { it.address }
            isGlassesLink(connectivity.getNetworkCapabilities(network), addresses)
        }
    } catch (e: SecurityException) {
        Log.w(TAG, "cannot list networks", e)
        null
    }

    private fun findLocalAddress(): InetAddress? = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && !isCarrierInterface(it.name) }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is Inet4Address && it.isLinkLocalAddress }
    } catch (e: IOException) {
        null
    }

    companion object {
        private const val TAG = "XrealOneNetwork"

        /**
         * Whether a network is the glasses' USB link: an IPv4 link-local address on something that
         * is not Wi-Fi, mobile data or a VPN. (A Wi-Fi network that failed DHCP also holds a
         * 169.254 address; it must not be mistaken for the glasses.)
         */
        fun isGlassesLink(capabilities: NetworkCapabilities?, addresses: List<InetAddress>): Boolean {
            if (capabilities != null && listOf(
                    NetworkCapabilities.TRANSPORT_WIFI,
                    NetworkCapabilities.TRANSPORT_CELLULAR,
                    NetworkCapabilities.TRANSPORT_VPN,
                    NetworkCapabilities.TRANSPORT_BLUETOOTH,
                ).any { capabilities.hasTransport(it) }
            ) return false
            return addresses.any { it is Inet4Address && it.isLinkLocalAddress }
        }

        /** Wi-Fi and mobile-data interface names, which can hold a link-local address too. */
        fun isCarrierInterface(name: String): Boolean =
            listOf("wlan", "p2p", "rmnet", "ccmni", "ap", "swlan", "tun").any { name.startsWith(it) }
    }
}
