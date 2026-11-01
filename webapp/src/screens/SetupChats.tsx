// The first run's "Your chats" step, as the apps' setup has it (995dd13): right after signing in,
// Browse everything (the default) or Choose chats, which opens the same Add chats picker Settings
// uses. Asked once: an answer on file (or a session Telegram ended, which keeps it) goes straight
// on, and so does an account with too few chats to be worth narrowing.
import { createEffect, createResource, createSignal, onMount, Show } from 'solid-js'
import { useNavigate, useSearchParams } from '@solidjs/router'
import { call } from '../tg/rpc'
import { t } from '../i18n'
import { addedMode, keepAllChats } from '../store'
import type { ChatItem, ChatsEvent } from '../tg/types'

/** Fewer chats than this and there is nothing worth narrowing (the apps' CHATS_STEP_MIN). */
const STEP_MIN = 4

type Choice = 'all' | 'choose'

export function SetupChats() {
    const navigate = useNavigate()
    const [params] = useSearchParams<{ next?: string }>()
    const next = () => {
        const n = params.next
        return n?.startsWith('/') && !n.startsWith('//') && !n.startsWith('/setup') ? n : '/'
    }
    const go = () => navigate(next(), { replace: true })
    onMount(() => {
        if (addedMode() !== 'unanswered') go()
    })

    // The chat list, as far as it has come: the first page is enough to know whether to ask.
    const [seen, setSeen] = createSignal(0)
    const [chats] = createResource(() => call('chats', [false], { onEvent: (e) => void setSeen((e as ChatsEvent).chats.length) }) as Promise<ChatItem[]>)
    // A resource read after it failed throws, so the failure is asked about first.
    const whole = () => (chats.error ? undefined : chats())
    const enough = () => Math.max(seen(), whole()?.length ?? 0) >= STEP_MIN
    // A small account, or a list that will not load (offline), goes on to browse everything, unanswered.
    createEffect(() => {
        if ((chats.error || whole()) && !enough()) go()
    })

    const [choice, setChoice] = createSignal<Choice>('all')
    const confirm = () => {
        if (choice() === 'all') {
            keepAllChats()
            go()
        } else navigate(`/added-chats?setup=1&next=${encodeURIComponent(next())}`)
    }

    return (
        <section class="setup-step" aria-labelledby="setup-chats-title">
            <h1 id="setup-chats-title" class="page-title">{t('setup.chats_title')}</h1>
            <Show when={enough()} fallback={<p class="muted">{t('picker.loading')}</p>}>
                <p class="muted">{t('setup.chats_body')}</p>
                <div class="set-card" role="radiogroup" aria-labelledby="setup-chats-title">
                    <ChoiceRow value="all" title={t('setup.chats_all')} detail={t('setup.chats_all_detail')} chosen={choice()} onPick={setChoice} />
                    <ChoiceRow value="choose" title={t('setup.chats_choose')} detail={t('setup.chats_choose_detail')} chosen={choice()} onPick={setChoice} />
                </div>
                <div class="setup-buttons">
                    <button class="primary" type="button" onClick={confirm}>{t('setup.next')}</button>
                </div>
            </Show>
        </section>
    )
}

function ChoiceRow(props: { value: Choice; title: string; detail: string; chosen: Choice; onPick: (c: Choice) => void }) {
    return (
        <label class="set-row setup-choice">
            <input type="radio" name="setup-chats" class="chat-tick" checked={props.chosen === props.value} onChange={() => props.onPick(props.value)} />
            <span class="set-text">
                <span class="set-title">{props.title}</span>
                <span class="set-sub">{props.detail}</span>
            </span>
        </label>
    )
}
