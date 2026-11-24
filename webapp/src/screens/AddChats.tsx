// The chats picker (HX27): which of the viewer's chats TMPlayer shows videos from. Nothing changes
// in Telegram either way; Done always writes a choice, even an empty one, because an empty choice
// is a real answer and not a reason to fall back to every chat (store.ts setAddedChats). The rows,
// their bands and what Select all means are core's ChatPicking rules (ui/picking.ts); the archive
// is read whole beside the list (HX32), and a list that failed part way says so.
//
// Opened with `setup=1` it is the chats gate (App.tsx): no Back, no bar, and Done waits for at
// least one chat. Either way a viewer who has not chosen before opens on the Recommended band
// (nothing ticked for them), made from the head of the list counted first (core's ChatSuggestions).
// The counts have a budget (ui/pickCount.ts): the head, then the rows the viewer stays on, then more
// only when they ask for it, and none once Telegram refuses.
import { onGlobal } from '../ui/globalInput'
import { createEffect, createMemo, createResource, createSignal, For, onCleanup, onMount, Show, untrack } from 'solid-js'
import { useBeforeLeave, useNavigate, useSearchParams } from '@solidjs/router'
import { Search01Icon, StarIcon } from '@hugeicons/core-free-icons'
import { call } from '../tg/rpc'
import { t } from '../i18n'
import { addedChats, addedMode, setAddedChats, setStarred, starredIds, type AddedChat } from '../store'
import { back } from '../session'
import { form } from '../form'
import { Icon } from '../ui/Icon'
import { PageBar } from '../ui/PageBar'
import { VirtualGrid, type GridLayout } from '../ui/VirtualGrid'
import { adding, afterAllOff, allTicked, changed, clearVisible, counted, freshTicks, headStart, needsConfirm, pickRows, refine, RECOMMEND_BY_MS, savedId, scanOrder, SCOPES, selectAll, visibleIds, type Band, type PickRow, type Scope, type Suggestion } from '../ui/picking'
import { COUNT_DWELL_MS, createCountPlan } from '../ui/pickCount'
import { createDwell } from '../ui/dwell'
import { REFUSED, UNKNOWN } from '../tg/counts'
import { ChatPicture } from './ChatPicture'
import { chatName } from './Chats'
import { gateNext } from './SetupChats'
import type { ChatItem, ChatsEvent } from '../tg/types'

/** One row at a time, as wide as the list: a tick, the picture and the name, and in Recommended a line on why it is there. */
const ROW_LAYOUT: GridLayout = { cols: 1, estimate: 72 }

/** Counts are asked for three chats at a time (tg/counts.ts); -1 is "could not be read", -2 "Telegram refused". They only rank the Recommended band: no figure is shown. */
const BATCH = 3

type Key = Parameters<typeof t>[0]

const SCOPE_LABEL: Record<Scope, Key> = {
    all: 'browse.filter_all',
    archived: 'browse.filter_archived',
    channels: 'browse.filter_channels',
    groups: 'browse.filter_groups',
    people: 'browse.filter_people',
}
const BAND_LABEL: Partial<Record<Band, Key>> = {
    recommended: 'picker.band_recommended',
    starred: 'picker.band_starred',
    chats: 'browse.filter_all_chats',
    archived: 'browse.filter_archived',
    gone: 'picker.band_gone',
}

type Item = { head: string } | { note: string } | { row: PickRow<ChatItem> }

/** A chat list read from the worker, with its pages as they come and whether it failed part way. */
function useChatList(name: 'chats' | 'archivedChats') {
    const [partial, setPartial] = createSignal<ChatItem[]>([])
    const [res, { refetch }] = createResource(() => call(name, name === 'chats' ? [false] : [], { onEvent: (e) => void setPartial((e as ChatsEvent).chats) }) as Promise<ChatItem[]>)
    return {
        list: () => (res.error ? partial() : (res() ?? partial())),
        done: () => !res.error && res() !== undefined,
        failed: () => !!res.error,
        retry: () => void refetch(),
    }
}

