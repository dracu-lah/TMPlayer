package com.tmplayer.player

/**
 * Which decoded audio layouts get folded to stereo before they reach the AudioTrack, and how.
 *
 * Some phones cannot play a multichannel track through the platform mixer: the audio server
 * invalidates the AudioTrack repeatedly and playback stutters until the renderer gives up. So the
 * fold happens here instead, in the audio processor chain, on any device that is not a television.
 * A television keeps every channel, because there multichannel goes out over HDMI and works.
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
     * renderer dies and the viewer is told the video is broken. Covering every count up to
     * [MAX_LAYOUT] leaves none that can end a video.
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
     * The channel count Media3 reports cannot be trusted: a phone may claim more than stereo from a
     * profile its audio server then fails to play. A television is the case where multichannel
     * demonstrably works, over HDMI to whatever is wired up, so that is the case that keeps its
     * channels.
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
     * Every layout with a documented order alternates left and right, so even numbered channels go
     * left and odd numbered ones go right. That is a real stereo image for anything following that
     * pattern and a safe fold for anything that does not.
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
