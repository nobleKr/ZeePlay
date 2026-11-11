/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 *
 * Drives the AR-HUD over fdbus, mirroring the engine reference: hold the current
 * route, (re)connect to the ARHudService, submit the route once, then heartbeat
 * while guiding. All transport calls are guarded; a failure schedules a 5 s retry.
 */
package com.shilapi.xcertplay.hud.zeekr.arhud

import android.os.SystemClock
import com.zeekrlife.systemhmi.adapter.FDbus

class ZeekrArHudOutput(
    private val diagnostic: (tag: String, message: String) -> Unit = { _, _ -> },
) {
    private var bus: FDbus? = null
    @Volatile private var connected = false
    @Volatile private var routeSent = false
    @Volatile private var generation = 0L
    private var count = 0
    private var retryAt = 0L
    private var route: ZeekrArHudRoute? = null
    private var payload: ByteArray? = null

    /** Sets (or clears) the active route; a route over the 1 MiB resource limit is dropped. */
    fun setRoute(value: ZeekrArHudRoute?) {
        require(value == null || value.isValid()) { "Invalid CarPlay AR-HUD route" }
        if (value == route) return
        end()
        route = value
        payload = value?.let { ZeekrArHudWire.route(it).takeIf { bytes -> bytes.size <= 1024 * 1024 } }
    }

    /** Called periodically; opens/keeps the AR-HUD feed while [guiding] with a valid route. */
    fun tick(guiding: Boolean) {
        val current = route
        if (!guiding || current == null) {
            end()
            diagnostic("ar", "inactive: " + if (current == null) "CarPlay geometry unavailable" else "no active route")
            return
        }
        val bytes = payload
        if (bytes == null) {
            diagnostic("ar", "route exceeds local 1 MiB resource limit")
            return
        }
        if (bus == null && SystemClock.elapsedRealtime() >= retryAt) open()
        val client = bus ?: return
        if (!connected) {
            diagnostic("ar", "waiting for ARHudService")
            return
        }
        try {
            if (!routeSent) {
                routeSent = client.send(ZeekrArHudWire.ROUTE_EVENT, bytes)
                if (!routeSent) {
                    diagnostic("ar", "route submission rejected")
                    return
                }
            }
            val n = count++
            if (client.send(ZeekrArHudWire.HEART_EVENT, ZeekrArHudWire.heartbeat(n, true))) {
                diagnostic("ar", "CarPlay route and heartbeat submitted; rendering unconfirmed")
            } else {
                routeSent = false
                diagnostic("ar", "heartbeat rejected")
            }
        } catch (error: Throwable) {
            diagnostic("ar", "unavailable: " + rootMessage(error))
            end()
            retryAt = SystemClock.elapsedRealtime() + 5000
        }
    }

    fun clear() {
        end()
        route = null
        payload = null
    }

    private fun open() {
        val revision = ++generation
        try {
            val client = FDbus(object : FDbus.Listener {
                override fun onConnect() = synchronized(this@ZeekrArHudOutput) {
                    if (revision == generation) { connected = true; routeSent = false }
                }
                override fun onDisconnect() = synchronized(this@ZeekrArHudOutput) {
                    if (revision == generation) { connected = false; routeSent = false }
                }
                override fun onReceive(code: Int, payload: ByteArray) {}
            })
            bus = client
            client.open("svc://ARHudService")
        } catch (error: Throwable) {
            diagnostic("ar", "FDbus unavailable: " + rootMessage(error))
            end()
            retryAt = SystemClock.elapsedRealtime() + 5000
        }
    }

    private fun end() {
        val client = bus
        bus = null
        val active: Boolean
        synchronized(this) {
            generation++
            active = connected && routeSent
            connected = false
            routeSent = false
        }
        if (client != null) {
            if (active) runCatching { client.send(ZeekrArHudWire.HEART_EVENT, ZeekrArHudWire.heartbeat(count, false)) }
            runCatching { client.close() }.onFailure { diagnostic("ar", "close failed: " + rootMessage(it)) }
        }
        count = 0
    }

    private fun rootMessage(t: Throwable): String =
        generateSequence(t) { it.cause }.last().message ?: t.javaClass.simpleName
}
