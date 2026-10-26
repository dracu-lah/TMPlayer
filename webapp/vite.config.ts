import { createHash } from 'node:crypto'
import { readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { defineConfig, type Plugin } from 'vite'
import solid from 'vite-plugin-solid'
import { notices } from './notices.config'
import { createHandler } from './tmdb/handler.ts'
import { PAGE_STRINGS } from './strings.config'

// The app's own api_id and hash: from the environment in CI, else from the repo's local.properties
// (the same secrets the Gradle builds read). Never a sample one.
function secret(name: string): string {
    const env = process.env[name]
    if (env) return env
    try {
        const line = readFileSync(new URL('../local.properties', import.meta.url), 'utf8')
            .split('\n')
            .find((l) => l.startsWith(`${name}=`))
        return line ? line.slice(name.length + 1).trim() : ''
    } catch {
        return ''
    }
}

const CATALOG = new URL('../core/src/commonMain/resources/i18n/en.json', import.meta.url)

function pageStrings(): Plugin {
    const id = 'virtual:strings'
    return {
        name: 'tmplayer-page-strings',
        resolveId: (source) => (source === id ? `\0${id}` : null),
        load(source) {
            if (source !== `\0${id}`) return null
            this.addWatchFile(CATALOG.pathname)
            const all = JSON.parse(readFileSync(CATALOG, 'utf8'))
            const out: Record<string, Record<string, unknown>> = {}
            for (const entry of PAGE_STRINGS) {
                const [section, key] = entry.split('.')
                const from = all[section]
                if (from === undefined || (key && from[key] === undefined)) this.error(`en.json has no ${entry}`)
                out[section] = key ? { ...out[section], [key]: from[key] } : from
            }
            return `export default ${JSON.stringify(out)}`
        },
    }
}

/** index.html's words for a browser too old to run the app, from the catalog, before any script of ours loads. */
function tooOldStrings(): Plugin {
    const escape = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/'/g, '&#39;')
    return {
        name: 'tmplayer-too-old',
        transformIndexHtml(html) {
            const web = JSON.parse(readFileSync(CATALOG, 'utf8')).web
            return html
                .replace('%TOO_OLD_TITLE%', escape(web.too_old_title))
                .replace('%TOO_OLD_BODY%', escape(web.too_old_body))
                .replace('%APP_GET%', escape(web.app_get))
        },
    }
}

/**
 * /tmdb/* for `pnpm dev` and `pnpm preview`: the poster proxy (tmdb/handler.ts, which Cloudflare
 * runs as functions/tmdb/[[path]].ts) with the key from the environment or local.properties.
 * Without one every lookup answers 503 and the cards keep their thumbnails.
 */
function tmdbProxy(): Plugin {
    const kept = new Map<string, Response>()
    const handle = createHandler({
        fetch: (request) => fetch(request),
        cache: { match: async (r) => kept.get(r.url)?.clone(), put: async (r, res) => void kept.set(r.url, res) },
    })
    const mount = (use: (path: string, fn: (req: any, res: any) => void) => void) =>
        use('/tmdb', (req, res) => {
            const url = new URL(req.originalUrl ?? req.url, `http://${req.headers.host ?? 'localhost'}`)
            handle(new Request(url, { method: req.method, headers: req.headers }), { TMDB_API_KEY: secret('TMDB_API_KEY') }).then(
                async (r) => {
                    res.statusCode = r.status
                    r.headers.forEach((v, k) => res.setHeader(k, v))
                    res.end(Buffer.from(await r.arrayBuffer()))
                },
                () => {
                    res.statusCode = 500
                    res.end()
                },
            )
        })
    return {
        name: 'tmplayer-tmdb-proxy',
        configureServer(server) {
            mount((path, fn) => void server.middlewares.use(path, fn))
        },
        configurePreviewServer(server) {
            mount((path, fn) => void server.middlewares.use(path, fn))
        },
    }
}

/** A release build needs the app's own api_id: one without it could never sign in. */
function needsApiId(): Plugin {
    return {
        name: 'tmplayer-api-id',
        apply: 'build',
        buildStart() {
            if (!Number(secret('TG_API_ID')) || !secret('TG_API_HASH')) this.error('TG_API_ID and TG_API_HASH are missing: set them in the environment or local.properties.')
        },
    }
}

/** Built files the service worker leaves for later: the sound decoders, fetched only for some videos. */
const NOT_IN_SHELL = /^mediabunny-(ac3|aac-encoder|dts)-|\.map$/

/**
 * Writes the built file list and a version made from it into dist/sw.js, which keeps them for
 * opening offline. Any change to any file makes a new version, so the browser sees a new worker
 * and the page offers Reload.
 */
function offlineShell(): Plugin {
    let outDir = 'dist'
    return {
        name: 'tmplayer-offline-shell',
        apply: 'build',
        configResolved: (config) => void (outDir = resolve(config.root, config.build.outDir)),
        closeBundle() {
            const sw = join(outDir, 'sw.js')
            const assets = readdirSync(join(outDir, 'assets')).filter((f) => !NOT_IN_SHELL.test(f)).sort()
            const shell = ['/', '/manifest.webmanifest', '/icon.svg', '/icon-192.png', '/icon-512.png', '/licenses.txt', ...assets.map((f) => `/assets/${f}`)]
            const version = createHash('sha256').update(readFileSync(join(outDir, 'index.html'))).update(shell.join('\n')).digest('hex').slice(0, 12)
            const source = readFileSync(sw, 'utf8')
            const out = source.replace("const VERSION = 'dev'", `const VERSION = '${version}'`).replace('const SHELL = []', `const SHELL = ${JSON.stringify(shell)}`)
            if (out === source) this.error('sw.js has no VERSION or SHELL to fill')
            writeFileSync(sw, out)
        },
    }
}

const licences = notices()

export default defineConfig({
    plugins: [solid(), pageStrings(), tooOldStrings(), needsApiId(), tmdbProxy(), ...licences.page, offlineShell()],
    define: {
        __TG_API_ID__: JSON.stringify(Number(secret('TG_API_ID')) || 0),
        __TG_API_HASH__: JSON.stringify(secret('TG_API_HASH')),
    },
    worker: { format: 'es', plugins: licences.worker },
    optimizeDeps: { exclude: ['@mtcute/wasm', '@mtcute/web', '@mediabunny/ac3', '@mediabunny/dts'] },
    build: { target: 'es2022', sourcemap: true },
    // The UI catalog is read from :core, where every app reads it.
    server: { port: 8091, host: true, fs: { allow: ['.', '../core/src/commonMain/resources/i18n'] } },
    preview: { port: 8091, host: true },
})
