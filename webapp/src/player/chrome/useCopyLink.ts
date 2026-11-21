// Copy link at this time (the apps' CopyLinkAtTime): the link is asked as More options opens, so the tap that copies it has it.
// Not for a chat that restricts saving, nor a video played from pieces. `shown` is the link, when the browser refused the clipboard.
import { createEffect, createSignal } from 'solid-js'
import { t } from '../../i18n'
import { call } from '../../tg/rpc'
import { copyLink, linkOf, linkOffered } from '../../ui/tgLinks'
import type { Ctl, Sheet } from './types'

export function useCopyLink(c: Ctl, sheet: () => Sheet, setSheet: (s: Sheet) => void, flash: (text: string) => void) {
    const askLink = (chatId: number, messageId: number) => call('messageLink', [chatId, messageId])
    const [shown, setShown] = createSignal('')
    // Telegram says this video may not be passed on now (the chat became protected, or the message destroys itself): the entry goes.
    const [blocked, setBlocked] = createSignal(false)
    let asked: string | null = null
    createEffect(() => {
        if (sheet() !== 'more' || !linkOffered(c.item)) return
        const key = `${c.item.chatId}:${c.item.messageId}`
        if (asked !== key) {
            asked = key
            setBlocked(false)
            void linkOf(c.item, askLink).then((a) => asked === key && setBlocked(!('link' in a) && a.none === 'protected'))
        }
    })
    let busy = false
    const copyAtTime = async () => {
        if (busy || !linkOffered(c.item)) return
        busy = true
        const at = Math.floor(c.video.currentTime)
        setSheet(null)
        try {
            const out = await copyLink(c.item, at, askLink)
            if (out.done === 'shown') {
                setShown(out.link)
                setSheet('link')
            } else {
                if (out.done === 'none' && out.why === 'protected') setBlocked(true)
                flash(out.done === 'copied' ? t('common.link_copied') : out.why === 'gone' ? t('link.not_found') : t('share.no_message_links'))
            }
        } finally {
            busy = false
        }
    }
    return { shown, blocked, copyAtTime, offered: () => linkOffered(c.item) && !blocked() }
}
