#!/usr/bin/env bash
# Finds and checks the files the maintainer built on their own machine for a release tag
# (scripts/release-local.sh), for release.yml's android and desktop-linux jobs, which hand them to
# the publish job instead of building their own. docs/RELEASING.md, "Building the release on your
# machine", has the steps.
#
#   scripts/release-prebuilt.sh <tag> android|linux <dir>
#
# The files wait in a draft GitHub release whose tag is prebuilt-<tag> (prebuilt-v1.28.6 for
# v1.28.6). It is never published, so no git tag of that name is ever made, and its name differs
# from the release's own, so the publish job's release is made fresh and never picks up the draft's
# R8 mapping or checksum list. Only people with push access see a draft.
#
# The draft holds SHA256SUMS, BUILD-INFO.txt, and for each platform it covers:
#   android  TMPlayer-<v>-universal.apk, mapping-<v>.txt
#   linux    TMPlayer-<v>-x86_64.AppImage, TMPlayer-<v>-x86_64.AppImage.zsync,
#            TMPlayer-<v>-linux-x64.tar.xz
#
# Exit 0: the platform's files are there and pass every check, and are put where the job's own
#         build would have put them: <dir>/dist/ for the release files, <dir>/mapping/ for the
#         mapping.
# Exit 3: no draft, or a draft without any of this platform's files: build as before.
# Other:  the draft is there but something is wrong (a file missing, a checksum, another commit or
#         version, the APK's signer): the release stops rather than publish it or quietly rebuild.
#
# Checks: every file against SHA256SUMS; BUILD-INFO.txt names this tag, version, versionCode and
# the commit checked out here (EXPECT_COMMIT overrides `git rev-parse HEAD`); for the APK,
# scripts/verify-apk-signer.sh (the release key's certificate, one signer, com.tmplayer at this
# version) and scripts/check-16kb.sh. The Linux job runs the plain Ubuntu 22.04 self-test on the
# packages after this. Needs gh with GH_TOKEN, and GITHUB_REPOSITORY (or REPO).
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
tag="${1:-}"
platform="${2:-}"
dir="${3:-}"
[ -n "$tag" ] && [ -n "$dir" ] || { echo "usage: $0 <tag> android|linux <dir>" >&2; exit 2; }
repo="${REPO:-${GITHUB_REPOSITORY:-}}"
[ -n "$repo" ] || { echo "set GITHUB_REPOSITORY or REPO (owner/name)" >&2; exit 2; }

version_info="$(python3 "$root/scripts/release-version.py" "$tag")"
v="$(sed -n 's/^name=//p' <<<"$version_info")"
code="$(sed -n 's/^code=//p' <<<"$version_info")"

case "$platform" in
  android) files=("TMPlayer-$v-universal.apk" "mapping-$v.txt") ;;
  linux) files=("TMPlayer-$v-x86_64.AppImage" "TMPlayer-$v-x86_64.AppImage.zsync" "TMPlayer-$v-linux-x64.tar.xz") ;;
  *) echo "platform must be android or linux, not '$platform'" >&2; exit 2 ;;
esac

draft_tag="prebuilt-$tag"
# A read that fails (a 5xx, a rate limit) is tried three times; when it keeps failing nothing was
# verified, so nothing is trusted and the job builds as usual (exit 3), saying why.
gh_read() {
  local try
  for try in 1 2 3; do
    gh api --paginate "$@" && return 0
    sleep $((try * 5))
  done
  echo "::warning::GitHub did not answer for the draft $draft_tag after 3 tries: $platform builds here as usual." >&2
  exit 3
}
ids="$(gh_read "repos/$repo/releases?per_page=100" \
  --jq ".[] | select(.draft and .tag_name == \"$draft_tag\") | .id")"
if [ -z "$ids" ]; then
  echo "No draft release $draft_tag: $platform builds here as usual."
  exit 3
fi
if [ "$(wc -l <<<"$ids")" != "1" ]; then
  echo "More than one draft release is named $draft_tag; delete all but one." >&2
  exit 1
fi

assets="$(gh_read "repos/$repo/releases/$ids/assets?per_page=100" --jq '.[] | "\(.id) \(.name)"')"
asset_id() { awk -v n="$1" '$2 == n { print $1 }' <<<"$assets"; }

present=0
for f in "${files[@]}"; do [ -n "$(asset_id "$f")" ] && present=$((present + 1)); done
if [ "$present" -eq 0 ]; then
  echo "The draft $draft_tag carries no $platform files: $platform builds here as usual."
  exit 3
fi
for f in "${files[@]}" SHA256SUMS BUILD-INFO.txt; do
  [ -n "$(asset_id "$f")" ] || { echo "The draft $draft_tag has some $platform files but not $f." >&2; exit 1; }
done

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
for f in "${files[@]}" SHA256SUMS BUILD-INFO.txt; do
  gh api -H "Accept: application/octet-stream" "repos/$repo/releases/assets/$(asset_id "$f")" > "$work/$f"
done

# Every file this job hands on, and BUILD-INFO.txt, must have its line in SHA256SUMS and match it.
for f in "${files[@]}" BUILD-INFO.txt; do
  want="$(awk -v n="$f" '$2 == n || $2 == "*" n { print $1 }' "$work/SHA256SUMS")"
  [ -n "$want" ] || { echo "SHA256SUMS has no line for $f" >&2; exit 1; }
  got="$(sha256sum "$work/$f" | awk '{ print $1 }')"
  [ "$got" = "$want" ] || { echo "$f does not match SHA256SUMS ($got, expected $want)" >&2; exit 1; }
done
echo "Checksums match for ${files[*]} and BUILD-INFO.txt"

info() { sed -n "s/^$1=//p" "$work/BUILD-INFO.txt" | head -n1; }
expect_commit="${EXPECT_COMMIT:-$(git -C "$root" rev-parse HEAD)}"
[ "$(info tag)" = "$tag" ] || { echo "BUILD-INFO.txt is for tag '$(info tag)', not $tag" >&2; exit 1; }
[ "$(info version)" = "$v" ] || { echo "BUILD-INFO.txt is for version '$(info version)', not $v" >&2; exit 1; }
[ "$(info code)" = "$code" ] || { echo "BUILD-INFO.txt is for versionCode '$(info code)', not $code" >&2; exit 1; }
if [ "$(info commit)" != "$expect_commit" ]; then
  echo "The prebuilt files were built from commit '$(info commit)', but $tag is $expect_commit. Rebuild at the tagged commit, or delete the draft $draft_tag to build here." >&2
  exit 1
fi
echo "Built from $expect_commit on $(info built), unit tests $(info tests)"

if [ "$platform" = android ]; then
  "$root/scripts/verify-apk-signer.sh" "$work/TMPlayer-$v-universal.apk" "$v" "$code"
  "$root/scripts/check-16kb.sh" "$work/TMPlayer-$v-universal.apk"
  mkdir -p "$dir/dist" "$dir/mapping"
  mv "$work/TMPlayer-$v-universal.apk" "$dir/dist/"
  mv "$work/mapping-$v.txt" "$dir/mapping/"
else
  mkdir -p "$dir/dist"
  for f in "${files[@]}"; do mv "$work/$f" "$dir/dist/"; done
  chmod +x "$dir/dist/TMPlayer-$v-x86_64.AppImage"
fi
echo "Using the prebuilt $platform files from the draft $draft_tag"
