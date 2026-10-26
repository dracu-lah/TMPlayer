import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.sentry.android)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Optional release signing: create keystore.properties (gitignored) to enable
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// The architectures a release build carries, and so both what the universal APK weighs and which
// per-ABI APKs exist beside it. Overridable from the command line for a one-off build for hardware
// this project does not otherwise ship for:
//
//     ./gradlew :app:assembleRelease -PtmAbis=x86_64
//
// It is a property rather than a longer default list on purpose. Adding an architecture here adds
// it to the universal APK as well, and that file is what nearly everyone downloads: x86_64 alone
// put 29 MB onto every transfer for emulators and a handful of Chromebooks, which is what made it
// worth removing in the first place.
val releaseAbis: List<String> = (findProperty("tmAbis") as String?)
    ?.split(",")
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?: listOf("armeabi-v7a", "arm64-v8a")

// The build F-Droid makes from source, switched on by its recipe:
//
//     ./gradlew :app:assembleRelease -PtmFdroid=true
//
// F-Droid installs and updates the app itself, so in that build the app never asks GitHub for a
// newer release, draws nothing about one (BuildConfig.UPDATE_CHECK, read into Updates.enabled),
// and leaves REQUEST_INSTALL_PACKAGES out of the release manifest (src/fdroid/AndroidManifest.xml).
// It also has no Cast button: Google's Cast framework is not a dependency there at all, the code
// that speaks to it (src/cast) is swapped for a stand-in that offers nothing (src/nocast), and
// the overlay takes the framework's options provider back out of the manifest.
// Off, which is every other build, nothing changes.
val fdroidBuild: Boolean = (findProperty("tmFdroid") as String?)?.trim()?.toBoolean() ?: false

// versionCode from the version name, under the release numbering (docs/RELEASING.md, and
// ReleaseVersion.androidCode in :core, which an app test holds this to): major*1000000 +
// minor*10000 + patch*100 + stage, where a test version x.y.z-beta.N is stage N, the release 99
// and any other suffix (-dev, -hx2) 0. 1.28.2-beta.1 is 1280201 and 1.28.2 is 1280299, above
// every code of the old scheme (12801 for 1.28.1). The release workflow passes the same number
// from scripts/release-version.py.
fun releaseVersionCode(name: String): Int {
    val clean = name.trim().removePrefix("v").substringBefore('+')
    val fields = clean.substringBefore('-').split('.').map { it.toIntOrNull() }
    if (fields.size != 3 || fields.any { it == null }) throw GradleException("tmVersionName $name is not x.y.z or x.y.z-suffix")
    val (major, minor, patch) = fields.map { it!! }
    val suffix = clean.substringAfter('-', "")
    val stage = when {
        suffix.isEmpty() -> 99
        else -> Regex("""beta\.(\d+)""").matchEntire(suffix)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..98 } ?: 0
    }
    if (major < 1 || minor > 99 || patch > 99) throw GradleException("tmVersionName $name does not fit the versionCode scheme")
    val code = major.toLong() * 1_000_000 + minor * 10_000L + patch * 100L + stage
    if (code > 2_100_000_000L) throw GradleException("tmVersionName $name passes Google Play's versionCode ceiling")
    return code.toInt()
}

// The FFmpeg extension. scripts/build-media3ext.sh builds nextlib-media3ext from its pinned
// source with TMPlayer's extra video decoders (MPEG-4 Part 2, DivX 3, VC-1, Motion JPEG) into
// this AAR; without it the build takes the same nextlib from Maven Central, which lacks them and
// says so. A release (and F-Droid's recipe) passes -PtmRequireMedia3ext=true, which fails the
// build instead of shipping without them.
val media3extAar = rootProject.file("build/media3ext/nextlib-media3ext-${libs.versions.nextlib.get()}-tm1.aar")
val requireMedia3ext: Boolean = (findProperty("tmRequireMedia3ext") as String?)?.trim()?.toBoolean() ?: false
if (!media3extAar.isFile) {
    if (requireMedia3ext) {
        throw GradleException("${media3extAar.name} is missing: run scripts/build-media3ext.sh first")
    }
    logger.lifecycle("FFmpeg extension: nextlib from Maven Central, without the extra video decoders (scripts/build-media3ext.sh builds them)")
}

