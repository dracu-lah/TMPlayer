// SubMaker in the player's subtitle menu (the viewer's own install of a Stremio subtitles addon, set
// in Settings): the subtitles it lists for this video, and "Make <Language>" entries that have it
// translate with the viewer's own AI key. A file loads as a dropped or MSone subtitle does. A Make
// entry asks once per language, then runs as a background job (submaker-job.ts): this sheet closes,
// playback goes on, and Chrome shows the progress line (SubmakerJobLine). Nothing here shows or logs
// the address: the list's url and every entry's url stay inside calls.
import { createSignal, For, onCleanup, onMount, Show } from 'solid-js'
import { Add01Icon, AiMagicIcon, Cancel01Icon, Refresh01Icon } from '@hugeicons/core-free-icons'
import { Icon } from '../ui/Icon'
import { language as shownLanguage, t } from '../i18n'
import { Choice, Picker } from './sheets'
import { ServiceBadge } from '../ui/ServiceBadge'
import { fetchList, fetchSubtitle, fetchTextWith, hasConsent, listUrl, type Entry, type Kind } from './submaker'
import type { JobView, Jobs } from './submaker-job'
import type { Why } from './submaker'

/** A language word ("eng", "Malayalam", "pt-BR") as the viewer's language writes it; the word itself when it is not a code. */
export function languageText(lang: string): string {
    try {
        const code = lang.trim().toLowerCase()
        if (/^[a-z]{2,3}(-[a-z0-9]{2,8})?$/.test(code)) {
            const name = new Intl.DisplayNames([shownLanguage()], { type: 'language' }).of(code)
            if (name && name.toLowerCase() !== code) return name
        }
    } catch {}
    return lang.trim()
}

/** Why a translation stopped, in the apps' words. */
function whyText(why: Why): string {
    switch (why) {
        case 'offline':
            return t('online.notice_offline')
        case 'refused':
            return t('submaker.rejected')
        case 'empty':
            return t('submaker.empty')
        default:
            return t('online.notice_unreadable')
    }
}

type Phase = 'searching' | 'results' | 'failed' | 'consent'

