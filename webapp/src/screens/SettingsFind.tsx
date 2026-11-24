// Settings' search box, the way to a row and What's new (WB20, U20). The apps' rules pick the hits
// (the worker's settingsSearch over core's SettingsMap), ui/settingsMap.ts says which rows this
// page draws and where; this file is the box, the results and the focus that follows a result.
import { createEffect, createResource, createSignal, For, on, onCleanup, Show } from 'solid-js'
import { A, useLocation, useNavigate, useParams } from '@solidjs/router'
import { ArrowRight01Icon, Cancel01Icon, CheckmarkCircle02Icon, Search01Icon, StarIcon } from '@hugeicons/core-free-icons'
import { catalogUrl, language, t } from '../i18n'
import { api } from '../tg/rpc'
import { form } from '../form'
import { Icon, type IconData } from '../ui/Icon'
import { coreForm, mergeHits, ownHits, pageFor, PAGE_TITLES, QUERY_MAX, rowFor, shownRows, type MapRow, type PageId, type RowContext } from '../ui/settingsMap'
import './settingsFind.css'

type Key = Parameters<typeof t>[0]
/** A catalog key held as plain text in the map. */
const say = (key: string): string => t(key as Key)

/** The id the page gives a row's element, so a result and an address can find it. */
export const rowDomId = (id: string) => `row-${id}`

// The search

const [query, setQuery] = createSignal('')
const [hits, setHits] = createSignal<MapRow[] | null>(null)
/** The rows the box found: null while nothing is typed, [] for a search that matched nothing. */
export const found = hits
/** What is typed, without the blanks around it. */
export const typed = () => query().trim().slice(0, QUERY_MAX)
/** Results are showing in place of the list of pages. */
export const showingResults = () => (hits()?.length ?? 0) > 0

/** One request to move to a row: a result chosen, or the address. A counter, so the same row twice still moves. */
const [request, setRequest] = createSignal<{ id: string; n: number } | null>(null)
let requests = 0
export const goToRow = (id: string) => setRequest({ id, n: ++requests })

const SEARCH_WAIT_MS = 150

/** Where a row is: its page's address and its anchor; a row of the list (Sign out) has no page of its own. */
const addressOf = (row: MapRow) => (row.page ? `/settings/${row.page}#${row.id}` : null)

/**
 * The box (a phone's list and a desktop's list column) and, when it found rows, their list. A
 * search that matches nothing says so and leaves the pages under it.
 */
export function SettingsSearch(props: { context: () => RowContext; icons: Record<PageId, IconData> }) {
    const navigate = useNavigate()
    let input!: HTMLInputElement
    let results: HTMLDivElement | undefined
    let generation = 0
    let timer: ReturnType<typeof setTimeout> | undefined

    const search = async (words: string) => {
        const mine = ++generation
        const context = props.context()
        const shown = shownRows(context)
        const own = ownHits(words, shown, say)
        let apps: string[] = []
        try {
            apps = await api.settingsSearch(words, coreForm(context.form), shown.filter((r) => r.core).map((r) => r.id), language(), catalogUrl(language()))
        } catch (e) {
            // The worker is not answering: the rows the web matches itself still list.
            console.warn('[settings] search', e)
        }
        if (mine === generation) setHits(mergeHits(apps, own, shown))
    }

    createEffect(
        on(
            () => [typed(), language(), props.context()] as const,
            ([words]) => {
                clearTimeout(timer)
                if (!words) {
                    generation++
                    setHits(null)
                    return
                }
                timer = setTimeout(() => void search(words), SEARCH_WAIT_MS)
            },
        ),
    )
    onCleanup(() => {
        clearTimeout(timer)
        generation++
    })

    const open = (row: MapRow) => {
        const address = addressOf(row)
        if (address) navigate(address, { replace: false })
        goToRow(row.id)
    }
    const clear = () => {
        setQuery('')
        input.focus()
    }
    const links = () => [...(results?.querySelectorAll<HTMLElement>('.set-row') ?? [])]
    const onInputKey = (e: KeyboardEvent) => {
        if (e.isComposing) return
        if (e.key === 'ArrowDown') {
            const first = links()[0]
            if (first) {
                e.preventDefault()
                first.focus()
            }
        } else if (e.key === 'Enter') {
            const first = hits()?.[0]
            if (first) {
                e.preventDefault()
                open(first)
            }
        } else if (e.key === 'Escape' && query()) {
            e.preventDefault()
            e.stopPropagation()
            clear()
        }
    }
    const onResultKey = (e: KeyboardEvent) => {
        if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return
        const all = links()
        const at = all.indexOf(document.activeElement as HTMLElement)
        if (at < 0) return
        e.preventDefault()
        if (e.key === 'ArrowDown') all[at + 1]?.focus()
        else if (at === 0) input.focus()
        else all[at - 1].focus()
    }

    return (
        <div class="set-find" role="search">
            <label class="search-field set-search-field">
                <Icon icon={Search01Icon} size={20} />
                <input
                    ref={input}
                    type="search"
                    enterkeyhint="search"
                    autocomplete="off"
                    spellcheck={false}
                    maxLength={QUERY_MAX}
                    placeholder={t('settings.search')}
                    aria-label={t('settings.search')}
                    value={query()}
                    onInput={(e) => setQuery(e.currentTarget.value)}
                    onKeyDown={onInputKey}
                />
                <Show when={query()}>
                    <button class="search-clear" type="button" aria-label={t('settings.search_clear')} title={t('settings.search_clear')} onClick={clear}>
                        <span class="search-clear-disc"><Icon icon={Cancel01Icon} size={14} /></span>
                    </button>
                </Show>
            </label>
            <Show when={found()?.length === 0}>
                <p class="muted small set-find-none" role="status">{t('settings.search_none', { query: typed().slice(0, 40) })}</p>
            </Show>
            <Show when={showingResults()}>
                <div class="set-card set-results" ref={results} onKeyDown={onResultKey}>
                    <For each={found()!}>
                        {(row) => (
                            <Show
                                when={addressOf(row)}
                                fallback={
                                    <button type="button" class="set-row" onClick={() => open(row)}>
                                        <ResultBody row={row} icon={StarIcon} />
                                    </button>
                                }
                            >
                                {(address) => (
                                    <A class="set-row" href={address()} onClick={() => goToRow(row.id)}>
                                        <ResultBody row={row} icon={props.icons[row.page!]} />
                                    </A>
                                )}
                            </Show>
                        )}
                    </For>
                </div>
            </Show>
        </div>
    )
}

