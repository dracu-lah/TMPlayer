// Why a video is not starting (the apps' OpeningStall, core/pureMain/.../OpeningStall.kt, U1): TDLib
// and Telegram do not fail a read they cannot begin, they wait, so the player counts the bytes. The
// sheet comes after STALL_MS with no new bytes, never while a slow line keeps sending (every byte
// starts the count again): that shows its speed in the loader. Pure, so a test can drive the clock.
// The rules chunk is loaded by the Telegram worker only, so the numbers are kept here, not imported.

/** How long a wait may go with no new bytes before the player says Telegram has not started sending. */
export const STALL_MS = 10_000
/** A file that is all here cannot be Telegram's doing: the old watch of a clock that does not move, kept. */
export const DECODE_STALL_MS = 15_000
/** Bytes arriving but nothing playing for this long: the loader shows the speed, and does not blame Telegram. */
export const TRICKLE_MS = 5_000
/** A conversion on this device is slow by itself: its waits are three times as long. */
export const TRANSCODE_FACTOR = 3

export type Phase = 'ok' | 'trickle' | 'stalled'

export interface Sample {
    /** The video wants to play and cannot yet (nothing in it, or playing with too little buffered). */
    waiting: boolean
    /** The clock moved since the last sample. */
    moved: boolean
    /** Bytes of the file received so far. */
    received: number
    /** The whole file is on this device. */
    local: boolean
    /** The picture is converted here. */
    transcode: boolean
}

export class StallWatch {
    private bytes = 0
    private bytesAt = 0
    private movedAt = 0
    private startBytes = 0

    /** A new source, or the page back in view: the count starts again. */
    reset(now: number, received = 0) {
        this.bytes = received
        this.bytesAt = now
        this.movedAt = now
        this.startBytes = received
    }

    /** Bytes a second since the wait began, over the whole wait: reads end a chunk at a time, so a single second jumps about. */
    speed(now: number): number {
        const seconds = (now - this.movedAt) / 1000
        return seconds > 0 ? Math.max(0, this.bytes - this.startBytes) / seconds : 0
    }

    tick(now: number, s: Sample): Phase {
        if (!s.waiting || s.moved) {
            this.reset(now, s.received)
            return 'ok'
        }
        if (s.received > this.bytes) this.bytesAt = now
        this.bytes = s.received
        const scale = s.transcode ? TRANSCODE_FACTOR : 1
        if (s.local) return now - this.movedAt >= DECODE_STALL_MS * scale ? 'stalled' : 'ok'
        if (now - this.bytesAt >= STALL_MS * scale) return 'stalled'
        // Some bytes have come since the wait began (and the last one is under STALL_MS old): slow, not silent.
        return this.bytesAt > this.movedAt && now - this.movedAt >= TRICKLE_MS ? 'trickle' : 'ok'
    }
}

/** What the sheet says: Telegram asked to wait (with how long), no line to Telegram, or Telegram has not started. */
export type Why = 'flood' | 'noline' | 'slow'

/** `link` is the Telegram worker's connection state, null before it has said anything. */
export function stallWhy(floodSeconds: number, online: boolean, link: string | null): Why {
    if (floodSeconds > 0) return 'flood'
    if (online && link !== null && link !== 'connected' && link !== 'updating') return 'noline'
    return 'slow'
}

/** A flood wait in an error from a read: its seconds, or 0 when it is not one. */
export function floodSeconds(e: { message?: string; text?: string; seconds?: number } | null | undefined): number {
    const raw = `${e?.text ?? ''} ${e?.message ?? ''}`
    if (!/FLOOD_(?:PREMIUM_)?WAIT/.test(raw)) return 0
    const n = Number(e?.seconds ?? /FLOOD_(?:PREMIUM_)?WAIT_(\d+)/.exec(raw)?.[1])
    // Telegram's wait is a small number; a wrong type or an absurd one is ignored, a day at most is believed.
    return Number.isFinite(n) && n > 0 ? Math.min(Math.ceil(n), 86_400) : 0
}
