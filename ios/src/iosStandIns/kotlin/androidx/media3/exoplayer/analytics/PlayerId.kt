package androidx.media3.exoplayer.analytics

/** Which player a decision is about, Media3's: one player per screen here, so one id. */
class PlayerId private constructor() {
    companion object {
        val UNSET: PlayerId = PlayerId()
    }
}