function ResultBody(props: { row: MapRow; icon: IconData }) {
    return (
        <>
            <span class="set-icon">
                <Icon icon={props.icon} size={22} />
            </span>
            <span class="set-text">
                <span class="set-title">{say(props.row.title)}</span>
                <span class="set-sub">{props.row.page ? say(PAGE_TITLES[props.row.page]) : t('nav.settings')}</span>
            </span>
        </>
    )
}

// Getting to a row

const FOCUSABLE = 'a[href], button, input, select, textarea, [tabindex]'
const FOUND_MS = 2400
let foundTimer: ReturnType<typeof setTimeout> | undefined

/** Moves the keyboard to a row and marks it for a moment; false while the row is not drawn (yet). */
export function focusRow(id: string): boolean {
    const el = document.getElementById(rowDomId(id))
    if (!el) return false
    // A control is itself the place; a block with a choice puts the keyboard on the choice made; any other block takes it whole.
    let target: HTMLElement = el
    if (!el.matches(FOCUSABLE)) {
        target = el.querySelector<HTMLElement>('input:checked, [aria-pressed="true"]') ?? el
        if (target === el) el.tabIndex = -1
    }
    document.querySelectorAll('.set-found').forEach((x) => x.classList.remove('set-found'))
    el.classList.add('set-found')
    clearTimeout(foundTimer)
    foundTimer = setTimeout(() => el.classList.remove('set-found'), FOUND_MS)
    target.focus({ preventScroll: true })
    try {
        el.scrollIntoView({ block: 'center' })
    } catch {}
    return true
}

/** Tries each frame for a little while: a page opened a moment ago draws its rows after this call. */
function focusRowSoon(id: string) {
    let frames = 0
    const tick = () => {
        if (focusRow(id) || ++frames > 30) return
        requestAnimationFrame(tick)
    }
    requestAnimationFrame(tick)
}

/**
 * Settings' way to a row: the address's `#anchor` (a link from elsewhere, a page reload) and a
 * result chosen in the box. A row on another page than the address names takes the viewer to its
 * page; a TV, with one page, scrolls to it. An anchor that names no row is ignored.
 */
export function useRowTarget() {
    const location = useLocation()
    const params = useParams<{ page?: string }>()
    const navigate = useNavigate()
    let handled = ''
    const go = (id: string) => {
        const row = rowFor(id)
        if (!row) return
        const here = pageFor(params.page)
        if (form() !== 'tv' && row.page && here !== row.page) {
            navigate(`/settings/${row.page}#${row.id}`, { replace: true })
            return
        }
        focusRowSoon(row.id)
    }
    const anchor = () => {
        try {
            return decodeURIComponent(location.hash.slice(1))
        } catch {
            return ''
        }
    }
    // The address: once for each address, so a page's own redraw does not take the keyboard back.
    createEffect(() => {
        const id = anchor()
        const key = `${location.pathname}#${id}`
        if (!id || key === handled) return
        handled = key
        go(id)
    })
    createEffect(
        on(request, (r) => {
            if (r) go(r.id)
        }, { defer: true }),
    )
}

