#!/usr/bin/env bash
# Fails when a shared library asks the system for more than the desktop Linux floor allows:
#   GLIBC_   above 2.35    (Ubuntu 22.04, Pop!_OS 22.04, Mint 21)
#   GLIBCXX_ above 3.4.30  (the libstdc++ of Ubuntu 22.04, GCC 12)
#   CXXABI_  above 1.3.13  (the same libstdc++)
# It reads the versioned symbols the library imports (objdump -T), so it sees what the loader will
# ask for, not what the build host happened to have.
#
# Usage: natives/tdlib-linux/check-floor.sh <library.so>...
#   GLIBC_FLOOR, GLIBCXX_FLOOR, CXXABI_FLOOR override the floors.
set -euo pipefail

glibc_floor="${GLIBC_FLOOR:-2.35}"
glibcxx_floor="${GLIBCXX_FLOOR:-3.4.30}"
cxxabi_floor="${CXXABI_FLOOR:-1.3.13}"

if [ "$#" -eq 0 ]; then
  echo "usage: $0 <library.so>..." >&2
  exit 2
fi
command -v objdump > /dev/null || { echo "objdump (binutils) is needed" >&2; exit 2; }

# Prints the versions of one prefix (GLIBC, GLIBCXX, CXXABI) that the library needs, sorted.
needed() {
  objdump -T "$1" | grep -oE "\b$2_[0-9]+(\.[0-9]+)*\b" | sed "s/^$2_//" | sort -uV || true
}

# True when version $1 is above version $2.
above() {
  [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -n 1)" = "$1" ]
}

failed=0
for lib in "$@"; do
  if [ ! -f "$lib" ]; then
    echo "FAIL $lib: no such file"
    failed=1
    continue
  fi
  if ! objdump -T "$lib" > /dev/null 2>&1; then
    echo "FAIL $lib: objdump cannot read it"
    failed=1
    continue
  fi
  for spec in "GLIBC:$glibc_floor" "GLIBCXX:$glibcxx_floor" "CXXABI:$cxxabi_floor"; do
    prefix="${spec%%:*}"
    floor="${spec#*:}"
    highest="$(needed "$lib" "$prefix" | tail -n 1)"
    if [ -z "$highest" ]; then
      echo "ok   $lib: no ${prefix}_ symbols"
      continue
    fi
    over=""
    for v in $(needed "$lib" "$prefix"); do
      if above "$v" "$floor"; then
        over="$over ${prefix}_$v"
      fi
    done
    if [ -n "$over" ]; then
      echo "FAIL $lib: needs${over} (floor ${prefix}_$floor); symbols:"
      for v in $over; do
        objdump -T "$lib" | grep -F "($v)" | awk '{print "       " $NF}' || true
      done
      failed=1
    else
      echo "ok   $lib: highest ${prefix}_$highest (floor $floor)"
    fi
  done
done
exit "$failed"
