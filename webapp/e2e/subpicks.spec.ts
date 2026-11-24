// HX25 (WB2): the online subtitle picked for a video, and Off, come back by themselves on the next opening;
// any other explicit choice ends the pick; a gone copy is downloaded once unless the line is a data saver;
// signing out wipes the picks and their copies. MSone's gateway is a fake here (the e2e build has a key).
import { expect, test, type Page } from '@playwright/test'
import { noSizeFloor, openCard, playsPast } from './helpers'

test.skip(({ browserName }) => browserName !== 'chromium', 'the 20 s chaptered show plays in Chromium')

const SRT = '1\n00:00:01,000 --> 00:00:04,000\nPicked online\n'
const LABEL = 'Malayalam · MSone'

/** MSone's gateway as a fake: healthy, one release, and a count of the downloads asked for. */
async function gateway(page: Page) {
    const seen = { downloads: 0, delayMs: 0 }
    const cors = { 'access-control-allow-origin': '*', 'access-control-allow-headers': '*', 'access-control-allow-methods': 'GET, OPTIONS' }
    await page.route('https://subs.tmplayer.org/**', async (route) => {
        const url = new URL(route.request().url())
        if (route.request().method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors })
        if (url.pathname === '/health') return route.fulfill({ json: { ok: true, providers: { msone: { state: 'on', message: null } } }, headers: cors })
        if (url.pathname === '/msone/search') {
            return route.fulfill({ json: { hits: [{ id: '4242', title: 'Lighthouse', year: 2019, season: null, episode: null, downloadUrl: 'https://malayalamsubtitles.org/x' }] }, headers: cors })
        }
        if (url.pathname.startsWith('/msone/download/')) {
            seen.downloads++
            if (seen.delayMs) await new Promise((r) => setTimeout(r, seen.delayMs))
            return route.fulfill({ body: SRT, headers: { ...cors, 'content-type': 'application/x-subrip', 'content-disposition': 'attachment; filename="Lighthouse.srt"' } })
        }
        return route.fulfill({ status: 404, headers: cors })
    })
    return seen
}

async function lighthouse(page: Page) {
    await noSizeFloor(page)
    await page.goto('/chat/-1006')
    await openCard(page, 'Lighthouse')
    await playsPast(page, 0.3)
}

const openMore = async (page: Page) => {
    await page.mouse.move(400, 300)
    await page.locator('[data-shot=more]').first().click()
}

const tracks = (page: Page) =>
    page.locator('video').evaluate((v: HTMLVideoElement) => Array.from(v.textTracks).map((tt) => ({ label: tt.label, mode: tt.mode, cues: tt.cues?.length ?? 0 })))
const showing = (page: Page) => tracks(page).then((list) => list.filter((tt) => tt.mode === 'showing').map((tt) => tt.label))

/** The subtitles list: the bar's own button where the form has one (the phone), else More options and its Subtitles line. */
async function openSubtitles(page: Page) {
    await page.mouse.move(400, 300)
    const bar = page.getByRole('button', { name: 'Subtitles', exact: true })
    if (await bar.count()) return bar.click()
    await openMore(page)
    await page.getByRole('menuitem', { name: /^Subtitles/ }).click()
}

/** The subtitles list, MSone's sheet, and the one release it lists. */
async function pickFromMsone(page: Page) {
    await openSubtitles(page)
    await page.getByText('Malayalam subtitles from MSone').click()
    await page.locator('.pc-msone-pick').first().click()
    await expect.poll(() => showing(page)).toEqual([LABEL])
}

async function subtitlesOff(page: Page) {
    await openSubtitles(page)
    await page.getByRole('menuitemradio', { name: 'Off' }).or(page.getByRole('radio', { name: 'Off', exact: true })).first().click()
    await expect.poll(() => showing(page)).toEqual([])
}

/** The player again on the same video, as a reload opens it. */
async function reopen(page: Page) {
    await page.reload()
    await playsPast(page, 0.2)
}

/** Empties every object store of a database (what a cleared cache looks like), or only the keys that start with `prefix`. */
const emptyDb = (page: Page, name: string, prefix = '') =>
    page.evaluate(
        ([db, start]) =>
            new Promise<void>((resolve) => {
                const open = indexedDB.open(db)
                open.onerror = () => resolve()
                open.onsuccess = () => {
                    const names = Array.from(open.result.objectStoreNames)
                    if (!names.length) return (open.result.close(), resolve())
                    const tx = open.result.transaction(names, 'readwrite')
                    for (const n of names) {
                        const store = tx.objectStore(n)
                        if (!start) store.clear()
                        else {
                            const keys = store.getAllKeys()
                            keys.onsuccess = () => (keys.result as string[]).filter((k) => String(k).startsWith(start)).forEach((k) => store.delete(k))
                        }
                    }
                    tx.oncomplete = () => (open.result.close(), resolve())
                }
            }),
        [name, prefix],
    )

