import { createEffect, createResource, createSignal, on, onCleanup, Show } from 'solid-js'
import { useSearchParams } from '@solidjs/router'
import { api, errorText } from '../tg/rpc'
import { onBackOnline } from '../net'
import { Search01Icon } from '@hugeicons/core-free-icons'
import { t } from '../i18n'
import { addedIds, addedMode, addedView, progress, settings } from '../store'
import { Icon } from '../ui/Icon'
import { HiddenBySize, tileCard, VideoList } from './VideoList'

/** Videos across every chat, grouped into shows, copies and pieces as a chat's are. The words are in the address (`?q=`), so Back returns to the same results. */
export function Search() {
    const [params, setParams] = useSearchParams<{ q?: string }>()
    const [text, setText] = createSignal(params.q ?? '')
    const [results, { refetch }] = createResource(
        () => {
            const q = params.q?.trim()
            // The added chats travel with every ask, even one the gate itself did not change (a watched tick): `addedView()` is cheap.
            return q ? ([q, settings().sizeMin, settings().sizeMax, addedMode(), [...(addedIds() ?? [])].join()] as const) : false
        },
        async ([q, minBytes, maxBytes]) => api.search(q, { minBytes, maxBytes, added: addedView(), ...(await progress()) }),
    )
    onBackOnline(() => results.error && refetch())
    let debounce = 0
    let typing = false
    onCleanup(() => clearTimeout(debounce))
    const onType = (v: string) => {
        setText(v)
        typing = true
        clearTimeout(debounce)
        // Typing replaces the entry instead of adding one per word.
        debounce = window.setTimeout(() => {
            typing = false
            setParams({ q: v || undefined }, { replace: true })
        }, 350)
    }
    // Back or Forward to other words puts them in the box, unless the box is being typed in.
    createEffect(on(() => params.q, (q) => !typing && setText(q ?? ''), { defer: true }))
    const found = () => (params.q?.trim() && !results.error ? results.latest : undefined)
    return (
        <>
            <h1 class="page-title">{t('common.search')}</h1>
            <label class="search-field">
                <Icon icon={Search01Icon} size={20} />
                <input type="search" placeholder={t('search.field_hint')} aria-label={t('search.field_hint')} value={text()} onInput={(e) => onType(e.currentTarget.value)} autofocus />
            </label>
            <Show when={results.error}>
                <div class="notice error-notice">
                    <p class="error">{errorText(results.error)}</p>
                    <button class="link" type="button" onClick={refetch}>{t('common.try_again')}</button>
                </div>
            </Show>
            <Show when={results.loading && !found()}>
                <p class="muted">{t('browse.finding_videos')}</p>
            </Show>
            <HiddenBySize count={found()?.hiddenBySize ?? 0} />
            <Show when={found() && !results.loading && !found()!.tiles.length}>
                <p class="muted">{t('search.none', { query: params.q!.trim() })}</p>
            </Show>
            <VideoList cards={found()?.tiles.map((tile) => tileCard(tile, true)) ?? []} label={t('common.search')} />
        </>
    )
}
