import { TgFile } from '../stream/reader'
import type { FileRef, Storyboard } from '../tg/types'
import { parseStoryboardMap } from './seekpreview'

async function readWhole(f: FileRef): Promise<Uint8Array> {
    const file = new TgFile(f.key, f.size, 0)
    file.wantAhead = false
    try {
        return await file.read(0, f.size)
    } finally {
        file.close()
    }
}

/** Telegram's storyboard, ready to draw: the sprite, one frame's size, and where each frame starts. */
export interface Frames {
    url: string
    /** One frame's size in the sprite. */
    width: number
    height: number
    /** The whole sprite's size, for scaling it as a background. */
    spriteWidth: number
    spriteHeight: number
    frames: { t: number; x: number; y: number }[]
}

/**
 * Telegram's storyboard: a JPEG sprite and a map, `file=`, `frame_width=`, `frame_height=` and then
 * one `seconds,x,y` line per frame (or a WebVTT map, see [parseStoryboardMap]). The seek bar's preview card draws the frame for a time
 * ([frameAt]) as a slice of the sprite, as the apps' StoryboardPreview does.
 */
export async function storyboardFrames(sb: Storyboard): Promise<Frames> {
    const [sprite, map] = await Promise.all([readWhole(sb.sprite), readWhole(sb.map)])
    const parsed = parseStoryboardMap(new TextDecoder().decode(map))
    if (!parsed) throw new Error('Unreadable storyboard')
    const { width, height, frames } = parsed
    const url = URL.createObjectURL(new Blob([sprite as BlobPart], { type: 'image/jpeg' }))
    const spriteWidth = frames.reduce((m, f) => Math.max(m, f.x), 0) + width
    const spriteHeight = frames.reduce((m, f) => Math.max(m, f.y), 0) + height
    return { url, width, height, spriteWidth, spriteHeight, frames }
}

/** The frame showing at `seconds`: the last one starting at or before it. */
export function frameAt(f: Frames, seconds: number): { x: number; y: number } {
    let lo = 0
    let hi = f.frames.length - 1
    while (lo < hi) {
        const mid = (lo + hi + 1) >> 1
        if (f.frames[mid].t <= seconds) lo = mid
        else hi = mid - 1
    }
    return f.frames[lo]
}
