/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 *
 * Encodes a ZeekrArHudRoute and heartbeats to the AR-HUD service's protobuf wire
 * format (ROUTE_EVENT / HEART_EVENT), mirroring the engine reference field-for-field.
 */
package com.shilapi.xcertplay.hud.zeekr.arhud

import java.io.ByteArrayOutputStream

object ZeekrArHudWire {
    const val ROUTE_EVENT = 4352
    const val HEART_EVENT = 4353

    fun route(route: ZeekrArHudRoute): ByteArray {
        require(route.isValid()) { "AR-HUD requires complete CarPlay route geometry" }
        val root = Message()
        root.string(1, route.requestId)
        for (link in route.links) {
            val linkMsg = Message()
            for (point in link.points) {
                val pointMsg = Message()
                pointMsg.double(1, point.longitude)
                pointMsg.double(2, point.latitude)
                pointMsg.double(3, point.altitude)
                linkMsg.message(2, pointMsg.bytes())
            }
            linkMsg.number(3, link.lengthMeters.toLong())
            linkMsg.number(4, link.roadClass.toLong())
            linkMsg.string(5, link.roadName)
            linkMsg.number(10, link.direction.toLong())
            linkMsg.number(12, link.laneCount.toLong())
            root.message(2, linkMsg.bytes())
        }
        root.number(3, route.requestType.toLong())
        root.number(4, route.timestamp)
        root.number(5, route.pathId)
        for (segment in route.segments) {
            val segMsg = Message()
            segMsg.number(1, segment.mainAction.toLong())
            segMsg.number(2, segment.assistantAction.toLong())
            segMsg.number(3, segment.linkCount.toLong())
            root.message(6, segMsg.bytes())
        }
        return root.bytes()
    }

    fun heartbeat(count: Int, navigating: Boolean): ByteArray {
        val m = Message()
        m.number(1, count.toLong())
        m.number(2, if (navigating) 7L else 0L)
        m.number(3, if (navigating) 3L else 0L)
        return m.bytes()
    }

    private class Message {
        private val out = ByteArrayOutputStream()
        fun number(field: Int, value: Long) { varint((field shl 3).toLong()); varint(value) }
        fun string(field: Int, value: String) = message(field, value.toByteArray(Charsets.UTF_8))
        fun message(field: Int, value: ByteArray) {
            varint(((field shl 3) or 2).toLong()); varint(value.size.toLong()); out.write(value)
        }
        fun double(field: Int, value: Double) {
            varint(((field shl 3) or 1).toLong())
            val bits = java.lang.Double.doubleToLongBits(value)
            for (i in 0 until 8) out.write(((bits ushr (i * 8)).toInt()) and 0xFF)
        }
        private fun varint(value: Long) {
            var remaining = value
            while (remaining and 0x7FL.inv() != 0L) {
                out.write(((remaining.toInt()) and 0x7F) or 0x80)
                remaining = remaining ushr 7
            }
            out.write(remaining.toInt())
        }
        fun bytes(): ByteArray = out.toByteArray()
    }
}
