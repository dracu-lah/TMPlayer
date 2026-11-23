package com.tmplayer.cast.dlna

import com.tmplayer.i18n.L

/**
 * Which step of a DLNA cast went wrong, so the cast screen names it instead of a plain "couldn't
 * play": nobody who saw a cast fail on a TV could say more than "it didn't work", and each of these
 * has a different fix (the network, the firewall, the format, the TV itself).
 *
 * Finding the TV and reading its description have their own words already (the picker's "No TVs
 * found yet", the address dialog's "isn't a TV TMPlayer can cast to", "Couldn't connect to").
 */
sealed interface DlnaFailure {

    /** SetAVTransportURI or Play got no answer at all (timed out, refused, cut short). */
    data object NoAnswer : DlnaFailure

    /** UPnP 714: the TV does not take this type of file, even with plain metadata. */
    data object BadType : DlnaFailure

    /** UPnP 716: the TV said it could not open the address. */
    data object CouldNotOpen : DlnaFailure

    /** UPnP 701, 705, 715: busy with something else, still after one Stop. */
    data object Busy : DlnaFailure

    /** Any other UPnP error, [code] as the TV gave it (null when it gave none). */
    data class Refused(val code: Int?) : DlnaFailure

    /** The TV took the address and was told to play, but never connected to fetch the video. */
    data object NeverFetched : DlnaFailure

    /** The TV connected for the video, then stopped before it ever played: most often the format. */
    data object StoppedBeforePlaying : DlnaFailure

    /** The TV played, then stopped with an error of its own. */
    data object StoppedWithError : DlnaFailure

    /** What the cast screen says. */
    fun text(): String = when (this) {
        NoAnswer -> L.castFailedNoAnswer
        BadType -> L.castRefusedType
        CouldNotOpen -> L.castRefusedOpen
        Busy -> L.castRefusedBusy
        is Refused -> if (code != null) L.castFailedRefusedCode(code) else L.castLoadFailed
        NeverFetched -> L.castFailedNeverFetched
        StoppedBeforePlaying -> L.castFailedStoppedStart
        StoppedWithError -> L.castFailedStoppedError
    }

    companion object {
        /** The failure a load came to, or null when it loaded. */
        fun of(load: DlnaLoad): DlnaFailure? = when (load) {
            DlnaLoad.Loaded -> null
            is DlnaLoad.Failed -> NoAnswer
            is DlnaLoad.Refused -> when (load.reason) {
                LoadRefusal.BadType -> BadType
                LoadRefusal.NotFound -> CouldNotOpen
                LoadRefusal.Busy -> Busy
                LoadRefusal.Other -> Refused(load.code)
            }
        }
    }
}
