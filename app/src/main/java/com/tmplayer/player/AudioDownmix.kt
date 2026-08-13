package com.tmplayer.player

/**
 * Which decoded audio layouts get folded to stereo before they reach the AudioTrack, and how.
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
 * So the fold happens here instead, in the audio processor chain, on any device that is not a
 * television. Nothing is lost by doing it a step earlier, and a TV stick wired to a receiver still
 * gets every channel untouched.
 */
object AudioDownmix {

    const val STEREO = 2

    /** The widest layout Media3's own constant power helper will build a fold for. */
    const val MAX_CONSTANT_POWER = 6

    /**
     * Every layout a matrix is registered for.
     *
     * Deliberately far wider than the layouts anyone expects to meet. [ChannelMixingAudioProcessor]
     * refuses to configure for a channel count it holds no matrix for, and refusing is fatal: the
     * renderer dies and the viewer is told the video is broken. That is not a hypothetical. An
     * episode of a series whose other episodes played perfectly arrived with a twelve channel track,
     * which was three channels past the end of the list this used to keep, and it failed instantly
     * every time with "This video wouldn't play. Try a different copy of it." The list now runs to
     * [MAX_LAYOUT], and everything past the fold Media3 can build gets one built here, so there is
     * no count left that can end a film.
     */
    const val MAX_LAYOUT = 32

    /** How a given layout is handled: the matrix to register, described without Media3 types. */
    sealed interface Fold {
        /** Hand the channels through untouched. Media3 recognises the square matrix and skips it. */
        data class Untouched(val channels: Int) : Fold

        /** Media3's own constant power fold to stereo, for the layouts it has coefficients for. */
        data class ConstantPower(val channels: Int) : Fold

        /** A fold this file builds, for the layouts Media3 will not build one for. */
        data class Wide(val channels: Int, val coefficients: FloatArray) : Fold {
            // Data classes compare arrays by identity, which would make two equal folds unequal and
            // is exactly the sort of thing a test notices and nothing else does.
            override fun equals(other: Any?): Boolean = other is Wide &&
                other.channels == channels &&
                other.coefficients.contentEquals(coefficients)

            override fun hashCode(): Int = 31 * channels + coefficients.contentHashCode()
        }
    }

    /**
     * The matrices the processor needs, or empty when it should not be in the chain at all.
     *
     * The question asked is "is this a television?", not "how many channels does the output take?".
     * The second is the one that sounds right, and Media3 will answer it, but on the phone this bug
     * was found on the answer is a lie: it reports more than stereo, from a Dolby profile the audio
     * server then fails to actually play. A television is the case where multichannel demonstrably
     * works, over HDMI to whatever is wired up, so that is the case that keeps its channels.
     */
    fun folds(television: Boolean): List<Fold> {
        if (television) return emptyList()
        return (1..MAX_LAYOUT).map { channels ->
            when {
                channels <= STEREO -> Fold.Untouched(channels)
                channels <= MAX_CONSTANT_POWER -> Fold.ConstantPower(channels)
                else -> Fold.Wide(channels, wideCoefficients(channels))
            }
        }
    }

    /**
     * A stereo fold for a layout nothing here knows the channel order of.
     *
     * Twelve channels could be 7.1.4, or an object bed, or a remuxer's idea of a joke, and the one
     * thing that can be said about the order is that the pattern of every layout that does have a
     * documented order alternates left and right. So even numbered channels go left, odd numbered
     * ones go right, which comes out as a real stereo image for anything that follows that pattern
     * and as a safe fold for anything that does not.
     *
     * The gain is constant power: each output's coefficients square and sum to one, so a fold of
     * twelve channels is no louder than a fold of six and neither of them clips.
     */
    fun wideCoefficients(channels: Int): FloatArray {
        val perSide = FloatArray(STEREO) { side ->
            val count = (channels - side + 1) / STEREO
            if (count <= 0) 0f else (1.0 / Math.sqrt(count.toDouble())).toFloat()
        }
        // Row major, as Media3 reads it: every input channel's pair of output coefficients in turn.
        return FloatArray(channels * STEREO) { index ->
            val input = index / STEREO
            val output = index % STEREO
            if (input % STEREO == output) perSide[output] else 0f
        }
    }
}
