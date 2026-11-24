import java.util.Properties

// The Kotlin half of the iOS and iPadOS app, one universal app for iPhone and iPad: TMPlayer's
// Android shell and phone screens compiled for iOS, with the stand ins and iOS versions they need.
// The Xcode project in iosApp/ links it through :ios:kit (the TMPlayerKit framework). See
// docs/IOS.md.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// A developer's build for the README's screenshots: upstream's promo fixture (app/src/promo) compiled
// in, which opens the app's real screens over an invented account, chosen at launch with `-promo`
// (iosApp/screenshots). -PtmPromo=true, or ORG_GRADLE_PROJECT_tmPromo=true as an xcodebuild setting.
val promo = project.findProperty("tmPromo") == "true"

// :app's BuildConfig and R for its screens on iOS. Telegram's credentials come from local.properties
// as :app reads them; the build works without them, and the sign in screen says so.
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val out = layout.buildDirectory.dir("generated/buildinfo")
    val apiId = localProps.getProperty("TG_API_ID")?.trim()?.takeIf { it.matches(Regex("[0-9]{1,10}")) } ?: "0"
    val apiHash = localProps.getProperty("TG_API_HASH")?.trim()?.takeIf { it.matches(Regex("[0-9a-f]{32}")) } ?: ""
    // The optional service keys, read as :app reads them (local.properties, then the environment).
    // Only letters, digits, '_' and '-' reach the generated source; anything else is left out.
    val keys = listOf("OPENSUBTITLES_API_KEY", "MSONE_GATEWAY_KEY", "TMDB_API_KEY", "OMDB_API_KEY").associateWith { name ->
        (localProps.getProperty(name) ?: System.getenv(name) ?: "").trim().takeIf { it.matches(Regex("[A-Za-z0-9_-]{0,128}")) } ?: ""
    }
    // :app's drawables, one id each, so its screens' R.drawable names resolve; the Xcode project
    // copies the folders into the bundle, where vectorResource and painterResource read them.
    val drawableDirs = listOf("drawable", "drawable-nodpi").map { rootProject.file("app/src/main/res/$it") } +
        // The promo fixture's pictures, which the Xcode project copies beside :app's in a promo build.
        listOfNotNull(rootProject.file("app/src/promo/res/drawable-nodpi").takeIf { promo })
    val drawables = drawableDirs.flatMap { dir -> dir.listFiles().orEmpty().filter { it.isFile }.map { it.nameWithoutExtension } }
        .filter { it.matches(Regex("[a-z0-9_]+")) }.distinct().sorted()
    // :app's layouts, the @+id names they make, and its colours (values and colour lists), for
    // Android's Views on iOS: R.id.x is the id the layout's @+id/x gets (ResourceFiles.install).
    val res = rootProject.file("app/src/main/res")
    val layoutFiles = res.resolve("layout").listFiles().orEmpty().filter { it.isFile && it.extension == "xml" }
    val layouts = layoutFiles.map { it.nameWithoutExtension }.filter { it.matches(Regex("[a-z0-9_]+")) }.sorted()
    val viewIds = layoutFiles.flatMap { Regex("@\\+id/([A-Za-z0-9_]+)").findAll(it.readText()).map { m -> m.groupValues[1] }.toList() }
        .distinct().sorted()
    val colors = (
        res.resolve("values").listFiles().orEmpty().filter { it.extension == "xml" }
            .flatMap { Regex("<color\\s+name=\"([a-z0-9_]+)\"").findAll(it.readText()).map { m -> m.groupValues[1] }.toList() } +
            res.resolve("color").listFiles().orEmpty().filter { it.extension == "xml" }.map { it.nameWithoutExtension }
        ).filter { it.matches(Regex("[a-z0-9_]+")) }.distinct().sorted()
    // Where "Send to developer" posts a report (workers/log-inbox): empty unless the builder sets
    // LOG_UPLOAD_URL, as :app reads it, and then an https address of plain URL characters only.
    val logUploadUrl = (localProps.getProperty("LOG_UPLOAD_URL") ?: System.getenv("LOG_UPLOAD_URL") ?: "").trim()
        .takeIf { it.matches(Regex("https://[A-Za-z0-9._~:/?#@!&'()*+,;=%-]{1,300}")) } ?: ""
    inputs.property("apiId", apiId)
    inputs.property("logUploadUrl", logUploadUrl)
    inputs.property("apiHash", apiHash)
    inputs.property("keys", keys)
    inputs.property("drawables", drawables)
    inputs.property("layouts", layouts)
    inputs.property("viewIds", viewIds)
    inputs.property("colors", colors)
    val dimens = res.resolve("values").listFiles().orEmpty().filter { it.extension == "xml" }
        .flatMap { Regex("<dimen\\s+name=\"([a-z0-9_]+)\"").findAll(it.readText()).map { m -> m.groupValues[1] }.toList() }
        .distinct().sorted()
    inputs.property("dimens", dimens)
    inputs.property("promo", promo)
    outputs.dir(out)
    doLast {
        out.get().asFile.deleteRecursively()
        out.get().asFile.resolve("com/tmplayer/ios").apply { mkdirs() }.resolve("PromoEntry.kt").writeText(
            """
            |package com.tmplayer.ios
            |
            |// Generated by :ios's generateBuildInfo. Do not edit.
            |/** Upstream's promo fixture in a build made with -PtmPromo=true, which `-promo` opens; none otherwise. */
            |object PromoEntry {
            |    fun activity(): androidx.activity.ComponentActivity? = ${if (promo) "com.tmplayer.PromoCaptureActivity()" else "null"}
            |}
            |""".trimMargin(),
        )
        val keyLines = keys.entries.joinToString("") { (name, value) -> "|    const val $name = \"$value\"\n" }
        out.get().asFile.resolve("com/tmplayer").apply { mkdirs() }.resolve("R.kt").writeText(
            buildString {
                appendLine("package com.tmplayer")
                appendLine()
                appendLine("// Generated by :ios's generateBuildInfo from app/src/main/res: :app's R. Do not edit.")
                appendLine("object R {")
                appendLine("    object drawable {")
                drawables.forEachIndexed { index, name -> appendLine("        const val $name = ${index + 1}") }
                appendLine("    }")
                appendLine()
                // Android's own ranges for each type, so the numbers never meet.
                appendLine("    object color {")
                colors.forEachIndexed { index, name -> appendLine("        const val $name = ${0x7F060000 + index}") }
                appendLine("    }")
                appendLine()
                appendLine("    object dimen {")
                dimens.forEachIndexed { index, name -> appendLine("        const val $name = ${0x7F070000 + index}") }
                appendLine("    }")
                appendLine()
                appendLine("    object id {")
                viewIds.forEachIndexed { index, name -> appendLine("        const val $name = ${0x7F0B0000 + index}") }
                appendLine("    }")
                appendLine()
                appendLine("    object layout {")
                layouts.forEachIndexed { index, name -> appendLine("        const val $name = ${0x7F0D0000 + index}") }
                appendLine("    }")
                appendLine()
                appendLine("    val drawableNames: Map<Int, String> = mapOf(")
                drawables.forEach { name -> appendLine("        drawable.$name to \"$name\",") }
                appendLine("    )")
                appendLine()
                appendLine("    /** Every id above by its \"type/name\", for ResourceFiles.install. */")
                appendLine("    val references: Map<Int, String> = mapOf(")
                drawables.forEach { name -> appendLine("        drawable.$name to \"drawable/$name\",") }
                colors.forEach { name -> appendLine("        color.$name to \"color/$name\",") }
                dimens.forEach { name -> appendLine("        dimen.$name to \"dimen/$name\",") }
                viewIds.forEach { name -> appendLine("        id.$name to \"id/$name\",") }
                layouts.forEach { name -> appendLine("        layout.$name to \"layout/$name\",") }
                appendLine("    )")
                appendLine("}")
            },
        )
        out.get().asFile.resolve("com/tmplayer").apply { mkdirs() }.resolve("BuildConfig.kt").writeText(
            """
            |package com.tmplayer
            |
            |// Generated by :ios's generateBuildInfo: :app's BuildConfig, for its screens on iOS. Do not edit.
            |object BuildConfig {
            |    const val DEBUG = false
            |    // "promo" in the screenshots' build (it logs and traces more, as Android's), else "release".
            |    const val BUILD_TYPE = "${if (promo) "promo" else "release"}"
            |    const val LOG_UPLOAD_URL = "$logUploadUrl"
            |    const val APPLICATION_ID = "org.tmplayer.ios"
            |    // The version and build in the app's Info.plist (MARKETING_VERSION and
            |    // CURRENT_PROJECT_VERSION, set from the release's tag), so About, bug reports and
            |    // backups name the version installed.
            |    val VERSION_NAME: String = platform.Foundation.NSBundle.mainBundle
            |        .objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "0"
            |    val VERSION_CODE: Int = (platform.Foundation.NSBundle.mainBundle
            |        .objectForInfoDictionaryKey("CFBundleVersion") as? String)?.toIntOrNull() ?: 0
            |    const val SENTRY_DSN = ""
            |    const val TG_API_ID = $apiId
            |    const val TG_API_HASH = "$apiHash"
            |    // No updater of its own: AltStore or SideStore offers each update from the source
            |    // (docs/IOS.md). The support links and their three reminders are on, as on
            |    // Android, with the maintainer's own ways to give (gradle/ios-apple-variant.gradle.kts):
            |    // the app is sideloaded, so the App Store's rule on donations does not apply.
            |    const val UPDATE_CHECK = false
            |    const val SUPPORT_LINKS = true
            $keyLines|}
            |""".trimMargin(),
        )
    }
}

