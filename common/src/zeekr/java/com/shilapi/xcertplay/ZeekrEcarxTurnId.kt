package com.shilapi.xcertplay

import com.shilapi.xcertplay.hud.ClusterTurnGuidance

/**
 * Maps our CarPlay-derived maneuver (the Gaode/BYD icon vocabulary carried by
 * [ClusterTurnGuidance.icon], see BydManeuverCodes) to the ECARX DIM "navigation turn id"
 * returned by INaviInteraction.INavigationInfo.getNavigationTurnId().
 *
 * The turn ids below are ground truth: they are the file numbers of the cluster/HUD glyph set
 * in the car's QNX HMI — qnx_resource/hmi/cluster_hmi/.../tbt/icon_cs_dim_day_<id>.png — read
 * off the actual arrows (102 left, 103 right, 104/105 slight, 106/107 sharp, 108 u-turn,
 * 109 straight, 111 roundabout, 115 destination, 121..130 roundabout exit 1..10 right-hand,
 * 131..140 left-hand). 0 means "no arrow".
 */
internal object ZeekrEcarxTurnId {

    fun of(guidance: ClusterTurnGuidance): Int = when (val icon = guidance.icon) {
        0 -> 0                                   // NONE -> no arrow
        1 -> 102                                 // LEFT
        2 -> 103                                 // RIGHT
        3 -> 104                                 // SLIGHT_LEFT (keep left)
        4 -> 105                                 // SLIGHT_RIGHT (keep right)
        7 -> 106                                 // SHARP_LEFT
        8 -> 107                                 // SHARP_RIGHT
        9 -> 108                                 // U_TURN_LEFT
        10 -> 108                                // U_TURN_RIGHT (same u-turn glyph)
        11 -> 109                                // STRAIGHT
        13 -> 111                                // ROUNDABOUT_ENTER (generic)
        48 -> 115                                // DESTINATION (checkered flag)
        24 -> 111                                // ROUNDABOUT_EXIT with no exit number
        in 25..34 -> 120 + (icon - 24)           // roundabout exit 1..10, right-hand -> 121..130
        in 35..44 -> 130 + (icon - 34)           // roundabout exit 1..10, left-hand  -> 131..140
        else -> 0
    }
}
