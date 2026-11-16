#!/usr/bin/env bash
# The 16 KB page-size gate (RL13). Google Play requires apps that target Android 15 or later to run
# on devices with 16 KB memory pages (since 2025-11-01), and a native library whose ELF LOAD
# segments are aligned to less than 0x4000 does not load there. This fails when any native library
# in the given inputs breaks that, so the build is stopped before it ships.
#
#   scripts/check-16kb.sh [--strict-32] [--allow-empty] <input>...
#   scripts/check-16kb.sh --self-test
#
# An input is an .apk, an .aab, a .jar or .zip, a single .so, or a folder (a desktop bundle: its
# loose .so files and every jar inside it are scanned).
#
# What is checked
#   1. APK only: `zipalign -c -P 16 -v 4`, so an uncompressed .so starts on a 16 KB boundary in the
#      zip (the app is built with compressed libraries, which zipalign reports as "OK - compressed";
#      they are extracted at install and only their ELF alignment matters).
#   2. Every ELF file (found by its magic bytes, not its name): the `p_align` of each LOAD segment
#      must be at least 0x4000. 64-bit libraries (arm64-v8a, x86_64) are what Google enforces, so a
#      miss there fails. A 32-bit library (armeabi-v7a, x86) below 0x4000 is only a warning, because
#      16 KB devices run 64-bit apps; --strict-32 makes it fail too.
#   3. An input with no native library at all fails (a broken unzip must not pass the gate),
#      unless --allow-empty.
#
# Exit codes: 0 all good, 1 a library breaks the rule, 2 usage error or a missing tool.
# Tools: readelf (binutils; READELF overrides, llvm-readelf and greadelf are tried), unzip, python3
# only for --self-test, zipalign from Android build-tools 35 or newer (ZIPALIGN overrides; PATH,
# then $ANDROID_HOME, $ANDROID_SDK_ROOT and ~/Android/Sdk are searched).
# Messages use GitHub's ::error:: and ::warning:: prefixes, which also read fine in a terminal.
set -euo pipefail

MIN_ALIGN=$((0x4000))
strict32=0
allow_empty=0
inputs=()
work=""
trap '[ -z "$work" ] || rm -rf "$work"' EXIT

die() { echo "check-16kb: $*" >&2; exit 2; }

find_readelf() {
  local t
  for t in "${READELF:-}" readelf llvm-readelf greadelf; do
    [ -n "$t" ] && command -v "$t" >/dev/null 2>&1 && { echo "$t"; return 0; }
  done
  return 1
}

# zipalign that knows -P (build-tools 35.0.0 and newer); prints nothing when there is none.
find_zipalign() {
  local c sdk help
  local -a candidates=()
  [ -n "${ZIPALIGN:-}" ] && candidates+=("$ZIPALIGN")
  command -v zipalign >/dev/null 2>&1 && candidates+=("$(command -v zipalign)")
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
    [ -d "$sdk/build-tools" ] || continue
    while IFS= read -r c; do candidates+=("$c"); done \
      < <(find "$sdk/build-tools" -maxdepth 2 -name zipalign -type f 2>/dev/null | sort -rV)
  done
  for c in "${candidates[@]}"; do
    help="$("$c" 2>&1 || true)"
    case "$help" in *"-P <pagesize_kb>"*) echo "$c"; return 0 ;; esac
  done
  return 0
}

bad=0
warned=0
checked=0

# scan_elf <file> <label>: one ELF file's LOAD segments.
scan_elf() {
  local file="$1" label="$2" class aligns align worst=-1 a
  case "$(head -c 5 "$file" | od -An -tx1 | tr -d ' \n')" in
    7f454c4601) class=32 ;;
    7f454c4602) class=64 ;;
    7f454c46*) echo "::warning::$label: unknown ELF class, skipped"; return 0 ;;
    *) return 0 ;; # not ELF (a Mach-O dylib, a Windows dll, a data file): not this gate's business
  esac
  aligns="$("$READELF_BIN" -lW "$file" 2>/dev/null | awk '$1 == "LOAD" { print $NF }')" || aligns=""
  checked=$((checked + 1))
  if [ -z "$aligns" ]; then
    bad=$((bad + 1))
    echo "::error::$label: no LOAD segment could be read"
    return 0
  fi
  for a in $aligns; do
    align=$((a))
    if [ "$worst" -lt 0 ] || [ "$align" -lt "$worst" ]; then worst=$align; fi
  done
  if [ "$worst" -ge "$MIN_ALIGN" ]; then
    echo "ok     ${class}-bit  $(printf '0x%x' "$worst")  $label"
  elif [ "$class" = 32 ] && [ "$strict32" = 0 ]; then
    warned=$((warned + 1))
    echo "::warning::$label: 32-bit library aligned to $(printf '0x%x' "$worst") (16 KB devices run 64-bit apps; --strict-32 to fail)"
  else
    bad=$((bad + 1))
    echo "::error::$label: ${class}-bit library has a LOAD segment aligned to $(printf '0x%x' "$worst"), 0x4000 is the minimum (link with -Wl,-z,max-page-size=16384)"
  fi
}

