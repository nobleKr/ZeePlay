/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 *
 * Which CarPlay screen the windshield HUD shows: a turn CARD or a live MAP.
 * Mirrors the engine reference (HudScreen). Both are CarPlay secondary-display
 * video streams routed to the HUD display (ZeekrDisplays.isHud) — not HAB.
 */
package com.shilapi.xcertplay.airplay

enum class HudScreen { CARD, MAP }
