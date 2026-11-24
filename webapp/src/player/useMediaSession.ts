// The lock screen and the media keys: the video's name and picture, where it is and how fast, the jumps, stop, previous and next.
// A lock of the profile empties the session itself (accounts.ts stopMediaForLock); leaving the player clears what this set.
import { createEffect, onCleanup } from 'solid-js'
import { loadThumb } from '../screens/thumbs'
import type { VideoItem, WatchInfo } from '../tg/types'
import { artworkFor, jumpedTo, MEDIA_JUMP_SECONDS, sendPositionState } from './mediaSessionState'

export function useMediaSession(video: () => HTMLVideoElement, item: VideoItem, info: () => WatchInfo | null, play: (next: VideoItem | null) => void, stop: () => void = () => {}) {
    createEffect(() => {
        if (!('mediaSession' in navigator)) return
        const now = info()
        const ep = now?.episode
        const v = video()
        const session = navigator.mediaSession
        const title = ep ? `${ep.code}${ep.name ? ` ${ep.name}` : ''}` : (now?.title ?? item.title)
        const set = (artwork: MediaImage[]) => {
            session.metadata = new MediaMetadata({ title, artist: ep?.show ?? item.chat, artwork })
        }
        // Under "restrict saving content" the OS gets the words only, never the picture.
        set([])
        let gone = false
        if (item.thumb && item.canBeSaved)
            loadThumb(item.thumb).then(
                (url) => !gone && set(artworkFor(url, item.canBeSaved)),
                () => {},
            )
        // seekBy: the OS's own step when it sends one, else ten seconds.
        const seekBy = (d: MediaSessionActionDetails, sign: 1 | -1) => (v.currentTime = jumpedTo(v.currentTime, sign * (d.seekOffset ?? MEDIA_JUMP_SECONDS), v.duration))
        session.setActionHandler('seekbackward', (d) => seekBy(d, -1))
        session.setActionHandler('seekforward', (d) => seekBy(d, 1))
        session.setActionHandler('seekto', (d) => d.seekTime != null && (v.currentTime = jumpedTo(d.seekTime, 0, v.duration)))
        session.setActionHandler('stop', stop)
        session.setActionHandler('previoustrack', now?.previous ? () => play(now.previous) : null)
        session.setActionHandler('nexttrack', now?.next ? () => play(now.next) : null)

        // Where it is and how fast: about every second, and at once when the speed, a seek or the length changes.
        const send = () => sendPositionState(session, v)
        let last = 0
        const tick = () => {
            const at = performance.now()
            if (at - last < 1000) return
            last = at
            send()
        }
        v.addEventListener('timeupdate', tick)
        for (const e of ['ratechange', 'seeked', 'durationchange', 'loadedmetadata', 'play', 'pause'] as const) v.addEventListener(e, send)
        send()
        onCleanup(() => {
            gone = true
            v.removeEventListener('timeupdate', tick)
            for (const e of ['ratechange', 'seeked', 'durationchange', 'loadedmetadata', 'play', 'pause'] as const) v.removeEventListener(e, send)
        })
    })
    onCleanup(() => {
        if (!('mediaSession' in navigator)) return
        navigator.mediaSession.metadata = null
        try {
            navigator.mediaSession.setPositionState()
        } catch {}
        for (const a of ['previoustrack', 'nexttrack', 'seekbackward', 'seekforward', 'seekto', 'stop'] as const) navigator.mediaSession.setActionHandler(a, null)
    })
}
