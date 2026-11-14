package com.tmplayer.player

import com.tmplayer.data.TelegramLinks
import com.tmplayer.data.TgLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Copy link at this time" (U9): the link TDLib gives, with the moment on it. */
class LinkAtTimeTest {

    @Test
    fun `a public channel's link gets the seconds`() {
        assertEquals("https://t.me/some_channel/123?t=754", LinkAtTime.of("https://t.me/some_channel/123", 754_900))
    }

    @Test
    fun `the start of the video is the plain link, no t of zero`() {
        assertEquals("https://t.me/some_channel/123", LinkAtTime.of("https://t.me/some_channel/123", 0))
        assertEquals("https://t.me/some_channel/123", LinkAtTime.of("https://t.me/some_channel/123", 900))
    }

    @Test
    fun `a private chat's link and a topic's keep their shape`() {
        assertEquals("https://t.me/c/1234567890/55?t=61", LinkAtTime.of("https://t.me/c/1234567890/55", 61_000))
        assertEquals("https://t.me/c/1234567890/7/55?t=5", LinkAtTime.of("https://t.me/c/1234567890/7/55", 5_000))
        assertEquals("https://t.me/some_channel/7/55?t=5", LinkAtTime.of("https://t.me/some_channel/7/55", 5_000))
    }

    @Test
    fun `a link that has a query keeps it and an earlier t is replaced, not doubled`() {
        val withThread = LinkAtTime.of("https://t.me/some_channel/123?thread=9", 30_000)!!
        assertTrue(withThread, "thread=9" in withThread && "t=30" in withThread)
        assertEquals("https://t.me/some_channel/123?t=30", LinkAtTime.of("https://t.me/some_channel/123?t=999", 30_000))
    }

    @Test
    fun `what TMPlayer opens from the link is the time that was copied`() {
        val link = LinkAtTime.of("https://t.me/some_channel/123", 3_723_000)!!
        val back = TelegramLinks.parse(link) as TgLink.Post
        assertEquals(3_723, back.startSec)
        assertEquals(123L, back.messageId)
    }

    @Test
    fun `nothing that is not a post link comes out`() {
        assertNull(LinkAtTime.of(null, 1_000))
        assertNull(LinkAtTime.of("", 1_000))
        assertNull(LinkAtTime.of("not a link", 1_000))
        assertNull(LinkAtTime.of("https://t.me/some_channel", 1_000))
        assertNull(LinkAtTime.of("https://t.me/+AbCdEfGh1234", 1_000))
    }

    @Test
    fun `a bad position cannot make a silly time`() {
        assertEquals("https://t.me/some_channel/123", LinkAtTime.of("https://t.me/some_channel/123", -5_000))
        val huge = LinkAtTime.of("https://t.me/some_channel/123", Long.MAX_VALUE)!!
        assertTrue(huge, huge.endsWith("?t=129600"))
    }

    @Test
    fun `offered only for a video from a message that may be shared and is not built from pieces`() {
        assertTrue(LinkAtTime.offered(hasMessage = true, mayShare = true, fromPieces = false))
        assertFalse(LinkAtTime.offered(hasMessage = false, mayShare = true, fromPieces = false))
        assertFalse("protected or restricted", LinkAtTime.offered(hasMessage = true, mayShare = false, fromPieces = false))
        assertFalse("archive pieces", LinkAtTime.offered(hasMessage = true, mayShare = true, fromPieces = true))
    }
}
