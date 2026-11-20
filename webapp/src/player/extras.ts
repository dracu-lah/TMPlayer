// The player's smaller rules, ported from core's pureMain so the browser answers as the apps do:
// A-B repeat (AbLoop), the way back from a long jump (SeekUndo), when to ask "Still watching?"
// (StillWatching), the sleep timer's choices (SleepTimer) and the slowed scrub (FineScrub). Times are seconds here where
// the Kotlin keeps milliseconds. Nothing here touches the page.
import { pictureSize, type Shape } from './shape.ts'
import { transformOf, type Zoom } from './zoom.ts'

// ---- A-B repeat ----------------------------------------------------------------------------

export interface Loop {
    start: number
    end: number | null
}

/** Shorter than this is a stutter, not a loop. */
const LOOP_MIN = 0.5
/** A position this far past the end still counts as having played into it; further is a seek past the loop. */
const LOOP_RUN_PAST = 2

/** AbLoop.press: the first press marks the start, the second the end (in time order), the third clears. */
export function pressLoop(current: Loop | null, position: number): Loop | null {
    if (!current) return { start: position, end: null }
    if (current.end !== null) return null
    if (Math.abs(position - current.start) < LOOP_MIN) return current
    return { start: Math.min(current.start, position), end: Math.max(current.start, position) }
}

/** AbLoop.wrap: where playback at `position` goes back to, or null to carry on. */
export function wrapLoop(loop: Loop | null, position: number): number | null {
    if (!loop || loop.end === null) return null
    return position >= loop.end && position < loop.end + LOOP_RUN_PAST ? loop.start : null
}

// ---- the way back from a long jump ---------------------------------------------------------

export interface UndoOffer {
    backTo: number
    until: number
}

/** SeekUndo: seeks close together are one journey; a long one offers the way back for a few seconds. */
export class SeekUndo {
    static readonly MIN_DISTANCE = 60
    static readonly BURST = 2000
    static readonly OFFER = 6000
    static readonly SLACK = 2
    private offer: UndoOffer | null = null
    private burstOrigin = -1
    private lastSeekAt = -Infinity
    private returningTo = -1

    current(now: number): UndoOffer | null {
        if (this.offer && now >= this.offer.until) this.offer = null
        return this.offer
    }

    /** A seek from `from` to `to` (seconds) happened at `now` (ms): the offer standing after it. */
    seeked(from: number, to: number, now: number): UndoOffer | null {
        if (from < 0 || to < 0) return this.current(now)
        if (this.returningTo >= 0) {
            const ours = Math.abs(to - this.returningTo) <= SeekUndo.SLACK
            this.returningTo = -1
            if (ours) {
                this.lastSeekAt = -Infinity
                return null
            }
        }
        const inBurst = this.lastSeekAt !== -Infinity && now - this.lastSeekAt <= SeekUndo.BURST
        this.lastSeekAt = now
        const standing = this.current(now)
        const origin = standing ? standing.backTo : inBurst && this.burstOrigin >= 0 ? this.burstOrigin : from
        this.burstOrigin = origin
        if (standing && Math.abs(to - origin) <= SeekUndo.SLACK) {
            this.offer = null
            return null
        }
        if (standing) this.offer = { ...standing, until: now + SeekUndo.OFFER }
        else this.offer = Math.abs(to - origin) >= SeekUndo.MIN_DISTANCE ? { backTo: origin, until: now + SeekUndo.OFFER } : null
        return this.offer
    }

    /** Takes the offer: the place to go back to, or null when none stands. */
    take(now: number): number | null {
        const standing = this.current(now)
        if (!standing) return null
        this.offer = null
        this.burstOrigin = -1
        this.returningTo = standing.backTo
        return standing.backTo
    }

    clear() {
        this.offer = null
        this.burstOrigin = -1
        this.lastSeekAt = -Infinity
        this.returningTo = -1
    }
}

// ---- still watching ------------------------------------------------------------------------

/** StillWatching: three episodes in a row, or two hours with nothing pressed. */
export const AUTOPLAY_LIMIT = 3
export const IDLE_LIMIT = 2 * 60 * 60 * 1000

/**
 * What outlives one player: how many episodes autoplay has started since anybody pressed anything,
 * and when anybody last did. The next episode is a new player, so this sits on the module.
 */
export const watching = { autoplayed: 0, lastInput: Date.now() }

/** Any input at all is somebody being there. */
export function noteInput() {
    watching.autoplayed = 0
    watching.lastInput = Date.now()
}

/**
 * Repeat this video (PlayQueue.repeatsVideo): the video it was switched on for, or null. Kept here
 * because the next video is a new player; it repeats only that video unless "Keep repeat on" is set.
 */
export const repeatState: { of: string | null } = { of: null }

/** Whether the video `key` plays again at its end. */
export function repeats(key: string, keep: boolean): boolean {
    return repeatState.of !== null && (keep || repeatState.of === key)
}

/** The sleep timer as it stands, kept here so it carries over to the next episode. */
export const sleepState: { at: number | null; end: boolean } = { at: null, end: false }

/** SleepTimer.CHOICES: minutes, then the end of the video (0). */
export const SLEEP_CHOICES = [15, 30, 45, 60, 90, 0]

// ---- the slowed scrub ----------------------------------------------------------------------

/**
 * FineScrub.rate: a finger further from the line it scrubs along slows the travel to a half and
 * then a quarter. The app measures in dp; a CSS pixel is one.
 */
export function fineRate(offset: number): number {
    const away = Math.abs(offset)
    return away >= 128 ? 0.25 : away >= 56 ? 0.5 : 1
}

// ---- rotate picture ------------------------------------------------------------------------

