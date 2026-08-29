package com.tmplayer.player

/**
 * The sleep timer's steps, and the words the button answers with.
 *
 * A fixed cycle rather than a dialog, because the button lives on the transport row where one
 * press has to mean one thing to a D-pad and a thumb alike: press until the figure suits, and
 * off is a step like any other, so disarming the timer is the same gesture as setting it. The
 * figures are the ones people actually fall asleep inside; anything finer is a settings screen.
 *
 * Pure, so the cycle is tested rather than discovered at midnight.
 */
enum class SleepTimer(val minutes: Int) {
    Off(0),
    Min15(15),
    Min30(30),
    Min45(45),
    Min60(60),
    Min90(90),
    ;

    /** The next step along, wrapping back to off past the longest. */
    fun next(): SleepTimer = entries[(ordinal + 1) % entries.size]

    /** What the press flashes over the picture: the state it just chose, as a sentence. */
    val label: String
        get() = if (this == Off) "Sleep timer off" else "Sleep in $minutes min"

    /**
     * When this step runs out, measured from [nowElapsedMs], or null for off.
     *
     * The caller feeds it elapsed realtime rather than the wall clock, so a timezone change or
     * an NTP correction in the small hours can neither fire the timer early nor never.
     */
    fun deadlineFrom(nowElapsedMs: Long): Long? =
        if (this == Off) null else nowElapsedMs + minutes * 60_000L
}
