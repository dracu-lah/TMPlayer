// HX88 phase B3: profiles in the web player, over the fake Telegram (fake/mtcute.ts keeps a session per
// storage name; `qr:N` signs the QR code in as account N). One profile changes nothing; Add account
// signs a second in beside it; the picker switches, asks a PIN, signs one out; Remove all leaves the
// normal sign in; two tabs never run one profile; a lost session is "Needs attention".
import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import { fakeCase, isPhoneProject } from './helpers'

const BOOK = 'tmplayer-accounts'
const PICKER = "Who's watching?"

interface StoredBook {
    profiles: { id: string; name: string; userId: number | null; state: string; pin: unknown }[]
    active: string
}
const readBook = (page: Page) => page.evaluate((key) => JSON.parse(localStorage.getItem(key) ?? 'null') as StoredBook | null, BOOK)

/** The keys of the app's database, as stored. */
const storedKeys = (page: Page) =>
    page.evaluate(
        () =>
            new Promise<string[]>((resolve) => {
                const open = indexedDB.open('tmplayer-app')
                open.onupgradeneeded = () => open.result.createObjectStore('kv')
                open.onsuccess = () => {
                    const req = open.result.transaction('kv').objectStore('kv').getAllKeys()
                    req.onsuccess = () => (open.result.close(), resolve(req.result.map(String)))
                }
            }),
    )

/** Two profiles already on this browser: the adopted one (account 1) and `abc123` (account 2), each with its chats answered. */
async function twoProfiles(page: Page, extra: { layout?: string; attention?: boolean } = {}) {
    await page.goto('/__e2e/sizes')
    await page.evaluate(
        ({ book, layout, attention }) =>
            new Promise<void>((resolve) => {
                localStorage.setItem('tmplayer-accounts', JSON.stringify(book))
                if (layout) localStorage.setItem('tmplayer-layout', layout)
                const app = indexedDB.open('tmplayer-app')
                app.onupgradeneeded = () => app.result.createObjectStore('kv')
                app.onsuccess = () => {
                    const tx = app.result.transaction('kv', 'readwrite')
                    tx.objectStore('kv').put('all', 'addedMode')
                    tx.objectStore('kv').put('all', '@abc123:addedMode')
                    tx.objectStore('kv').put({ sizeMin: 0 }, 'settings')
                    tx.oncomplete = () => {
                        app.result.close()
                        if (attention) return resolve()
                        // The second profile's Telegram session, where the fake keeps it.
                        const tg = indexedDB.open('tmplayer-abc123', 1)
                        tg.onupgradeneeded = () => tg.result.createObjectStore('fake')
                        tg.onsuccess = () => {
                            const t2 = tg.result.transaction('fake', 'readwrite')
                            t2.objectStore('fake').put({ id: 2 }, 'self')
                            t2.oncomplete = () => (tg.result.close(), resolve())
                        }
                    }
                }
            }),
        {
            book: {
                v: 1,
                profiles: [
                    { id: 'main', name: 'Dana Test', userId: 1, state: 'ok', pin: null, fails: 0, lockedUntil: 0, added: 1 },
                    { id: 'abc123', name: 'Robin Test', userId: 2, state: 'ok', pin: null, fails: 0, lockedUntil: 0, added: 2 },
                ],
                active: 'main',
            },
            layout: extra.layout,
            attention: extra.attention,
        },
    )
}

const picker = (page: Page) => page.getByRole('dialog', { name: PICKER })
const tile = (page: Page, name: RegExp) => picker(page).getByRole('button', { name })

/** Settings' list, where the account and its profile menu are. */
async function openSettings(page: Page) {
    await page.goto('/settings')
    await expect(page.locator('.set-account, .settings .panel').first()).toBeVisible()
}

/** Runs an action that switches profile, and waits for the page it reloads into. */
async function reloads(page: Page, action: () => Promise<unknown>) {
    await Promise.all([page.waitForEvent('load', { timeout: 15_000 }), action()])
}

