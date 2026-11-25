// HX88 phase B3: profiles in the web player, over the fake Telegram (fake/mtcute.ts keeps a session per
// storage name; `qr:N` signs the QR code in as account N). One profile changes nothing; Add account
// signs a second in beside it; the picker switches, asks a PIN, signs one out; Remove all leaves the
// normal sign in; two tabs never run one profile; a lost session is "Needs attention".
import { mkdirSync } from 'node:fs'
import { expect, test, type BrowserContext, type Page, type TestInfo } from '@playwright/test'
import { fakeCase, isPhoneProject, openCard, openClips, playsPast } from './helpers'
import { makePin, type Pin } from '../src/ui/accountBook'

const BOOK = 'tmplayer-accounts'
/** The picker in each form, for a look by eye (design/web-profiles). */
const SHOTS = `${process.cwd()}/../design/web-profiles`
async function shot(page: Page, info: TestInfo, name: string) {
    mkdirSync(SHOTS, { recursive: true })
    await page.screenshot({ path: `${SHOTS}/${name}-${info.project.name}.png` })
}
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

/**
 * Two profiles already on this browser: the adopted one (account 1) and `abc123` (account 2), each with
 * its chats answered. `attention`: abc123 has no session; `corrupt`: its session cannot be read.
 */
async function twoProfiles(page: Page, extra: { layout?: string; attention?: boolean; corrupt?: boolean; active?: string; pins?: { main?: Pin; abc123?: Pin } } = {}) {
    await page.goto('/__e2e/sizes')
    await page.evaluate(
        ({ book, layout, attention, corrupt }) =>
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
                            t2.objectStore('fake').put(corrupt ? { corrupt: true } : { id: 2 }, 'self')
                            t2.oncomplete = () => (tg.result.close(), resolve())
                        }
                    }
                }
            }),
        {
            book: {
                v: 1,
                profiles: [
                    { id: 'main', name: 'Dana Test', userId: 1, state: 'ok', pin: extra.pins?.main ?? null, fails: 0, lockedUntil: 0, added: 1 },
                    { id: 'abc123', name: 'Robin Test', userId: 2, state: extra.corrupt ? 'attention' : 'ok', pin: extra.pins?.abc123 ?? null, fails: 0, lockedUntil: 0, added: 2 },
                ],
                active: extra.active ?? 'main',
            },
            layout: extra.layout,
            attention: extra.attention,
            corrupt: extra.corrupt,
        },
    )
}

const picker = (page: Page) => page.getByRole('dialog', { name: PICKER })
const tile = (page: Page, name: RegExp) => picker(page).getByRole('button', { name })

