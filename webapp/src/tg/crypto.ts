// A page served over plain http from another computer (a TV opening this machine's build on the
// LAN) is not a secure context, so it has no crypto.subtle. mtcute does everything else in its own
// wasm and needs subtle only for PBKDF2 (the two-step password) and HMAC, so those two fall back to
// @noble/hashes there, loaded only when asked for. Random bytes come from getRandomValues, which
// every context has.
import { WebCryptoProvider } from '@mtcute/web'

type Algo = 'sha1' | 'sha256' | 'sha512'

class PlainCryptoProvider extends WebCryptoProvider {
    constructor() {
        super({ crypto: { getRandomValues: (b) => crypto.getRandomValues(b), subtle: {} } as Crypto })
    }

    override async pbkdf2(password: Uint8Array, salt: Uint8Array, iterations: number, keylen?: number, algo?: Algo): Promise<Uint8Array> {
        const [{ pbkdf2Async }, hash] = await Promise.all([import('@noble/hashes/pbkdf2.js'), hashOf(algo ?? 'sha512')])
        return pbkdf2Async(hash, password, salt, { c: iterations, dkLen: keylen || 64 })
    }

    override async hmacSha256(data: Uint8Array, key: Uint8Array): Promise<Uint8Array> {
        const [{ hmac }, { sha256 }] = await Promise.all([import('@noble/hashes/hmac.js'), import('@noble/hashes/sha2.js')])
        return hmac(sha256, key, data)
    }
}

async function hashOf(algo: Algo) {
    if (algo === 'sha1') return (await import('@noble/hashes/legacy.js')).sha1
    const sha2 = await import('@noble/hashes/sha2.js')
    return algo === 'sha256' ? sha2.sha256 : sha2.sha512
}

/**
 * WebCrypto where the context has it, else the fallback above. @mtcute/web's client builds its own
 * WebCrypto provider before taking this one and that throws without crypto.subtle, so there a
 * subtle that refuses every call stands in (nothing reaches it: this provider replaces that one).
 */
export function cryptoProvider() {
    if (globalThis.crypto?.subtle) return new WebCryptoProvider()
    const refuse = () => Promise.reject(new DOMException('crypto.subtle needs a secure context', 'NotSupportedError'))
    Object.defineProperty(globalThis.crypto, 'subtle', { value: new Proxy({}, { get: () => refuse }), configurable: true })
    return new PlainCryptoProvider()
}
