
import { type Frames } from '../storyboard'
import { type FrameGrabber } from '../seekpreview'
import { type Chapter } from '../chapters'
import type { SoundTrack } from '../engines'
import type { VideoCopy, VideoItem, WatchInfo } from '../../tg/types'
// What the overlay needs from the player (Chrome.tsx) and the sheets it can open.

/** What the overlay needs from the player: its video, the item, and the actions that load things. */
export interface Ctl {
    video: HTMLVideoElement
    root: HTMLElement
    item: VideoItem
    info: () => WatchInfo | null
    /** A Play all run: the end of a video goes on to the next one whatever the Autoplay setting says. */
    playAll?: () => boolean
    close: () => void
    play: (next: VideoItem | null) => void
    /** A status sheet is up (stall, failure, needs the app): the controls stand aside. */
    blocked: () => boolean
    audios: () => SoundTrack[]
    audio: () => SoundTrack | null
    canSwitchAudio: () => boolean
    pickAudio: (index: number) => void
    trackLabel: (track: SoundTrack, index: number) => string
    copies: VideoCopy[]
    sourceKey: () => string
    sourceHeight: () => number
    pickQuality: (key: string) => void
    qualityLabel: (height: number) => string
    readout: () => string
    details: () => string
    /** Report stutter (WB18): null when this browser has no frame counters, so the line is not offered. */
    stutter: () => { press: () => 'kept' | 'already' } | null
    /** What the page's logs weigh, scrubbed, for the line under Copy details. */
    logsSize: () => string
    /** The scrubbed logs as text, for the selected copy a browser that refuses the clipboard gets. */
    logsText: () => string
    /** Copy details with the logs, written to the clipboard inside the press itself; false when the browser refused. */
    copyLogs: () => Promise<boolean>
    loadSubtitleFile: () => void
    /** Lays a subtitle file over the video and shows it; throws when the file cannot be read. */
    addSubtitle: (file: File, as?: { label: string; language: string }, replacing?: TextTrack | null) => Promise<void>
    frames: () => Frames | null
    /** The video's own frames for the seek bar's card, for a natively played file; null otherwise. */
    grabber: () => FrameGrabber | null
    /** Where the video was resumed from, for the Start over offer; null when it starts at the beginning. */
    resumedAt: () => number | null
    startOver: () => void
    /** The controls came up or went: subtitles move up out of the bar's way while they show. */
    onControls?: (up: boolean) => void
    /** The file's chapter marks (empty when it has none or they are not read). */
    chapters: () => Chapter[]
    /** How much of the file has arrived (0 to 1); null once all of it has. */
    loaded: () => number | null
    /** Whether the video is on the Watched list, and the menu's way to change it. */
    watched: () => boolean
    markWatched: (on: boolean) => void
}

export type Sheet = 'subtitles' | 'audio' | 'speed' | 'copies' | 'episodes' | 'more' | 'shape' | 'details' | 'options' | 'sleep' | 'bookmarks' | 'timing' | 'shortcuts' | 'still' | 'msone' | 'submaker' | 'chatsubs' | 'goto' | 'rotate' | 'boost' | 'link' | 'chapters' | null
