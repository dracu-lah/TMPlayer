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
        // The order and Show first, then the filters by what a video is (U6).
        assertEquals(listOf(Translator.messages.gridSortLabel, Translator.messages.gridFilterLabel), groups.map { it.title }.take(2))
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
    fun `Play all and Shuffle lead the Play group before Play random, and each is left out when it has nothing to start`() {
        var all = 0
        var shuffled = 0
        val full = VideoMenuState(VideoSort.Newest, VideoFilter.All, onSort = {}, onFilter = {}, onPlayRandom = {}, onPlayAll = { all++ }, onShuffle = { shuffled++ })
        val first = videoMenuGroups(full, Translator.messages).first()
        assertEquals(Translator.messages.commonPlay, first.title)
        assertEquals(listOf("Play all", "Shuffle", "Play random"), first.choices.map { it.label })
        first.choices[0].onPick()
        first.choices[1].onPick()
        assertEquals(1, all)
        assertEquals(1, shuffled)
        // A chat of one video: Play all, no Shuffle. An empty list: no Play group at all.
        val one = VideoMenuState(VideoSort.Newest, VideoFilter.All, onSort = {}, onFilter = {}, onPlayAll = {})
        assertEquals(listOf("Play all"), videoMenuGroups(one, Translator.messages).first().choices.map { it.label })
        assertEquals(Translator.messages.gridSortLabel, videoMenuGroups(state(null), Translator.messages).first().title)
    }

    @Test
    fun `the order and the filter keep their place after it`() {
        val groups = videoMenuGroups(state {}, Translator.messages)
        assertEquals(Translator.messages.gridSortLabel, groups[1].title)
        assertEquals(VideoSort.entries.size, groups[1].choices.size)
        assertEquals(Translator.messages.gridFilterLabel, groups[2].title)
        // Then Length, Picture, With and Added; Language only once the loaded names state one.
        assertEquals(
            listOf(Translator.messages.gridFilterLengthLabel, Translator.messages.gridFilterPictureLabel, Translator.messages.gridFilterWithLabel, Translator.messages.gridFilterAddedLabel),
            groups.drop(3).map { it.title },
        )
        assertNull(groups.getOrNull(7))
    }

    @Test
    fun `the Hide small clips switch sits right under Show, and is absent without a handler`() {
        val withSwitch = VideoMenuState(VideoSort.Newest, VideoFilter.All, onSort = {}, onFilter = {}, hideSmall = true, onHideSmall = {})
        val groups = videoMenuGroups(withSwitch, Translator.messages)
        assertEquals(Translator.messages.gridFilterSizeLabel, groups[2].title)
        assertEquals(Translator.messages.gridFilterLengthLabel, groups[3].title)
        val line = groups[2].choices.single()
        assertEquals("Hide small clips (under 50 MB)", line.label)
        assertTrue("reads on", line.chosen)
        assertTrue(videoMenuGroups(state(null), Translator.messages).none { it.title == Translator.messages.gridFilterSizeLabel })
    }

    @Test
    fun `a switch press asks for the opposite, and a custom floor gets a line that resets it`() {
        var asked: Boolean? = null
        var reset = 0
        val st = VideoMenuState(
            VideoSort.Newest, VideoFilter.All, onSort = {}, onFilter = {},
            hideSmall = false, onHideSmall = { asked = it },
            customLimits = "You'll see videos from 700 MB upwards.", onResetLimits = { reset++ },
        )
        val lines = videoMenuGroups(st, Translator.messages)[2].choices
        assertEquals(2, lines.size)
        lines[0].onPick()
        assertEquals(true, asked)
        assertTrue(lines[1].label, "700 MB" in lines[1].label)
        lines[1].onPick()
        assertEquals(1, reset)
    }
}
