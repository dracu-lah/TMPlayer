package com.tmplayer.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TitleLedgerTest {

    private fun report(n: Int, chat: String? = null) =
        assertNotNull(TitleReport.of("Show.$n.mkv", "Show $n", chat, includeChat = chat != null))

    @Test
    fun `a rename asks once per pair, whatever was answered`() {
        val a = report(1)
        val ledger = TitleLedger()
        assertTrue(ledger.shouldAsk(a))
        val asked = ledger.withAsked(a)
        assertFalse(asked.shouldAsk(a))
        // The same pair in another case or spacing is the same pair.
        assertFalse(asked.shouldAsk(assertNotNull(TitleReport.of(" SHOW.1.MKV ", "show   1"))))
        assertTrue(asked.shouldAsk(report(2)))
        assertSame(asked, asked.withAsked(a))
    }

    @Test
    fun `a pair that went or waits is never asked about or queued again`() {
        val a = report(1)
        val sent = TitleLedger().withSent(a)
        assertTrue(sent.hasSent(a))
        assertFalse(sent.shouldAsk(a))
        assertSame(sent, sent.withQueued(a))
        val waiting = TitleLedger().withQueued(a)
        assertTrue(waiting.isQueued(a))
        assertFalse(waiting.shouldAsk(a))
        assertSame(waiting, waiting.withQueued(a))
        // A second copy with the chat ticked is the same pair.
        assertSame(waiting, waiting.withQueued(report(1, chat = "Some chat")))
    }

    @Test
    fun `sending takes a pair off the queue and remembers its hash only`() {
        val a = report(1)
        val after = TitleLedger().withQueued(a).withSent(a)
        assertTrue(after.queue.isEmpty())
        assertEquals(listOf(a.hash), after.sent)
        assertFalse(after.encode().contains("Show.1"))
    }

    @Test
    fun `the queue holds twenty and drops the oldest`() {
        var ledger = TitleLedger()
        for (n in 1..25) ledger = ledger.withQueued(report(n))
        assertEquals(TitleLedger.MAX_QUEUE, ledger.queue.size)
        assertEquals((6..25).map { report(it).hash }, ledger.queue.map { it.hash })
        assertTrue(ledger.isQueued(report(25)))
        assertFalse(ledger.isQueued(report(5)))
    }

    @Test
    fun `the lists of hashes are capped too`() {
        var ledger = TitleLedger()
        for (n in 1..(TitleLedger.MAX_SENT + 30)) ledger = ledger.withSent(report(n)).withAsked(report(n))
        assertEquals(TitleLedger.MAX_SENT, ledger.sent.size)
        assertEquals(TitleLedger.MAX_ASKED, ledger.asked.size)
        assertTrue(ledger.hasSent(report(TitleLedger.MAX_SENT + 30)))
        assertFalse(ledger.hasSent(report(1)))
    }

    @Test
    fun `a ledger written out reads back the same`() {
        val ledger = TitleLedger().withSent(report(1)).withAsked(report(2)).withQueued(report(3, chat = "Home videos"))
        assertEquals(ledger, TitleLedger.decode(ledger.encode()))
        assertEquals("Home videos", TitleLedger.decode(ledger.encode()).queue.single().chatName)
    }

    @Test
    fun `garbage reads as an empty ledger and bad entries are skipped`() {
        assertEquals(TitleLedger(), TitleLedger.decode(null))
        assertEquals(TitleLedger(), TitleLedger.decode(""))
        assertEquals(TitleLedger(), TitleLedger.decode("not json"))
        assertEquals(TitleLedger(), TitleLedger.decode("[1,2,3]"))
        assertEquals(TitleLedger(), TitleLedger.decode("{".repeat(200_000)))
        val mixed = """{"v":1,"sent":["0123456789abcdef","zz",7,null,"0123456789ABCDEF"],"asked":"x","queue":["nope",5,"{\"v\":1,\"name\":\"A.mkv\",\"title\":\"A\"}"]}"""
        val read = TitleLedger.decode(mixed)
        assertEquals(listOf("0123456789abcdef"), read.sent)
        assertTrue(read.asked.isEmpty())
        assertEquals(listOf("A.mkv"), read.queue.map { it.fileName })
    }

    @Test
    fun `a queued pair is scrubbed again when it is read`() {
        val text = """{"v":1,"queue":["{\"v\":1,\"name\":\"Night Train +91 98765 43210 @someone.mkv\",\"title\":\"Night Train\"}"]}"""
        val name = TitleLedger.decode(text).queue.single().fileName
        assertFalse(name.contains("98765"))
        assertFalse(name.contains("someone"))
    }
}
