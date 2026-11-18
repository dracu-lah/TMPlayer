#!/usr/bin/env bash
# CI's "Desktop (Linux)" job on this machine (docs/agents/CI-LOCAL-PLAN.md, P3), in two parts:
#
#   1. Natives: the glibc 2.35 TDLib and libmpv, what .github/actions/linux-natives does. Restored
#      from ~/.cache/tmplayer/linux-natives/<recipe hash>, or built by scripts/build-tdlib-linux.sh
#      and scripts/build-mpv-linux-2-35.sh in their Ubuntu 22.04 podman images, then checked
#      against natives/linux-2-35/PINS.txt. The hash covers the same files as CI's cache key
#      (natives/ without natives/linux-2-35/, and the two scripts), so a recipe change rebuilds.
#   2. Package: what .github/actions/desktop-linux does, on this host: :desktop:test, the app
#      image and its --self-test, the glibc floor, the tarball and AppImage into dist/, their
#      self-tests, the plain Ubuntu 22.04 check in podman and the AppImage's .zsync check.
#
#   scripts/linux-local.sh                  both parts, version 1.0.1-local.0
#   scripts/linux-local.sh --dry-run        print what would run, check the tools, change nothing
#   scripts/linux-local.sh --natives-only   part 1 only
#   scripts/linux-local.sh --no-tests       skip :desktop:test (CI runs it; ci-local's kotlin group does not)
#   scripts/linux-local.sh --adopt          on a cache miss, take ~/.cache/tmplayer-natives as built from
#                                           this recipe when it matches the pins, instead of a 30 min build
#   scripts/linux-local.sh --version 1.2.3-beta.1
#
# JOBS (default 4, as in CI) caps the native compile jobs; CI_LOCAL_WORKERS the Gradle workers.
# Needs podman, a JDK 21 (JAVA_HOME, or found in ~/.jdks, ~/.local/jdks, /usr/lib/jvm), curl,
# objcopy, desktop-file-validate, appstreamcli and zsyncmake (Fedora: sudo dnf install zsync).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
repo=$PWD

dry=false natives_only=false tests=true adopt=false version=1.0.1-local.0
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) dry=true ;;
    --natives-only) natives_only=true ;;
    --no-tests) tests=false ;;
    --adopt) adopt=true ;;
    --version) version="${2:?--version needs x.y.z or x.y.z-suffix}"; shift ;;
    -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
    *) echo "linux-local: unknown argument $1" >&2; exit 2 ;;
  esac
  shift
done
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || { echo "linux-local: version $version is not x.y.z or x.y.z-suffix" >&2; exit 2; }

cache="${TMPLAYER_CACHE:-$HOME/.cache/tmplayer}"
built="${TMPLAYER_NATIVES:-$HOME/.cache/tmplayer-natives}"   # where the two build scripts write
workers="${CI_LOCAL_WORKERS:-$(( $(nproc) > 8 ? 4 : 2 ))}"
export JOBS="${JOBS:-4}"

say() { echo "== linux-local: $*"; }
x() { printf '+ %s\n' "$*"; $dry || "$@"; }
missing=()
need() { command -v "$1" >/dev/null || missing+=("$1${2:+ ($2)}"); }
free_gb() { df -P --block-size=1G "$1" | awk 'NR == 2 { print $4 }'; }

# --- 1. natives -------------------------------------------------------------------------------

recipe_hash() {
  git ls-files -co --exclude-standard -- natives scripts/build-tdlib-linux.sh scripts/build-mpv-linux-2-35.sh \
    | grep -v '^natives/linux-2-35/' | LC_ALL=C sort \
    | while IFS= read -r f; do [ -f "$f" ] && printf '%s  %s\n' "$(sha256sum < "$f" | cut -d' ' -f1)" "$f"; done \
    | sha256sum | cut -c1-16
}

pins_file() { awk '!/^#/ && NF { print $1 "  " $2 }' "$repo/natives/linux-2-35/PINS.txt" | LC_ALL=C sort -u; }
matches_pins() { [ -d "$1" ] && (cd "$1" && pins_file | sha256sum -c --quiet --status - 2>/dev/null); }

# Copies the pinned files (and nothing else: no downloads, logs or build trees) from $1 into the
# cache entry $2, through a temporary folder so an interrupted copy never looks complete.
store() {
  local from=$1 to=$2 tmp="$2.part.$$"
  rm -rf "$tmp"; mkdir -p "$tmp/tdlib" "$tmp/mpv" || return 1
  pins_file | while read -r _ f; do cp -p "$from/$f" "$tmp/$f" || exit 1; done || { rm -rf "$tmp"; return 1; }
  date -Is > "$tmp/.complete" && rm -rf "$to" && mv "$tmp" "$to"
}

natives() {
  local hash entry
  hash=$(recipe_hash)
  entry="$cache/linux-natives/$hash"
  say "natives: recipe $hash"
  if [ -f "$entry/.complete" ]; then
    say "natives: restored from $entry"
  elif $adopt && matches_pins "$built"; then
    say "natives: cache miss, adopting $built (it matches the pins)"
    $dry || store "$built" "$entry" || return 1
  else
    say "natives: cache miss, building in the Ubuntu 22.04 images (about 30 min cold, JOBS=$JOBS)"
    if ! $dry && [ "$(free_gb "$HOME")" -lt 15 ]; then
      echo "linux-local: the native builds need about 15 GB free under $HOME, $(free_gb "$HOME") GB is" >&2; return 1
    fi
    x scripts/build-tdlib-linux.sh --no-smoke || return 1
    x scripts/build-mpv-linux-2-35.sh || return 1
    if ! $dry; then
      matches_pins "$built" || { pins_mismatch "$built"; return 1; }
      store "$built" "$entry" || return 1
    fi
  fi
  # The same check as the action's last step, on what the build will read.
  printf '+ sha256sum -c PINS.txt in %s\n' "$entry"
  if ! $dry; then
    (cd "$entry" && pins_file | sha256sum -c --quiet -) || { pins_mismatch "$entry"; return 1; }
  fi
  export TMPLAYER_LINUX_NATIVES="$entry"
  say "natives: TMPLAYER_LINUX_NATIVES=$entry"
}

