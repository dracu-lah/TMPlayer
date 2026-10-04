package com.tmplayer.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The shared progress policy, against a fake clock and a notifier that writes down what it hears. */
class TransferNotifierTest {

    private class Recording : TransferNotifier {
        val heard = mutableListOf<String>()
        override fun begin(id: Long, kind: TransferNotifier.Kind, title: String) {
            heard += "begin $id $kind"
        }
        override fun progress(id: Long, done: Long, total: Long?, bytesPerSecond: Long?) {
            heard += "progress $id $done/$total"
        }
        override fun complete(id: Long, title: String, body: String, open: TransferNotifier.OpenTarget?) {
            heard += "complete $id $open"
        }
        override fun fail(id: Long, title: String, reason: String, retryable: Boolean) {
            heard += "fail $id $retryable"
        }
        override fun cancel(id: Long) {
            heard += "cancel $id"
        }
        override val capabilities = setOf(TransferNotifier.Capability.ProgressBar)
    }

    private var now = 0L
    private val inner = Recording()
    private val notifier = CoalescingTransferNotifier(inner, clock = { now })

    private val mb = 1024L * 1024

    @Test
    fun `the first progress goes straight through`() {
        notifier.begin(1, TransferNotifier.Kind.Download, "Film")
        notifier.progress(1, 0, 100 * mb, null)
        assertEquals(listOf("begin 1 Download", "progress 1 0/${100 * mb}"), inner.heard)
    }

    @Test
    fun `at most one update a second per transfer`() {
        notifier.begin(1, TransferNotifier.Kind.Download, "Film")
        notifier.progress(1, 1 * mb, 100 * mb, null)
        now += 300
        notifier.progress(1, 5 * mb, 100 * mb, null)
        now += 300
        notifier.progress(1, 9 * mb, 100 * mb, null)
        now += 500
        notifier.progress(1, 12 * mb, 100 * mb, null)
        assertEquals(
            listOf("begin 1 Download", "progress 1 ${1 * mb}/${100 * mb}", "progress 1 ${12 * mb}/${100 * mb}"),
            inner.heard,
        )
    }

    @Test
    fun `nothing is sent when neither the percentage nor the size as written has changed`() {
        notifier.begin(1, TransferNotifier.Kind.Download, "Film")
        val total = 100L * 1024 * mb
        notifier.progress(1, 10 * 1024 * mb, total, null)
        now += 5_000
        // A few bytes more: still 10% and still "10.00 GB".
        notifier.progress(1, 10 * 1024 * mb + 1000, total, null)
        now += 5_000
        // Enough to change the size on screen, though not the percentage.
        notifier.progress(1, 10 * 1024 * mb + 200 * mb, total, null)
        assertEquals(3, inner.heard.size)
        assertEquals("progress 1 ${10 * 1024 * mb + 200 * mb}/$total", inner.heard.last())
    }

    @Test
    fun `transfers are throttled apart from each other`() {
        notifier.begin(1, TransferNotifier.Kind.Download, "One")
        notifier.begin(2, TransferNotifier.Kind.MoveToDownloads, "Two")
        notifier.progress(1, 1 * mb, 10 * mb, null)
        notifier.progress(2, 1 * mb, 10 * mb, null)
        assertEquals(4, inner.heard.size)
    }

    @Test
    fun `an unknown size is judged by the size alone`() {
        notifier.begin(1, TransferNotifier.Kind.Download, "Film")
        notifier.progress(1, 1 * mb, null, null)
        now += 2_000
        notifier.progress(1, 1 * mb, null, null)
        now += 2_000
        notifier.progress(1, 3 * mb, null, null)
        assertEquals(listOf("begin 1 Download", "progress 1 ${1 * mb}/null", "progress 1 ${3 * mb}/null"), inner.heard)
    }

    @Test
    fun `the end of a transfer always goes through, however soon`() {
        notifier.begin(1, TransferNotifier.Kind.Download, "Film")
        notifier.progress(1, 1 * mb, 10 * mb, null)
        notifier.complete(1, "Downloaded", "Film", TransferNotifier.OpenTarget.DownloadsScreen)
        notifier.begin(2, TransferNotifier.Kind.Download, "Other")
        notifier.fail(2, "Stopped", "No space", retryable = true)
        notifier.begin(3, TransferNotifier.Kind.Download, "Third")
        notifier.cancel(3)
        assertEquals("complete 1 DownloadsScreen", inner.heard[2])
        assertEquals("fail 2 true", inner.heard[4])
        assertEquals("cancel 3", inner.heard[6])
    }

    @Test
    fun `the aggregate weighs each transfer by its bytes`() {
        assertNull(notifier.aggregate.value)
        notifier.begin(1, TransferNotifier.Kind.Download, "Big")
        notifier.begin(2, TransferNotifier.Kind.Download, "Small")
        notifier.progress(1, 0, 900, null)
        notifier.progress(2, 100, 100, null)
        // A finished small file and an untouched big one is a tenth done, not half.
        assertEquals(0.1f, notifier.aggregate.value!!, 0.0001f)
        notifier.complete(2, "Downloaded", "Small", null)
        assertEquals(0f, notifier.aggregate.value!!, 0.0001f)
        notifier.complete(1, "Downloaded", "Big", null)
        assertNull(notifier.aggregate.value)
    }

    @Test
    fun `the aggregate moves even when the notification is held back`() {
        notifier.begin(1, TransferNotifier.Kind.Download, "Film")
        notifier.progress(1, 10, 100, null)
        notifier.progress(1, 50, 100, null)
        assertEquals(0.5f, notifier.aggregate.value!!, 0.0001f)
        assertEquals(2, inner.heard.size)
    }

    @Test
    fun `byte weighting ignores transfers with no size and clamps overshoot`() {
        assertNull(CoalescingTransferNotifier.byteWeighted(emptyList()))
        assertEquals(1f, CoalescingTransferNotifier.byteWeighted(listOf(150L to 100L))!!, 0.0001f)
    }

    @Test
    fun `capabilities are the wrapped notifier's`() {
        assertEquals(setOf(TransferNotifier.Capability.ProgressBar), notifier.capabilities)
        assertEquals(emptySet<TransferNotifier.Capability>(), NoTransferNotifier.capabilities)
    }
}
