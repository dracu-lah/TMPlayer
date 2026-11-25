// Sync across my devices on the web (src/sync): Home's one question, the switch under Settings,
// Privacy and lock, and the file in Saved Messages (the fake keeps it in the test server, so a test reads
// what the page put there). Opt in: with the switch off nothing is read from or written to Saved Messages.
import { expect, test, type BrowserContext, type Page, type TestInfo } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { gunzipSync } from 'node:zlib'
import { fakeCase, noSizeFloor } from './helpers'
import { emptyDoc, encode, tdlibMessageId } from '../src/sync/format'

test.skip(({ browserName }) => browserName !== 'chromium', 'the sync runs in Chromium')

const SHOTS = `${process.cwd()}/../design/web-sync`

interface Stored {
    id: number
    text: string
    fileName: string
    bytes: string
}

const unique = () => `k${Math.random().toString(36).slice(2, 10)}`
const cloud = async (page: Page, key: string, op = 'list', body?: unknown): Promise<Stored[]> =>
    (await (await page.request.post(`/__e2e/cloud?key=${key}&op=${op}`, { data: body })).json()) as Stored[]
const fileOf = (m: Stored) => JSON.parse(gunzipSync(Buffer.from(m.bytes, 'base64')).toString('utf8'))

async function start(context: BrowserContext, page: Page, key: string) {
    // 'big': Home leaves out videos under 50 MB, and the test files are tiny.
    await fakeCase(context, 'big', `cloud:${key}`)
    await noSizeFloor(page)
}

async function shot(page: Page, info: TestInfo, name: string) {
    mkdirSync(SHOTS, { recursive: true })
    await page.screenshot({ path: `${SHOTS}/${name}-${info.project.name}.png` })
}

const card = (page: Page) => page.locator('.keep-place')
const homeReady = (page: Page) => expect(page.locator('.home-row .card, .home-row .continue-card').first()).toBeVisible()

test('the question is asked once, at the end of a Home that has rows, and "Not now" touches nothing in Saved Messages', async ({ context, page }, info) => {
    const key = unique()
    await start(context, page, key)
    await page.goto('/')
    await homeReady(page)
    await expect(card(page)).toBeVisible()
    await expect(card(page).getByRole('heading', { name: 'Sync your progress across devices?' })).toBeVisible()
    await expect(card(page)).toContainText('one small file in your own Saved Messages')
    await card(page).scrollIntoViewIfNeeded()
    await shot(page, info, 'home-card')
    await card(page).getByRole('button', { name: 'Not now' }).click()
    await expect(card(page)).toHaveCount(0)
    // The keyboard is not left at the top of the page: it goes on to a video of Home.
    await expect.poll(() => page.evaluate(() => !!document.activeElement?.closest('.home-row, .home-head'))).toBe(true)
    // Asked once: answering either way ends it for good.
    await page.reload()
    await homeReady(page)
    await expect(card(page)).toHaveCount(0)
    expect(await cloud(page, key)).toEqual([])
})

test('the card counts as shown when it first appears: leaving Home without answering never asks again', async ({ context, page }) => {
    const key = unique()
    await start(context, page, key)
    await page.goto('/')
    await expect(card(page)).toBeVisible()
    await page.goto('/chats')
    await page.goto('/')
    await homeReady(page)
    await expect(card(page)).toHaveCount(0)
    expect(await cloud(page, key)).toEqual([])
})

test('"Yes, sync it" by the keyboard turns the sync on and sends one file, the account\'s own, edited in place after', async ({ context, page }, info) => {
    const key = unique()
    await start(context, page, key)
    await page.goto('/')
    await expect(card(page)).toBeVisible()
    const yes = card(page).getByRole('button', { name: 'Yes, sync it' })
    await yes.focus()
    await expect(yes).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(page.getByText('Your progress now syncs across your devices')).toBeVisible()
    await expect(card(page)).toHaveCount(0)
    await expect.poll(async () => (await cloud(page, key)).length, { timeout: 20_000 }).toBe(1)
    const [saved] = await cloud(page, key)
    expect(saved.text.startsWith('#TMPlayerSyncFile\n')).toBe(true)
    expect(saved.fileName).toBe('tmplayer-sync.json.gz')
    const doc = fileOf(saved)
    expect(doc.format).toBe('tmplayer-sync')
    expect(doc.account).toBe(1)
    // Sync now: the same message, replaced in place, never a second one.
    await page.goto('/settings/privacy')
    await expect(page.getByRole('switch', { name: /^Sync across my devices/ })).toHaveAttribute('aria-checked', 'true')
    await page.getByRole('button', { name: 'Sync now' }).click()
    await expect(page.getByRole('status').filter({ hasText: /^Synced / })).toBeVisible()
    const after = await cloud(page, key)
    expect(after.map((m) => m.id)).toEqual([saved.id])
    await shot(page, info, 'settings-privacy-on')
})

