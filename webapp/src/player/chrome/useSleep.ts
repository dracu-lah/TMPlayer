// The sleep timer (SleepTimer) and "Still watching?" (StillWatching): the timer, the two hours of playing with
// nothing pressed, the end of an autoplay run, all of which pause the video and ask.
import { createSignal, onCleanup, onMount } from 'solid-js'
import { t } from '../../i18n'
import { onGlobal } from '../../ui/globalInput'
import { IDLE_LIMIT, noteInput, sleepState, watching } from '../extras'

export type StillWhy = 'idle' | 'autoplay' | 'sleep' | 'sleep_end'

export function useSleep(
    v: HTMLVideoElement,
    listen: (name: 'play' | 'pause', fn: () => void) => void,
    /** What the sheet says it did, and the sheet that asks "Still watching?". */
    ui: { flash: (text: string) => void; ask: () => void },
) {
    const [sleepAt, setSleepAt] = createSignal(sleepState.at)
    const [sleepEnd, setSleepEnd] = createSignal(sleepState.end)
    const [sleepPick, setSleepPick] = createSignal<number | null>(null)
    const [stillWhy, setStillWhy] = createSignal<StillWhy>('idle')
    let sleepTimer = 0
    /** The next episode keeps the timer; leaving the player ends it. */
    let keepSleep = false
    const askStill = (why: StillWhy) => {
        v.pause()
        setStillWhy(why)
        ui.ask()
    }
    const clearSleep = () => {
        clearTimeout(sleepTimer)
        setSleepAt(null)
        setSleepEnd(false)
        sleepState.at = null
        sleepState.end = false
    }
    const armSleep = () => {
        clearTimeout(sleepTimer)
        const at = sleepAt()
        if (at !== null) sleepTimer = window.setTimeout(sleepCheck, Math.max(0, Math.min(at - Date.now(), 2 ** 30)))
    }
    function sleepCheck() {
        const at = sleepAt()
        if (at === null) return
        if (Date.now() < at) return armSleep()
        clearSleep()
        askStill('sleep')
    }
    const sleepLabel = (minutes: number) =>
        minutes === 0
            ? t('player.sleep_end_of_video')
            : minutes < 60
              ? t('unit.minutes', { count: minutes })
              : minutes % 60 === 0
                ? t('unit.hours', { count: minutes / 60 })
                : t('sleep.hours_minutes', { hours: Math.floor(minutes / 60), minutes: minutes % 60 })
    const setSleep = (minutes: number | null) => {
        clearSleep()
        setSleepPick(minutes)
        if (minutes === null) return ui.flash(t('player.sleep_off'))
        if (minutes === 0) {
            setSleepEnd(true)
            sleepState.end = true
        } else {
            const at = Date.now() + minutes * 60_000
            setSleepAt(at)
            sleepState.at = at
            armSleep()
        }
        ui.flash(t('player.sleep_in', { time: sleepLabel(minutes) }))
    }
    const sleepSummary = () => {
        const at = sleepAt()
        if (at !== null) return t('sleep.minutes_left', { count: Math.max(1, Math.ceil((at - Date.now()) / 60_000)) })
        return sleepEnd() ? t('player.sleep_end_of_video') : t('common.off')
    }
    const onVisible = () => document.visibilityState === 'visible' && sleepAt() !== null && sleepCheck()
    onMount(() => (armSleep(), document.addEventListener('visibilitychange', onVisible)))
    onCleanup(() => {
        clearTimeout(sleepTimer)
        document.removeEventListener('visibilitychange', onVisible)
        if (!keepSleep) (sleepState.at = null, (sleepState.end = false))
    })
    // Two hours of playing with nothing pressed (StillWatching.askAfterIdle): one timer, armed only while it plays.
    let idleTimer = 0
    const armIdle = () => {
        clearTimeout(idleTimer)
        if (v.paused) return
        const left = IDLE_LIMIT - (Date.now() - watching.lastInput)
        idleTimer = window.setTimeout(() => {
            if (v.paused) return
            if (Date.now() - watching.lastInput >= IDLE_LIMIT) askStill('idle')
            else armIdle()
        }, Math.max(1000, left))
    }
    listen('play', armIdle)
    listen('pause', () => clearTimeout(idleTimer))
    onMount(() => {
        const offs = (['keydown', 'pointerdown'] as const).map((e) => onGlobal(document, e, noteInput, { capture: true, passive: true }))
        onCleanup(() => offs.forEach((off) => off()))
    })
    onCleanup(() => clearTimeout(idleTimer))
    return { sleepAt, sleepEnd, sleepPick, stillWhy, askStill, clearSleep, sleepLabel, setSleep, sleepSummary, keep: () => (keepSleep = true) }
}
