// The licence notices served beside the app as /licenses.txt (Settings links to it, and the service
// worker keeps it for reading offline). rollup-plugin-license lists every package that ends up in a
// built file, from the page's build and the workers' builds alike; the parts that come from no npm
// package (TMPlayer's own code, the Kotlin standard library in the rules, FFmpeg inside the sound
// decoders) are written here by hand.
import { readFileSync } from 'node:fs'
import type { Plugin } from 'vite'
import license from 'rollup-plugin-license'

interface Dependency {
    name: string | null
    version: string | null
    license: string | null
    licenseText: string | null
    noticeText: string | null
    homepage: string | null
    author: { name?: string; email?: string; url?: string; text?(): string } | null
    repository: { url?: string } | string | null
}

const text = (name: string) => readFileSync(new URL(`./licenses/${name}`, import.meta.url), 'utf8').replace(/^\n+/, '').trimEnd()

/** libavcodec's version as the AAC encoder's build states it, so the FFmpeg source can be matched. */
function libavcodecVersion(): string | null {
    try {
        const source = readFileSync(new URL('./node_modules/@mediabunny/aac-encoder/dist/bundles/mediabunny-aac-encoder.mjs', import.meta.url), 'utf8')
        return /Lavc(\d+\.\d+\.\d+)/.exec(source)?.[1] ?? null
    } catch {
        return null
    }
}

function own(): string {
    return [
        'TMPlayer for the web',
        '',
        'A player for the videos already in your own Telegram chats. Unofficial, not affiliated with Telegram.',
        'Free software under the GNU General Public License, version 3 or later, with an additional',
        'permission for OpenSSL. Source and licence: https://github.com/dracu-lah/TMPlayer',
        '',
        'It contains the components below. Their copyrights stay with their authors, and their licence',
        'terms continue to apply.',
    ].join('\n')
}

function ffmpeg(): string {
    const lavc = libavcodecVersion()
    return [
        'FFmpeg (libavcodec, libavutil)',
        'Licence: GNU Lesser General Public License, version 2.1 or later (the text is at the end of this file)',
        '',
        "The AC-3, E-AC-3 and DTS decoders and the AAC encoder are FFmpeg's, compiled to WebAssembly by",
        `Mediabunny${lavc ? ` (libavcodec ${lavc})` : ''}, configured with --disable-everything and only those coders, no GPL parts.`,
        'They are the separate files assets/mediabunny-ac3-*.js, assets/mediabunny-dts-*.js and',
        'assets/mediabunny-aac-encoder-*.js, loaded only when a video needs them, so they can be replaced',
        'with your own build.',
        "FFmpeg's source: https://ffmpeg.org/download.html, https://github.com/FFmpeg/FFmpeg",
        'How they are built, with the code that joins them to FFmpeg:',
        'https://github.com/Vanilagy/mediabunny/tree/main/packages (ac3, dts, aac-encoder)',
        '',
        "AVI files are read and their pictures and sound decoded by TMPlayer's own build of FFmpeg 9.0",
        '(libavformat, libavcodec, libavutil) made with libav.js 6.10.9.0 (Yahweasel, 0BSD-style wrapper)',
        'and emfiberthreads 1.3 (0BSD), no GPL parts: the separate files libav/libav-6.10.9.0-tmplayer.*,',
        'loaded only when an AVI file plays, so they can be replaced with your own build of the same names.',
        'The exact sources (FFmpeg, libav.js, emfiberthreads) and the build script are served beside them:',
        '/libav/sources/ (README.txt there), and are in TMPlayer\'s repository under webapp/libav/.',
        '',
        "Videos the browser plays neither as they are nor repacked are played by movi-player (Apache 2.0,",
        'listed above), whose WebAssembly part is a build of FFmpeg (libavformat, libavcodec 63.1.102,',
        'libavutil, libswresample, libswscale) with dav1d (BSD 2-Clause), no GPL parts. It comes inside',
        "movi-player's own file (assets/player-*.js), loaded only when such a video plays.",
        'How it is built, with the code that joins it to FFmpeg: https://github.com/mrujjwalg/movi-player',
        '(wasm/ and scripts/), dav1d: https://code.videolan.org/videolan/dav1d',
    ].join('\n')
}

function kotlin(): string {
    return [
        'Kotlin standard library',
        'Licence: Apache License 2.0 (the text is at the end of this file)',
        "Inside TMPlayer's own rules, compiled from TMPlayer's core into the Telegram worker (assets/worker-*.js).",
        'Source: https://github.com/JetBrains/kotlin',
    ].join('\n')
}

/** The Noto fonts subtitles fall back to (src/player/subfonts.css), with their licence. */
function fonts(): string {
    return [
        'Noto Sans fonts for subtitles',
        'Licence: SIL Open Font License 1.1',
        'In assets/subfont-*.woff2, fetched only for a subtitle line in a script the system has no font for.',
        'Source: https://github.com/google/fonts (ofl/notosans*), cut by ui/fonts/subtitle-fonts.py',
        '',
        text('OFL-Noto.txt'),
    ].join('\n')
}

/** core-js, in the TV build's separately built polyfill file (vite.config.ts), which the collectors never see. */
export function coreJs(): string {
    const read = (f: string) => readFileSync(new URL(`./node_modules/core-js/${f}`, import.meta.url), 'utf8')
    const { version } = JSON.parse(read('package.json')) as { version: string }
    return [
        `core-js ${version}`,
        'Licence: MIT',
        "In the TV build's assets/polyfills-*.js, which fills in what older TV browsers lack.",
        'Source: https://github.com/zloirock/core-js',
        '',
        read('LICENSE').trim(),
    ].join('\n')
}

function entry(dep: Dependency): string {
    const repo = typeof dep.repository === 'string' ? dep.repository : dep.repository?.url
    const author = dep.author?.text?.() ?? dep.author?.name
    const lines = [`${dep.name} ${dep.version}`, `Licence: ${dep.license ?? 'not stated'}`]
    if (author) lines.push(`Author: ${author}`)
    const source = (repo ?? dep.homepage ?? '').replace(/^git\+/, '').replace(/\.git$/, '')
    if (source) lines.push(`Source: ${source}`)
    if (dep.licenseText) lines.push('', dep.licenseText.trim())
    if (dep.noticeText) lines.push('', dep.noticeText.trim())
    return lines.join('\n')
}

/**
 * The plugins that write /licenses.txt: one collector for each build (the page's and every
 * worker's), and the writer in the page's build, which runs last.
 */
export function notices(extra: () => string[] = () => []): { page: Plugin[]; worker: () => Plugin[] } {
    const found = new Map<string, Dependency>()
    const collect = (deps: Dependency[]) => {
        for (const dep of deps) if (dep.name) found.set(`${dep.name}@${dep.version}`, dep)
    }
    const collector = () => license({ thirdParty: { includePrivate: false, output: collect as never } }) as unknown as Plugin
    const writer: Plugin = {
        name: 'tmplayer-notices',
        apply: 'build',
        generateBundle() {
            if (found.size === 0) this.error('rollup-plugin-license found no packages: the notices would be empty')
            const rule = '-'.repeat(80)
            const deps = [...found.values()].sort((a, b) => a.name!.localeCompare(b.name!))
            const body = [own(), ...deps.map(entry), ...extra(), ffmpeg(), kotlin(), fonts(), text('LGPL-2.1.txt'), text('Apache-2.0.txt')]
            this.emitFile({ type: 'asset', fileName: 'licenses.txt', source: `${body.join(`\n\n${rule}\n\n`)}\n` })
        },
    }
    return { page: [collector(), writer], worker: () => [collector()] }
}
