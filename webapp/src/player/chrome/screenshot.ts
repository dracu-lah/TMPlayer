// Screenshots (S with the subtitles, Shift+S without): the frame shown, at the video's own size, as a PNG the
// browser saves. The canvas engines' picture is their canvas; a frame the page may not read (a WebGL canvas that
// kept no copy, a source the browser calls foreign) comes out empty or throws, and the note says it failed.
import { t } from '../../i18n'
import { sidewaysTurn } from '../extras'
import { shotName } from './parts'

export interface ShotDeps {
    video: HTMLVideoElement
    /** The engine's canvas, when one draws the picture. */
    canvas: () => HTMLCanvasElement | null
    /** The picture's turn in degrees (Rotate picture). */
    turn: () => number
    /** The subtitle track showing, for Shift-less S. */
    subtitles: () => TextTrack | null
    title: () => string
    flash: (text: string) => void
}

/** The subtitles showing at `at`, drawn at the foot of the frame in the player's look (white, a dark edge). */
function drawCues(g: CanvasRenderingContext2D, w: number, h: number, at: number, track: TextTrack | null) {
    const lines = Array.from(track?.cues ?? [])
        .filter((cue) => cue.startTime <= at && at < cue.endTime)
        .flatMap((cue) => String((cue as VTTCue).text ?? '').replace(/<[^>]*>/g, '').split('\n'))
        .map((s) => s.trim())
        .filter(Boolean)
        .slice(-4)
    if (!lines.length) return
    const size = Math.max(14, Math.round(h * 0.05))
    g.font = `600 ${size}px system-ui, sans-serif`
    g.textAlign = 'center'
    g.textBaseline = 'bottom'
    g.lineJoin = 'round'
    g.lineWidth = Math.max(2, size / 8)
    g.strokeStyle = 'rgba(0, 0, 0, .85)'
    g.fillStyle = '#fff'
    lines.forEach((line, i) => {
        const y = h - Math.round(h * 0.06) - (lines.length - 1 - i) * size * 1.25
        g.strokeText(line, w / 2, y, w * 0.9)
        g.fillText(line, w / 2, y, w * 0.9)
    })
}

export function createScreenshot(d: ShotDeps) {
    const v = d.video
    return async (withSubtitles: boolean) => {
        const cv = d.canvas()
        const onCanvas = !!cv && (v.classList.contains('movi-active') || v.classList.contains('sw-active'))
        const w = onCanvas ? cv!.width : v.videoWidth
        const h = onCanvas ? cv!.height : v.videoHeight
        const at = v.currentTime
        if (!(w > 0) || !(h > 0)) return d.flash(t('player.screenshot_failed'))
        try {
            const out = document.createElement('canvas')
            out.width = w
            out.height = h
            const g = out.getContext('2d')
            if (!g) throw new Error('no 2d')
            g.drawImage(onCanvas ? cv! : v, 0, 0, w, h)
            // A cleared WebGL buffer reads as see-through black; a real frame is opaque.
            if (g.getImageData(w >> 1, h >> 1, 1, 1).data[3] === 0) throw new Error('empty frame')
            // A turned picture is saved as it is shown (Rotate picture): the frame turned, the subtitles upright over it.
            let shot = out
            const turn = d.turn()
            if (turn !== 0) {
                const side = sidewaysTurn(turn)
                shot = document.createElement('canvas')
                shot.width = side ? h : w
                shot.height = side ? w : h
                const r = shot.getContext('2d')
                if (!r) throw new Error('no 2d')
                r.translate(shot.width / 2, shot.height / 2)
                r.rotate((turn * Math.PI) / 180)
                r.drawImage(out, -w / 2, -h / 2)
            }
            if (withSubtitles) drawCues(shot.getContext('2d')!, shot.width, shot.height, at, d.subtitles())
            const blob = await new Promise<Blob | null>((done) => shot.toBlob(done, 'image/png'))
            if (!blob) throw new Error('no image')
            const file = shotName(d.title(), at)
            const url = URL.createObjectURL(blob)
            const a = document.createElement('a')
            a.href = url
            a.download = file
            a.click()
            window.setTimeout(() => URL.revokeObjectURL(url), 30_000)
            d.flash(t('player.screenshot_saved', { file }))
        } catch {
            d.flash(t('player.screenshot_failed'))
        }
    }
}
