#!/usr/bin/env python3
"""
Lists string literals in Kotlin sources that look like user visible English, so text that has not
gone through the i18n catalog (core/src/commonMain/resources/i18n/en.json) shows up.

    scripts/i18n-leftovers.py                 # desktop and core
    scripts/i18n-leftovers.py app/src/main    # any other source roots

It skips comments, log and exception lines, regexes, ids, paths and constants. A literal that is
meant to stay (a brand name, a format with no words) is kept quiet with an `// i18n-ok` comment on
its line, which also says why. Prints `file:line<TAB>text` and the count on stderr.
"""
import pathlib
import re
import sys

DEFAULT = ["desktop/src/main/kotlin", "core/src/commonMain/kotlin"]

# Whole files whose literals are not UI text, and why.
SKIP_FILES = {
    "Mpris.kt": "MPRIS D-Bus property names and values",
    "Smtc.kt": "WinRT interface signatures",
    "PlayerIcons.kt": "icon names and SVG path data",
    "SelfTest.kt": "the --self-test report, read by CI",
    "NativeInventory.kt": "native library diagnostics in the self-test report",
    "DevPlayerMain.kt": "the developer-only player harness",
    "Icu.kt": "ICU syntax errors for whoever edits a catalog",
    "Languages.kt": "each language named in itself, the same in every UI language",
    "MediaName.kt": "file name patterns",
    "LinuxKeepAwake.kt": "D-Bus names",
    "OpenExternal.kt": "log lines and Windows handler names",
}
LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')
SKIP_LINE = re.compile(
    r"^\s*(//|\*|/\*)|i18n-ok|Logger\.|\blog\(|\blog[A-Z]\w*\(|println|\bTAG\b|Regex\(|toRegex|"
    r"System\.(get|set)Property|getenv|@Suppress|@JvmName|ProcessBuilder|\"@type\"|JSONObject|"
    r"getString\(|Class\.forName|const val [A-Z_]+ = \"|testTag|error\(|check\(|require\(|"
    r"IllegalStateException|IllegalArgumentException|IOException\(|Exception\(\""
)


# Literal shapes that are never UI text, and why.
SKIP_TEXT = [
    (r"^TMPlayer$|^TMPlayer[-/ ]|^Telegram$|^FFmpeg$|^TDLib$", "the product's own name and its parts, never translated"),
    (r"^(User-Agent|Accept|SHA-\d+|IconUri|DisplayName|IOKit|Bypass|Hidden)$", "HTTP headers, digests, registry and OS names"),
    (r"[<>]|&\w+;|^\\?\$|\[Windows\.|\\\$|^' ->|Software\\\\", "PowerShell and toast XML sent to Windows"),
    (r"^(d MMM|d MMM yyyy|yyyy-MM-dd|HH:mm|S%02dE%02d)$", "date and episode patterns, filled in per locale"),
    (r"^[a-z]+(\.[A-Za-z]+){2,}$", "D-Bus and toolkit class names"),
]


def visible(text: str) -> bool:
    if any(re.search(pattern, text) for pattern, _ in SKIP_TEXT):
        return False
    bare = re.sub(r"\$\{[^}]*\}|\$\w+", "", text)
    if not re.search(r"[A-Za-z]{2}", bare):
        return False
    templated = bare != text and re.search(r"(^|\s)[a-z]{3,}(\s|$)", bare)
    if re.fullmatch(r"[a-z0-9_.\-/:@%=&?#+,* ]*", bare) and " " not in bare.strip() and not templated:
        return False  # ids, keys, paths, urls, one lowercase word
    if re.fullmatch(r"[A-Z0-9_]+", bare):
        return False  # constants
    if re.match(r"^(https?:|file:|/|\.\w|\./|--|-\w|%)", bare):
        return False
    if re.fullmatch(r"[\w.\-]+\.[a-z0-9]{2,5}", bare):
        return False  # file names
    return True


def scan(root: pathlib.Path, dirs):
    found = []
    for d in dirs:
        for f in sorted((root / d).rglob("*.kt")):
            if f.name in SKIP_FILES and d in DEFAULT:
                continue
            in_comment = False
            for number, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
                stripped = line.strip()
                if in_comment:
                    if "*/" in stripped:
                        in_comment = False
                    continue
                if stripped.startswith("/*") and "*/" not in stripped:
                    in_comment = True
                    continue
                if SKIP_LINE.search(line):
                    continue
                code = re.sub(r"\s//\s.*$", "", line)
                for match in LITERAL.finditer(code):
                    if visible(match.group(1)):
                        found.append((f.relative_to(root), number, match.group(1)))
    return found


def main():
    root = pathlib.Path(__file__).resolve().parent.parent
    dirs = sys.argv[1:] or DEFAULT
    found = scan(root, dirs)
    for path, number, text in found:
        print(f"{path}:{number}\t{text}")
    print(f"{len(found)} literals", file=sys.stderr)


if __name__ == "__main__":
    main()
