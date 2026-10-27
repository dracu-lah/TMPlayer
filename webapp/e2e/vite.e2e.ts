// The app as the end to end tests run it: the real page and worker, with fake/mtcute.ts in place
// of @mtcute/web and e2e/media served with byte ranges, as Telegram's chunks would come.
import { createReadStream, readdirSync, statSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { defineConfig, mergeConfig, type Plugin } from 'vite'
import base from '../vite.config'

const MEDIA = fileURLToPath(new URL('./media/', import.meta.url))

const testServer: Plugin = {
    name: 'tmplayer-e2e',
    // Also behind `vite preview`, for the TV build made with this config (`--mode tv`) in old engines.
    configurePreviewServer(server) {
        ;(testServer.configureServer as (s: typeof server) => void)(server)
    },
    configureServer(server) {
        // The case a test picked, from its `e2e` cookie, for the fake in the worker.
        server.middlewares.use('/__e2e/flags', (req, res) => {
            res.end(/(?:^|;\s*)e2e=([^;]*)/.exec(req.headers.cookie ?? '')?.[1] ?? '')
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
        resolve: { alias: { '@mtcute/web': fileURLToPath(new URL('./fake/mtcute.ts', import.meta.url)) } },
        server: { port: Number(process.env.E2E_PORT) || 8097, strictPort: true, host: '127.0.0.1' },
    }),
)
