package com.tmplayer.desktop.ui

/**
 * The window's title (WP-51): "Page - TMPlayer" on a page, the video's name on its player, and
 * only "TMPlayer" while a PIN profile is hidden (locked, or no window on screen), since the title
 * shows in the taskbar, in Alt+Tab and in screen sharing. A chat's name is never in it.
 */
internal object WindowTitle {
    const val APP = "TMPlayer"
    private const val SEPARATOR = " - "

    /**
     * [videoOwner] is the profile the video was opened under: a video of a profile no longer open
     * (a switch with the player up) is not named, as [com.tmplayer.desktop.os.ProfileOutside.mediaTitle]
     * has it for the media controls (SEC2-08).
     */
    fun of(
        hidden: Boolean,
        page: String?,
        video: String?,
        videoOwner: com.tmplayer.data.AccountStamp? = null,
        current: com.tmplayer.data.AccountStamp = com.tmplayer.data.Accounts.stamp,
    ): String = when {
        hidden -> APP
        videoOwner != null && videoOwner != current -> APP
        !video.isNullOrBlank() -> video.trim() + SEPARATOR + APP
        !page.isNullOrBlank() -> page.trim() + SEPARATOR + APP
        else -> APP
    }
}

/**
 * The smallest the window may be (WP-51): 960 by 600 at the Default size, growing with the
 * interface size, but never more than the usable part of the screen and never under 800 by 480, so
 * a 1366 by 768 laptop at 125 or 150 per cent can still hold the window.
 */
internal object WindowFloor {
    const val WIDTH = 960
    const val HEIGHT = 600
    const val FLOOR_WIDTH = 800
    const val FLOOR_HEIGHT = 480

    fun minimum(factor: Float, usableWidth: Int, usableHeight: Int): Pair<Int, Int> {
        val width = (WIDTH * factor).toInt().coerceAtMost(usableWidth).coerceAtLeast(FLOOR_WIDTH)
        val height = (HEIGHT * factor).toInt().coerceAtMost(usableHeight).coerceAtLeast(FLOOR_HEIGHT)
        return width to height
    }
}