android {
    namespace = "com.tmplayer"
    // 37 because tdl-coroutines 14+ refuses to be compiled against anything older. targetSdk is
    // separate and stays where it is.
    compileSdk = 37

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "com.tmplayer"
        // Android 6. The floor is the highest minSdk among the libraries (TDLib's tdl-coroutines,
        // Media3, nextlib, Compose, AndroidX and the Cast framework all declare 23), so older TVs
        // such as the Fire TV Stick 4K on Fire OS 6 (Android 7.1) and the Android 6 and 7 boxes
        // install it. What only Android 8 and later have is guarded where it is called
        // (LegacyApiCompat.kt); `:app:lintRelease` reports a new one in :app as NewApi.
        minSdk = 23
        targetSdk = 35
        // A release is cut by pushing a git tag, and the workflow passes the version through as
        // Gradle properties. The name below is what a local build gets, and it tracks the most
        // recent tag: leaving it behind means a local build cannot be installed over the release
        // it is meant to be debugging, since Android refuses the downgrade.
        // Every APK in a release carries this same code, the universal one and the per-ABI ones
        // alike. A store would want them to differ, so that it can offer the right file as an
        // upgrade of the wrong one; nothing here goes to a store. For sideloading, equal codes are
        // what lets somebody who took the universal file swap to the arm64 one without Android
        // refusing it as a downgrade, which an offset scheme would cause in one direction.
        val tmVersionName = (findProperty("tmVersionName") as String?) ?: "1.28.1"
        versionCode = (findProperty("tmVersionCode") as String?)?.toInt() ?: releaseVersionCode(tmVersionName)
        versionName = tmVersionName

        // Telegram API credentials. Bring your own via local.properties (see README)
        buildConfigField("int", "TG_API_ID", localProps.getProperty("TG_API_ID") ?: "0")
        buildConfigField("String", "TG_API_HASH", "\"${localProps.getProperty("TG_API_HASH") ?: ""}\"")

        // Where an opted-in crash report goes. Empty is the normal case and the honest default:
        // with no DSN the reporting code never initialises, the Settings switch is not offered,
        // and a fork or a CI build carries no endpoint of mine. Set SENTRY_DSN in local.properties
        // or in the environment. It is not a secret in the usual sense, since a DSN that ships in
        // an APK can be read out of it, but it is still nobody's business but the project's.
        val sentryDsn = localProps.getProperty("SENTRY_DSN")
            ?: System.getenv("SENTRY_DSN")
            ?: ""
        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")

        // The OpenSubtitles app key for online subtitles. Like the DSN, optional: a build without
        // it hides the feature (OnlineSubtitles.inBuild). Set OPENSUBTITLES_API_KEY in
        // local.properties or the environment; CI takes it from the repository secret.
        val openSubtitlesKey = localProps.getProperty("OPENSUBTITLES_API_KEY")
            ?: System.getenv("OPENSUBTITLES_API_KEY")
            ?: ""
        buildConfigField("String", "OPENSUBTITLES_API_KEY", "\"$openSubtitlesKey\"")
        // The TMDB key for posters and overviews. Optional: a build without it still finds shows on
        // TVmaze and anime on AniList, and Settings asks for a key of the viewer's own for films.
        // Set TMDB_API_KEY in local.properties or the environment; CI takes it from the secret.
        val tmdbKey = localProps.getProperty("TMDB_API_KEY")
            ?: System.getenv("TMDB_API_KEY")
            ?: ""
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbKey\"")
        // The OMDb key for IMDb's, Rotten Tomatoes' and Metacritic's scores on a detail page.
        // Optional: without it the page shows the provider's own score alone. Set OMDB_API_KEY in
        // local.properties or the environment; CI takes it from the secret.
        val omdbKey = localProps.getProperty("OMDB_API_KEY")
            ?: System.getenv("OMDB_API_KEY")
            ?: ""
        buildConfigField("String", "OMDB_API_KEY", "\"$omdbKey\"")

        // Donation links and the support card, all behind one switch (SupportReminder.enabled).
        // The `play` flavor planned in CP35 sets this to false: Play does not allow donation links
        // that bypass its billing.
        buildConfigField("boolean", "SUPPORT_LINKS", "true")

        // The update check and the APK install that follows it, off in the F-Droid build (see
        // fdroidBuild above): F-Droid is what updates that build.
        buildConfigField("boolean", "UPDATE_CHECK", "${!fdroidBuild}")

        // English only. Every androidx and Media3 dependency ships translations for ~80
        // locales, and none of this app's own strings are translated, so the rest is dead
        // weight in resources.arsc. Revisit the moment the UI itself is localised.
        androidResources.localeFilters += listOf("en")

    }

    // Per-ABI APKs beside the universal one. This was tried and dropped once, on the grounds that
    // a viewer who takes the wrong file gets an install that fails at the first video rather than
    // at install time. That is not what happens: an APK whose native libraries match no ABI on the
    // device is refused by the package installer itself, with INSTALL_FAILED_NO_MATCHING_ABIS,
    // before anything is unpacked. The wrong choice costs a download and a clear error, not a
    // broken app, so the megabytes are worth having after all.
    //
    // Measured on 1.11.1, where 92 per cent of the download is native code:
    //
    //     universal     47.8 MB   both architectures, still the default download
    //     arm64-v8a     29.2 MB   nearly every television, stick and phone made since 2017
    //     armeabi-v7a   22.8 MB   the older 32-bit sticks
    //
    // The universal APK is unchanged and stays the file the site links to, because "works
    // everywhere" is the right default for something people sideload onto a television with a
    // remote control. The split files are for anyone who knows what their device is.
    splits {
        abi {
            // Release only. Enabled unconditionally this would package five APKs on every debug
            // build too, which for a 48 MB app is a slow way to change one line of Kotlin.
            // Reading the task names at configuration time is what makes that possible; it is
            // also the one thing here that would need rethinking if the configuration cache is
            // ever switched on in gradle.properties.
            isEnable = gradle.startParameter.taskNames.any { it.contains("Release") }
            reset()
            include(*releaseAbis.toTypedArray())
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            // Compressed in the APK and extracted at install. TDLib and FFmpeg for two ABIs are
            // almost all of the universal APK, and stored uncompressed they made it a 52 MB
            // download; deflated it is about half that. The cost is a second, extracted copy on
            // the device, which every phone and TV we target can afford more easily than a
            // download twice the size.
            useLegacyPackaging = true
        }
        resources.excludes += setOf("META-INF/*.version", "kotlin/**", "DebugProbesKt.bin")
    }

    signingConfigs {
        if (!keystoreProps.isEmpty) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // The architectures the published APK carries, and the whole of why it is the size
            // it is: TDLib alone is 20 MB per architecture, FFmpeg another 4.
            //
            // Until now no such list was applied at all. `splits.abi.include` decides which
            // per-ABI APKs are produced and has no say over what goes into the universal one,
            // which takes every architecture the dependencies happen to carry. TDLib ships four,
            // so every download held a 24 MB x86 copy of it, plus x86 builds of FFmpeg and the
            // Media3 extension, for a platform the project never offered a build for.
            //
            // x86_64 goes with it, being emulators and a handful of Chromebooks rather than any
            // television or phone, and 29 MB of every viewer's download. Both stay available to
            // developers, because this filter is on the release build alone: a debug build still
            // carries every architecture, and the x86_64 emulator that BUILDING.md names as the
            // reference environment still runs one. Only the file the public downloads is cut.
            //
            // See releaseAbis above for the list itself and how to override it for a one-off.
            ndk.abiFilters += releaseAbis

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // A preview build for testers in the community group: the release build (minified,
        // signed, the same speed) under its own package, so it installs beside the real app and
        // leaves it, its sign in and its downloads alone. No updater, since the only thing it could
        // offer is the release it is ahead of, and no crash reports, since its traces would land
        // among the release's with no mapping uploaded for them.
        // `./gradlew :app:assemblePreview -PtmRequireMedia3ext=true -PtmVersionName=<next release>`
        create("preview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
            buildConfigField("boolean", "UPDATE_CHECK", "false")
            buildConfigField("String", "SENTRY_DSN", "\"\"")
            matchingFallbacks += listOf("release")
        }
        create("promo") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".promo"
            // `-PpromoShots` drops the suffix, so the site's screenshots show the version people
            // download rather than "1.24.0-promo" in the rail and on the About line.
            versionNameSuffix = if (project.hasProperty("promoShots")) null else "-promo"
            matchingFallbacks += listOf("debug")
        }
    }

    // The F-Droid build's manifest overlay, which removes the install permission. On the release
    // source set because a release is all F-Droid builds; debug and promo keep main's manifest.
    // The promo fixture's demo library, plain Kotlin that the desktop's promo render test compiles
    // too, so every platform's screenshots show the same chats and titles.
    sourceSets.getByName("promo").kotlin.directories += "src/promo/shared"
    // The promo build's own name, so it is told apart from the real one on a launcher; with
    // `-PpromoShots` the real name, since the home screen channel shots carry it over the row.
    // The preview build's name and marked TV banner, so it is told apart from the real one.
    sourceSets.getByName("preview").res.directories += "src/preview/label"
    sourceSets.getByName("promo").res.directories += if (project.hasProperty("promoShots")) "src/promo/shots-label" else "src/promo/label"

    if (fdroidBuild) {
        sourceSets.getByName("release").manifest.srcFile("src/fdroid/AndroidManifest.xml")
    }
    // Casting (CP73): Google's Cast framework behind CastBuild in the regular build, a stand-in
    // with no Google code in the F-Droid one. Every build type of one build gets the same side.
    sourceSets.getByName("main").kotlin.directories += if (fdroidBuild) "src/nocast/java" else "src/cast/java"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // The shared core uses java.time and java.nio.file (File.toPath(), Files.move,
        // FileChannel.open), which Android only has from 8.0. Desugaring rewrites those calls at
        // build time to a bundled copy, so nothing is looked up or loaded at start-up: the classes
        // load lazily on first use, the same as the platform's would.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
        viewBinding = false
    }


    testOptions {
        unitTests {
            // Without this, every android.jar method is a stub that throws, so a single
            // Log.w in otherwise pure code fails the test that covers it, which pushes
            // logging out of exactly the error paths that most need it.
            isReturnDefaultValues = true
            // Robolectric reads the real resources, so a vectorResource in a smoke test resolves.
            isIncludeAndroidResources = true
            all {
                // The render tests draw whole screens in Robolectric (the TV's top bar, Settings and
                // its pages, the player), more than the test worker's default heap holds: CI ran
                // out of memory part way once they grew (2026-10-19).
                it.maxHeapSize = "3g"
                // A fresh process for each test class. After many render tests in one process,
                // Compose's frame clock under Robolectric stopped delivering frames, so effects
                // inside a screen never started: the fast scroller's handle never came out and a
                // changed setting never reached the screen (CP101), and whole runs sat for close to
                // an hour on CI. Each class starting clean costs a few seconds and removes all of it.
                it.forkEvery = 1
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    // TDLib, the repositories, settings, the download queue and the streaming window: everything
    // that is not Android's, shared with the desktop app. It brings tdl-coroutines, coroutines and
    // DataStore's preferences core with it.
    implementation(project(":core"))
    // The Compose pieces shared with the desktop: palette and Material 3 theme, icons, skeletons,
    // thumbnails, the QR code, back handling and the device form. Built against the same BOM.
    implementation(project(":ui"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Telegram: prebuilt TDLib 1.8.61 with a typed coroutine API and native libs for every ABI
    implementation(libs.tdl.coroutines)
    implementation(libs.zxing.core)

    // Playback: Media3 core and NextLib's FFmpeg audio renderers for DTS / TrueHD / E-AC3
    // tracks the stick can't decode natively. The transport UI and the track picker are the app's
    // own; leanback stays only for the TV player's theme (values-television/themes.xml).
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    // Only for 3D video: the anaglyph shader ([Stereo3dEffect]). Nothing goes through it otherwise.
    implementation(libs.androidx.media3.effect)
    // The transport controls the system draws for us: notification, lock screen, headset
    // buttons and the Assistant, none of which the app has to lay out itself.
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.leanback)
    implementation(libs.androidx.fragment.ktx)
    if (media3extAar.isFile) {
        // The AAR's own dependencies, which a file dependency does not bring: the ones the Maven
        // Central POM lists, Media3 itself being above.
        implementation(files(media3extAar))
        implementation(libs.androidx.annotation)
        implementation(libs.errorprone.annotations)
    } else {
        implementation(libs.nextlib.media3ext)
    }

    // Casting to a Chromecast or Google TV (CP73), in every build but F-Droid's: see fdroidBuild.
    // Looked up by name rather than through the typed accessor, so a recipe that deletes the
    // library's line from the catalog (F-Droid's scanner reads it) still has a script that compiles.
    if (!fdroidBuild) {
        val catalog = project.extensions.getByType<VersionCatalogsExtension>().named("libs")
        implementation(catalog.findLibrary("play-services-cast-framework").get())
        implementation(libs.androidx.mediarouter)
        // SMS User Consent for the sign in code (ui/auth/SmsCodeConsent.kt in src/cast): no SMS
        // permission, the viewer allows each message in a system prompt.
        implementation(catalog.findLibrary("play-services-auth-api-phone").get())
    }

    // Crash reporting, off unless the viewer turns it on in Settings. Nothing here runs at
    // startup: auto-init is switched off in the manifest and CrashReports does the rest.
    implementation(libs.sentry.android.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // The unit tests run against android.jar stubs, whose org.json returns nothing, and the UI
    // text catalog is parsed with org.json. The real library goes first on the test classpath.
    testImplementation(libs.json)
}

// The :core models that are immutable in fact though the compiler cannot see it; the file says which.
composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf"))
}

// The R8 mapping for crash reports, and nothing else of Sentry's plugin. A release build is
// minified, so without its mapping a report's stack trace is a list of renamed classes. The
// plugin writes a UUID into the APK and the matching mapping is uploaded under it, but only when
// SENTRY_AUTH_TOKEN is set (the release workflow, or a local `export`): any other build, a fork's
// included, uploads nothing and needs no account. No SDK is added (the app picks its own, core
// only), no code is instrumented, and the plugin sends no telemetry of its own.
sentry {
    org.set(System.getenv("SENTRY_ORG") ?: "nevil-i0")
    // The project is the DSN's last path segment, its numeric id, which sentry-cli takes as well
    // as a slug, so nothing but the DSN has to be configured.
    val dsn = localProps.getProperty("SENTRY_DSN") ?: System.getenv("SENTRY_DSN") ?: ""
    projectName.set(System.getenv("SENTRY_PROJECT") ?: dsn.substringAfterLast('/', ""))
    authToken.set(System.getenv("SENTRY_AUTH_TOKEN") ?: "")
    val upload = !System.getenv("SENTRY_AUTH_TOKEN").isNullOrBlank() && dsn.isNotBlank()

    includeProguardMapping.set(true)
    autoUploadProguardMapping.set(upload)
    uploadNativeSymbols.set(false)
    includeNativeSources.set(false)
    includeSourceContext.set(false)
    includeDependenciesReport.set(false)
    telemetry.set(false)
    // Release only: the debug and promo builds are not minified, and never report anything.
    ignoredVariants.set(listOf("debug", "promoDebug", "promoRelease", "promo", "preview"))

    tracingInstrumentation {
        enabled.set(false)
    }
    autoInstallation {
        enabled.set(false)
    }
}
