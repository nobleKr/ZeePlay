package com.shilapi.xcertplay

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import java.lang.reflect.Array as RArray
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Zeekr 001 HUD + cluster navigation output via the ECARX adaptapi (zeekr edition only).
 *
 * The 001 windshield HUD and the cluster TBT widget live on a QNX guest that is only
 * reachable through the vendor's sanctioned ECARX DimInteraction / INaviInteraction API
 * (the raw HAB camera channel a normal app cannot open). The stock Zeekr base map drives
 * them the same way we mirror here:
 *
 *   DimInteraction.create(context)  ->  getNaviInteraction()         (no permission, no focus call)
 *   per update:  notifyTurnByTurnStarted()  ->  updateNavigationInfo(info)
 *   on stop:     notifyTurnByTurnStopped()
 *
 * "info" is an INaviInteraction.INavigationInfo. The vendor impl reads it purely through
 * the interface getters and serialises them to JSON, so we satisfy it with a reflection
 * [Proxy] rather than a compile-time dependency on the vendor SDK (clean-room). The HUD
 * arrow is getNavigationTurnId(); we map our CarPlay maneuver to the ECARX id space via
 * [ZeekrEcarxTurnId]. Diagnostics go to the in-app report (WheelKeyLog) so the HUD can be
 * debugged on the car without a laptop.
 */
internal class ZeekrEcarxHudOutput(private val context: Context) : WindshieldHud {

    private val thread = HandlerThread("zeekr-ecarx-hud").apply { start() }
    private val handler = Handler(thread.looper)

    private var dim: Any? = null
    private var navi: Any? = null
    private var naviInfoCls: Class<*>? = null
    @Volatile private var connected = false
    private var released = false
    private var connecting = false
    private var tbtStarted = false
    private var announced = false
    private var lastTurnId = Int.MIN_VALUE
    private var lastLogMs = 0L

    // Cached INaviInteraction methods, resolved once the navi handle is obtained.
    private var mUpdateNavInfo: Method? = null
    private var mTbtStart: Method? = null
    private var mTbtStop: Method? = null

    override fun update(guidance: ClusterTurnGuidance?) {
        if (released) return
        if (!announced) { announced = true; WheelKeyLog.note("Ecarx HUD: guidance wired (guidance=${guidance != null})") }
        handler.post { render(guidance) }
    }

    override fun release() {
        handler.post {
            released = true
            safe("tbtStop") { if (connected) mTbtStop?.invoke(navi) }
            safe("disconnect") { dim?.let { it.javaClass.getMethod("disconnect").invoke(it) } }
            dim = null; navi = null; connected = false
        }
        thread.quitSafely()
    }

    private fun render(guidance: ClusterTurnGuidance?) {
        ensureConnecting()
        val n = navi ?: return
        if (guidance == null) {
            if (tbtStarted) {
                safe("tbtStop") { mTbtStop?.invoke(n) }; tbtStarted = false; lastTurnId = Int.MIN_VALUE
                WheelKeyLog.note("Ecarx HUD: tbt stopped (no guidance)")
            }
            return
        }
        if (!tbtStarted) { safe("tbtStart") { mTbtStart?.invoke(n) }; tbtStarted = true }
        val turnId = ZeekrEcarxTurnId.of(guidance)
        val info = buildInfo(turnId, guidance.distanceMeters.toLong(), guidance.road)
        safe("navInfo") { mUpdateNavInfo?.invoke(n, info) }
        // Heartbeat log: on maneuver change or every ~3s, so the report shows whether guidance
        // actually keeps flowing (dist changing) or stalls, plus the raw icon vs mapped turnId.
        val now = android.os.SystemClock.uptimeMillis()
        if (turnId != lastTurnId || now - lastLogMs > 3000L) {
            lastTurnId = turnId; lastLogMs = now
            WheelKeyLog.note("Ecarx HUD: push icon=${guidance.icon} turnId=$turnId dist=${guidance.distanceMeters} road=${guidance.road}")
        }
    }

