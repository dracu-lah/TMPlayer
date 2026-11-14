/* TMPlayer's log inbox: the receiving end of "Send to developer". Not deployed by anyone but the
   maintainer: see README.md. All the rules are in inbox.ts. */

import { handle, sweep, type Env } from './inbox.ts'

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      return await handle(request, env)
    } catch {
      // Nothing about the request is logged: a failure is only a failure.
      return new Response(JSON.stringify({ error: 'unavailable' }), {
        status: 503,
        headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' },
      })
    }
  },

  // Daily, see wrangler.jsonc: the 30 day retention, in code as well as in the bucket's lifecycle rule.
  async scheduled(_event: unknown, env: Env): Promise<void> {
    await sweep(env.REPORTS, Date.now())
  },
}
