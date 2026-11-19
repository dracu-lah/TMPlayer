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
