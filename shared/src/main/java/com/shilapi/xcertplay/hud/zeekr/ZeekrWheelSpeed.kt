/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 *
 * Wheel-speed source for the iPhone's dead reckoning in tunnels. Reads speed
 * and gear from ZeekrCarSignals (android.car, no adb needed on Zeekr) and
 * accumulates samples the iAP2 location plane drains as $PASCD.
 *
 * ZeekrSpeedBuffer holds the pure accumulation logic and is unit-tested;
 * ZeekrWheelSpeedSource adds the Android polling around it.
 */
package com.shilapi.xcertplay.hud.zeekr

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSample
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Pure, Android-free accumulator: newest-capped samples + last known gear. */
internal class ZeekrSpeedBuffer(private val maxSamples: Int = 40) {
    private val samples = ArrayList<VehicleSpeedSample>()
    private var gear: VehicleGear? = null

    @Synchronized fun addSpeed(elapsedMillis: Long, metersPerSecond: Double) {
        if (metersPerSecond < 0) return
        if (samples.size >= maxSamples) samples.removeAt(0)
        samples.add(VehicleSpeedSample(elapsedMillis, metersPerSecond))
    }

    @Synchronized fun setGear(g: VehicleGear?) { if (g != null) gear = g }

    /** Returns readings since the last drain and clears them; null when no gear or no samples. */
    @Synchronized fun drain(): VehicleSpeedReading? {
        val g = gear ?: return null
        if (samples.isEmpty()) return null
        val out = VehicleSpeedReading(g, ArrayList(samples))
        samples.clear()
        return out
    }

    @Synchronized fun reset() { samples.clear(); gear = null }
}

class ZeekrWheelSpeedSource(context: Context) : VehicleSpeedSource {
    private val app = context.applicationContext
    private val buffer = ZeekrSpeedBuffer()
    private var executor: ScheduledExecutorService? = null
    private var reads = 0

    @Synchronized
    override fun start() {
        if (executor != null) return
        buffer.reset(); reads = 0
        executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "zeekr-wheel-speed").apply { isDaemon = true }
        }.also { it.scheduleWithFixedDelay(::poll, 0, READ_MILLIS, TimeUnit.MILLISECONDS) }
        Log.i(TAG, "wheel speed started")
    }

    @Synchronized
    override fun stop() {
        executor?.shutdownNow(); executor = null
    }

    override fun drain(): VehicleSpeedReading? = buffer.drain()

    private fun poll() {
        runCatching {
            ZeekrCarSignals.speedKmh(app)?.let { buffer.addSpeed(SystemClock.elapsedRealtime(), it / 3.6) }
            if (reads % GEAR_EVERY_READS == 0) buffer.setGear(ZeekrCarSignals.gear(app))
            reads++
        }
    }

    companion object {
        private const val TAG = "Zeekr-Speed"
        private const val READ_MILLIS = 250L
        private const val GEAR_EVERY_READS = 4
    }
}
