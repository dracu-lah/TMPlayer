// What the OS's media controls are told about the video (Chrome's media hub, the lock screen, a headset): where it is,
// how fast it moves, and its picture. Pure, so a stub of `navigator.mediaSession` can test it.

/** The part of an HTMLVideoElement the position state reads. */
export interface PositionSource {
    duration: number
    currentTime: number
    playbackRate: number
}

/** `setPositionState`'s argument. */
export interface PositionState {
    duration: number
    playbackRate: number
    position: number
}

/**
 * The state the Media Session API accepts, or null when it would throw: it refuses an unknown or infinite length (a live
 * stream, a file not yet loaded), a position outside the length and a rate of zero. The position is held inside the length
 * and a speed that is not a usable number is 1.
 */
export function positionState(v: PositionSource): PositionState | null {
    const duration = v.duration
    if (!Number.isFinite(duration) || duration <= 0) return null
    const rate = Number.isFinite(v.playbackRate) && v.playbackRate !== 0 ? v.playbackRate : 1
    const position = Number.isFinite(v.currentTime) ? Math.min(Math.max(0, v.currentTime), duration) : 0
    return { duration, playbackRate: rate, position }
}

/** Sends the position to [session]; a browser that refuses it (or has no `setPositionState`) is left alone. */
export function sendPositionState(session: Pick<MediaSession, 'setPositionState'> | undefined, v: PositionSource): void {
    if (!session || typeof session.setPositionState !== 'function') return
    const state = positionState(v)
    try {
        session.setPositionState(state ?? undefined)
    } catch {
        // A state this browser refuses is not the player's to fail on.
    }
}

/**
 * The picture for the OS widget: the video's thumbnail, and nothing where the chat restricts saving content (the picture
 * would leave the page for the OS's own surfaces, as a saved frame would). The size is the thumbnail's own, unknown here.
 */
export function artworkFor(url: string | null | undefined, canBeSaved: boolean): MediaImage[] {
    if (!url || !canBeSaved) return []
    return [{ src: url }]
}

/** The jump of the lock screen's and headset's "back" and "forward", in seconds, or the one the OS asks for. */
export const MEDIA_JUMP_SECONDS = 10

/** [from] moved by [seconds], held inside 0 and the length where it is known. */
export function jumpedTo(from: number, seconds: number, duration: number): number {
    const to = (Number.isFinite(from) ? from : 0) + (Number.isFinite(seconds) ? seconds : 0)
    const top = Number.isFinite(duration) && duration > 0 ? duration : Infinity
    return Math.min(Math.max(0, to), top)
}
