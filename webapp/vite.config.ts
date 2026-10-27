import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { build as viteBuild, defineConfig, type Plugin, type Rollup } from 'vite'
import solid from 'vite-plugin-solid'
import { legacyCss } from './legacy-css'
import { coreJs, notices } from './notices.config'
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

/**
 * Built files the service worker leaves for later, fetched (and then kept) only for the videos that
 * need them: the sound decoders, movi-player (player-*, about 10 MB) and hevc.js (hevc-*).
 */
const NOT_IN_SHELL = /^mediabunny-(ac3|aac-encoder|dts)-|^player-|^hevc-|\.map$/

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

let polyfillCode: Promise<string> | undefined

const FLEX_GAP_TEST =
    "(function(){var h=document.documentElement,d=document.createElement('div');d.style.cssText='display:flex;flex-direction:column;row-gap:1px;position:absolute';" +
    "d.appendChild(document.createElement('div'));d.appendChild(document.createElement('div'));h.appendChild(d);if(d.scrollHeight!==1)h.className+=' no-flex-gap';h.removeChild(d);" +
    "try{if(!CSS.supports('selector(:has(a))'))h.className+=' no-has'}catch(e){h.className+=' no-has'}})()"

/** src/legacy.ts (core-js and a few helpers) as one classic script for Chromium 68, built once per run. */
function polyfills(): Promise<string> {
    polyfillCode ??= viteBuild({
        configFile: false,
        logLevel: 'silent',
        build: {
            write: false,
            target: 'chrome68',
            lib: { entry: fileURLToPath(new URL('./src/legacy.ts', import.meta.url)), formats: ['iife'], name: 'tmplayerPolyfills', fileName: 'polyfills' },
        },
    }).then((out) => {
        const chunk = (Array.isArray(out) ? out[0] : (out as Rollup.RollupOutput)).output[0]
        return chunk.code.trim()
    })
    return polyfillCode
}

/**
 * `--mode tv`: the build smart TVs open (webOS 5 and 6 run Chromium 68 and 79, Tizen 2020 runs 69).
 * Syntax lowered to Chromium 68 and a classic worker, as module workers came in Chromium 80. core-js
 * fills the built-ins those engines lack, as its own classic script run before anything else: in
 * the page a script tag ahead of the module, in the Telegram worker the start of its file (core-js
 * is CommonJS, so imported first it would still run after the modules that need it). index.html's
 * old browser check asks only for what the lowered code still needs.
 */
