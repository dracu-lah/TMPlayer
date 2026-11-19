// The link to a video's message in Telegram (WB16, the apps' U9 Copy link): `t.me/<name>/<id>` for a
// public chat, `t.me/c/<id>/<id>` for a private channel or supergroup, a forum topic between, built
// by core's rules (tgPostLink) from what Telegram says. This file is the asking: the chat's public
// name and the message itself (which tells a message that is gone, and its topic). It takes its two
// Telegram calls as plain functions, so `node --test` runs it against a fake account.
import type { TelegramClient } from '@mtcute/web'
import type { Rules } from '../rules'
import { chatProtects, topicOf } from './media.ts'

/**
 * What the page gets: the link, or why there is none: a message gone, a chat that has no links to its messages, or a video that
 * may not be passed on ('protected': the chat or message restricts saving, or the message destroys itself), which hides the entries.
 * `partial` is a link made without something Telegram could not be asked (the public name or the topic): it is not kept.
 */
export type LinkAnswer = { link: string; partial?: true } | { none: 'gone' | 'nolink' | 'protected' }

/** A Bot API chat id of a channel or supergroup is -100 and its own number; a person and a small group have no message links. */
const CHANNEL_BASE = -1_000_000_000_000

export interface LinkLook {
    /** The chat's public username, null for a private one. Throws when the chat is not known (left, never joined). */
    username(chatId: number): Promise<string | null>
    /**
     * The message as it is now: its forum topic (0 for none) and whether it may be passed on (not under restrict saving, the chat's or
     * the message's, and not self-destructing), or null when Telegram says there is no such message. Throws when Telegram cannot be asked.
     */
    message(chatId: number, messageId: number): Promise<{ topic: number; shareable: boolean } | null>
}

export async function messageLink(look: LinkLook, rules: Pick<Rules, 'tgPostLink'>, chatId: unknown, messageId: unknown): Promise<LinkAnswer> {
    if (typeof chatId !== 'number' || typeof messageId !== 'number') return { none: 'nolink' }
    if (!Number.isSafeInteger(chatId) || !Number.isSafeInteger(messageId) || messageId < 1) return { none: 'nolink' }
    if (chatId >= CHANNEL_BASE) return { none: 'nolink' }
    // Either call may fail (offline, a chat the viewer left): the link is still built from the ids, which is what Telegram's own apps do,
    // but it is marked partial so the page does not keep it (the next try asks again).
    let partial = false
    const [name, message] = await Promise.all([
        look.username(chatId).catch(() => ((partial = true), null)),
        look.message(chatId, messageId).catch(() => ((partial = true), undefined)),
    ])
    if (message === null) return { none: 'gone' }
    if (message && !message.shareable) return { none: 'protected' }
    const link = rules.tgPostLink(name, chatId, message?.topic ?? 0, messageId, 0)
    return link ? (partial ? { link, partial: true } : { link }) : { none: 'nolink' }
}

/** The two calls through mtcute. */
export function telegramLook(tg: TelegramClient): LinkLook {
    return {
        async username(chatId) {
            const peer = await tg.getPeer(chatId)
            return peer.username ?? null
        },
        async message(chatId, messageId) {
            const [msg] = await tg.getMessages(chatId, messageId)
            if (!msg) return null
            const raw = msg.raw._ === 'message' ? msg.raw : null
            const destroys = raw?.media?._ === 'messageMediaDocument' && !!raw.media.ttlSeconds
            return { topic: topicOf(msg), shareable: !raw?.noforwards && !chatProtects(msg) && !destroys }
        },
    }
}
