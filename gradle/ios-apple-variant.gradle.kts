// The iOS and iPadOS app's few changes to the shared and Android sources (docs/IOS.md). Started
// from TMPlayer for Apple by Pietro Giuseppe D'Amico (github.com/Killua99x/TMPlayer-for-apple,
// GPL-3.0), where this file named that fork's own links and mark. Here the iOS app is TMPlayer, so
// its name, mark, links, support page and contact stay TMPlayer's; what is left is what iOS needs
// to work or read right: the iPhone and iPad bug template, the release tags iOS builds carry, the
// privacy note without an updater, the device's own name in a report, the lists running under the
// home indicator, and a downloaded video playing before the sign in.
//
// The source files stay as they are. :core, :ui and :ios compile, for iOS only, copies of the files
// below with these exact replacements. A replacement whose text is no longer in the file stops the
// build, so a change there is seen and never skipped. Linux checks them too, without Xcode:
// `./gradlew -Ptmplayer.ios=true :core:appleSourcesForIos :ui:appleSourcesForIos
// :ios:androidScreensForIos :ios:androidPlayerForIos` (docs/IOS.md).
//
// Applied with apply(from = ...). The build reads back `extra["appleVariant"]`, a map from module
// to the source paths it changes, and `extra["forApple"]` as `(String, String, String) -> String`
// (module, path, text).

/** One replacement: every [old] in the file becomes [new], and [old] must be there at least once. */
data class Replacement(val old: String, val new: String)

infix fun String.becomes(new: String) = Replacement(this, new)

/**
 * `footInsets`: on iOS a list runs under the home indicator to the foot of the window, as iOS's
 * own lists do, and keeps the indicator's strip after its last row (the strip was left empty under
 * every list, 2026-11-16). Material's Scaffold pads its content by the system bars, the bottom one
 * included, so the screens that already keep that strip in their lists take only the status bar
 * from it, as the grid and the Downloads page do (`contentWindowInsets = WindowInsets.statusBars`).
 */
val importStatusBars = "import androidx.compose.foundation.layout.WindowInsets\n" becomes
    "import androidx.compose.foundation.layout.WindowInsets\nimport androidx.compose.foundation.layout.statusBars\n"

