// "Rename" (the apps' rename sheet): a dialog with one field for the title the viewer wants to see
// for a video, Save, and "Use the file name" to take the rename off. The title is stored through
// copies.ts renameVideo (store, the Telegram worker, the sync file), for the video and its other
// copies so it holds whichever copy a card shows. One dialog for the whole page, mounted by the card
// menu and opened from the menu and from a video's page. Keyboard and remote: the field takes focus
// with its text selected, Enter saves, Escape (Back) closes and focus goes back to where it was.
import { createEffect, createSignal, Show } from 'solid-js'
import { t } from '../i18n'
import { renamedTitle, renameVideo } from '../copies'
import { cleanTitle, countOf, groupStep, RENAME_MAX, saveOf, typed } from '../rename'
import { renameTarget as target, setRenameTarget as setTarget, type Ref } from './renameTarget'
import './rename.css'

export function RenameSheet() {
    let dialog!: HTMLDialogElement
    let field: HTMLInputElement | undefined
    const [value, setValue] = createSignal('')
    // A second press while the first is written is the same press again.
    let done = false
    let alone = false

    createEffect(() => {
        const now = target()
        if (now && !dialog.open) {
            done = false
            alone = false
            setValue(renamedTitle(now.item) ?? now.shown)
            find()
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
    // The group of copies the rename is written to: found when the sheet opens (local data in the worker), waited for at Save, never given up on silently.
    let group: Promise<readonly Ref[] | null> = Promise.resolve([])
    const [lost, setLost] = createSignal(false)
    const find = () => {
        const now = target()
        if (!now) return
        setLost(false)
        alone = false
        const copies = now.copies
        group = (typeof copies === 'function' ? copies() : Promise.resolve(copies)).then(
            (refs) => refs,
            () => (setLost(true), null),
        )
    }
    // `alone` is the viewer's yes to "this copy only" once the others could not be found.
    const write = async (title: string | null) => {
        const now = target()
        if (!now || done) return
        done = true
        const others = await group
        if (groupStep(others, alone) === 'ask') {
            // The others were not found: say so and keep the sheet; Save again renames this copy only, Try again looks once more.
            alone = true
            done = false
            return
        }
        renameVideo(now.item, title, others ?? [])
        close()
        if (title) now.after?.()
    }
    const save = (e: Event) => {
        e.preventDefault()
        const what = action()
        if (what === null) return close()
        void write(what === 'reset' ? null : cleanTitle(value()))
    }

    // The close event comes a task after Escape shuts the dialog: by then Enter on the button may have opened it
    // again (the browser gave the focus back at once), and that late event must not shut the new sheet.
    return (
        <dialog class="confirm rename-sheet" ref={dialog} aria-labelledby="rename-title" onClose={() => !dialog.open && target() && close()} onClick={(e) => e.target === dialog && close()}>
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
                    <Show when={lost()}>
                        <p class="rename-lost error small" role="alert">
                            {t('grid.rename_copies_failed')}{' '}
                            <button class="link" type="button" onClick={find}>{t('common.try_again')}</button>
                        </p>
                    </Show>
                    <p class="rename-count muted small" aria-live="polite">{`${countOf(value())} / ${RENAME_MAX}`}</p>
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
