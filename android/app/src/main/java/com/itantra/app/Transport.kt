package com.itantra.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "Transport"
const val TRANSPORT_PORT = 47821

enum class ConnectionState { DISCONNECTED, LISTENING, CONNECTING, CONNECTED }

/**
 * M2 transport: a plain TCP socket over whatever Wi-Fi both phones share (a hotspot from
 * one of them, or a common router) -- the fastest path to a reliably working two-phone
 * demo. Wi-Fi Direct (no shared network needed) is the natural upgrade once this loop is
 * proven; the Frame protocol above it doesn't change either way.
 *
 * One phone hosts (listens), the other joins (connects to the host's IP). Once connected,
 * the link is symmetric -- either side can call [send]; both run a receive loop.
 */
class Transport(
    private val context: Context,
    private val onStateChanged: (ConnectionState) -> Unit,
    private val onFrameReceived: (Frame) -> Unit,
) {
    @Volatile
    private var state = ConnectionState.DISCONNECTED
        set(value) {
            field = value
            onStateChanged(value)
        }

    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private val writeLock = Any()

    /** The connected peer's address, once [state] is CONNECTED; null otherwise. */
    @Volatile
    var remoteAddress: String? = null
        private set

    /** Starts listening for one peer to connect. Call off the main thread. */
    fun startHost() {
        stop()
        state = ConnectionState.LISTENING
        try {
            val server = ServerSocket(TRANSPORT_PORT)
            serverSocket = server
            val client = server.accept() // blocks until the other phone connects
            attach(client)
        } catch (ex: IOException) {
            Log.e(TAG, "startHost failed", ex)
            state = ConnectionState.DISCONNECTED
        }
    }

    /** Connects to a host at [hostIp]. Call off the main thread. */
    fun connectToHost(hostIp: String) {
        stop()
        state = ConnectionState.CONNECTING
        try {
            val client = Socket()
            // If the phone also has mobile data up, Android's default-network routing can
            // send this connection attempt out over data instead of Wi-Fi (silently, no
            // error until it times out trying to reach a private IP that isn't reachable
            // that way) -- happened during testing. Binding explicitly to the Wi-Fi network
            // makes this connection use it regardless of what the OS would otherwise prefer.
            activeWifiNetwork()?.bindSocket(client)
            client.connect(InetSocketAddress(hostIp, TRANSPORT_PORT), 8000)
            attach(client)
        } catch (ex: IOException) {
            Log.e(TAG, "connectToHost failed", ex)
            state = ConnectionState.DISCONNECTED
        }
    }

    private fun activeWifiNetwork(): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        return cm.allNetworks.firstOrNull { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private fun attach(client: Socket) {
        socket = client
        output = DataOutputStream(client.getOutputStream())
        remoteAddress = client.inetAddress?.hostAddress
        state = ConnectionState.CONNECTED
        Log.i(TAG, "Connected to $remoteAddress")

        val input = DataInputStream(client.getInputStream())
        try {
            while (state == ConnectionState.CONNECTED) {
                val frame = Frame.readFrom(input)
                onFrameReceived(frame)
            }
        } catch (ex: IOException) {
            Log.i(TAG, "Peer disconnected: ${ex.message}")
        } finally {
            state = ConnectionState.DISCONNECTED
        }
    }

    /** Sends [frame] to the connected peer. No-op (returns false) if not connected. */
    fun send(frame: Frame): Boolean {
        val out = output ?: return false
        return try {
            synchronized(writeLock) {
                frame.writeTo(out)
            }
            true
        } catch (ex: IOException) {
            Log.e(TAG, "send failed", ex)
            state = ConnectionState.DISCONNECTED
            false
        }
    }

    fun stop() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        socket = null
        output = null
        serverSocket = null
        remoteAddress = null
        state = ConnectionState.DISCONNECTED
    }
}
