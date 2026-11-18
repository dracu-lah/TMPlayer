"""Tests for altstore_source.py. Run from the repository root:

    python3 -I -m unittest discover -s iosApp/release
"""

import contextlib
import hashlib
import io
import json
import os
import plistlib
import re
import shutil
import struct
import subprocess
import tempfile
import unittest
import zipfile

import altstore_source

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
BUNDLE = "org.example.player"
RELEASE = "https://github.com/owner/TMPlayer/releases/download/apple-v1.0.0/"


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


def make_png(path, width, height):
    """A file with a PNG's signature and IHDR, which is all the script reads."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as file:
        file.write(b"\x89PNG\r\n\x1a\n" + struct.pack(">I", 13) + b"IHDR" + struct.pack(">II", width, height) + b"\x08\x06\0\0\0")


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
        run("add", "--source", self.source, "--ipa", ipa, "--download-url", RELEASE + os.path.basename(ipa),
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

    def test_the_same_version_and_build_made_again_replaces_its_entry(self):
        self.add(make_ipa(self.folder, "1.0.0", "7"))
        source = self.add(make_ipa(self.folder, "1.0.0", "7", extra={"CFBundleName": "again"}))
        versions = source["apps"][0]["versions"]
        self.assertEqual(1, len(versions))
        self.assertEqual("7", versions[0]["buildVersion"])

    def test_another_build_of_a_version_is_an_entry_of_its_own_and_old_ones_stay(self):
        self.add(make_ipa(self.folder, "1.0.0", "1"))
        self.add(make_ipa(self.folder, "1.0.0", "2", name="b2.ipa"))
        source = self.add(make_ipa(self.folder, "1.1.0", "1", name="next.ipa"))
        shown = [(v["version"], v["buildVersion"]) for v in source["apps"][0]["versions"]]
        self.assertEqual([("1.1.0", "1"), ("1.0.0", "2"), ("1.0.0", "1")], shown)

    def test_an_older_version_added_late_does_not_go_on_top(self):
        self.add(make_ipa(self.folder, "1.10.0", "1"))
        source = self.add(make_ipa(self.folder, "1.9.0", "1", name="old.ipa"))
        app = source["apps"][0]
        self.assertEqual(["1.10.0", "1.9.0"], [v["version"] for v in app["versions"]], "10 is newer than 9")
        self.assertEqual("1.10.0", app["version"], "the app's own fields follow the newest")
        self.assertIn("in order", run("check", "--source", self.source))

    def test_the_same_ipa_again_changes_nothing(self):
        ipa = make_ipa(self.folder, "1.0.0", "1")
        first = self.add(ipa)
        with open(self.source, "rb") as file:
            before = file.read()
        run("add", "--source", self.source, "--ipa", ipa, "--download-url", RELEASE + "Player.ipa")
        with open(self.source, "rb") as file:
            self.assertEqual(before, file.read(), "the entry keeps its date: not a new commit for the same release")
        self.assertEqual(first, self.add(ipa))

    def test_the_entry_carries_the_ipas_sha256_size_and_minimum_ios(self):
        ipa = make_ipa(self.folder, "1.0.0", "1")
        with open(ipa, "rb") as file:
            expected = hashlib.sha256(file.read()).hexdigest()
        entry = self.add(ipa)["apps"][0]["versions"][0]
        self.assertEqual(expected, entry["sha256"])
        self.assertEqual("26.4", entry["minOSVersion"])
        self.assertEqual(os.path.getsize(ipa), entry["size"])

    def test_news_with_a_release_when_asked(self):
        notes = os.path.join(self.folder, "notes.md")
        with open(notes, "w", encoding="utf-8") as file:
            file.write("- Faster starts.\n- More.\n")
        source = self.add(make_ipa(self.folder, "1.0.0", "1"), "--notes-file", notes, "--news",
                          "--release-url", RELEASE)
        post = source["news"][0]
        self.assertEqual("release-1-0-0-1", post["identifier"])
        self.assertEqual("Faster starts.", post["caption"])
        self.assertEqual(BUNDLE, post["appID"])
        self.assertTrue(post["notify"])
        again = self.add(make_ipa(self.folder, "1.0.0", "1"), "--notes-file", notes, "--news")
        self.assertEqual(1, len(again["news"]), "the same release posts once")

    def test_screenshots_listed_with_raw_addresses_and_sizes(self):
        make_png(os.path.join(self.folder, "shots", "iphone", "2-player.png"), 1290, 2796)
        make_png(os.path.join(self.folder, "shots", "iphone", "1-home.png"), 1290, 2796)
        make_png(os.path.join(self.folder, "shots", "ipad", "1 home.png"), 2064, 2752)
        with open(os.path.join(self.folder, "shots", "iphone", "notes.txt"), "w") as file:
            file.write("not a picture")
        # The script reads iosApp/screenshots under --root.
        root = os.path.join(self.folder, "root")
        os.makedirs(os.path.join(root, "iosApp"))
        os.rename(os.path.join(self.folder, "shots"), os.path.join(root, "iosApp", "screenshots"))
        # The icon and the source the published check also finds in the checkout.
        for name in (altstore_source.ICON_PATH, altstore_source.SOURCE_PATH):
            os.makedirs(os.path.dirname(os.path.join(root, name)), exist_ok=True)
            with open(os.path.join(root, name), "w") as file:
                file.write("x")
        run("screenshots", "--source", self.source, "--root", root, "--repo", "owner/TMPlayer")
        with open(self.source, encoding="utf-8") as file:
            shots = json.load(file)["apps"][0]["screenshots"]
        base = "https://raw.githubusercontent.com/owner/TMPlayer/main/iosApp/screenshots"
        self.assertEqual([f"{base}/iphone/1-home.png", f"{base}/iphone/2-player.png"], [s["url"] for s in shots["iphone"]])
        self.assertEqual({"url": f"{base}/ipad/1%20home.png", "width": 2064, "height": 2752}, shots["ipad"][0])
        self.assertEqual((1290, 2796), (shots["iphone"][0]["width"], shots["iphone"][0]["height"]))
        # A published check finds each file in the checkout, and refuses one that is gone.
        self.add(make_ipa(self.folder), "--repo", "owner/TMPlayer", "--developer", "owner", "--root", root)
        run("check", "--source", self.source, "--published", "--root", root)
        os.unlink(os.path.join(root, "iosApp", "screenshots", "iphone", "1-home.png"))
        with self.assertRaises(SystemExit) as refused:
            run("check", "--source", self.source, "--published", "--root", root)
        self.assertIn("1-home.png", str(refused.exception))

    def test_screenshots_without_pictures_say_so(self):
        with self.assertRaises(SystemExit):
            run("screenshots", "--source", self.source, "--root", self.folder, "--repo", "owner/TMPlayer")

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
                       "## 1.0.0 build 2 (2026-12-01)\n\n- Fix.\n\n## 1.0.0-beta.2\n\n- Test two.\n\n"
                       "## 1.0.0-beta.1\n\n- Test one.\n\n## 1.0.0\n\n- One.\n\n## 1.0.0.5\n\n- Not one.\n")

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

    def test_a_test_version_has_its_own_section_and_the_release_never_takes_it(self):
        self.assertEqual("- Test two.", self.notes("1.0.0-beta.2"))
        self.assertEqual("- Test one.", self.notes("1.0.0-beta.1"))
        self.assertEqual("- One.", self.notes("1.0.0"))
        with self.assertRaises(SystemExit):
            self.notes("1.0.0-beta.3")

    def test_a_missing_or_commented_section_stops_the_release(self):
        for version in ("2.0.0", "9.9.9"):
            with self.assertRaises(SystemExit):
                self.notes(version)


class NewsTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.folder)
        self.source = make_source(self.folder)

    def post(self, title, *more, date="2026-12-01T10:00:00Z"):
        run("news", "--source", self.source, "--title", title, "--date", date, *more)
        with open(self.source, encoding="utf-8") as file:
            return json.load(file)["news"]

    def test_a_post_has_what_altstore_reads(self):
        posts = self.post("Hello iPad", "--caption", "Now on iPad.", "--url", "https://tmplayer.org/", "--app", BUNDLE,
                          "--image-url", "https://tmplayer.org/a.png", "--notify")
        self.assertEqual({
            "identifier": "hello-ipad-2026-12-01", "title": "Hello iPad", "caption": "Now on iPad.",
            "date": "2026-12-01T10:00:00Z", "tintColor": "#2AABEE", "notify": True, "url": "https://tmplayer.org/",
            "imageURL": "https://tmplayer.org/a.png", "appID": BUNDLE,
        }, posts[0])

    def test_newest_first_the_same_identifier_replaced_and_the_oldest_dropped(self):
        self.post("One", "--identifier", "one", date="2026-12-01T00:00:00Z")
        self.post("Three", "--identifier", "three", date="2026-12-03T00:00:00Z")
        posts = self.post("Two", "--identifier", "two", date="2026-12-02T00:00:00Z")
        self.assertEqual(["three", "two", "one"], [p["identifier"] for p in posts])
        posts = self.post("Two again", "--identifier", "two", date="2026-12-02T00:00:00Z")
        self.assertEqual(["three", "two", "one"], [p["identifier"] for p in posts], "not listed twice")
        self.assertEqual("Two again", posts[1]["title"])
        posts = self.post("Four", "--identifier", "four", "--keep", "2", date="2026-12-04T00:00:00Z")
        self.assertEqual(["four", "three"], [p["identifier"] for p in posts])

    def test_a_post_about_an_unknown_app_or_with_a_plain_http_link_is_refused(self):
        with self.assertRaises(SystemExit) as refused:
            self.post("Bad", "--app", "org.example.other")
        self.assertIn("org.example.other", str(refused.exception))
        with self.assertRaises(SystemExit):
            self.post("Bad", "--url", "http://tmplayer.org/")
        with open(self.source, encoding="utf-8") as file:
            self.assertEqual([], json.load(file)["news"], "a refused post leaves the file as it was")


class LinksTest(unittest.TestCase):
    def test_the_address_and_the_deep_links(self):
        lines = run("links", "--repo", "owner/TMPlayer").splitlines()
        url = "https://raw.githubusercontent.com/owner/TMPlayer/main/altstore/source.json"
        self.assertEqual([f"source:    {url}", f"altstore:  altstore://source?url={url}",
                          f"sidestore: sidestore://source?url={url}"], lines)


class CheckTest(unittest.TestCase):
    """The rules of `check`, each broken on its own."""

    def setUp(self):
        self.folder = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.folder)
        self.source = make_source(self.folder)
        run("add", "--source", self.source, "--ipa", make_ipa(self.folder), "--download-url", RELEASE + "Player.ipa",
            "--repo", "owner/TMPlayer", "--developer", "owner", "--date", "2026-12-01T10:00:00Z")

    def broken(self, change, *flags):
        with open(self.source, encoding="utf-8") as file:
            source = json.load(file)
        change(source)
        path = os.path.join(self.folder, "broken.json")
        with open(path, "w", encoding="utf-8") as file:
            json.dump(source, file)
        with self.assertRaises(SystemExit) as refused:
            run("check", "--source", path, *flags)
        return str(refused.exception)

    def test_the_added_source_passes_both_checks(self):
        self.assertIn("in order", run("check", "--source", self.source))
        self.assertIn("in order", run("check", "--source", self.source, "--published"))

    def test_a_colour_that_is_not_hex(self):
        self.assertIn("tintColor", self.broken(lambda s: s["apps"][0].update(tintColor="blue")))

    def test_an_address_that_is_not_https(self):
        self.assertIn("https", self.broken(lambda s: s.update(website="http://tmplayer.org")))
        self.assertIn("https", self.broken(lambda s: s["apps"][0]["versions"][0].update(downloadURL="ftp://x/y.ipa")))

    def test_versions_out_of_order(self):
        def swap(source):
            first = dict(source["apps"][0]["versions"][0])
            older = dict(first, version="0.9.0", date="2020-01-01T00:00:00Z")
            source["apps"][0]["versions"] = [older, first]
        self.assertIn("newest first", self.broken(swap))

    def test_a_bad_checksum(self):
        self.assertIn("sha256", self.broken(lambda s: s["apps"][0]["versions"][0].update(sha256="abc")))

    def test_a_published_source_needs_the_checksum_and_a_release_download(self):
        self.assertIn("no sha256", self.broken(lambda s: s["apps"][0]["versions"][0].pop("sha256"), "--published"))
        self.assertIn("releases/download", self.broken(
            lambda s: s["apps"][0]["versions"][0].update(downloadURL="https://example.org/Player.ipa"), "--published"))

    def test_the_apps_own_fields_must_follow_the_newest_version(self):
        self.assertIn("newest", self.broken(lambda s: s["apps"][0].update(version="0.1.0"), "--published"))

    def test_a_screenshot_list_that_is_not_iphone_or_ipad(self):
        self.assertIn("watch", self.broken(lambda s: s["apps"][0].update(screenshots={"watch": []})))

    def test_a_build_listed_twice(self):
        def twice(source):
            source["apps"][0]["versions"].append(dict(source["apps"][0]["versions"][0]))
        self.assertIn("listed twice", self.broken(twice))


try:
    import jsonschema
except ImportError:
    jsonschema = None


@unittest.skipIf(jsonschema is None, "pip install jsonschema (CI does)")
class SchemaTest(unittest.TestCase):
    """altstore/source.schema.json accepts what the script writes and the repository's source."""

    def validate(self, source):
        with open(os.path.join(ROOT, "altstore", "source.schema.json"), encoding="utf-8") as file:
            jsonschema.Draft7Validator(json.load(file)).validate(source)

    def test_the_repositorys_source(self):
        with open(os.path.join(ROOT, "altstore", "source.json"), encoding="utf-8") as file:
            self.validate(json.load(file))

    def test_a_source_the_script_wrote_with_a_version_screenshots_and_news(self):
        folder = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, folder)
        source = make_source(folder)
        make_png(os.path.join(folder, "iosApp", "screenshots", "iphone", "1.png"), 1290, 2796)
        run("add", "--source", source, "--ipa", make_ipa(folder), "--download-url", RELEASE + "Player.ipa",
            "--repo", "owner/TMPlayer", "--developer", "owner", "--root", folder, "--news")
        with open(source, encoding="utf-8") as file:
            written = json.load(file)
        self.assertEqual(1, len(written["apps"][0]["screenshots"]["iphone"]))
        self.validate(written)

    def test_a_source_without_a_bundle_identifier_is_refused(self):
        with open(os.path.join(ROOT, "altstore", "source.json"), encoding="utf-8") as file:
            source = json.load(file)
        del source["apps"][0]["bundleIdentifier"]
        with self.assertRaises(jsonschema.ValidationError):
            self.validate(source)


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
        # The CI workflow uses dummies, made at run time: no 32 digit hex literal that could be a hash.
        self.assertIsNone(re.search(r"\b[0-9a-f]{32}\b", read(".github", "workflows", "apple-ci.yml")))

    def test_the_apple_workflows_use_only_actions_the_others_already_pin(self):
        def pins(name):
            return set(re.findall(r"uses:\s*([\w./-]+@[\w.]+)", read(".github", "workflows", name)))

        # Every action at a major version some other workflow already uses (setup-xcode is the one
        # only the Apple workflows need, and the release's own pin since the import).
        others = {"maxim-lobanov/setup-xcode@v1"}
        for name in os.listdir(os.path.join(ROOT, ".github", "workflows")):
            if name.endswith(".yml") and not name.startswith("apple-"):
                others |= pins(name)
        for name in ("apple-ci.yml", "apple-release.yml"):
            self.assertEqual(set(), pins(name) - others, name)

    def test_the_apple_workflows_are_guarded_and_name_no_fork(self):
        release = read(".github", "workflows", "apple-release.yml")
        self.assertIn("github.repository == (vars.APPLE_RELEASE_REPO || 'dracu-lah/TMPlayer')", release)
        for name in ("apple-ci.yml", "apple-release.yml"):
            text = read(".github", "workflows", name)
            self.assertNotIn("killua99x/tmplayer-for-apple", text.lower().replace("github.com/killua99x/tmplayer-for-apple", ""),
                             "the fork is credited by its address in a comment only")
            self.assertIn("workflow_dispatch", text)
        self.assertIn("dry_run", release)
        self.assertIn("min_ios", release)
        self.assertIn("APPLE_SOURCE_PUSH", release)
        self.assertIn("gh pr create", release)
        self.assertIn("vars.APPLE_MAC_CI == 'true'", read(".github", "workflows", "apple-ci.yml"))

    def test_a_test_version_is_a_pre_release_that_never_reaches_the_source(self):
        workflow = read(".github", "workflows", "apple-release.yml")
        self.assertIn("${BETA:+--prerelease}", workflow)
        self.assertIn("if: env.DRY_RUN != 'true' && env.BETA == ''", workflow)  # the AltStore source step
        self.assertIn("if: env.BETA == ''", workflow)  # the source entry made for the IPA
        self.assertIn('notes --version "$NOTES_VERSION" --build "$NOTES_BUILD"', workflow)
        # A beta's build is 0.<n>, below the release's build 1, and the script names the file from it.
        script = read("iosApp", "release", "build-ipa.sh")
        self.assertIn("^0\\.([1-9][0-9]*)$", script)
        self.assertIn('name="$name-beta.${BASH_REMATCH[1]}"', script)

    def test_the_tags_are_read_as_the_docs_say(self):
        bash = shutil.which("bash")
        if not bash:
            self.skipTest("no bash")
        workflow = read(".github", "workflows", "apple-release.yml")
        step = re.search(r"- name: Mode, version and build\n.*?run: \|\n(.*?)\n\n      - name: Notes", workflow, re.S)
        script = "\n".join(line[10:] for line in step.group(1).split("\n"))

        def mode(**env):
            folder = tempfile.mkdtemp()
            self.addCleanup(shutil.rmtree, folder)
            out = os.path.join(folder, "env")
            open(out, "w").close()
            base = {"PATH": os.environ["PATH"], "GITHUB_ENV": out, "IS_TAG": "true", "EVENT": "push"}
            done = subprocess.run([bash, "-c", script], cwd=ROOT, env={**base, **env},
                                  capture_output=True, text=True)
            with open(out, encoding="utf-8") as file:
                values = dict(line.rstrip("\n").split("=", 1) for line in file if "=" in line)
            return done.returncode, values

        code, got = mode(GITHUB_REF_NAME="apple-v1.28.6-beta.4")
        self.assertEqual(0, code)
        self.assertEqual(("1.28.6", "0.4", "4", "apple-v1.28.6-beta.4", "TMPlayer-ios-1.28.6-beta.4.ipa",
                          "1.28.6-beta.4", "1"),
                         tuple(got[k] for k in ("VERSION", "BUILD", "BETA", "TAG", "IPA", "NOTES_VERSION", "NOTES_BUILD")))
        code, got = mode(GITHUB_REF_NAME="apple-v1.28.6")
        self.assertEqual((0, "1", "", "1.28.6"), (code, got["BUILD"], got["BETA"], got["NOTES_VERSION"]))
        code, got = mode(GITHUB_REF_NAME="apple-v1.28.5-2")
        self.assertEqual((0, "2", "", "1.28.5", "2"),
                         (code, got["BUILD"], got["BETA"], got["NOTES_VERSION"], got["NOTES_BUILD"]))
        for bad in ("apple-v1.28.6-beta.0", "apple-v1.28.6-beta", "apple-v1.28.6-rc.1", "apple-v1.28.6-beta.1-2", "v1.28.6"):
            self.assertNotEqual(0, mode(GITHUB_REF_NAME=bad)[0], bad)
        code, got = mode(IS_TAG="false", EVENT="workflow_dispatch", INPUT_DRY_RUN="true", INPUT_VERSION="1.28.6-beta.2")
        self.assertEqual((0, "true", "0.2", "apple-v1.28.6-beta.2"), (code, got["DRY_RUN"], got["BUILD"], got["TAG"]))
        # A release never starts from a branch, a test version included.
        code, _ = mode(IS_TAG="false", EVENT="workflow_dispatch", INPUT_DRY_RUN="false", INPUT_VERSION="1.28.6-beta.2")
        self.assertNotEqual(0, code)


if __name__ == "__main__":
    unittest.main()