/** Settings' list, where the account and its profile menu are. */
async function openSettings(page: Page) {
    // Inside the app, as its own links go: a new page load would ask a PIN profile's PIN again
    // (an unlock is taken over only by a reload of the tab or a page the tab started itself).
    if ((await page.locator('main.page').count()) > 0) await page.evaluate(() => (history.pushState(null, '', '/settings'), dispatchEvent(new PopStateEvent('popstate'))))
    else await page.goto('/settings')
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

test('the picker at launch, a PIN, and five wrong tries wait 30 s across a reload', async ({ page }, info) => {
    await twoProfiles(page)
    await page.goto('/')
    await expect(picker(page)).toBeVisible()
    await shot(page, info, 'picker-launch')
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
    await shot(page, info, 'pin')
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

test('a profile whose session is gone needs attention, and Remove takes it away', async ({ page }, info) => {
    await twoProfiles(page, { attention: true })
    await page.goto('/')
    await tile(page, /^Robin Test/).click()
    // Its session is missing: the picker says so, nothing crashes.
    await expect(tile(page, /^Robin Test, In use, Needs attention/)).toBeVisible({ timeout: 15_000 })
    await shot(page, info, 'needs-attention')
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
    await shot(page, info, 'picker-tv')
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

// ---- Security review: a PIN profile never opens without its PIN ----

/** Notes in the tab whether a screen of the app ever showed while a sign out was pending (it must not). */
async function watchPendingScreens(page: Page) {
    await page.addInitScript(() => {
        // Only a page that starts with the sign out pending is the signed out profile's own.
        if (!sessionStorage.getItem('tmplayer-accounts-pending')) return
        const check = () => {
            if (document.querySelector('main.page, .profiles')) sessionStorage.setItem('e2e-pending-screen', '1')
        }
        new MutationObserver(check).observe(document, { childList: true, subtree: true })
    })
}

test('signing out a locked profile asks its PIN, and none of its screens ever opens', async ({ page }) => {
    const robin = await makePin('4821', globalThis.crypto, 2000)
    await twoProfiles(page, { pins: { abc123: robin } })
    await watchPendingScreens(page)
    await page.goto('/')
    await tile(page, /^Dana Test/).click()
    await expect(page.locator('main.page')).toBeVisible()
    await openSettings(page)
    await page.getByRole('button', { name: 'Switch profile' }).click()
    await picker(page).getByRole('button', { name: 'Manage profiles' }).click()
    await tile(page, /^Robin Test/).click()
    // Its options are behind its PIN.
    const pin = page.locator('dialog.pin-dialog')
    await expect(pin).toBeVisible()
    await page.keyboard.type('4821')
    await page.getByRole('dialog', { name: 'Robin Test' }).getByRole('button', { name: 'Sign out of this profile' }).click()
    await reloads(page, () => page.getByRole('dialog', { name: 'Sign out of Robin Test?' }).getByRole('button', { name: 'Sign out', exact: true }).click())
    await expect(page.getByText('Signed out of that profile')).toBeVisible({ timeout: 15_000 })
    expect((await readBook(page))!.profiles.map((p) => p.id)).toEqual(['main'])
    expect(await page.evaluate(() => [sessionStorage.getItem('e2e-pending-screen'), sessionStorage.getItem('tmplayer-tab-profile'), sessionStorage.getItem('tmplayer-accounts-pending')])).toEqual([null, 'main', null])
})

test('cancel on a new account goes back to the picker when the profile it came from has a PIN', async ({ page }) => {
    const dana = await makePin('1111', globalThis.crypto, 2000)
    await twoProfiles(page, { pins: { main: dana } })
    await page.goto('/')
    await tile(page, /^Dana Test/).click()
    // The profile the page already runs: it opens in place.
    await page.keyboard.type('1111')
    await expect(page.locator('main.page')).toBeVisible()
    await openSettings(page)
    await expect(page.locator('.set-account').getByText('Dana Test')).toBeVisible()
    await page.getByRole('button', { name: 'Switch profile' }).click()
    await reloads(page, () => picker(page).getByRole('button', { name: 'Add account' }).click())
    await reloads(page, () => page.getByRole('button', { name: 'Cancel, go back' }).click())
    // Not straight back into Dana: the picker, and her PIN again.
    await expect(picker(page)).toBeVisible()
    await expect(page.locator('main.page')).toHaveCount(0)
    await tile(page, /^Dana Test/).click()
    await expect(page.locator('dialog.pin-dialog')).toBeVisible()
    // A reload does not get past it either.
    await page.reload()
    await expect(picker(page)).toBeVisible()
    await expect(page.locator('main.page')).toHaveCount(0)
})

test('Remove all asks every locked PIN first, and a cancelled one wipes nothing', async ({ page }) => {
    const robin = await makePin('4821', globalThis.crypto, 2000)
    await twoProfiles(page, { pins: { abc123: robin } })
    await page.goto('/')
    await tile(page, /^Dana Test/).click()
    await expect(page.locator('main.page')).toBeVisible()
    await openSettings(page)
    await page.getByRole('button', { name: 'Remove all accounts' }).click()
    const pin = page.locator('dialog.pin-dialog')
    await expect(pin.getByRole('heading', { name: 'Enter the PIN for Robin Test' })).toBeVisible()
    await pin.getByRole('button', { name: 'Cancel' }).click()
    await expect(page.getByRole('dialog', { name: 'Remove all accounts?' })).toHaveCount(0)
    expect((await readBook(page))!.profiles.map((p) => p.id)).toEqual(['main', 'abc123'])
    // A wrong one does not get past either.
    await page.getByRole('button', { name: 'Remove all accounts' }).click()
    await page.keyboard.type('0000')
    await expect(pin.getByText('Wrong PIN. 4 tries left')).toBeVisible()
    await page.keyboard.type('4821')
    // The browser's own storage, deleted with Dana's profile, starts signed out (the fake's default is signed in).
    await fakeCase(page.context(), 'signedout')
    await page.getByRole('dialog', { name: 'Remove all accounts?' }).getByRole('button', { name: 'Remove all' }).click()
    await expect(page).toHaveURL(/\/signin/, { timeout: 20_000 })
    expect((await readBook(page))!.profiles.map((p) => [p.id, p.userId])).toEqual([['main', null]])
})

test('a minute hidden locks a PIN profile again', async ({ page }) => {
    const dana = await makePin('1111', globalThis.crypto, 2000)
    await twoProfiles(page, { pins: { main: dana } })
    await page.clock.install()
    await page.goto('/')
    await tile(page, /^Dana Test/).click()
    // The profile the page already runs: it opens in place.
    await page.keyboard.type('1111')
    await expect(page.locator('main.page')).toBeVisible()
    const show = (state: 'hidden' | 'visible') =>
        page.evaluate((s) => {
            Object.defineProperty(document, 'visibilityState', { value: s, configurable: true })
            document.dispatchEvent(new Event('visibilitychange'))
        }, state)
    // Under a minute away: still open.
    await show('hidden')
    await page.clock.fastForward(30_000)
    await show('visible')
    await expect(picker(page)).toHaveCount(0)
    // Over a minute: locked, and the picker cannot be closed without the PIN.
    await show('hidden')
    await page.clock.fastForward(61_000)
    await show('visible')
    await expect(picker(page)).toBeVisible()
    await expect(page.getByRole('button', { name: 'Close profiles' })).toHaveCount(0)
    await page.keyboard.press('Escape')
    await expect(picker(page)).toBeVisible()
    expect(await page.evaluate(() => sessionStorage.getItem('tmplayer-tab-unlocked'))).toBeNull()
    await tile(page, /^Dana Test/).click()
    await page.keyboard.type('1111')
    await expect(picker(page)).toHaveCount(0)
    await expect(page.locator('main.page')).toBeVisible()
})

// ---- Second review ----

/** The tab shown or hidden, as the browser says it. */
const showTab = (page: Page, state: 'hidden' | 'visible') =>
    page.evaluate((s) => {
        Object.defineProperty(document, 'visibilityState', { value: s, configurable: true })
        document.dispatchEvent(new Event('visibilitychange'))
    }, state)

/** Dana with a PIN, already unlocked in this tab (as after entering it): the page opens with no picker. */
async function danaUnlocked(page: Page) {
    const dana = await makePin('1111', globalThis.crypto, 2000)
    await twoProfiles(page, { pins: { main: dana } })
    await page.evaluate(() => {
        sessionStorage.setItem('tmplayer-tab-profile', 'main')
        // As this tab's own page leaves it when it starts the next one on Dana: the unlock and its handoff.
        sessionStorage.setItem('tmplayer-tab-unlocked', JSON.stringify({ id: 'main', at: Date.now(), token: 'e2etoken0001' }))
        sessionStorage.setItem('tmplayer-unlock-handoff', 'e2etoken0001')
    })
}

test('on the picker at launch, signing out the profile the page would run wipes it', async ({ page }) => {
    await twoProfiles(page)
    await page.goto('/')
    await expect(picker(page)).toBeVisible()
    await picker(page).getByRole('button', { name: 'Manage profiles' }).click()
    await tile(page, /^Dana Test/).click()
    await page.getByRole('dialog', { name: 'Dana Test' }).getByRole('button', { name: 'Sign out of this profile' }).click()
    await reloads(page, () => page.getByRole('dialog', { name: 'Sign out of Dana Test?' }).getByRole('button', { name: 'Sign out', exact: true }).click())
    await expect(page.getByText('Signed out of that profile')).toBeVisible({ timeout: 15_000 })
    expect((await readBook(page))!.profiles.map((p) => p.id)).toEqual(['abc123'])
    const keys = await storedKeys(page)
    expect(keys.filter((k) => !k.startsWith('@') && k !== 'addedMode' && k !== 'settings')).toEqual([])
    expect(keys).toContain('@abc123:addedMode')
    await openSettings(page)
    await expect(page.locator('.set-account').getByText('Robin Test')).toBeVisible()
})

test('a place saved as the page closes after a sign out of it lands nowhere', async ({ page }) => {
    await danaUnlocked(page)
    await page.clock.install()
    await openClips(page)
    await openCard(page, 'Native Clip')
    await playsPast(page, 1)
    // Locked under the player, then signed out from the picker: the player's save on the way out must not bring its place back.
    await showTab(page, 'hidden')
    await page.clock.fastForward(61_000)
    await showTab(page, 'visible')
    await expect(picker(page)).toBeVisible()
    await picker(page).getByRole('button', { name: 'Manage profiles' }).click()
    await tile(page, /^Dana Test/).click()
    await page.keyboard.type('1111')
    await page.getByRole('dialog', { name: 'Dana Test' }).getByRole('button', { name: 'Sign out of this profile' }).click()
    await reloads(page, () => page.getByRole('dialog', { name: 'Sign out of Dana Test?' }).getByRole('button', { name: 'Sign out', exact: true }).click())
    await expect(page.getByText('Signed out of that profile')).toBeVisible({ timeout: 15_000 })
    expect(await page.evaluate(() => Object.keys(localStorage).filter((k) => k.startsWith('tmplayer-last-position')))).toEqual([])
})

test('a PIN entered for a Remove all that was cancelled is asked again', async ({ page }) => {
    const robin = await makePin('4821', globalThis.crypto, 2000)
    await twoProfiles(page, { pins: { abc123: robin } })
    await page.goto('/')
    await tile(page, /^Dana Test/).click()
    await expect(page.locator('main.page')).toBeVisible()
    await openSettings(page)
    const pin = page.locator('dialog.pin-dialog')
    await page.getByRole('button', { name: 'Remove all accounts' }).click()
    await expect(pin.getByText("Forgot the PIN? It can only be cleared by clearing this site's data in the browser's settings, and that removes every profile on this browser.")).toBeVisible()
    await page.keyboard.type('4821')
    await page.getByRole('dialog', { name: 'Remove all accounts?' }).getByRole('button', { name: 'Cancel' }).click()
    await page.getByRole('button', { name: 'Remove all accounts' }).click()
    await expect(pin.getByRole('heading', { name: 'Enter the PIN for Robin Test' })).toBeVisible()
    await pin.getByRole('button', { name: 'Cancel' }).click()
    expect((await readBook(page))!.profiles.map((p) => p.id)).toEqual(['main', 'abc123'])
})

test('a copied unlock (a duplicated tab, a session restored later) has run out; a reload keeps a fresh one', async ({ page }) => {
    await danaUnlocked(page)
    await page.goto('/')
    await expect(page.locator('main.page')).toBeVisible()
    // A reload of the same tab: still open.
    await page.reload()
    await expect(page.locator('main.page')).toBeVisible()
    await expect(picker(page)).toHaveCount(0)
    // The same unlock, two minutes old, as a restored session carries it: the picker and the PIN.
    await page.evaluate(() => sessionStorage.setItem('tmplayer-tab-unlocked', JSON.stringify({ id: 'main', at: Date.now() - 120_000 })))
    await page.reload()
    await expect(picker(page)).toBeVisible()
    await expect(page.locator('main.page')).toHaveCount(0)
    await tile(page, /^Dana Test/).click()
    await expect(page.locator('dialog.pin-dialog')).toBeVisible()
})

test('locking again with the picker unable to load covers everything, with Try again', async ({ page }) => {
    await danaUnlocked(page)
    // Offline for the picker's code: never fetched, so the lock has nothing but its cover.
    await page.route('**/src/ui/Profiles.tsx*', (route) => route.abort())
    await page.clock.install()
    await page.goto('/')
    await expect(page.locator('main.page')).toBeVisible()
    await showTab(page, 'hidden')
    await page.clock.fastForward(61_000)
    await showTab(page, 'visible')
    const cover = page.locator('.lock-cover')
    await expect(cover.getByText('The profiles could not load. Check the connection and try again.')).toBeVisible()
    // Nothing of the profile under it: the page is inert and the cover is opaque over all of it.
    await expect(page.locator('main.page')).toHaveAttribute('inert', /.*/)
    const box = await cover.boundingBox()
    expect(box).toEqual({ x: 0, y: 0, width: page.viewportSize()!.width, height: page.viewportSize()!.height })
    expect(await cover.evaluate((el) => getComputedStyle(el).backgroundColor)).not.toMatch(/rgba\(0, 0, 0, 0\)|transparent/)
    await page.unroute('**/src/ui/Profiles.tsx*')
    await reloads(page, () => cover.getByRole('button', { name: 'Try again' }).click())
    await expect(picker(page)).toBeVisible()
    await expect(page.locator('main.page')).toHaveCount(0)
})

// ---- Third review ----

/** What the screen shows in its middle: inside the picker (or its cover), or the profile's page. */
const topAtMiddle = (page: Page) =>
    page.evaluate(() => {
        const el = document.elementFromPoint(innerWidth / 2, innerHeight / 2)
        return el?.closest('dialog.profiles, dialog.lock-cover') ? 'picker' : el?.closest('dialog') ? 'other dialog' : 'page'
    })

test('locking again closes a question that was open, and the picker is on top of everything', async ({ page }) => {
    await danaUnlocked(page)
    await page.clock.install()
    await page.goto('/settings')
    await page.getByRole('button', { name: 'Sign out of Telegram' }).click()
    await expect(page.getByRole('dialog', { name: 'Sign out of Dana Test?' })).toBeVisible()
    await showTab(page, 'hidden')
    await page.clock.fastForward(61_000)
    await showTab(page, 'visible')
    await expect(picker(page)).toBeVisible()
    // Only the picker is open, in the top layer, and it is what shows.
    expect(await page.evaluate(() => [...document.querySelectorAll('dialog[open]')].map((d) => d.className))).toEqual(['profiles'])
    expect(await page.locator('dialog.profiles').evaluate((d) => d.matches(':modal'))).toBe(true)
    expect(await topAtMiddle(page)).toBe('picker')
    // Escape does not close it while locked.
    await page.keyboard.press('Escape')
    await expect(picker(page)).toBeVisible()
})

test('on the picker at launch, a profile whose session cannot be read is removed here, and said so', async ({ page }) => {
    await twoProfiles(page, { corrupt: true, active: 'abc123' })
    await page.goto('/')
    await expect(tile(page, /^Robin Test, Needs attention/)).toBeVisible()
    await tile(page, /^Robin Test/).click()
    await page.getByRole('dialog', { name: 'Robin Test' }).getByRole('button', { name: 'Remove from this browser' }).click()
    await reloads(page, () => page.getByRole('dialog', { name: 'Remove Robin Test?' }).getByRole('button', { name: 'Remove', exact: true }).click())
    await expect(page.getByText('Profile removed')).toBeVisible({ timeout: 15_000 })
    expect((await readBook(page))!.profiles.map((p) => p.id)).toEqual(['main'])
    expect((await storedKeys(page)).filter((k) => k.startsWith('@'))).toEqual([])
    expect(await page.evaluate(async () => (await indexedDB.databases()).map((d) => d.name).includes('tmplayer-abc123'))).toBe(false)
})

test('on the picker at launch, Sign in again on an unreadable session opens its sign in, its lists kept', async ({ page }) => {
    await twoProfiles(page, { corrupt: true, active: 'abc123' })
    await page.goto('/')
    await tile(page, /^Robin Test/).click()
    await page.getByRole('dialog', { name: 'Robin Test' }).getByRole('button', { name: 'Sign in again' }).click()
    await expect(page).toHaveURL(/\/signin/, { timeout: 15_000 })
    await expect(picker(page)).toHaveCount(0)
    expect(await storedKeys(page)).toContain('@abc123:addedMode')
    expect((await readBook(page))!.profiles.map((p) => p.id)).toEqual(['main', 'abc123'])
})

test('a copy of the tab (its sessionStorage in a new tab) does not open the PIN profile', async ({ page, browser }) => {
    await danaUnlocked(page)
    await page.goto('/')
    await expect(page.locator('main.page')).toBeVisible()
    // Duplicate tab: the original stays open, the copy carries its sessionStorage and the shared localStorage.
    const stored = await page.evaluate(() => ({ session: { ...sessionStorage }, local: { ...localStorage } }))
    const copy = await page.context().newPage()
    await copy.goto('/__e2e/sizes')
    await copy.evaluate(({ session }) => Object.entries(session).forEach(([k, v]) => sessionStorage.setItem(k, v)), stored)
    await copy.goto('/')
    await expect(picker(copy)).toBeVisible()
    await expect(copy.locator('main.page')).toHaveCount(0)
    await tile(copy, /^Dana Test/).click()
    await expect(copy.locator('dialog.pin-dialog')).toBeVisible()
    await copy.close()
    // A new browser context restored from the same storage (a session restored after a crash): the same.
    const restored = await browser.newContext()
    const again = await restored.newPage()
    await again.goto('/__e2e/sizes')
    await again.evaluate(({ session, local }) => {
        Object.entries(local).forEach(([k, v]) => localStorage.setItem(k, v))
        Object.entries(session).forEach(([k, v]) => sessionStorage.setItem(k, v))
    }, stored)
    await again.reload()
    await again.goto('/')
    await expect(picker(again)).toBeVisible()
    await restored.close()
    // The original is still open and unlocked.
    await expect(page.locator('main.page')).toBeVisible()
    await expect(picker(page)).toHaveCount(0)
})

test('Remove all refuses, before wiping anything, while a profile is open in another tab', async ({ page, context }) => {
    await twoProfiles(page)
    await page.goto('/')
    await tile(page, /^Dana Test/).click()
    await expect(page.locator('main.page')).toBeVisible()
    const other = await context.newPage()
    await other.goto('/')
    await reloads(other, () => tile(other, /^Robin Test/).click())
    await expect(other.locator('main.page')).toBeVisible()
    await openSettings(page)
    await page.getByRole('button', { name: 'Remove all accounts' }).click()
    await expect(page.getByText('A profile is open in another tab. Close it there first: nothing was removed')).toBeVisible()
    await expect(page.getByRole('dialog', { name: 'Remove all accounts?' })).toHaveCount(0)
    expect((await readBook(page))!.profiles.map((p) => p.id)).toEqual(['main', 'abc123'])
    await other.close()
})

// ---- Fourth review ----

test('locked under a playing video: no key plays, opens or skips it, and nothing of it plays on', async ({ page }) => {
    // The Media Session as the OS's player sees it: every handler the page sets is noted.
    await page.addInitScript(() => {
        const handlers: Record<string, unknown> = {}
        ;(window as unknown as { __media: Record<string, unknown> }).__media = handlers
        // On the prototype, not the instance: WebKit gives navigator.mediaSession no own method to replace, or makes it after this script ran.
        const proto = globalThis.MediaSession?.prototype
        if (!proto) return
        const set = proto.setActionHandler
        proto.setActionHandler = function (this: MediaSession, action: MediaSessionAction, handler: MediaSessionActionHandler | null) {
            handlers[action] = handler
            return set.call(this, action, handler)
        }
    })
    await danaUnlocked(page)
    await page.clock.install()
    await openClips(page)
    await openCard(page, 'Native Clip')
    await playsPast(page, 1)
    const watchPath = new URL(page.url()).pathname
    expect(await page.evaluate(() => Object.values((window as unknown as { __media: Record<string, unknown> }).__media).some((h) => typeof h === 'function'))).toBe(true)
    await showTab(page, 'hidden')
    await page.clock.fastForward(61_000)
    await showTab(page, 'visible')
    await expect(picker(page)).toBeVisible()
    // The player closed after keeping its place; its media and the Media Session are empty.
    await expect(page).not.toHaveURL(/\/watch\//)
    const state = () =>
        page.evaluate(() => ({
            playing: [...document.querySelectorAll('video, audio')].some((m) => !(m as HTMLMediaElement).paused),
            pip: !!document.pictureInPictureElement,
            fullscreen: !!document.fullscreenElement,
            metadata: navigator.mediaSession?.metadata ?? null,
            // WebKit works the state out itself: with no media left it reads 'paused', which is as quiet as 'none'. Only 'playing' is a gap.
            playback: navigator.mediaSession?.playbackState === 'playing' ? 'playing' : 'none',
            handlers: Object.entries((window as unknown as { __media: Record<string, unknown> }).__media).filter(([, h]) => h !== null).map(([a]) => a),
            path: location.pathname,
            dialogs: [...document.querySelectorAll('dialog[open]')].map((d) => d.className),
        }))
    const locked = { playing: false, pip: false, fullscreen: false, metadata: null, playback: 'none', handlers: [], path: '/', dialogs: ['profiles'] }
    expect(await state()).toEqual(locked)
    // The player's keys, the page's keys: nothing changes.
    for (const key of ['k', 'p', 'Control+p', 'Shift+N', ' ', 'ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', '/', 'Escape', 'Control+k']) await page.keyboard.press(key)
    await page.clock.fastForward(2_000)
    expect(await state()).toEqual(locked)
    await expect(picker(page)).toBeVisible()
    // The page behind is inert and hidden from screen readers.
    expect(await page.locator('main.page').evaluate((m) => m.hasAttribute('inert'))).toBe(true)
    // The bar that mounted after the lock (the player left) and an element added later are shielded too.
    await expect(page.locator('nav.nav')).toHaveAttribute('inert', /.*/)
    await page.evaluate(() => {
        document.body.appendChild(document.createElement('aside')).id = 'late-chrome'
    })
    await expect(page.locator('#late-chrome')).toHaveAttribute('inert', /.*/)
    await expect(page.locator('#late-chrome')).toHaveAttribute('aria-hidden', 'true')
    // A browser Forward to /watch while locked renders no player and plays nothing: it goes to the lock.
    await page.evaluate((path) => {
        history.pushState({}, '', path)
        dispatchEvent(new PopStateEvent('popstate', { state: {} }))
    }, watchPath)
    await expect(page).not.toHaveURL(/\/watch\//)
    await expect(page.locator('video, audio, .media-container')).toHaveCount(0)
    expect(await state()).toEqual(locked)
    // A dialog that opens late (an answer coming back after the lock) does not open.
    expect(
        await page.evaluate(() => {
            const d = document.body.appendChild(document.createElement('dialog'))
            d.textContent = 'late answer'
            d.showModal()
            return d.open
        }),
    ).toBe(false)
    // The PIN opens it again, at Home; the place was kept for Continue watching.
    await tile(page, /^Dana Test/).click()
    await page.keyboard.type('1111')
    await expect(picker(page)).toHaveCount(0)
    expect(await page.evaluate(() => Object.keys(localStorage).some((k) => k.startsWith('tmplayer-last-position')))).toBe(true)
})

test('Remove all checks again right before wiping: a tab opened while its question was up stops it', async ({ page, context }) => {
    await twoProfiles(page)
    await page.goto('/')
    await tile(page, /^Dana Test/).click()
    await expect(page.locator('main.page')).toBeVisible()
    await openSettings(page)
    await page.getByRole('button', { name: 'Remove all accounts' }).click()
    const ask = page.getByRole('dialog', { name: 'Remove all accounts?' })
    await expect(ask).toBeVisible()
    const other = await context.newPage()
    await other.goto('/')
    await reloads(other, () => tile(other, /^Robin Test/).click())
    await expect(other.locator('main.page')).toBeVisible()
    await ask.getByRole('button', { name: 'Remove all' }).click()
    await expect(page.getByText('A profile is open in another tab. Close it there first: nothing was removed')).toBeVisible()
    expect((await readBook(page))!.profiles.map((p) => p.id)).toEqual(['main', 'abc123'])
    expect(await storedKeys(page)).toContain('@abc123:addedMode')
    await other.close()
})
