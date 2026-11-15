// Draws decoded pictures (Y, U and V planes, libavplanes.ts) on a canvas for the canvas engine
// (libavcanvas.ts). WebGL when the browser has it (three one-channel textures, the colour
// conversion on the graphics chip: no JS pass over the pixels, which is what a TV can afford); a
// 2D canvas with the conversion in JS when it does not. Written for Chromium 68 (the oldest TV).
import type { Planes } from './libavplanes'

export interface Drawing {
    readonly kind: 'webgl' | 'canvas2d'
    /** Draws the picture, stretched to `displayWidth` pixels across when the pixels are not square. */
    draw(p: Planes, displayWidth: number): void
    /** Draws the last picture again (after the canvas was cleared or the context came back). */
    redraw(): void
    destroy(): void
}

const VERTEX = 'attribute vec2 p;varying vec2 t;void main(){t=vec2(p.x*0.5+0.5,0.5-p.y*0.5);gl_Position=vec4(p,0.0,1.0);}'
// BT.601 below 720 lines, BT.709 from there; studio or full range.
const FRAGMENT =
    'precision mediump float;varying vec2 t;uniform sampler2D y,u,v;uniform vec2 k;uniform float hd;' +
    'void main(){float Y=texture2D(y,t).r;float U=texture2D(u,t).r-0.5;float V=texture2D(v,t).r-0.5;' +
    'Y=(Y-k.x)*k.y;vec3 c=hd>0.5?vec3(Y+1.5748*V,Y-0.187324*U-0.468124*V,Y+1.8556*U):vec3(Y+1.402*V,Y-0.344136*U-0.714136*V,Y+1.772*U);' +
    'gl_FragColor=vec4(clamp(c,0.0,1.0),1.0);}'

/** WebGL drawing, or null when this browser has none (or it refuses the program). */
function glDrawing(canvas: HTMLCanvasElement): Drawing | null {
    let gl: WebGLRenderingContext | null = null
    try {
        gl = (canvas.getContext('webgl', { alpha: false, antialias: false, depth: false, stencil: false, preserveDrawingBuffer: true }) ||
            canvas.getContext('experimental-webgl', { alpha: false, preserveDrawingBuffer: true })) as WebGLRenderingContext | null
    } catch {
        gl = null
    }
    if (!gl) return null
    const g = gl
    let program: WebGLProgram | null = null
    let textures: WebGLTexture[] = []
    let sized = ''
    let last: Planes | null = null
    let lastWidth = 0
    let gone = false

    const shader = (type: number, src: string) => {
        const s = g.createShader(type)
        if (!s) return null
        g.shaderSource(s, src)
        g.compileShader(s)
        return g.getShaderParameter(s, g.COMPILE_STATUS) ? s : null
    }
    const init = (): boolean => {
        const vs = shader(g.VERTEX_SHADER, VERTEX)
        const fs = shader(g.FRAGMENT_SHADER, FRAGMENT)
        const pr = g.createProgram()
        if (!vs || !fs || !pr) return false
        g.attachShader(pr, vs)
        g.attachShader(pr, fs)
        g.linkProgram(pr)
        if (!g.getProgramParameter(pr, g.LINK_STATUS)) return false
        g.useProgram(pr)
        program = pr
        const buf = g.createBuffer()
        g.bindBuffer(g.ARRAY_BUFFER, buf)
        g.bufferData(g.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), g.STATIC_DRAW)
        const at = g.getAttribLocation(pr, 'p')
        g.enableVertexAttribArray(at)
        g.vertexAttribPointer(at, 2, g.FLOAT, false, 0, 0)
        textures = []
        ;['y', 'u', 'v'].forEach((name, i) => {
            const tex = g.createTexture()!
            g.activeTexture(g.TEXTURE0 + i)
            g.bindTexture(g.TEXTURE_2D, tex)
            g.texParameteri(g.TEXTURE_2D, g.TEXTURE_WRAP_S, g.CLAMP_TO_EDGE)
            g.texParameteri(g.TEXTURE_2D, g.TEXTURE_WRAP_T, g.CLAMP_TO_EDGE)
            g.texParameteri(g.TEXTURE_2D, g.TEXTURE_MIN_FILTER, g.LINEAR)
            g.texParameteri(g.TEXTURE_2D, g.TEXTURE_MAG_FILTER, g.LINEAR)
            g.uniform1i(g.getUniformLocation(pr, name), i)
            textures.push(tex)
        })
        g.pixelStorei(g.UNPACK_ALIGNMENT, 1)
        sized = ''
        return true
    }
    if (!init()) return null

    const upload = (i: number, w: number, h: number, data: Uint8Array, fresh: boolean) => {
        g.activeTexture(g.TEXTURE0 + i)
        g.bindTexture(g.TEXTURE_2D, textures[i])
        if (fresh) g.texImage2D(g.TEXTURE_2D, 0, g.LUMINANCE, w, h, 0, g.LUMINANCE, g.UNSIGNED_BYTE, data)
        else g.texSubImage2D(g.TEXTURE_2D, 0, 0, 0, w, h, g.LUMINANCE, g.UNSIGNED_BYTE, data)
    }

    const onLost = (e: Event) => {
        e.preventDefault()
        gone = true
    }
    const onRestored = () => {
        gone = false
        if (init() && last) paint(last, lastWidth)
    }
    canvas.addEventListener('webglcontextlost', onLost)
    canvas.addEventListener('webglcontextrestored', onRestored)

    const paint = (p: Planes, displayWidth: number) => {
        if (gone || !program) return
        const w = Math.max(2, Math.min(2048, Math.round(displayWidth) || p.width))
        if (canvas.width !== w || canvas.height !== p.height) {
            canvas.width = w
            canvas.height = p.height
        }
        g.viewport(0, 0, canvas.width, canvas.height)
        const key = `${p.width}x${p.height}`
        const fresh = key !== sized
        sized = key
        upload(0, p.width, p.height, p.y, fresh)
        upload(1, p.cw, p.ch, p.u, fresh)
        upload(2, p.cw, p.ch, p.v, fresh)
        // Studio range: 16 to 235 stretched to 0 to 1; full range as it is.
        g.uniform2f(g.getUniformLocation(program, 'k'), p.full ? 0 : 16 / 255, p.full ? 1 : 255 / 219)
        g.uniform1f(g.getUniformLocation(program, 'hd'), p.height >= 720 ? 1 : 0)
        g.drawArrays(g.TRIANGLE_STRIP, 0, 4)
    }

    return {
        kind: 'webgl',
        draw(p, displayWidth) {
            last = p
            lastWidth = displayWidth
            paint(p, displayWidth)
        },
        redraw() {
            if (last) paint(last, lastWidth)
        },
        destroy() {
            canvas.removeEventListener('webglcontextlost', onLost)
            canvas.removeEventListener('webglcontextrestored', onRestored)
            // Frees the graphics memory now, not at the next garbage collection.
            try {
                g.getExtension('WEBGL_lose_context')?.loseContext()
            } catch {
                /* already gone */
            }
            last = null
        },
    }
}

