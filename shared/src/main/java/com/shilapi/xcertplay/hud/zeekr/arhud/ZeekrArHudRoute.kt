/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 *
 * The CarPlay route geometry fed to the Zeekr AR-HUD (augmented-reality windshield
 * projection), mirroring the engine reference. The system HMI renders the overlay;
 * we only supply the polyline + maneuver metadata (see ZeekrArHudWire / ZeekrArHudOutput).
 */
package com.shilapi.xcertplay.hud.zeekr.arhud

data class ZeekrArHudRoute(
    val requestId: String,
    val pathId: Long,
    val timestamp: Long,
    val links: List<Link>,
    val segments: List<Segment>,
    val requestType: Int = 0,
) {
    data class Point(val longitude: Double, val latitude: Double, val altitude: Double)

    data class Link(
        val points: List<Point>,
        val lengthMeters: Int,
        val roadClass: Int,
        val roadName: String,
        val direction: Int,
        val laneCount: Int,
    )

    data class Segment(val mainAction: Int, val assistantAction: Int, val linkCount: Int)

    /** Complete, in-range geometry the AR-HUD requires; matches the engine's validation. */
    fun isValid(): Boolean {
        if (requestId.isBlank()) return false
        if (pathId < 0 || timestamp <= 0 || links.isEmpty() || links.size > 20000 || segments.isEmpty()) return false
        if (!segments.all { it.linkCount > 0 }) return false
        if (segments.sumOf { it.linkCount.toLong() } != links.size.toLong()) return false
        return links.all { link ->
            link.lengthMeters >= 0 &&
                link.points.size in 2..10000 &&
                link.roadClass in 0 until 13 &&
                link.direction >= 0 && link.laneCount >= 0 &&
                link.points.all { p ->
                    p.longitude in -180.0..180.0 && p.latitude in -90.0..90.0 && p.altitude.isFinite()
                }
        }
    }
}
