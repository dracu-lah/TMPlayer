// What the page does with a message link (WB16, U9): who is offered it, the time added the way the
// apps' LinkAtTime does (core's LinkAtTimeTest rows), the copy and its fallback, the asking kept
// once and never kept when it failed, and an answer from the worker that is not one.
import assert from 'node:assert/strict'
import { beforeEach, test } from 'node:test'
import type { LinkAnswer } from '../tg/links'
import { MAX_START_S, copyLink, copyText, forgetLinks, isTgLink, linkOf, linkOffered, withStart, type Asker } from './tgLinks.ts'

beforeEach(forgetLinks)

test('a video is offered a link unless saving is restricted or it is played from pieces', () => {
    assert.equal(linkOffered({ canBeSaved: true }), true)
    assert.equal(linkOffered({ canBeSaved: false }), false)
    assert.equal(linkOffered({ canBeSaved: true, joined: { pieces: [] } as never }), false)
    assert.equal(linkOffered({ canBeSaved: undefined as never }), false)
})

test('the time goes into the link as ?t=, replacing an old one and keeping the rest', () => {
    assert.equal(withStart('https://t.me/clap_films/42', 90), 'https://t.me/clap_films/42?t=90')
    assert.equal(withStart('https://t.me/c/1234567890/7/42', 3723), 'https://t.me/c/1234567890/7/42?t=3723')
    assert.equal(withStart('https://t.me/clap_films/42?t=5', 90), 'https://t.me/clap_films/42?t=90')
    assert.equal(withStart('https://t.me/clap_films/42?comment=7&t=5', 90), 'https://t.me/clap_films/42?comment=7&t=90')
    assert.equal(withStart('https://t.me/clap_films/42?t=5', 0), 'https://t.me/clap_films/42')
    assert.equal(withStart('https://t.me/clap_films/42', 59.9), 'https://t.me/clap_films/42?t=59')
})

test('a silly position cannot make a silly number', () => {
    assert.equal(withStart('https://t.me/a_chat/1', -5), 'https://t.me/a_chat/1')
    assert.equal(withStart('https://t.me/a_chat/1', Number.NaN), 'https://t.me/a_chat/1')
    assert.equal(withStart('https://t.me/a_chat/1', Infinity), 'https://t.me/a_chat/1')
    assert.equal(withStart('https://t.me/a_chat/1', 1e12), `https://t.me/a_chat/1?t=${MAX_START_S}`)
})

test('only a Telegram address is a link: nothing else goes to an anchor or the clipboard as one', () => {
    assert.equal(isTgLink('https://t.me/clap_films/42'), true)
    for (const bad of ['http://t.me/a/1', 'https://t.me.evil.com/a/1', 'https://evil.com/https://t.me/', 'javascript:alert(1)', 'tg://resolve?domain=a', '', 'https://t.me/' + 'x'.repeat(400), 5, null, undefined, {}]) {
        assert.equal(isTgLink(bad), false, String(bad))
    }
    assert.equal(withStart('https://evil.com/a/1', 5), 'https://evil.com/a/1')
})

const answers = (a: LinkAnswer) => {
    const calls: string[] = []
    const ask: Asker = async (chat, message) => {
        calls.push(`${chat}:${message}`)
        return a
    }
    return { ask, calls }
}
const item = { chatId: -1001234567890, messageId: 42 }

test('Copy link puts the link on the clipboard and says so', async () => {
    const { ask } = answers({ link: 'https://t.me/c/1234567890/42' })
    const written: string[] = []
    const out = await copyLink(item, 0, ask, async (text) => (written.push(text), true))
    assert.deepEqual(out, { done: 'copied', link: 'https://t.me/c/1234567890/42' })
    assert.deepEqual(written, ['https://t.me/c/1234567890/42'])
})

test('Copy link at this time adds the second the video is at', async () => {
    const { ask } = answers({ link: 'https://t.me/clap_films/42' })
    const out = await copyLink(item, 125, ask, async () => true)
    assert.deepEqual(out, { done: 'copied', link: 'https://t.me/clap_films/42?t=125' })
})

