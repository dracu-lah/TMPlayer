// The viewer's named lists (HX43) on the web, as the apps have them: "Add to a list" from a card's
// menu or a video's or a show's page (ListPicker), and the lists' page at /lists[/id]: a chip per
// list, Rename, Show on Home, Delete (asked first), and the list's cards grouped as a chat's grid
// (a show is one card), in the order added, each with Remove from list under it.
import { createEffect, createResource, createSignal, For, on, Show } from 'solid-js'
import { A, useNavigate, useParams } from '@solidjs/router'
import { Add01Icon, CheckmarkCircle02Icon, Delete02Icon, LeftToRightListBulletIcon } from '@hugeicons/core-free-icons'
import { call } from '../tg/rpc'
import { t } from '../i18n'
import { addToList, createList, deleteList, namedLists, removeFromList, renameList, setListHome } from '../store'
import { Icon } from '../ui/Icon'
import { showToast } from '../ui/Toast'
import { cleanName, lineKey, MAX_LISTS, NAME_MAX, type AddResult } from '../ui/lists'
import { Card, tileCard, useProgress } from './VideoList'
import type { Tile, VideoItem } from '../tg/types'

interface Picking {
    item: VideoItem
    /** A whole show: its name, as Watch later keeps a show. */
    show?: string
}

const [picking, setPicking] = createSignal<Picking | null>(null)

/** Opens "Add to a list" for a video, or for the whole show it is an episode of. */
export const openListPicker = (item: VideoItem, show?: string) => setPicking({ item, show })

function said(result: AddResult, title: string, list: string): string {
    if (result === 'added') return t('lists.added', { title, list })
    if (result === 'already') return t('lists.already', { title, list })
    if (result === 'full') return t('lists.full', { list })
    return t('lists.gone')
}

/** One for the whole page, beside the card menu: every list, ticked where the line is in it, then New list. */
export function ListPicker() {
    let dialog!: HTMLDialogElement
    const [naming, setNaming] = createSignal(false)
    const [name, setName] = createSignal('')
    createEffect(() => {
        if (picking() && !dialog.open) dialog.showModal()
        else if (!picking() && dialog.open) dialog.close()
    })
    const close = () => {
        setPicking(null)
        setNaming(false)
        setName('')
    }
    const title = (p: Picking) => p.show ?? p.item.name ?? p.item.title
    const keyOf = (p: Picking) => lineKey({ item: p.item, show: p.show?.trim().toLowerCase() })
    const pick = (id: string, listName: string, inIt: boolean) => {
        const p = picking()
        close()
        if (!p) return
        if (inIt) {
            removeFromList(id, [keyOf(p)])
            showToast({ text: t('lists.removed', { title: title(p), list: listName }) })
        } else {
            showToast({ text: said(addToList(id, p.item, p.show), title(p), listName) })
        }
    }
    const make = (e: Event) => {
        e.preventDefault()
        const p = picking()
        const made = createList(name())
        close()
        if (!p) return
        if (!made) showToast({ text: namedLists().length >= MAX_LISTS ? t('lists.too_many', { count: MAX_LISTS }) : t('lists.name_empty') })
        else showToast({ text: said(addToList(made.id, p.item, p.show), title(p), made.name) })
    }
    return (
        <dialog class="confirm card-menu" ref={dialog} aria-label={t('lists.add_to')} onClose={close} onClick={(e) => e.target === dialog && close()}>
            <Show when={picking()}>
                {(p) => (
                    <Show
                        when={!naming()}
                        fallback={
                            <form onSubmit={make}>
                                <h2>{t('lists.new')}</h2>
                                <label class="list-name">
                                    <span>{t('lists.name')}</span>
                                    <input type="text" maxLength={NAME_MAX} value={name()} onInput={(e) => setName(e.currentTarget.value)} autofocus data-tv-first />
                                </label>
                                <p class="muted small">{t('lists.name_hint')}</p>
                                <div class="confirm-buttons">
                                    <button class="link" type="button" onClick={close}>{t('common.cancel')}</button>
                                    <button class="primary" type="submit" disabled={!cleanName(name())}>{t('lists.create')}</button>
                                </div>
                            </form>
                        }
                    >
                        <h2>{t('lists.add_to')}</h2>
                        <p class="muted">{title(p())}</p>
                        <Show when={!namedLists().length}>
                            <p class="muted">{t('lists.none')}</p>
                        </Show>
                        <For each={namedLists()}>
                            {(list) => {
                                const inIt = () => list.lines.some((l) => lineKey(l) === keyOf(p()))
                                return (
                                    <button class="menu-row" type="button" onClick={() => pick(list.info.id, list.info.name, inIt())}>
                                        <Icon icon={inIt() ? CheckmarkCircle02Icon : LeftToRightListBulletIcon} size={22} />
                                        <span>
                                            {list.info.name}
                                            <small>{inIt() ? t('lists.remove') : t('browse.videos_count', { count: list.lines.length })}</small>
                                        </span>
                                    </button>
                                )
                            }}
                        </For>
                        <Show when={namedLists().length < MAX_LISTS}>
                            <button class="menu-row" type="button" onClick={() => setNaming(true)}>
                                <Icon icon={Add01Icon} size={22} />
                                <span>
                                    {t('lists.new')}
                                    <small>{t('lists.name_hint')}</small>
                                </span>
                            </button>
                        </Show>
                        <div class="confirm-buttons">
                            <button class="link" type="button" onClick={close}>{t('common.cancel')}</button>
                        </div>
                    </Show>
                )}
            </Show>
        </dialog>
    )
}

