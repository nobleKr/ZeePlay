/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 *
 * ECARX DimInteraction HUD channel, reflection-only (no com.ecarx.* compile
 * dependency), mirroring the engine reference: connect Device/Car/DimInteraction,
 * push turn-by-turn via INaviInteraction.updateNavigationInfo (INavigationInfo
 * dynamic proxy), and request HUD projection via dimProjectionScreenRequest.
 * Device gating per vehicleType + ICarFunction values. Correctness is confirmed
 * on-car; here it compiles and no-ops where the SDK is absent.
 */
package com.shilapi.xcertplay.hud.zeekr

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class ZeekrHudSdk(
    private val app: Context,
    private val diagnostic: (String, String) -> Unit = { _, _ -> },
) {
    private val tag = "Zeekr-HudSdk"
    private val DIM = "com.ecarx.xui.adaptapi.diminteraction.DimInteraction"

    private var car: Any? = null
    private var carFunction: Any? = null
    private var dim: Any? = null
    private var navi: Any? = null
    private var naviClass: Class<*>? = null
    @Volatile private var connected = false
    @Volatile private var tbtStarted = false
    @Volatile private var projecting = false
    private var vehicleType: String = ""
    private val displayApi = ZeekrHudDisplayApi(app.applicationContext)
    @Volatile private var logicalDisplayId: Int? = null

    fun refresh() {
        if (vehicleType.isEmpty()) runCatching {
            val cls = Class.forName("com.ecarx.xui.adaptapi.device.Device")
            val device = cls.getMethod("create", Context::class.java).invoke(null, app)
            vehicleType = (cls.getMethod("getVehicleType").invoke(device) as? String).orEmpty()
        }
        if (car == null) runCatching {
            car = Class.forName("com.ecarx.xui.adaptapi.car.Car").getMethod("create", Context::class.java).invoke(null, app)
            val connectable = Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable")
            if (connectable.isInstance(car)) connectable.getMethod("connect").invoke(car)
            carFunction = Class.forName("com.ecarx.xui.adaptapi.car.ICar").getMethod("getICarFunction").invoke(car)
        }
        if (dim == null) openDim()
        if (connected && navi == null) runCatching {
            navi = Class.forName(DIM).getMethod("getNaviInteraction").invoke(dim)
        }
        logicalDisplayId = runCatching { displayApi.bind(); displayApi.displayId() }.getOrNull() ?: logicalDisplayId
        diagnostic("sdk", "vehicle=$vehicleType dim=${dim != null} connected=$connected display=$logicalDisplayId")
    }

    private fun openDim() = runCatching {
        val cls = Class.forName(DIM)
        val instance = cls.getMethod("create", Context::class.java).invoke(null, app)
        dim = instance
        naviClass = Class.forName("com.ecarx.xui.adaptapi.diminteraction.INaviInteraction")
        navi = cls.getMethod("getNaviInteraction").invoke(instance)
        val connectable = Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable")
        if (connectable.isInstance(instance)) {
            val watcherClass = Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable\$IConnectWatcher")
            val watcher = proxy(watcherClass) { m, _ ->
                if (m.name == "onConnectStateChanged" || m.name == "onServiceConnected") connected = true
                defaultValue(m.returnType)
            }
            connectable.getMethod("registerConnectWatcher", watcherClass).invoke(instance, watcher)
            connectable.getMethod("connect").invoke(instance)
        } else connected = true
    }.onFailure { diagnostic("sdk", "openDim: ${it.message}") }

    private fun functionValue(function: Int): Int? = runCatching {
        (Class.forName("com.ecarx.xui.adaptapi.car.base.ICarFunction")
            .getMethod("getFunctionValue", Int::class.javaPrimitiveType)
            .invoke(carFunction, function) as? Number)?.toInt()
    }.getOrNull()

    /** HUD-navigation permission per vehicle type + car function values. */
    fun enabled(): Boolean {
        if (vehicleType == "EF1E-8155") return true
        if (vehicleType.startsWith("DC1E") && !vehicleType.startsWith("DC1E-A3")) {
            return Settings.System.getInt(app.contentResolver, "key_car_settings_hud_navigation", 1) == 1
        }
        val fv = functionValue(654508800)?.takeIf { it == 0 || it == 1 }
        if (vehicleType.startsWith("BX1E")) {
            val mode = functionValue(537985280)?.takeIf { it in 0..2 }
            return mode == 2 || ((mode == null || mode == 0) && (fv == null || fv != 0))
        }
        diagnostic("setting", "HUD navigation=${fv ?: "unknown"}")
        return fv == null || fv != 0
    }

    fun publish(g: ZeekrHudGuidance, turnId: Int?): Boolean {
        val target = navi; val api = naviClass
        if (target == null || api == null || !connected) return false
        return runCatching {
            if (!tbtStarted) { api.getMethod("notifyTurnByTurnStarted").invoke(target); tbtStarted = true }
            val infoClass = Class.forName("com.ecarx.xui.adaptapi.diminteraction.INaviInteraction\$INavigationInfo")
            val info = proxy(infoClass) { m, _ -> navigationInfoValue(m, g, turnId) }
            api.getMethod("updateNavigationInfo", infoClass).invoke(target, info)
            true
        }.getOrElse { diagnostic("tbt", "unavailable: ${it.message}"); stopTbt(); false }
    }

    /** INavigationInfo getter -> guidance value (mirrors the engine ref mapping). */
    private fun navigationInfoValue(m: Method, g: ZeekrHudGuidance, turnId: Int?): Any? = when (m.name) {
        "getNavigationTurnId" -> turnId ?: ZeekrHudTurnId.from(g.icon, g.roundaboutExit, 0)
        "getDistanceToNextGuidancePoint" -> g.distanceMeters
        "getNextGuidancePointName" -> g.road
        "getDistanceToDestination" -> g.routeRemainingMeters.toLong()
        "getETA" -> g.routeRemainingSeconds.toLong()
        "getNavigationStatus" -> 0
        "getExtensionLineInfo" -> 4
        "getSecondTurnId" -> -1
        "getDestinationTypeString", "getNavigationTurnSVG", "getLaneInfo",
        "getRoadCameraInfo", "getServiceAreaInfo", "getHighwayExitInfo" -> null
        else -> defaultValue(m.returnType)
    }

    fun projection(start: Boolean): Boolean {
        val target = dim ?: return true
        if (!connected) return false
        if (projecting == start) return true
        projecting = start
        return runCatching {
            val infoClass = Class.forName("com.ecarx.xui.adaptapi.diminteraction.DimInteraction\$IProjectScreenInfo")
            val info = proxy(infoClass) { m, _ ->
                when (m.name) {
                    "getAction" -> if (start) 65282 else 65283
                    "getItem" -> 0
                    else -> defaultValue(m.returnType)
                }
            }
            Class.forName(DIM).getMethod("dimProjectionScreenRequest", infoClass).invoke(target, info)
            diagnostic("projection", "submitted ${if (start) "start" else "stop"}")
            true
        }.getOrElse { projecting = !start; diagnostic("projection", "unavailable: ${it.message}"); false }
    }

    fun end() { stopTbt(); projection(false) }

    private fun stopTbt() {
        if (!tbtStarted) return
        runCatching { naviClass?.getMethod("notifyTurnByTurnStopped")?.invoke(navi) }
        tbtStarted = false
    }

    // --- reflection helpers ---
    private fun proxy(iface: Class<*>, handler: (Method, Array<Any?>?) -> Any?): Any =
        Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, m, args -> handler(m, args) }

    private fun defaultValue(type: Class<*>): Any? = when {
        !type.isPrimitive -> null
        type == java.lang.Boolean.TYPE -> false
        type == java.lang.Void.TYPE -> null
        else -> 0
    }
}
