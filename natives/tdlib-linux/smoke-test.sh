#!/usr/bin/env bash
# Proves a libtdjsonjava.so loads and answers: once inside a plain Ubuntu 22.04 container with a
# JDK 21 (the floor, glibc 2.35), once on this host. Smoke.java loads it with System.load, asks
# td_execute for the version and commit hash, then runs a client through send and receive until it
# answers. No network, no account.
#
# It uses org.drinkless.tdlib.JsonClient from the tdl-coroutines jar in the Gradle cache, the class
# the app calls, so a library that registers its natives for another class fails here.
#
# Usage: natives/tdlib-linux/smoke-test.sh <libtdjsonjava.so> <expected version> <expected commit>
#   SMOKE_HOST=false skips the host run, SMOKE_CONTAINER=false the container run.
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <libtdjsonjava.so> <expected version> <expected commit>" >&2
  exit 2
fi
lib="$(readlink -f "$1")"
version="$2"
commit="$3"
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
root=$(CDPATH= cd -- "$here/../.." && pwd)

[ -f "$lib" ] || { echo "no such file: $lib" >&2; exit 2; }
[ "$(basename "$lib")" = "libtdjsonjava.so" ] || {
  echo "the file must be named libtdjsonjava.so (JsonClient loads it by that name)" >&2
  exit 2
}

tdl="$(sed -n 's/^tdl = "\(.*\)"$/\1/p' "$root/gradle/libs.versions.toml")"
jar="$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/dev.g000sha256/tdl-coroutines-jvm/$tdl" \
  -name "tdl-coroutines-jvm-$tdl.jar" 2> /dev/null | head -n 1)"
[ -n "$jar" ] || {
  echo "tdl-coroutines-jvm-$tdl.jar is not in the Gradle cache; run ./gradlew :desktop:compileKotlin once" >&2
  exit 2
}

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes" "$work/lib"
unzip -q -o "$jar" 'org/drinkless/tdlib/*' -d "$work/classes"
cp "$here/Smoke.java" "$work/Smoke.java"
cp "$lib" "$work/lib/libtdjsonjava.so"

status=0

if [ "${SMOKE_CONTAINER:-true}" = "true" ]; then
  image="localhost/tmplayer-tdlib-linux-smoke"
  podman build --target smoke -t "$image" -f "$here/Containerfile" "$here"
  echo "== ubuntu:22.04 container"
  if podman run --rm --network none --security-opt label=disable \
      -v "$work:/smoke:ro" "$image" \
      sh -c 'ldd --version | head -n 1; java -version 2>&1 | head -n 1;
        java --enable-native-access=ALL-UNNAMED -cp /smoke/classes /smoke/Smoke.java \
          /smoke/lib/libtdjsonjava.so "$0" "$1"' "$version" "$commit"; then
    echo "container: ok"
  else
    echo "container: FAILED"
    status=1
  fi
fi

if [ "${SMOKE_HOST:-true}" = "true" ]; then
  java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
  echo "== host ($(ldd --version | head -n 1))"
  if "$java" --enable-native-access=ALL-UNNAMED -cp "$work/classes" "$work/Smoke.java" \
      "$work/lib/libtdjsonjava.so" "$version" "$commit"; then
    echo "host: ok"
  else
    echo "host: FAILED"
    status=1
  fi
fi

exit "$status"