export function AddChats() {
    const navigate = useNavigate()
    // Opened as the chats gate: Done goes on to where the visitor was headed.
    const [params] = useSearchParams<{ setup?: string; next?: string }>()
    const gate = () => params.setup !== undefined
    const setupNext = () => (gate() ? gateNext(params.next) : null)

    // The full account's chats, unfiltered, archive included: picking from them is the one place
    // the gate itself does not apply (Chats.tsx is the gated browse list; this is where it is set).
    const main = useChatList('chats')
    const archive = useChatList('archivedChats')
    const list = createMemo(() => {
        const here = new Set(main.list().map((c) => c.id))
        return [...main.list(), ...archive.list().filter((c) => !here.has(c.id))]
    })
    const loaded = () => main.done() && archive.done()
    const failed = () => main.failed() || archive.failed()
    const retry = () => {
        if (main.failed()) main.retry()
        if (archive.failed()) archive.retry()
    }

    const [stored] = createResource(addedChats)

    // What is ticked, seeded once from the stored choice (an empty choice from before stays empty, not "every chat").
    const [ticked, setTicked] = createSignal<ReadonlySet<number>>(new Set())
    let start: ReadonlySet<number> = new Set()
    let seeded = false
    const [seededNow, setSeededNow] = createSignal(false)
    // "All chats" (HX86): every chat counts, the ticks are disabled but kept, and off brings them back.
    // Off by default; a stored 'all' opens on. A visitor who never answered starts on Saved Messages once the list has it.
    const [allChats, setAllChats] = createSignal(false)
    let startAll = false
    let touchedAll = false
    let fresh = false
    let freshSeeded = false
    createEffect(() => {
        const s = stored()
        if (s && !seeded) {
            seeded = true
            start = new Set(s.map((a) => a.chatId))
            setTicked(start)
            startAll = addedMode() === 'all'
            fresh = addedMode() === 'unanswered'
            if (!touchedAll) setAllChats(startAll)
            setSeededNow(true)
        }
    })
    createEffect(() => {
        if (!seededNow() || !fresh || freshSeeded || !savedId(list())) return
        freshSeeded = true
        // Only on an empty choice: a tick made first is the viewer's. The untouched picker then counts as unchanged.
        if (untrack(ticked).size) return
        const first = freshTicks(list())
        setTicked(first)
        if (!start.size) start = first
    })
    const flipAll = () => {
        touched = true
        touchedAll = true
        if (allChats()) {
            setAllChats(false)
            setTicked(afterAllOff(ticked(), list(), loaded()))
        } else setAllChats(true)
    }

    const [text, setText] = createSignal('')
    const [scope, setScope] = createSignal<Scope>('all')
    // The Recommended band: taken within seconds from the head start, ranked again as the rest of the
    // account is counted until the viewer touches the list, then only added to (core's ChatSuggestions.refine).
    const [recommended, setRecommended] = createSignal<number[]>([])
    const [belowBar, setBelowBar] = createSignal(false)
    const [recommending, setRecommending] = createSignal(false)
    const whole = () => !text().trim() && scope() === 'all'
    const rows = createMemo(() =>
        pickRows({
            chats: list(),
            name: chatName,
            added: allChats() ? new Set<number>() : new Set((stored() ?? []).map((a) => a.chatId)),
            addedNames: new Map((stored() ?? []).map((a) => [a.chatId, a.title])),
            starred: starredIds(),
            recommended: recommended(),
            query: text(),
            scope: scope(),
            listComplete: loaded(),
        }),
    )
    const visible = createMemo(() => visibleIds(rows()))
    // A heading over each band once there is more than one; Saved Messages has none. While the
    // head start counts, the Recommended band's place is held under Saved Messages with a line.
    const items = createMemo((): Item[] => {
        const shown = rows()
        const bands = new Set(shown.map((r) => r.band))
        const holding = recommending() && whole()
        const out: Item[] = []
        let last: Band | null = null
        const hold = () => {
            out.push({ head: t('picker.band_recommended') }, { note: t('browse.finding_videos') })
            last = 'recommended'
        }
        shown.forEach((row, i) => {
            if (holding && last !== 'recommended' && row.band !== 'saved') hold()
            const label = BAND_LABEL[row.band]
            if (row.band !== last && (bands.size > 1 || holding) && label) out.push({ head: t(label) })
            last = row.band
            out.push({ row })
            // Under the last recommended row, when none cleared the bar: why these are offered unticked.
            if (row.band === 'recommended' && belowBar() && shown[i + 1]?.band !== 'recommended') out.push({ note: t('picker.below_bar_many') })
        })
        if (holding && last !== 'recommended' && out.length) hold()
        return out
    })

    // Each chat's rough video count, asked for three chats at a time, within the visit's budget (ui/pickCount.ts).
    // It ranks the band and is never shown.
    const [known, setKnown] = createSignal<ReadonlyMap<number, number>>(new Map())
    const plan = createCountPlan()
    const [refused, setRefused] = createSignal(false)
    const [spent, setSpent] = createSignal(0)
    const [allowance, setAllowance] = createSignal(plan.allowance)
    const sync = () => {
        setSpent(plan.spent)
        setAllowance(plan.allowance)
    }
    async function resolveCounts(ids: number[]) {
        try {
            const counts = await call('videoCounts', [ids], { signal: stop.signal })
            if (closed) return
            const failed: number[] = []
            const next = new Map(known())
            for (const [k, v] of Object.entries(counts)) {
                const n = typeof v === 'number' && Number.isFinite(v) ? v : UNKNOWN
                // A refusal is not an answer: the chat stays uncounted, and nothing more is asked this visit.
                if (n === REFUSED) continue
                if (n === UNKNOWN) failed.push(Number(k))
                next.set(Number(k), n)
            }
            setKnown(next)
            if (Object.values(counts).includes(REFUSED)) {
                plan.refuse()
                setRefused(true)
            }
            plan.retryLater(failed)
        } catch {
            // The call itself failed (the worker restarted): these chats get the one more try every failed chat gets, no more.
            if (!closed) plan.retryLater(ids)
        }
        sync()
    }
    // True once the viewer has done anything to the list (ticked, starred, searched, filtered): from
    // then on no row of the band moves under a finger.
    let touched = false
    let closed = false
    let recommendBy = 0
    // Closing the picker aborts the call in flight: the worker asks nothing more for it, not even after a flood wait.
    const stop = new AbortController()
    onCleanup(() => {
        closed = true
        stop.abort()
        plan.close()
        clearTimeout(recommendBy)
    })

    const current = (): Suggestion => ({ ids: recommended(), belowBar: belowBar() })
    // Ranks the band from the counts in hand.
    const rank = () => {
        const next = refine(current(), counted(list(), known()), touched, ticked())
        if (next.ids.length === recommended().length && next.ids.every((id, i) => id === recommended()[i]) && next.belowBar === belowBar()) return
        setRecommended(next.ids)
        setBelowBar(next.belowBar)
        // Recommended rows are shown, never ticked for the viewer (2026-11-04).
    }
    const countAll = async (ids: number[]) => {
        for (let i = 0; i < ids.length && !closed && !plan.refused; i += BATCH) await resolveCounts(ids.slice(i, i + BATCH))
    }

    // The head of the list is counted once, as soon as the first chats and the stored choice are
    // in, so the Recommended band shows within the first seconds rather than after the whole
    // account is read (core's ChatPicking.headStart). Behind it only what the budget allows is
    // counted: rows the viewer stays on, then more when they ask. Three at a time, and it stops when
    // the picker closes or Telegram refuses.
    let started = false
    const [headDone, setHeadDone] = createSignal(false)
    let pumping = false
    /** Counts what the plan hands out, a few at a time, until it has nothing: no timer runs once it is empty. */
    const pump = async () => {
        if (pumping || !headDone()) return
        pumping = true
        try {
            for (let ids = plan.take(BATCH); ids.length && !closed; ids = plan.take(BATCH)) {
                sync()
                await resolveCounts(ids)
                rank()
            }
        } finally {
            pumping = false
            sync()
        }
    }
    // A row the viewer stayed on is counted next (the dwell is Home's, ui/dwell.ts).
    const rowDwell = createDwell({ ms: COUNT_DWELL_MS, grace: 600, confirm: (k) => { plan.offer(Number(k)); void pump() }, release: () => {} })
    createEffect(() => {
        if (started || !seededNow() || !list().length) return
        started = true
        const ids = untrack(() => headStart(list()))
        plan.reserve(ids)
        sync()
        setRecommending(true)
        const counting = countAll(ids)
        // The band is made from whatever answered by the deadline: one slow chat must not hold back the other fifty nine.
        let settled = false
        const settle = () => {
            if (settled || closed) return
            settled = true
            clearTimeout(recommendBy)
            rank()
            setRecommending(false)
            // Nothing answered in time at all (offline, a cold start): the band waits for the head start instead of staying empty.
            if (!recommended().length) void counting.then(() => !closed && rank())
        }
        recommendBy = window.setTimeout(settle, RECOMMEND_BY_MS)
        void counting.then(() => {
            settle()
            setHeadDone(true)
            void pump()
        })
    })
    // The head start asked each chat once; the count stays in `known`, so a row that was in it is not asked again.
    // "Look in more chats": the budget grows and the rest of the account is counted in scanOrder, channels first.
    const countMore = () => {
        plan.setScanOrder(scanOrder(list()).map((c) => c.id))
        plan.more()
        sync()
        void pump()
    }
    const wantsMore = () => whole() && loaded() && !refused() && (spent(), allowance(), plan.wantsMore(list().length))

    // The TV's remote moves by where things stand, and Done floats at the foot of the page, so no arrow reaches it from the rows:
    // Right from a star goes to it, and Left, Up or Down from it back to the row the remote was on. Left from a star is its
    // own row's tick: a row under the floating Done (a 720 p screen, the All chats switch above) would otherwise find Done nearer.
    let lastRow: HTMLElement | null = null
    let doneButton!: HTMLButtonElement
    const toDone = (el: HTMLElement) =>
        el.addEventListener('tvmove', (e) => {
            const dir = (e as CustomEvent).detail
            if (dir === 'left') {
                const tick = el.closest('.chat-row')?.querySelector<HTMLInputElement>('.chat-tick')
                if (!tick || tick.disabled) return
                e.preventDefault()
                tick.focus()
                return
            }
            if (dir !== 'right' || doneButton.disabled) return
            e.preventDefault()
            doneButton.focus()
        })
    const fromDone = (el: HTMLElement) =>
        el.addEventListener('tvmove', (e) => {
            const dir = (e as CustomEvent).detail
            // Nothing lies to the right of Done; the other ways go back to the row.
            if (dir !== 'right' && !lastRow?.isConnected) return
            e.preventDefault()
            if (dir !== 'right') lastRow?.focus()
        })

    const toggle = (id: number) => {
        touched = true
        if (allChats()) return
        setTicked((t) => {
            const next = new Set(t)
            if (next.has(id)) next.delete(id)
            else next.add(id)
            return next
        })
    }

    // Select all over the rows shown, with a word first when it is a lot; Clear once all are ticked. Ctrl+A runs the same.
    let confirmAll!: HTMLDialogElement
    const [asking, setAsking] = createSignal(0)
    const selectAllNow = () => {
        touched = true
        // Ctrl+A and the button do nothing while every chat counts.
        if (allChats()) return
        const n = adding(ticked(), visible())
        if (!n) return
        if (needsConfirm(n)) {
            setAsking(n)
            confirmAll.showModal()
        } else setTicked(selectAll(ticked(), visible()))
    }
    const allOn = () => allTicked(ticked(), visible())
    onMount(() => {
        const keys = (e: KeyboardEvent) => {
            if (form() !== 'desktop' || !(e.ctrlKey || e.metaKey) || e.key.toLowerCase() !== 'a') return
            if (e.target instanceof HTMLInputElement && e.target.type !== 'checkbox') return
            if (document.querySelector('dialog[open]')) return
            e.preventDefault()
            selectAllNow()
        }
        onCleanup(onGlobal(window, 'keydown', keys))
    })

    const footer = () => {
        if (allChats()) return t('picker.all_chats_on')
        const n = ticked().size
        return n ? t('picker.total', { chats: n }) : t('picker.total_none')
    }

    // Leaving with ticks not saved asks first, whichever way it leaves (Back, the browser, the remote).
    let leaving = false
    let discard!: HTMLDialogElement
    let retryLeave: (() => void) | null = null
    // The gate asks nothing: leaving it only meets it again (App.tsx), so there is nothing to lose.
    useBeforeLeave((e) => {
        if (leaving || gate() || e.defaultPrevented || (!changed(start, ticked()) && startAll === allChats())) return
        e.preventDefault()
        retryLeave = () => e.retry(true)
        discard.showModal()
    })

    const [saving, setSaving] = createSignal(false)
    // The gate needs at least one chat; Settings takes an empty choice as a real answer.
    const blocked = () => gate() && !ticked().size && !allChats()
    const doneLabel = () => (ticked().size || allChats() ? t('common.done') : gate() ? t('picker.gate_done_none') : t('picker.done_none'))
    const done = () => {
        if (saving() || blocked()) return
        setSaving(true)
        const byId = new Map(list().map((c) => [c.id, chatName(c)]))
        const oldNames = new Map((stored() ?? []).map((a: AddedChat) => [a.chatId, a.title]))
        const chosen = [...ticked()].map((chatId) => ({ chatId, title: byId.get(chatId) ?? oldNames.get(chatId) ?? '' }))
        setAddedChats(chosen, allChats())
        leaving = true
        const next = setupNext()
        if (next) navigate(next, { replace: true })
        else back(navigate)
    }

    return (
        <>
            <Show
                when={gate()}
                fallback={
                    <>
                        <PageBar title={t('picker.choose_chats')} />
                        <h1 class="visually-hidden">{t('picker.choose_chats')}</h1>
                        <p class="muted picker-blurb">{t('picker.blurb_only')}</p>
                    </>
                }
            >
                {/* The gate has no Back: choosing is the way on. */}
                <h1 class="page-title">{t('setup.which_title')}</h1>
                <p class="muted picker-blurb">{t('setup.which_body')}</p>
            </Show>
            <button type="button" role="switch" class="picker-all" aria-checked={allChats()} onClick={flipAll} data-tv-first={allChats() ? '' : undefined}>
                <span class="picker-all-text">
                    <span class="picker-all-title">{t('picker.all_chats_switch')}</span>
                    <span class="muted small">{allChats() ? t('picker.all_chats_on') : t('picker.all_chats_detail')}</span>
                </span>
                <span class="switch" aria-hidden="true" />
            </button>
            <label class="search-field">
                <Icon icon={Search01Icon} size={20} />
                {/* The TV's remote starts here, the field read only until OK; on the switch above instead while All chats is on, as in the apps (every row is disabled then). */}
                <input type="search" placeholder={t('chats.search')} aria-label={t('chats.search')} value={text()} onInput={(e) => { touched = true; setText(e.currentTarget.value) }} data-tv-first={allChats() ? undefined : ''} />
            </label>
            <div class="picker-actions">
                <div class="picker-scopes" role="group" aria-label={t('picker.choose_chats')}>
                    <For each={SCOPES}>
                        {(s) => (
                            <button class="history-tab" type="button" aria-pressed={scope() === s} onClick={() => { touched = true; setScope(s) }}>
                                {t(SCOPE_LABEL[s])}
                            </button>
                        )}
                    </For>
                </div>
                <button class="link" type="button" onClick={() => { touched = true; if (allOn()) setTicked(clearVisible(ticked(), visible())); else selectAllNow() }} disabled={!visible().length || allChats()}>
                    {allOn() ? t('common.clear') : t('browse.select_all')}
                </button>
            </div>
            {/* The count behind the Recommended band stops at its budget: say how far it went, and let the viewer ask for more. */}
            <Show when={refused() && whole()}>
                <p class="muted small picker-note" role="status">{t('picker.count_refused')}</p>
            </Show>
            <Show when={wantsMore()}>
                <div class="picker-count-more">
                    <span class="muted small" role="status">{t('picker.counted_some', { counted: spent(), total: list().length })}</span>
                    <button class="link" type="button" onClick={countMore}>{t('picker.count_more')}</button>
                </div>
            </Show>
            <Show when={failed()}>
                <div class="notice error-notice">
                    <p class="error" role="alert">{t('chats.some_not_loaded')}</p>
                    <button class="link" type="button" onClick={retry}>{t('common.try_again')}</button>
                </div>
            </Show>
            <Show when={!loaded() && !failed() && !list().length}>
                <p class="muted">{t('picker.loading')}</p>
            </Show>
            {/* No chat holds videos yet (Saved Messages may be the only row): say what to do. */}
            <Show when={gate() && loaded() && !failed() && list().length <= 1}>
                <p class="muted picker-note">{t('picker.forward_hint')}</p>
            </Show>
            <Show when={(loaded() || failed()) && !rows().length}>
                <p class="muted">{text().trim() ? t('chats.no_match', { query: text().trim() }) : t('picker.empty')}</p>
            </Show>
            <VirtualGrid items={items()} layout={() => ROW_LAYOUT} rowClass="chats" label={t('picker.choose_chats')}>
                {(item) =>
                    'head' in item ? (
                        <h2 class="picker-band">{item.head}</h2>
                    ) : 'note' in item ? (
                        <p class="muted small picker-note">{item.note}</p>
                    ) : item.row.chat ? (
                        <PickRowView chat={item.row.chat} disabled={allChats()} on={allChats() || ticked().has(item.row.id)} onToggle={() => toggle(item.row.id)} onStar={() => (touched = true)} onFocused={(el) => (lastRow = el)} onStarRef={toDone} onShown={() => rowDwell.enter(String(item.row.id), () => {})} onGone={() => rowDwell.leave(String(item.row.id))} reason={item.row.band === 'recommended' ? t(belowBar() ? 'picker.reason_some' : 'picker.reason_many') : ''} />
                    ) : (
                        <div class="picker-gone-row">
                            <span class="chat-name">{item.row.title}</span>
                            <span class="muted small">{t('picker.gone_detail')}</span>
                            <Show when={ticked().has(item.row.id)}>
                                <button class="link" type="button" onClick={() => toggle(item.row.id)}>
                                    {t('picker.remove')}
                                </button>
                            </Show>
                        </div>
                    )
                }
            </VirtualGrid>
            <div class="select-bar picker-bar">
                <span class="select-count" aria-live="polite">{footer()}</span>
                <button class="primary" type="button" ref={(el) => { doneButton = el; fromDone(el) }} disabled={saving() || blocked()} onClick={done}>{doneLabel()}</button>
            </div>
            <dialog class="confirm" ref={confirmAll} aria-labelledby="select-all-title" onClose={() => setAsking(0)}>
                <h2 id="select-all-title">{t('picker.confirm_title', { count: asking() })}</h2>
                <p>{t('picker.confirm_body')}</p>
                <div class="confirm-buttons">
                    <button class="link" type="button" onClick={() => confirmAll.close()}>{t('common.cancel')}</button>
                    <button
                        class="primary"
                        type="button"
                        onClick={() => {
                            confirmAll.close()
                            setTicked(selectAll(ticked(), visible()))
                        }}
                    >
                        {t('browse.select_all')}
                    </button>
                </div>
            </dialog>
            {/* Opened from the router's leave guard, in the middle of a Back: it takes no history entry of its own (ui/modalHistory.ts), or that push would cut the entry the router steps back to. */}
            <dialog class="confirm" data-no-history ref={discard} aria-labelledby="discard-title" onClose={() => (retryLeave = null)}>
                <h2 id="discard-title">{t('picker.discard_title')}</h2>
                <p>{t('picker.discard_body')}</p>
                <div class="confirm-buttons">
                    <button class="link" type="button" onClick={() => discard.close()}>{t('common.cancel')}</button>
                    <button
                        class="primary danger"
                        type="button"
                        onClick={() => {
                            const go = retryLeave
                            leaving = true
                            discard.close()
                            go?.()
                        }}
                    >
                        {t('picker.discard_yes')}
                    </button>
                </div>
            </dialog>
        </>
    )
}

