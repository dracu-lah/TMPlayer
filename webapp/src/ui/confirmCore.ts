// The parts of the confirm dialog (Confirm.tsx) that do not need a page, so a test can run them:
// opening where `<dialog>` is missing (iOS before 15.4 has no showModal), closing that always ends
// in a close event, holding a close while the question's work runs, and one question at a time.

export interface DialogLike {
    showModal?: () => void
    close?: () => void
    setAttribute(name: string, value: string): void
    removeAttribute(name: string): void
    hasAttribute(name: string): boolean
    dispatchEvent(event: Event): boolean
}

export const isOpen = (d: DialogLike) => d.hasAttribute('open')

/** Opens as a modal; true when the browser made it modal, false when it is only shown (the caller then traps Tab and draws the backdrop). Never throws. */
export function openModal(d: DialogLike): boolean {
    try {
        if (typeof d.showModal === 'function') {
            d.showModal()
            return true
        }
    } catch {}
    try {
        d.setAttribute('open', '')
    } catch {}
    return false
}

/** Closes, and where the element has no close() says so with a close event, as the browser would. */
export function closeModal(d: DialogLike) {
    if (typeof d.close === 'function') {
        d.close()
        return
    }
    const was = isOpen(d)
    d.removeAttribute('open')
    if (was) d.dispatchEvent(new Event('close'))
}

/**
 * Makes `d.close()` do nothing while `working()` is true: the TV's Back calls close() on the open
 * dialog itself (tv/focus.ts), and the question must not go while its work runs.
 */
export function holdClose(d: DialogLike, working: () => boolean) {
    const real = typeof d.close === 'function' ? d.close.bind(d) : undefined
    d.close = () => {
        if (working()) return
        if (real) real()
        else {
            const was = isOpen(d)
            d.removeAttribute('open')
            if (was) d.dispatchEvent(new Event('close'))
        }
    }
}

/** A second ask while one is open is answered "no" at once: it never gets the first one's yes. */
export function onlyOne(open: boolean, start: () => Promise<boolean>): Promise<boolean> {
    return open ? Promise.resolve(false) : start()
}
