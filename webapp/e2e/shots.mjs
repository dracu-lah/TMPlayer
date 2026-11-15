// UX audit captures (docs/ux-audit.md): the web app over the fake Telegram at 390, 768 and 1280
// wide, in system Chromium (headless). Start the test server first:
//   E2E_PORT=8197 pnpm exec vite --config e2e/vite.e2e.ts --port 8197
// then: node e2e/shots.mjs <out dir> [port]
import { chromium } from '@playwright/test'
import { mkdirSync } from 'node:fs'

const out = process.argv[2] ?? 'shots'
const port = Number(process.argv[3] ?? 8197)
const base = `http://127.0.0.1:${port}`
mkdirSync(out, { recursive: true })

const forms = [
    { name: '390', width: 390, height: 844, touch: true },
    { name: '768', width: 768, height: 1024, touch: true },
    { name: '1280', width: 1280, height: 800, touch: false },
]
const pages = [
    ['home', '/'],
    ['chats', '/chats'],
    ['chat', '/chat/-1001'],
    ['search', '/search?q=clip'],
    ['settings', '/settings'],
    ['favourites', '/favourites'],
    ['history', '/history'],
    ['recent', '/recent'],
    ['chat-info', '/chat/-1001/info'],
]

const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium-browser', args: ['--disable-gpu', '--no-sandbox', '--hide-scrollbars'] })
for (const form of forms) {
    const context = await browser.newContext({
        viewport: { width: form.width, height: form.height },
        hasTouch: form.touch,
        isMobile: form.touch,
        deviceScaleFactor: 1,
    })
    if (!form.touch) await context.addInitScript(() => localStorage.setItem('tmplayer-layout', 'desktop'))
    const page = await context.newPage()
    // Every list shows the test files, which are under the default 50 MB floor.
    await page.goto(base + '/__e2e/sizes')
    await page.evaluate(() => new Promise((resolve) => {
        const open = indexedDB.open('tmplayer-app')
        open.onupgradeneeded = () => open.result.createObjectStore('kv')
        open.onsuccess = () => {
            const tx = open.result.transaction('kv', 'readwrite')
            tx.objectStore('kv').put({ sizeMin: 0 }, 'settings')
            tx.oncomplete = () => (open.result.close(), resolve())
        }
    }))
    for (const [name, path] of pages) {
        await page.goto(base + path)
        await page.waitForTimeout(2500)
        await page.screenshot({ path: `${out}/web-${name}-${form.name}.png` })
        const wide = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1)
        if (wide) console.log(`horizontal scroll: ${name} at ${form.name}`)
    }
    // A video's page: the first card of Test Clips.
    await page.goto(base + '/chat/-1001')
    await page.waitForTimeout(2000)
    const card = page.locator('.card').first()
    if (await card.count()) {
        await card.click()
        await page.waitForTimeout(2000)
        await page.screenshot({ path: `${out}/web-detail-${form.name}.png` })
    }
    // Small touch targets on the pages a phone reaches first.
    if (form.touch) {
        for (const [name, path] of pages.slice(0, 5)) {
            await page.goto(base + path)
            await page.waitForTimeout(1500)
            const small = await page.evaluate(() => [...document.querySelectorAll('a,button,[role=button],input,select,[role=tab],[role=menuitem]')]
                .filter((el) => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0 && (r.width < 44 || r.height < 44) && getComputedStyle(el).visibility !== 'hidden' })
                .map((el) => { const r = el.getBoundingClientRect(); return `${el.tagName.toLowerCase()}.${(el.className || '').toString().split(' ')[0]} "${(el.getAttribute('aria-label') || el.textContent || '').trim().slice(0, 30)}" ${Math.round(r.width)}x${Math.round(r.height)}` }))
            if (small.length) console.log(`small targets ${name} at ${form.name}:\n  ` + [...new Set(small)].join('\n  '))
        }
    }
    await context.close()
}
await browser.close()
