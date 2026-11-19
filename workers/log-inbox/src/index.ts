/* TMPlayer's log inbox: the receiving end of "Send to developer". Not deployed by anyone but the
   maintainer: see README.md. All the rules are in inbox.ts. */

import { FEEDBACK_PATH, preflight, withCors } from './feedback.ts'
import { handle, sweep, type Env } from './inbox.ts'

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    // Feedback is also posted by the web app (web.tmplayer.org), whose browser must be allowed to read the answer.
    const feedback = new URL(request.url).pathname === FEEDBACK_PATH
    if (feedback && request.method === 'OPTIONS') return preflight(request.headers.get('Origin'))
    try {
      const answer = await handle(request, env)
      return feedback ? withCors(request, answer) : answer
    } catch {
      // Nothing about the request is logged: a failure is only a failure.
      const failed = new Response(JSON.stringify({ error: 'unavailable' }), {
        status: 503,
        headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' },
      })
      return feedback ? withCors(request, failed) : failed
    }
  },

  // Daily, see wrangler.jsonc: the 30 day retention, in code as well as in the bucket's lifecycle rule.
  async scheduled(_event: unknown, env: Env): Promise<void> {
    await sweep(env.REPORTS, Date.now())
  },
}
