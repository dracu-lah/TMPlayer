/* Caches TMPlayer's own GitHub release assets at Cloudflare's edge, so a slow route to GitHub's
   asset CDN doesn't make every download slow. The first request for a given file still reaches
   github.com, so GitHub's own download_count sees it as usual. Every request after is served
   from Cloudflare's cache instead, which GitHub never sees, so this worker counts those itself in
   HITS (Analytics Engine, plus a frozen KV base in DOWNLOADS) and answers /stats with the running total. site/src/lib/stats.ts
   adds that total to GitHub's own count, so the home page's "downloads" figure covers both: see
   site/AGENTS.md "Home page pill and stats".

   Only ever proxies TMPlayer's own release assets: the path has to match PATH below, which pins
   the owner/repo and the file naming scripts/write-latest-json.py already uses. Anything else,
   or a method other than GET/HEAD, is refused before a subrequest is made. */

interface Env {
  DOWNLOADS: KVNamespace
  HITS: AnalyticsEngineDataset
  // Secrets for reading HITS back through the SQL API (wrangler secret put). Without them /stats
  // answers with the KV base alone.
  CF_ACCOUNT_ID?: string
  CF_API_TOKEN?: string
}

const REPO = 'dracu-lah/TMPlayer'
const PATH =
  /^\/download\/(v\d+\.\d+\.\d+(?:-beta\.\d+)?)\/(TMPlayer-[A-Za-z0-9._-]+\.(?:apk|msi|dmg|AppImage|tar\.xz|ipk|wgt))$/
const COUNTER_KEY = 'hits'

// Each cached download is one Analytics Engine data point: a write there is not metered like a KV
// put (the free KV tier stopped this counter on 2026-11-02 at 1,000 puts a day). The KV key 'hits'
// is now frozen: it holds the total up to that day and /stats adds the Analytics Engine count on top.
function bumpCounter(env: Env): void {
  env.HITS.writeDataPoint({ indexes: ['hit'], doubles: [1] })
}

const STATS_TTL_SECONDS = 600

async function analyticsHits(env: Env): Promise<number> {
  if (!env.CF_ACCOUNT_ID || !env.CF_API_TOKEN) return 0
  try {
    const res = await fetch(`https://api.cloudflare.com/client/v4/accounts/${env.CF_ACCOUNT_ID}/analytics_engine/sql`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${env.CF_API_TOKEN}` },
      body: "SELECT sum(_sample_interval * double1) AS n FROM tmplayer_hits FORMAT JSON",
    })
    if (!res.ok) return 0
    const body = (await res.json()) as { data?: { n?: number | string }[] }
    return Number(body.data?.[0]?.n) || 0
  } catch {
    return 0
  }
}

async function stats(env: Env, ctx: ExecutionContext, url: URL): Promise<Response> {
  const headers = { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-store' }
  const cacheKey = new Request(`${url.origin}/stats-cache`)
  const cached = await caches.default.match(cacheKey)
  if (cached) return new Response(cached.body, { headers })
  const base = Number(await env.DOWNLOADS.get(COUNTER_KEY)) || 0
  const hits = base + (await analyticsHits(env))
  const body = JSON.stringify({ hits })
  ctx.waitUntil(caches.default.put(cacheKey, new Response(body, { headers: { 'Cache-Control': `max-age=${STATS_TTL_SECONDS}` } })))
  return new Response(body, { headers })
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url)

    if (request.method === 'GET' && url.pathname === '/stats') {
      return stats(env, ctx, url)
    }
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return new Response('Method not allowed', { status: 405, headers: { Allow: 'GET, HEAD' } })
    }

    const match = PATH.exec(url.pathname)
    if (!match) {
      return new Response('Not found', { status: 404 })
    }
    const [, tag, name] = match
    const origin = `https://github.com/${REPO}/releases/download/${tag}/${name}`

    // Range and If-Range carry through so a resumed download still works once the file is cached.
    const headers = new Headers()
    const range = request.headers.get('Range')
    if (range) headers.set('Range', range)
    const ifRange = request.headers.get('If-Range')
    if (ifRange) headers.set('If-Range', ifRange)

    const upstream = await fetch(origin, {
      method: request.method,
      headers,
      redirect: 'follow',
      // Tells Cloudflare's edge cache to keep this response a year: release files are named with
      // their version (TMPlayer-1.28.3-universal.apk), so a given URL never changes under us.
      // cacheEverything also applies to a cross-origin subrequest like this one, and the Response
      // it returns carries Cloudflare's own cf-cache-status, which is how bumpCounter below knows
      // this request never reached github.com.
      cf: { cacheEverything: true, cacheTtl: 31536000 },
    })

    if (upstream.status >= 400) {
      return new Response('Upstream error', { status: upstream.status === 404 ? 404 : 502 })
    }

    if (request.method === 'GET' && upstream.headers.get('cf-cache-status') === 'HIT') {
      bumpCounter(env)
    }

    const out = new Headers(upstream.headers)
    out.set('Cache-Control', 'public, max-age=31536000, immutable')
    out.set('Access-Control-Allow-Origin', '*')
    out.delete('Set-Cookie')

    return new Response(upstream.body, { status: upstream.status, headers: out })
  },
}
