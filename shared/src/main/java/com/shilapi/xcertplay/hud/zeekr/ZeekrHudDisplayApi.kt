/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 * Logical display id of the HUD via com.zeekr.coreservice (CoreService binder):
 * transact(1) init, transact(4) "getLogicalDisplayId". Mirrors the engine ref.
 */
package com.shilapi.xcertplay.hud.zeekr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Log

class ZeekrHudDisplayApi(private val app: Context) {
    private val tag = "Zeekr-HudDisplay"
    @Volatile private var binder: IBinder? = null
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) { binder = service; init(service) }
        override fun onServiceDisconnected(name: ComponentName) { binder = null }
    }

    fun bind() = runCatching {
        app.bindService(Intent("com.zeekr.intent.action.CoreService").setPackage("com.zeekr.coreservice"), conn, Context.BIND_AUTO_CREATE)
    }.getOrDefault(false)

    fun unbind() = runCatching { app.unbindService(conn) }

    private fun init(remote: IBinder) {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try { remote.transact(1, data, reply, 0); reply.readException() }
        catch (t: Throwable) { Log.w(tag, "CoreService init: ${t.message}") }
        finally { data.recycle(); reply.recycle() }
    }

    fun displayId(): Int? {
        val remote = binder ?: return null
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeString("getLogicalDisplayId")
            if (!remote.transact(4, data, reply, 0)) return null
            reply.readException(); reply.readInt()
        } catch (t: Throwable) { Log.w(tag, "getLogicalDisplayId: ${t.message}"); null }
        finally { data.recycle(); reply.recycle() }
    }
}
