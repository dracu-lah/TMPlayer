package com.tmplayer.online

import org.junit.Assert.assertEquals
import org.junit.Test

/** [MetaImageSize]: the provider's larger copy of a picture, never a smaller one. */
class MetaImageSizeTest {

    @Test
    fun `tmdb steps up its own ladder`() {
        val poster = "https://image.tmdb.org/t/p/w342/abc.jpg"
        assertEquals(poster, MetaImageSize.atLeast(poster, 300))
        assertEquals("https://image.tmdb.org/t/p/w500/abc.jpg", MetaImageSize.atLeast(poster, 480))
        assertEquals("https://image.tmdb.org/t/p/w780/abc.jpg", MetaImageSize.atLeast(poster, 700))
        assertEquals("https://image.tmdb.org/t/p/original/abc.jpg", MetaImageSize.atLeast(poster, 1200))
        val backdrop = "https://image.tmdb.org/t/p/w780/b.jpg"
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", MetaImageSize.atLeast(backdrop, 1280))
        assertEquals("https://image.tmdb.org/t/p/original/b.jpg", MetaImageSize.atLeast(backdrop, 2496))
        assertEquals("https://image.tmdb.org/t/p/original/s.jpg", MetaImageSize.atLeast("https://image.tmdb.org/t/p/w300/s.jpg", 400))
    }

    @Test
    fun `tvmaze and anilist, and anything else as it is`() {
        assertEquals(
            "https://static.tvmaze.com/uploads/images/original_untouched/1/4600.jpg",
            MetaImageSize.atLeast("https://static.tvmaze.com/uploads/images/medium_portrait/1/4600.jpg", 400),
        )
        assertEquals(
            "https://static.tvmaze.com/uploads/images/medium_portrait/1/4600.jpg",
            MetaImageSize.atLeast("https://static.tvmaze.com/uploads/images/medium_portrait/1/4600.jpg", 200),
        )
        assertEquals(
            "https://s4.anilist.co/file/anilistcdn/media/anime/cover/extraLarge/bx1.jpg",
            MetaImageSize.atLeast("https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx1.jpg", 400),
        )
        assertEquals("https://example.com/w342/a.jpg", MetaImageSize.atLeast("https://example.com/w342/a.jpg", 2000))
    }

    @Test
    fun stillsAreSizedToTheCard() {
        val still = "https://image.tmdb.org/t/p/w300/s.jpg"
        assertEquals("https://image.tmdb.org/t/p/w185/s.jpg", MetaImageSize.still(still, 128))
        assertEquals("https://image.tmdb.org/t/p/w300/s.jpg", MetaImageSize.still(still, 256))
        assertEquals("https://image.tmdb.org/t/p/w300/s.jpg", MetaImageSize.still("https://image.tmdb.org/t/p/original/s.jpg", 352))
        assertEquals("https://image.tmdb.org/t/p/original/s.jpg", MetaImageSize.still(still, 520))
        val tvmaze = "https://static.tvmaze.com/uploads/images/medium_landscape/1/4600.jpg"
        assertEquals(tvmaze, MetaImageSize.still(tvmaze, 520))
    }

    @Test
    fun `a carousel at 1080p and at 4K asks for the size that is wide enough, the original past 1280`() {
        // HX55: TMDB serves no 1920 wide backdrop on its image API (the sizes are w300, w780,
        // w1280 and the original), so the original is the sharpest copy for a frame past 1280.
        val named = "https://image.tmdb.org/t/p/w780/b.jpg"
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", MetaImageSize.atLeast(named, 1280))
        assertEquals("https://image.tmdb.org/t/p/original/b.jpg", MetaImageSize.atLeast(named, 1920))
        assertEquals("https://image.tmdb.org/t/p/original/b.jpg", MetaImageSize.atLeast(named, 3840))
        // The reference crop (w1920_and_h800_multi_faces) is not an API size: an address in it is kept.
        val crop = "https://media.themoviedb.org/t/p/w1920_and_h800_multi_faces/b.jpg"
        assertEquals(crop, MetaImageSize.atLeast(crop, 1920))
    }
}
