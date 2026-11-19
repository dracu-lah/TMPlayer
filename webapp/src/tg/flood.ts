// Telegram's flood wait in an error from a file read (the player's "why", player/openStall.ts). Kept apart
// from the player's watch: this is read in the page's first load, the watch only with the player.

/** A flood wait in an error from a read: its seconds, or 0 when it is not one. */
export function floodSeconds(e: { message?: string; text?: string; seconds?: number } | null | undefined): number {
    const raw = `${e?.text ?? ''} ${e?.message ?? ''}`
    if (!/FLOOD_(?:PREMIUM_)?WAIT/.test(raw)) return 0
    const n = Number(e?.seconds ?? /FLOOD_(?:PREMIUM_)?WAIT_(\d+)/.exec(raw)?.[1])
    // Telegram's wait is a small number; a wrong type or an absurd one is ignored, a day at most is believed.
    return Number.isFinite(n) && n > 0 ? Math.min(Math.ceil(n), 86_400) : 0
}

/**
 * The flood wait Telegram last asked a file read for, as time left. One for the page: the reads
 * wait it out themselves (stream/reader.ts); the player only says how long. `clear` is for a new
 * video, so a wait that belonged to the last one is not told for this one.
 */
export function createFloodClock(now: () => number = () => performance.now()) {
    let until = 0
    return {
        note(seconds: number) {
            if (seconds > 0) until = Math.max(until, now() + seconds * 1000)
        },
        /** Whole seconds left, 0 when none. */
        left: () => Math.max(0, Math.ceil((until - now()) / 1000)),
        clear() {
            until = 0
        },
    }
}
