// The subtitles laid over the playing video: a file read and shown as a text track, and the look (size, colour,
// edge, box) painted as custom properties the video's cues read.
import { createEffect, onCleanup, onMount } from 'solid-js'
import { t } from '../i18n'
import { applyDelay, look, lookVars, placeCues } from './cues'
import { choseOther } from './subpick-use'
import { readSubtitle, SubtitleError } from './subtitles'
import type { VideoItem } from '../tg/types'

export function useSubtitleTracks(
    video: () => HTMLVideoElement,
    container: () => HTMLElement,
    ready: () => boolean,
    item: VideoItem,
    /** The player went away while a file was being read. */
    closed: () => boolean,
    setNotice: (text: string) => void,
) {
    const subtitleUrls: string[] = []
    onCleanup(() => {
        for (const u of subtitleUrls) URL.revokeObjectURL(u)
    })
    // Subtitles move up while the controls are showing, so the seek bar never covers them; the look's position holds otherwise.
    let controlsUp = true
    /** A subtitle file laid over the video and shown at once; throws when it cannot be read (MSone's sheet says so itself). */
    const addSubtitle = async (picked: File, as?: { label: string; language: string }, replacing?: TextTrack | null) => {
        const v = video()
        const sub = await readSubtitle(picked)
        if (closed()) return URL.revokeObjectURL(sub.url)
        subtitleUrls.push(sub.url)
        // A translation that grew (SubMaker): the track laid before is swapped for this one, and keeps the choice made of it.
        const old = replacing ? Array.from(v.querySelectorAll('track')).find((el) => el.track === replacing) : undefined
        const wasShowing = !old || replacing!.mode === 'showing'
        const track = document.createElement('track')
        track.kind = 'subtitles'
        track.label = as?.label ?? sub.label
        const language = as?.language ?? sub.language
        if (language) track.srclang = language
        track.src = sub.url
        track.addEventListener('load', () => (placeCues(v, controlsUp), applyDelay(v)))
        v.append(track)
        if (old) {
            const gone = old.src
            old.remove()
            URL.revokeObjectURL(gone)
            const at = subtitleUrls.indexOf(gone)
            if (at >= 0) subtitleUrls.splice(at, 1)
        }
        if (wasShowing) {
            // Shown at once: the one just loaded is the one wanted.
            for (const tt of Array.from(v.textTracks)) if (tt.kind === 'subtitles' || tt.kind === 'captions') tt.mode = tt === track.track ? 'showing' : 'disabled'
        } else if (track.track) track.track.mode = 'disabled'
        setNotice('')
    }
    const loadSubtitle = async (picked: File) => {
        try {
            await addSubtitle(picked)
            // A file of their own is an explicit choice: the remembered online subtitle no longer stands (HX25).
            choseOther(item)
        } catch (e) {
            setNotice(e instanceof SubtitleError && e.why === 'vobsub' ? t('web.needs_app_picture_subtitles') : t('player.subtitle_load_failed', { file: picked.name }))
        }
    }
    const onControls = (up: boolean) => {
        controlsUp = up
        placeCues(video(), up)
    }
    // The look: size, colour, edge and box as custom properties on the video, which its cues read.
    let pictureHeight = 0
    const paintLook = () => {
        const v = video()
        for (const [name, value] of Object.entries(lookVars(look(), pictureHeight || container().clientHeight || 720))) v.style.setProperty(name, value)
        placeCues(v, controlsUp)
    }
    createEffect(() => (look(), ready() && paintLook()))
    onMount(() => {
        const watch = new ResizeObserver(() => {
            const h = container().clientHeight
            if (Math.abs(h - pictureHeight) > 8) (pictureHeight = h, paintLook())
        })
        watch.observe(container())
        onCleanup(() => watch.disconnect())
    })
    return { addSubtitle, loadSubtitle, onControls }
}
