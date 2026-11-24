import { createEffect, createResource, createSignal, For, on, onCleanup, onMount, Show } from 'solid-js'
import { A, useNavigate, useSearchParams } from '@solidjs/router'
import { api, errorText } from '../tg/rpc'
import { onBackOnline } from '../net'
import { Cancel01Icon, Search01Icon } from '@hugeicons/core-free-icons'
import { t } from '../i18n'
import { addedIds, addedMode, addedView, clearSearches, forgetSearch, noteSearch, progress, recentSearches, settings } from '../store'
import { locked } from '../accounts'
import { showToast } from '../ui/Toast'
import { Icon } from '../ui/Icon'
import { back, entryState } from '../session'
import { dropsAddressSearch, keepsSearch, loadType } from '../ui/searchLifetime'
import { SearchTopics } from './SearchTopics'
import { tileCard, VideoList } from './VideoList'

/** Whether this page load has already shown the search: a reload or a new tab starts with an empty box, though the address may still carry `?q=`. The app's own link to it ("Find in other chats") brings its words (dropsAddressSearch). */
let shownThisLoad = false

/** Videos across every chat, grouped into shows, copies and pieces as a chat's are. The words are in the address (`?q=`), so Back returns to the same results. */
export function Search() {
    const [params, setParams] = useSearchParams<{ q?: string }>()
    const navigate = useNavigate()
    // A fresh page load (reload, a pasted address, a restored tab) never brings an old search back; a link from inside the app does bring its words.
    const stale = dropsAddressSearch({ shownThisLoad, words: !!params.q, entry: !!entryState(), loadType: loadType() })
    shownThisLoad = true
    const [text, setText] = createSignal(stale ? '' : (params.q ?? ''))
    let input: HTMLInputElement | undefined
    // "Search my other chats" (HX62): the same words over every chat, once. The next words go back to the added chats.
    const [everyChatFor, setEveryChatFor] = createSignal('')
    const everyChat = () => !!params.q && everyChatFor() === params.q.trim()
    const [results, { refetch }] = createResource(
        () => {
            const q = params.q?.trim()
            // The added chats travel with every ask, even one the gate itself did not change (a watched tick): `addedView()` is cheap.
            return q ? ([q, addedMode(), [...(addedIds() ?? [])].join(), everyChat()] as const) : false
        },
        // A search names the video the viewer is after, so the size floor stands aside for it (as the apps' chat search).
        async ([q, , , wide]) => api.search(q, { minBytes: 0, maxBytes: Number.MAX_SAFE_INTEGER, added: addedView(), ...(await progress()) }, wide),
    )
    onBackOnline(() => results.error && refetch())
    let debounce = 0
    let typing = false
    onCleanup(() => clearTimeout(debounce))
    // Clearing empties the box, the address's words and so the results, the note under them and the other-chats ask at once, and puts the cursor back in the box.
    const clear = () => {
        clearTimeout(debounce)
        typing = false
        setText('')
        setEveryChatFor('')
        setParams({ q: undefined }, { replace: true })
        input?.focus()
    }
    onMount(() => {
        if (stale) setParams({ q: undefined }, { replace: true })
        // Back in front after more than two minutes: a fresh box. A tab that was only glanced away from keeps it.
        let hiddenAt: number | null = null
        const onVisible = () => {
            if (document.visibilityState === 'hidden') {
                hiddenAt = Date.now()
                return
            }
            const was = hiddenAt
            hiddenAt = null
            if (!keepsSearch(was, Date.now())) clear()
        }
        document.addEventListener('visibilitychange', onVisible)
        onCleanup(() => document.removeEventListener('visibilitychange', onVisible))
    })
    // Escape empties the box first and leaves the page on an empty one (there is no close button: the browser's Back leaves too).
    const onKey = (e: KeyboardEvent) => {
        if (e.key !== 'Escape') return
        e.preventDefault()
        if (text()) clear()
        else back(navigate)
    }
    const onType = (v: string) => {
        setText(v)
        typing = true
        clearTimeout(debounce)
        // Typing replaces the entry instead of adding one per word.
        debounce = window.setTimeout(() => {
            typing = false
            setParams({ q: v || undefined }, { replace: true })
            // A search that has settled is remembered (when remembering is on), newest first.
            if (v.trim()) noteSearch(v)
        }, 350)
    }
    // A recent search run again: its words in the box and in the address, and to the front of the list.
    const rerun = (q: string) => {
        clearTimeout(debounce)
        typing = false
        setText(q)
        setParams({ q }, { replace: true })
        noteSearch(q)
        input?.focus()
    }
    const clearRecents = () => {
        clearSearches()
        showToast({ kind: 'success', text: t('search.recent_cleared') })
        input?.focus()
    }
    const recents = () => (!locked() && !text().trim() ? recentSearches() : [])
    // Back or Forward to other words puts them in the box, unless the box is being typed in.
    createEffect(on(() => params.q, (q) => !typing && setText(q ?? ''), { defer: true }))
    const found = () => (params.q?.trim() && !results.error ? results.latest : undefined)
    return (
        <>
            <h1 class="page-title">{t('common.search')}</h1>
            <label class="search-field">
                <Icon icon={Search01Icon} size={20} />
                <input ref={input} type="search" placeholder={t('search.field_hint')} aria-label={t('search.field_hint')} value={text()} onInput={(e) => onType(e.currentTarget.value)} onKeyDown={onKey} autofocus />
                <Show when={text()}>
                    <button class="search-clear" type="button" aria-label={t('browse.clear_search')} title={t('browse.clear_search')} onClick={clear}>
                        <span class="search-clear-disc"><Icon icon={Cancel01Icon} size={14} /></span>
                    </button>
                </Show>
            </label>
            {/* Recent searches (WP-59): under an empty box, one tap runs one again; Clear and a remove button on each. */}
            <Show when={recents().length}>
                <section class="search-recents" aria-labelledby="search-recents-title">
                    <div class="search-recents-head">
                        <h2 id="search-recents-title">{t('browse.recent_searches')}</h2>
                        <button class="link" type="button" onClick={clearRecents}>{t('grid.clear_recent_searches')}</button>
                    </div>
                    <ul class="search-recents-list">
                        <For each={recents()}>
                            {(q) => (
                                <li class="recent-chip">
                                    <button class="recent-run" type="button" onClick={() => rerun(q)}>{q}</button>
                                    <button class="recent-remove" type="button" aria-label={t('search.remove_recent', { query: q })} title={t('search.remove_recent', { query: q })} onClick={() => forgetSearch(q)}>
                                        <Icon icon={Cancel01Icon} size={14} />
                                    </button>
                                </li>
                            )}
                        </For>
                    </ul>
                </section>
            </Show>
            <Show when={results.error}>
                <div class="notice error-notice">
                    <p class="error" role="alert">{errorText(results.error)}</p>
                    <button class="link" type="button" onClick={refetch}>{t('common.try_again')}</button>
                </div>
            </Show>
            {/* One live region that stays, so a screen reader hears the count, the wait or the empty answer as it changes. */}
            <div class="search-status" role="status">
                <Show when={results.loading && !found()}>
                    <p class="muted">{t('browse.finding_videos')}</p>
                </Show>
                <Show when={found() && !results.loading && found()!.tiles.length}>
                    <p class="muted search-count">{t('search.count', { videos: found()!.videos, chats: found()!.chats })}</p>
                </Show>
                <Show when={found() && !results.loading && !found()!.tiles.length}>
                    <p class="muted">{t('search.none', { query: params.q!.trim() })}</p>
                    <Show when={found()!.addedOnly}>
                        <p class="muted">{t('search.none_added')}</p>
                    </Show>
                    <A class="link" href="/added-chats">{t('picker.add_more')}</A>
                </Show>
            </div>
            <SearchTopics words={params.q} everyChat={everyChat()} />
            <VideoList cards={found()?.tiles.map((tile) => tileCard(tile, true)) ?? []} label={t('common.search')} />
            {/* The ways to search further come after the results, so Down from the field reaches the first video on a
                remote and nothing that can take the focus stands between them (tv.spec, Search). */}
            <Show when={found() && !results.loading && found()!.addedOnly && !everyChat()}>
                <button class="link" type="button" onClick={() => setEveryChatFor(params.q!.trim())}>{t('search.other_chats')}</button>
            </Show>
            <Show when={found() && !results.loading && !found()!.addedOnly && found()!.tiles.length}>
                <p class="muted">{t('search.every_chat_note')}</p>
                <A class="link" href="/added-chats">{t('picker.add_more')}</A>
            </Show>
        </>
    )
}
