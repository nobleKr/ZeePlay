package com.shilapi.xcertplay.airplay

/**
 * CarPlay's instrument-cluster screen (stream type 111).
 *
 * The iPhone lists the cluster content it offers in its /info request (`altScreenURLs`); without
 * an initial URL it streams a black frame. The cluster has no input. Apple Maps keeps the car
 * position inside the safe area while the map still fills the panel, so the safe area is set to
 * the part of the panel the cluster leaves uncovered.
 */
object CarPlayClusterDisplay {
    const val MAP_URL = "maps:/car/instrumentcluster/map"

    /**
     * What the dashboard shows: one of the cluster contents the iPhone lists in `altScreenURLs`.
     * On the tested car the iPhone glass turn card needed 0–5 kbit/s against 0.3–4 Mbit/s for the map.
     * MAP_WITH_CUSTOM_CARD asks for the map stream so DiPlay can draw a movable card on top.
     */
    enum class Content(val url: String) {
        MAP(MAP_URL),
        TURN_CARD("maps:/car/instrumentcluster/instructioncard"),
        INSTRUMENTS("maps:/car/instrumentcluster"),
        MAP_WITH_CUSTOM_CARD(MAP_URL),
    }

    fun usesCustomTurnCard(content: Content): Boolean = content == Content.MAP_WITH_CUSTOM_CARD

    fun usesOfficialTurnCard(content: Content): Boolean =
        content == Content.TURN_CARD || content == Content.INSTRUMENTS

    /**
     * Where the car marker goes, as percent of the panel (left, top, right, bottom). Measured on a
     * DiLink 5.0 cluster with a calibration grid. In "Full screen navi" BYD draws a status row,
     * turn/ADAS icons, the ADAS lane view (from x 68 %), speed and power readouts and a bottom band
     * over the map; "Small screen navi" crops the panel to a centre window of about x 31–68 %,
     * y 16–89 %. This centre area is clear in both modes, so no per-mode setting is needed.
     */
    val SAFE_AREA_PERCENT = AirPlayInsets(top = 16, bottom = 25, left = 35, right = 36)

    /** Balanced margins for virtual full-screen/16:9 streams (e.g. desktop cards/widgets). */
    val VIRTUAL_SAFE_AREA_PERCENT = AirPlayInsets(top = 8, bottom = 8, left = 8, right = 8)

    /** The driver can move the marker from that centre, in steps of 10 % of the panel. */
    const val MARKER_STEP_PERCENT = 10
    val horizontalSteps = -4..4 // negative = left
    val verticalSteps = -3..3 // negative = up

    /** Marker placement on a 1 % grid, used by the full-screen and small-window positions. */
    val markerXPercents = (5..95 step 1).toList()
    val markerYPercents = (5..95 step 1).toList()

    /** Where the car marker lands, in percent of the panel (x from the left, y from the top). */
    fun markerPercent(
        horizontalStep: Int,
        verticalStep: Int,
        area: AirPlayInsets = SAFE_AREA_PERCENT,
    ): Pair<Double, Double> {
        val x = (area.left + 100 - area.right) / 2.0 + horizontalStep.coerceIn(horizontalSteps) * MARKER_STEP_PERCENT
        val y = (area.top + 100 - area.bottom) / 2.0 + verticalStep.coerceIn(verticalSteps) * MARKER_STEP_PERCENT
        return x.coerceIn(MARKER_MARGIN, 100 - MARKER_MARGIN) to y.coerceIn(MARKER_MARGIN, 100 - MARKER_MARGIN)
    }

    private const val MARKER_MARGIN = 5.0

    /**
     * Stream size in percent of the panel. The cluster scales the stream up, so a smaller stream
     * gives a larger map: 83 % is clearly larger and still sharp.
     *
     * Values above 100 ask for a larger stream than the panel: the iPhone renders more map
     * area and the cluster scales it down, so features are smaller and finer — "smaller"
     * in the settings means more content, not a magnified view.
     */
    const val STREAM_SCALE_PERCENT = 83
    val scalePresets = listOf(100, STREAM_SCALE_PERCENT, 67, 125)

    private const val WIDTH_PHYSICAL_MM = 292 // a 12.3-inch 8:3 cluster panel; Apple Maps ignores it here
    private const val FPS = 30

