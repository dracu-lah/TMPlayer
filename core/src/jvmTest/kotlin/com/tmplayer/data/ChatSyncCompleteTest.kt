package com.tmplayer.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A chat sync that ends on TDLib's "nothing more to load" is a complete one. */
class ChatSyncCompleteTest {

    @Test
    fun `404 Not Found ends the sync as complete`() {
        // What TDLib actually sends: the number is in the code, not in the message.
        assertTrue(isAllLoaded(404, "Not Found"))
    }

    @Test
    fun `a real failure is still one`() {
        assertFalse(isAllLoaded(429, "Too Many Requests: retry after 30"))
        assertFalse(isAllLoaded(500, "Request aborted"))
    }
}
