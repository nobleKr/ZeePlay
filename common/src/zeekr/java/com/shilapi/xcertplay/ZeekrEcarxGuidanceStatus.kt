package com.shilapi.xcertplay

import android.content.Context
import android.util.Log

/**
 * Zeekr 001 — ECARX "navigation guidance active" announce (zeekr edition only).
 *
 * Feeding turn-by-turn through INaviInteraction (see ZeekrEcarxHudOutput) draws the DIM
 * cluster TBT card, but the windshield HUD / ZID navi state only switches into its
 * "navigation is running" mode once the active navi app ALSO announces a guidance status
 * over ICarFunction. The stock ZeekrBaseMap does this in OutsideDataAdaptProxy.sendNaviStatus,
 * separately from its DimInteraction calls:
 *
 *     Car.create(ctx).getICarFunction()
 *         .setBytesFunctionValue(SETTING_FUNC_GUIDANCE_STATUS_UPDATE_INFO,   // 1342245632
 *                                Integer.MIN_VALUE,                          // 0x80000000 (global)
 *                                new Gson().toJson(idtGuidanceStatusUpdate).getBytes())
 *
 * Payload is the UTF-8 JSON of a `com.zeekr.soa.adcu.v3.m14.model.IdtGuidanceStatusUpdate`
 * (confirmed from the stock ZeekrBaseMap sources). That POJO and its BaseUint8/32/64 + NameType
 * wrappers are PRIVATE to ZeekrBaseMap — not framework/boot-classpath — so we cannot reflectively
 * reuse them. We reproduce the exact on-wire JSON by hand instead. No @SerializedName anywhere,
 * so Gson uses the raw field names; the numeric wrappers serialize as {"value":N}, NameType as
 * {"isValidate":true,"value":"..."}. The stock `new IdtGuidanceStatusUpdate(new byte[0])` leaves
 * every field non-null (zero defaults) with isValidate=false, then the builder overwrites five —
 * so the full object below is byte-for-byte what the working stock map emits when guidance starts.
 *
 * vGuidanceStatus = 1 while guiding, 0 when it stops. We keep a stable synthetic route identity
 * (vNaviId="zeeplay", vRouteUuid=1) so the receiver has a consistent route to track.
 *
 * Clean-room: reflection-only against the sanctioned ECARX framework API; no vendor code copied,
 * no protected binary touched. Every send logs funcId, the exact bytes and the boolean return to
 * WheelKeyLog so activation is verifiable on the car without a laptop.
 */
internal class ZeekrEcarxGuidanceStatus(private val context: Context) {

    private var carFunction: Any? = null
    private var resolved = false
    private var lastSent = -1
    private var lastTla: String? = null
    private var lastTlaLogMs = 0L

    /** Announce guidance active (1) or stopped (0). No-op if already in that state. */
    fun setActive(active: Boolean) {
        val want = if (active) 1 else 0
        if (!active) lastTla = null
        if (want == lastSent) return
        val fn = ensureCarFunction() ?: return
        val json = payload(want)
        val bytes = json.toByteArray(Charsets.UTF_8)
        val rc = runCatching {
            // boolean setBytesFunctionValue(int funcId, int areaId, byte[] value)
            val m = fn.javaClass.getMethod(
                "setBytesFunctionValue",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                ByteArray::class.java,
            )
            m.invoke(fn, FUNC_GUIDANCE_STATUS, ZONE_GLOBAL, bytes)
        }.onFailure {
            Log.w(TAG, "guidance-status send failed: ${it.message}")
            WheelKeyLog.note("Ecarx HUD: guidance-status send FAILED ${it.javaClass.simpleName} (${it.message})")
        }.getOrNull()
        if (rc != null) lastSent = want
        WheelKeyLog.note("Ecarx HUD: guidance-status active=$active funcId=$FUNC_GUIDANCE_STATUS rc=$rc bytes=${bytes.size}")
    }

    fun release() {
        runCatching { carFunction?.let { it.javaClass.getMethod("disconnect").invoke(it) } }
        carFunction = null
        resolved = false
        lastSent = -1
        lastTla = null
    }

    /**
     * Exact Gson serialization of IdtGuidanceStatusUpdate as the stock map emits it.
     * Field order follows the class declaration order (irrelevant to the JSON parser).
     */
    private fun payload(status: Int): String =
        "{" +
            "\"isValidate\":false," +
            "\"vGuidanceStatus\":{\"value\":$status}," +
            "\"vGuidanceStatusType\":{\"value\":0}," +
            "\"vMemoryRouteFlag\":{\"value\":0}," +
            "\"vMileageDist\":{\"value\":0}," +
            "\"vNaviId\":{\"isValidate\":true,\"value\":\"$NAVI_ID\"}," +
            "\"vParentRouteUuid\":{\"value\":0}," +
            "\"vReasonCode\":{\"value\":0}," +
            "\"vRouteUuid\":{\"value\":$ROUTE_UUID}," +
            "\"vStraightDist\":{\"value\":0}" +
            "}"

