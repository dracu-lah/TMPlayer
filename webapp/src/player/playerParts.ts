// What Player.tsx shares with the parts drawn beside it: the source being played, a few constants, and the small
// pure helpers that say things about a file or a failure.
import { t } from '../i18n'
import { TV_BROWSER } from './tvnative'
import { Unplayable, type Why } from './engines'
import type { FormatFacts } from './formatline'
import type { PieceSet, VideoCopy } from '../tg/types'

/** What plays: the posted file, or one of Telegram's lighter copies of it. */
export interface Source {
    key: string
    size: number
    duration: number
    /** A copy's picture height; 0 for the posted file. */
    height: number
    /** The posted file is a video cut into pieces: they play as one (stream/joined.ts). */
    joined?: PieceSet
    /** The posted file is a ZIP: the video stored inside it plays out of the archive. */
    zip?: boolean
}

/** Seconds before the end at which the next episode is fetched ahead and its card comes up. */
export const NEXT_AHEAD = 30
/** An LG or Samsung TV, whose own player may be given the file (tvnative.ts). */
export const onTv = TV_BROWSER.test(navigator.userAgent)
/** How long the picture has to move on after a failure nobody caught, before the player says it broke. */
export const CRASH_GRACE_MS = 8_000

/** Local testing only: start and seek times, in the console and in `window.playerTimes`. */
export const timing =
    location.hostname === 'localhost'
        ? (label: string, since: number) => {
              const ms = Math.round(performance.now() - since)
              console.debug('[player]', label, ms, 'ms')
              ;((window as unknown as { playerTimes?: { label: string; ms: number }[] }).playerTimes ??= []).push({ label, ms })
          }
        : () => {}

/** Only one tab plays at a time: a tab that starts playing tells the others, which pause. */
export const tabs = typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel('tmplayer-player') : null

/** Telegram's copies this browser can play: H.264 always, AV1 where it decodes it. */
export function playableCopies(copies: VideoCopy[]): VideoCopy[] {
    const av1 = typeof MediaSource !== 'undefined' && MediaSource.isTypeSupported('video/mp4; codecs="av01.0.08M.08"')
    return copies.filter((c) => c.codec !== 'av01' || av1)
}

/** A transparent pixel: a poster of nothing. */
export const BLANK_POSTER = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7'

export function speedText(bytesPerSecond: number): string {
    const mb = bytesPerSecond / 1e6
    return mb >= 1 ? t('format.speed_mb', { speed: mb.toFixed(1) }) : t('format.speed_kb', { speed: Math.round(bytesPerSecond / 1e3) })
}

/** The failure is the browser turning the media down (a codec, a container, a broken stream), not Telegram's or the network's. */
export const isMediaFailure = (e: unknown) =>
    e instanceof Unplayable || e instanceof DOMException || /could not take this part|MediaSource|SourceBuffer|decod|codec|hevc/i.test(String((e as Error)?.message ?? e))

export const whyText = (why: Why) =>
    why.kind === 'format'
        ? t('web.needs_app_format', { format: why.format === 'unknown' ? '?' : why.format })
        : why.kind === 'picture'
          ? t('web.needs_app_picture', { codec: why.codec })
          : why.kind === 'no-mse'
            ? t('web.needs_app_no_mse')
            : why.kind === 'too-heavy'
              ? t('web.needs_app_too_heavy')
              : t('web.needs_app_codec')

/** What this browser says of the codecs in the file's header, one line each ("video/mp4; codecs=... yes"), for Copy details. */
export function codecSupport(f: FormatFacts | null): string[] {
    const source = globalThis.MediaSource ?? (globalThis as { ManagedMediaSource?: typeof MediaSource }).ManagedMediaSource
    const ask = (kind: string, codec: string | null) => {
        if (!codec) return null
        const type = `${kind}/mp4; codecs="${codec}"`
        let yes = false
        try {
            yes = !!source?.isTypeSupported(type)
        } catch {
            // a browser that throws on a string it does not know says no
        }
        return `${type} ${yes ? 'yes' : 'no'}`
    }
    return [ask('video', f?.videoParams ?? null), ask('audio', f?.audioParams ?? null)].filter((s): s is string => !!s)
}
