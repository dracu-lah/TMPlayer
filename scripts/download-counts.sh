#!/bin/sh
# Prints how many times each release has been downloaded.
#
# For an app that is sideloaded rather than installed from a store, this is the only install figure
# that exists, and it is the one that matters: it counts people who went as far as fetching the APK.
# GitHub keeps the count on every release asset and serves it publicly, so nothing is added to the
# app or to the site to produce it. No analytics, no server, no promise broken.
#
# Note what it is not. It counts downloads, not installs and not users: a mirror, a crawler or one
# person on three televisions all look the same from here, and the count never goes down when
# somebody uninstalls. Read it as a trend rather than as a headcount.
#
# Needs the GitHub CLI, signed in:
#
#   gh auth login
#   scripts/download-counts.sh
#
# Unauthenticated works too, at 60 requests an hour, if you would rather not sign in:
#
#   curl -s https://api.github.com/repos/dracu-lah/TMPlayer/releases?per_page=100

set -eu

REPO="${TMPLAYER_REPO:-dracu-lah/TMPlayer}"

command -v gh >/dev/null 2>&1 || {
  echo "The GitHub CLI is not installed. See https://cli.github.com" >&2
  exit 1
}

gh api "repos/$REPO/releases?per_page=100" --paginate --jq '
  [ .[]
    | select(.draft | not)
    | { tag: .tag_name,
        date: (.published_at // .created_at | split("T")[0]),
        prerelease: .prerelease,
        assets: [ .assets[] | select(.name | endswith(".apk")) ],
        total: ([ .assets[] | select(.name | endswith(".apk")) | .download_count ] | add // 0) }
  ] as $releases
  | ($releases | map(.total) | add // 0) as $grand
  | ( $releases[]
      | "\(.tag)\t\(.date)\t\(.total)\t\(if .prerelease then "pre-release" else "" end)",
        ( .assets[] | "  \(.name)\t\t\(.download_count)\t" ) ),
    "",
    "TOTAL\t\t\($grand)\t"
' | awk -F'\t' '
  BEGIN {
    printf "%-34s %-14s %10s  %s\n", "RELEASE", "PUBLISHED", "DOWNLOADS", ""
    printf "%-34s %-14s %10s  %s\n", "----------------------------------", \
      "--------------", "----------", ""
  }
  { printf "%-34s %-14s %10s  %s\n", $1, $2, $3, $4 }
'
