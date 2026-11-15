package com.tmplayer.data

import com.tmplayer.data.ContinueWidget.Account
import com.tmplayer.data.ContinueWidget.Size
import com.tmplayer.data.ContinueWidget.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The widget's choice of tile, by state: a table per rule, no phone needed. */
class ContinueWidgetTest {

    private val duration = 2_640_000L

    private fun record(
        message: Long,
        position: Long = 1_000_000,
        durationMs: Long = duration,
        updatedAt: Long = message,
        chat: Long = -100L,
        file: Int = message.toInt(),
        title: String = "Show S01E0$message",
    ) = ResumeRecord(
        chatId = chat, messageId = message, fileId = file, title = title, chatTitle = "Show",
        sizeBytes = 1_000_000, durationSec = (durationMs / 1_000).toInt(), positionMs = position,
        durationMs = durationMs, updatedAt = updatedAt,
    )

    private fun decide(
        records: List<ResumeRecord>,
        account: Account = Account.SignedIn,
        locked: Boolean = false,
        unavailable: (ResumeRecord) -> Boolean = { false },
    ) = ContinueWidget.decide(account, locked, records, unavailable, titleOf = { it.title })

    private fun Tile.messageId() = (this as Tile.Video).video.messageId

    @Test
    fun `the newest unfinished video wins`() {
        val tile = decide(listOf(record(1, updatedAt = 10), record(2, updatedAt = 30), record(3, updatedAt = 20)))
        assertEquals(2L, tile.messageId())
    }

    @Test
    fun `the tile carries what a tap needs and the progress`() {
        val tile = decide(listOf(record(4, position = 1_320_000))) as Tile.Video
        assertEquals(WatchNext.Video(-100L, 4L, 4, "Show S01E04", "Show", 1_000_000, 2_640), tile.video)
        assertEquals("Show S01E04", tile.title)
        assertEquals(50, tile.percent)
        assertEquals(1_320L, tile.remainingSec)
    }

    @Test
    fun `no records is the friendly empty tile`() {
        assertEquals(Tile.Nothing, decide(emptyList()))
        assertEquals(Tile.Nothing, decide(emptyList(), account = Account.Unknown))
    }

    @Test
    fun `signed out is a sign in tile, still connecting is not`() {
        assertEquals(Tile.SignIn, decide(emptyList(), account = Account.SignedOut))
        assertEquals(Tile.SignIn, decide(listOf(record(1)), account = Account.SignedOut))
        assertEquals(1L, decide(listOf(record(1)), account = Account.Unknown).messageId())
    }

    @Test
    fun `a lock hides everything and wins over the account`() {
        assertEquals(Tile.Locked, decide(listOf(record(1)), locked = true))
        assertEquals(Tile.Locked, decide(emptyList(), locked = true))
        assertEquals(Tile.Locked, decide(listOf(record(1)), account = Account.SignedOut, locked = true))
    }

    @Test
    fun `a video gone from Telegram is skipped to the next`() {
        val records = listOf(record(1, updatedAt = 10), record(2, updatedAt = 30), record(3, updatedAt = 20))
        assertEquals(3L, decide(records) { it.messageId == 2L }.messageId())
        assertEquals(Tile.Nothing, decide(records) { true })
    }

    @Test
    fun `finished, glanced at and unopenable lines are skipped`() {
        val finished = record(1, position = 2_500_000, updatedAt = 50)
        val credits = record(2, position = 2_450_000, updatedAt = 40)
        val glance = record(3, position = 60_000, updatedAt = 30)
        val noFile = record(4, file = 0, updatedAt = 20)
        val noChat = record(5, chat = 0, updatedAt = 15)
        val good = record(6, updatedAt = 10)
        assertEquals(6L, decide(listOf(finished, credits, glance, noFile, noChat, good)).messageId())
        assertEquals(Tile.Nothing, decide(listOf(finished, credits, glance, noFile, noChat)))
    }

    @Test
    fun `a stream with no length yet still counts and shows no time left`() {
        val tile = decide(listOf(record(1, position = 400_000, durationMs = 0))) as Tile.Video
        assertEquals(0, tile.percent)
        assertEquals(0L, tile.remainingSec)
    }

    @Test
    fun `copies of one video are one tile, the latest copy`() {
        val hd = record(1, updatedAt = 10, title = "Coast S01E04 1080p")
        val sd = record(2, updatedAt = 20, title = "Coast S01E04 720p")
        val tile = decide(listOf(hd, sd), unavailable = { it.messageId == 2L })
        // The newest copy is gone: the older copy of the same episode is the one still there.
        assertEquals(1L, tile.messageId())
    }

    @Test
    fun `a blank name falls back to the stored title`() {
        val tile = ContinueWidget.decide(Account.SignedIn, false, listOf(record(1)), titleOf = { " " }) as Tile.Video
        assertEquals("Show S01E01", tile.title)
    }

    @Test
    fun `only Telegram asking for a login counts as signed out`() {
        assertEquals(Account.SignedIn, ContinueWidget.accountOf(AuthState.Ready))
        assertEquals(Account.Unknown, ContinueWidget.accountOf(AuthState.Connecting))
        assertEquals(Account.Unknown, ContinueWidget.accountOf(AuthState.Failed("offline")))
        assertEquals(Account.SignedOut, ContinueWidget.accountOf(AuthState.ChooseMethod))
        assertEquals(Account.SignedOut, ContinueWidget.accountOf(AuthState.Qr("tg://login")))
        assertEquals(Account.SignedOut, ContinueWidget.accountOf(AuthState.Phone()))
    }

    @Test
    fun `either lock hides the widget, a PIN alone does not`() {
        assertEquals(false, ContinueWidget.lockedBy(AppLockSettings()))
        assertEquals(false, ContinueWidget.lockedBy(AppLockSettings(pinSet = true)))
        assertEquals(true, ContinueWidget.lockedBy(AppLockSettings(pinSet = true, onOpen = true)))
        assertEquals(true, ContinueWidget.lockedBy(AppLockSettings(pinSet = true, kids = true)))
    }

    @Test
    fun `size classes by launcher measure`() {
        // width, height -> size. A 2x1 strip, a 2x2, a 4x2, a 4x4, and a launcher that says nothing.
        val table = listOf(
            Triple(110, 40, Size.Compact),
            Triple(110, 110, Size.Compact),
            Triple(160, 160, Size.Compact),
            Triple(250, 56, Size.Compact),
            Triple(250, 99, Size.Compact),
            Triple(250, 100, Size.Regular),
            Triple(170, 110, Size.Regular),
            Triple(250, 110, Size.Regular),
            Triple(320, 250, Size.Regular),
            Triple(0, 0, Size.Regular),
            Triple(180, 0, Size.Regular),
            Triple(0, 180, Size.Regular),
        )
        for ((w, h, expected) in table) assertEquals("$w x $h", expected, ContinueWidget.sizeFor(w, h))
    }

    @Test
    fun `the redraw gaps are what the cost note says`() {
        assertEquals(60_000L, ContinueWidget.MIN_GAP_MS)
        assertTrue(ContinueWidget.SYSTEM_PERIOD_MS >= 30L * 60_000L)
    }
}
