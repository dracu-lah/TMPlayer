// Gone for good against merely unanswered (HX21), with garbage in: `node --test src/goneRules.test.ts`.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { chatGone, goneKey } from './goneRules.ts'

test('a chat closed to the account is gone', () => {
    for (const text of ['CHANNEL_PRIVATE', 'CHAT_FORBIDDEN', 'USER_BANNED_IN_CHANNEL', 'PEER_ID_INVALID', 'CHANNEL_INVALID', 'CHAT_ID_INVALID']) {
        assert.equal(chatGone({ text }), true, text)
        assert.equal(chatGone(new Error(`RPC error 400: ${text}`)), true, text)
    }
})

test('waits, outages and the unknown are not gone', () => {
    for (const e of [{ text: 'FLOOD_WAIT_42' }, new Error('timed out'), new Error('network error'), { text: 'INTERNAL_SERVER_ERROR' }, { text: 'MESSAGE_ID_INVALID_X' }, 'CHANNEL_PRIVATEX', { text: 'XCHANNEL_PRIVATE' }]) {
        assert.equal(chatGone(e), false, String((e as { text?: string }).text ?? e))
    }
})

test('garbage never throws and is never gone', () => {
    for (const e of [null, undefined, 0, 42, NaN, {}, [], { text: 7 }, { message: {} }, Symbol('x'), () => 1, { text: 'x'.repeat(1_000_000) }]) {
        assert.equal(chatGone(e), false)
    }
})

test('the line names the lists that changed', () => {
    assert.equal(goneKey(0, 0, 0), null)
    assert.equal(goneKey(2, 0, 2), 'continue.gone_left_history')
    assert.equal(goneKey(0, 1, 1), 'continue.gone_left_watch_later')
    assert.equal(goneKey(1, 1, 1), 'continue.gone_left_lists')
    assert.equal(goneKey(-1, -1, NaN), null)
})
