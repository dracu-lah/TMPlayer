// The letter a chat's or an account's picture shows while there is none: the first letter or
// digit of the name with the marks written on it, upper case. Punctuation, symbols and emoji in
// front are skipped ("(YEDHU)" reads "Y"); a name with no letter or digit gives '' and the
// caller keeps its plain picture. The same rule as core's NameInitial.kt; keep the two alike.
const FIRST = /[\p{L}\p{N}]\p{M}*/u

export function initialOf(name: string | null | undefined): string {
    const m = FIRST.exec(name ?? '')
    return m ? m[0].toUpperCase() : ''
}
