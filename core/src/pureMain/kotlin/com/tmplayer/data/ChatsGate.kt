package com.tmplayer.data

/**
 * Whether the app must ask which chats to show before anything else (the mandatory chat choice).
 *
 * TMPlayer shows videos only from the chats the viewer chose ([AddedChats]). An account that has
 * never answered ([AddedMode.Unanswered]: a fresh sign in, or an install from before the picker)
 * is asked once, by the picker in its required mode, on every platform and independent of the
 * setup after sign in. An account that already answered, [AddedMode.All] included (a choice made
 * on purpose before this was mandatory), is never asked again: nothing is migrated.
 *
 * Purely state driven: the answer follows the stored choice and the chat list, so the gate shows
 * as soon as the list arrives, goes by itself once Done writes [AddedMode.Picked], and nothing
 * here retries or loops on its own.
 */
object ChatsGate {

    /**
     * Below this many chats there is nothing worth choosing between (a fresh account with Saved
     * Messages and a couple of chats), so the picker is not shown and the account is left
     * unanswered. Nothing is written for it: a chat joined later is never hidden behind an answer
     * nobody gave, and the gate asks once the account reaches this many. The web's `STEP_MIN`
     * mirrors this value.
     */
    const val MIN_CHATS = 4

    /** What the shell does about the chat choice right now. */
    enum class Move {
        /** Nothing: already answered, waiting on something else, or too few chats to ask about. */
        None,

        /**
         * Unanswered, but the chat list is not in yet: a short wait, bounded by the host's timer
         * (the setup's own `WAIT_FOR_CHATS_MS`, eight seconds).
         */
        Wait,

        /** Show the picker in its required mode, in place of the app. */
        Show,
    }

    /**
     * The gate's next move, checked in this order:
     *
     * 1. Any answer ([AddedMode.All], or [AddedMode.Picked], an empty pick included) means [Move.None].
     * 2. [setupPending]: the setup after sign in runs first, so the two never stack.
     * 3. [kidsLocked]: a child's profile is not asked; the gate comes back once the lock is off.
     * 4. The chat list not loaded yet and not [waitedOut]: [Move.Wait].
     * 5. At least [MIN_CHATS] chats ([total], after the kids lock): [Move.Show].
     * 6. Anything else (too few chats, waited out, offline) is [Move.None], so Home with its
     *    downloads and Continue watching stays usable, and the gate shows once the list comes.
     *
     * [listComplete] is not needed for either answer: enough chats already in is enough to ask,
     * and a short list that is still arriving is not waited on (rule 6), so a slow or offline
     * account is never held behind a spinner.
     */
    @Suppress("UNUSED_PARAMETER")
    fun decide(
        mode: AddedMode,
        setupPending: Boolean,
        kidsLocked: Boolean,
        chatsLoaded: Boolean,
        listComplete: Boolean,
        total: Int,
        waitedOut: Boolean,
    ): Move = when {
        mode != AddedMode.Unanswered -> Move.None
        setupPending -> Move.None
        kidsLocked -> Move.None
        !chatsLoaded && !waitedOut -> Move.Wait
        total >= MIN_CHATS -> Move.Show
        else -> Move.None
    }

    /** Whether [move] takes the app's place: the shell's notices and offers wait while it does. */
    fun up(move: Move): Boolean = move != Move.None
}
