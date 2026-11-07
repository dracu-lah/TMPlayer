// "Choose the right poster": a dialog listing the TMDB titles a video could be (poster, title, year,
// language, a line of story; the one on show marked). Choosing one stores it for the video
// (store.ts setPick) and every card, the hero and the video's page redraw with it at once. One
// dialog for the whole page, opened from a card's menu and from a video's page. Keyboard and
// remote: the first title takes focus, Up and Down walk the list, Enter picks, Escape (Back) closes
// and the focus goes back to where it was; a failure says so with Try again.
import { createEffect, createResource, createSignal, For, on, onCleanup, Show } from 'solid-js'
import { t } from '../i18n'
import { pickOf, setPick, settings } from '../store'
import { sameTitle, type PickedAsk } from '../pick'
import { answer, askOf, ChoicesError, loadChoices, loadFile, type Choice } from '../screens/posters'
import type { VideoItem } from '../tg/types'

interface Target {
    item: VideoItem
    /** Other videos of the title in this chat, for "Also use it for...". */
    others: VideoItem[]
}

const [target, setTarget] = createSignal<Target | null>(null)

/** The videos the chat on screen holds, so a pick can be offered to the others of its title. */
let pool: { chatId: number; items: readonly VideoItem[] } | null = null
export function notePool(chatId: number, items: readonly VideoItem[]) {
    pool = { chatId, items }
}

/** Opens the picker for `item`; `extra` is more videos of its title the page knows (a show's episodes). */
export function openPosterPicker(item: VideoItem, extra: readonly VideoItem[] = []) {
    const held = pool && pool.chatId === item.chatId ? pool.items : []
    setTarget({ item, others: sameTitle(item, [...held, ...extra]) })
}

type Load = { kind: 'ok'; choices: Choice[] } | { kind: 'off' } | { kind: 'failed' }

/** The question the search takes: the name's own, or the video's title for a name that asked nothing. */
function searchAsk(item: VideoItem) {
    return item.poster ?? { title: item.title, year: null, kind: 'film' as const, languages: [] }
}

/** "Tamil" for "ta", in the page's language; the code itself where the browser has no name for it. */
function languageName(code: string): string {
    if (!code) return ''
    try {
        return new Intl.DisplayNames([navigator.language || 'en'], { type: 'language' }).of(code) ?? code.toUpperCase()
    } catch {
        return code.toUpperCase()
    }
}

function Thumb(props: { path: string | null }) {
    const [src, setSrc] = createSignal<string | null>(null)
    createEffect(() => {
        const path = props.path
        setSrc(null)
        if (!path) return
        let live = true
        loadFile(path, 'w185').then((u) => live && setSrc(u), () => {})
        onCleanup(() => (live = false))
    })
    return (
        <span class="choice-thumb" aria-hidden="true">
            <Show when={src()}>
                <img src={src()!} alt="" decoding="async" />
            </Show>
        </span>
    )
}

