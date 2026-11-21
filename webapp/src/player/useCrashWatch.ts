// A failure nobody caught while the video is open comes here, not to the page's card (HX19). On the
// Mi TV one came while a 3D video opened and the video then played on fine, so the player waits:
// if the picture moves on within a few seconds it was not the video's, and only the log keeps it;
// if not, the status sheet says so, with Reload (this video again, where it was) and Go back.
import { onCleanup } from 'solid-js'
import { routeCrashes } from '../crash'
import { CRASH_GRACE_MS } from './playerParts'

export function useCrashWatch(video: () => HTMLVideoElement, state: { closed: () => boolean; error: () => string | null; opened: () => boolean }, say: () => void) {
    let check: ReturnType<typeof setTimeout> | undefined
    const unroute = routeCrashes(() => {
        if (check !== undefined || state.closed()) return
        const v = video()
        // Moved on: the picture's clock ticked forward (a looping or seeking video can come back below
        // where it was, so the ticks are counted rather than the times compared).
        let last = v.currentTime
        let moved = 0
        const tick = () => {
            if (v.currentTime !== last && !v.paused) moved++
            last = v.currentTime
        }
        v.addEventListener('timeupdate', tick)
        check = setTimeout(() => {
            check = undefined
            v.removeEventListener('timeupdate', tick)
            // Paused by the viewer after it played: still is not stuck.
            if (state.closed() || state.error() || moved >= 2 || (v.paused && state.opened())) return
            say()
        }, CRASH_GRACE_MS)
    })
    onCleanup(() => {
        unroute()
        clearTimeout(check)
    })
}
