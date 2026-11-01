import { test } from 'node:test'
import assert from 'node:assert/strict'
import { cleanHome, RECENT_CHATS_MAX } from './homeSettings.ts'

test('good values stand', () => {
    const good = { carousel: false, homeContinue: true, homeRecent: false, homeStarred: true, recentSource: 'chosen' as const, recentChats: [-1001, 5] }
    assert.deepEqual(cleanHome(good), good)
})

test('garbage: wrong types, unknown source, bad ids, a huge list', () => {
    const bad = cleanHome({ carousel: 'yes', homeContinue: 1, homeRecent: null, recentSource: 'everything', recentChats: [0, 1.5, 'x', NaN, 2 ** 60, -7, -7, 9] } as never) as Record<string, unknown>
    assert.equal('carousel' in bad, false)
    assert.equal('homeContinue' in bad, false)
    assert.equal('homeRecent' in bad, false)
    assert.equal('recentSource' in bad, false)
    assert.deepEqual(bad.recentChats, [-7, 9])
    assert.deepEqual((cleanHome({ recentChats: 'oops' } as never) as Record<string, unknown>).recentChats, [])
    const huge = cleanHome({ recentChats: Array.from({ length: RECENT_CHATS_MAX + 500 }, (_, i) => i + 1) })
    assert.equal(huge.recentChats!.length, RECENT_CHATS_MAX)
})

test('other keys are left alone', () => {
    assert.deepEqual(cleanHome({ theme: 'dark' } as never), { theme: 'dark' })
})
