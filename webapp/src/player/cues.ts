// How subtitles sit on the picture: the delay (a cue's times shifted from the ones the file gave)
// and the look (the app's size, colour, edge, box and position), shared by the player, which
// applies them to the video, and the sheet that changes them.
import { createSignal } from 'solid-js'
import { BOTTOM_FRACTION, COLOUR_HEX, DEFAULT_LOOK, SIZE_FRACTION, type SubtitleLook } from './keep'

/** Seconds the subtitles are shown later (positive) or earlier than the file says; per video. */
export const [subDelay, setSubDelay] = createSignal(0)
export const [look, setLook] = createSignal<SubtitleLook>(DEFAULT_LOOK)

const original = new WeakMap<VTTCue, [number, number]>()

/** Shifts every cue of every text track by `delay` seconds from the times it loaded with. */
export function applyDelay(video: HTMLVideoElement, delay = subDelay()) {
    for (const tt of Array.from(video.textTracks)) {
        if (tt.kind !== 'subtitles' && tt.kind !== 'captions') continue
        for (const cue of Array.from(tt.cues ?? [])) {
            if (!(cue instanceof VTTCue)) continue
            let o = original.get(cue)
            if (!o) original.set(cue, (o = [cue.startTime, cue.endTime]))
            // Later: the end first, so the cue never ends before it starts; earlier: the start first.
            if (delay >= 0) {
                cue.endTime = o[1] + delay
                cue.startTime = o[0] + delay
            } else {
                cue.startTime = o[0] + delay
                cue.endTime = o[1] + delay
            }
        }
    }
}

/** Where the bottom of the lines sits: the look's position, lifted clear of the bar while the controls are up. */
export function placeCues(video: HTMLVideoElement, controlsUp: boolean, l = look()) {
    const bottom = Math.max(BOTTOM_FRACTION[l.position], controlsUp ? 0.2 : 0)
    for (const tt of Array.from(video.textTracks)) {
        if (tt.kind !== 'subtitles' && tt.kind !== 'captions') continue
        for (const cue of Array.from(tt.cues ?? [])) {
            if (!(cue instanceof VTTCue)) continue
            cue.snapToLines = false
            cue.lineAlign = 'end'
            cue.line = Math.round((1 - bottom) * 1000) / 10
        }
    }
}

/** The look as custom properties, which `.player video::cue` reads; `height` is the picture's height in px. */
export function lookVars(l: SubtitleLook, height: number): Record<string, string> {
    const edge = l.edge === 'outline' ? '0 0 3px #000, 0 0 3px #000, 1px 1px 2px #000, -1px -1px 2px #000' : l.edge === 'shadow' ? '2px 2px 4px rgba(0,0,0,.9)' : 'none'
    return {
        '--cue-size': `${Math.max(12, Math.round(height * SIZE_FRACTION[l.size]))}px`,
        '--cue-colour': COLOUR_HEX[l.colour],
        '--cue-box': `rgba(0,0,0,${l.box / 100})`,
        '--cue-edge': edge,
    }
}
