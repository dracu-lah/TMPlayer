// MSone search stays hidden until it is ready (plan CP33): `?msone=1` in the address turns it on
// for this device and `?msone=0` off, with a toast when that changes it. Without it nothing about MSone
// shows anywhere: not the player's subtitle menu, not Settings. Also the words for its states,
// shared by the player's sheet and Settings.
import { createSignal } from 'solid-js'
import { showToast } from './ui/Toast'
import { t } from './i18n'
import type { State } from './player/msone'

const KEY = 'tmplayer-msone'

function stored(): boolean {
    try {
        return localStorage.getItem(KEY) === '1'
    } catch {
        return false
    }
}

const [msoneOn, setMsoneOn] = createSignal(stored())
export { msoneOn }

/** Reads and drops `?msone=` from the address, before the router starts. Without storage it holds for this page only. */
export function takeMsoneParam() {
    let url: URL
    try {
        url = new URL(location.href)
    } catch {
        return
    }
    const asked = url.searchParams.get('msone')
    if (asked !== '1' && asked !== '0') return
    const on = asked === '1'
    // Said only when it changes: the TV app opens every launch with `?msone=1`, and a toast on
    // Home each time over a setting that was already on reads as noise.
    const changed = on !== msoneOn()
    try {
        if (on) localStorage.setItem(KEY, '1')
        else localStorage.removeItem(KEY)
    } catch {}
    setMsoneOn(on)
    url.searchParams.delete('msone')
    try {
        history.replaceState(history.state, '', url.pathname + url.search + url.hash)
    } catch {}
    if (changed) showToast({ text: t(on ? 'msone.hidden_on' : 'msone.hidden_off') })
}

/** The line for a state: on, off, paused, stopped, or not known. */
export const stateLine = (state: State) => t(`msone.state_${state}`)
