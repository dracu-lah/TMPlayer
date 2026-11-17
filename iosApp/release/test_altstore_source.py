"""Tests for altstore_source.py. Run from the repository root:

    python3 -I -m unittest discover -s iosApp/release
"""

import contextlib
import io
import json
import os
import plistlib
import re
import shutil
import tempfile
import unittest
import zipfile

import altstore_source

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
BUNDLE = "org.example.player"


def make_ipa(folder, version="1.0.0", build="1", bundle=BUNDLE, extra=None, name="Player.ipa"):
    info = {
        "CFBundleIdentifier": bundle,
        "CFBundleShortVersionString": version,
        "CFBundleVersion": build,
        "MinimumOSVersion": "26.4",
        "NSPhotoLibraryAddUsageDescription": "Saves the videos you choose to Photos.",
        "UIBackgroundModes": ["audio"],
    }
    info.update(extra or {})
    path = os.path.join(folder, name)
    with zipfile.ZipFile(path, "w") as ipa:
        ipa.writestr("Payload/Player.app/Info.plist", plistlib.dumps(info, fmt=plistlib.FMT_BINARY))
        ipa.writestr("Payload/Player.app/Player", b"\0" * 64)
    return path


def make_source(folder):
    source = {
        "name": "Player",
        "apps": [{
            "name": "Player",
            "bundleIdentifier": BUNDLE,
            "localizedDescription": "Plays videos.",
            "category": "entertainment",
            "versions": [],
            "appPermissions": {"entitlements": [], "privacy": {}},
        }],
        "news": [],
    }
    path = os.path.join(folder, "source.json")
    with open(path, "w", encoding="utf-8") as file:
        json.dump(source, file)
    return path


def run(*argv):
    out = io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
        altstore_source.main(list(argv))
    return out.getvalue()


class AddTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.folder)
        self.source = make_source(self.folder)

    def add(self, ipa, *more):
        run("add", "--source", self.source, "--ipa", ipa, "--download-url", "https://example.org/" + os.path.basename(ipa),
            "--date", "2026-12-01T10:00:00Z", *more)
        with open(self.source, encoding="utf-8") as file:
            return json.load(file)

    def test_a_version_goes_on_top_with_the_ipas_permissions(self):
        self.add(make_ipa(self.folder, "1.0.0", "1"))
        notes = os.path.join(self.folder, "notes.md")
        with open(notes, "w", encoding="utf-8") as file:
            file.write("- Faster starts.\n")
        source = self.add(make_ipa(self.folder, "1.1.0", "2", name="Next.ipa"), "--notes-file", notes,
                          "--repo", "owner/TMPlayer", "--developer", "owner")
        app = source["apps"][0]
        self.assertEqual(["1.1.0", "1.0.0"], [v["version"] for v in app["versions"]], "newest first")
        latest = app["versions"][0]
        self.assertEqual("2", latest["buildVersion"])
        self.assertEqual("26.4", latest["minOSVersion"])
        self.assertEqual("- Faster starts.", latest["localizedDescription"])
        self.assertEqual(os.path.getsize(os.path.join(self.folder, "Next.ipa")), latest["size"])
        self.assertEqual({"entitlements": [], "privacy": {"NSPhotoLibraryAddUsageDescription": "Saves the videos you choose to Photos."}},
                         app["appPermissions"], "usage descriptions only, as the IPA has them")
        self.assertEqual(("1.1.0", latest["downloadURL"], latest["size"]), (app["version"], app["downloadURL"], app["size"]),
                         "the latest version also at the app's level, for older AltStore and SideStore")
        self.assertEqual("https://raw.githubusercontent.com/owner/TMPlayer/main/altstore/source.json", source["sourceURL"])
        self.assertTrue(app["iconURL"].endswith("/AppIcon.png"))
        self.assertEqual("owner", app["developerName"])
        self.assertEqual(f"{BUNDLE}.source", source["identifier"])

    def test_the_same_version_made_again_replaces_its_entry(self):
        self.add(make_ipa(self.folder, "1.0.0", "1"))
        source = self.add(make_ipa(self.folder, "1.0.0", "7"))
        versions = source["apps"][0]["versions"]
        self.assertEqual(1, len(versions))
        self.assertEqual("7", versions[0]["buildVersion"])

    def test_an_ipa_of_another_app_is_refused(self):
        with self.assertRaises(SystemExit) as refused:
            self.add(make_ipa(self.folder, bundle="org.example.other"))
        self.assertIn("org.example.other", str(refused.exception))

    def test_a_tag_and_an_ipa_that_disagree_are_refused(self):
        with self.assertRaises(SystemExit) as refused:
            self.add(make_ipa(self.folder, "1.0.0"), "--version", "1.0.1")
        self.assertIn("1.0.1", str(refused.exception))

    def test_a_tag_and_an_ipa_of_another_build_are_refused(self):
        with self.assertRaises(SystemExit) as refused:
            self.add(make_ipa(self.folder, "1.0.0", "1"), "--version", "1.0.0", "--build", "2")
        self.assertIn("build 2", str(refused.exception))

    def test_a_file_that_is_not_an_ipa_is_refused(self):
        bad = os.path.join(self.folder, "bad.ipa")
        with open(bad, "wb") as file:
            file.write(b"not a zip")
        with self.assertRaises(SystemExit):
            self.add(bad)

    def test_the_result_passes_the_published_check(self):
        self.add(make_ipa(self.folder), "--repo", "owner/name", "--developer", "owner")
        self.assertIn("in order", run("check", "--source", self.source, "--published"))


class NotesTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.folder)
        self.changelog = os.path.join(self.folder, "CHANGELOG.md")
        with open(self.changelog, "w", encoding="utf-8") as file:
            file.write("# Changes\n\n<!--\n## 9.9.9\n- not this\n-->\n\n## 1.1.0 (2026-12-02)\n\n- Two.\n- Three.\n\n"
                       "## 1.0.0 build 2 (2026-12-01)\n\n- Fix.\n\n## 1.0.0\n\n- One.\n\n## 1.0.0.5\n\n- Not one.\n")

    def notes(self, version, build="1"):
        return run("notes", "--changelog", self.changelog, "--version", version, "--build", build).strip()

    def test_a_section_is_its_lines_up_to_the_next(self):
        self.assertEqual("- Two.\n- Three.", self.notes("1.1.0"))
        self.assertEqual("- One.", self.notes("1.0.0"))

    def test_a_later_build_has_its_own_section(self):
        self.assertEqual("- Fix.", self.notes("1.0.0", "2"))
        for build in ("3", "0", "two"):
            with self.assertRaises(SystemExit):
                self.notes("1.0.0", build)

    def test_a_missing_or_commented_section_stops_the_release(self):
        for version in ("2.0.0", "9.9.9"):
            with self.assertRaises(SystemExit):
                self.notes(version)


# The one identity the iOS app has (docs/IOS.md, "Identifiers"): AltStore and SideStore update an
# installed app by its bundle identifier, so every place that names it must name the same one.
APP_ID = "org.tmplayer.ios"


def read(*parts):
    with open(os.path.join(ROOT, *parts), encoding="utf-8") as file:
        return file.read()


def project_settings():
    """project.yml's base MARKETING_VERSION and CURRENT_PROJECT_VERSION."""
    text = read("iosApp", "project.yml")
    found = dict(re.findall(r'^    (MARKETING_VERSION|CURRENT_PROJECT_VERSION): "([^"]+)"$', text, re.M))
    return found.get("MARKETING_VERSION"), found.get("CURRENT_PROJECT_VERSION")


