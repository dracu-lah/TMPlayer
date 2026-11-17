#!/usr/bin/env bash
# Fails when any shared library in a runtime folder needs a newer glibc or libstdc++ than the
# Linux floor (Ubuntu 22.04: glibc 2.35, GLIBCXX_3.4.30, CXXABI_1.3.13).
#
# Usage: natives/mpv-linux/check-floor.sh <folder> [max glibc] [max GLIBCXX]
#   defaults: 2.35 3.4.30. Prints one line per library: the highest GLIBC_ and GLIBCXX_ it needs.
set -euo pipefail

dir="${1:?usage: check-floor.sh <folder> [max glibc] [max GLIBCXX]}"
max_glibc="${2:-2.35}"
max_glibcxx="${3:-3.4.30}"
max_cxxabi="1.3.13"

command -v objdump >/dev/null || { echo "objdump not found (binutils)" >&2; exit 2; }

# True when version $1 is above version $2.
above() { [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -1)" = "$1" ]; }

bad=0
count=0
while IFS= read -r -d '' f; do
  # Only ELF files: a folder may hold a SHA256SUMS or a jar manifest beside the libraries.
  [ "$(head -c 4 "$f" | od -An -c | tr -d ' ')" = "177ELF" ] || continue
  count=$((count + 1))
  syms=$(objdump -T "$f" 2>/dev/null) || { echo "FAIL $f: objdump could not read it"; bad=1; continue; }
  g=$(grep -oE '\bGLIBC_[0-9]+(\.[0-9]+)+' <<<"$syms" | sed 's/GLIBC_//' | sort -uV | tail -1 || true)
  x=$(grep -oE '\bGLIBCXX_[0-9]+(\.[0-9]+)+' <<<"$syms" | sed 's/GLIBCXX_//' | sort -uV | tail -1 || true)
  c=$(grep -oE '\bCXXABI_[0-9]+(\.[0-9]+)+' <<<"$syms" | sed 's/CXXABI_//' | sort -uV | tail -1 || true)
  status=ok
  if [ -n "$g" ] && above "$g" "$max_glibc"; then status=FAIL; fi
  if [ -n "$x" ] && above "$x" "$max_glibcxx"; then status=FAIL; fi
  if [ -n "$c" ] && above "$c" "$max_cxxabi"; then status=FAIL; fi
  printf '%-4s %-28s GLIBC %-6s GLIBCXX %-7s CXXABI %s\n' "$status" "$(basename "$f")" \
    "${g:--}" "${x:--}" "${c:--}"
  [ "$status" = ok ] || bad=1
done < <(find "$dir" -maxdepth 1 \( -type f -o -type l \) -name '*.so*' -print0 | sort -z)

if [ "$count" -eq 0 ]; then
  echo "no shared libraries in $dir" >&2
  exit 2
fi
if [ "$bad" -ne 0 ]; then
  echo "above the floor (glibc $max_glibc, GLIBCXX $max_glibcxx, CXXABI $max_cxxabi)" >&2
  exit 1
fi
echo "all $count libraries within glibc $max_glibc, GLIBCXX $max_glibcxx"