pins_mismatch() {
  echo "linux-local: the natives in $1 do not match natives/linux-2-35/PINS.txt. If the recipe under natives/ or the build scripts changed on purpose, copy the new hashes into the pins file." >&2
}

# --- 2. package and self-test -----------------------------------------------------------------

find_jdk21() {
  local j
  is21() { [ -x "$1/bin/java" ] && "$1/bin/java" -version 2>&1 | head -1 | grep -q '"21[."]'; }
  if [ -n "${JAVA_HOME:-}" ] && is21 "$JAVA_HOME"; then return 0; fi
  for j in "$HOME"/.jdks/*21* "$HOME"/.local/jdks/*21* /usr/lib/jvm/*21*; do
    if is21 "$j"; then export JAVA_HOME="$j"; return 0; fi
  done
  missing+=("a JDK 21 (set JAVA_HOME; JDK 25 fails the build, docs/BUILDING.md)")
}

appimagetool() {
  local tool="$cache/tools/appimagetool-1.9.1"
  if [ ! -x "$tool" ]; then
    x mkdir -p "$cache/tools"
    x curl -sSfL -o "$tool.part" https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage
    if ! $dry; then
      echo "ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0  $tool.part" | sha256sum -c --quiet - \
        || { rm -f "$tool.part"; echo "linux-local: appimagetool failed its SHA-256 check" >&2; return 1; }
      chmod +x "$tool.part"; mv "$tool.part" "$tool"
    fi
  fi
  export APPIMAGETOOL="$tool"
}

zsync_check() {
  local image="dist/TMPlayer-$version-x86_64.AppImage" info channel=latest
  echo "+ check $image.zsync and the AppImage's update information"
  $dry && return 0
  [ -s "$image.zsync" ] || { echo "linux-local: $image.zsync was not written" >&2; return 1; }
  grep -q "^Filename: TMPlayer-$version-x86_64.AppImage$" "$image.zsync" \
    || { echo "linux-local: $image.zsync does not name the AppImage" >&2; return 1; }
  info="$(objcopy -O binary --only-section=.upd_info "$image" /dev/stdout | tr -d '\0')"
  case "$version" in *-*) channel=latest-all ;; esac
  [ "$info" = "gh-releases-zsync|dracu-lah|TMPlayer|$channel|TMPlayer-*-x86_64.AppImage.zsync" ] \
    || { echo "linux-local: the AppImage carries update information '$info'" >&2; return 1; }
}

package() {
  local gradle=(./gradlew --max-workers="$workers") app=desktop/build/compose/binaries/main-release/app/TMPlayer
  [ -n "${TMPLAYER_LINUX_NATIVES:-}" ] || { echo "linux-local: TMPLAYER_LINUX_NATIVES is not set" >&2; return 1; }
  if ! $dry && [ "$(free_gb "$repo")" -lt 5 ]; then
    echo "linux-local: packaging needs about 5 GB free in $repo, $(free_gb "$repo") GB is" >&2; return 1
  fi
  say "package: version $version, JAVA_HOME=${JAVA_HOME:-unset}, $workers Gradle workers"
  if $tests; then x "${gradle[@]}" :desktop:test || return 1; fi
  x "${gradle[@]}" :desktop:createReleaseDistributable -PdesktopVersion="$version" || return 1
  x "$app/bin/TMPlayer" --self-test || return 1
  x desktop/packaging/linux/glibc-floor.sh "$app" || return 1
  appimagetool || return 1
  # Only this version's files: the earlier ones in dist/ are left as they are.
  x rm -f "dist/TMPlayer-$version-linux-x64.tar.xz" "dist/TMPlayer-$version-x86_64.AppImage" "dist/TMPlayer-$version-x86_64.AppImage.zsync"
  x env OUT="$repo/dist" desktop/packaging/linux/package.sh "$version" || return 1

  x tar -C "$tmp" -xJf "dist/TMPlayer-$version-linux-x64.tar.xz" || return 1
  x "$tmp/TMPlayer-$version/bin/TMPlayer" --self-test || return 1
  x env APPIMAGE_EXTRACT_AND_RUN=1 "dist/TMPlayer-$version-x86_64.AppImage" --self-test || return 1
  x env CONTAINER_ENGINE=podman .github/actions/desktop-linux/plain-linux-check.sh \
    "dist/TMPlayer-$version-x86_64.AppImage" "dist/TMPlayer-$version-linux-x64.tar.xz" || return 1
  zsync_check || return 1
  x ls -la dist
}

# --- run --------------------------------------------------------------------------------------

need git; need sha256sum
if ! $natives_only; then
  find_jdk21
  need curl; need objcopy binutils; need desktop-file-validate desktop-file-utils
  need appstreamcli appstream; need zsyncmake "sudo dnf install zsync"; need podman; need unzip
fi
if [ ${#missing[@]} -gt 0 ]; then
  printf 'linux-local: missing %s\n' "${missing[@]}" >&2
  $dry || exit 1
  echo "linux-local: (dry run) a real run stops here" >&2
fi
$dry && say "dry run: nothing is built, downloaded or written"

# Functions run under || below, where set -e is off: each step checks its own result.
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
start=$SECONDS
natives || exit 1
$natives_only || package || exit 1
if $dry; then say "dry run done"; else say "passed in $(( (SECONDS - start) / 60 )) min"; fi
