import assert from 'node:assert/strict'
import { test } from 'node:test'
import { canShare, shareVideo } from './shareVideo.ts'

test('a browser with navigator.share can share, one without cannot', () => {
    assert.equal(canShare({ share: async () => {} }), true)
    assert.equal(canShare({}), false)
    assert.equal(canShare(undefined), false)
})

test('the link and the title go to the share sheet', async () => {
    const seen: unknown[] = []
    const r = await shareVideo('https://t.me/linkroom/5', 'Plain Clip', { share: async (d) => void seen.push(d) })
    assert.equal(r, 'shared')
    assert.deepEqual(seen, [{ url: 'https://t.me/linkroom/5', title: 'Plain Clip' }])
})

test('a closed sheet is cancelled, any other failure is failed, no sheet is unsupported', async () => {
    const abort = Object.assign(new Error('x'), { name: 'AbortError' })
    assert.equal(await shareVideo('l', 't', { share: async () => Promise.reject(abort) }), 'cancelled')
    assert.equal(await shareVideo('l', 't', { share: async () => Promise.reject(new Error('NotAllowedError')) }), 'failed')
    assert.equal(await shareVideo('l', 't', { share: async () => Promise.reject(null) }), 'failed')
    assert.equal(await shareVideo('l', 't', {}), 'unsupported')
    assert.equal(await shareVideo('l', 't', undefined), 'unsupported')
})

test('a long title is cut', async () => {
    let title = ''
    await shareVideo('l', 'x'.repeat(1000), { share: async (d) => void (title = d.title ?? '') })
    assert.equal(title.length, 200)
})