/** 2D drawing: the picture turned to RGB in JS (integer BT.601/709), for a browser with no WebGL. */
function plainDrawing(canvas: HTMLCanvasElement): Drawing | null {
    const ctx = canvas.getContext('2d', { alpha: false })
    if (!ctx) return null
    let image: ImageData | null = null
    let last: Planes | null = null
    let lastWidth = 0

    // The picture is made at its own size on a scratch canvas, then stretched to the shown width.
    const scratch = document.createElement('canvas')
    const sctx = scratch.getContext('2d', { alpha: false })
    if (!sctx) return null
    const paint = (p: Planes, displayWidth: number) => {
        const w = Math.max(2, Math.min(2048, Math.round(displayWidth) || p.width))
        if (scratch.width !== p.width || scratch.height !== p.height) {
            scratch.width = p.width
            scratch.height = p.height
            image = null
        }
        if (canvas.width !== w || canvas.height !== p.height) {
            canvas.width = w
            canvas.height = p.height
        }
        const img = (image ??= sctx.createImageData(p.width, p.height))
        const d = img.data
        const hd = p.height >= 720
        const [rv, gu, gv, bu] = hd ? [1.5748, 0.187324, 0.468124, 1.8556] : [1.402, 0.344136, 0.714136, 1.772]
        const off = p.full ? 0 : 16
        const mul = p.full ? 1 : 255 / 219
        for (let y = 0; y < p.height; y++) {
            const cy = (y >> 1) * p.cw
            let o = y * p.width * 4
            for (let x = 0; x < p.width; x++, o += 4) {
                const Y = (p.y[y * p.width + x] - off) * mul
                const U = p.u[cy + (x >> 1)] - 128
                const V = p.v[cy + (x >> 1)] - 128
                const r = Y + rv * V
                const g = Y - gu * U - gv * V
                const b = Y + bu * U
                d[o] = r < 0 ? 0 : r > 255 ? 255 : r
                d[o + 1] = g < 0 ? 0 : g > 255 ? 255 : g
                d[o + 2] = b < 0 ? 0 : b > 255 ? 255 : b
                d[o + 3] = 255
            }
        }
        sctx.putImageData(img, 0, 0)
        ctx.drawImage(scratch, 0, 0, canvas.width, canvas.height)
    }

    return {
        kind: 'canvas2d',
        draw(p, displayWidth) {
            last = p
            lastWidth = displayWidth
            paint(p, displayWidth)
        },
        redraw() {
            if (last) paint(last, lastWidth)
        },
        destroy() {
            last = null
            image = null
        },
    }
}

/**
 * The best drawing this browser has, on a canvas from `make`. A canvas gets one kind of context for
 * good, so when WebGL fails after it was asked for (a program the chip refuses) the 2D drawing
 * takes another canvas. `prefer2d` is for the tests of that fallback.
 */
export function startDrawing(make: () => HTMLCanvasElement, prefer2d = false): { drawing: Drawing; canvas: HTMLCanvasElement } | null {
    if (!prefer2d) {
        const canvas = make()
        const gl = glDrawing(canvas)
        if (gl) return { drawing: gl, canvas }
    }
    const canvas = make()
    const plain = plainDrawing(canvas)
    return plain ? { drawing: plain, canvas } : null
}