val appleVariant: Map<String, Map<String, List<Replacement>>> = mapOf(
    // :core, paths under src/commonMain/kotlin.
    "core" to mapOf(
        // "Report a problem" opens TMPlayer's iPhone and iPad template, whose platform dropdown
        // has the option below (.github/ISSUE_TEMPLATE/apple_bug_report.yml).
        "com/tmplayer/data/Feedback.kt" to listOf(
            "const val TEMPLATE = \"bug_report.yml\"" becomes "const val TEMPLATE = \"apple_bug_report.yml\"",
            "Phone(\"Android phone or tablet\")," becomes "Phone(\"iPhone or iPad\"),",
        ),
    ),
    // :ui, paths under src/shared/kotlin.
    "ui" to mapOf(
        "com/tmplayer/ui/about/About.kt" to listOf(
            // iOS releases are tagged apple-v<version> (.github/workflows/apple-release.yml).
            "\"\$SOURCE/releases/tag/v\$version\"" becomes "\"\$SOURCE/releases/tag/apple-v\$version\"",
            // Its corresponding source is MPVKit's, with mpv and FFmpeg, not NextLib's.
            "L.aboutCorrespondingSourceDetail," becomes "L.aboutCorrespondingSourceDetailIos,",
        ),
        // The iOS app carries no TMDB key of its own, so with none added TMDB was never asked:
        // "TMDB did not accept the key" read as a fault (iPad, 2026-11-14). Settings' own words
        // for a missing key say what to do; a key of one's own that TMDB refuses keeps the usual.
        "com/tmplayer/ui/browse/PosterPicker.kt" to listOf(
            "PosterChoices.NoKey -> PickerLoad.Message(s.posterNoKey, canRetry = false)" becomes
                "PosterChoices.NoKey -> PickerLoad.Message(\n" +
                "                if (online.store.now.ownKey.isBlank()) s.metadataKeyNone else s.posterNoKey,\n" +
                "                canRetry = false,\n" +
                "            )",
        ),
    ),
    // :ios, Android's screens, paths under app/src/main/java.
    "ios" to mapOf(
        "com/tmplayer/ui/settings/AboutScreen.kt" to listOf(
            // The list runs to the foot of the window (see `footInsets` above).
            importStatusBars,
            // The fixed PageHeader's Scaffold (Back, the title, a hairline once the list has moved).
            "                        scrolled = moved,\n                    )\n                },\n            ) { padding ->\n" becomes
                "                        scrolled = moved,\n                    )\n                },\n" +
                "                contentWindowInsets = WindowInsets.statusBars,\n            ) { padding ->\n",
        ),
        "com/tmplayer/ui/settings/LanguageAndFeedback.kt" to listOf(
            // A bug report's and a saved log's facts: the device as iOS names it ("iPad
            // (iPad13,18), iPadOS 27.0.1"), not "Apple iPad, Android 27.0.1" (an iPad's log,
            // 2026-11-15), and the build, since builds of one version differ on iOS.
            "    val model = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.distinct().joinToString(\" \")\n" becomes
                "    val model = com.tmplayer.data.appleDevice()\n",
            "\"\$model, Android \${Build.VERSION.RELEASE}, " becomes "\"\$model, ",
            "version = BuildConfig.VERSION_NAME," becomes "version = com.tmplayer.data.Updates.installedVersion,",
        ),
        // The iOS app checks for no update itself: AltStore or SideStore does, from the source.
        "com/tmplayer/ui/settings/SettingsScreen.kt" to listOf(
            "s.settingsPrivacyNote," becomes "s.settingsPrivacyNoteIos,",
            // "this device", not "this phone", on an iPad as well (2026-11-15).
            "subtitle = s.settingsPrivacyDetail(device)," becomes "subtitle = s.settingsPrivacyDetailDevice,",
            // The list and each page run to the foot of the window (see `footInsets` above).
            importStatusBars,
            "                        scrolled = pageMoved,\n                    )\n                },\n            ) { padding ->\n" becomes
                "                        scrolled = pageMoved,\n                    )\n                },\n" +
                "                contentWindowInsets = WindowInsets.statusBars,\n            ) { padding ->\n",
            "scrolled = listMoved) },\n            ) { padding ->\n" becomes
                "scrolled = listMoved) },\n" +
                "                contentWindowInsets = WindowInsets.statusBars,\n            ) { padding ->\n",
        ),
        // Home and the chat lists: the frame stops leaving the home indicator's strip under them,
        // and the lists keep that strip after their last row instead (homeIndicatorInset in :ios).
        "com/tmplayer/ui/browse/PhoneBrowse.kt" to listOf(
            // Compose for iOS has no statusBarsIgnoringVisibility (the Android-only insets API that keeps
            // the status bar's height while it is hidden); iOS's status bar height is the plain
            // statusBars inset, which the clock and the home name are kept clear of the same way.
            "import androidx.compose.foundation.layout.statusBarsIgnoringVisibility\n" becomes
                "import androidx.compose.foundation.layout.statusBars\n",
            "androidx.compose.foundation.layout.WindowInsets.statusBarsIgnoringVisibility.getTop(this)" becomes
                "androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(this)",
            "                bottom = padding.calculateBottomPadding(),\n            )\n        } else {\n            padding\n        }\n" becomes
                "                bottom = 0.dp,\n            )\n        } else {\n" +
                "            PaddingValues(\n" +
                "                start = padding.calculateStartPadding(direction),\n" +
                "                top = padding.calculateTopPadding(),\n" +
                "                end = padding.calculateEndPadding(direction),\n" +
                "            )\n        }\n",
        ),
        "com/tmplayer/ui/browse/BrowseScreen.kt" to listOf(
            "rail = LocalTouchRail.current) + com.tmplayer.ui.components.LocalBannerRoom.current)" becomes
                "rail = LocalTouchRail.current) + com.tmplayer.ui.components.LocalBannerRoom.current + com.tmplayer.ios.homeIndicatorInset())",
        ),
    ),
    // :ios, Android's player as copied for iOS (androidPlayerForIos). A video kept in Downloads
    // plays from its file without waiting for Telegram's sign in, which the player waits for
    // before any video: one opened before TDLib is ready, or with no one signed in, sat on "This
    // video hasn't started" (2026-11-16). One cut into pieces still asks Telegram for them, and a
    // stream still waits for the sign in.
    "iosPlayer" to mapOf(
        // The engine reads ahead by the default load control's budget (EngineExoPlayer.useLoadControl): it
        // looks through this wrapper to find it, so the wrapped control is visible inside the module.
        "com/tmplayer/player/AheadFirstLoadControl.kt" to listOf(
            "    private val delegate: LoadControl," becomes "    internal val delegate: LoadControl,",
        ),
        // "follow the device", not "follow the phone", on an iPad as well (2026-11-15).
        "com/tmplayer/player/ScreenOrientation.kt" to listOf(
            "Follow -> L.playerRotationFollow" becomes "Follow -> L.playerRotationFollowDevice",
        ),
        "com/tmplayer/player/PlayerActivity.kt" to listOf(
            "        itemScope.launch {\n            val session = Td.awaitAuthorizedSession()\n            markStart(\"session ready\", STAGE_SESSION)\n" becomes
                "        itemScope.launch {\n" +
                "            val kept = !mayBeJoined() && LocalDownloads.videoFor(settings, chatId, messageId) != null\n" +
                "            val session = if (kept) Td.awaitAnySession() else Td.awaitAuthorizedSession()\n" +
                "            markStart(\"session ready\", STAGE_SESSION)\n",
            // A session counts as current only once someone is signed in: a kept video goes on.
            "            if (!session.isCurrent()) return@launch\n            // The engine's inputs (PR2)" becomes
                "            if (!kept && !session.isCurrent()) return@launch\n            // The engine's inputs (PR2)",
        ),
    ),
    // :ios's promo build only (-PtmPromo=true, the screenshots), the promo fixture under
    // app/src/promo: its pictures come from the app bundle, where the Xcode project copies them,
    // instead of Android's resources.
    "iosPromo" to mapOf(
        // Its demo files are named as release groups name theirs: the iOS screenshots never show a
        // name that reads like piracy, so each is made plain (PromoNames in :ios) where it is made,
        // and found again by its plain name. Kotlin/Native has no String.format, so the episode
        // numbers are padded by hand.
        "com/tmplayer/promo/PromoCatalog.kt" to listOf(
            "\"Harbour.Lines.S01E%02d.1080p.WEB-DL.DDP5.1.x264.mkv\".format(it.number)" becomes
                "\"Harbour.Lines.S01E\${it.number.toString().padStart(2, '0')}.1080p.WEB-DL.DDP5.1.x264.mkv\"",
            "\"[Kumo] Skybound Academy - %02d (1080p).mkv\".format(it.number)" becomes
                "\"[Kumo] Skybound Academy - \${it.number.toString().padStart(2, '0')} (1080p).mkv\"",
            "\"Wild.Coasts.S01E%02d.2160p.WEB-DL.HEVC.mkv\".format(it.number)" becomes
                "\"Wild.Coasts.S01E\${it.number.toString().padStart(2, '0')}.2160p.WEB-DL.HEVC.mkv\"",
            "\"\$COURSE - Lesson %02d - %s.mp4\".format(l.number, l.name)" becomes
                "\"\$COURSE - Lesson \${l.number.toString().padStart(2, '0')} - \${l.name}.mp4\"",
            "title = copy.fileName," becomes "title = com.tmplayer.ios.PromoNames.clean(copy.fileName),",
            "fileName = copy.fileName," becomes "fileName = com.tmplayer.ios.PromoNames.clean(copy.fileName),",
            "val name = show.fileName(e)" becomes "val name = com.tmplayer.ios.PromoNames.clean(show.fileName(e))",
            "private fun named(part: String) = all.first { part in it.fileName }" becomes
                "private fun named(part: String) = all.first { com.tmplayer.ios.PromoNames.clean(part) in it.fileName }",
            "\"air_date\":\"\${s.year}-02-%02d\"" becomes
                "\"air_date\":\"\${s.year}-02-\${(e.number * 7 - 6).toString().padStart(2, '0')}\"",
            "\"\"\".format(e.number * 7 - 6)" becomes "\"\"\"",
            // String.toByteArray is the JVM's; Kotlin's own is encodeToByteArray (UTF-8 as well).
            "body.toByteArray())" becomes "body.encodeToByteArray())",
        ),
        "com/tmplayer/PromoCaptureActivity.kt" to listOf(
            "fileName = \"Quiet.Orbit.2024.1080p.WEB-DL.mkv\"" becomes "fileName = \"The Quiet Orbit (2024) 1080p.mkv\"",
            "fileName = \"Quiet.Orbit.2024.720p.mkv\"" becomes "fileName = \"The Quiet Orbit (2024) 720p.mkv\"",
        ),
        "com/tmplayer/PromoMeta.kt" to listOf(
            "                    val id = res.getIdentifier(name, \"drawable\", context.packageName)\n" +
                "                    if (id == 0) null else res.openRawResource(id).use { it.readBytes() }\n" becomes
                "                    com.tmplayer.ios.PromoPictures.bytes(name)\n",
        ),
    ),
)

fun forApple(module: String, path: String, text: String): String {
    val replacements = appleVariant[module]?.get(path) ?: return text
    return replacements.fold(text) { current, (old, new) ->
        if (!current.contains(old)) {
            throw GradleException(
                "$module/$path no longer contains the text the iOS variant replaces " +
                    "(gradle/ios-apple-variant.gradle.kts): ${old.trim().take(120)}",
            )
        }
        current.replace(old, new)
    }
}

extra["appleVariant"] = appleVariant.mapValues { (_, files) -> files.keys.sorted() }
extra["forApple"] = { module: String, path: String, text: String -> forApple(module, path, text) }
