// The subtitles a viewer can lay over the video: the tracks the element has, the files posted in the video's own
// chat (EG-F48), MSone's list, SubMaker's list and translations, and the pick remembered for this video (HX25).
import { createEffect, on, onCleanup, onMount, createSignal, type Accessor } from 'solid-js'
import { t } from '../../i18n'
import { answer, askOf, loadExtras } from '../../screens/posters'
import { settings } from '../../store'
import { call } from '../../tg/rpc'
import type { ChatSubtitle, WatchInfo } from '../../tg/types'
import { ChatSubtitleError } from '../chatsubs'
import { readChatSubtitle } from '../chatsubs-read'
import { episodeOf } from '../msone'
import { trackLabel as onlineLabel } from '../msone-sheet'
import { idFor, type Kind } from '../submaker'
import { createJobs } from '../submaker-job'
import * as subpick from '../subpick-use'
import { SubtitleError } from '../subtitles'
import type { Ctl, Sheet } from './types'

export interface SubtitleDeps {
    subs: Accessor<TextTrack[]>
    subOn: Accessor<TextTrack | null>
    /** Reads the element's tracks again into `subs` and `subOn`. */
    readSubs: () => void
    sheet: Accessor<Sheet>
    setSheet: (s: Sheet) => void
    flash: (text: string) => void
    duration: Accessor<number>
    info: () => WatchInfo | null
}

/** The new text track `add` brought, or null when it brought none. */
async function addedTrack(v: HTMLVideoElement, add: () => Promise<void>): Promise<TextTrack | null> {
    const before = new Set(Array.from(v.textTracks))
    await add()
    return Array.from(v.textTracks).find((tt) => !before.has(tt)) ?? null
}

