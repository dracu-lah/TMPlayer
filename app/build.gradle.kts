import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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

android {
    namespace = "com.tmplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tmplayer"
        minSdk = 26
        targetSdk = 35
        // A release is cut by pushing a git tag, and the workflow passes the version through as
        // Gradle properties. The literals below are what a local build gets, and they track the
        // most recent tag: leaving them behind means a local build cannot be installed over the
        // release it is meant to be debugging, since Android refuses the downgrade.
        versionCode = (findProperty("tmVersionCode") as String?)?.toInt() ?: 11000
        versionName = (findProperty("tmVersionName") as String?) ?: "1.10.0"

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

        // English only. Every androidx and Media3 dependency ships translations for ~80
        // locales, and none of this app's own strings are translated, so the rest is dead
        // weight in resources.arsc. Revisit the moment the UI itself is localised.
        androidResources.localeFilters += listOf("en")

    }

    // One APK, and only one. The per-ABI builds were four extra artifacts on every release for a
    // sideloaded app whose viewers have to be told which file to take, and the guessing is worth
    // more than the megabytes: a viewer who picks the wrong one gets an install that fails at the
    // first video rather than at install time. The size that made them tempting came from the
    // universal APK carrying architectures nobody asked for, which `ndk.abiFilters` above is the
    // actual fix for.
    packaging {
        jniLibs {
            // The modern default, written down because it is the answer to "how big is it after
            // it is installed": the .so files are stored uncompressed and mapped straight out of
            // the APK, so there is one copy on the device rather than a compressed one in the APK
            // and an extracted one beside it. It makes the download larger than a zipped-up APK
            // would be and the installation considerably smaller, which is the right way round
            // for a 20 MB library that is most of the app.
            useLegacyPackaging = false
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
            ndk.abiFilters += listOf("armeabi-v7a", "arm64-v8a")

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        create("promo") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".promo"
            versionNameSuffix = "-promo"
            matchingFallbacks += listOf("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons)
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

    // Playback: Media3 core, the ready-made leanback TV player UI, and NextLib's
    // FFmpeg audio renderers for DTS / TrueHD / E-AC3 tracks the stick can't decode natively.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.ui.leanback)
    // The transport controls the system draws for us: notification, lock screen, headset
    // buttons and the Assistant, none of which the app has to lay out itself.
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.leanback)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.nextlib.media3ext)

    implementation(libs.androidx.datastore.preferences)

    // Crash reporting, off unless the viewer turns it on in Settings. Nothing here runs at
    // startup: auto-init is switched off in the manifest and CrashReports does the rest.
    implementation(libs.sentry.android.core)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
