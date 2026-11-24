// The version line (WP-104) where the web shows it: the You menu's last line, the TV account menu's,
// the top of Settings and About's masthead. One component, four looks; the facts and the update
// state come from ui/versionLine.ts and the service worker's own news (install.ts), and a press
// copies "TMPlayer web (<sha>, <day>) <browser on system>" for support. Nothing here sends anything.
import { createMemo, createSignal, onCleanup, Show } from 'solid-js'
import { applyUpdate, checking, installed, lastChecked, updateReady } from '../install'
import { t } from '../i18n'
import { copyText, shown, versionState, webVersionFacts, type VersionState } from './versionLine'

/** How long the line says "Version details copied" before the version returns. */
const COPIED_MS = 2000

/** The facts of this build: "web", the commit and day it was built, and the browser. */
export const facts = () => webVersionFacts(__APP_BUILD__, __APP_BUILT__, typeof navigator === 'undefined' ? '' : navigator.userAgent, installed())

/** The state beside the version, from the service worker's news. */
export const state = (): VersionState => versionState({ updateReady: updateReady(), checking: checking(), lastChecked: lastChecked() })

/** The words for [s], or '' when the version stands alone. */
export const stateWords = (s: VersionState): string => (s === 'available' ? t('update.state_available_plain') : s === 'checking' ? t('update.checking') : s === 'upToDate' ? t('update.state_up_to_date') : '')

/** The line as shown: "TMPlayer web (a1b2c3d, 2026-11-24), Up to date". */
export function lineText(withAvailable = true): string {
    const f = shown(facts())
    const s = state()
    const words = s === 'available' && !withAvailable ? '' : stateWords(s)
    const base = t('about.version_build', { version: f.version, build: f.build })
    return words ? `${base}, ${words}` : base
}

const canCopy = () => typeof navigator !== 'undefined' && !!navigator.clipboard?.writeText

/**
 * [look] "menu" is the You menu's last line (a `menuitem` the arrows reach), "tv" the TV menu's row
 * (`.tv-menu-row`, which OK presses), "line" a quiet line of its own (Settings, About) and "text" the
 * same words with nothing to press (the TV's Settings, where the menu's footer is the copy target).
 */
export function VersionFooter(props: { look: 'menu' | 'tv' | 'line' | 'text' }) {
    const [copied, setCopied] = createSignal(false)
    let timer: ReturnType<typeof setTimeout> | undefined
    onCleanup(() => clearTimeout(timer))
    const copy = async () => {
        try {
            await navigator.clipboard.writeText(copyText(facts()))
        } catch {
            // No clipboard permission: the line stays, and says nothing was copied by not changing.
            return
        }
        setCopied(true)
        clearTimeout(timer)
        timer = setTimeout(() => setCopied(false), COPIED_MS)
    }
    // The text is the live region: it changes once, to the confirmation, and a second press leaves it.
    // The menu keeps "Update available" for its own button under the line.
    const words = () => (
        <span class="version-text" aria-live="polite">
            {copied() ? t('about.version_copied') : lineText(props.look !== 'menu')}
        </span>
    )
    const press = createMemo(() => props.look !== 'text' && canCopy())
    return (
        <Show
            when={press()}
            fallback={
                <p class="version-line" classList={{ 'you-version': props.look === 'menu', 'tv-version': props.look === 'tv' || props.look === 'text' }} data-version-line>
                    {words()}
                </p>
            }
        >
            <Show
                when={props.look === 'menu'}
                fallback={
                    <button
                        type="button"
                        class={props.look === 'tv' ? 'tv-menu-row tv-version version-line version-copy' : 'version-line version-copy'}
                        role={props.look === 'tv' ? 'menuitem' : undefined}
                        title={t('about.version_copy')}
                        data-version-line
                        onClick={() => void copy()}
                    >
                        {words()}
                    </button>
                }
            >
                <button type="button" class="you-row you-version" role="menuitem" title={t('about.version_copy')} data-version-line onClick={() => void copy()}>
                    {words()}
                </button>
                <Show when={state() === 'available' && !copied()}>
                    <button type="button" class="you-row you-update" role="menuitem" onClick={() => applyUpdate()}>
                        {t('update.state_available_plain')}
                    </button>
                </Show>
            </Show>
        </Show>
    )
}