    private fun ensureConnecting() {
        if (connected || released) return
        if (!connecting) {
            connecting = true
            runCatching {
                val dimCls = Class.forName("com.ecarx.xui.adaptapi.diminteraction.DimInteraction")
                val instance = dimCls.getMethod("create", Context::class.java)
                    .invoke(null, context.applicationContext) ?: return@runCatching
                dim = instance
                // Register a connect watcher as a fallback, then attempt the handle immediately
                // (the stock base map obtains it right after create(), without waiting).
                runCatching {
                    val watcherCls = Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable\$IConnectWatcher")
                    val watcher = Proxy.newProxyInstance(watcherCls.classLoader, arrayOf(watcherCls)) { proxy, method, args ->
                        when (method.name) {
                            "onConnected" -> { handler.post { bindNavi() }; null }
                            "onDisConnected" -> { connected = false; null }
                            "toString" -> "ZeekrEcarxConnectWatcher"
                            "hashCode" -> System.identityHashCode(proxy)
                            "equals" -> proxy === args?.getOrNull(0)
                            else -> null
                        }
                    }
                    dimCls.getMethod("registerConnectWatcher", watcherCls).invoke(instance, watcher)
                    dimCls.getMethod("connect").invoke(instance)
                }
            }.onFailure {
                Log.w(TAG, "ECARX connect failed: ${it.message}")
                WheelKeyLog.note("Ecarx HUD: connect failed ${it.javaClass.simpleName}")
                connecting = false
            }
        }
        if (navi == null) bindNavi()
    }

    private fun bindNavi() {
        if (connected || dim == null) return
        val instance = dim ?: return
        runCatching {
            val n = instance.javaClass.getMethod("getNaviInteraction").invoke(instance) ?: return
            val naviCls = Class.forName("com.ecarx.xui.adaptapi.diminteraction.INaviInteraction")
            naviInfoCls = Class.forName("com.ecarx.xui.adaptapi.diminteraction.INaviInteraction\$INavigationInfo")
            mUpdateNavInfo = naviCls.getMethod("updateNavigationInfo", naviInfoCls)
            mTbtStart = naviCls.getMethod("notifyTurnByTurnStarted")
            mTbtStop = naviCls.getMethod("notifyTurnByTurnStopped")
            navi = n
            connected = true
        }.onFailure {
            Log.w(TAG, "ECARX navi init failed: ${it.message}")
            WheelKeyLog.note("Ecarx HUD: navi init failed ${it.javaClass.simpleName}")
        }
        if (connected) {
            Log.i(TAG, "ECARX HUD connected, navi ready")
            WheelKeyLog.note("Ecarx HUD: connected, navi ready")
        }
    }

    /** INavigationInfo via reflection Proxy: the vendor impl reads it through getters only. */
    private fun buildInfo(turnId: Int, distMeters: Long, road: String): Any {
        val cls = naviInfoCls!!
        return Proxy.newProxyInstance(cls.classLoader, arrayOf(cls)) { proxy, method, args ->
            if (method.declaringClass == Any::class.java) {
                return@newProxyInstance when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "ZeekrNavInfo(turnId=$turnId)"
                    else -> null
                }
            }
            when (method.name) {
                "getNavigationTurnId" -> turnId
                "getNavigationTurnSVG" -> ""
                "getNextGuidancePointName" -> road
                "getDistanceToNextGuidancePoint" -> distMeters
                "getNavigationStatus" -> 0          // stock leaves naviStatus at 0
                else -> defaultFor(method.returnType)
            }
        }
    }

    private fun defaultFor(t: Class<*>): Any? = when {
        t == java.lang.Boolean.TYPE -> false
        t == java.lang.Integer.TYPE || t == java.lang.Short.TYPE ||
            t == java.lang.Byte.TYPE || t == Character.TYPE -> 0
        t == java.lang.Long.TYPE -> 0L
        t == java.lang.Float.TYPE -> 0f
        t == java.lang.Double.TYPE -> 0.0
        t == String::class.java -> ""
        t == Bundle::class.java -> Bundle()
        t.isArray -> RArray.newInstance(t.componentType, 0)
        else -> null
    }

    private inline fun safe(what: String, block: () -> Unit) {
        runCatching { block() }.onFailure { Log.w(TAG, "$what failed: ${it.message}") }
    }

    private companion object {
        private const val TAG = "Zeekr-EcarxHUD"
    }
}
