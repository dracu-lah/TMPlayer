package com.tmplayer.ui.browse

import com.tmplayer.data.VideoFilter
import com.tmplayer.data.VideoSort
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.icons.TmIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/**
 * U19: a chat's Sort menu tells its lines apart by glyph as well as by word. Oldest and Last
 * played wore the same clock, and Single videos the only filled glyph among outlines.
 */
class VideoMenuIconsTest {

    @Before
    fun english() = Translator.use(Languages.ENGLISH)

    @Test
    fun `every order and every Show line has a glyph of its own`() {
        val sorts = VideoSort.entries.map(::videoSortIcon)
        assertEquals(sorts.size, sorts.toSet().size)
        val filters = VideoFilter.entries.map(::videoFilterIcon)
        assertEquals(filters.size, filters.toSet().size)
    }

    @Test
    fun `the same idea wears the same glyph in both groups`() {
        assertEquals(videoFilterIcon(VideoFilter.Unwatched), videoSortIcon(VideoSort.Unwatched))
    }

    @Test
    fun `no filled glyph among the outlines`() {
        val filled = setOf(TmIcons.PlaySolid, TmIcons.PauseSolid)
        (VideoSort.entries.map(::videoSortIcon) + VideoFilter.entries.map(::videoFilterIcon)).forEach { assertNotEquals(true, it in filled) }
    }
}
