package com.tmplayer.data

/**
 * The setup after sign in, as one question (R3): "Which chats have your videos?", then Home.
 *
 * A sign in screen arms the setup ([SettingsStore.armSetup]); this says what the shell shows while
 * it is armed. There are no other pages: the device's lighter or full pictures are decided by
 * [DeviceFitRules] and applied silently, crash reports stay off (Settings asks), and the quick tour
 * is a Tips card on Home. Purely state driven, so every host (phone, television, desktop) asks the
 * same question and no state is kept anywhere but the settings store.
 */
object SetupFlow {

    /**
     * How long the chat list may be missing with no network before the offline card replaces the
     * spinner. Short enough to say so, long enough that the connecting moment after a sign in
     * does not flash it.
     */
    const val OFFLINE_AFTER_MS = 3_000L

    /** What the shell shows right now. */
    enum class Next {
        /** The setup is not armed: nothing to show. */
        Idle,

        /** Armed, but the stored answer has not been read yet: a plain spinner. */
        Hold,

        /** The chat list is on its way: the spinner with "Loading your chats" under it. */
        Wait,

        /** No chat list and no network: the offline card. It goes by itself when the list comes. */
        Offline,

        /** The question. */
        Choose,

        /** Nothing more to ask: mark the setup done and show Home. */
        Finish,
    }

    /** Everything [next] reads. */
    data class Inputs(
        /** [SettingsStore.setupPending]. */
        val pending: Boolean,
        /** The stored answer ([AddedChats.loaded]) has been read. */
        val answerLoaded: Boolean,
        val mode: AddedMode,
        /** Settings' "Run the setup again": ask even though an answer is stored. */
        val replay: Boolean = false,
        val kidsLocked: Boolean = false,
        /** "Continue without choosing" was pressed for this visit ([ChatsGate.waived]). */
        val waived: Boolean = false,
        /** The chat list has answered at all. */
        val chatsLoaded: Boolean = false,
        /** Chats in the list after the kids lock; Saved Messages counts. */
        val total: Int = 0,
        /** [Td.connected]. */
        val connected: Boolean = true,
        /** [OFFLINE_AFTER_MS] has passed since the list was first needed. */
        val offlineSure: Boolean = false,
    )

    /**
     * The setup's next move, checked in this order:
     *
     * 1. Not armed: [Next.Idle].
     * 2. The stored answer not read yet: [Next.Hold].
     * 3. An answer already stored ([AddedMode.Picked] or [AddedMode.All]), unless this is a replay:
     *    [Next.Finish]. A sign in on a device that already had a setup asks nothing again.
     * 4. A child's profile ([Inputs.kidsLocked]) or Continue without choosing ([Inputs.waived]):
     *    [Next.Finish], nothing written, so the chat choice asks again at the next start.
     * 5. At least one chat ([Inputs.total], Saved Messages included): [Next.Choose]. Unlike the
     *    chat choice that follows an update ([ChatsGate.MIN_CHATS]), even one chat is asked about,
     *    so a new account with only Saved Messages is told to forward a video there.
     * 6. No chats and no network after [OFFLINE_AFTER_MS]: [Next.Offline].
     * 7. Anything else: [Next.Wait]. It never ends by itself without a choice.
     */
    fun next(i: Inputs): Next = when {
        !i.pending -> Next.Idle
        !i.answerLoaded -> Next.Hold
        !i.replay && i.mode != AddedMode.Unanswered -> Next.Finish
        i.kidsLocked -> Next.Finish
        i.waived -> Next.Finish
        i.chatsLoaded && i.total >= 1 -> Next.Choose
        !i.connected && i.offlineSure -> Next.Offline
        else -> Next.Wait
    }

    /**
     * Whether a shell that sees [next] covers the whole app: every move but [Next.Idle]. The
     * shell's notices, update popup and tips wait while it does.
     */
    fun up(next: Next): Boolean = next != Next.Idle

    /**
     * [next] as the chat choice's own move, so the picker's run bookkeeping ([ChatsGate.advance]) and
     * the shell's "something covers the app" checks treat the setup like the gate: the question is
     * [ChatsGate.Move.Show], every wait [ChatsGate.Move.Wait], and Finish or Idle [ChatsGate.Move.None].
     */
    fun toMove(next: Next): ChatsGate.Move = when (next) {
        Next.Choose -> ChatsGate.Move.Show
        Next.Hold, Next.Wait, Next.Offline -> ChatsGate.Move.Wait
        Next.Idle, Next.Finish -> ChatsGate.Move.None
    }

    /**
     * Whether the question is asked in the strict form (no Back button, Done only with a chat
     * ticked, Back leaves the app). A replay from Settings is the ordinary picker instead.
     */
    fun required(replay: Boolean): Boolean = !replay
}