const kept = (page: Page) =>
    page.evaluate(
        () =>
            new Promise<number>((resolve) => {
                const open = indexedDB.open('tmplayer-subpicks')
                open.onerror = () => resolve(-1)
                open.onsuccess = () => {
                    const names = Array.from(open.result.objectStoreNames)
                    if (!names.length) return (open.result.close(), resolve(0))
                    const req = open.result.transaction('kv').objectStore('kv').count()
                    req.onsuccess = () => (open.result.close(), resolve(req.result))
                }
            }),
    )

test('a picked MSone subtitle is back on reopening with no download, and Off is remembered too', async ({ page }, info) => {
    const seen = await gateway(page)
    await lighthouse(page)
    await pickFromMsone(page)
    expect(seen.downloads).toBe(1)
    await expect.poll(async () => (await tracks(page))[0].cues).toBeGreaterThan(0)
    const dir = process.env.TMPLAYER_SHOTS
    if (dir) await page.screenshot({ path: `${dir}/web-wb2-picked-${info.project.name}.png` })
    // Reopened: the same track, laid by itself from its own copy.
    await reopen(page)
    await expect.poll(() => showing(page)).toEqual([LABEL])
    if (dir) await page.screenshot({ path: `${dir}/web-wb2-restored-${info.project.name}.png` })
    expect(seen.downloads).toBe(1)
    // Off ends it, and Off is what comes back.
    await subtitlesOff(page)
    await reopen(page)
    await page.waitForTimeout(1200)
    expect(await tracks(page)).toEqual([])
    // The track picked again after an Off: it comes back (MSone's file is kept, so nothing is downloaded again).
    await pickFromMsone(page)
    await reopen(page)
    await expect.poll(() => showing(page)).toEqual([LABEL])
    expect(seen.downloads).toBe(1)
})

test('a file of the viewer\'s own ends the pick; a gone copy and cache download once, and not on a data saver', async ({ page }) => {
    const seen = await gateway(page)
    await lighthouse(page)
    await pickFromMsone(page)
    await page.locator('input[type=file]').setInputFiles({ name: 'mine.srt', mimeType: 'text/plain', buffer: Buffer.from(SRT) })
    await expect.poll(() => showing(page)).toEqual(['mine'])
    await reopen(page)
    await page.waitForTimeout(1200)
    expect(await tracks(page)).toEqual([])
    // Picked again, then both kept files are gone (the pick's copy and MSone's cache): one download brings it back.
    await pickFromMsone(page)
    const first = seen.downloads
    await emptyDb(page, 'tmplayer-subpicks', 'copy|')
    await emptyDb(page, 'tmplayer-msone')
    await reopen(page)
    await expect.poll(() => showing(page)).toEqual([LABEL])
    expect(seen.downloads).toBe(first + 1)
    // Gone again, on a data saver: nothing is downloaded unasked and nothing is said; the viewer picks again.
    await page.addInitScript(() => Object.defineProperty(navigator, 'connection', { value: { saveData: true }, configurable: true }))
    await emptyDb(page, 'tmplayer-subpicks', 'copy|')
    await emptyDb(page, 'tmplayer-msone')
    const before = seen.downloads
    await reopen(page)
    await page.waitForTimeout(1200)
    expect(await tracks(page)).toEqual([])
    expect(seen.downloads).toBe(before)
    await expect(page.locator('.pc-status, .status-sheet')).toHaveCount(0)
})

test('signing out wipes the picks and their copies', async ({ page }) => {
    await gateway(page)
    await lighthouse(page)
    await pickFromMsone(page)
    await expect.poll(() => kept(page)).toBeGreaterThanOrEqual(2)
    await page.goto('/settings')
    await page.getByRole('button', { name: 'Sign out of Telegram' }).click()
    await page.getByRole('dialog', { name: 'Sign out of Telegram as Dana Test?' }).getByRole('button', { name: 'Sign out' }).click()
    await expect.poll(() => kept(page)).toBe(0)
})

test('Off chosen while the download is still coming stands: nothing is laid over it and nothing is filed again', async ({ page }) => {
    const seen = await gateway(page)
    await lighthouse(page)
    await pickFromMsone(page)
    await emptyDb(page, 'tmplayer-subpicks', 'copy|')
    await emptyDb(page, 'tmplayer-msone')
    seen.delayMs = 4000
    const before = seen.downloads
    await reopen(page)
    // The download is on its way; the viewer turns subtitles off.
    await expect.poll(() => seen.downloads).toBe(before + 1)
    await subtitlesOff(page)
    await page.waitForTimeout(4600)
    expect(await tracks(page)).toEqual([])
    // The next opening still finds Off, not the pick that was being fetched.
    seen.delayMs = 0
    await reopen(page)
    await page.waitForTimeout(1200)
    expect(await tracks(page)).toEqual([])
    expect(seen.downloads).toBe(before + 1)
})