export function PosterPicker() {
    let dialog!: HTMLDialogElement
    let list: HTMLDivElement | undefined
    const [attempt, setAttempt] = createSignal(0)
    const [alsoOthers, setAlsoOthers] = createSignal(false)
    // A second press while the first is being written is the same press again.
    let chosen = false

    createEffect(() => {
        if (target() && !dialog.open) dialog.showModal()
        else if (!target() && dialog.open) dialog.close()
    })
    createEffect(
        on(target, () => {
            setAttempt(0)
            setAlsoOthers(false)
            chosen = false
        }),
    )

    const [load] = createResource(
        () => {
            const item = target()?.item
            return item ? { item, attempt: attempt() } : null
        },
        async ({ item }): Promise<Load> => {
            if (!settings().posters) return { kind: 'off' }
            try {
                return { kind: 'ok', choices: await loadChoices(searchAsk(item)) }
            } catch (e) {
                if (!(e instanceof ChoicesError)) console.warn('[poster picker]', e)
                return { kind: 'failed' }
            }
        },
    )

    // The title on show now: the viewer's pick, else what the name's own lookup matched (kept already by the card).
    const [shown] = createResource(
        () => target()?.item ?? null,
        async (item) => {
            const ask: PickedAsk | null = askOf(item)
            if (!ask) return null
            const found = await answer(ask).catch(() => null)
            return found?.match && found.id && found.kind ? { id: Number(found.id), kind: found.kind } : null
        },
    )
    const isCurrent = (c: Choice) => {
        const item = target()?.item
        const pick = item ? pickOf(item.chatId, item.messageId) : undefined
        if (pick) return pick.id === c.id && pick.kind === c.kind
        const s = shown()
        return !!s && s.id === c.id && s.kind === c.kind
    }

    // The keyboard (and a TV remote, which sends the same keys) lands on the first title once the list is in.
    createEffect(() => {
        const l = load()
        if (!target() || !l || l.kind === 'off') return
        queueMicrotask(() => (list?.querySelector<HTMLElement>('.choice-row, .link') ?? dialog.querySelector<HTMLElement>('button'))?.focus())
    })

    const close = () => setTarget(null)
    const files = (item: VideoItem) => [item, ...(alsoOthers() ? target()!.others : [])]
    const choose = (c: Choice) => {
        const now = target()
        if (!now || chosen) return
        chosen = true
        setPick(files(now.item), { id: c.id, kind: c.kind })
        close()
    }
    const reset = () => {
        const now = target()
        if (!now || chosen) return
        chosen = true
        setPick(files(now.item), null)
        close()
    }
    const walk = (e: KeyboardEvent) => {
        if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return
        const rows = [...dialog.querySelectorAll<HTMLElement>('.choice-row')]
        const at = rows.indexOf(document.activeElement as HTMLElement)
        if (at < 0 && e.key === 'ArrowUp') return
        const next = rows[at + (e.key === 'ArrowDown' ? 1 : -1)]
        if (!next) return
        e.preventDefault()
        next.focus()
    }

    return (
        <dialog
            class="confirm card-menu poster-picker"
            ref={dialog}
            aria-labelledby="poster-picker-title"
            onClose={close}
            onClick={(e) => e.target === dialog && close()}
            onKeyDown={walk}
        >
            <Show when={target()}>
                {(now) => (
                    <>
                        <h2 id="poster-picker-title">{t('poster.choose')}</h2>
                        <p class="muted small">{t('poster.note')}</p>
                        <Show when={load()?.kind === 'ok' && now().others.length > 0}>
                            <label class="choice-others">
                                <input type="checkbox" checked={alsoOthers()} onChange={(e) => setAlsoOthers(e.currentTarget.checked)} />
                                <span>{t('poster.others', { count: now().others.length })}</span>
                            </label>
                        </Show>
                        <div class="choice-list" ref={list} aria-live="polite">
                            <Show when={load.loading}>
                                <p class="muted">{t('common.loading')}</p>
                            </Show>
                            <Show when={!load.loading ? load() : undefined}>
                                {(l) => (
                                    <>
                                        <Show when={l().kind === 'off'}>
                                            <p class="muted">{t('poster.off')}</p>
                                        </Show>
                                        <Show when={l().kind === 'failed'}>
                                            <p class="muted">{t('poster.failed')}</p>
                                            <button class="link" type="button" onClick={() => setAttempt(attempt() + 1)}>{t('common.try_again')}</button>
                                        </Show>
                                        <Show when={l().kind === 'ok' && (l() as { choices: Choice[] }).choices.length === 0}>
                                            <p class="muted">{t('poster.none')}</p>
                                        </Show>
                                        <For each={l().kind === 'ok' ? (l() as { choices: Choice[] }).choices : []}>
                                            {(c) => (
                                                <button class="choice-row" type="button" aria-current={isCurrent(c) ? 'true' : undefined} onClick={() => choose(c)}>
                                                    <Thumb path={c.poster} />
                                                    <span class="choice-text">
                                                        <strong>{c.title}</strong>
                                                        <small>
                                                            {[isCurrent(c) ? t('poster.current') : '', c.year ? String(c.year) : '', c.kind === 'show' ? t('poster.show') : t('poster.film'), languageName(c.language)]
                                                                .filter(Boolean)
                                                                .join('  ·  ')}
                                                        </small>
                                                        <Show when={c.overview}>
                                                            <small class="choice-story">{c.overview}</small>
                                                        </Show>
                                                    </span>
                                                </button>
                                            )}
                                        </For>
                                    </>
                                )}
                            </Show>
                        </div>
                        <div class="confirm-buttons">
                            <Show when={pickOf(now().item.chatId, now().item.messageId)}>
                                <button class="link" type="button" onClick={reset}>{t('poster.reset')}</button>
                            </Show>
                            <button class="link" type="button" onClick={close}>{t('common.cancel')}</button>
                        </div>
                    </>
                )}
            </Show>
        </dialog>
    )
}
