// Lowers libav.js's classic scripts to what Chromium 68 parses (the TVs: webOS 5 and 6 run Chromium
// 68 and 79, Tizen 2020 runs 69). Emscripten writes optional chaining and `??` (Chromium 80); the
// player loads these classic files everywhere (libavload.ts), so the lowered copy is the one served.
// Used by build.sh: node lower.mjs <file>...
import { readFileSync, writeFileSync } from 'node:fs'
import { transformWithOxc } from 'vite'

for (const file of process.argv.slice(2)) {
    const code = readFileSync(file, 'utf8')
    // The licence header stays as it is: the LGPL notice must come with the file.
    const head = code.match(/^\/\*[\s\S]*?\*\/\n?/)?.[0] ?? ''
    const out = await transformWithOxc(code.slice(head.length), file.replace(/\.js$/, '.cjs'), { target: 'chrome68', lang: 'js' })
    // Class fields become defineProperty calls: the helper is put inline (the file is no module).
    const helper = 'var _defineProperty = require("@oxc-project/runtime/helpers/defineProperty");'
    out.code = out.code.replace(helper, 'var _defineProperty = function (o, k, v) { Object.defineProperty(o, k, { value: v, enumerable: true, configurable: true, writable: true }); return o };')
    if (/require\("@oxc-project/.test(out.code)) throw new Error(`${file}: needs a helper that is not inlined`)
    if (/\?\.[\w$([]|\?\?/.test(out.code.replace(/(["'`])(?:\\.|(?!\1).)*\1/g, ''))) throw new Error(`${file}: still has optional chaining or ??`)
    // globalThis came in Chromium 71; `self` is the global in a page and in a worker alike.
    const global = 'if (typeof globalThis === "undefined") self.globalThis = self;\n'
    writeFileSync(file, head + (out.code.startsWith(global) ? '' : global) + out.code)
    console.log(`lowered ${file}`)
}
