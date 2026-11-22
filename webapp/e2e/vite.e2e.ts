// The app as the end to end tests run it: the real page and worker, with fake/mtcute.ts in place
// of @mtcute/web and e2e/media served with byte ranges, as Telegram's chunks would come.
import { createReadStream, readdirSync, statSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { defineConfig, mergeConfig, type Plugin } from 'vite'
import base from '../vite.config'

const MEDIA = fileURLToPath(new URL('./media/', import.meta.url))

/**
 * Every test but the chats gate's own starts on an account that has already answered which chats
 * it watches ('all', every chat), as the tests were written before choosing was required; the
 * `unanswered` case leaves it open so the gate (App.tsx) shows. Since HX86 'all' is the picker's
 * "All chats" switch on, its ticks disabled: the `picked` case answers with no chats picked instead,
 * for the tests that tick rows in the picker. Written before the app reads its
 * settings: IndexedDB runs this read-write transaction before the app's later read of the store.
 * A browser that refuses storage (app.spec.ts) is left to meet the gate.
 */
const ANSWERED = `(function(){try{if(/(?:^|;\\s*)e2e=[^;]*unanswered/.test(document.cookie))return;var o=indexedDB.open('tmplayer-app');o.onupgradeneeded=function(){o.result.createObjectStore('kv')};o.onsuccess=function(){var s=o.result.transaction('kv','readwrite').objectStore('kv'),g=s.get('addedMode'),m=/(?:^|;\\s*)e2e=[^;]*\\bpicked\\b/.test(document.cookie)?'picked':'all';g.onsuccess=function(){if(g.result===undefined)s.put(m,'addedMode')};s.transaction.oncomplete=function(){o.result.close()}}}catch(e){}})()`

/** One account's Saved Messages per test (the `cloud:<key>` flag): the messages that hold a sync file. */
interface CloudMessage {
    id: number
    date: number
    text: string
    fileName: string
    /** The file's bytes, base64. */
    bytes: string
}
const clouds = new Map<string, CloudMessage[]>()
let nextCloudId = 100

const testServer: Plugin = {
    name: 'tmplayer-e2e',
    transformIndexHtml: (html) => html.replace('<head>', `<head>\n  <script>${ANSWERED}</script>`),
    // Also behind `vite preview`, for the TV build made with this config (`--mode tv`) in old engines.
    configurePreviewServer(server) {
        ;(testServer.configureServer as (s: typeof server) => void)(server)
    },
    configureServer(server) {
        // The case a test picked, from its `e2e` cookie, for the fake in the worker.
        server.middlewares.use('/__e2e/flags', (req, res) => {
            res.end(/(?:^|;\s*)e2e=([^;]*)/.exec(req.headers.cookie ?? '')?.[1] ?? '')
        })
        // The account's Saved Messages as the fake keeps them (fake/mtcute.ts, flag `cloud:<key>`): in this server, so the
        // test reads what the page put there, and a second browser context is a second device on the same account.
        server.middlewares.use('/__e2e/cloud', (req, res) => {
            const url = new URL(req.url ?? '/', 'http://x')
            const key = url.searchParams.get('key') ?? 'default'
            const list = (clouds.get(key) ?? clouds.set(key, []).get(key))!
            const chunks: Buffer[] = []
            req.on('data', (c) => chunks.push(c))
            req.on('end', () => {
                const op = url.searchParams.get('op')
                const body = chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : null
                if (op === 'put') list.push({ ...body, id: nextCloudId++ })
                else if (op === 'edit') Object.assign(list.find((m) => m.id === body.id) ?? {}, body)
                else if (op === 'delete') list.splice(0, list.length, ...list.filter((m) => !body.ids.includes(m.id)))
                else if (op === 'reset') list.length = 0
                res.setHeader('Content-Type', 'application/json')
                res.end(JSON.stringify(op === 'put' ? list[list.length - 1] : list))
            })
        })
        server.middlewares.use('/__e2e/sizes', (_, res) => {
            res.setHeader('Content-Type', 'application/json')
            res.end(JSON.stringify(Object.fromEntries(readdirSync(MEDIA).map((f) => [f, statSync(MEDIA + f).size]))))
        })
        server.middlewares.use('/__e2e/media/', (req, res) => {
            const file = MEDIA + decodeURIComponent(req.url!.slice(1).split('?')[0]).replace(/[/\\]/g, '')
            let size: number
            try {
                size = statSync(file).size
            } catch {
                res.statusCode = 404
                return res.end()
            }
            const m = /bytes=(\d+)-(\d*)/.exec(req.headers.range ?? '')
            if (!m) return createReadStream(file).pipe(res)
            const start = +m[1]
            const end = Math.min(m[2] ? +m[2] : size - 1, size - 1)
            // A range that starts past the end (a probe reading beyond a short file) is answered as a
            // server does, 416. It used to throw out of createReadStream: Vite then logged an
            // "Internal server error" and sent its error overlay to every open page, which sits over
            // the page and swallows the next test's click (the Web player failures of 2026-11-17).
            if (start >= size) {
                res.statusCode = 416
                res.setHeader('Content-Range', `bytes */${size}`)
                return res.end()
            }
            res.statusCode = 206
            res.setHeader('Content-Range', `bytes ${start}-${end}/${size}`)
            res.setHeader('Content-Length', end - start + 1)
            createReadStream(file, { start, end }).pipe(res)
        })
    },
}

export default defineConfig((env) =>
    mergeConfig(base(env), {
        root: fileURLToPath(new URL('..', import.meta.url)),
        plugins: [testServer],
        // MSone is offered only in a build with the gateway key; its answers are the tests' own (e2e/subpicks.spec.ts).
        define: { __MSONE_GATEWAY_KEY__: JSON.stringify('e2e-key'), __LOG_UPLOAD_URL__: JSON.stringify('https://inbox.test/v1/report') },
        resolve: { alias: { '@mtcute/web': fileURLToPath(new URL('./fake/mtcute.ts', import.meta.url)) } },
        // No error overlay: a page of a test is never covered by Vite's, whatever the server logs.
        server: { port: Number(process.env.E2E_PORT) || 8097, strictPort: true, host: '127.0.0.1', hmr: { overlay: false } },
    }),
)
