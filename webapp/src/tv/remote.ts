// What a remote's key means to the player (plan 8.5, session 21): the arrows, OK, Back and the
// media keys, by `key` and by the codes the TV makers send (webOS, Tizen, Android TV's browser,
// HbbTV). Anything else is a key the page may use itself (a digit, a letter on a USB keyboard).
export type RemoteKey = 'up' | 'down' | 'left' | 'right' | 'ok' | 'back' | 'play' | 'pause' | 'playpause' | 'stop' | 'forward' | 'rewind' | 'next' | 'previous'

const BY_KEY: Record<string, RemoteKey> = {
    ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', Up: 'up', Down: 'down', Left: 'left', Right: 'right',
    Enter: 'ok', Select: 'ok', Accept: 'ok',
    Escape: 'back', Backspace: 'back', GoBack: 'back', BrowserBack: 'back', XF86Back: 'back', Back: 'back',
    MediaPlay: 'play', MediaPause: 'pause', Pause: 'pause', MediaPlayPause: 'playpause', MediaStop: 'stop',
    MediaFastForward: 'forward', MediaRewind: 'rewind', MediaTrackNext: 'next', MediaTrackPrevious: 'previous',
}

/** Play 415, Pause 19, Stop 413, Rewind 412, Fast forward 417 and Back 461 (webOS); 10252 play/pause and 10009 Back (Tizen). */
const BY_CODE: Record<number, RemoteKey> = {
    38: 'up', 40: 'down', 37: 'left', 39: 'right', 13: 'ok',
    27: 'back', 8: 'back', 461: 'back', 10009: 'back', 166: 'back',
    415: 'play', 19: 'pause', 413: 'stop', 412: 'rewind', 417: 'forward', 10252: 'playpause', 179: 'playpause', 178: 'stop',
}

/** The key's meaning, or null for one the remote does not use (a digit, a letter). */
export function remoteKey(e: KeyboardEvent): RemoteKey | null {
    const named = BY_KEY[e.key]
    if (named) return named
    // A TV that sends no name (or "Unidentified") is known by its code alone.
    if (!e.key || e.key === 'Unidentified') return BY_CODE[e.keyCode || e.which] ?? null
    return null
}