/** The lists' page, at `/lists[/id]`. */
export function ListsPage() {
    const params = useParams<{ id?: string }>()
    const navigate = useNavigate()
    const shown = () => namedLists().find((l) => l.info.id === params.id) ?? namedLists()[0]
    // The list's cards from the videos kept with it, grouped by the app's rules in the worker.
    const [tiles] = createResource(
        () => shown()?.lines,
        (lines): Promise<Tile[]> => (lines.length ? call('listTiles', [lines.map((l) => ({ item: l.item, show: l.show }))]) : Promise.resolve([])),
    )
    const progress = useProgress()
    let renaming!: HTMLDialogElement
    let deleting!: HTMLDialogElement
    let making!: HTMLDialogElement
    const [name, setName] = createSignal('')
    createEffect(on(() => shown()?.info.id, (id) => id && id !== params.id && navigate(`/lists/${id}`, { replace: true }), { defer: true }))
    // The lines a card stands for: a show's card takes its episodes with it.
    const keysOf = (tile: Tile) => {
        const list = shown()
        if (!list) return []
        if (tile.kind !== 'show') return [`${tile.item.chatId}:${tile.item.messageId}`]
        const show = tile.title.trim().toLowerCase()
        return list.lines.filter((l) => l.item.chatId === tile.item.chatId && (l.show === show || (!l.show && (l.item.name ?? '').toLowerCase().includes(show)))).map(lineKey)
    }
    const remove = (tile: Tile) => {
        const list = shown()
        if (!list) return
        removeFromList(list.info.id, keysOf(tile).length ? keysOf(tile) : [`${tile.item.chatId}:${tile.item.messageId}`])
        showToast({ text: t('lists.removed', { title: tile.title, list: list.info.name }) })
    }
    return (
        <>
            <h1 class="page-title">{t('lists.heading')}</h1>
            <nav class="history-tabs" aria-label={t('lists.heading')}>
                <For each={namedLists()}>
                    {(list) => (
                        <A class="history-tab" href={`/lists/${list.info.id}`} aria-current={shown()?.info.id === list.info.id ? 'page' : undefined} data-tv-first={shown()?.info.id === list.info.id || undefined}>
                            <Icon icon={LeftToRightListBulletIcon} size={16} />
                            {list.info.name}
                        </A>
                    )}
                </For>
                <Show when={namedLists().length < MAX_LISTS}>
                    <button class="history-tab" type="button" onClick={() => (setName(''), making.showModal())}>
                        <Icon icon={Add01Icon} size={16} />
                        {t('lists.new')}
                    </button>
                </Show>
            </nav>
            <Show when={shown()} fallback={<p class="muted">{t('lists.none')}</p>}>
                {(list) => (
                    <>
                        <div class="list-actions">
                            <button class="outline-button" type="button" onClick={() => (setName(list().info.name), renaming.showModal())}>{t('lists.rename')}</button>
                            <label class="switch-row">
                                <input type="checkbox" checked={list().info.home} onChange={(e) => setListHome(list().info.id, e.currentTarget.checked)} />
                                <span>{t('lists.on_home')}</span>
                            </label>
                            <button class="outline-button" type="button" onClick={() => deleting.showModal()}>
                                <Icon icon={Delete02Icon} size={18} />
                                {t('lists.delete')}
                            </button>
                        </div>
                        <Show when={list().lines.length} fallback={<p class="muted">{t('lists.empty')}</p>}>
                            <div class="cards">
                                <For each={tiles.latest ?? []}>
                                    {(tile) => (
                                        <div class="list-card">
                                            <Card card={tileCard(tile, true)} progress={progress(tile.item)} />
                                            <button class="link small" type="button" onClick={() => remove(tile)}>{t('lists.remove')}</button>
                                        </div>
                                    )}
                                </For>
                            </div>
                        </Show>
                        <dialog class="confirm" ref={deleting} aria-labelledby="list-delete-title" onClick={(e) => e.target === deleting && deleting.close()}>
                            <h2 id="list-delete-title">{t('lists.delete_title', { list: list().info.name })}</h2>
                            <p>{t('lists.delete_message', { count: list().lines.length })}</p>
                            <div class="confirm-buttons">
                                <button class="link" type="button" onClick={() => deleting.close()}>{t('common.cancel')}</button>
                                <button
                                    class="primary danger"
                                    type="button"
                                    onClick={() => {
                                        const gone = list().info
                                        deleting.close()
                                        deleteList(gone.id)
                                        showToast({ text: t('lists.deleted', { list: gone.name }) })
                                    }}
                                >
                                    {t('common.delete')}
                                </button>
                            </div>
                        </dialog>
                        <NameDialog
                            ref={(d) => (renaming = d)}
                            title={t('lists.rename')}
                            confirm={t('lists.save')}
                            name={name()}
                            onName={setName}
                            onSave={() => renameList(list().info.id, name())}
                        />
                    </>
                )}
            </Show>
            <NameDialog
                ref={(d) => (making = d)}
                title={t('lists.new')}
                confirm={t('lists.create')}
                name={name()}
                onName={setName}
                onSave={() => {
                    const made = createList(name())
                    if (made) navigate(`/lists/${made.id}`)
                    else showToast({ text: namedLists().length >= MAX_LISTS ? t('lists.too_many', { count: MAX_LISTS }) : t('lists.name_empty') })
                }}
            />
        </>
    )
}

function NameDialog(props: { ref: (d: HTMLDialogElement) => void; title: string; confirm: string; name: string; onName: (v: string) => void; onSave: () => void }) {
    let dialog!: HTMLDialogElement
    return (
        <dialog class="confirm" ref={(d) => ((dialog = d), props.ref(d))} onClick={(e) => e.target === dialog && dialog.close()}>
            <form
                onSubmit={(e) => {
                    e.preventDefault()
                    if (!cleanName(props.name)) return
                    dialog.close()
                    props.onSave()
                }}
            >
                <h2>{props.title}</h2>
                <label class="list-name">
                    <span>{t('lists.name')}</span>
                    <input type="text" maxLength={NAME_MAX} value={props.name} onInput={(e) => props.onName(e.currentTarget.value)} autofocus />
                </label>
                <p class="muted small">{t('lists.name_hint')}</p>
                <div class="confirm-buttons">
                    <button class="link" type="button" onClick={() => dialog.close()}>{t('common.cancel')}</button>
                    <button class="primary" type="submit" disabled={!cleanName(props.name)}>{props.confirm}</button>
                </div>
            </form>
        </dialog>
    )
}