/** The QR code on the sign in (a phone opens on the number first), scanned as account `id`. */
async function scanAs(page: Page, context: BrowserContext, id: number) {
    const qr = page.getByRole('button', { name: 'Scan a QR code instead' })
    await expect(page.getByRole('heading', { name: /Sign in to Telegram|Your phone number/ })).toBeVisible()
    if (await qr.count()) await qr.click()
    await fakeCase(context, `qr:${id}`)
}

test('one profile: no picker, nothing changes, Settings offers Add account', async ({ page }) => {
    await page.goto('/')
    await expect(page.locator('main.page')).toBeVisible()
    await expect(page.getByRole('heading', { name: PICKER })).toHaveCount(0)
    await openSettings(page)
    await expect(page.getByRole('button', { name: 'Add account' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Remove all accounts' })).toHaveCount(0)
    // The adopted profile keeps the old storage names: no key of it is renamed.
    expect((await storedKeys(page)).filter((k) => k.startsWith('@'))).toEqual([])
})

test('add a second account, switch between them, and sign one out', async ({ page, context }) => {
    await openSettings(page)
    await page.getByRole('button', { name: 'Add account' }).click()
    // The new profile starts on the normal sign in, with a way back.
    await expect(page).toHaveURL(/\/signin/)
    await expect(page.getByRole('button', { name: 'Cancel, go back' })).toBeVisible()
    await scanAs(page, context, 2)
    // Its own chats are asked for: the gate of a new account.
    await expect(page).toHaveURL(/\/setup\/chats|\/added-chats\?setup/, { timeout: 15_000 })
    await page.getByRole('checkbox', { name: 'Test Clips' }).check()
    await page.getByRole('button', { name: 'Done' }).click()
    let book = (await readBook(page))!
    expect(book.profiles.map((p) => p.name)).toEqual(['Dana Test', 'Robin Test'])
    const folder = book.profiles[1].id
    expect(folder).toMatch(/^[a-z0-9]{6}$/)
    // Its answer is its own; the first profile's stays.
    const keys = await storedKeys(page)
    expect(keys).toContain(`@${folder}:addedMode`)
    expect(keys).toContain('addedMode')

    await openSettings(page)
    await expect(page.locator('.set-account').getByText('Robin Test')).toBeVisible()
    await page.getByRole('button', { name: 'Switch profile' }).click()
    await expect(picker(page)).toBeVisible()
    // A focus group: the first picture has the focus and the arrows move along the row.
    await expect(tile(page, /^Dana Test/)).toBeFocused()
    await page.keyboard.press('ArrowRight')
    await expect(tile(page, /^Robin Test, In use/)).toBeFocused()
    await page.keyboard.press('ArrowRight')
    await expect(picker(page).getByRole('button', { name: 'Add account' })).toBeFocused()
    // Escape closes the picker opened from the menu.
    await page.keyboard.press('Escape')
    await expect(picker(page)).toHaveCount(0)
    await page.getByRole('button', { name: 'Switch profile' }).click()
    // Another profile: the page starts again on it, at Home.
    await reloads(page, () => tile(page, /^Dana Test/).click())
    await expect(page.locator('main.page')).toBeVisible()
    await openSettings(page)
    await expect(page.locator('.set-account').getByText('Dana Test')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Remove all accounts' })).toBeVisible()

    // Sign out of this profile only: the other opens, the picker is not needed for one.
    await page.getByRole('button', { name: 'Sign out of Telegram' }).click()
    const ask = page.getByRole('dialog', { name: 'Sign out of Dana Test?' })
    await expect(ask).toBeVisible()
    await ask.getByRole('button', { name: 'Sign out', exact: true }).click()
    await expect(page.getByText('Signed out of that profile')).toBeVisible({ timeout: 15_000 })
    book = (await readBook(page))!
    expect(book.profiles.map((p) => p.id)).toEqual([folder])
    // Its own keys went, the other profile's and the device's stay.
    const left = await storedKeys(page)
    // `addedMode` is put back by the test server's page script (vite.e2e.ts) on every load.
    expect(left.filter((k) => !k.startsWith('@') && k !== 'settings' && k !== 'addedMode')).toEqual([])
    expect(left).toContain(`@${folder}:addedMode`)
    await openSettings(page)
    await expect(page.locator('.set-account').getByText('Robin Test')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Add account' })).toBeVisible()
})

test('the picker at launch, a PIN, and five wrong tries wait 30 s across a reload', async ({ page }) => {
    await twoProfiles(page)
    await page.goto('/')
    await expect(picker(page)).toBeVisible()
    // At launch there is nothing to close: a profile must be chosen.
    await expect(page.getByRole('button', { name: 'Close profiles' })).toHaveCount(0)
    await tile(page, /^Dana Test/).click()
    await expect(page.locator('main.page')).toBeVisible()

    await openSettings(page)
    await page.getByRole('button', { name: 'Switch profile' }).click()
    await picker(page).getByRole('button', { name: 'Manage profiles' }).click()
    await tile(page, /^Robin Test/).click()
    const options = page.getByRole('dialog', { name: 'Robin Test' })
    await options.getByRole('button', { name: 'Set a PIN' }).click()
    const pin = page.locator('dialog.pin-dialog')
    await expect(pin.getByText('The PIN only locks this profile on this browser. It does not encrypt what is stored here.')).toBeVisible()
    await page.keyboard.type('4821')
    await expect(pin.getByRole('heading', { name: 'Enter the same PIN again' })).toBeVisible()
    await page.keyboard.type('4821')
    await expect(page.getByText('PIN set')).toBeVisible()
    expect((await readBook(page))!.profiles[1].pin).not.toBeNull()

    await picker(page).getByRole('button', { name: 'Done' }).click()
    await tile(page, /^Robin Test, Locked with a PIN/).click()
    for (let i = 4; i >= 1; i--) {
        await page.keyboard.type('0000')
        await expect(pin.getByText(i === 1 ? 'Wrong PIN. 1 try left' : `Wrong PIN. ${i} tries left`)).toBeVisible()
    }
    await page.keyboard.type('0000')
    await expect(pin.getByText(/Too many tries\. Try again in \d+ s/)).toBeVisible()
    // The lock is kept in the book: a reload does not lift it.
    await page.reload()
    await page.getByRole('button', { name: 'Switch profile' }).click()
    await tile(page, /^Robin Test/).click()
    await expect(pin.getByText(/Too many tries\. Try again in \d+ s/)).toBeVisible()
    await pin.getByRole('button', { name: 'Cancel' }).click()
    // Once the wait is over (moved on here rather than waited), the right PIN opens it.
    await page.evaluate((key) => {
        const b = JSON.parse(localStorage.getItem(key)!)
        b.profiles[1].lockedUntil = 0
        localStorage.setItem(key, JSON.stringify(b))
    }, BOOK)
    await page.reload()
    await page.getByRole('button', { name: 'Switch profile' }).click()
    await tile(page, /^Robin Test/).click()
    await reloads(page, () => page.keyboard.type('4821'))
    await expect(page.locator('main.page')).toBeVisible()
    await openSettings(page)
    await expect(page.locator('.set-account').getByText('Robin Test')).toBeVisible()
})

test('two tabs never run one profile; two profiles run in two tabs', async ({ page, context }) => {
    await twoProfiles(page)
    await page.goto('/')
    await tile(page, /^Dana Test/).click()
    await expect(page.locator('main.page')).toBeVisible()

    // A second tab starts on the picker and runs the other profile beside the first.
    const second = await context.newPage()
    await second.goto('/')
    await tile(second, /^Robin Test/).click()
    await expect(second.locator('main.page')).toBeVisible()
    await expect(second.getByRole('alertdialog', { name: 'TMPlayer is open in another tab' })).toHaveCount(0)
    await expect(page.getByRole('alertdialog', { name: 'TMPlayer is open in another tab' })).toHaveCount(0)

    // A third on the first tab's profile waits, and Use it here takes it over.
    const third = await context.newPage()
    await third.goto('/')
    await tile(third, /^Dana Test/).click()
    await expect(third.getByRole('alertdialog', { name: 'TMPlayer is open in another tab' })).toBeVisible()
    await third.getByRole('button', { name: 'Use it here' }).click()
    await expect(third.locator('main.page')).toBeVisible()
    await expect(page.getByRole('alertdialog', { name: 'TMPlayer is open in another tab' })).toBeVisible()
    // The second tab, on its own profile, was never asked.
    await expect(second.getByRole('alertdialog', { name: 'TMPlayer is open in another tab' })).toHaveCount(0)
    await second.close()
    await third.close()
})

test('the same Telegram account twice is refused', async ({ page, context }) => {
    await openSettings(page)
    await page.getByRole('button', { name: 'Add account' }).click()
    await expect(page).toHaveURL(/\/signin/)
    await scanAs(page, context, 1)
    await expect(page.getByText('That Telegram account is already a profile here, so it was not added again')).toBeVisible({ timeout: 15_000 })
    expect((await readBook(page))!.profiles.map((p) => p.id)).toEqual(['main'])
    await expect(page.locator('main.page')).toBeVisible()
})

test('cancel on the sign in of a new account goes back', async ({ page }) => {
    await openSettings(page)
    await page.getByRole('button', { name: 'Add account' }).click()
    await page.getByRole('button', { name: 'Cancel, go back' }).click()
    await expect(page.locator('main.page')).toBeVisible()
    expect((await readBook(page))!.profiles.map((p) => p.id)).toEqual(['main'])
})

test('a profile whose session is gone needs attention, and Remove takes it away', async ({ page }) => {
    await twoProfiles(page, { attention: true })
    await page.goto('/')
    await tile(page, /^Robin Test/).click()
    // Its session is missing: the picker says so, nothing crashes.
    await expect(tile(page, /^Robin Test, In use, Needs attention/)).toBeVisible({ timeout: 15_000 })
    await tile(page, /^Robin Test/).click()
    const options = page.getByRole('dialog', { name: 'Robin Test' })
    await expect(options.getByRole('button', { name: 'Sign in again' })).toBeVisible()
    await options.getByRole('button', { name: 'Remove from this browser' }).click()
    const ask = page.getByRole('dialog', { name: 'Remove Robin Test?' })
    await ask.getByRole('button', { name: 'Remove', exact: true }).click()
    await expect(page.locator('main.page')).toBeVisible({ timeout: 15_000 })
    expect((await readBook(page))!.profiles.map((p) => p.id)).toEqual(['main'])
    expect((await storedKeys(page)).filter((k) => k.startsWith('@'))).toEqual([])
})

test('Remove all accounts signs every profile out and ends on the sign in', async ({ page }) => {
    await twoProfiles(page)
    await page.goto('/')
    await tile(page, /^Robin Test/).click()
    await expect(page.locator('main.page')).toBeVisible()
    await openSettings(page)
    await page.getByRole('button', { name: 'Remove all accounts' }).click()
    await page.getByRole('dialog', { name: 'Remove all accounts?' }).getByRole('button', { name: 'Remove all' }).click()
    await expect(page).toHaveURL(/\/signin/, { timeout: 20_000 })
    const book = await readBook(page)
    expect(book?.profiles.map((p) => [p.id, p.userId])).toEqual([['main', null]])
    // Nothing of either profile is left (`addedMode` is the test server's page script, vite.e2e.ts).
    expect((await storedKeys(page)).filter((k) => k !== 'addedMode')).toEqual([])
    await expect(page.getByRole('heading', { name: PICKER })).toHaveCount(0)
})

test('the TV: the picker is a focus group for the remote', async ({ page }, info) => {
    // A touch screen is always the phone layout.
    test.skip(isPhoneProject(info.project.name), 'the TV layout is not offered on a phone')
    await twoProfiles(page, { layout: 'tv' })
    await page.goto('/')
    await expect(picker(page)).toBeVisible()
    await expect(tile(page, /^Dana Test/)).toBeFocused()
    await page.keyboard.press('ArrowRight')
    await expect(tile(page, /^Robin Test/)).toBeFocused()
    await page.keyboard.press('ArrowLeft')
    await expect(tile(page, /^Dana Test/)).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(picker(page)).toHaveCount(0)
    await expect(page.locator('.tv-bar')).toBeVisible()
    // Settings' profile menu opens it again, and Back closes it.
    await page.goto('/settings')
    await page.getByRole('button', { name: 'Switch profile' }).click()
    await expect(tile(page, /^Dana Test, In use/)).toBeFocused()
    await page.keyboard.press('Backspace')
    await expect(picker(page)).toHaveCount(0)
})
