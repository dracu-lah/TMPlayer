#!/usr/bin/env bash
# Builds a release's signed Android APK, its R8 mapping and, with --linux, the Linux AppImage, its
# .zsync and the tarball on this machine, the way release.yml's android and desktop-linux jobs
# build them, so the tag's run hands these to the publish job instead of building them again
# (scripts/release-prebuilt.sh, docs/RELEASING.md "Building the release on your machine"). Windows,
# macOS and the TV wrappers still build on GitHub.
#
#   scripts/release-local.sh --dry-run [--linux] <tag>   the plan and every check, builds nothing
#   scripts/release-local.sh [--linux] <tag>             builds into dist/local-release/<tag>/
#
# It builds HEAD, which must be clean and is the commit to tag. Signing and the build's keys come
# from keystore.properties and local.properties in this checkout, read by Gradle as they are; this
# script only checks that their entries are set and never prints, copies or logs a value.
# SENTRY_AUTH_TOKEN (environment, else local.properties) uploads the R8 mapping to Sentry, as the
# release job does.
#
# Environment:
#   SKIP_TESTS=1     skip the unit tests (the release job skips them when CI passed on the code)
#   JOBS=4           FFmpeg extension build jobs (scripts/build-media3ext.sh)
#   GRADLE_WORKERS   Gradle's --max-workers, for a machine that is busy or short of memory
#   TMPLAYER_LINUX_NATIVES   the glibc 2.35 TDLib and libmpv (default ~/.cache/tmplayer-natives)
#
# It ends by printing the commands that upload the files to the draft release prebuilt-<tag> and
# push the tag. It runs none of them.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

dry=0
linux=0
tag=""
for arg in "$@"; do
  case "$arg" in
    --dry-run) dry=1 ;;
    --linux) linux=1 ;;
    -h | --help) sed -n '2,25p' "$0"; exit 0 ;;
    -*) echo "unknown option $arg" >&2; exit 2 ;;
    *) [ -z "$tag" ] || { echo "one tag only" >&2; exit 2; }; tag="$arg" ;;
  esac
done
[ -n "$tag" ] || { echo "usage: $0 [--dry-run] [--linux] <tag>" >&2; exit 2; }

python3 scripts/release-version.py --check > /dev/null
version_info="$(python3 scripts/release-version.py "$tag")"
v="$(sed -n 's/^name=//p' <<<"$version_info")"
code="$(sed -n 's/^code=//p' <<<"$version_info")"
prerelease="$(sed -n 's/^prerelease=//p' <<<"$version_info")"
out="dist/local-release/$tag"
draft="prebuilt-$tag"

problems=()
notes=()
problem() { problems+=("$1"); }
note() { notes+=("$1"); }

# prop <file> <key>: the value of a .properties entry (never printed, only tested).
prop() { sed -n "s/^[[:space:]]*$2[[:space:]]*=[[:space:]]*//p" "$1" 2>/dev/null | tail -n1 || true; }

# --- The commit ---
commit="$(git rev-parse HEAD)"
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  problem "the working tree has uncommitted changes: the files must be built from a commit that can be tagged"
fi
if existing="$(git rev-parse -q --verify "refs/tags/$tag^{commit}" 2>/dev/null)"; then
  [ "$existing" = "$commit" ] || problem "$tag already exists and points at $existing, not HEAD $commit"
fi