# scan_tree <folder> <label prefix>: every file under it that is an ELF, and every jar or zip inside.
scan_tree() {
  local dir="$1" prefix="$2" f sub
  while IFS= read -r -d '' f; do
    case "$f" in
      *.jar | *.zip)
        sub="$(mktemp -d "$work/jar.XXXXXX")"
        if unzip -qq -o "$f" -d "$sub" 2>/dev/null; then scan_tree "$sub" "$prefix${f#"$dir"/}!/"; fi
        ;;
      *)
        # cheap pre-filter, so a big folder of resources is not opened one file at a time
        if [ "$(head -c 4 "$f" 2>/dev/null | od -An -tx1 | tr -d ' \n')" = 7f454c46 ]; then
          scan_elf "$f" "$prefix${f#"$dir"/}"
        fi
        ;;
    esac
  done < <(find "$dir" -type f -print0)
}

check_input() {
  local in="$1" before="$checked" dest zip zout
  [ -e "$in" ] || die "no such file or folder: $in"
  echo "== $in"
  if [ -d "$in" ]; then
    scan_tree "$in" ""
  else
    case "$in" in
      *.apk | *.aab | *.jar | *.zip)
        if [[ "$in" == *.apk ]]; then
          zip="$(find_zipalign)"
          if [ -z "$zip" ]; then
            bad=$((bad + 1))
            echo "::error::$in: no zipalign with -P found (Android build-tools 35 or newer; set ZIPALIGN or ANDROID_HOME)"
          elif zout="$("$zip" -c -P 16 -v 4 "$in" 2>&1)"; then
            echo "ok     zipalign -c -P 16 -v 4"
          else
            bad=$((bad + 1))
            echo "::error::$in: not 16 KB zip aligned, these entries are off:"
            echo "$zout" | grep -i 'fail\|bad\|\.so' | grep -vi 'ok' | sed 's/^/    /' | head -20
          fi
        fi
        dest="$(mktemp -d "$work/in.XXXXXX")"
        unzip -qq -o "$in" -d "$dest" || die "cannot unzip $in"
        scan_tree "$dest" ""
        ;;
      *)
        scan_elf "$in" "$(basename "$in")"
        ;;
    esac
  fi
  if [ "$checked" = "$before" ] && [ "$allow_empty" = 0 ]; then
    bad=$((bad + 1))
    echo "::error::$in: no native library found, so nothing was checked"
  fi
}

# ---- self test -----------------------------------------------------------------------------------

# make_elf <out> <32|64> <align>: the smallest ELF readelf reads, one PT_LOAD with that p_align.
make_elf() {
  python3 -I - "$1" "$2" "$3" <<'PY'
import struct, sys
out, bits, align = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
if bits == 64:
    ehdr = b"\x7fELF" + bytes([2, 1, 1, 0]) + bytes(8) + struct.pack("<HHIQQQIHHHHHH", 3, 183, 1, 0, 64, 0, 0, 64, 56, 1, 0, 0, 0)
    phdr = struct.pack("<IIQQQQQQ", 1, 5, 0, 0, 0, 120, 120, align)
else:
    ehdr = b"\x7fELF" + bytes([1, 1, 1, 0]) + bytes(8) + struct.pack("<HHIIIIIHHHHHH", 3, 40, 1, 0, 52, 0, 0, 52, 32, 1, 0, 0, 0)
    phdr = struct.pack("<IIIIIIII", 1, 0, 0, 0, 84, 84, 5, align)
open(out, "wb").write(ehdr + phdr)
PY
}

# make_apk <out> <elf> <aligned|unaligned>: a zip with that .so stored, the way an APK keeps it.
make_apk() {
  local out="$1" elf="$2" mode="$3" zip
  python3 -I - "$out.raw" "$elf" <<'PY'
import sys, zipfile
raw, elf = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(raw, "w", zipfile.ZIP_STORED) as z:
    z.writestr("classes.dex", b"x" * 5000)  # makes the next entry start off a page boundary
    z.write(elf, "lib/arm64-v8a/libfixture.so")
PY
  if [ "$mode" = aligned ]; then
    zip="$(find_zipalign)"
    "$zip" -f -P 16 4 "$out.raw" "$out" >/dev/null
    rm -f "$out.raw"
  else
    mv "$out.raw" "$out"
  fi
}

