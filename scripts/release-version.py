#!/usr/bin/env python3
"""The release numbering for the release workflow: a tag in, its version, kind and codes out.

A release is tagged vX.Y.Z; a test version of it, published as a GitHub pre-release, vX.Y.Z-beta.N
(N from 1 to 98). Each gets a stage, N for a test version and 99 for the release, so every test
version of X.Y.Z sorts after X.Y.(Z-1) and before X.Y.Z (docs/RELEASING.md):

    Android versionCode   major*1000000 + minor*10000 + patch*100 + stage   1.28.2-beta.1 = 1280201
    installer version     major.minor.(patch*100 + stage)                   1.28.2-beta.1 = 1.28.201

The same rules live in ReleaseVersion (core), app/build.gradle.kts and desktop/build.gradle.kts.
The table core/src/jvmTest/resources/release-versions.txt holds them together: ReleaseVersionTest
reads it, and so does --check here, which the workflow runs before it trusts this script.

    scripts/release-version.py v1.28.2-beta.1            # name=, code=, prerelease=, installer=
    scripts/release-version.py --github-output v1.28.2   # the same, appended to $GITHUB_OUTPUT
    scripts/release-version.py --check                   # every case in the shared table
"""

import argparse
import os
import re
import sys

FINAL_STAGE = 99
MAX_BETA = 98
MAX_CODE = 2_100_000_000  # Google Play's ceiling, below 2**31 - 1

TABLE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "core", "src", "jvmTest", "resources", "release-versions.txt")

# What may be tagged: a release or a test version of one, and nothing else.
TAG = re.compile(r"v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-beta\.([1-9]\d?))?")


def parse(version):
    """(major, minor, patch, stage) for any version string, or None. A suffix other than a valid
    -beta.N (-dev, -ci.N, -test.N) is stage 0, below every test version."""
    clean = version.strip()
    if clean[:1] in ("v", "V"):
        clean = clean[1:]
    clean = clean.split("+", 1)[0]
    core, _, suffix = clean.partition("-")
    fields = core.split(".")
    if len(fields) != 3 or not all(f.isdigit() for f in fields):
        return None
    major, minor, patch = (int(f) for f in fields)
    if not suffix:
        stage = FINAL_STAGE
    else:
        m = re.fullmatch(r"beta\.(\d+)", suffix)
        stage = int(m.group(1)) if m and 1 <= int(m.group(1)) <= MAX_BETA else 0
    return major, minor, patch, stage


def android_code(version):
    parts = parse(version)
    if parts is None:
        return None
    major, minor, patch, stage = parts
    if major < 1 or minor > 99 or patch > 99:
        return None
    code = major * 1_000_000 + minor * 10_000 + patch * 100 + stage
    return code if code <= MAX_CODE else None


def installer_version(version):
    parts = parse(version)
    if parts is None:
        return None
    major, minor, patch, stage = parts
    third = patch * 100 + stage
    if major > 255 or minor > 255 or third > 65535:
        return None
    return f"{major}.{minor}.{third}"


def check():
    failures = 0
    count = 0
    with open(TABLE) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            version, code, installer = line.split()
            want_code = None if code == "-" else int(code)
            want_installer = None if installer == "-" else installer
            got_code, got_installer = android_code(version), installer_version(version)
            count += 1
            if got_code != want_code or got_installer != want_installer:
                failures += 1
                print(f"{version}: code {got_code} (table {want_code}), installer {got_installer} (table {want_installer})")
    print(f"{count} cases, {failures} wrong")
    return failures == 0 and count > 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("tag", nargs="?", help="vX.Y.Z or vX.Y.Z-beta.N")
    parser.add_argument("--github-output", action="store_true", help="append to $GITHUB_OUTPUT as well")
    parser.add_argument("--check", action="store_true", help="check every case in the shared table")
    args = parser.parse_args()

    if args.check:
        sys.exit(0 if check() else 1)
    if not args.tag:
        parser.error("a tag, or --check")

    match = TAG.fullmatch(args.tag)
    if not match or (match.group(4) and int(match.group(4)) > MAX_BETA):
        sys.exit(f"Tag '{args.tag}' is not vMAJOR.MINOR.PATCH or vMAJOR.MINOR.PATCH-beta.N (N from 1 to {MAX_BETA})")
    name = args.tag[1:] if args.tag.startswith("v") else args.tag
    code = android_code(name)
    installer = installer_version(name)
    if code is None or installer is None:
        sys.exit(f"{name} does not fit the numbering: minor and patch go up to 99 (docs/RELEASING.md)")
    out = {
        "name": name,
        "code": str(code),
        "prerelease": "true" if match.group(4) else "false",
        "installer": installer,
    }
    lines = "".join(f"{k}={v}\n" for k, v in out.items())
    sys.stdout.write(lines)
    if args.github_output:
        with open(os.environ["GITHUB_OUTPUT"], "a") as f:
            f.write(lines)


if __name__ == "__main__":
    main()
