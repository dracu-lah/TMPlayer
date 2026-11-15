package com.tmplayer.ui.onboarding

import com.tmplayer.data.DeviceFit
import com.tmplayer.data.LightSettings
import com.tmplayer.data.Recommendation
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The words around the low-end device recommendations (CP68), and the hint's list. */
class DeviceFitUiTest {

    @Test
    fun `every locale names every change and the reason, none left in English`() {
        val en = Translator.english()
        for (language in Languages.all) {
            val s = Translator.load(language.tag)
            for (recommendation in Recommendation.entries) {
                val copy = s.recommendationCopy(recommendation)
                assertTrue(copy.title.isNotBlank() && copy.detail.isNotBlank())
            }
            val headlines = listOf(DeviceFit(true, false), DeviceFit(false, true), DeviceFit(true, true)).map { s.fitHeadline(it) }
            assertEquals("three different reasons in ${language.tag}", 3, headlines.toSet().size)
            if (language.tag != Languages.ENGLISH) {
                assertNotEquals(language.tag, en.deviceQuestion, s.deviceQuestion)
                assertNotEquals(language.tag, en.settingsLighterPicturesOn, s.settingsLighterPicturesOn)
                assertNotEquals(language.tag, en.settingsReduceMotionOn, s.settingsReduceMotionOn)
            }
        }
    }

    @Test
    fun `the rows the changes name are the Settings rows`() {
        val s = Translator.english()
        assertEquals(s.settingsStreamRecommended, s.recommendationCopy(Recommendation.LowStorageMode).title)
        assertEquals(s.settingsTrickplay, s.recommendationCopy(Recommendation.NoSeekThumbnails).title)
        assertEquals(s.settingsLighterPictures, s.recommendationCopy(Recommendation.LighterPictures).title)
        assertEquals(s.settingsReduceMotion, s.recommendationCopy(Recommendation.ReduceMotion).title)
    }

    @Test
    fun `after a struggle the desktop is offered storage only, and only when short of it`() {
        val defaults = LightSettings(lowStorageMode = false, seekThumbnails = true, lighterPictures = false, reduceMotion = false)
        // The desktop has no picture rows (hasPictureSettings), so a stutter there offers nothing new.
        assertEquals(emptyList<Recommendation>(), recommendationsAfterStruggle(DeviceFit.Fine, defaults, seekThumbnailsShown = false))
        assertEquals(
            listOf(Recommendation.LowStorageMode),
            recommendationsAfterStruggle(DeviceFit(false, true), defaults, seekThumbnailsShown = false),
        )
    }
}
