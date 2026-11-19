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
/** A read ends a whole 512 KiB chunk at a time, so a slow line shows no byte for as long as a chunk needs: the wait grows with what is asked, at this speed. */
export const SLOW_FLOOR_BYTES_PER_S = 25_000
/** The most that is added for the chunks asked of Telegram. */
export const MAX_EXTRA_MS = 65_000
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
    /** Bytes asked of Telegram and not yet in: 0 when the cause is known (a flood wait, no line), so the wait is not stretched. */
    pending?: number
}

/** How long a wait may go with no new bytes: STALL_MS, and the time the chunks asked for need at the slowest line worth waiting on. */
export function stallWindow(pending: number): number {
    return STALL_MS + Math.min(MAX_EXTRA_MS, Math.max(0, pending) / SLOW_FLOOR_BYTES_PER_S * 1000)
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
        if (now - this.bytesAt >= stallWindow(s.pending ?? 0) * scale) return 'stalled'
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