    /**
     * Push the turn-by-turn main action (arrow + distance + road name) to the windshield HUD via
     * ICarFunction SETTING_FUNC_TLA_MAIN_ACTION_INFO. Payload is the exact Gson JSON of the stock
     * IdtTLAMainAction (full object, {"value":N} wrappers), only vFirstTurn / vFirstTurnDis /
     * vCurrRoadNameNavingSeN carry data; the rest stay at the byte[0] defaults the stock map emits.
     * [turnType] is the ECARX TurnType code (see ZeekrEcarxTurnId.tla).
     */
    fun updateTurn(turnType: Int, distanceMeters: Int, road: String) {
        val key = "$turnType|$distanceMeters|$road"
        if (key == lastTla) return
        lastTla = key
        val fn = ensureCarFunction() ?: return
        val json = tlaPayload(turnType, distanceMeters, road)
        val bytes = json.toByteArray(Charsets.UTF_8)
        val rc = runCatching {
            val m = fn.javaClass.getMethod(
                "setBytesFunctionValue",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                ByteArray::class.java,
            )
            m.invoke(fn, FUNC_TLA_MAIN_ACTION, ZONE_GLOBAL, bytes)
        }.onFailure {
            Log.w(TAG, "tla send failed: ${it.message}")
            WheelKeyLog.note("Ecarx HUD: tla send FAILED ${it.javaClass.simpleName}")
        }.getOrNull()
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastTlaLogMs > 3000L) {
            lastTlaLogMs = now
            WheelKeyLog.note("Ecarx HUD: tla turn=$turnType dist=$distanceMeters rc=$rc bytes=${bytes.size}")
        }
    }

    /** Full IdtTLAMainAction Gson JSON; only first-turn fields carry data. */
    private fun tlaPayload(turn: Int, dist: Int, road: String): String =
        "{" +
            "\"isValidate\":false," +
            "\"vChargeStationRemain\":{\"isValidate\":false}," +
            "\"vCrossOutCntSeN\":{\"value\":0}," +
            "\"vCurLinkIdxSeN\":{\"value\":0}," +
            "\"vCurLinkSpeed\":{\"value\":0}," +
            "\"vCurSegIdxSeN\":{\"value\":0}," +
            "\"vCurrRoadNameNavingSeN\":{\"isValidate\":true,\"value\":\"${jsonEscape(road)}\"}," +
            "\"vFirstTurn\":{\"value\":$turn}," +
            "\"vFirstTurnDis\":{\"value\":$dist}," +
            "\"vLinkRemainDistSeN\":{\"value\":0}," +
            "\"vPathIDSeN\":{\"value\":0}," +
            "\"vRoadLevelForNavingSeN\":{\"value\":0}," +
            "\"vRoundaboutOutAngle\":{\"value\":0}," +
            "\"vRouteRemain\":{\"isValidate\":true,\"vRemainDist\":{\"value\":0},\"vRemainTime\":{\"value\":0}}," +
            "\"vSecondTurn\":{\"value\":0}," +
            "\"vSecondTurnDis\":{\"value\":0}," +
            "\"vViaRemain\":{\"isValidate\":false}" +
            "}"

    private fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.toString()
    }

    private fun ensureCarFunction(): Any? {
        if (resolved) return carFunction
        resolved = true
        carFunction = runCatching {
            val carCls = Class.forName("com.ecarx.xui.adaptapi.car.Car")
            val car = carCls.getMethod("create", Context::class.java)
                .invoke(null, context.applicationContext) ?: return@runCatching null
            car.javaClass.getMethod("getICarFunction").invoke(car)
        }.onFailure {
            Log.w(TAG, "ICarFunction resolve failed: ${it.message}")
            WheelKeyLog.note("Ecarx HUD: ICarFunction resolve failed ${it.javaClass.simpleName}")
        }.getOrNull()
        return carFunction
    }

    private companion object {
        private const val TAG = "Zeekr-EcarxGuide"
        private const val FUNC_GUIDANCE_STATUS = 1342245632   // INavigation.SETTING_FUNC_GUIDANCE_STATUS_UPDATE_INFO
        private const val FUNC_TLA_MAIN_ACTION = 1342243328   // INavigation.SETTING_FUNC_TLA_MAIN_ACTION_INFO
        private const val ZONE_GLOBAL = -2147483648           // 0x80000000, Integer.MIN_VALUE
        private const val NAVI_ID = "zeeplay"
        private const val ROUTE_UUID = 1
    }
}