// Android's phone screens (app/src/main/java/com/tmplayer/ui), compiled for iOS as upstream wrote
// them, against the stand ins for the Android APIs they touch (src/iosStandIns). Copied at build
// time so they never drift from upstream (docs/IOS.md, "Android's phone screens on iOS").
/** Upstream's Home carousel page timer, as written in HomeCarousel.kt (see the rewrite below). */
val CAROUSEL_TIMER = "progress.animateTo(1f, tween((ADVANCE_MS * left).toInt(), easing = LinearEasing))"

// The Apple variant's own links in three of those screens (gradle/ios-apple-variant.gradle.kts).
apply(from = rootProject.file("gradle/ios-apple-variant.gradle.kts"))
@Suppress("UNCHECKED_CAST")
val forApple = extra["forApple"] as (String, String, String) -> String
@Suppress("UNCHECKED_CAST")
val appleScreens = (extra["appleVariant"] as Map<String, List<String>>)["ios"].orEmpty()
@Suppress("UNCHECKED_CAST")
val applePromo = (extra["appleVariant"] as Map<String, List<String>>)["iosPromo"].orEmpty()
@Suppress("UNCHECKED_CAST")
val applePlayer = (extra["appleVariant"] as Map<String, List<String>>)["iosPlayer"].orEmpty()

