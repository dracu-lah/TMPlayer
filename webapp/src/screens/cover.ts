// The apps' GeneratedCover colours (ui CoverArt.Pairs and pairIndex), so a card waiting for its
// poster, or one with none, wears the same dark gradient as on the desktop, chosen by the title
// alone: every episode of a show shares it, and so does the same video posted in two chats.

const PAIRS: [string, string][] = [
    ['#34397A', '#15173A'],
    ['#145A5A', '#082A2C'],
    ['#6A2A5E', '#2A1028'],
    ['#7A3524', '#30140D'],
    ['#245C34', '#0D2716'],
    ['#1F4A7E', '#0C1D38'],
    ['#4A3680', '#1C1438'],
    ['#6B5719', '#2B230A'],
]

/** Kotlin's String.hashCode, over UTF-16 units as the JVM counts them. */
function hashCode(s: string): number {
    let h = 0
    for (let i = 0; i < s.length; i++) h = (Math.imul(h, 31) + s.charCodeAt(i)) | 0
    return h
}

/** CoverArt.pairIndex: which pair `title` wears, the same number the apps pick. */
export function pairIndex(title: string): number {
    let h = hashCode(title.trim().toLowerCase().replace(/\s+/g, ' '))
    h ^= h >>> 16
    h = Math.imul(h, 0x45d9f3b)
    h ^= h >>> 16
    return ((h % PAIRS.length) + PAIRS.length) % PAIRS.length
}

/** The cover as a CSS background: the pair's diagonal with a soft light from the top start corner. */
export function coverBackground(title: string): string {
    const [start, end] = PAIRS[pairIndex(title)]
    return `radial-gradient(circle at 15% 10%, rgba(255, 255, 255, .10), transparent 85%), linear-gradient(135deg, ${start}, ${end})`
}
