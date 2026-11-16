// The way into the chats gate (App.tsx sends every signed in visitor who has never chosen here):
// a loader with nothing to press, which reads the first page of the chat list and decides. Enough
// chats opens the picker in its gate form; too few, or a list that will not load, goes on with
// nothing written, so the gate asks again on a later visit (the apps' ChatsGate.decide).
import { createEffect, createResource, createSignal, onCleanup, onMount, Show } from 'solid-js'
import { useNavigate, useSearchParams } from '@solidjs/router'
import { call } from '../tg/rpc'
import { t } from '../i18n'
import { addedMode, setChatsGateWaived } from '../store'
import type { ChatItem, ChatsEvent } from '../tg/types'

/**
 * Fewer chats than this and there is nothing to ask about. The apps' setup asks about even one
 * chat (SetupFlow: Saved Messages alone gets the "Forward a video" hint), so the web does too.
 */
const STEP_MIN = 1
/** How long the first page may take before the visitor goes on without the gate (the apps' Setup.WAIT_FOR_CHATS_MS). */
const WAIT_MS = 8000

/** Where the visitor was headed, never back into the gate itself. */
export function gateNext(n: string | undefined): string {
    return n?.startsWith('/') && !n.startsWith('//') && !n.startsWith('/setup') && !n.startsWith('/added-chats') ? n : '/'
}

export function SetupChats() {
    const navigate = useNavigate()
    const [params] = useSearchParams<{ next?: string }>()
    const next = () => gateNext(params.next)
    let gone = false
    const go = () => {
        if (gone) return
        gone = true
        navigate(next(), { replace: true })
    }
    // Nothing to choose this visit: the gate stands aside until the next, and nothing is written.
    const waive = () => {
        setChatsGateWaived(true)
        go()
    }
    onMount(() => {
        if (addedMode() !== 'unanswered') go()
    })

    // The chat list, as far as it has come: the first page is enough to know whether to ask.
    const [seen, setSeen] = createSignal(0)
    const [chats] = createResource(() => call('chats', [false], { onEvent: (e) => void setSeen((e as ChatsEvent).chats.length) }) as Promise<ChatItem[]>)
    // A resource read after it failed throws, so the failure is asked about first.
    const whole = () => (chats.error ? undefined : chats())
    const enough = () => Math.max(seen(), whole()?.length ?? 0) >= STEP_MIN
    createEffect(() => {
        if (gone) return
        if (enough()) {
            gone = true
            navigate(`/added-chats?setup=1&next=${encodeURIComponent(next())}`, { replace: true })
        } else if (chats.error || whole()) waive()
    })
    // No network and nothing in yet: say so under the heading, as the apps' offline card does.
    const [offline, setOffline] = createSignal(typeof navigator !== 'undefined' && navigator.onLine === false)
    const goOffline = () => setOffline(true)
    const goOnline = () => setOffline(false)
    window.addEventListener('offline', goOffline)
    window.addEventListener('online', goOnline)
    onCleanup(() => {
        window.removeEventListener('offline', goOffline)
        window.removeEventListener('online', goOnline)
    })
    // A list that never answers (offline, a stalled connection) does not hold the visitor here.
    const timer = window.setTimeout(() => {
        if (!enough()) waive()
    }, WAIT_MS)
    onCleanup(() => clearTimeout(timer))

    return (
        <section class="setup-step" aria-labelledby="setup-chats-title" aria-busy="true">
            <h1 id="setup-chats-title" class="page-title">{t('setup.which_title')}</h1>
            <p class="muted">{t(offline() ? 'setup.offline_title' : 'picker.loading')}</p>
            <Show when={offline()}>
                <p class="muted">{t('setup.offline_body')}</p>
            </Show>
        </section>
    )
}
