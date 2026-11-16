# Regenerates webapp/src/player/skipTable.ts from the Kotlin chapter table (SkipChapters.kt).
# Usage: python3 scripts/gen-skip-table.py webapp/src/player/skipTable.ts
import re
import sys

src = open(__import__('os').path.join(__import__('os').path.dirname(__file__), '..', 'core/src/pureMain/kotlin/com/tmplayer/online/SkipChapters.kt'), encoding='utf8').read()
start = src.index('private val RULES')
body = src[start:]
blocks = re.findall(r'r\(\s*(?://[^\n]*\n\s*)*SkipKind\.(\w+),(.*?)\n        \)', body, flags=re.S)
assert len(blocks) == 6, len(blocks)
strings = lambda text: re.findall(r'"((?:[^"\\]|\\.)*)"', text)

neg_start = src.index('private val NOT_A_SKIP')
neg = strings(src[neg_start:src.index('.map { fold(it) }', neg_start)])

out = []
out.append('// GENERATED from core/src/pureMain/kotlin/com/tmplayer/online/SkipChapters.kt by scripts/gen-skip-table.py.')
out.append('// Edit the Kotlin table, then run the script; skipTable.test.ts fails when the two drift.')
out.append("export type SkipKindName = 'recap' | 'intro' | 'opening' | 'credits' | 'ending' | 'preview'")
out.append('')
out.append('/** In the order they are tried, most specific first. Phrases are written raw and folded when loaded. */')
out.append('export const SKIP_RULES: [SkipKindName, string[]][] = [')
for kind, text in blocks:
    ph = strings(text)
    out.append("    ['%s', [" % kind.lower())
    for p in ph:
        out.append("        '%s'," % p.replace("'", "\\'"))
    out.append('    ]],')
out.append(']')
out.append('')
out.append('/** Words that make a title a scene or a talk about the thing, not the thing. */')
out.append('export const NOT_A_SKIP: string[] = [' + ', '.join("'%s'" % p for p in neg) + ']')
out.append('')
open(sys.argv[1], 'w', encoding='utf8').write('\n'.join(out))
print(len(blocks), sum(len(strings(t)) for _, t in blocks), len(neg))
