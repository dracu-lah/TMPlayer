// The asked Refresh as Solid pieces (WP-40; the logic and its tests are ui/askedRefresh.ts): `useAskedRefresh`
// wires one reload to its status and to the page coming back after 5 minutes hidden, `RefreshButton` is
// the visible, keyboard and remote reachable button (the same round button as a chat's Refresh), and
// `PullToRefresh` is the phone's pull, off under reduced motion. Every one goes through the same `ask`,
// so a refresh already running is not started twice.
import { createSignal, onCleanup, onMount, Show } from 'solid-js'
import { RefreshIcon } from '@hugeicons/core-free-icons'
import { Icon } from './Icon'
import { t } from '../i18n'
import { form } from '../form'
import { online } from '../net'
import { reducedMotion } from './motion'
import { createAskedRefresh, onReturnAfterHidden, pullReleases, pullShown } from './askedRefresh'
import { idle, type WorkStatus } from './workStatus'
import { onGlobal } from './globalInput'

export function useAskedRefresh(run: () => Promise<unknown>) {
    const [status, setStatus] = createSignal<WorkStatus>(idle)
    const [busy, setBusy] = createSignal(false)
    const asked = createAskedRefresh({
        run: async () => {
            setBusy(true)
            try {
                await run()
            } finally {
                setBusy(false)
            }
        },
        set: setStatus,
    })
    // Back after 5 minutes hidden: Home and the list are read again quietly (no words unless it fails), keeping the scroll.
    onCleanup(onReturnAfterHidden(() => online() && asked.ask(true)))
    return { status, busy, ask: () => asked.ask() }
}

/** A round button with Refresh's glyph that spins while the reload runs; a press while it runs does nothing. */
export function RefreshButton(props: { busy: boolean; onRefresh: () => void }) {
    return (
        <button
            class="round-button refresh-button"
            classList={{ spinning: props.busy }}
            type="button"
            aria-label={t('common.refresh')}
            title={t('common.refresh')}
            aria-disabled={props.busy ? 'true' : undefined}
            onClick={() => !props.busy && props.onRefresh()}
        >
            <Icon icon={RefreshIcon} size={22} />
        </button>
    )
}


/**
 * Pull down from the top of the page to refresh, in the phone form only (a mouse, a keyboard and a remote
 * have the button and F5). Off under reduced motion, where the button remains. It never takes the page's
 * own scroll: the listeners are passive and only read how far a finger that began at the top has gone.
 */
export function PullToRefresh(props: { onRefresh: () => void }) {
    const [shown, setShown] = createSignal(0)
    onMount(() => {
        if (form() !== 'phone' || reducedMotion()) return
        const root = document.documentElement
        root.dataset.pull = ''
        let startY: number | null = null
        let travel = 0
        const atTop = () => (window.scrollY || document.scrollingElement?.scrollTop || 0) <= 0
        const start = (e: TouchEvent) => {
            startY = e.touches.length === 1 && atTop() ? e.touches[0].clientY : null
            travel = 0
        }
        const move = (e: TouchEvent) => {
            if (startY === null) return
            travel = e.touches[0].clientY - startY
            setShown(pullShown(travel))
        }
        const end = () => {
            const go = startY !== null && pullReleases(travel)
            startY = null
            travel = 0
            setShown(0)
            if (go) props.onRefresh()
        }
        // Not while the profile is locked (HX88): onGlobal is the one way to listen to the whole page.
        const off = [
            onGlobal(window, 'touchstart', start, { passive: true }),
            onGlobal(window, 'touchmove', move, { passive: true }),
            onGlobal(window, 'touchend', end, { passive: true }),
            onGlobal(window, 'touchcancel', end, { passive: true }),
        ]
        onCleanup(() => {
            delete root.dataset.pull
            for (const f of off) f()
        })
    })
    return (
        <Show when={shown() > 0}>
            <div class="pull-indicator" aria-hidden="true" style={{ transform: `translate(-50%, ${shown()}px)` }}>
                <Icon icon={RefreshIcon} size={20} />
            </div>
        </Show>
    )
}
