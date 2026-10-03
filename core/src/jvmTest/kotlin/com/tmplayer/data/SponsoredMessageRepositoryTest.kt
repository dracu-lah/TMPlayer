package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SponsoredMessageRepositoryTest {

    private fun sponsored(id: Long) = SponsoredItem(
        messageId = id,
        label = "Sponsored",
        title = "Example",
        text = "Complete sponsored text",
        buttonText = "Open",
        sponsorInfo = "Sponsor information",
        additionalInfo = "Additional information",
        sponsorUrl = "https://example.com",
        canBeReported = true,
        miniThumbnail = null,
        thumbnailFileId = 0,
    )

    @Test
    fun `ordinary media stays unchanged when Telegram returns no sponsored messages`() {
        val result = placeSponsored(listOf("a", "b"), null)

        assertEquals(
            listOf(MediaFeedEntry.Media("a"), MediaFeedEntry.Media("b")),
            result,
        )
    }

    @Test
    fun `sponsored messages follow Telegram spacing without replacing media`() {
        val result = placeSponsored(
            media = listOf("a", "b", "c", "d", "e"),
            sponsored = SponsoredBatch(
                messages = listOf(sponsored(10), sponsored(11)),
                messagesBetween = 2,
            ),
        )

        assertEquals(
            listOf(
                MediaFeedEntry.Media("a"),
                MediaFeedEntry.Media("b"),
                MediaFeedEntry.Sponsored(sponsored(10)),
                MediaFeedEntry.Media("c"),
                MediaFeedEntry.Media("d"),
                MediaFeedEntry.Sponsored(sponsored(11)),
                MediaFeedEntry.Media("e"),
            ),
            result,
        )
    }

    @Test
    fun `zero spacing places one sponsored message after the media`() {
        val result = placeSponsored(
            media = listOf("a"),
            sponsored = SponsoredBatch(listOf(sponsored(10), sponsored(11)), 0),
        )

        assertEquals(
            listOf(MediaFeedEntry.Media("a"), MediaFeedEntry.Sponsored(sponsored(10))),
            result,
        )
    }

    @Test
    fun `view is recorded only when the complete text block is in the viewport`() {
        assertTrue(isSponsoredTextFullyVisible(20f, 180f, 200f))
        assertFalse(isSponsoredTextFullyVisible(-1f, 180f, 200f))
        assertFalse(isSponsoredTextFullyVisible(20f, 201f, 200f))
        assertFalse(isSponsoredTextFullyVisible(20f, 180f, 0f))
    }
}
