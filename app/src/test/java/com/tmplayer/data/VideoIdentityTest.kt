package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoIdentityTest {
    @Test fun `identity survives persistence`() {
        val identity = VideoIdentity(accountId = 7, chatId = -100123, messageId = 42)
        assertEquals(identity, VideoIdentity.decode(identity.storageSuffix))
    }

    @Test fun `accounts and chats cannot collide`() {
        val first = VideoIdentity(7, -100123, 42)
        assertNotEquals(first.storageSuffix, VideoIdentity(8, -100123, 42).storageSuffix)
        assertNotEquals(first.storageSuffix, VideoIdentity(7, -100124, 42).storageSuffix)
        assertNotEquals(first.storageSuffix, VideoIdentity(7, -100123, 43).storageSuffix)
    }

    @Test fun `invalid persisted identity is ignored`() {
        assertNull(VideoIdentity.decode("7_bad_42"))
        assertNull(VideoIdentity.decode("7_42"))
    }
}
