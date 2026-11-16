// Which sync files are downloaded: only one whose size Telegram states and is within the limit.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { isReadableSize } from './syncfile.ts'

test('a file of unknown, zero, negative, absurd or too large a size is not downloaded', () => {
    for (const bad of [undefined, null, 0, -1, NaN, Infinity, 2 * 1024 * 1024 + 1, 2 ** 40]) assert.equal(isReadableSize(bad), false, String(bad))
    for (const ok of [1, 663, 2 * 1024 * 1024]) assert.equal(isReadableSize(ok), true, String(ok))
})