val androidScreensForIos = tasks.register<Sync>("androidScreensForIos") {
    inputs.file(rootProject.file("gradle/ios-apple-variant.gradle.kts"))
    from(rootProject.layout.projectDirectory.dir("app/src/main/java")) {
        include("com/tmplayer/ui/**", "com/tmplayer/data/**", "com/tmplayer/MainActivity.kt", "com/tmplayer/App.kt")
        // The Search key the activities take when nothing on screen did (plain Kotlin over KeyEvent).
        include("com/tmplayer/SearchKeyGuard.kt")
        include("com/tmplayer/player/PlayerBookmarkSheets.kt", "com/tmplayer/player/SmootherPick.kt")
        exclude("com/tmplayer/ui/browse/DebugFakeChats.kt")
        // Android's biometric prompt (androidx.biometric); iOS has a no-op of the same name in src/iosMain (WP-41).
        exclude("com/tmplayer/ui/lock/BiometricPrompter.kt")
        // Android 14's back preview (PredictiveBackHandler); iOS has a plain Back of the same name in src/iosMain (WP-44).
        exclude("com/tmplayer/ui/components/PredictivePageBack.kt")
        // Android's trailer player is a WebView (android.webkit); iOS's is a WKWebView in the Swift
        // shell (iosApp/TMPlayer/Player/TrailerSheet.swift), reached through src/iosMain/.../ui/trailer/TrailerPlayerRoot.kt,
        // which keeps the name MainActivity calls. TrailerControls.kt (the state machine over core's
        // TrailerRules, the remote's keys, the mobile data rule) has no Android types and is shared.
        exclude("com/tmplayer/ui/trailer/TrailerPlayer.kt", "com/tmplayer/ui/trailer/TrailerWebPage.kt")
        // Android system integration (services, notifications, TV channels, the installer, crash
        // exits, storage access) has iOS versions in src/iosMain with the same names instead.
        listOf(
            "AndroidTransferNotifier", "AndroidUpdates", "AppNotices", "AutoUpdater", "BackgroundChecks",
            "CrashExits", "CrashReports", "DownloadRelocationService", "DownloadService", "FolderExport",
            "NoticeActions", "SelfInstaller", "TvArt", "TvArtProvider", "TvChannelsPublisher", "UpdatedReceiver",
            "WatchNextPublisher", "AppLocales", "AppNightMode", "LegacyApiCompat", "LauncherShortcuts", "NetworkMonitor", "ProfileNotifications",
            // The update's baseline profile (java.util.zip), read only by SelfInstaller.
            "UpdateDexMetadata",
            // The version line's facts (Build.*), with an iOS twin in AndroidSystemOnIos.kt (WP-104).
            "VersionFacts",
        ).forEach { exclude("com/tmplayer/data/$it.kt") }
    }
    // The build flavour without Google's services, the one iOS matches (no Cast, no SMS consent).
    from(rootProject.layout.projectDirectory.dir("app/src/nocast/java")) {
        // Its CastBuild names Android's cast support; iOS has its own no-cast pieces (src/iosMain).
        exclude("com/tmplayer/cast/**")
    }
    // In a promo build, upstream's promo fixture and its invented library (see `promo` above): the
    // screens it opens are the ones above. Its decoder bench and its stand in player are built on
    // Android's Media3; the screenshots show the app's real player instead, playing invented
    // videos (iosApp/screenshots/make-videos.sh).
    inputs.property("promo", promo)
    if (promo) {
        from(rootProject.layout.projectDirectory.dir("app/src/promo/java")) {
            exclude("com/tmplayer/PromoDecodeBenchActivity.kt", "com/tmplayer/PromoPlayerActivity.kt")
        }
        from(rootProject.layout.projectDirectory.dir("app/src/promo/shared"))
    }
    // The only changes made to upstream's text on the way, mechanical and the same at every site:
    // Android's ViewModelProvider.Factory takes a Class, the multiplatform one a KClass and extras;
    // and a dialog's DialogProperties.decorFitsSystemWindows (fit inside the system bars, or draw
    // under them) is called usePlatformInsets on iOS; and Compose's Dialog becomes one that also
    // closes on iOS's back swipe (com.tmplayer.ios.DialogWithBack); and the Home carousel's page
    // timer, an animation nothing draws, waits out the same time with a timer instead, since on iOS
    // each frame an animation waits for is a frame drawn whole (com.tmplayer.ios.QuietTimer).
    // Gradle does not see inside the filter: this names its rewrites, so a change to them reruns it.
    inputs.property("rewrites", "decorFitsSystemWindows to usePlatformInsets; ViewModel factories to KClass; Dialog with Back; carousel timer")
    filter { line ->
        line.replace("decorFitsSystemWindows = ", "usePlatformInsets = ")
            .replace(CAROUSEL_TIMER, "com.tmplayer.ios.QuietTimer.run(progress, (ADVANCE_MS * left).toLong())")
            .replace(Regex("^import androidx\\.compose\\.ui\\.window\\.Dialog$"), "import com.tmplayer.ios.DialogWithBack as Dialog")
            .replace(
            Regex("override fun <T : ((?:androidx\\.lifecycle\\.)?ViewModel)> create\\(modelClass: Class<T>\\): T"),
            "override fun <T : $1> create(modelClass: kotlin.reflect.KClass<T>, extras: androidx.lifecycle.viewmodel.CreationExtras): T",
        )
    }
    into(layout.buildDirectory.dir("android-screens/kotlin"))
    // The carousel's timer is rewritten by its exact text: should upstream change it, the build
    // stops here rather than ship the frame-a-vsync timer again unseen.
    doLast {
        val carousel = destinationDir.resolve("com/tmplayer/ui/browse/HomeCarousel.kt")
        if (carousel.isFile && "QuietTimer.run(" !in carousel.readText()) {
            throw GradleException("HomeCarousel.kt's page timer changed upstream: update the iOS rewrite (CAROUSEL_TIMER in ios/build.gradle.kts)")
        }
        appleScreens.forEach { path ->
            val file = destinationDir.resolve(path)
            file.writeText(forApple("ios", path, file.readText()))
        }
        if (promo) {
            applePromo.forEach { path ->
                val file = destinationDir.resolve(path)
                file.writeText(forApple("iosPromo", path, file.readText()))
            }
        }
    }
}