# --- JDK 21 (the build fails on 25, docs/BUILDING.md) ---
java_major() { "$1/bin/java" -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p'; }
if [ -n "${JAVA_HOME:-}" ] && [ "$(java_major "$JAVA_HOME")" = "21" ]; then
  :
else
  jdk="$(find "$HOME/.jdks" /usr/lib/jvm -maxdepth 1 -mindepth 1 -type d \( -name 'jdk-21*' -o -name 'java-21*' -o -name '*-21-*' \) 2>/dev/null | sort -rV | sed -n 1p || true)"
  if [ -n "$jdk" ] && [ "$(java_major "$jdk")" = "21" ]; then
    export JAVA_HOME="$jdk"
    note "JAVA_HOME=$jdk (JDK 21)"
  else
    problem "no JDK 21: set JAVA_HOME to one (the build fails on newer JDKs)"
  fi
fi

# --- Signing and keys ---
if [ ! -f keystore.properties ]; then
  problem "keystore.properties is missing, so the APK would not be signed with the release key"
else
  for k in storeFile storePassword keyAlias keyPassword; do
    [ -n "$(prop keystore.properties "$k")" ] || problem "keystore.properties has no $k"
  done
  store="$(prop keystore.properties storeFile)"
  if [ -n "$store" ]; then
    case "$store" in /*) ;; *) store="$root/$store" ;; esac
    [ -s "$store" ] || problem "the keystore that keystore.properties names (storeFile) is not there"
  fi
fi
if [ ! -f local.properties ]; then
  problem "local.properties is missing (sdk.dir and the Telegram credentials)"
else
  for k in TG_API_ID TG_API_HASH; do
    [ -n "$(prop local.properties "$k")" ] || problem "local.properties has no $k, so the app could not sign in"
  done
  optional=(SENTRY_DSN OPENSUBTITLES_API_KEY MSONE_GATEWAY_KEY TMDB_API_KEY OMDB_API_KEY)
  [ "$linux" = 1 ] && optional+=(SENTRY_DSN_DESKTOP)
  missing=()
  for k in "${optional[@]}"; do
    [ -n "$(prop local.properties "$k")" ] || [ -n "${!k:-}" ] || missing+=("$k")
  done
  [ ${#missing[@]} -eq 0 ] || note "not set, so this build lacks what they enable (as the release job would): ${missing[*]}"
  if [ -n "$(prop local.properties LOG_UPLOAD_URL)" ] || [ -n "${LOG_UPLOAD_URL:-}" ]; then
    problem "LOG_UPLOAD_URL is set: it is baked into the APK and Linux packages, and the release job's build never carries it. Unset it in local.properties and the environment"
  fi
fi
if [ -z "${SENTRY_AUTH_TOKEN:-}" ] && [ -f local.properties ] && [ -n "$(prop local.properties SENTRY_AUTH_TOKEN)" ]; then
  SENTRY_AUTH_TOKEN="$(prop local.properties SENTRY_AUTH_TOKEN)"
fi
if [ -n "${SENTRY_AUTH_TOKEN:-}" ]; then
  note "SENTRY_AUTH_TOKEN is set: the R8 mapping goes to Sentry with the build"
else
  note "SENTRY_AUTH_TOKEN is not set: the mapping is not uploaded to Sentry (it is still in the draft)"
fi

# --- Android SDK and the FFmpeg extension's tools ---
sdk="${ANDROID_HOME:-}"
[ -n "$sdk" ] || sdk="$(prop local.properties sdk.dir)"
if [ -z "$sdk" ] || [ ! -d "$sdk" ]; then
  problem "no Android SDK: set sdk.dir in local.properties or ANDROID_HOME"
else
  export ANDROID_HOME="$sdk"
  newest_tool() { find "$sdk/build-tools" -maxdepth 2 -name "$1" -type f 2>/dev/null | sort -rV | sed -n 1p; }
  for t in apksigner aapt2 zipalign; do
    [ -n "$(newest_tool "$t")" ] || problem "$t not found under the SDK's build-tools"
  done
  if ! ls build/media3ext/*-"$(sed -n 's/^TM_REVISION="\(.*\)"$/\1/p' scripts/build-media3ext.sh)".aar > /dev/null 2>&1; then
    note "the FFmpeg extension is not built yet: about an hour"
  fi
  [ -d "$sdk/ndk/25.2.9519653" ] || problem "NDK 25.2.9519653 is not installed (sdkmanager \"ndk;25.2.9519653\")"
  [ -d "$sdk/cmake/3.22.1" ] || problem "CMake 3.22.1 is not installed (sdkmanager \"cmake;3.22.1\")"
fi
for t in python3 sha256sum nasm meson ninja pkg-config; do
  command -v "$t" > /dev/null || problem "$t is not on PATH"
done

# --- Linux packages ---
natives="${TMPLAYER_LINUX_NATIVES:-$HOME/.cache/tmplayer-natives}"
appimagetool="$HOME/.cache/tmplayer/appimagetool-1.9.1"
appimagetool_sha="ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0"
if [ "$linux" = 1 ]; then
  pins="$(mktemp)"
  awk '!/^#/ && NF { print $1 "  " $2 }' natives/linux-2-35/PINS.txt | sort -u > "$pins"
  if ! (cd "$natives" 2> /dev/null && sha256sum --quiet -c "$pins" > /dev/null 2>&1); then
    problem "the glibc 2.35 natives in $natives do not match natives/linux-2-35/PINS.txt: build them (scripts/build-tdlib-linux.sh, scripts/build-mpv-linux-2-35.sh) or set TMPLAYER_LINUX_NATIVES"
  fi
  rm -f "$pins"
  for t in zsyncmake podman objcopy curl desktop-file-validate appstreamcli xz; do
    command -v "$t" > /dev/null || problem "$t is not on PATH (needed for the Linux packages)"
  done
  [ -x "$appimagetool" ] || note "appimagetool 1.9.1 is fetched into $appimagetool, checked by SHA-256"
fi

# --- Room to work ---
avail_gb="$(df -Pk "$root" | awk 'NR == 2 { print int($4 / 1048576) }')"
[ "$avail_gb" -ge 8 ] || problem "only ${avail_gb} GB free on this disk; the build needs about 8 GB"
mem_gb="$(awk '/^MemAvailable:/ { print int($2 / 1048576) }' /proc/meminfo)"
[ "$mem_gb" -ge 6 ] || note "only ${mem_gb} GB of memory available: close other builds or set GRADLE_WORKERS=2"

# --- What GitHub already has ---
draft_exists=0
if command -v gh > /dev/null && gh auth status > /dev/null 2>&1; then
  repo="$(gh repo view --json nameWithOwner -q .nameWithOwner 2> /dev/null || echo dracu-lah/TMPlayer)"
  if gh api "repos/$repo/releases/tags/$tag" > /dev/null 2>&1; then
    problem "$tag is already released on GitHub"
  fi
  if [ -n "$(gh api --paginate "repos/$repo/releases?per_page=100" --jq ".[] | select(.draft and .tag_name == \"$draft\") | .id" 2> /dev/null)" ]; then
    draft_exists=1
    note "the draft $draft exists already: the upload replaces its files"
  fi
else
  note "gh is not signed in: not checked whether $tag or the draft $draft exist"
fi

echo "Release $tag: version $v, versionCode $code, $([ "$prerelease" = true ] && echo "a test version" || echo "a release")"
echo "Commit $commit"
echo "Builds into $out/:"
echo "  TMPlayer-$v-universal.apk, mapping-$v.txt"
[ "$linux" = 1 ] && echo "  TMPlayer-$v-x86_64.AppImage, its .zsync, TMPlayer-$v-linux-x64.tar.xz"
echo "  BUILD-INFO.txt, SHA256SUMS"
echo "Steps: $([ "${SKIP_TESTS:-}" = 1 ] && echo "unit tests skipped (SKIP_TESTS=1)" || echo "unit tests"), FFmpeg extension, signed APK, 16 KB check, signer check$([ "$linux" = 1 ] && echo ", desktop tests, Linux packages and their self-tests (here and on Ubuntu 22.04 in podman)")"
for n in ${notes[@]+"${notes[@]}"}; do echo "Note: $n"; done
for p in ${problems[@]+"${problems[@]}"}; do echo "Problem: $p" >&2; done

if [ ${#problems[@]} -gt 0 ]; then
  echo "${#problems[@]} problem(s): fix them before building." >&2
  exit 1
fi
if [ "$dry" = 1 ]; then
  echo "Dry run: every check passed, nothing was built."
  exit 0
fi

# --- Build ---
gradle_flags=()
[ -n "${GRADLE_WORKERS:-}" ] && gradle_flags+=("--max-workers=$GRADLE_WORKERS")
# The earlier output of this tag stays until this build is whole: a failed rerun puts it back.
[ -n "$out" ] || exit 2
if [ -d "$out" ]; then rm -rf "$out.prev"; mv "$out" "$out.prev"; fi
restore_previous() { if [ "$?" -ne 0 ] && [ -d "$out.prev" ]; then rm -rf "$out"; mv "$out.prev" "$out"; fi; }
trap restore_previous EXIT
mkdir -p "$out"

tests=skipped
if [ "${SKIP_TESTS:-}" != 1 ]; then
  ./gradlew ${gradle_flags[@]+"${gradle_flags[@]}"} :core:jvmTest :ui:jvmTest :app:testDebugUnitTest
  tests=run
fi

JOBS="${JOBS:-4}" scripts/build-media3ext.sh

# No earlier build's APK or mapping can be picked up by mistake.
rm -rf app/build/outputs/apk/release app/build/outputs/mapping/release
SENTRY_AUTH_TOKEN="${SENTRY_AUTH_TOKEN:-}" ./gradlew ${gradle_flags[@]+"${gradle_flags[@]}"} :app:assembleRelease \
  -PtmRequireMedia3ext=true -PtmVersionName="$v" -PtmVersionCode="$code"
apk=app/build/outputs/apk/release/app-universal-release.apk
[ -f "$apk" ] || { echo "$apk was not built. Check the splits block in app/build.gradle.kts" >&2; exit 1; }
scripts/check-16kb.sh "$apk"
scripts/verify-apk-signer.sh "$apk" "$v" "$code"
cp "$apk" "$out/TMPlayer-$v-universal.apk"
cp app/build/outputs/mapping/release/mapping.txt "$out/mapping-$v.txt"

platforms=android
if [ "$linux" = 1 ]; then
  # The steps of .github/actions/desktop-linux, in its order.
  export TMPLAYER_LINUX_NATIVES="$natives"
  [ "${SKIP_TESTS:-}" = 1 ] || ./gradlew ${gradle_flags[@]+"${gradle_flags[@]}"} :desktop:test
  ./gradlew ${gradle_flags[@]+"${gradle_flags[@]}"} :desktop:createReleaseDistributable -PdesktopVersion="$v"
  app=desktop/build/compose/binaries/main-release/app/TMPlayer
  "$app/bin/TMPlayer" --self-test
  desktop/packaging/linux/glibc-floor.sh "$app"
  if [ ! -x "$appimagetool" ]; then
    mkdir -p "$(dirname "$appimagetool")"
    curl -sSfL -o "$appimagetool.part" https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage
    echo "$appimagetool_sha  $appimagetool.part" | sha256sum -c --status || { rm -f "$appimagetool.part"; echo "appimagetool does not match its SHA-256" >&2; exit 1; }
    chmod +x "$appimagetool.part"
    mv "$appimagetool.part" "$appimagetool"
  fi
  APPIMAGETOOL="$appimagetool" OUT="$root/$out" desktop/packaging/linux/package.sh "$v"
  scratch="$(mktemp -d)"
  tar -C "$scratch" -xJf "$out/TMPlayer-$v-linux-x64.tar.xz"
  "$scratch/TMPlayer-$v/bin/TMPlayer" --self-test
  rm -rf "$scratch"
  APPIMAGE_EXTRACT_AND_RUN=1 "$out/TMPlayer-$v-x86_64.AppImage" --self-test
  CONTAINER_ENGINE=podman .github/actions/desktop-linux/plain-linux-check.sh \
    "$out/TMPlayer-$v-x86_64.AppImage" "$out/TMPlayer-$v-linux-x64.tar.xz"
  image="$out/TMPlayer-$v-x86_64.AppImage"
  [ -s "$image.zsync" ] || { echo "$image.zsync was not written" >&2; exit 1; }
  grep -q "^Filename: TMPlayer-$v-x86_64.AppImage$" "$image.zsync" || { echo "$image.zsync does not name the AppImage" >&2; exit 1; }
  info="$(objcopy -O binary --only-section=.upd_info "$image" /dev/stdout | tr -d '\0')"
  channel=latest
  case "$v" in *-*) channel=latest-all ;; esac
  [ "$info" = "gh-releases-zsync|dracu-lah|TMPlayer|$channel|TMPlayer-*-x86_64.AppImage.zsync" ] \
    || { echo "the AppImage carries update information '$info'" >&2; exit 1; }
  platforms=android,linux
fi

cat > "$out/BUILD-INFO.txt" <<EOF
tag=$tag
version=$v
code=$code
commit=$commit
platforms=$platforms
tests=$tests
built=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
(cd "$out" && find . -maxdepth 1 -type f ! -name SHA256SUMS -printf '%f\n' | sort | xargs sha256sum > SHA256SUMS)
ls -la "$out"
rm -rf "$out.prev"

if [ "$draft_exists" = 1 ]; then
  # Not a clobber onto the old draft: files of the other platform from another commit would stay
  # beside the new SHA256SUMS and BUILD-INFO and fail the release's checks. A fresh draft has only
  # this build.
  upload="gh release delete $draft --yes && gh release create $draft --draft --target $commit --title \"Prebuilt $tag (never publish)\" --notes \"Built on the maintainer's machine for $tag at $commit. release.yml takes the files from here. Never publish this draft.\" $root/$out/*"
else
  upload="gh release create $draft --draft --target $commit --title \"Prebuilt $tag (never publish)\" --notes \"Built on the maintainer's machine for $tag at $commit. release.yml takes the files from here. Never publish this draft.\" $root/$out/*"
fi
cat <<EOF

Built. Nothing is uploaded or tagged. Run these yourself, with ! in Claude Code, from $root.
$([ "$prerelease" = true ] || echo "A release, not a test version: run the cadence guard first (docs/RELEASING.md).")

  $upload
      Puts the files in the private draft release $draft. Check: gh release view $draft lists them.
  git tag -a $tag $commit -F notes.txt
      The annotated tag on the commit built here, notes as docs/RELEASING.md says. Check: git show $tag.
  git push origin $tag
      Starts release.yml, which takes the APK$([ "$linux" = 1 ] && echo " and the Linux packages") from the draft once they verify. Check: the android job says "Using the prebuilt android files".
EOF
