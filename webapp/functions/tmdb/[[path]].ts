// /tmdb/*: the poster proxy (webapp/tmdb/handler.ts) as a Cloudflare Pages Function. It deploys with
// the site (`wrangler pages deploy`, run from webapp/ so this folder is found) and reads its key
// from the Pages project's secret TMDB_API_KEY. Without the secret every lookup answers 503 and
// the page keeps its thumbnails.
import { createHandler, type Deps, type Env } from '../../tmdb/handler.ts'

interface Context {
    request: Request
    env: Env
    waitUntil(work: Promise<unknown>): void
}

// One handler per isolate: its pause after a throttle and its note of a refused key live here.
let handle: ((request: Request, env: Env) => Promise<Response>) | null = null
let later: (work: Promise<unknown>) => void = () => {}

export const onRequest = (context: Context): Promise<Response> => {
    later = (work) => context.waitUntil(work)
    handle ??= createHandler({
        fetch: (request) => fetch(request),
        cache: (caches as unknown as { default: NonNullable<Deps['cache']> }).default,
        later: (work) => later(work),
    })
    return handle(context.request, context.env)
}
