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

internal class ZeekrHudPresentation(
    context: Context,
    display: Display,
    private val onSurface: (Surface?) -> Unit,
) : Presentation(context, display) {
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
        setContentView(surfaceView)
        window?.decorView?.setBackgroundColor(Color.BLACK)
    }

    companion object {
        const val TAG = "Zeekr-HUD"
    }
}
