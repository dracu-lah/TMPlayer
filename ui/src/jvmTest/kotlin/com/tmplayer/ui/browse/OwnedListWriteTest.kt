package com.tmplayer.ui.browse

import com.tmplayer.data.AccountOwner
import com.tmplayer.data.AccountStamp
import com.tmplayer.data.Accounts
import com.tmplayer.i18n.L
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A list write from a menu runs off the screen's scope (HX88): it carries the profile that was
 * open when it was asked, so a switch before it lands drops it instead of writing it into the
 * next profile's lists.
 */
class OwnedListWriteTest {

    @Test
    fun `a list write carries the profile it was asked under`() = runBlocking {
        val asked = Accounts.stamp
        val seen = CompletableDeferred<AccountStamp?>()
        listWrite(say = {}, s = L.messages) {
            seen.complete(currentCoroutineContext()[AccountOwner]?.stamp)
            ""
        }
        assertEquals(asked, withTimeout(5_000) { seen.await() })
    }
}
