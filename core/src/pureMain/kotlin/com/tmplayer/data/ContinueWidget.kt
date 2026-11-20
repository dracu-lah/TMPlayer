package com.tmplayer.data

/**
 * What the phone's "Continue watching" home screen widget shows, decided away from the platform.
 *
 * One question, [decide]: given the account, the locks and the viewer's own resume points
 * ([SettingsBook.continueWatching], the list Home's Continue watching row reads, already without
 * the chats the kids lock hides), which tile is drawn. The rules for what counts as unfinished are
 * [ResumeRules] and the folding of copies is [HomeRows.foldCopies], the same as Home's row and the
 * TV's "Play next" ([WatchNext]); nothing here is a second copy of them. The Android side
 * (`com.tmplayer.widget`) only draws the answer and opens [Tile.Video.video] from a tap.
 */
object ContinueWidget {

    /** Whether somebody is signed in, as far as this process can tell yet. */
    enum class Account {
        SignedIn,

        /** Telegram is asking for a login: the app was never signed in, or signed out. */
        SignedOut,

        /** Still connecting, or Telegram unreachable: not a reason to say "sign in". */
        Unknown,
    }

    /** The two layouts: a strip with no picture, and anything bigger with the poster. */
    enum class Size { Compact, Regular }

    sealed interface Tile {
        /** Signed out: a tile that opens the app on its sign in. */
        data object SignIn : Tile

        /** The app lock or the kids lock is on: no title, no picture, a tap opens the app (and so its lock). */
        data object Locked : Tile

        /** Nothing part watched: a friendly tile that opens the app. */
        data object Nothing : Tile

        /**
         * The most recent unfinished video. [percent] is whole per cent watched, [remainingSec]
         * what is left (0 when the length is unknown), and [video] what a tap opens, at the resume
         * point the player already keeps.
         */
        data class Video(
            val video: WatchNext.Video,
            val title: String,
            val percent: Int,
            val remainingSec: Long,
        ) : Tile
    }

    /** Fewest milliseconds between two redraws driven by playback progress. */
    const val MIN_GAP_MS = 60_000L

    /** The system's own refresh, in milliseconds: Android never goes below 30 minutes anyway. */
    const val SYSTEM_PERIOD_MS = 30L * 60_000L

    /** Below this height (dp) the widget is the strip: a 2x1 cell is about 110 x 40 dp to 110 x 110 dp. */
    private const val COMPACT_BELOW_DP = 100

    /** Below this width (dp) the picture is left out whatever the height: the title would have under 80 dp. */
    private const val NARROW_BELOW_DP = 170

    /** [Account] from where the sign in is: only Telegram asking for a login counts as signed out. */
    fun accountOf(auth: AuthState): Account = when (auth) {
        AuthState.Ready -> Account.SignedIn
        AuthState.Connecting, is AuthState.Failed -> Account.Unknown
        else -> Account.SignedOut
    }

    /** Whether either lock hides what the widget would show. */
    fun lockedBy(lock: AppLockSettings): Boolean = lock.onOpen || lock.kids

    /**
     * [lockedBy], and the open profile's own PIN (HX88): [profileLocked] while it has one that was
     * not given in this process, so a PIN locked profile's titles and posters never show.
     */
    fun lockedBy(lock: AppLockSettings, profileLocked: Boolean): Boolean = profileLocked || lockedBy(lock)

    /** The layout for a widget [widthDp] by [heightDp] (0 when the launcher did not say: the roomy one). */
    fun sizeFor(widthDp: Int, heightDp: Int): Size =
        if (heightDp in 1 until COMPACT_BELOW_DP || widthDp in 1 until NARROW_BELOW_DP) Size.Compact else Size.Regular

    /**
     * The tile for the state. [records] are the viewer's resume points (sorted here, newest
     * first); [unavailable] says a video is known gone from Telegram and not downloaded, so the
     * next one is shown instead. [titleOf] is how Home names a card.
     */
    fun decide(
        account: Account,
        locked: Boolean,
        records: List<ResumeRecord>,
        unavailable: (ResumeRecord) -> Boolean = { false },
        titleOf: (ResumeRecord) -> String = { SeriesShelf.tileTitle(it.toMediaItem()) },
    ): Tile {
        if (locked) return Tile.Locked
        if (account == Account.SignedOut) return Tile.SignIn
        // Gone ones leave before the copies fold, so a copy still there is not hidden behind a gone one.
        val next = HomeRows.foldCopies(records.filter { playable(it) && !unavailable(it) }.sortedByDescending { it.updatedAt })
            .firstOrNull()
            ?: return Tile.Nothing
        val video = WatchNext.Video(
            chatId = next.chatId,
            messageId = next.messageId,
            fileId = next.fileId,
            title = next.title,
            chatTitle = next.chatTitle,
            sizeBytes = next.sizeBytes,
            durationSec = next.durationSec,
        )
        return Tile.Video(
            video = video,
            title = titleOf(next).ifBlank { next.title },
            percent = (next.fraction * 100).toInt().coerceIn(0, 100),
            remainingSec = (next.remainingMs / 1_000).coerceAtLeast(0L),
        )
    }

    /**
     * Whether a record can be opened from a tap (the ids the launch intent needs, as
     * `WatchNextPublisher.videoFrom` reads them) and is still part way through by [ResumeRules].
     */
    private fun playable(record: ResumeRecord): Boolean {
        if (record.chatId == 0L || record.messageId <= 0L || record.fileId <= 0) return false
        if (ResumeRules.watched(record.positionMs, record.durationMs)) return false
        return ResumeRules.keeps(record.positionMs, record.durationMs)
    }
}