test('a refused clipboard hands the link back to show for copying by hand', async () => {
    const { ask } = answers({ link: 'https://t.me/clap_films/42' })
    assert.deepEqual(await copyLink(item, 0, ask, async () => false), { done: 'shown', link: 'https://t.me/clap_films/42' })
})

test('a message that is gone, or a chat with no links, is said, and nothing is copied', async () => {
    for (const none of ['gone', 'nolink'] as const) {
        forgetLinks()
        const { ask } = answers({ none })
        let wrote = 0
        assert.deepEqual(await copyLink(item, 30, ask, async () => (wrote++, true)), { done: 'none', why: none })
        assert.equal(wrote, 0)
    }
})

test('the same video is asked once while its answer is a link, so the second tap and the other menu cost nothing', async () => {
    const { ask, calls } = answers({ link: 'https://t.me/clap_films/42' })
    await Promise.all([linkOf(item, ask), linkOf(item, ask)])
    await linkOf(item, ask)
    assert.deepEqual(calls, ['-1001234567890:42'])
})

test('a failed asking, or a gone answer, is asked again next time', async () => {
    let n = 0
    const flaky: Asker = async () => {
        n++
        if (n === 1) throw new Error('no line')
        if (n === 2) return { none: 'gone' }
        return { link: 'https://t.me/clap_films/42' }
    }
    assert.deepEqual(await linkOf(item, flaky), { none: 'nolink' })
    assert.deepEqual(await linkOf(item, flaky), { none: 'gone' })
    assert.deepEqual(await linkOf(item, flaky), { link: 'https://t.me/clap_films/42' })
    assert.deepEqual(await linkOf(item, flaky), { link: 'https://t.me/clap_films/42' })
    assert.equal(n, 3)
})

test('a partial link (a lookup failed) is not kept: the next ask goes to Telegram again; a protected answer is understood and not kept', async () => {
    let n = 0
    const ask: Asker = async () => (++n === 1 ? { link: 'https://t.me/c/1234567890/42', partial: true } : n === 2 ? { none: 'protected' } : { link: 'https://t.me/club/42' })
    assert.deepEqual(await linkOf(item, ask), { link: 'https://t.me/c/1234567890/42', partial: true })
    assert.deepEqual(await linkOf(item, ask), { none: 'protected' })
    assert.deepEqual(await linkOf(item, ask), { link: 'https://t.me/club/42' })
    assert.deepEqual(await linkOf(item, ask), { link: 'https://t.me/club/42' })
    assert.equal(n, 3)
    forgetLinks()
    assert.deepEqual(await copyLink(item, 0, async () => ({ none: 'protected' }), async () => true), { done: 'none', why: 'protected' })
})

test('forgetting the links (sign out, delete your data) makes the next ask go to Telegram', async () => {
    const { ask, calls } = answers({ link: 'https://t.me/clap_films/42' })
    await linkOf(item, ask)
    forgetLinks()
    await linkOf(item, ask)
    assert.equal(calls.length, 2)
})

test('an answer that is not one (wrong type, another site) is no link', async () => {
    for (const bad of [null, 'https://t.me/a/1', { link: 'https://evil.com/a' }, { link: 5 }, { none: 'other' }, {}]) {
        forgetLinks()
        const out = await copyLink(item, 0, async () => bad as never, async () => true)
        assert.deepEqual(out, { done: 'none', why: 'nolink' }, JSON.stringify(bad))
    }
})

test('the kept answers are bounded', async () => {
    const { ask, calls } = answers({ link: 'https://t.me/clap_films/42' })
    for (let i = 1; i <= 100; i++) await linkOf({ chatId: -1001234567890, messageId: i }, ask)
    await linkOf({ chatId: -1001234567890, messageId: 1 }, ask)
    assert.equal(calls.length, 101)
})

test('copyText: the clipboard, and false when it refuses or is missing', async () => {
    const got: string[] = []
    assert.equal(await copyText('a', { writeText: async (t) => void got.push(t) }), true)
    assert.deepEqual(got, ['a'])
    assert.equal(await copyText('a', { writeText: async () => Promise.reject(new DOMException('no', 'NotAllowedError')) }), false)
    assert.equal(await copyText('a', undefined), false)
})
