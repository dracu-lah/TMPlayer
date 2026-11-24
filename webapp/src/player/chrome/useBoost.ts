// Volume boost, Night mode and Even out volume (boost.ts): the sound above the file's own level, its loud scenes held
// back, and its quiet tracks lifted, each kept for every video until turned off. Not on an iPhone or iPad, where the
// page cannot carry them, so the entries are left out there. A canvas engine has the boost only (extrasHere).
import { createSignal, onCleanup } from 'solid-js'
import { t } from '../../i18n'
import { onGlobal } from '../../ui/globalInput'
import { readBoost, readEven, readNight, writeBoost, writeEven, writeNight } from '../../store'
import { applyAudio, boostIsIos, boostSupported, extrasHere, releaseBoost } from '../boost'

export function useBoost(v: HTMLVideoElement, listen: (name: 'playing', fn: () => void) => void, flash: (text: string) => void) {
    const ok = boostSupported()
    const ios = boostIsIos()
    const [boost, setBoost] = createSignal(1)
    const [night, setNight] = createSignal(false)
    const [even, setEven] = createSignal(false)
    let picked = false
    let touched = false
    // A level that could not start yet (no tap has allowed audio) starts on the next one.
    let retry: (() => void) | null = null
    let offs: (() => void)[] = []
    let gone = false
    const anyOn = () => boost() > 1 || night() || even()
    // Says the whole sound again: the level, Night mode and Even out volume as they stand now.
    const start = () => {
        const level = boost()
        void applyAudio(v, { level, night: night(), even: even() }).then((applied) => {
            if (applied || retry || gone) return
            retry = () => {
                offs.forEach((off) => off())
                offs = []
                retry = null
                if (anyOn()) start()
            }
            offs = [onGlobal(document, 'pointerdown', retry, true), onGlobal(document, 'keydown', retry, true)]
        })
    }
    if (ok) {
        void readBoost().then((level) => {
            if (picked || level <= 1) return
            setBoost(level)
            start()
        })
        void Promise.all([readNight(), readEven()]).then(([n, e]) => {
            if (touched || !(n || e)) return
            setNight(n)
            setEven(e)
            start()
        })
    }
    // A new source or engine (a quality pick, the fallback chain) starts without the graph, and Even out volume's
    // look at the level starts again after a pause: say it all again.
    listen('playing', () => anyOn() && !retry && start())
    onCleanup(() => {
        gone = true
        offs.forEach((off) => off())
        releaseBoost(v)
    })
    const pick = (level: number) => {
        picked = true
        setBoost(level)
        writeBoost(level)
        start()
        flash(level > 1 ? t('player.volume_boost_now', { percent: Math.round(level * 100) }) : t('player.volume_boost_off'))
    }
    const toggleNight = () => {
        touched = true
        const on = !night()
        setNight(on)
        writeNight(on)
        start()
        flash(t(on ? 'player.night_mode_on' : 'player.night_mode_off'))
    }
    const toggleEven = () => {
        touched = true
        const on = !even()
        setEven(on)
        writeEven(on)
        start()
        flash(t(on ? 'player.even_volume_on' : 'player.even_volume_off'))
    }
    /** Night mode and Even out volume reach only the page's own sound path: a canvas engine has the boost alone. */
    const extras = () => ok && extrasHere(v)
    return { ok, ios, level: boost, pick, night, even, toggleNight, toggleEven, extras }
}
