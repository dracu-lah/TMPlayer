// Full screen: the player's own box where the browser has it; the iPhone's video full screen otherwise
// (none for movi's canvas, which takes the iPhone's away). The app's player always fills the
// screen and opens sideways (ScreenOrientation.DEFAULT), so on a phone the row's button does both:
// full screen, then the turn locked sideways for a wide video, where the browser can lock it.
import { createSignal, onCleanup } from 'solid-js'
import { t } from '../../i18n'
import type { Ctl } from './types'

type WebkitFull = HTMLVideoElement & { webkitEnterFullscreen?: () => void }
type Orientation = ScreenOrientation & { lock?: (o: string) => Promise<void>; unlock?: () => void }

export function useFullscreen(c: Ctl, phone: () => boolean, flash: (text: string) => void) {
    const v = c.video
    const orientation = (screen as Screen & { orientation?: Orientation }).orientation
    const iosFullscreen = () => typeof (v as WebkitFull).webkitEnterFullscreen === 'function'
    const canFullscreen = () => !!document.fullscreenEnabled || iosFullscreen()
    const canTurn = () => !!orientation?.lock && !!document.fullscreenEnabled
    const [fullscreen, setFullscreen] = createSignal(false)
    const onChange = () => {
        setFullscreen(!!document.fullscreenElement)
        // Leaving full screen (the button, Esc, the system's back gesture) hands the turn back to the phone.
        if (!document.fullscreenElement) {
            try {
                orientation?.unlock?.()
            } catch {}
        }
    }
    document.addEventListener('fullscreenchange', onChange)
    onCleanup(() => document.removeEventListener('fullscreenchange', onChange))
    const toggle = async () => {
        if (document.fullscreenElement) return void document.exitFullscreen().catch(() => {})
        if (document.fullscreenEnabled && c.root.requestFullscreen) {
            try {
                await c.root.requestFullscreen({ navigationUI: 'hide' })
            } catch {
                return
            }
            // An upright video (or one not measured yet) is left to the phone; a refused lock changes nothing.
            if (phone() && canTurn() && v.videoWidth > v.videoHeight) await orientation!.lock!('landscape').catch(() => {})
            return
        }
        try {
            ;(v as WebkitFull).webkitEnterFullscreen?.()
        } catch {}
    }
    // Rotate (the phone's More menu): flips the locked turn, entering full screen first, where the browser can lock it.
    const rotate = async () => {
        try {
            if (!document.fullscreenElement) await c.root.requestFullscreen({ navigationUI: 'hide' })
            const upright = orientation!.type.startsWith('portrait')
            await orientation!.lock!(upright ? 'landscape' : 'portrait')
            flash(upright ? t('player.rotation_landscape') : t('player.rotation_portrait'))
        } catch {}
    }
    return { fullscreen, canFullscreen, canTurn, toggle, rotate }
}
