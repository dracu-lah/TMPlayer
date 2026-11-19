// The link to a video's message (WB16, U9) against a fake account: public and private chats, a forum
// topic, a message that is gone, a chat the viewer left, and ids that cannot be a message. The link
// text itself is core's (WebRulesTest holds the table); this checks what is asked and what is given.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import type { Message } from '@mtcute/web'
import * as core from '../rules/core/tmplayer-rules.mjs'
import type { Rules } from '../rules'
import { messageLink, telegramLook, type LinkLook } from './links.ts'
import { topicOf } from './media.ts'

core.provideCatalog('en', readFileSync(new URL('../../../core/src/commonMain/resources/i18n/en.json', import.meta.url), 'utf8'))
const rules = core as unknown as Rules

const PRIVATE = -1001234567890
const look = (o: { name?: string | null | Error; topic?: number | null | Error; shareable?: boolean } = {}): LinkLook & { calls: string[] } => {
    const calls: string[] = []
    return {
        calls,
        async username(chatId) {
            calls.push(`name ${chatId}`)
            if (o.name instanceof Error) throw o.name
            return o.name ?? null
        },
        async message(chatId, messageId) {
            calls.push(`topic ${chatId} ${messageId}`)
            if (o.topic instanceof Error) throw o.topic
            if (o.topic === null) return null
            return { topic: o.topic ?? 0, shareable: o.shareable ?? true }
        },
    }
}
test('a public chat gives t.me/<name>/<id>, a private one t.me/c/<id>/<id>', async () => {
    assert.deepEqual(await messageLink(look({ name: 'clap_films' }), rules, PRIVATE, 42), { link: 'https://t.me/clap_films/42' })
    assert.deepEqual(await messageLink(look(), rules, PRIVATE, 42), { link: 'https://t.me/c/1234567890/42' })
})

test('a forum topic sits between the chat and the message, public and private', async () => {
    assert.deepEqual(await messageLink(look({ name: 'club', topic: 7 }), rules, PRIVATE, 42), { link: 'https://t.me/club/7/42' })
    assert.deepEqual(await messageLink(look({ topic: 7 }), rules, PRIVATE, 42), { link: 'https://t.me/c/1234567890/7/42' })
})

test('a message that is gone has no link, and says so', async () => {
    assert.deepEqual(await messageLink(look({ name: 'clap_films', topic: null }), rules, PRIVATE, 42), { none: 'gone' })
})

test('a chat the viewer left, or no line to Telegram, still gives the link the ids make', async () => {
    const left = look({ name: new Error('CHANNEL_PRIVATE'), topic: new Error('CHANNEL_PRIVATE') })
    assert.deepEqual(await messageLink(left, rules, PRIVATE, 42), { link: 'https://t.me/c/1234567890/42', partial: true })
})

test('a person, a small group, or an id that cannot be one has no link and nothing is asked', async () => {
    for (const chat of [777, -4242, -1_000_000_000_000, 0, Number.NaN, Infinity, 1.5, '5', null, undefined, {}]) {
        const l = look({ name: 'someone' })
        assert.deepEqual(await messageLink(l, rules, chat, 42), { none: 'nolink' }, String(chat))
        assert.deepEqual(l.calls, [], String(chat))
    }
    for (const message of [0, -3, 1.5, Number.NaN, Infinity, '9', null, undefined, 2 ** 60]) {
        const l = look()
        assert.deepEqual(await messageLink(l, rules, PRIVATE, message), { none: 'nolink' }, String(message))
        assert.deepEqual(l.calls, [], String(message))
    }
})

test('a message in a forum topic reads its topic from the reply header, General and plain chats read none', () => {
    const msg = (replyTo: unknown) => ({ raw: { _: 'message', replyTo } }) as unknown as Message
    assert.equal(topicOf(msg({ _: 'messageReplyHeader', forumTopic: true, replyToTopId: 9, replyToMsgId: 3 })), 9)
    assert.equal(topicOf(msg({ _: 'messageReplyHeader', forumTopic: true, replyToMsgId: 5 })), 5)
    assert.equal(topicOf(msg({ _: 'messageReplyHeader', replyToMsgId: 5 })), 0)
    assert.equal(topicOf(msg(undefined)), 0)
    assert.equal(topicOf({ raw: { _: 'messageService' } } as unknown as Message), 0)
})

test('a message that may not be passed on (restrict saving, self-destructing) has no link, whatever the stored card said', async () => {
    assert.deepEqual(await messageLink(look({ name: 'clap_films', shareable: false }), rules, PRIVATE, 42), { none: 'protected' })
    // Gone wins over protected: there is no message to read flags from.
    assert.deepEqual(await messageLink(look({ topic: null, shareable: false }), rules, PRIVATE, 42), { none: 'gone' })
})

test('a lookup that failed makes a partial link, so the page does not keep it; a whole answer is not partial', async () => {
    // Public chat whose name could not be read: the private form, partial. Topic that could not be read: no topic, partial.
    assert.deepEqual(await messageLink(look({ name: new Error('offline'), topic: 7 }), rules, PRIVATE, 42), { link: 'https://t.me/c/1234567890/7/42', partial: true })
    assert.deepEqual(await messageLink(look({ name: 'club', topic: new Error('offline') }), rules, PRIVATE, 42), { link: 'https://t.me/club/42', partial: true })
    assert.deepEqual(await messageLink(look({ name: 'club', topic: 7 }), rules, PRIVATE, 42), { link: 'https://t.me/club/7/42' })
})

test('telegramLook reads the three flags off the message as Telegram has it now', async () => {
    const msgs = new Map<number, unknown>()
    const tg = {
        async getPeer() {
            return { username: 'club' }
        },
        async getMessages(_: number, id: number) {
            return [msgs.get(id) ?? null]
        },
    } as unknown as Parameters<typeof telegramLook>[0]
    const message = (raw: Record<string, unknown>, chatRaw: Record<string, unknown> = {}) => ({ chat: { raw: chatRaw }, raw: { _: 'message', ...raw } })
    msgs.set(1, message({}))
    msgs.set(2, message({ noforwards: true }))
    msgs.set(3, message({}, { noforwards: true }))
    msgs.set(4, message({ media: { _: 'messageMediaDocument', ttlSeconds: 10 } }))
    msgs.set(5, message({ media: { _: 'messageMediaDocument' }, replyTo: { _: 'messageReplyHeader', forumTopic: true, replyToTopId: 9 } }))
    const look = telegramLook(tg)
    assert.deepEqual(await look.message(PRIVATE, 1), { topic: 0, shareable: true })
    assert.deepEqual(await look.message(PRIVATE, 2), { topic: 0, shareable: false })
    assert.deepEqual(await look.message(PRIVATE, 3), { topic: 0, shareable: false })
    assert.deepEqual(await look.message(PRIVATE, 4), { topic: 0, shareable: false })
    assert.deepEqual(await look.message(PRIVATE, 5), { topic: 9, shareable: true })
    assert.equal(await look.message(PRIVATE, 99), null)
    assert.equal(await look.username(PRIVATE), 'club')
})
