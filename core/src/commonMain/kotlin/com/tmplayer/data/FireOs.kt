package com.tmplayer.data

/**
 * Amazon's Fire OS, told apart from stock Android.
 *
 * Fire OS is Android underneath, so [DeviceForm] already says "television" on a Fire TV. What it
 * does not say is that Amazon changed a few things a remote lands on: the microphone button belongs
 * to Alexa and never reaches an app, there is no speech recogniser for an app to call, the home
 * screen has no Watch Next row to write to, and Amazon's own guidelines give the Menu key to the
 * playing app's options. Those differences are [RemoteQuirks]; this only answers which device it is.
 *
 * Pure, so it is tested with a faked manufacturer and feature list: Fire OS cannot be emulated.
 */
object FireOs {

    /** The system feature every Fire TV declares, from the first box to the current sticks. */
    const val FIRE_TV_FEATURE = "amazon.hardware.fire_tv"

    enum class Kind {
        /** Stock Android, Android TV or Google TV. */
        None,

        /** A Fire TV stick, cube or box, or a television running Fire TV. */
        Tv,

        /** A Fire tablet: Fire OS on a touch screen, which keeps the phone layout. */
        Tablet,
    }

    /**
     * Which kind of device this is.
     *
     * The feature flag is Amazon's documented test and comes first. The model prefix is the way
     * round for a Fire TV build that ever ships without it: every Fire TV model name starts "AFT"
     * (AFTMM, AFTKA, AFTSS), while Fire tablets are "KF" names. Anything else from Amazon is treated
     * as a tablet, the device that still has a touch screen to fall back on.
     */
    fun detect(manufacturer: String, model: String, features: Set<String>): Kind {
        if (FIRE_TV_FEATURE in features) return Kind.Tv
        if (!manufacturer.trim().equals("Amazon", ignoreCase = true)) return Kind.None
        return if (model.trim().uppercase().startsWith("AFT")) Kind.Tv else Kind.Tablet
    }
}

/** How a request to search by voice is answered on this device. */
enum class VoiceRoute {
    /** Android's speech recogniser, which hands back the spoken words. */
    Recognizer,

    /**
     * Open the system keyboard on the search field. On Fire OS the keyboard is where dictation
     * lives: the remote's microphone dictates into it while it is up, and a Fire tablet's keyboard
     * has a microphone key of its own.
     */
    Keyboard,

    /** No voice at all, and no microphone button pretending otherwise. */
    None,
}

/**
 * What the player and the search box do differently because of the remote and the system around
 * it. One place for every Fire OS difference, so each call site asks a question instead of
 * testing the device.
 */
data class RemoteQuirks(
    /** A short press of Menu opens the player's own menu, as Amazon's guidelines ask. */
    val menuOpensPlayerMenu: Boolean,
    /** Holding rewind or fast forward scans in growing steps instead of repeating one jump. */
    val holdSeekAccelerates: Boolean,
    /** Whether the system keeps a Watch Next row this app can write to. */
    val watchNextSupported: Boolean,
    /** Whether the speech recogniser should be trusted even when one answers the probe. */
    private val recognizerUsable: Boolean,
    /** Where a voice request goes when there is no recogniser to use. */
    private val voiceFallback: VoiceRoute,
) {

    /** Where a press of the microphone button goes, given whether a recogniser is installed. */
    fun voiceRoute(recognizerInstalled: Boolean): VoiceRoute =
        if (recognizerUsable && recognizerInstalled) VoiceRoute.Recognizer else voiceFallback

    companion object {

        /**
         * The quirks for a device.
         *
         * On Fire OS the recogniser is never used, even when something answers the probe: the
         * remote's microphone is Alexa's, and what resolves on some Fire tablets opens a screen that
         * never returns text. Watch Next is an Android TV launcher feature, absent on a phone and
         * on Fire TV's own home screen.
         */
        fun of(fire: FireOs.Kind, tv: Boolean): RemoteQuirks = when (fire) {
            FireOs.Kind.Tv -> RemoteQuirks(
                menuOpensPlayerMenu = true,
                holdSeekAccelerates = true,
                watchNextSupported = false,
                recognizerUsable = false,
                voiceFallback = VoiceRoute.Keyboard,
            )
            FireOs.Kind.Tablet -> RemoteQuirks(
                menuOpensPlayerMenu = false,
                holdSeekAccelerates = false,
                watchNextSupported = false,
                recognizerUsable = false,
                voiceFallback = VoiceRoute.Keyboard,
            )
            FireOs.Kind.None -> RemoteQuirks(
                menuOpensPlayerMenu = false,
                holdSeekAccelerates = false,
                watchNextSupported = tv,
                recognizerUsable = true,
                voiceFallback = VoiceRoute.None,
            )
        }

        /** Repeats of a held key arrive about every 50 ms; every fourth one moves, five a second. */
        const val HOLD_EVERY = 4

        /** About two seconds of holding at the first step, then the larger ones. */
        private const val HOLD_MEDIUM_FROM = 40
        private const val HOLD_LARGE_FROM = 100

        /**
         * How far a held rewind or fast forward moves on this repeat, or 0 to let it pass.
         *
         * The first press is the ordinary [baseMs] jump. Held, it scans: [baseMs] five times a
         * second, then three times that after two seconds, then six times after five, so a held key
         * crosses a film in seconds while a tap still lands where it always did.
         */
        fun holdStepMs(repeatCount: Int, baseMs: Long): Long {
            if (repeatCount <= 0) return baseMs
            if (repeatCount % HOLD_EVERY != 0) return 0L
            return when {
                repeatCount >= HOLD_LARGE_FROM -> baseMs * 6
                repeatCount >= HOLD_MEDIUM_FROM -> baseMs * 3
                else -> baseMs
            }
        }
    }
}