// The desktop's download queue, which fetches in the app's own process: on iOS it runs what
// Android's DownloadService intents ask for (src/iosMain/.../DownloadsOnIos.kt). And the desktop
// player's engine interface with the pieces it names: on iOS PlaybackEngine is the rules every
// video engine follows (docs/IOS.md), so they are upstream's own and move with it.
val desktopPiecesForIos = tasks.register<Sync>("desktopPiecesForIos") {
    from(rootProject.layout.projectDirectory.dir("desktop/src/main/kotlin")) {
        include("com/tmplayer/desktop/DesktopDownloads.kt")
        include("com/tmplayer/desktop/player/PlaybackEngine.kt")
        include("com/tmplayer/desktop/player/PlayerExtras.kt")
        include("com/tmplayer/desktop/player/VideoAspect.kt")
    }
    into(layout.buildDirectory.dir("desktop-pieces/kotlin"))
}

// Android's player (app/src/main/java/com/tmplayer/player), compiled for iOS as upstream wrote it
// against the Media3 stand ins (src/iosStandIns/kotlin/androidx/media3), whose ExoPlayer plays on
// the app's video engine (docs/IOS.md, "Android's phone player on iOS, unchanged"). File
// by file as the layers land; for now the files that use Media3 without Android's Views.
val androidPlayerForIos = tasks.register<Sync>("androidPlayerForIos") {
    from(rootProject.layout.projectDirectory.dir("app/src/main/java")) {
        listOf(
            "StateChangeOnlyPlayer", "FormatRoute", "TrackPickerHost", "TdDataSource", "JoinedDataSource",
            "CuelessMatroskaSeek", "VolumeBoost", "SyncShims", "SubtitleEncoding", "PassthroughBufferCap",
            "LevelLenientVideoRenderer", "BackgroundPlayback", "HardwareDecoders", "PlayPauseIcon", "FineTimeBar",
            "PlayerControls", "ControlsScrim", "ScrubPreview", "TrickplayFrames",
            // Layer 4 (docs/IOS.md), brought over in batches that each build: first the
            // files that need no other player file yet (the overlays, keys, subtitle styling).
            "SubtitleLift", "PictureInPictureRules", "HevcHdr10PlusMasker", "ExternalPlayer", "ExternalSubtitle",
            "PlayerMenu", "LockedKeys", "SubtitleScripts", "ScreenOrientation", "SheetActions", "NextUpCard",
            "SubtitleLook", "SkipPill", "UndoSeekChip", "StatusSheetShape", "PlayerErrorSheet",
            // Batch 2: gestures, feedback, the loader, the track picker, subtitle files, saving a frame.
            "PlayerGestures", "PlayerFeedback", "PlayerLoaderHost", "TrackPickerFragment", "SubtitleFiles", "FrameSaver",
            // Batch 3: the decoder choices, which the engine answers for on iOS, and the storyboards.
            "DolbyVisionBaseLayer", "FrameRateMatch", "DeviceDecode", "DecoderSwitch", "Hdr10PlusMaskingCodecAdapter",
            "StoryboardThumbnails",
            // Batch 4, one cycle that comes together: the activity, its panels, its service, its 3D.
            // PlayerCast: no casting on iOS (maintainer, 2026-11-12); src/iosMain/.../PlayerCastOnIos.kt answers "not available".
            // PlaybackService, an Android foreground service, has an iOS version (src/iosMain/.../PlaybackServiceOnIos.kt).
            "PlayerActivity", "PlayerEpisodes", "PlayerMorePanel", "PlayerSheets", "Stereo3dEffect",
            // Audio passthrough off (its wrapper over the output) and the More options' "Add to a list" page.
            "PcmOnlyOutputProvider", "PlayerListsSheet",
            // Batch 5 (TMPlayer, 2026-11-17): the player files written after the port's base (1.28.5),
            // brought in when the port joined TMPlayer (docs/IOS.md).
            // Left out, with iOS versions in src/iosMain/.../player/*OnIos.kt: the external player apps and their
            // loopback stream service (ExternalChooserSheet, ExternalPlayRouter, ExternalStreams, InstalledPlayers),
            // MediaCodec's decoder list (DecoderFacts, DecoderLister) and libVLC's engine (VlcEngine).
            "AheadFirstLoadControl", "AndroidPlaybackTestEnv", "AudioButtonAnswer", "AudioPolicy", "ChatSubtitleLoader",
            "ControlLabels", "CrashPlayingNote", "DecoderPolicy", "EngineAvailability",
            "EngineCrashWatch", "EngineDevice", "Engine", "ExoEngine", "ExternalPlayHost",
            "ExternalSubtitleFile", "FailedFormat", "FitText", "HoldSpeedKey",
            "LastVideoCodec", "LatestWriter", "LinkAtTime", "Media3Probe", "PinchTracker",
            "PlaybackMarks", "PlaybackRecorder", "PlaybackStatsOverlay", "PlaybackStatsText", "PlaybackTestRunner",
            "PlaybackTestSession", "RemedyFacts", "RemedyRunner", "SessionFacts", "SpeedLabelDrawable", "StalledSheet",
            "SubtitleCueFit", "SubtitleOfferChip", "TdReadCounters", "TraceFile", "TraceSampler", "TrackKeys", "TunnelingSupport",
            "VlcBackend", "VlcOptions",
            // The remote key helper (what each key means in the player), pure Kotlin over KeyEvent.
            "PlayerRemoteKeys",
            // The row around a pause, kept out of the activity for its focus test (PB-01).
            "PauseRow",
            // The corner window's own buttons on Android 8 to 10 (WP-36); iOS has no such window.
            "PipActions",
            // The savable verdict told to iOS's AirPlay (SEC2-01).
            "ExternalPlaybackVerdict",
        )
            .forEach { include("com/tmplayer/player/$it.kt") }
    }
    // The build flavour without libVLC, the one iOS matches: VLC is never offered there.
    from(rootProject.layout.projectDirectory.dir("app/src/novlc/java"))
    into(layout.buildDirectory.dir("android-player/kotlin"))
    // A downloaded video plays without waiting for Telegram's sign in (iosPlayer in
    // gradle/ios-apple-variant.gradle.kts).
    inputs.file(rootProject.file("gradle/ios-apple-variant.gradle.kts"))
    doLast {
        applePlayer.forEach { path ->
            val file = destinationDir.resolve(path)
            file.writeText(forApple("iosPlayer", path, file.readText()))
        }
    }
}

kotlin {
    // A library: the framework Swift links is :ios:kit, which keeps this module's API to itself.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        iosMain {
            kotlin.srcDir(generateBuildInfo)
            kotlin.srcDir(androidScreensForIos)
            kotlin.srcDir(desktopPiecesForIos)
            kotlin.srcDir(androidPlayerForIos)
            kotlin.srcDir("src/iosStandIns/kotlin")
            dependencies {
                implementation(project(":core"))
                implementation(project(":ui"))
                implementation(libs.androidx.lifecycle.runtime.compose)
                implementation(libs.androidx.lifecycle.viewmodel.compose)
                implementation(libs.tdl.coroutines)
                implementation(libs.cmp.runtime)
                implementation(libs.cmp.ui)
                implementation(libs.cmp.foundation)
                implementation(libs.cmp.material3)
            }
        }
        // Checks on the iOS simulator: ./gradlew :ios:iosSimulatorArm64Test
        iosTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
