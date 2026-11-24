// One status line for every wait that is not a first full page load (WP-98, LS-29; the 3.10 spec).
// A polite `role=status` region that is always in the page and empty while Idle (it takes no space,
// and a screen reader that has met the region reads what lands in it); `aria-busy` while Syncing on an inline line. Not on the pill: a polite region marked busy
// holds back what lands in it, and the pill's whole job is to be read once ("Updating…").
// Never `role=alert` here: a first load that failed is a card of its own. A countdown ticks in an
// `aria-hidden` span beside one fixed spoken sentence, so a reader hears it once and not each second.
// It never takes focus (Try again and Send logs are plain buttons in reading order, so the remote
// reaches them with the arrows), and it keeps one line's height for the one state, so nothing under
// it moves when it comes and goes. Words and states are ui/workStatus.ts.
import { createEffect, createMemo, createSignal, on, onCleanup, Show } from 'solid-js'
import { t, language } from '../i18n'
import { linkLine } from '../net'
import { clockTime, statusWords, type Say, type WorkStatus } from './workStatus'

const say: Say = (key, args) => t(key, args)

export interface WorkStatusProps {
    status: WorkStatus
    /** Try again on Failed and Partial; with none the button is not drawn. */
    onRetry?: () => void
    /** Send logs on Failed and Partial; with none the button is not drawn. */
    onSendLogs?: () => void
    /** A pill over the page's foot, in the offline line's place (the link line), not a line in the page. */
    pill?: boolean
}

export function WorkStatusLine(props: WorkStatusProps) {
    const [now, setNow] = createSignal(Date.now())
    const kind = () => props.status.kind
    // One timer, only while a flood wait counts down.
    createEffect(
        on(kind, (k) => {
            if (k !== 'rateLimited') return
            setNow(Date.now())
            const timer = window.setInterval(() => setNow(Date.now()), 1000)
            onCleanup(() => window.clearInterval(timer))
        }),
    )
    const time = (at: number) => clockTime(at, language())
    const words = createMemo(() => statusWords(props.status, now(), say, time))
    // The toast sits above a pill that is up (styles.css), so the two never cover each other.
    createEffect(() => {
        if (!props.pill) return
        const root = document.documentElement
        if (words()) root.dataset.statusPill = ''
        else delete root.dataset.statusPill
        onCleanup(() => delete root.dataset.statusPill)
    })
    // The spoken sentence is read from the clock once per status, not from the ticking one.
    const spoken = createMemo(() => statusWords(props.status, Date.now(), say, time)?.text ?? '')
    return (
        <div class="work-status" classList={{ pill: props.pill }} role="status" aria-live="polite" aria-busy={!props.pill && words()?.busy ? 'true' : undefined}>
            <Show when={words()}>
                {(w) => (
                    <div class="work-status-line" classList={{ [`is-${kind()}`]: true }}>
                        <Show when={w().busy}>
                            <span class="work-spinner" aria-hidden="true" />
                        </Show>
                        <Show when={w().ticking} fallback={<p>{w().text}</p>}>
                            <p>
                                <span aria-hidden="true">{w().text}</span>
                                <span class="visually-hidden">{spoken()}</span>
                            </p>
                        </Show>
                        <Show when={w().progress}>
                            {(p) => <progress value={p().done} max={p().total} aria-label={w().text} />}
                        </Show>
                        <Show when={w().actions && props.onRetry}>
                            <button class="link" type="button" onClick={() => props.onRetry?.()}>
                                {t('common.try_again')}
                            </button>
                        </Show>
                        <Show when={w().actions && props.onSendLogs}>
                            <button class="link" type="button" onClick={() => props.onSendLogs?.()}>
                                {t('logs.send')}
                            </button>
                        </Show>
                    </div>
                )}
            </Show>
        </div>
    )
}

/** The line to Telegram in the offline line's place: "Connecting…" after 3 s, "Updating…" after a 2 s settle (net.ts). */
export function LinkStatusLine() {
    const status = createMemo<WorkStatus>(() => {
        const line = linkLine()
        if (line === 'connecting') return { kind: 'syncing', what: t('state.connecting_short') }
        if (line === 'updating') return { kind: 'syncing', what: t('state.updating') }
        return { kind: 'idle' }
    })
    return <WorkStatusLine pill status={status()} />
}