function tvLegacy(inWorker = false): Plugin {
    const OLD_CHECK = "try{new Function('class A{static{}}');ok=!!([].at&&window.structuredClone&&window.BigInt&&window.Worker&&Promise.allSettled)}catch(e){}"
    let file = ''
    return {
        name: 'tmplayer-tv-legacy',
        enforce: 'pre',
        async buildStart() {
            const code = await polyfills()
            if (inWorker) return
            file = `assets/polyfills-${createHash('sha256').update(code).digest('hex').slice(0, 8)}.js`
            this.emitFile({ type: 'asset', fileName: file, source: code })
        },
        transform(code, id) {
            if (/\/src\/.*\.tsx?$/.test(id) && code.includes('new Worker(')) {
                const out = code.replace(/(new Worker\([^)]*\),\s*\{\s*)type: 'module'/g, "$1type: 'classic'")
                if (out === code) this.error(`${id} starts a module worker the TV build cannot make classic`)
                return { code: out, map: null }
            }
        },
        async renderChunk(code, chunk) {
            if (inWorker && chunk.isEntry) return { code: `${await polyfills()}\n${code}`, map: null }
        },
        // mtcute's wasm uses saturating float to int, sign extension and bulk memory (Chromium 74 and
        // 75); Binaryen lowers them to the first wasm, which Chromium 68 compiles. Its SIMD build is
        // left alone: no TV engine here has SIMD, so mtcute never asks for it.
        async generateBundle(_, bundle) {
            if (!inWorker) return
            const wasmOpt = join(dirname(fileURLToPath(import.meta.resolve('binaryen'))), 'bin', 'wasm-opt')
            const tmp = mkdtempSync(join(tmpdir(), 'tmplayer-wasm-'))
            try {
                for (const out of Object.values(bundle)) {
                    if (out.type !== 'asset' || !/^assets\/mtcute-[\w-]+\.wasm$/.test(out.fileName) || out.fileName.startsWith('assets/mtcute-simd-')) continue
                    const from = join(tmp, 'in.wasm')
                    const to = join(tmp, 'out.wasm')
                    writeFileSync(from, out.source)
                    const lower = ['--detect-features', '--llvm-nontrapping-fptoint-lowering', '--signext-lowering', '--llvm-memory-copy-fill-lowering', '-O2']
                    execFileSync(process.execPath, [wasmOpt, from, ...lower, '-o', to])
                    // Fails unless the result is the first wasm alone.
                    execFileSync(process.execPath, [wasmOpt, to, '--mvp-features', '-o', join(tmp, 'check.wasm')])
                    out.source = readFileSync(to)
                }
            } finally {
                rmSync(tmp, { recursive: true, force: true })
            }
        },
        transformIndexHtml(html) {
            if (inWorker) return
            if (!html.includes(OLD_CHECK)) this.error('index.html has no old browser check to replace')
            return {
                html: html.replace(OLD_CHECK, 'ok=!!(window.BigInt&&window.Worker&&window.WebAssembly&&window.indexedDB)'),
                tags: [
                    { tag: 'script', attrs: { src: `${TV_BASE}${file}` }, injectTo: 'head' },
                    // For legacy-css.ts: `no-flex-gap` where gap does nothing in a flex box (Chromium 83 and older),
                    // `no-has` where :has() is missing (104 and older).
                    { tag: 'script', children: FLEX_GAP_TEST, injectTo: 'head' },
                ],
            }
        },
    }
}

/**
 * Where the TV build lives on web.tmplayer.org: functions/_middleware.ts answers a TV's page loads
 * with /tv/index.html, so its assets and licences sit there while its routes stay the site's own.
 * `TV_BASE=/` builds it for the root, as served from this computer for the LAN wrappers (tv/README.md).
 */
const TV_BASE = process.env.TV_BASE || '/tv/'
let tvBuild = false
const licences = notices(() => (tvBuild ? [coreJs()] : []))

export default defineConfig(({ mode }) => {
    const tv = mode === 'tv'
    tvBuild = tv
    return {
        base: tv ? TV_BASE : '/',
        plugins: [solid(), pageStrings(), tooOldStrings(), needsApiId(), tmdbProxy(), ...licences.page, tv ? tvLegacy() : offlineShell()],
        define: {
            __TG_API_ID__: JSON.stringify(Number(secret('TG_API_ID')) || 0),
            __TG_API_HASH__: JSON.stringify(secret('TG_API_HASH')),
        },
        css: tv ? { postcss: { plugins: [legacyCss()] } } : {},
        worker: { format: tv ? 'iife' : 'es', plugins: () => (tv ? [tvLegacy(true), ...licences.worker()] : licences.worker()) },
        optimizeDeps: { exclude: ['@mtcute/wasm', '@mtcute/web', '@mediabunny/ac3', '@mediabunny/dts'] },
        build: tv ? { target: 'chrome68', cssTarget: 'chrome68', outDir: 'dist-tv', sourcemap: true } : { target: 'es2022', sourcemap: true },
        // The UI catalog is read from :core, where every app reads it.
        server: { port: 8091, host: true, fs: { allow: ['.', '../core/src/commonMain/resources/i18n'] } },
        // The TV build (`pnpm serve:tv`, at /tv/) answers on its own port, so both can run at once.
        preview: { port: tv ? 8092 : 8091, host: true },
    }
})