function PickRowView(props: { chat: ChatItem; on: boolean; disabled: boolean; onToggle: () => void; onStar: () => void; onShown: () => void; onGone: () => void; onFocused: (el: HTMLElement) => void; onStarRef: (el: HTMLElement) => void; reason: string }) {
    // On screen: the picker counts it once it has stayed (the row is drawn only while it is near the screen).
    onMount(props.onShown)
    onCleanup(props.onGone)
    return (
        <div class="chat-row" onFocusIn={(e) => props.onFocused(e.target as HTMLElement)}>
            <label class="pick-chat">
                <input type="checkbox" class="chat-tick" checked={props.on} disabled={props.disabled} onChange={props.onToggle} aria-label={chatName(props.chat)} />
                <ChatPicture photo={props.chat.photo} name={chatName(props.chat)} self={props.chat.self} />
                <span class="chat-text">
                    <span class="chat-name">{chatName(props.chat)}</span>
                    {/* Why a Recommended chat is there, in words; the other rows keep the line's height so the list lays out the same. */}
                    <span class="muted chat-count">{props.reason || ' '}</span>
                </span>
            </label>
            <PickerStar chatId={props.chat.id} onStar={props.onStar} onRef={props.onStarRef} />
        </div>
    )
}

/** A chat's star, as Chats.tsx's own StarButton has it (kept here too: that one is this file's own screen, not a shared component). */
function PickerStar(props: { chatId: number; onStar: () => void; onRef: (el: HTMLElement) => void }) {
    const on = () => starredIds().has(props.chatId)
    const label = () => (on() ? t('chats.unfavourite') : t('chats.favourite'))
    return (
        <button class="icon-button star" ref={props.onRef} classList={{ on: on() }} type="button" aria-label={label()} title={label()} onClick={() => { props.onStar(); setStarred(props.chatId, !on()) }}>
            <Icon icon={StarIcon} size={22} selected={on()} />
        </button>
    )
}