export function useSubtitles(c: Ctl, d: SubtitleDeps) {
    const v = c.video
    const itemKey = () => `${c.item.chatId}:${c.item.messageId}`
    // The subtitle files posted in this video's chat (EG-F48): looked for once, when the Subtitles
    // picker (or More options, which leads to it) first opens. One search, answered best first; the
    // entry shows only when there are some, and a failure just leaves it out.
    const [chatSubs, setChatSubs] = createSignal<ChatSubtitle[]>([])
    const [chatNote, setChatNote] = createSignal('')
    const [chatBusy, setChatBusy] = createSignal<string | null>(null)
    let chatAsked = false
    createEffect(() => {
        if (chatAsked || (d.sheet() !== 'subtitles' && d.sheet() !== 'more')) return
        chatAsked = true
        const wanted = [navigator.language.slice(0, 2).toLowerCase(), 'en']
        call('chatSubtitles', [c.item.chatId, c.item.messageId, c.item.fileName || c.item.title, '', wanted]).then(setChatSubs, () => {})
    })
    /** The listed file read and laid over the video, or the sentence saying why not (a gone file leaves the list). */
    const pickChat = async (sub: ChatSubtitle) => {
        if (chatBusy()) return
        setChatBusy(sub.key)
        setChatNote('')
        const failed = t('player.subtitle_load_failed', { file: sub.name })
        try {
            const info = d.info()
            const own = episodeOf(c.item.fileName ?? '') ?? (info?.episode ? episodeOf(info.episode.code) : null)
            const file = await readChatSubtitle(sub, own)
            await c.addSubtitle(file, { label: sub.name.replace(/\.[^.]+$/, ''), language: sub.language ?? '' })
            subpick.choseOther(c.item)
            d.setSheet(null)
        } catch (e) {
            if (e instanceof ChatSubtitleError) {
                setChatNote(e.why === 'gone' ? t('tracks.in_chat_gone') : e.why === 'too-big' ? t('tracks.file_too_big') : e.why === 'offline' ? t('errors.offline') : failed)
                if (e.why === 'gone') {
                    setChatSubs((list) => list.filter((s) => s.key !== sub.key))
                    void call('chatSubtitlesForget', [c.item.chatId, c.item.messageId])
                    if (!chatSubs().length) d.setSheet('subtitles')
                }
            } else if (e instanceof SubtitleError) {
                setChatNote(e.why === 'vobsub' ? t('web.needs_app_picture_subtitles') : e.why === 'too-big' ? t('tracks.file_too_big') : failed)
            } else setChatNote(failed)
        } finally {
            setChatBusy(null)
        }
    }

    // The online subtitle laid over this video (picked from MSone, or brought back by itself): the one track the
    // remembered pick is about. Choosing it again ends an Off; Off is remembered; any other track ends the pick (HX25).
    let onlineTrack: TextTrack | null = null
    const layOnline = async (file: File, as: { label: string; language: string }) => {
        onlineTrack = await addedTrack(v, () => c.addSubtitle(file, as))
        d.readSubs()
    }
    // SubMaker (the viewer's own addon): a subtitle it lists loads as MSone's does; a translation it makes runs as a background
    // job whose track is replaced in place as the file grows, and ends with the video or the player (submaker-job.ts).
    let makerTrack: TextTrack | null = null
    const layMaker = async (file: File, as: { label: string; language: string }) => {
        await c.addSubtitle(file, as)
        d.readSubs()
    }
    const jobs = createJobs({
        videoKey: itemKey,
        duration: () => d.duration(),
        said: (what, language) => d.flash(what === 'started' ? t('submaker.started', { language }) : t('submaker.cancelled')),
        lay: async (srt, language, replace) => {
            const track = await addedTrack(v, () => c.addSubtitle(new File([srt], `${language}.srt`), { label: t('online.track_label', { language, provider: 'SubMaker' }), language: '' }, replace ? makerTrack : null))
            makerTrack = track ?? makerTrack
            if (!replace) subpick.choseOther(c.item)
            d.readSubs()
        },
    })
    onCleanup(() => jobs.dispose())
    createEffect(on(itemKey, () => ((makerTrack = null), jobs.dispose()), { defer: true }))
    /** The IMDb id the addon is asked about, from the poster lookup the page already makes (the apps' ids); null when it names none (posters off, no match, an anime AniList matched). */
    const submakerTarget = async (): Promise<{ kind: Kind; id: string } | null> => {
        const ask = askOf(c.item)
        if (!ask || !settings().posters) return null
        const found = await answer(ask)
        if (!found.match || !found.id || !found.kind) return null
        const m = /^S(\d+)E(\d+)/i.exec(d.info()?.episode?.code ?? '')
        const ep = m ? { season: Number(m[1]), episode: Number(m[2]) } : null
        if (found.kind === 'show' && !ep) return null
        const extras = await loadExtras(found, found.kind === 'show' ? ep : null)
        const id = idFor(extras.found ? extras.imdb : null, found.kind === 'show' ? ep!.season : null, found.kind === 'show' ? ep!.episode : null)
        return id ? { kind: found.kind === 'show' ? 'series' : 'movie', id } : null
    }

    const subLabel = (tt: TextTrack, i: number) => tt.label || (tt.language ? tt.language.toUpperCase() : t('player.track_number', { number: i + 1 }))
    const pickSub = (track: TextTrack | null) => {
        for (const tt of d.subs()) tt.mode = tt === track ? 'showing' : 'disabled'
        d.readSubs()
        if (!track) subpick.choseOff(c.item)
        else if (track === onlineTrack) subpick.choseOnline(c.item)
        else subpick.choseOther(c.item)
        d.flash(track ? t('player.subtitles_track', { track: subLabel(track, d.subs().indexOf(track)) }) : t('player.subtitles_off'))
    }
    const cycleSub = (back = false) => {
        const list = [null, ...d.subs()]
        const at = list.indexOf(d.subOn())
        pickSub(list[(at + (back ? -1 : 1) + list.length) % list.length])
    }
    // HX25, the start: the online subtitle picked for this video before comes back by itself (its own copy, the MSone
    // cache's file, or one download when nothing forbids it), and so does Off. The lookup began when the player opened
    // (Player's warmRestore), so the track is usually in before the first frame, and always before the viewer can enter
    // the iPhone's own full screen. A subtitle the viewer chose meanwhile is never replaced; nothing here shows an error.
    let leaving = false
    onCleanup(() => (leaving = true))
    onMount(() => {
        void subpick.takeRestore(c.item).then(async ({ found, wanted }) => {
            const showing = () => Array.from(v.textTracks).some((tt) => (tt.kind === 'subtitles' || tt.kind === 'captions') && tt.mode === 'showing')
            // Whatever the viewer chose while this was looked for (Off, a track, a file, MSone's list) stands over it.
            if (leaving || !wanted() || showing()) return
            if (found.kind === 'ready') {
                try {
                    await layOnline(found.file, { label: onlineLabel(), language: 'ml' })
                    if (!wanted()) return
                    if (!found.fromDevice) subpick.keepRestored(c.item, found.hit, found.file)
                    if (onlineTrack) d.flash(t('player.subtitles_track', { track: subLabel(onlineTrack, d.subs().indexOf(onlineTrack)) }))
                } catch {
                    // A copy that does not read is no pick: the viewer is simply asked again, no error card.
                    subpick.choseOther(c.item)
                }
            }
            // Off needs nothing laid: no subtitle shows by itself, and none is laid.
        })
    })
    return { chatSubs, chatNote, setChatNote, chatBusy, pickChat, layOnline, layMaker, jobs, submakerTarget, subLabel, pickSub, cycleSub }
}
