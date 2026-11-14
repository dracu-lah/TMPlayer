package com.tmplayer.ui.browse

import com.tmplayer.data.VideoFilter
import com.tmplayer.data.VideoSort
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** U16: Play random is the first line of a chat's Sort menu on every device, and absent for an empty list. */
class VideoMenuRandomTest {

    @Before
    fun english() = Translator.use(Languages.ENGLISH)

    private fun state(onPlayRandom: (() -> Unit)?) =
        VideoMenuState(VideoSort.Newest, VideoFilter.All, onSort = {}, onFilter = {}, onPlayRandom = onPlayRandom)

    @Test
    fun `an empty list has no Play random line`() {
        val groups = videoMenuGroups(state(null), Translator.messages)
        assertEquals(listOf(Translator.messages.gridSortLabel, Translator.messages.gridFilterLabel), groups.map { it.title })
        assertTrue(groups.flatMap { it.choices }.none { it.label == Translator.messages.gridPlayRandom })
    }

    @Test
    fun `Play random leads the menu and its press runs the action`() {
        var played = 0
        val groups = videoMenuGroups(state { played++ }, Translator.messages)
        val first = groups.first()
        assertEquals(Translator.messages.commonPlay, first.title)
        assertEquals(1, first.choices.size)
        assertEquals("Play random", first.choices.single().label)
        assertFalse("never shown as the order in force", first.choices.single().chosen)
        first.choices.single().onPick()
        assertEquals(1, played)
    }

    @Test
    fun `the order and the filter keep their place after it`() {
        val groups = videoMenuGroups(state {}, Translator.messages)
        assertEquals(3, groups.size)
        assertEquals(Translator.messages.gridSortLabel, groups[1].title)
        assertEquals(VideoSort.entries.size, groups[1].choices.size)
        assertNull(groups.getOrNull(3))
    }
}
