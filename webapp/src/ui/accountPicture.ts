// What the account's picture on the bar draws (WP-103), the apps' AccountPicture chain on the web:
// the profile's saved photo, else the first letter of the name on its colour, and the person glyph
// only when no name is known at all, never an empty disc. Pure, so `node --test` holds it.
import { initialOf } from './initial.ts'

export type AccountPicture = { kind: 'photo'; src: string } | { kind: 'initial'; letter: string } | { kind: 'glyph' }

/** Who is signed in: Telegram's name once the session has it, else the profile's own, else ''. */
export function accountName(telegram: string | null | undefined, profile: string | null | undefined): string {
    return telegram?.trim() || profile?.trim() || ''
}

/** The picture for [photo] (a cleaned data URL, or null) and [name]. */
export function accountPicture(photo: string | null | undefined, name: string | null | undefined): AccountPicture {
    if (photo) return { kind: 'photo', src: photo }
    const letter = initialOf(name)
    return letter ? { kind: 'initial', letter } : { kind: 'glyph' }
}

/** The first word of [name], the bar's short caption; '' for no name. */
export function firstName(name: string): string {
    return name.trim().split(/[ \t\n]+/)[0] ?? ''
}
