// Subtitles from a file for the engines that take the `<video>` over (movi.ts, libavcanvas.ts):
// the element's own cue box does not run (its clock stays at 0), so the showing track's cues are
// drawn in a layer over the picture, in the look the custom properties give `video::cue`.

export interface CueOverlay {
    /** Shows the cues active at the engine's current time (call it on every time update). */
    draw(): void
    remove(): void
}

/** The layer is the `.movi-cues` box in player.css, a child of the video's parent. */
export function cueOverlay(video: HTMLVideoElement, time: () => number): CueOverlay {
    const cues = document.createElement('div')
    cues.className = 'movi-cues'
    video.parentElement?.append(cues)
    let shown = ''
    return {
        draw() {
            const t = time()
            const track = Array.from(video.textTracks).find((tt) => tt.mode === 'showing' && (tt.kind === 'subtitles' || tt.kind === 'captions'))
            const active = track ? Array.from(track.cues ?? []).filter((c) => c.startTime <= t && t < c.endTime) : []
            const key = active.map((c) => `${c.startTime}:${c.endTime}`).join('|')
            if (key === shown) return
            shown = key
            // The look (size, colour, box, edge) is set on the element by Player.tsx.
            for (const name of ['--cue-size', '--cue-colour', '--cue-box', '--cue-edge']) cues.style.setProperty(name, video.style.getPropertyValue(name))
            // Not replaceChildren: a TV's Chromium (68, 79) has no such call.
            cues.textContent = ''
            for (const c of active) {
                const line = document.createElement('span')
                line.className = 'movi-cue'
                if (c instanceof VTTCue) line.append(c.getCueAsHTML())
                cues.append(line)
            }
            const first = active[0]
            const at = first instanceof VTTCue && typeof first.line === 'number' && !first.snapToLines ? first.line : 90
            cues.style.paddingBottom = `${Math.max(0, 100 - at)}%`
        },
        remove() {
            cues.remove()
        },
    }
}
