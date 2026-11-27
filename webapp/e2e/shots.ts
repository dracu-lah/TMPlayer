// Where a spec writes the pictures a person looks at. A normal run writes them under the OS temp
// directory, so the tracked pictures in design/ stay untouched. WRITE_SHOTS=1 (or the older
// E2E_SHOTS, WB_SHOTS or SHOTS switches) refreshes the tracked ones.
import { mkdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const DESIGN = fileURLToPath(new URL('../../design/', import.meta.url))

/** True when the run should refresh the tracked pictures in design/. */
export function writeShots(): boolean {
    const e = process.env
    return e.WRITE_SHOTS === '1' || !!e.E2E_SHOTS || !!e.WB_SHOTS || !!e.SHOTS
}

/** The folder for `relFromDesign` (for example `web-gaps/WB8`), created, without a trailing slash. */
export function shotsDir(relFromDesign: string): string {
    const dir = writeShots() ? join(DESIGN, relFromDesign) : join(tmpdir(), 'tmplayer-shots', relFromDesign)
    mkdirSync(dir, { recursive: true })
    return dir
}

/** Same folder as a directory URL (trailing slash), for `new URL(name, dir)`. */
export function shotsUrl(relFromDesign: string): URL {
    return pathToFileURL(shotsDir(relFromDesign) + '/')
}
