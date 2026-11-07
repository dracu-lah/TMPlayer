// The menu a video card opens on a long press (touch) or a right click: the actions the apps' hold
// menu has (Play, Details, Watched, Watch later) and, on a Continue watching or Watched card, Remove
// from history, which the toast's Undo takes back. One menu for the whole page. The browser's own
// right click menu is turned off page wide (blockBrowserMenu) so a right click feels like the app's.
import { createEffect, createSignal, Show } from 'solid-js'
import { useNavigate } from '@solidjs/router'
import { Clock01Icon, Delete02Icon, InformationCircleIcon, PlayIcon, Search01Icon, Tick02Icon } from '@hugeicons/core-free-icons'
import { t } from '../i18n'
import { historyId, removeWithUndo } from '../historyEdits'
import { laterIds, setLater, setWatched, watchedIds, watchedTick } from '../store'
import { Icon } from './Icon'
import { openPosterPicker } from './PosterPicker'
import type { VideoItem } from '../tg/types'

interface Target {
    item: VideoItem
    /** A history card: Remove from history is offered too. */
    history: boolean
}

const [target, setTarget] = createSignal<Target | null>(null)

const HOLD_MS = 500
const SLOP = 10

/**
 * Handlers for a card's link. A long press with a finger or a right click opens the menu; the click
 * that follows a long press is swallowed so the card does not also open.
 */
export function cardMenu(item: VideoItem, history = false) {
    let timer = 0
    let x = 0
    let y = 0
    let held = false
    const stop = () => clearTimeout(timer)
    const open = () => setTarget({ item, history })
    return {
        'on:contextmenu': (e: MouseEvent) => {
            e.preventDefault()
            open()
        },
        'on:pointerdown': (e: PointerEvent) => {
            if (e.pointerType === 'mouse') return
            held = false
            x = e.clientX
            y = e.clientY
            stop()
            timer = window.setTimeout(() => {
                held = true
                open()
            }, HOLD_MS)
        },
        'on:pointermove': (e: PointerEvent) => {
            if (Math.abs(e.clientX - x) > SLOP || Math.abs(e.clientY - y) > SLOP) stop()
        },
        'on:pointerup': stop,
        'on:pointercancel': stop,
        'on:click': (e: MouseEvent) => {
            if (!held) return
            held = false
            e.preventDefault()
            e.stopPropagation()
        },
    }
}

/**
 * Turns the browser's right click menu off for the whole page, as an app has none: a card opens its
 * own menu instead (cardMenu), and anywhere else the click does nothing. Left alone where a menu is
 * useful: text fields and a text selection (Copy, Paste).
 */
export function blockBrowserMenu(doc: Document = document) {
    doc.addEventListener('contextmenu', (e) => {
        if (e.defaultPrevented) return
        const el = e.target instanceof Element ? e.target : null
        if (el?.closest('input, textarea, select, [contenteditable=""], [contenteditable="true"]')) return
        if (doc.getSelection()?.toString()) return
        e.preventDefault()
    })
}

export function CardMenu() {
    let dialog!: HTMLDialogElement
    const navigate = useNavigate()
    createEffect(() => {
        if (target() && !dialog.open) dialog.showModal()
        else if (!target() && dialog.open) dialog.close()
    })
    // Watched or not, read when the menu opens and again after a change (the tick).
    const [watched, setWatchedNow] = createSignal(false)
    createEffect(() => {
        const now = target()
        watchedTick()
        if (!now) return
        void watchedIds().then((ids) => {
            if (target() === now) setWatchedNow(ids.includes(historyId(now.item)))
        })
    })
    const close = () => setTarget(null)
    const go = (path: string) => {
        const now = target()
        close()
        if (now) navigate(path, { state: { video: now.item } })
    }
    const toggleWatched = () => {
        const now = target()
        close()
        if (now) setWatched(now.item.chatId, now.item.messageId, !watched())
    }
    const toggleLater = () => {
        const now = target()
        close()
        if (now) setLater(now.item, !laterIds().has(historyId(now.item)))
    }
    const choosePoster = () => {
        const now = target()
        close()
        if (now) openPosterPicker(now.item)
    }
    const remove = () => {
        const now = target()
        close()
        if (now) void removeWithUndo([historyId(now.item)])
    }
    return (
        <dialog class="confirm card-menu" ref={dialog} aria-label={t('detail.open_details')} onClose={close} onClick={(e) => e.target === dialog && close()}>
            <Show when={target()}>
                {(now) => (
                    <>
                        <h2>{now().item.name}</h2>
                        <button class="menu-row" type="button" onClick={() => go(`/watch/${now().item.chatId}/${now().item.messageId}`)}>
                            <Icon icon={PlayIcon} size={22} />
                            <span>{t('common.play')}</span>
                        </button>
                        <button class="menu-row" type="button" onClick={() => go(`/video/${now().item.chatId}/${now().item.messageId}`)}>
                            <Icon icon={InformationCircleIcon} size={22} />
                            <span>{t('detail.open_details')}</span>
                        </button>
                        <button class="menu-row" type="button" onClick={toggleWatched}>
                            <Icon icon={Tick02Icon} size={22} />
                            <span>{watched() ? t('detail.round_unwatched') : t('detail.round_watched')}</span>
                        </button>
                        <button class="menu-row" type="button" onClick={toggleLater}>
                            <Icon icon={Clock01Icon} size={22} />
                            <span>{laterIds().has(historyId(now().item)) ? t('detail.watch_later_remove') : t('detail.watch_later_add')}</span>
                        </button>
                        <button class="menu-row" type="button" onClick={choosePoster}>
                            <Icon icon={Search01Icon} size={22} />
                            <span>
                                {t('poster.choose')}
                                <small>{t('poster.choose_detail')}</small>
                            </span>
                        </button>
                        <Show when={now().history}>
                            <button class="menu-row" type="button" onClick={remove}>
                                <Icon icon={Delete02Icon} size={22} />
                                <span>
                                    {t('browse.remove_from_history')}
                                    <small>{t('browse.remove_from_history_detail')}</small>
                                </span>
                            </button>
                        </Show>
                        <div class="confirm-buttons">
                            <button class="link" type="button" onClick={close}>{t('common.cancel')}</button>
                        </div>
                    </>
                )}
            </Show>
        </dialog>
    )
}
