package android.media

import platform.AVFAudio.otherAudioPlaying
import platform.AVFAudio.outputVolume
import kotlin.math.roundToInt

/**
 * The device's volume, Android's AudioManager for the music stream, as iOS has it: the system
 * volume in its sixteen steps, read from the audio session and set through the system's own
 * volume control (MPVolumeView), the one public way iOS allows (maintainer, 2026-11-11). The
 * system's volume badge may show as it changes.
 */
class AudioManager internal constructor() {
    fun getStreamMaxVolume(streamType: Int): Int = STEPS

    fun getStreamMinVolume(streamType: Int): Int = 0

    fun getStreamVolume(streamType: Int): Int =
        (platform.AVFAudio.AVAudioSession.sharedInstance().outputVolume * STEPS).roundToInt().coerceIn(0, STEPS)

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    fun setStreamVolume(streamType: Int, index: Int, flags: Int) {
        val slider = SystemVolume.slider() ?: return
        slider.setValue(index.coerceIn(0, STEPS) / STEPS.toFloat(), animated = false)
        slider.sendActionsForControlEvents(platform.UIKit.UIControlEventValueChanged)
    }

    fun adjustStreamVolume(streamType: Int, direction: Int, flags: Int) =
        setStreamVolume(streamType, getStreamVolume(streamType) + direction, flags)

    val isMusicActive: Boolean get() = platform.AVFAudio.AVAudioSession.sharedInstance().otherAudioPlaying

    companion object {
        const val STREAM_MUSIC = 3
        const val FLAG_SHOW_UI = 1
        const val ADJUST_LOWER = -1
        const val ADJUST_SAME = 0
        const val ADJUST_RAISE = 1
        const val AUDIOFOCUS_GAIN = 1
        const val AUDIOFOCUS_LOSS = -1
        const val AUDIOFOCUS_LOSS_TRANSIENT = -2
        const val AUDIOFOCUS_REQUEST_GRANTED = 1
        const val ACTION_AUDIO_BECOMING_NOISY = "android.media.AUDIO_BECOMING_NOISY"

        /** iOS's volume buttons step the volume in sixteen. */
        private const val STEPS = 16

        internal val Shared by lazy { AudioManager() }
    }
}

/** The system volume control, off screen in the key window, whose slider sets the volume. */
private object SystemVolume {
    private var view: platform.MediaPlayer.MPVolumeView? = null

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    fun slider(): platform.UIKit.UISlider? {
        val window = platform.UIKit.UIApplication.sharedApplication.keyWindow ?: return null
        val volume = view?.takeIf { it.window != null } ?: platform.MediaPlayer.MPVolumeView(
            platform.CoreGraphics.CGRectMake(-1000.0, -1000.0, 1.0, 1.0),
        ).also {
            window.addSubview(it)
            view = it
        }
        return volume.subviews.firstNotNullOfOrNull { it as? platform.UIKit.UISlider }
    }
}
