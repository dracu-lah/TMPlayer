// "Rename" (the apps' rename sheet): a dialog with one field for the title the viewer wants to see
// for a video, Save, and "Use the file name" to take the rename off. The title is stored through
// copies.ts renameVideo (store, the Telegram worker, the sync file), for the video and its other
// copies so it holds whichever copy a card shows. One dialog for the whole page, mounted by the card
// menu and opened from the menu and from a video's page. Keyboard and remote: the field takes focus
// with its text selected, Enter saves, Escape (Back) closes and focus goes back to where it was.
import { createEffect, createSignal, Show } from 'solid-js'
import { t } from '../i18n'
import { renamedTitle, renameVideo } from '../copies'
import { cleanTitle, RENAME_MAX, saveOf, typed } from '../rename'
import { renameTarget as target, setRenameTarget as setTarget, type Ref } from './renameTarget'
import './rename.css'

/** How long Save waits for a card's copies before it saves what it has. */
const COPIES_WAIT_MS = 1_500

export function RenameSheet() {
    let dialog!: HTMLDialogElement
    let field: HTMLInputElement | undefined
    const [value, setValue] = createSignal('')
    // A second press while the first is written is the same press again.
    let done = false

    createEffect(() => {
        const now = target()
        if (now && !dialog.open) {
            done = false
            setValue(renamedTitle(now.item) ?? now.shown)
            dialog.showModal()
            field?.focus()
            field?.select()
        } else if (!now && dialog.open) dialog.close()
    })

    const action = () => {
        const now = target()
        return now ? saveOf(value(), now.shown, renamedTitle(now.item)) : null
    }
    const close = () => {
        const now = target()
        setTarget(null)
        const back = now?.back
        if (back instanceof HTMLElement && back.isConnected) queueMicrotask(() => back.focus())
    }
    const write = async (title: string | null) => {
        const now = target()
        if (!now || done) return
        done = true
        // The copies a card does not know are looked up; a slow lookup saves what is known, never holds the sheet.
        const others = await Promise.race([now.copies, new Promise<readonly Ref[]>((r) => setTimeout(() => r([]), COPIES_WAIT_MS))])
        renameVideo(now.item, title, others)
        close()
        if (title) now.after?.()
    }
    const save = (e: Event) => {
        e.preventDefault()
        const what = action()
        if (what === null) return close()
        void write(what === 'reset' ? null : cleanTitle(value()))
    }

    return (
        <dialog class="confirm rename-sheet" ref={dialog} aria-labelledby="rename-title" onClose={() => target() && close()} onClick={(e) => e.target === dialog && close()}>
            <Show when={target()}>
                <form onSubmit={save}>
                    <h2 id="rename-title">{t('grid.rename_title')}</h2>
                    <p class="muted small">{t('grid.rename_detail')}</p>
                    <label class="list-name">
                        <span>{t('grid.rename_hint')}</span>
                        <input
                            ref={field}
                            type="text"
                            dir="auto"
                            maxLength={RENAME_MAX}
                            autocomplete="off"
                            enterkeyhint="done"
                            spellcheck={false}
                            value={value()}
                            onInput={(e) => {
                                const text = typed(e.currentTarget.value)
                                if (text !== e.currentTarget.value) e.currentTarget.value = text
                                setValue(text)
                            }}
                        />
                    </label>
                    <p class="rename-count muted small" aria-live="polite">{`${[...value()].length} / ${RENAME_MAX}`}</p>
                    <div class="confirm-buttons rename-buttons">
                        <Show when={renamedTitle(target()!.item)}>
                            <button class="link rename-reset" type="button" onClick={() => void write(null)}>{t('grid.rename_reset')}</button>
                        </Show>
                        <button class="link" type="button" onClick={close}>{t('common.cancel')}</button>
                        <button class="primary" type="submit" disabled={action() === null}>{t('grid.rename_save')}</button>
                    </div>
                </form>
            </Show>
        </dialog>
    )
}
