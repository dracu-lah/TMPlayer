// The HEVC codec string a browser's Media Source takes for a file. Smart TVs (LG webOS 6 on
// Chromium 79, for one) decode HEVC in hardware but turn down some exact strings, such as the
// file's own level or `hvc1` where they know `hev1`. The decoder reads the stream's own header
// (hvcC), not the string, so a string of the same profile the browser takes stands in for it.

/** Main and Main 10 at levels 5.0, 5.1 and 3.1 (L150, L153, L93), as players and TVs name them. */
const STAND_INS: Record<string, string[]> = {
    '1': ['1.6.L150.90', '1.6.L153.B0', '1.6.L150.B0', '1.6.L93.B0'],
    '2': ['2.4.L150.90', '2.4.L153.B0', '2.4.L150.B0', '2.4.L93.B0'],
}

/**
 * The names to try for `codec` (the file's own, `hvc1.` or `hev1.`), in order: its own as `hvc1`
 * (Safari takes only that) and as `hev1`, then stand-ins of the same profile. Empty when `codec`
 * is not HEVC. Range extensions and the rest have no stand-ins: only their own name is asked.
 */
export function hevcNames(codec: string): string[] {
    const m = /^(?:hev1|hvc1)\.(.+)$/.exec(codec)
    if (!m) return []
    const rest = m[1]
    const profile = /^[A-C]?(\d+)\./.exec(rest)?.[1] ?? ''
    const names = [`hvc1.${rest}`, `hev1.${rest}`]
    for (const s of STAND_INS[profile] ?? []) names.push(`hvc1.${s}`, `hev1.${s}`)
    return [...new Set(names)]
}

/** The first of `hevcNames(codec)` that `supported` takes in an MP4, or null. */
export function hevcAccepted(codec: string, supported: (mime: string) => boolean): string | null {
    for (const name of hevcNames(codec)) {
        try {
            if (supported(`video/mp4; codecs="${name}"`)) return name
        } catch {
            // An old engine that throws on a string it cannot read: the next one.
        }
    }
    return null
}

/**
 * LG webOS and Samsung Tizen TVs, whose browsers can say no to every HEVC name while the TV decodes
 * HEVC in hardware (Jellyfin's web player takes HEVC on both without asking, browserDeviceProfile.js).
 * There the Media Source is tried anyway, and a refusal goes on to the next engine as before.
 */
export function tvDecodesHevc(userAgent: string): boolean {
    return /Web0S|webOS|Tizen/.test(userAgent)
}

/** `mime` with its HEVC codec named `name` instead, the sound's codec kept. */
export function withHevc(mime: string, name: string): string {
    return mime.replace(/(hev1|hvc1)\.[^,"\s]+/, name)
}
