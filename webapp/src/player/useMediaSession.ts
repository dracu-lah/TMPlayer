// The lock screen and the media keys: the video's name, the jumps, previous and next. A lock of the profile
// empties the session itself (accounts.ts stopMediaForLock); leaving the player clears what this set.
import { createEffect, onCleanup } from 'solid-js'
import type { VideoItem, WatchInfo } from '../tg/types'

export function useMediaSession(video: () => HTMLVideoElement, item: VideoItem, info: () => WatchInfo | null, play: (next: VideoItem | null) => void) {
    createEffect(() => {
        if (!('mediaSession' in navigator)) return
        const now = info()
        const ep = now?.episode
        const v = video()
        navigator.mediaSession.metadata = new MediaMetadata({ title: ep ? `${ep.code}${ep.name ? ` ${ep.name}` : ''}` : (now?.title ?? item.title), artist: ep?.show ?? item.chat })
        navigator.mediaSession.setActionHandler('seekbackward', () => (v.currentTime = Math.max(0, v.currentTime - 10)))
        navigator.mediaSession.setActionHandler('seekforward', () => (v.currentTime += 10))
        navigator.mediaSession.setActionHandler('seekto', (d) => d.seekTime != null && (v.currentTime = d.seekTime))
        navigator.mediaSession.setActionHandler('previoustrack', now?.previous ? () => play(now.previous) : null)
        navigator.mediaSession.setActionHandler('nexttrack', now?.next ? () => play(now.next) : null)
    })
    onCleanup(() => {
        if (!('mediaSession' in navigator)) return
        navigator.mediaSession.metadata = null
        for (const a of ['previoustrack', 'nexttrack', 'seekbackward', 'seekforward', 'seekto'] as const) navigator.mediaSession.setActionHandler(a, null)
    })
}