class RepositoryTest(unittest.TestCase):
    def test_the_source_matches_the_xcode_project(self):
        output = run("check", "--source", os.path.join(ROOT, "altstore", "source.json"),
                     "--project", os.path.join(ROOT, "iosApp", "project.yml"))
        self.assertIn("in order", output)

    def test_a_source_without_versions_is_not_ready_to_publish(self):
        with open(os.path.join(ROOT, "altstore", "source.json"), encoding="utf-8") as f:
            source = json.load(f)
        for app in source["apps"]:
            app["versions"] = []
        with tempfile.TemporaryDirectory() as folder:
            path = os.path.join(folder, "source.json")
            with open(path, "w", encoding="utf-8") as f:
                json.dump(source, f)
            with self.assertRaises(SystemExit):
                run("check", "--source", path, "--published")

    def test_the_published_check_passes_once_a_version_is_listed(self):
        # The repository's source is a template until the first apple-v release adds a version:
        # until then the published check refuses it, afterwards it must pass.
        path = os.path.join(ROOT, "altstore", "source.json")
        with open(path, encoding="utf-8") as f:
            source = json.load(f)
        if any(app.get("versions") for app in source["apps"]):
            run("check", "--source", path, "--published")
        else:
            with self.assertRaises(SystemExit):
                run("check", "--source", path, "--published")

    def test_one_bundle_identifier_everywhere(self):
        with open(os.path.join(ROOT, "altstore", "source.json"), encoding="utf-8") as f:
            source = json.load(f)
        self.assertEqual([APP_ID], [app["bundleIdentifier"] for app in source["apps"]])
        self.assertEqual(f"{APP_ID}.source", source["identifier"])
        self.assertEqual([APP_ID], source["featuredApps"])
        self.assertEqual(APP_ID, altstore_source.project_bundle(os.path.join(ROOT, "iosApp", "project.yml")))
        expected = sorted([APP_ID, APP_ID, f"{APP_ID}.tests", f"{APP_ID}.tests", f"{APP_ID}.uitests", f"{APP_ID}.uitests"])
        pbxproj = re.findall(r"PRODUCT_BUNDLE_IDENTIFIER = ([^;]+);", read("iosApp", "TMPlayer.xcodeproj", "project.pbxproj"))
        self.assertEqual(expected, sorted(pbxproj), "the committed Xcode project, Debug and Release")
        yml = re.findall(r"PRODUCT_BUNDLE_IDENTIFIER: (\S+)", read("iosApp", "project.yml"))
        self.assertEqual(sorted([APP_ID, f"{APP_ID}.tests", f"{APP_ID}.uitests"]), sorted(yml))
        self.assertIn(f'const val APPLICATION_ID = "{APP_ID}"', read("ios", "build.gradle.kts"))
        self.assertIn(f'?: "{APP_ID}"', read("ui", "src", "iosStandIns", "kotlin", "android", "content", "Content.kt"))

    def test_the_versions_agree(self):
        version, build = project_settings()
        self.assertRegex(version or "", r"^[0-9]+\.[0-9]+(\.[0-9]+)?$")
        self.assertRegex(build or "", r"^[1-9][0-9]*$")
        pbxproj = read("iosApp", "TMPlayer.xcodeproj", "project.pbxproj")
        self.assertEqual({version}, set(re.findall(r"MARKETING_VERSION = ([^;]+);", pbxproj)))
        self.assertEqual({build}, set(re.findall(r"CURRENT_PROJECT_VERSION = ([^;]+);", pbxproj)))
        info = read("iosApp", "TMPlayer", "Info.plist")
        self.assertIn("<string>$(MARKETING_VERSION)</string>", info)
        self.assertIn("<string>$(CURRENT_PROJECT_VERSION)</string>", info)

    def test_the_minimum_ios_agrees(self):
        yml = read("iosApp", "project.yml")
        minimum = re.search(r'^    iOS: "([0-9.]+)"$', yml, re.M).group(1)
        pbxproj = read("iosApp", "TMPlayer.xcodeproj", "project.pbxproj")
        self.assertEqual({minimum}, set(re.findall(r"IPHONEOS_DEPLOYMENT_TARGET = ([^;]+);", pbxproj)))

    def test_no_secret_is_committed(self):
        # Telegram's api id and hash and the service keys come from local.properties or the
        # release's secrets, never from a committed file.
        for parts in (("ios", "build.gradle.kts"), ("iosApp", "project.yml"), ("iosApp", "TMPlayer", "Info.plist"),
                      (".github", "workflows", "apple-release.yml")):
            text = read(*parts)
            self.assertIsNone(re.search(r"\b[0-9a-f]{32}\b", text), "/".join(parts))
            self.assertIsNone(re.search(r"TG_API_ID\s*=\s*[0-9]+", text), "/".join(parts))


if __name__ == "__main__":
    unittest.main()