    fun config(
        widthPixels: Int,
        heightPixels: Int,
        scalePercent: Int = STREAM_SCALE_PERCENT,
        horizontalStep: Int = 0,
        verticalStep: Int = 0,
        content: Content = Content.MAP,
        markerXPercent: Int? = null,
        markerYPercent: Int? = null,
        baseSafeArea: AirPlayInsets = SAFE_AREA_PERCENT,
    ): AirPlayDisplayConfig {
        // Height rounds to a multiple of 8 and width follows it, so the panel's aspect is kept
        // (83 % of 1920x720 gives exactly 1600x600). The cluster scales the stream to the panel.
        val scale = scalePercent.coerceIn(25, 150)
        val height = roundTo8(heightPixels * scale / 100.0)
        val width = roundTo8(height * widthPixels.toDouble() / heightPixels)
        return AirPlayDisplayConfig(
            widthPixels = width,
            heightPixels = height,
            widthPhysicalMm = WIDTH_PHYSICAL_MM,
            heightPhysicalMm = Math.round(WIDTH_PHYSICAL_MM * height.toDouble() / width).toInt(),
            fps = FPS,
            primaryInputDevice = 0,
            features = 0,
            initialUrl = content.url,
            safeArea = safeArea(width, height, horizontalStep, verticalStep, markerXPercent, markerYPercent, baseSafeArea),
            safeAreaDrawOutside = true,
        )
    }

    // The safe area keeps its measured size around the marker and shrinks only where the marker
    // comes close to a panel edge, so the marker always sits at its centre.
    private fun safeArea(
        width: Int,
        height: Int,
        horizontalStep: Int,
        verticalStep: Int,
        markerXPercent: Int? = null,
        markerYPercent: Int? = null,
        baseSafeArea: AirPlayInsets = SAFE_AREA_PERCENT,
    ): AirPlayInsets {
        val area = baseSafeArea
        val (x, y) = if (markerXPercent != null && markerYPercent != null) {
            markerXPercent.coerceIn(5, 95).toDouble() to markerYPercent.coerceIn(5, 95).toDouble()
        } else {
            markerPercent(horizontalStep, verticalStep, area)
        }
        val halfWidth = minOf((100 - area.left - area.right) / 2.0, x, 100 - x)
        val halfHeight = minOf((100 - area.top - area.bottom) / 2.0, y, 100 - y)
        return AirPlayInsets(
            top = (height * (y - halfHeight) / 100).toInt(),
            bottom = (height * (100 - y - halfHeight) / 100).toInt(),
            left = (width * (x - halfWidth) / 100).toInt(),
            right = (width * (100 - x - halfWidth) / 100).toInt(),
        )
    }

    // --- HUD map: a CarPlay map stream routed to the windshield HUD display ---
    // Same mechanism as the cluster, sized for the HUD panel. Base 640x576 @ 15 fps;
    // sizePercent snaps to a preset. The safe area keeps the marker inside a 60%% window.
    const val HUD_MAP_BASE_WIDTH = 640
    const val HUD_MAP_BASE_HEIGHT = 576
    private const val HUD_MAP_FPS = 15
    private const val HUD_MAP_MARKER_STEP_PERCENT = 2.5
    private const val HUD_MAP_SAFE_PERCENT = 60.0
    val hudMapSizePresets = listOf(75, 100, 125, 150)
    val hudMapHorizontalSteps = -8..8
    val hudMapVerticalSteps = -6..6

    /** CarPlay map display config for the HUD; [mmPerPixel] sizes the physical hint. */
    fun hudMap(
        sizePercent: Int,
        horizontalStep: Int,
        verticalStep: Int,
        mmPerPixel: Double,
    ): AirPlayDisplayConfig {
        val percent = hudMapSizePresets.minByOrNull { Math.abs(it - sizePercent) } ?: 100
        val width = percent * HUD_MAP_BASE_WIDTH / 100
        val height = percent * HUD_MAP_BASE_HEIGHT / 100
        return AirPlayDisplayConfig(
            widthPixels = width,
            heightPixels = height,
            widthPhysicalMm = Math.round(width * mmPerPixel).toInt(),
            heightPhysicalMm = Math.round(height * mmPerPixel).toInt(),
            fps = HUD_MAP_FPS,
            primaryInputDevice = 0,
            features = 0,
            initialUrl = MAP_URL,
            safeArea = hudMapSafeArea(width, height, horizontalStep, verticalStep),
            safeAreaDrawOutside = true,
        )
    }

    private fun hudMapSafeArea(width: Int, height: Int, horizontalStep: Int, verticalStep: Int): AirPlayInsets {
        val half = HUD_MAP_SAFE_PERCENT / 2.0
        val x = (50.0 + horizontalStep.coerceIn(hudMapHorizontalSteps) * HUD_MAP_MARKER_STEP_PERCENT)
            .coerceIn(half, 100 - half)
        val y = (50.0 + verticalStep.coerceIn(hudMapVerticalSteps) * HUD_MAP_MARKER_STEP_PERCENT)
            .coerceIn(half, 100 - half)
        return AirPlayInsets(
            top = (height * (y - half) / 100).toInt(),
            bottom = (height * ((100 - y) - half) / 100).toInt(),
            left = (width * (x - half) / 100).toInt(),
            right = (width * ((100 - x) - half) / 100).toInt(),
        )
    }

    private fun roundTo8(value: Double): Int = (Math.round(value / 8) * 8).toInt().coerceAtLeast(8)
}
