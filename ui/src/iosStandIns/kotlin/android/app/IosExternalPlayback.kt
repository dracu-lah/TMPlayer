package android.app

import platform.AVKit.AVPictureInPictureController

/**
 * What iOS's player can do with a video beyond the app's own screen: AirPlay to a TV and picture in
 * picture, both of Apple's player (AVPlayer) alone, which is the engine that plays MP4 and MOV
 * (WP-31). mpv draws through Metal, which AVKit cannot take into a corner window or send to a TV.
 *
 * The engine in Swift implements [Controls] and the Kotlin player registers it here while a video
 * is open. The Android player's rows (More: Picture in picture and AirPlay) and the activity's
 * `enterPictureInPictureMode` reach it through this object, so nothing in `:app` knows about iOS.
 */
object IosExternalPlayback {
    /** The engine's side, implemented where the engine lives (Swift's AppleEngine through ChoosingEngine). */
    interface Controls {
        /** True while Apple's player holds the video: the only engine AirPlay and picture in picture work for. */
        val usesApplePlayer: Boolean

        /** Opens iOS's list of AirPlay screens, over the player. */
        fun showAirPlayPicker()

        /** Starts the corner window; false when it cannot (not Apple's player, or the system says no). */
        fun startPictureInPicture(): Boolean

        fun stopPictureInPicture()

        /** Whether the system may start the corner window by itself when the app goes away while this plays. */
        fun setAutoPictureInPicture(on: Boolean)

        /**
         * Whether AVPlayer may send the video to an AirPlay screen at all (SEC2-01): the route button in
         * Control Center and on the lock screen reach the player without the More panel's row. False
         * pauses a video already on another screen.
         */
        fun setExternalPlaybackAllowed(on: Boolean)
    }

    /** The engine of the video now open; null between videos. Set on the main thread; told [externalPlaybackAllowed] at once. */
    var controls: Controls? = null
        set(value) {
            field = value
            value?.setExternalPlaybackAllowed(externalPlaybackAllowed)
        }

    /**
     * The player's verdict for the video now open: true only once the chat is known to allow saving
     * (the cast rule), false before that and for a restricted chat. Set by the player on the main thread
     * whenever the verdict changes, and handed to the engine.
     */
    var externalPlaybackAllowed: Boolean = false
        set(value) {
            field = value
            controls?.setExternalPlaybackAllowed(value)
        }

    /** Whether the corner window is up now; set by the engine on the main thread. */
    var pictureInPictureActive: Boolean = false
        private set

    /** Told when [pictureInPictureActive] changes, by the activity that shows the video. */
    var onPictureInPictureChanged: ((Boolean) -> Unit)? = null

    /** The engine tells that the corner window opened or closed. */
    fun pictureInPictureChanged(active: Boolean) {
        if (pictureInPictureActive == active) return
        pictureInPictureActive = active
        onPictureInPictureChanged?.invoke(active)
    }

    /** Whether this device does picture in picture at all (an iPad or iPhone with the feature on). */
    val pictureInPictureSupported: Boolean by lazy { AVPictureInPictureController.isPictureInPictureSupported() }

    /** Whether the open video is Apple's player's, and so can go to AirPlay and the corner. */
    val appliesToThisVideo: Boolean get() = controls?.usesApplePlayer == true

    /** The corner window for the open video; false when none is open or it is not Apple's player's. */
    fun startPictureInPicture(): Boolean = pictureInPictureSupported && appliesToThisVideo && controls?.startPictureInPicture() == true

    /** The AirPlay list for the open video; false when it is not Apple's player's (mpv's files). */
    fun showAirPlayPicker(): Boolean {
        val engine = controls?.takeIf { it.usesApplePlayer } ?: return false
        engine.showAirPlayPicker()
        return true
    }
}
