/* Caches TMPlayer's own GitHub release assets at Cloudflare's edge, so a slow route to GitHub's
   asset CDN doesn't make every download slow. The first request for a given file still reaches
   github.com, so GitHub's own download_count sees it as usual. Every request after is served
   from Cloudflare's cache instead, which GitHub never sees, so this worker counts those itself in
   a Durable Object (plus a frozen KV base in DOWNLOADS) and answers /stats with the running total. site/src/lib/stats.ts
   adds that total to GitHub's own count, so the home page's "downloads" figure covers both: see
   site/AGENTS.md "Home page pill and stats".

   Only ever proxies TMPlayer's own release assets: the path has to match PATH below, which pins
   the owner/repo and the file naming scripts/write-latest-json.py already uses. Anything else,
   or a method other than GET/HEAD, is refused before a subrequest is made. */

interface Env {
  DOWNLOADS: KVNamespace
  COUNTER: DurableObjectNamespace
}

// One object holds the running count of cached downloads. A Durable Object serialises its
// requests, so unlike the old KV read-then-write no two hits can overwrite each other, and a write
// here is not capped like the free KV tier's 1,000 puts a day (which stopped this counter on
// 2026-11-02). The KV key 'hits' is frozen: it holds the total up to that day, and /stats adds the
// object's count on top.
export class HitCounter {
  constructor(private state: DurableObjectState) {}
  async fetch(request: Request): Promise<Response> {
    const n = (await this.state.storage.get<number>('n')) ?? 0
    if (new URL(request.url).pathname === '/hit') {
      await this.state.storage.put('n', n + 1)
      return new Response(String(n + 1))
    }
    return new Response(String(n))
  }
}

function counter(env: Env): DurableObjectStub {
  return env.COUNTER.get(env.COUNTER.idFromName('hits'))
}

const REPO = 'dracu-lah/TMPlayer'
const PATH =
  /^\/download\/(v\d+\.\d+\.\d+(?:-beta\.\d+)?)\/(TMPlayer-[A-Za-z0-9._-]+\.(?:apk|msi|dmg|AppImage|tar\.xz|ipk|wgt))$/
const COUNTER_KEY = 'hits'
// Part of the cache key of the subrequest to github.com. Bump it to abandon everything cached so
// far (a cached bad answer cannot be purged from here). 2 on 2026-11-08: the 1.28.4 AppImage had
// an error answer cached for a year by the old cacheTtl, which applied to every status.
const CACHE_GEN = '2'

async function bumpCounter(env: Env): Promise<void> {
  await counter(env).fetch('https://counter/hit')
}

async function stats(env: Env): Promise<Response> {
  const base = Number(await env.DOWNLOADS.get(COUNTER_KEY)) || 0
  const live = Number(await (await counter(env).fetch('https://counter/get')).text()) || 0
  return new Response(JSON.stringify({ hits: base + live }), {
    headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-store' },
  })
}

// The home page's downloads figure, kept fresh without a site rebuild: GitHub's count over every
// release's app files (the same files as APP_FILE in site/src/lib/stats.ts) plus this worker's own
// hits. The page draws the build's figure first and raises it to this one when it arrives (never
// lowers it). GitHub is asked at most once per TOTAL_TTL per edge location, so the 60 unauthenticated
// calls an hour are not used up; when GitHub fails, the answer is a 503 and the page keeps what it has.
const APP_FILE = /^tmplayer(?!.*-source\.)(?:[-_.].*)?\.(apk|msi|dmg|appimage|deb|rpm|flatpak|zip|tar\.xz|tar\.gz)$/i
const TOTAL_TTL = 900

async function githubDownloads(): Promise<number | null> {
  try {
    const res = await fetch(`https://api.github.com/repos/${REPO}/releases?per_page=100`, {
      headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'tmplayer-download-mirror' },
      signal: AbortSignal.timeout(8000),
    })
    if (!res.ok) return null
    const list = (await res.json()) as { draft?: boolean; assets?: { name?: string; download_count?: number }[] }[]
    if (!Array.isArray(list)) return null
    let total = 0
    for (const r of list) {
      if (r.draft) continue
      for (const a of r.assets ?? []) {
        if (typeof a.name === 'string' && APP_FILE.test(a.name)) total += Number(a.download_count) || 0
      }
    }
    return total
  } catch {
    return null
  }
}

