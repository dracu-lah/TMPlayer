// What the player writes to the log, and the scrubbed logs the Details sheet can copy. The log may take out
// names as written, and says of a video only the engine and the format in plain words, never a name.
import { createEffect } from 'solid-js'
import { buildBundle, log, rememberNames, sizeText, suppliedParts, supply, type Bundle, type BundleFacts } from '../logs'
import { formatLine, type FormatFacts } from './formatline'
import { deviceLines } from './stutter'
import type { Why } from './engines'
import type { VideoItem, WatchInfo } from '../tg/types'

export function usePlayerLogs(
    video: () => HTMLVideoElement,
    item: VideoItem,
    info: () => WatchInfo | null,
    state: { facts: () => FormatFacts | null; stall: () => 'start' | 'mid' | 'offline' | null; error: () => string | null; needsApp: () => Why | null },
) {
    rememberNames([item.title, item.name, item.chat, item.fileName])
    createEffect(() => rememberNames([info()?.title, info()?.episode?.show, info()?.episode?.name]))
    createEffect(() => {
        const line = formatLine(state.facts())
        if (line) log('I', 'player', `format ${line}`)
    })
    createEffect(() => {
        const s = state.stall()
        if (s) log('W', 'player', `stalled, ${s}`)
    })
    createEffect(() => {
        if (state.error() || state.needsApp()) log('E', 'player', `blocked: ${state.needsApp() ? `needs the app, ${state.needsApp()!.kind}` : 'could not play'}`)
    })
    /**
     * The logs this device holds, scrubbed, for Copy details: the facts, the marks and the page's own log. Built in one go
     * with no wait, so a press can copy it inside the click (iPhone Safari refuses a copy that follows an await). Nothing leaves until the viewer presses it.
     */
    let factsHere: BundleFacts | null = null
    let factsAsked = false
    const logBundle = (): Bundle => {
        // The browser's line comes from the feedback form's code, loaded once when the sheet first asks; until then a plain one.
        if (!factsAsked) {
            factsAsked = true
            void Promise.all([import('../ui/feedback'), import('../install')]).then(
                ([{ webFacts }, { installed }]) => void (factsHere = webFacts(navigator.userAgent, document.documentElement.lang || navigator.language || 'en', installed())),
                () => {},
            )
        }
        const line = formatLine(state.facts())
        supply('device', () => [...deviceLines({ video: video() }), ...(line ? [`Format: ${line}`] : []), ...(item.dc ? [`Datacenter: DC ${item.dc}`] : [])])
        return buildBundle(factsHere ?? { version: 'web', platform: 'Web', device: 'a browser', language: document.documentElement.lang || 'en' }, suppliedParts())
    }
    const logsSize = () => sizeText(logBundle().bytes)
    const logsText = () => logBundle().text
    /** The text goes to the clipboard in the click itself; false when the browser refuses (the sheet then shows it selected). */
    const copyLogs = (): Promise<boolean> => {
        try {
            return navigator.clipboard.writeText(logsText()).then(() => true, () => false)
        } catch {
            return Promise.resolve(false)
        }
    }
    return { logsSize, logsText, copyLogs }
}
