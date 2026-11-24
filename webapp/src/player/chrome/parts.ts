
import { CropIcon, FitToScreenIcon, MaximizeScreenIcon } from '@hugeicons/core-free-icons'
import { type IconData } from '../../ui/Icon'
import { t } from '../../i18n'
import { ratioPath, shapeRatio, type Shape } from '../shape'
// Constants, icons and small pure helpers of the player controls (Chrome.tsx and the parts drawn beside it).

/** TmIcons.PlaySolid and PauseSolid: Hugeicons Play and Pause, filled. */
const PLAY_PATH =
    'M20.1406 12.846C19.7871 14.189 18.1167 15.138 14.7757 17.0361C11.546 18.8709 9.9312 19.7884 8.6298 19.4196C8.0918 19.2671 7.6016 18.9776 7.2062 18.5787C6.25 17.6139 6.25 15.7426 6.25 12C6.25 8.2574 6.25 6.3861 7.2062 5.42132C7.6016 5.02245 8.0918 4.73288 8.6298 4.58042C9.9312 4.21165 11.546 5.12907 14.7757 6.96393C18.1167 8.86197 19.7871 9.811 20.1406 11.154C20.2865 11.7084 20.2865 12.2916 20.1406 12.846Z'
export const PlaySolid: IconData = [['path', { d: PLAY_PATH, fill: 'currentColor' }]]
export const PauseSolid: IconData = [
    ['path', { d: 'M4 7C4 5.58579 4 4.87868 4.43934 4.43934C4.87868 4 5.58579 4 7 4C8.41421 4 9.12132 4 9.56066 4.43934C10 4.87868 10 5.58579 10 7V17C10 18.4142 10 19.1213 9.56066 19.5607C9.12132 20 8.41421 20 7 20C5.58579 20 4.87868 20 4.43934 19.5607C4 19.1213 4 18.4142 4 17V7Z', fill: 'currentColor' }],
    ['path', { d: 'M14 7C14 5.58579 14 4.87868 14.4393 4.43934C14.8787 4 15.5858 4 17 4C18.4142 4 19.1213 4 19.5607 4.43934C20 4.87868 20 5.58579 20 7V17C20 18.4142 20 19.1213 19.5607 19.5607C19.1213 20 18.4142 20 17 20C15.5858 20 14.8787 20 14.4393 19.5607C14 19.1213 14 18.4142 14 17V7Z', fill: 'currentColor' }],
]
/** The phone's copies button (ic_copies.xml): three stacked layers, drawn by hand in Hugeicons' stroke. */
export const CopiesIcon: IconData = [
    ['path', { d: 'M12 3.5L21 8.25L12 13L3 8.25Z' }],
    ['path', { d: 'M3 12.25L12 17L21 12.25' }],
    ['path', { d: 'M3 16.25L12 21L21 16.25' }],
]

/** PlaybackSpeed.CHOICES, and the fine control's step and ends. */
export const SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2]
export const FINE_STEP = 0.05
export const SPEED_MIN = 0.25
export const SPEED_MAX = 4

/** PlayerControls' timings: in a touch slower than out, hidden after 3.5 s of playing untouched. */
export const HIDE_MS = 3500
/** The TV layout waits longer: a remote's press is slower than a finger (PlayerControls on Android TV hides after 5 s). */
export const HIDE_TV_MS = 5000
/** A second press inside this is a double tap or click (VideoGestures' DOUBLE_CLICK_MS). */
export const DOUBLE_MS = 300
/** A finger held this long plays fast until it lifts. */
export const HOLD_MS = 450
/** A side tap within this of the last jump adds another (the running total). */
export const RUN_MS = 700
/** A finger that moved further than this is a drag, not a tap. */
export const SLOP = 12
/** TapZone.SIDE_FRACTION: the left and right thirds-and-a-bit that jump. */
export const SIDE = 0.35
/** PlayerControls.WIDE_ROW_DP: from this width the phone's row adds picture shape and the lock. */
export const WIDE_ROW = 540
export const NOTE_MS = 900
/** How long the TV's card stays after an arrow press on the bar. */
export const PEEK_MS = 1200
/** An iPhone or iPad: a page cannot set the volume there (the side buttons do), so the right side's drag does nothing. */
export const FIXED_VOLUME = /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
export const UNLOCK_PILL_MS = 2500
/** PlayerFeedback.ZOOM_MS: the zoom's figure stays this long after the fingers lift or a key zooms. */
export const ZOOM_MS = 1800
/** Cmd is the command key on a Mac (and an iPad's keyboard, which says MacIntel), Ctrl elsewhere. */
export const MAC = /Mac/.test(navigator.platform)

const SHAPE_ICON: Partial<Record<Shape, IconData>> = { fit: FitToScreenIcon, crop: CropIcon, stretch: MaximizeScreenIcon }
/** The glyph for a shape: the three of the apps' button, a frame at its ratio for a fixed one. */
export const shapeIcon = (s: Shape): IconData => SHAPE_ICON[s] ?? [['path', { d: ratioPath(shapeRatio(s) ?? 1) }]]
/** Original, Fill and Stretch from the catalog; a ratio is its own name in every language, as in the apps. */
export const shapeName = (s: Shape) => (s === 'fit' || s === 'crop' || s === 'stretch' ? t(`player.scale_${s}`) : s)

/** SeekMath.clock: 4:05, 1:02:03. */
export function clock(seconds: number): string {
    const s = Math.max(0, Math.floor(isFinite(seconds) ? seconds : 0))
    const h = Math.floor(s / 3600)
    const m = Math.floor((s % 3600) / 60)
    const sec = String(s % 60).padStart(2, '0')
    return h ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`
}

/** PlaybackSpeed.label: 1x, 1.25x, 0.5x. */
export const speedLabel = (rate: number) => t('format.speed_factor', { speed: String(Math.round(rate * 100) / 100) })

/** A file name the three systems all take: no path or reserved characters, not too long. */
export function shotName(title: string, at: number): string {
    const name = title.replace(/[\\/:*?"<>|\u0000-\u001f]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 80) || 'TMPlayer'
    return `${name} ${clock(at).replace(/:/g, '.')}.png`
}