/** PictureRotation.CHOICES: the four turns, clockwise degrees, the one that changes nothing first. */
export const TURNS = [0, 90, 180, 270]

/** PictureRotation.normalise: negative and past a whole turn wrap, between two quarters goes to the nearer. */
export function normaliseTurn(degrees: number): number {
    if (!Number.isFinite(degrees)) return 0
    const wrapped = ((Math.round(degrees) % 360) + 360) % 360
    return (Math.floor((wrapped + 45) / 90) * 90) % 360
}

/** PictureRotation.next: the next quarter turn clockwise, 270 coming back round to 0. */
export const nextTurn = (degrees: number) => normaliseTurn(normaliseTurn(degrees) + 90)

/** PictureRotation.sideways: whether the turned picture's width and height trade places (90 and 270). */
export const sidewaysTurn = (degrees: number) => normaliseTurn(degrees) % 180 !== 0

/**
 * The box a video is drawn in before it is turned, inside a stage: the stage itself, or for a
 * sideways turn the stage's width and height traded and centred, so the turned box fills the
 * stage exactly and the picture inside it is fitted, never stretched or cut off.
 */
export function turnFrame(stageW: number, stageH: number, degrees: number): { left: number; top: number; width: number; height: number } {
    if (!sidewaysTurn(degrees)) return { left: 0, top: 0, width: stageW, height: stageH }
    return { left: (stageW - stageH) / 2, top: (stageH - stageW) / 2, width: stageH, height: stageW }
}

/**
 * The ONE transform a picture wears: the free zoom (zoom.ts transformOf, in the stage's pixels) over
 * the turn, which is applied first and about the box's centre. Zoom and rotate both write the same
 * style property, so neither may write it alone: translate and scale of the zoom, then the rotation.
 */
export function pictureTransform(degrees: number, z: Zoom): string {
    const turn = normaliseTurn(degrees)
    return [transformOf(z), turn ? `rotate(${turn}deg)` : ''].filter(Boolean).join(' ')
}

/**
 * The picture's size on the screen at 100 per cent zoom, for the zoom's reach: the shape lays the
 * picture out in the turn's frame (the stage, or for a sideways turn the stage traded), and the
 * turn then swaps its width and height on the screen.
 */
export function turnedPictureSize(stageW: number, stageH: number, frameW: number, frameH: number, shape: Shape, degrees: number): { width: number; height: number } | null {
    const f = turnFrame(stageW, stageH, degrees)
    const pic = pictureSize(f.width, f.height, frameW, frameH, shape)
    if (!pic) return null
    return sidewaysTurn(degrees) ? { width: pic.height, height: pic.width } : pic
}

// ---- speed, per series, chat and topic -------------------------------------------------------

/** PlaybackSpeed.MIN and MAX. */
export const SPEED_LOW = 0.25
export const SPEED_HIGH = 4

export interface SpeedScopes {
    series: string | null
    chat: string | null
}

/** SettingsStore.seriesKey: lower case, letters and digits only, 48 at most, so "Show Name" and "show_name" are one show. */
export const seriesKeyOf = (show: string) =>
    Array.from(show.toLowerCase())
        .filter((ch) => /[\p{L}\p{N}]/u.test(ch))
        .slice(0, 48)
        .join('')

/**
 * SpeedMemory.scopesOf, for what this page knows of a video: the show it is an episode of (the
 * series), and its chat. The apps also file a numbered lecture under its course and a forum chat's
 * video under its topic; a page has neither (it holds no lesson reading and no topic id).
 */
export function speedScopes(chatId: number, show: string | null): SpeedScopes {
    const key = show ? seriesKeyOf(show) : ''
    return { series: key ? `series:${key}` : null, chat: Number.isFinite(chatId) && chatId !== 0 ? `chat:${Math.trunc(chatId)}` : null }
}

/** A speed as the player can use it: two decimals, within the limits; anything else is as if none was kept. */
function usableSpeed(value: unknown): number | null {
    if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) return null
    return Math.min(SPEED_HIGH, Math.max(SPEED_LOW, Math.round(value * 100) / 100))
}

/** SpeedMemory.resolve: the series' speed, else the chat's, else 1x. */
export const resolveSpeed = (series: unknown, chat: unknown): number => usableSpeed(series) ?? usableSpeed(chat) ?? 1

/** SpeedMemory.toKeep: what to write for a newly picked speed, or null for 1x (which is written as nothing). */
export function speedToKeep(value: number): number | null {
    const v = usableSpeed(value)
    return v === null || Math.abs(v - 1) < 0.005 ? null : v
}

/** SpeedMemory.Scopes.target: where a newly picked speed is written, the narrowest place the video belongs to. */
export const speedTarget = (s: SpeedScopes) => s.series ?? s.chat

// ---- volume boost --------------------------------------------------------------------------

/** The levels the boost picker offers: 100% is off, 200% the most. A gain over the file's own level. */
export const BOOSTS = [1, 1.25, 1.5, 1.75, 2]

/** movi's own gain takes 0 to 2 (above 1 linearly): the level the controls set times the boost, held to that, as the native and canvas engines multiply. */
export const MOVI_MAX_VOLUME = 2
export const moviVolume = (level: number, boost: number): number => Math.min(MOVI_MAX_VOLUME, Math.max(0, level * Math.max(1, boost)))

/** A stored or typed boost as one of [BOOSTS]: anything else (a damaged setting) is off, so a bad value never blasts a video. */
export function sanitiseBoost(value: unknown): number {
    if (typeof value !== 'number' || !Number.isFinite(value)) return 1
    return BOOSTS.find((b) => Math.abs(b - value) < 0.001) ?? 1
}
