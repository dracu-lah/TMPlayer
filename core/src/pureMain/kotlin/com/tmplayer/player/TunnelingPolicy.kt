package com.tmplayer.player

/**
 * Whether ExoPlayer is told to play tunneled (PR5): the television's decoder keeps picture and
 * sound together, so the app never touches a frame. Pure, so every rule is a test.
 *
 * Tunneling is a television's feature and a risky one on cheap boxes, so it is on only when the
 * viewer asked for it ([TunneledMode.On]) or the known device list says this device plays well
 * with it, and never when it cannot work or has failed already:
 * - never on a phone or tablet (the setting is not drawn there);
 * - never when the app draws on the picture ([Input.drawsOnPicture]: 3D, a forced shape, anything
 *   in Media3's effect pipeline, which needs the frames a tunneled decoder never hands over), so
 *   nothing is ever burned into a picture the app cannot reach;
 * - never when the device has no decoder with the capability for the video ([Input.codecCanTunnel]);
 * - never again after a playback error under it ([Input.failedBefore]): the retry is without.
 */
object TunnelingPolicy {

    data class Input(
        val mode: TunneledMode,
        val television: Boolean,
        /** [com.tmplayer.data.KnownDevices.tunnelingFor] for this device and video; null when it says nothing. */
        val knownDevice: Boolean?,
        val drawsOnPicture: Boolean,
        val codecCanTunnel: Boolean,
        val failedBefore: Boolean,
    )

    enum class Reason { NotTelevision, FailedBefore, DrawsOnPicture, NoCodecSupport, ViewerOff, ViewerOn, KnownDevice, AutoOff }

    data class Decision(val tunneled: Boolean, val reason: Reason)

    fun decide(input: Input): Decision = when {
        !input.television -> Decision(false, Reason.NotTelevision)
        input.failedBefore -> Decision(false, Reason.FailedBefore)
        input.mode == TunneledMode.Off -> Decision(false, Reason.ViewerOff)
        input.drawsOnPicture -> Decision(false, Reason.DrawsOnPicture)
        !input.codecCanTunnel -> Decision(false, Reason.NoCodecSupport)
        input.mode == TunneledMode.On -> Decision(true, Reason.ViewerOn)
        input.knownDevice == true -> Decision(true, Reason.KnownDevice)
        else -> Decision(false, Reason.AutoOff)
    }

    /**
     * Whether a playback error is answered with one more try without tunneling: only while the
     * player is tunneling and has not been retried, and not for a failure of the source (the
     * network or the file would stop any decoder, so the retry would only repeat the wait).
     */
    fun retriesWithout(tunneling: Boolean, alreadyRetried: Boolean, sourceFailure: Boolean): Boolean =
        tunneling && !alreadyRetried && !sourceFailure
}