async function total(env: Env, request: Request, ctx: ExecutionContext): Promise<Response> {
  const cache = caches.default
  const key = new Request(new URL('/total', request.url).toString())
  const cached = await cache.match(key)
  if (cached) return cached
  const github = await githubDownloads()
  const headers = { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
  if (github === null) {
    return new Response('{}', { status: 503, headers: { ...headers, 'Cache-Control': 'no-store' } })
  }
  const hits = (await (await stats(env)).json()) as { hits: number }
  const res = new Response(JSON.stringify({ downloads: github + hits.hits }), {
    headers: { ...headers, 'Cache-Control': `public, max-age=${TOTAL_TTL}` },
  })
  ctx.waitUntil(cache.put(key, res.clone()))
  return res
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url)

    if (request.method === 'GET' && url.pathname === '/total') {
      return total(env, request, ctx)
    }

    if (request.method === 'GET' && url.pathname === '/stats') {
      return stats(env)
    }
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return new Response('Method not allowed', { status: 405, headers: { Allow: 'GET, HEAD' } })
    }

    const match = PATH.exec(url.pathname)
    if (!match) {
      return new Response('Not found', { status: 404 })
    }
    const [, tag, name] = match
    const origin = `https://github.com/${REPO}/releases/download/${tag}/${name}?gen=${CACHE_GEN}`

    // Range and If-Range carry through so a resumed download still works once the file is cached.
    const headers = new Headers()
    const range = request.headers.get('Range')
    if (range) headers.set('Range', range)
    const ifRange = request.headers.get('If-Range')
    if (ifRange) headers.set('If-Range', ifRange)

    // Only a good answer is cached, and for a year: release files are named with their version
    // (TMPlayer-1.28.3-universal.apk), so a given URL never changes under us. A plain cacheTtl
    // caches every status, so one failed first fetch (a GitHub hiccup, a rate limit) became that
    // file's answer for a year. -1 means do not store. cacheEverything also applies to a
    // cross-origin subrequest like this one, and the Response it returns carries Cloudflare's own
    // cf-cache-status, which is how bumpCounter below knows this request never reached github.com.
    const fetchOrigin = () =>
      fetch(origin, {
        method: request.method,
        headers,
        redirect: 'follow',
        cf: { cacheEverything: true, cacheTtlByStatus: { '200-299': 31536000, '300-599': -1 } },
      })

    // One retry for a passing GitHub error (5xx or 429) before the visitor sees a failure.
    let upstream = await fetchOrigin()
    if (upstream.status >= 500 || upstream.status === 429) {
      await upstream.body?.cancel()
      upstream = await fetchOrigin()
    }

    if (upstream.status >= 400) {
      console.log(`mirror: upstream ${upstream.status} for ${tag}/${name}`)
      return new Response('Upstream error', {
        status: upstream.status === 404 ? 404 : 502,
        headers: { 'Cache-Control': 'no-store', 'X-Upstream-Status': String(upstream.status) },
      })
    }

    // A resumed or split download sends several ranged GETs for one file; only the request that
    // starts at the first byte (or has no Range at all) is one download, so the rest are not counted.
    const startsAtZero = !range || /^bytes=0-/.test(range.trim())
    if (request.method === 'GET' && startsAtZero && upstream.headers.get('cf-cache-status') === 'HIT') {
      ctx.waitUntil(bumpCounter(env))
    }

    const out = new Headers(upstream.headers)
    out.set('Cache-Control', 'public, max-age=31536000, immutable')
    out.set('Access-Control-Allow-Origin', '*')
    out.delete('Set-Cookie')

    return new Response(upstream.body, { status: upstream.status, headers: out })
  },
}
