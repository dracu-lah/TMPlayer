package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDownmixTest {

    @Test
    fun `a phone folds 5 point 1 down to two channels`() {
        assertTrue(
            AudioDownmix.folds(television = false)
                .contains(AudioDownmix.Fold.ConstantPower(6)),
        )
    }

    @Test
    fun `stereo and mono are handed through untouched`() {
        val folds = AudioDownmix.folds(television = false)
        assertEquals(AudioDownmix.Fold.Untouched(1), folds[0])
        assertEquals(AudioDownmix.Fold.Untouched(2), folds[1])
    }

    @Test
    fun `every layout up to the widest has a matrix, so nothing can refuse to configure`() {
        val folds = AudioDownmix.folds(television = false)
        assertEquals(AudioDownmix.MAX_LAYOUT, folds.size)
        val counts = folds.map {
            when (it) {
                is AudioDownmix.Fold.Untouched -> it.channels
                is AudioDownmix.Fold.ConstantPower -> it.channels
                is AudioDownmix.Fold.Wide -> it.channels
            }
        }
        assertEquals((1..AudioDownmix.MAX_LAYOUT).toList(), counts)
    }

    /** The layout that broke an episode: past Media3's helper, so this file builds the fold. */
    @Test
    fun `twelve channels get a fold built here`() {
        val fold = AudioDownmix.folds(television = false)[11]
        assertEquals(AudioDownmix.Fold.Wide(12, AudioDownmix.wideCoefficients(12)), fold)
    }

    @Test
    fun `a wide fold alternates the channels between the two sides`() {
        val coefficients = AudioDownmix.wideCoefficients(4)
        // Row major: channel 0 to left only, channel 1 to right only, and so on.
        assertTrue(coefficients[0] > 0f && coefficients[1] == 0f)
        assertTrue(coefficients[2] == 0f && coefficients[3] > 0f)
        assertTrue(coefficients[4] > 0f && coefficients[5] == 0f)
        assertTrue(coefficients[6] == 0f && coefficients[7] > 0f)
    }

    @Test
    fun `a wide fold keeps the power constant, so it cannot clip`() {
        for (channels in 7..AudioDownmix.MAX_LAYOUT) {
            val coefficients = AudioDownmix.wideCoefficients(channels)
            for (output in 0 until AudioDownmix.STEREO) {
                val power = (0 until channels).sumOf { input ->
                    val value = coefficients[input * AudioDownmix.STEREO + output].toDouble()
                    value * value
                }
                assertEquals("$channels channels, output $output", 1.0, power, 1e-5)
            }
        }
    }

    @Test
    fun `a television keeps the processor out of the chain`() {
        assertEquals(emptyList<AudioDownmix.Fold>(), AudioDownmix.folds(television = true))
    }
}
