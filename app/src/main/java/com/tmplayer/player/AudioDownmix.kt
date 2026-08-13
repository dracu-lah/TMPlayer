package com.tmplayer.player

/**
 * Which decoded audio layouts have to be folded to stereo before they reach the AudioTrack.
 *
 * A film remux commonly carries 5.1, and the obvious thing to do with it on a device that has two
 * speakers is to hand all six channels to Android and let the mixer fold them. Some phones cannot:
 * on a Redmi running HyperOS's Dolby chain a six channel track has its AudioTrack invalidated by the
 * audio server about once a second, which Media3 reports as "AudioTrack write failed: -6" and
 * recovers from by rebuilding the track, so the film plays a second, stalls, plays a second, and
 * eventually gives up with an error that reads as if the video were broken. It is not: the same
 * file plays perfectly with two channels, and perfectly on a TV, where the six go out over HDMI
 * and never meet that mixer at all.
 *
 * So the fold happens here instead, in the audio processor chain, whenever the output cannot take
 * more than stereo anyway. Nothing is lost by doing it a step earlier, and a TV stick wired to a
 * receiver still reports six or eight channels and still gets all of them untouched.
 */
object AudioDownmix {

    const val STEREO = 2

    /**
     * The highest layout Media3 has constant power coefficients for.
     *
     * `ChannelMixingMatrix.createForConstantPower` throws above this, so 7.1 keeps its channels
     * and Android's own fold, rather than the renderer dying on the way to fixing 5.1.
     */
    const val MAX_FOLDABLE = 6

    /** The most channels worth naming a matrix for at all: 7.1 is the widest layout in the wild. */
    const val MAX_LAYOUT = 8

    /**
     * Input to output channel counts for every matrix the processor needs, or empty when it should
     * not be in the chain at all.
     *
     * The question asked is "is this a television?", not "how many channels does the output take?".
     * The second is the one that sounds right, and Media3 will answer it, but on the phone this bug
     * was found on the answer is a lie: it reports more than stereo, from a Dolby profile the audio
     * server then fails to actually play. A television is the case where multichannel demonstrably
     * works, over HDMI to whatever is wired up, so that is the case that keeps its channels.
     *
     * Every layout has to be listed, not just the ones being folded. `ChannelMixingAudioProcessor`
     * refuses to configure for a channel count it holds no matrix for, so a chain that named only
     * 5.1 would fail on the next file that turned out to be stereo. The counts that are not folded
     * get a square matrix, which is the identity, which the processor recognises and skips.
     */
    fun matrices(television: Boolean): List<Pair<Int, Int>> {
        if (television) return emptyList()
        return (1..MAX_LAYOUT).map { channels ->
            if (channels in (STEREO + 1)..MAX_FOLDABLE) channels to STEREO else channels to channels
        }
    }
}