// What's new

/**
 * What the apps' "What's new" sheet and Changelog row say for this build, from the rules (the
 * version, the address and the lines in the shown language). One resource for both rows. When the
 * worker does not answer, the What's new row stays, saying Try again, and a press asks again.
 */
function useNews() {
    const [news, { refetch }] = createResource(language, async (tag) => {
        try {
            return await api.whatsNew(tag, catalogUrl(tag))
        } catch (e) {
            console.warn('[settings] whats new', e)
            return null
        }
    })
    return { news, retry: () => void refetch() }
}

/**
 * About's two rows from the rules (What's new and Changelog) and the sheet What's new opens: a row
 * in a phone's or a desktop's list, a button and a link in the TV's panel.
 */
export function AboutNews(props: { variant: 'row' | 'tv'; icon: IconData; changelogIcon: IconData }) {
    const { news, retry } = useNews()
    let dialog!: HTMLDialogElement
    const failed = () => news.state === 'ready' && news() === null
    const open = () => {
        if (failed()) return retry()
        if (!news()) return
        if (typeof dialog.showModal === 'function') {
            if (!dialog.open) dialog.showModal()
        } else dialog.setAttribute('open', '')
    }
    const close = () => (typeof dialog.close === 'function' ? dialog.close() : dialog.removeAttribute('open'))
    const detail = () => (failed() ? t('common.try_again') : news() ? t('settings.whats_new_detail', { version: news()!.version }) : '')
    return (
        <>
            <Show
                when={props.variant === 'row'}
                fallback={
                    <>
                        <button id={rowDomId('whats_new')} class="outline-button" style={{ 'align-self': 'start' }} type="button" aria-busy={news.loading} onClick={open}>
                            {failed() ? t('common.try_again') : t('settings.whats_new')}
                        </button>
                        <Show when={news()}>
                            {(n) => <a class="link" id={rowDomId('changelog')} href={n().changelog} target="_blank" rel="noopener">{t('settings.changelog')}</a>}
                        </Show>
                    </>
                }
            >
                <button id={rowDomId('whats_new')} type="button" class="set-row" aria-busy={news.loading} onClick={open}>
                    <span class="set-icon">
                        <Icon icon={props.icon} size={22} />
                    </span>
                    <span class="set-text">
                        <span class="set-title">{t('settings.whats_new')}</span>
                        <Show when={detail()}>
                            <span class="set-sub" classList={{ 'caution-text': failed() }} role={failed() ? 'alert' : undefined}>{detail()}</span>
                        </Show>
                    </span>
                </button>
                <Show when={news()}>
                    {(n) => (
                        <a class="set-row" id={rowDomId('changelog')} href={n().changelog} target="_blank" rel="noopener">
                            <span class="set-icon">
                                <Icon icon={props.changelogIcon} size={22} />
                            </span>
                            <span class="set-text">
                                <span class="set-title">{t('settings.changelog')}</span>
                                <span class="set-sub">{t('settings.changelog_detail')}</span>
                            </span>
                            <Icon icon={ArrowRight01Icon} size={20} class="set-chevron" />
                        </a>
                    )}
                </Show>
            </Show>
            <Show when={news()}>
                {(n) => (
                    <dialog class="confirm whatsnew" ref={dialog} aria-labelledby="whatsnew-title" onClick={(e) => { if (e.target === dialog) close() }}>
                        <h2 id="whatsnew-title">{t('whatsnew.title', { version: n().version })}</h2>
                        <p class="muted">{t('whatsnew.subtitle')}</p>
                        <For each={n().sections}>
                            {(section) => (
                                <>
                                    <h3 class="whatsnew-heading">{section.title}</h3>
                                    <ul class="whatsnew-list">
                                        <For each={section.lines}>
                                            {(line) => (
                                                <li>
                                                    <Icon icon={CheckmarkCircle02Icon} size={22} class="whatsnew-tick" />
                                                    <span>{line}</span>
                                                </li>
                                            )}
                                        </For>
                                    </ul>
                                </>
                            )}
                        </For>
                        <div class="confirm-buttons whatsnew-buttons">
                            <a class="link" href={n().changelog} target="_blank" rel="noopener">{t('whatsnew.full_changelog')}</a>
                            <button class="primary" type="button" onClick={close}>{t('common.close')}</button>
                        </div>
                    </dialog>
                )}
            </Show>
        </>
    )
}
