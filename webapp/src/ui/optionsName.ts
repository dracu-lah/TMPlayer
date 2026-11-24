// The name the grid header's Options button says on a TV (WP-14): the video's, cut so a long file name
// does not take the header. Kept apart from CardMenu.tsx so it is tested without the page.

/** The longest name the Options button says: the rest is cut with an ellipsis. */
export const OPTIONS_NAME_MAX = 24

/** [name] as it is, or its first characters and an ellipsis, [OPTIONS_NAME_MAX] characters in all (by code point, so no emoji is cut in half). */
export function clipName(name: string): string {
    const chars = [...name]
    return chars.length <= OPTIONS_NAME_MAX ? name : chars.slice(0, OPTIONS_NAME_MAX - 1).join('').trimEnd() + '\u2026'
}
