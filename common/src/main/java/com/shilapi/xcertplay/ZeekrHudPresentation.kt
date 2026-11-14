/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 *
 * Minimal Presentation that shows a CarPlay HUD video stream on the Zeekr
 * windshield HUD display. It owns a SurfaceView and hands its Surface to the
 * caller (which registers it with the media sink for the HUD screen stream).
 * Unlike ClusterMapPresentation this carries no BYD/DiLink layout logic — the
 * HUD panel renders the stream 1:1.
 */
package com.shilapi.xcertplay

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Gravity
import android.widget.FrameLayout

internal class ZeekrHudPresentation(
    context: Context,
    display: Display,
    private val onSurface: (Surface?) -> Unit,
) : Presentation(context, display) {
    /**
     * Where the CarPlay stream sits on the HUD panel. The stream keeps its own aspect ratio; the
     * rest of the panel stays black, which the projector does not light up.
     */
    data class Layout(val streamWidth: Int, val streamHeight: Int, val sizePercent: Int, val xPercent: Int, val yPercent: Int)

    private var root: FrameLayout? = null
    private var view: SurfaceView? = null
    private var layout: Layout? = null
    private var placed: IntArray? = null

    /** Main thread. Re-places the stream; a no-op when nothing changed. */
    fun applyLayout(next: Layout) {
        layout = next
        place()
    }

    private fun place() {
        val frame = root ?: return
        val surface = view ?: return
        val l = layout ?: return
        val pw = frame.width
        val ph = frame.height
        if (pw <= 0 || ph <= 0 || l.streamWidth <= 0 || l.streamHeight <= 0) return
        val fit = minOf(pw.toFloat() / l.streamWidth, ph.toFloat() / l.streamHeight)
        val scale = fit * l.sizePercent.coerceIn(5, 300) / 100f
        val w = (l.streamWidth * scale).toInt().coerceAtLeast(1)
        val h = (l.streamHeight * scale).toInt().coerceAtLeast(1)
        val left = (pw * l.xPercent.coerceIn(0, 100) / 100f - w / 2f).toInt()
        val top = (ph * l.yPercent.coerceIn(0, 100) / 100f - h / 2f).toInt()
        val next = intArrayOf(left, top, w, h)
        if (placed?.contentEquals(next) == true) return
        placed = next
        surface.layoutParams = FrameLayout.LayoutParams(w, h).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = left
            topMargin = top
        }
        Log.i(TAG, "HUD layout ${w}x$h at $left,$top on ${pw}x$ph (stream ${l.streamWidth}x${l.streamHeight} size=${l.sizePercent}%)")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val surfaceView = SurfaceView(context)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "HUD surface created")
                onSurface(holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i(TAG, "HUD surface ${width}x$height")
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "HUD surface destroyed")
                onSurface(null)
            }
        })
        val frame = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        frame.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
        root = frame
        view = surfaceView
        setContentView(frame)
        window?.decorView?.setBackgroundColor(Color.BLACK)
    }

    companion object {
        const val TAG = "Zeekr-HUD"
    }
}
