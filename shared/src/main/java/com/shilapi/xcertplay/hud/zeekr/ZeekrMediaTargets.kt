/*
 * DiPlay — Zeekr adaptation. GPLv3 (see LICENSE).
 *
 * Where CarPlay's now-playing artwork is shared on the Zeekr 001: the media
 * centre, widgets, CSD island and launchers read it through a FileProvider, so
 * each needs a read grant for the artwork URI. This holds the authority and the
 * target package list (from the engine reference) and a grant helper; the
 * MediaBrowserService/provider registration itself is app-module manifest work.
 */
package com.shilapi.xcertplay.hud.zeekr

object ZeekrMediaTargets {
    /** FileProvider authority that serves the now-playing artwork. */
    const val ARTWORK_AUTHORITY = "com.zeekr.carplay.media-artwork"

    /** Packages that render CarPlay media/artwork and need the URI read grant. */
    val ARTWORK_CONSUMERS: List<String> = listOf(
        "com.zeekr.mediacenter",
        "com.zeekr.mediawidget",
        "com.zeekr.mediawidget.csd.island",
        "com.zeekr.carlauncher",
        "com.zeekr.carlauncher3d",
        "com.zeekr.media.multidisplay",
        "ecarx.launcher3",
        "com.zeekr.eva",
        "com.zeekr.local",
        "com.zeekr.media.qq",
    )

    /** Grant the artwork URI to every consumer; [grant] is the per-package grant call. */
    inline fun grantArtwork(grant: (pkg: String) -> Unit) {
        for (pkg in ARTWORK_CONSUMERS) runCatching { grant(pkg) }
    }
}