export function SubmakerSheet(props: {
    base: string
    /** The video's id for the addon ("tt..." or "tt...:S:E"), or null when this video cannot be named. */
    resolve: () => Promise<{ kind: Kind; id: string } | null>
    jobs: Jobs
    /** Lays a subtitle file over the video and chooses it; throws when it cannot be read. */
    onLoad: (file: File, as: { label: string; language: string }) => Promise<void>
    phone: boolean
    panel: boolean
    anchor: DOMRect | null
    root: HTMLElement
    onClose: () => void
    onBack?: () => void
}) {
    const [phase, setPhase] = createSignal<Phase>('searching')
    const [line, setLine] = createSignal('')
    const [entries, setEntries] = createSignal<Entry[]>([])
    const [asking, setAsking] = createSignal<Entry | null>(null)
    /** The entry being fetched: another press meanwhile is ignored. */
    const [busy, setBusy] = createSignal<string | null>(null)
    // Closing the sheet stops whatever it waits for; a translation already started is the job's, not this sheet's.
    const work = new AbortController()
    let closed = false
    onCleanup(() => {
        closed = true
        work.abort()
    })

    const fail = (text: string) => {
        setLine(text)
        setPhase('failed')
    }

    const search = async () => {
        setPhase('searching')
        setLine('')
        let target: Awaited<ReturnType<typeof props.resolve>> = null
        try {
            target = await props.resolve()
        } catch {}
        if (closed) return
        const url = target ? listUrl(props.base, target.kind, target.id) : null
        if (!url) return fail(t('submaker.no_id'))
        const r = await fetchList(url, fetchTextWith(), work.signal)
        if (closed) return
        if (!r.ok) return r.why === 'aborted' ? undefined : fail(t(r.why === 'offline' ? 'online.notice_offline' : 'online.notice_unreadable'))
        if (!r.entries.length) return fail(t('submaker.no_results'))
        setEntries(r.entries)
        setPhase('results')
    }
    onMount(() => void search())

    const files = () => entries().filter((e) => !e.make)
    const makes = () => entries().filter((e) => e.make)

    const loadFile = async (e: Entry) => {
        if (busy() !== null) return
        setBusy(e.id)
        try {
            const r = await fetchSubtitle(e.url, fetchTextWith(), work.signal)
            if (closed) return
            if (!r.ok) return r.why === 'aborted' ? undefined : void setLine(t(r.why === 'offline' ? 'online.notice_offline' : 'online.notice_unreadable'))
            const name = languageText(e.lang)
            try {
                await props.onLoad(new File([r.srt], `${name}.srt`), { label: t('online.track_label', { language: name, provider: 'SubMaker' }), language: /^[a-z]{2,3}$/i.test(e.lang.trim()) ? e.lang.trim().toLowerCase() : '' })
            } catch {
                if (!closed) setLine(t('online.notice_unreadable'))
                return
            }
            if (!closed) props.onClose()
        } finally {
            if (!closed) setBusy(null)
        }
    }

    const make = (e: Entry) => {
        const name = e.make ?? languageText(e.lang)
        if (!hasConsent(name)) {
            setAsking(e)
            return setPhase('consent')
        }
        props.jobs.start(e, name)
        props.onClose()
    }

    return (
        <Picker title={t('submaker.title')} note={phase() === 'results' ? line() || undefined : undefined} phone={props.phone} panel={props.panel} anchor={props.anchor} root={props.root} onClose={props.onClose} onBack={props.onBack}>
            <ServiceBadge service="submaker" size={24} name />
            <Show when={phase() === 'searching'}>
                <p class="pc-menu-note pc-submaker-line" role="status">{t('submaker.searching')}</p>
            </Show>
            <Show when={phase() === 'failed'}>
                <p class="pc-menu-note pc-submaker-line" role="status">{line()}</p>
                <Choice icon={Refresh01Icon} text={t('common.try_again')} phone={props.phone} onClick={() => void search()} />
            </Show>
            <Show when={phase() === 'consent' && asking()}>
                {(e) => {
                    const name = () => e().make ?? languageText(e().lang)
                    return (
                        <>
                            <p class="pc-menu-note pc-submaker-line" role="status">
                                <strong>{t('submaker.ask_title', { language: name() })}</strong>
                                <br />
                                {t('submaker.ask_message')}
                            </p>
                            <Choice icon={AiMagicIcon} text={t('submaker.ask_confirm')} phone={props.phone} onClick={() => (props.jobs.start(e(), name()), props.onClose())} />
                            <Choice icon={Cancel01Icon} text={t('common.cancel')} phone={props.phone} onClick={() => setPhase('results')} />
                        </>
                    )
                }}
            </Show>
            <Show when={phase() === 'results'}>
                <For each={files()}>
                    {(e) => <Choice icon={Add01Icon} text={languageText(e.lang)} detail={e.label !== e.lang ? e.label : undefined} phone={props.phone} disabled={busy() !== null && busy() !== e.id} onClick={() => void loadFile(e)} />}
                </For>
                <For each={makes()}>
                    {(e) => <Choice icon={AiMagicIcon} text={t('submaker.translate_to', { language: e.make! })} detail={t('submaker.translate_detail')} phone={props.phone} onClick={() => make(e)} />}
                </For>
            </Show>
        </Picker>
    )
}

/**
 * The progress line over the picture while a translation runs, and after it: "Translating, 40 lines
 * so far" with Cancel, then "ready" or "did not work" with Try again, each with a way to close it.
 * It never takes the keys of the player: only its own buttons are in the tab order.
 */
export function SubmakerJobLine(props: { jobs: Jobs }) {
    const words = (view: JobView): string => {
        if (view.phase === 'running') return view.lines > 0 ? t('submaker.progress', { language: view.language, count: view.lines }) : t('submaker.progress_waiting', { language: view.language })
        if (view.phase === 'done') return t('submaker.done_line', { language: view.language, count: view.lines })
        return t('submaker.failed_line', { language: view.language, reason: whyText(view.why) })
    }
    const v = () => props.jobs.view()
    return (
        <Show when={v()}>
            {(view) => (
                <div class="pc-submaker-job" role="status">
                    <p>{words(view())}</p>
                    <Show when={view().phase === 'running'}>
                        <button type="button" class="pc-sheet-button" onClick={() => props.jobs.cancel()}>{t('submaker.cancel')}</button>
                    </Show>
                    <Show when={view().phase === 'failed'}>
                        <button type="button" class="pc-sheet-button" onClick={() => props.jobs.retry()}>{t('common.try_again')}</button>
                    </Show>
                    <Show when={view().phase !== 'running'}>
                        <button type="button" class="pc-submaker-close" aria-label={t('common.close')} onClick={() => props.jobs.dismiss()}>
                            <Icon icon={Cancel01Icon} size={20} />
                        </button>
                    </Show>
                </div>
            )}
        </Show>
    )
}
