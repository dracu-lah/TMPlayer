import { test } from 'node:test'
import assert from 'node:assert/strict'
import { cleanHome } from './homeSettings.ts'

test('good values stand', () => {
    const good = { carousel: false, homeContinue: true, homeRecent: false, homeStarred: true }
    assert.deepEqual(cleanHome(good), good)
})

test('garbage: wrong types fall back to the defaults', () => {
    const bad = cleanHome({ carousel: 'yes', homeContinue: 1, homeRecent: null, homeStarred: {} } as never) as Record<string, unknown>
    assert.equal('carousel' in bad, false)
    assert.equal('homeContinue' in bad, false)
    assert.equal('homeRecent' in bad, false)
    assert.equal('homeStarred' in bad, false)
})

test("an older build's chosen chats are dropped", () => {
    const old = cleanHome({ carousel: true, recentSource: 'chosen', recentChats: [-1001, 5] } as never) as Record<string, unknown>
    assert.deepEqual(old, { carousel: true })
})

test('other keys are left alone', () => {
    assert.deepEqual(cleanHome({ theme: 'dark' } as never), { theme: 'dark' })
})

test('the order of rows is text and nothing else', () => {
    assert.deepEqual(cleanHome({ homeOrder: 'recent,continue' } as never), { homeOrder: 'recent,continue' })
    for (const bad of [5, null, {}, ['recent'], true, 'x'.repeat(200_000)]) {
        assert.equal('homeOrder' in (cleanHome({ homeOrder: bad } as never) as Record<string, unknown>), false)
    }
})
