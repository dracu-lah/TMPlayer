// The menu a card opens on a long press (touch) or a right click: Choose the right poster on every
// card, and Remove from history on a History card (the toast's Undo takes it back). One menu for the page.
import { createEffect, createSignal, Show } from 'solid-js'
import { Delete02Icon, Search01Icon } from '@hugeicons/core-free-icons'
import { t } from '../i18n'
import { historyId, removeWithUndo } from '../historyEdits'
import { Icon } from './Icon'
import { openPosterPicker } from './PosterPicker'
import type { VideoItem } from '../tg/types'

const [target, setTarget] = createSignal<{ item: VideoItem; history: boolean } | null>(null)

const HOLD_MS = 500
const SLOP = 10

/**
 * Handlers for a card's link; `history` for a History card, whose menu also removes it. A long press
 * with a finger or a right click opens the menu; the click that follows a long press is swallowed so
 * the card does not also open.
 */
export function historyMenu(item: VideoItem, history = true) {
    let timer = 0
    let x = 0
    let y = 0
    let held = false
    const stop = () => clearTimeout(timer)
    return {
        'on:contextmenu': (e: MouseEvent) => {
            e.preventDefault()
            setTarget({ item, history })
        },
        'on:pointerdown': (e: PointerEvent) => {
            if (e.pointerType === 'mouse') return
            held = false
            x = e.clientX
            y = e.clientY
            stop()
            timer = window.setTimeout(() => {
                held = true
                setTarget({ item, history })
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

export function CardMenu() {
    let dialog!: HTMLDialogElement
    createEffect(() => {
        if (target() && !dialog.open) dialog.showModal()
        else if (!target() && dialog.open) dialog.close()
    })
    const remove = () => {
        const now = target()
        setTarget(null)
        if (now) void removeWithUndo([historyId(now.item)])
    }
    const choosePoster = () => {
        const now = target()
        setTarget(null)
        if (now) openPosterPicker(now.item)
    }
    return (
        <dialog class="confirm card-menu" ref={dialog} aria-label={t('poster.choose')} onClose={() => setTarget(null)} onClick={(e) => e.target === dialog && setTarget(null)}>
            <Show when={target()}>
                {(now) => (
                    <>
                        <h2>{now().item.name}</h2>
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
                            <button class="link" type="button" onClick={() => setTarget(null)}>{t('common.cancel')}</button>
                        </div>
                    </>
                )}
            </Show>
        </dialog>
    )
}
