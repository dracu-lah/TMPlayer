package com.tmplayer.player

import androidx.test.core.app.ApplicationProvider
import com.tmplayer.data.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** HX9: the setting's default and storage, and when leaving the app enters picture in picture. */
@RunWith(RobolectricTestRunner::class)
class PictureInPictureRulesTest {

    private fun hint(sdk: Int, on: Boolean, tv: Boolean = false, playing: Boolean = true, casting: Boolean = false, sheet: Boolean = false) =
        PictureInPictureRules.entersOnLeaveHint(sdk, on, tv, playing, casting, sheet)

    @Test
    fun `the setting is on by default and remembers off`() = runBlocking {
        val store = SettingsStore(ApplicationProvider.getApplicationContext<android.content.Context>())
        assertTrue(store.pictureInPictureOnLeave.first())
        store.setPictureInPictureOnLeave(false)
        assertFalse(store.pictureInPictureOnLeave.first())
        store.setPictureInPictureOnLeave(true)
        assertTrue(store.pictureInPictureOnLeave.first())
    }

    @Test
    fun `Android 8 to 11 enters from the leave hint only with the setting on`() {
        for (sdk in listOf(26, 29)) {
            assertTrue(hint(sdk, on = true))
            assertFalse(hint(sdk, on = false))
            assertFalse(hint(sdk, on = true, playing = false))
            assertFalse(hint(sdk, on = true, casting = true))
            assertFalse(hint(sdk, on = true, sheet = true))
            assertFalse(hint(sdk, on = true, tv = true))
        }
    }

    @Test
    fun `Android 12 and later leaves entering to the system request`() {
        for (sdk in listOf(31, 34, 36)) {
            assertFalse(hint(sdk, on = true))
            assertFalse(hint(sdk, on = false))
        }
    }

    @Test
    fun `auto enter follows the setting, playback, sheets, the lock and the television`() {
        assertTrue(PictureInPictureRules.autoEnter(true, tv = false, playing = true, sheetUp = false, locked = false))
        assertFalse(PictureInPictureRules.autoEnter(false, tv = false, playing = true, sheetUp = false, locked = false))
        assertFalse(PictureInPictureRules.autoEnter(true, tv = true, playing = true, sheetUp = false, locked = false))
        assertFalse(PictureInPictureRules.autoEnter(true, tv = false, playing = false, sheetUp = false, locked = false))
        assertFalse(PictureInPictureRules.autoEnter(true, tv = false, playing = true, sheetUp = true, locked = false))
        assertFalse(PictureInPictureRules.autoEnter(true, tv = false, playing = true, sheetUp = false, locked = true))
    }

    @Test
    fun `the manual entry does not look at the setting`() {
        // enterPictureInPictureNow() asks neither rule: with the setting off, only the auto paths close.
        assertFalse(PictureInPictureRules.autoEnter(false, tv = false, playing = true, sheetUp = false, locked = false))
        assertFalse(hint(29, on = false))
    }
}
