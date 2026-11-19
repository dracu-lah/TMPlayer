// What opens the rename sheet (RenameSheet.tsx): the video it is for, and the "Wrong title?" hook.
// Kept apart from the sheet so a video's page and a card's menu open it by one call and the sheet's
// markup is drawn in one place (CardMenu.tsx mounts it).
import { createSignal } from 'solid-js'
import { renamedTitle } from '../copies'
import type { VideoItem } from '../tg/types'

export type Ref = Pick<VideoItem, 'chatId' | 'messageId'>

export interface Target {
    item: Ref
    /** The title shown now (what the file name reads as), the field's start when nothing was renamed. */
    shown: string
    /** The video's other copies, as they are known or found out (a card knows only its own); the sheet runs the lookup and offers Try again when it fails. */
    copies: readonly Ref[] | (() => Promise<readonly Ref[]>)
    /** Called once the title is saved (WB28's report, which asks for a rename first). */
    after?: () => void
    /** Where the focus was, to give it back. */
    back: Element | null
}

const [target, setTarget] = createSignal<Target | null>(null)
export { target as renameTarget, setTarget as setRenameTarget }

/**
 * Opens the sheet for `item`. `shown` is the title on show now; `copies` the video's other copies
 * (an array, or a lookup started now and waited for at Save, briefly).
 */
export function openRename(item: Ref, shown: string, copies: readonly Ref[] | (() => Promise<readonly Ref[]>) = [], after?: () => void) {
    setTarget({ item, shown, copies, after, back: document.activeElement })
}

// "Wrong title?" (N1): the report is WB28's. Until its inbox exists nothing registers, so the entry stays hidden.
const [reporter, setReporter] = createSignal<((item: Ref) => void) | null>(null)

/** WB28 hands in the sheet that shows what would be sent. */
export const registerTitleReporter = (fn: ((item: Ref) => void) | null) => setReporter(() => fn)

/** Whether "Wrong title?" is offered (the build has an inbox). */
export const canReportTitle = () => reporter() !== null

/** "Wrong title?": for a video renamed already, the report; for one not renamed yet the rename comes first and its Save opens the report. */
export function reportTitle(item: Ref, shown: string, copies: readonly Ref[] | (() => Promise<readonly Ref[]>) = []) {
    const report = reporter()
    if (!report) return
    if (renamedTitle(item)) report(item)
    else openRename(item, shown, copies, () => report(item))
}

