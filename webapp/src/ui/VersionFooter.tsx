// The version line (WP-104) where the web shows it: the account menu's last line, the TV account menu's,
// the foot of Settings' main list and About's masthead. One component, five looks. The menus and the
// Settings list show the compact row: the TMPlayer icon and "v1.28.7", and a press opens About, where
// the full line stays. The facts and the update state come from ui/versionLine.ts and the service
// worker's own news (install.ts), and a press on the full line copies
// "TMPlayer web (<sha>, <day>) <browser on system>" for support. Nothing here sends anything.
import { createMemo, createSignal, Match, onCleanup, Show, Switch } from 'solid-js'
import { A } from '@solidjs/router'
import { applyUpdate, checking, installed, lastChecked, updateReady } from '../install'
import { t } from '../i18n'
import { compactVersion, copyText, shown, versionState, webVersionFacts, type VersionState } from './versionLine'

/** How long the line says "Version details copied" before the version returns. */
const COPIED_MS = 2000

/** The facts of this build: "web", the commit and day it was built, and the browser. */
export const facts = () => webVersionFacts(__APP_BUILD__, __APP_BUILT__, typeof navigator === 'undefined' ? '' : navigator.userAgent, installed())

/** The compact release as the menus show it: "v1.28.7", or "web" when the build knows no number. */
export const compact = (): string => compactVersion(typeof __APP_RELEASE__ === 'string' ? __APP_RELEASE__ : '') || 'web'

/** The TMPlayer icon beside the compact version (decorative: the row's own name carries the words). */
export function VersionMark() {
    return <img class="version-mark" src={`${import.meta.env.BASE_URL}icon.svg`} alt="" width="16" height="16" aria-hidden="true" draggable={false} />
}

/** The state beside the version, from the service worker's own news. */
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
 * [look] "menu" is the account menu's last line (a `menuitem` the arrows reach) and "chip" the foot of
 * Settings' main list: the icon and "v1.28.7", a press opens About, and the update state is a chip of
 * its own beside it. "tv" is the TV menu's row (`.tv-menu-row`, which OK presses, and which still
 * copies the support text), "line" the quiet full line of About and "text" the same words with
 * nothing to press (the TV's Settings, where the menu's footer is the copy target).
 * [onOpen] runs before the press goes to About (the menu closes itself).
 */
export function VersionFooter(props: { look: 'menu' | 'chip' | 'tv' | 'line' | 'text'; onOpen?: () => void }) {
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
    const small = () => props.look === 'menu' || props.look === 'chip' || props.look === 'tv'
    // The text is the live region: it changes once, to the confirmation, and a second press leaves it.
    const words = () => (
        <span class="version-text" aria-live="polite">
            {copied() ? t('about.version_copied') : small() ? compact() : lineText(true)}
        </span>
    )
    const marked = () => (
        <>
            <VersionMark />
            {words()}
        </>
    )
    const press = createMemo(() => props.look !== 'text' && canCopy())
    const label = () => t('about.version_short', { version: compact() })
    // "Update available" (a button) beside the compact version, and only then (user, 2026-11-25): no "Checking", no "Up to date".
    const chip = () =>
        state() !== 'available' ? null : (
            <button type="button" class="version-state available" role={props.look === 'menu' ? 'menuitem' : undefined} onClick={() => applyUpdate()}>
                {t('update.state_available_plain')}
            </button>
        )
    return (
        <Switch>
            <Match when={props.look === 'menu'}>
                <div class="you-foot">
                    <A class="you-row you-version" role="menuitem" href="/settings/about" aria-label={label()} title={label()} data-version-line onClick={() => props.onOpen?.()}>
                        {marked()}
                    </A>
                    <Show when={state() === 'available'}>{chip()}</Show>
                </div>
            </Match>
            <Match when={props.look === 'chip'}>
                <div class="version-chip-row">
                    <A class="version-chip" href="/settings/about" aria-label={label()} title={label()} data-version-line>
                        {marked()}
                    </A>
                    {chip()}
                </div>
            </Match>
            <Match when={true}>
                <Show
                    when={press()}
                    fallback={
                        <p class="version-line" classList={{ 'tv-version': props.look === 'tv' || props.look === 'text' }} data-version-line>
                            {small() ? marked() : words()}
                        </p>
                    }
                >
                    <button
                        type="button"
                        class={props.look === 'tv' ? 'tv-menu-row tv-version version-line version-copy' : 'version-line version-copy'}
                        role={props.look === 'tv' ? 'menuitem' : undefined}
                        title={t('about.version_copy')}
                        data-version-line
                        onClick={() => void copy()}
                    >
                        {small() ? marked() : words()}
                    </button>
                </Show>
            </Match>
        </Switch>
    )
}
