#!/usr/bin/env bash
# Proves the CI gate (.github/actions/desktop-linux/plain-linux-check.sh) does its job: it must FAIL
# on TDLib as the tdl-coroutines jar ships it (built on Ubuntu 24.04: GLIBCXX_3.4.32, GCC 13,
# glibc 2.38) and PASS on the glibc 2.35 rebuild (scripts/build-tdlib-linux.sh). Run it after
# changing the gate, glibc-floor.sh or the floor, and whenever the gate has not failed in a while.
#
#   CONTAINER_ENGINE=podman desktop/packaging/linux/test-floor-gate.sh
#
# What it builds are two stand-in app images, each a jar with the TDLib library under test plus a
# bin/TMPlayer whose --self-test loads that library in the container the way the real one would
# (ldd on it, "not found" is a failure). The real self-test is covered by running the gate on a real
# build, which CI does on every build.
#
# Inputs, overridable:  ORIGINAL_JAR (the tdl-coroutines jar) and REBUILT_SO (the rebuilt library),
# CONTAINER_ENGINE (docker or podman), IMAGE.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
gate="$repo/.github/actions/desktop-linux/plain-linux-check.sh"
engine="${CONTAINER_ENGINE:-docker}"
export CONTAINER_ENGINE="$engine"

original_jar="${ORIGINAL_JAR:-$(ls "$HOME"/.gradle/caches/modules-2/files-2.1/dev.g000sha256/tdl-coroutines-jvm/15.0.0/*/tdl-coroutines-jvm-15.0.0.jar 2>/dev/null | head -1)}"
rebuilt_so="${REBUILT_SO:-$HOME/.cache/tmplayer-natives/tdlib/libtdjsonjava.so}"
[ -f "$original_jar" ] || { echo "the tdl-coroutines 15.0.0 jar is not in the Gradle cache (set ORIGINAL_JAR)" >&2; exit 2; }
[ -f "$rebuilt_so" ] || { echo "no rebuilt TDLib at $rebuilt_so (scripts/build-tdlib-linux.sh, or set REBUILT_SO)" >&2; exit 2; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# A stand-in app image around one TDLib library: lib/app/tdlib.jar holds it at linux/x64/ like the
# real jar, the launcher answers --self-test the way SelfTest.kt reports it.
standin() {
  local dir="$1" so="$2"
  mkdir -p "$dir/bin" "$dir/lib/app" "$dir/jar/linux/x64"
  cp "$so" "$dir/jar/linux/x64/libtdjsonjava.so"
  (cd "$dir/jar" && zip -q -r ../lib/app/tdlib.jar linux)
  rm -rf "$dir/jar"
  cat > "$dir/bin/TMPlayer" <<'EOF'
#!/usr/bin/env bash
# Stand-in launcher: only --self-test, loading the jar's TDLib library the way the system loader would.
here="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
unzip -qq -p "$here/lib/app/tdlib.jar" linux/x64/libtdjsonjava.so > "$tmp/libtdjsonjava.so"
if ldd "$tmp/libtdjsonjava.so" 2>&1 | grep -E 'not found|version .* not found' >&2; then
  echo "FAIL tdlib: the library does not load"
  echo "SELF-TEST FAILED (1)"
  exit 1
fi
echo "PASS tdlib: loads"
echo "PASS libmpv: not under test here"
echo "SELF-TEST PASSED"
EOF
  chmod +x "$dir/bin/TMPlayer"
}

unzip -qq -p "$original_jar" linux/x64/libtdjsonjava.so > "$work/original.so"
standin "$work/bad" "$work/original.so"
standin "$work/good" "$rebuilt_so"
rm -f "$work/original.so"

fail=0
ok() { echo "ok:   $*"; }
no() { echo "FAIL: $*" >&2; fail=1; }

echo "== 1. TDLib as the jar ships it (GCC 13, glibc 2.38): the gate must fail, twice over"
if "$gate" "$work/bad" > "$work/bad.log" 2>&1; then
  no "the gate PASSED on the jar's library"
else
  ok "the gate failed on the jar's library"
  grep -q 'needs a newer glibc or libstdc++' "$work/bad.log" && ok "the floor check named it" \
    || no "the floor check did not fire (glibc-floor.sh)"
  grep -q 'TDLib did not load' "$work/bad.log" && ok "the self-test step named it" \
    || no "the self-test step did not fire"
fi

echo "== 2. TDLib rebuilt for glibc 2.35: the gate must pass"
if "$gate" "$work/good" > "$work/good.log" 2>&1; then
  ok "the gate passed on the rebuilt library"
else
  no "the gate failed on the rebuilt library"
  sed 's/^/   /' "$work/good.log" >&2
fi

if [ "$fail" -ne 0 ]; then
  echo "--- log of the jar's library run" >&2
  sed 's/^/   /' "$work/bad.log" >&2
  echo "The floor gate does not work as it should." >&2
  exit 1
fi
echo "The floor gate fails on the jar's TDLib and passes on the glibc 2.35 build."