test('Settings, Privacy and lock: off by default and nothing sent; turning it on asks first; Turn off and delete takes the file away', async ({ context, page }, info) => {
    const key = unique()
    await start(context, page, key)
    await page.goto('/settings/privacy')
    const sw = page.getByRole('switch', { name: /^Sync across my devices/ })
    await expect(sw).toHaveAttribute('aria-checked', 'false')
    await expect(page.getByText(/^Only TMPlayer's own data goes into one file/)).toBeVisible()
    await expect(page.getByRole('button', { name: 'Sync now' })).toHaveCount(0)
    await shot(page, info, 'settings-privacy-off')
    // Turning it on is a question first; Cancel changes nothing.
    await sw.click()
    const dialog = page.getByRole('dialog', { name: 'Sync across my devices?' })
    await expect(dialog).toBeVisible()
    await dialog.getByRole('button', { name: 'Cancel' }).click()
    await expect(sw).toHaveAttribute('aria-checked', 'false')
    expect(await cloud(page, key)).toEqual([])
    await sw.click()
    await dialog.getByRole('button', { name: 'Yes, sync it' }).click()
    await expect(sw).toHaveAttribute('aria-checked', 'true')
    await expect.poll(async () => (await cloud(page, key)).length, { timeout: 20_000 }).toBe(1)
    // Delete: the question names what goes, and the file is gone from Saved Messages with the switch.
    await page.getByRole('button', { name: /^Turn off and delete the sync file/ }).click()
    const sure = page.getByRole('dialog', { name: 'Turn off and delete the sync file?' })
    await expect(sure).toBeVisible()
    await shot(page, info, 'settings-privacy-delete')
    await sure.getByRole('button', { name: 'Turn off and delete the sync file' }).click()
    await expect(sw).toHaveAttribute('aria-checked', 'false')
    await expect.poll(async () => (await cloud(page, key)).length).toBe(0)
})

test('another device\'s place is taken in: a video left part way on the phone is on Continue watching here', async ({ context, page }) => {
    const key = unique()
    await start(context, page, key)
    // The file another device (the phone) wrote: Native Clip, left two seconds in. The apps write TDLib's message ids.
    const doc = emptyDoc(1)
    doc.updatedAt = Date.now() - 60_000
    doc.appVersion = '1.99.0'
    doc.resume.push({ chat: -1001, msg: tdlibMessageId(1), file: 7, title: 'Native Clip', chatTitle: '', size: 0, durationSec: 4, positionMs: 2_000, durationMs: 4_000, updatedAt: Date.now() - 60_000 })
    const { bytes } = await encode(doc)
    await page.request.post(`/__e2e/cloud?key=${key}&op=put`, { data: { date: Date.now(), text: '#TMPlayerSyncFile\nthe file', fileName: 'tmplayer-sync.json.gz', bytes: Buffer.from(bytes).toString('base64') } })
    // Not on yet: nothing is read, so nothing comes in.
    await page.goto('/')
    await homeReady(page)
    await expect(page.locator('.continue-card')).toHaveCount(0)
    await card(page).getByRole('button', { name: 'Yes, sync it' }).click()
    await expect(page.locator('.continue-card').filter({ hasText: 'Native Clip' })).toBeVisible({ timeout: 20_000 })
    // The file the phone wrote is still the one message, with its place and the web's account.
    const list = await cloud(page, key)
    expect(list).toHaveLength(1)
    expect(fileOf(list[0]).resume.map((r: { message: number }) => r.message)).toContain(tdlibMessageId(1))
})