self_test() {
  command -v python3 >/dev/null || die "--self-test needs python3"
  local t me failures=0 zip
  me="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
  t="$(mktemp -d)"
  work="$t"
  expect() { # <exit code wanted> <name> <args...>
    local want="$1" name="$2" got=0
    shift 2
    "$me" "$@" >"$t/out.txt" 2>&1 || got=$?
    if [ "$got" = "$want" ]; then
      echo "pass  $name"
    else
      failures=$((failures + 1))
      echo "FAIL  $name (exit $got, wanted $want)"
      sed 's/^/        /' "$t/out.txt"
    fi
  }
  make_elf "$t/good64.so" 64 $((0x4000))
  make_elf "$t/big64.so" 64 $((0x10000))
  make_elf "$t/bad64.so" 64 $((0x1000))
  make_elf "$t/good32.so" 32 $((0x4000))
  make_elf "$t/bad32.so" 32 $((0x1000))
  printf 'not an elf' > "$t/plain.so"

  expect 0 "64-bit 16 KB aligned passes" "$t/good64.so"
  expect 0 "64-bit 64 KB aligned passes" "$t/big64.so"
  expect 1 "64-bit 4 KB aligned fails" "$t/bad64.so"
  expect 0 "32-bit 4 KB aligned only warns" "$t/bad32.so"
  expect 1 "32-bit 4 KB aligned fails with --strict-32" --strict-32 "$t/bad32.so"
  expect 0 "32-bit 16 KB aligned passes with --strict-32" --strict-32 "$t/good32.so"
  expect 1 "a file that is not ELF counts as no library" "$t/plain.so"
  expect 0 "a file that is not ELF passes with --allow-empty" --allow-empty "$t/plain.so"

  mkdir -p "$t/bundle/lib" "$t/badbundle/lib" "$t/jarsrc/natives" "$t/jarbundle"
  cp "$t/good64.so" "$t/bundle/lib/libgood.so"
  cp "$t/good64.so" "$t/badbundle/lib/libgood.so"
  cp "$t/bad64.so" "$t/badbundle/lib/libbad.so"
  expect 0 "a folder of good libraries passes" "$t/bundle"
  expect 1 "a folder with one 4 KB library fails" "$t/badbundle"
  cp "$t/bad64.so" "$t/jarsrc/natives/libinjar.so"
  python3 -I -c "import sys, zipfile; z = zipfile.ZipFile(sys.argv[1], 'w'); z.write(sys.argv[2], 'natives/libinjar.so'); z.close()" \
    "$t/jarbundle/native.jar" "$t/bad64.so"
  expect 1 "a bad library inside a jar of a bundle fails" "$t/jarbundle"
  mkdir -p "$t/empty"
  expect 1 "a folder with no library fails" "$t/empty"
  expect 0 "a folder with no library passes with --allow-empty" --allow-empty "$t/empty"
  expect 2 "a missing path is a usage error" "$t/does-not-exist"

  zip="$(find_zipalign)"
  if [ -n "$zip" ]; then
    make_apk "$t/aligned.apk" "$t/good64.so" aligned
    make_apk "$t/unaligned.apk" "$t/good64.so" unaligned
    make_apk "$t/badelf.apk" "$t/bad64.so" aligned
    expect 0 "an APK with 16 KB zip alignment and 16 KB libraries passes" "$t/aligned.apk"
    expect 1 "an APK that is not 16 KB zip aligned fails" "$t/unaligned.apk"
    expect 1 "an APK with a 4 KB library fails" "$t/badelf.apk"
  else
    echo "skip  the APK cases: no zipalign with -P (build-tools 35 or newer) here"
  fi
  rm -rf "$t"
  work=""
  if [ "$failures" = 0 ]; then echo "check-16kb self-test: all passed"; else echo "check-16kb self-test: $failures failed"; exit 1; fi
}

# ---- main ----------------------------------------------------------------------------------------

while [ $# -gt 0 ]; do
  case "$1" in
    --self-test) self_test; exit 0 ;;
    --strict-32) strict32=1 ;;
    --allow-empty) allow_empty=1 ;;
    -h | --help) sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 0 ;;
    -*) die "unknown option $1" ;;
    *) inputs+=("$1") ;;
  esac
  shift
done
[ "${#inputs[@]}" -gt 0 ] || die "usage: $0 [--strict-32] [--allow-empty] <apk|aab|jar|so|folder>... | --self-test"
READELF_BIN="$(find_readelf)" || die "readelf not found (binutils); set READELF"
command -v unzip >/dev/null || die "unzip not found"
work="$(mktemp -d)"

for in in "${inputs[@]}"; do check_input "$in"; done

echo "checked $checked native libraries: $bad failed, $warned warned"
[ "$bad" = 0 ]
