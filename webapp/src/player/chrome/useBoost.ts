// Volume boost (boost.ts): the sound above the file's own level, kept for every video until turned off. Not on an
// iPhone or iPad, where the page cannot carry it, so the entry is left out there.
import { createSignal, onCleanup } from 'solid-js'
import { t } from '../../i18n'
import { onGlobal } from '../../ui/globalInput'
import { readBoost, writeBoost } from '../../store'
import { applyBoost, boostIsIos, boostSupported, releaseBoost } from '../boost'

export function useBoost(v: HTMLVideoElement, listen: (name: 'playing', fn: () => void) => void, flash: (text: string) => void) {
    const ok = boostSupported()
    const ios = boostIsIos()
    const [boost, setBoost] = createSignal(1)
    let picked = false
    // A level that could not start yet (no tap has allowed audio) starts on the next one.
    let retry: (() => void) | null = null
    let offs: (() => void)[] = []
    const start = (level: number) => {
        void applyBoost(v, level).then((applied) => {
            if (applied || retry) return
            retry = () => {
                offs.forEach((off) => off())
                offs = []
                retry = null
                if (boost() === level) start(level)
            }
            offs = [onGlobal(document, 'pointerdown', retry, true), onGlobal(document, 'keydown', retry, true)]
        })
    }
    if (ok)
        void readBoost().then((level) => {
            if (picked || level <= 1) return
            setBoost(level)
            start(level)
        })
    // A new source or engine (a quality pick, the fallback chain) starts without the graph: say the level again.
    listen('playing', () => boost() > 1 && !retry && start(boost()))
    onCleanup(() => {
        offs.forEach((off) => off())
        releaseBoost(v)
    })
    const pick = (level: number) => {
        picked = true
        setBoost(level)
        writeBoost(level)
        start(level)
        flash(level > 1 ? t('player.volume_boost_now', { percent: Math.round(level * 100) }) : t('player.volume_boost_off'))
    }
    return { ok, ios, level: boost, pick }
}
