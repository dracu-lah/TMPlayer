package android.hardware.display

/** Android's displays and their settings; on iOS the one screen, answered through android.view.Display. */
class DisplayManager internal constructor() {
    fun getDisplay(displayId: Int): android.view.Display? = if (displayId == android.view.Display.DEFAULT_DISPLAY) android.view.Display.Main else null

    /** iOS has no such setting: ProMotion follows the content by itself, so the player sets nothing. */
    val matchContentFrameRateUserPreference: Int get() = MATCH_CONTENT_FRAMERATE_NEVER

    companion object {
        const val MATCH_CONTENT_FRAMERATE_UNKNOWN = -1
        const val MATCH_CONTENT_FRAMERATE_NEVER = 0
        const val MATCH_CONTENT_FRAMERATE_SEAMLESSS_ONLY = 1
        const val MATCH_CONTENT_FRAMERATE_ALWAYS = 2

        internal val Shared = DisplayManager()
    }
}
