#!/usr/bin/env bash
# Prints the newest glibc and libstdc++ symbol versions any native in the app image needs,
# including the ones packed inside jars (TDLib, libmpv and FFmpeg, skiko, JNA), and fails if
# either is newer than the floor the downloads promise (package.sh, the PKGBUILD, the release notes).
#
#   desktop/packaging/linux/glibc-floor.sh desktop/build/compose/binaries/main/app/TMPlayer
set -euo pipefail

app="${1:?usage: glibc-floor.sh <app image dir>}"
max_glibc="${MAX_GLIBC:-2.38}"
max_glibcxx="${MAX_GLIBCXX:-3.4.32}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Natives inside jars, unpacked where the loaders would put them.
n=0
while IFS= read -r -d '' jar; do
  n=$((n + 1))
  mkdir -p "$tmp/$n"
  unzip -qq -o "$jar" '*.so' '*.so.*' -d "$tmp/$n" 2>/dev/null || true
done < <(find "$app" -name '*.jar' -print0)

report="$tmp/report.txt"
while IFS= read -r -d '' f; do
  file -b "$f" | grep -q '^ELF' || continue
  g="$(objdump -T "$f" 2>/dev/null | grep -oE 'GLIBC_[0-9.]+' | sed 's/GLIBC_//' | sort -uV | tail -1 || true)"
  x="$(objdump -T "$f" 2>/dev/null | grep -oE 'GLIBCXX_[0-9.]+' | sed 's/GLIBCXX_//' | sort -uV | tail -1 || true)"
  printf '%s\t%s\t%s\n' "${g:-none}" "${x:-none}" "${f#"$tmp"/}" >> "$report"
done < <(find "$app" "$tmp" -type f \( -name '*.so' -o -name '*.so.*' -o -perm -u+x \) -print0)

newest() { cut -f"$1" "$report" | grep -v none | sort -uV | tail -1 || true; }
glibc="$(newest 1)"
glibcxx="$(newest 2)"
echo "Highest symbol versions required:"
grep -v '^none' "$report" | sort -t$'\t' -k1,1V -k2,2V | tail -6 | sed 's/^/  /'
echo "glibc $glibc (floor $max_glibc), libstdc++ GLIBCXX_$glibcxx (floor $max_glibcxx)"

above() { [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -1)" != "$2" ]; }
if above "$glibc" "$max_glibc" || above "$glibcxx" "$max_glibcxx"; then
  echo "A native now needs a newer glibc or libstdc++ than the downloads promise." >&2
  echo "Raise the floor in the PKGBUILD, package.sh and the release notes, or pin the dependency." >&2
  exit 1
fi
