/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 * Sends ZeekrHudMessage items as AMap standard broadcasts to each receiver
 * package (FLAG_INCLUDE_STOPPED_PACKAGES), mirroring the engine reference.
 */
package com.shilapi.xcertplay.hud.zeekr

import android.content.Context
import android.content.Intent

object ZeekrHudBroadcaster {
    fun send(context: Context, messages: List<ZeekrHudMessage>) {
        val app = context.applicationContext
        for (message in messages) {
            for (pkg in ZeekrHudMessage.RECEIVER_PACKAGES) {
                val intent = Intent(ZeekrHudMessage.ACTION)
                    .setPackage(pkg)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                intent.putExtra("KEY_TYPE", message.keyType)
                for ((key, value) in message.extras) when (value) {
                    is Int -> intent.putExtra(key, value)
                    is String -> intent.putExtra(key, value)
                }
                runCatching { app.sendBroadcast(intent) }
            }
        }
    }
}
