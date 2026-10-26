import { TgFile } from '../stream/reader'
import type { FileRef, Storyboard } from '../tg/types'

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
 * one `seconds,x,y` line per frame. The seek bar's preview card draws the frame for a time
 * ([frameAt]) as a slice of the sprite, as the apps' StoryboardPreview does.
 */
export async function storyboardFrames(sb: Storyboard): Promise<Frames> {
    const [sprite, map] = await Promise.all([readWhole(sb.sprite), readWhole(sb.map)])
    const url = URL.createObjectURL(new Blob([sprite as BlobPart], { type: 'image/jpeg' }))
    let width = 0
    let height = 0
    const frames: Frames['frames'] = []
    for (const line of new TextDecoder().decode(map).split('\n')) {
        if (line.startsWith('frame_width=')) width = Number(line.slice(12))
        else if (line.startsWith('frame_height=')) height = Number(line.slice(13))
        else {
            const [t, x, y] = line.split(',').map(Number)
            if (line.includes(',') && [t, x, y].every(Number.isFinite)) frames.push({ t, x, y })
        }
    }
    if (!width || !height || !frames.length) {
        URL.revokeObjectURL(url)
        throw new Error('Unreadable storyboard')
    }
    frames.sort((a, b) => a.t - b.t)
    const spriteWidth = Math.max(...frames.map((f) => f.x)) + width
    const spriteHeight = Math.max(...frames.map((f) => f.y)) + height
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
