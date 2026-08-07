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
        versionCode = (findProperty("tmVersionCode") as String?)?.toInt() ?: 501
        versionName = (findProperty("tmVersionName") as String?) ?: "0.5.1"

        // Telegram API credentials. Bring your own via local.properties (see README)
        buildConfigField("int", "TG_API_ID", localProps.getProperty("TG_API_ID") ?: "0")
        buildConfigField("String", "TG_API_HASH", "\"${localProps.getProperty("TG_API_HASH") ?: ""}\"")

        // English only. Every androidx and Media3 dependency ships translations for ~80
        // locales, and none of this app's own strings are translated, so the rest is dead
        // weight in resources.arsc. Revisit the moment the UI itself is localised.
        resourceConfigurations += listOf("en")

        // The Movie Database, for posters, cast and trailers. Optional on purpose: with no key
        // the app builds and runs exactly as before, and the details panel says the extras are
        // unavailable rather than failing. Contributors should not need a TMDB account.
        buildConfigField(
            "String",
            "TMDB_API_KEY",
            "\"${System.getenv("TMDB_API_KEY") ?: localProps.getProperty("TMDB_API_KEY") ?: ""}\"",
        )
    }

    // One APK per ABI keeps the install ~1/3 the size, which matters on an 8 GB TV stick.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = false
        }
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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

    packaging {
        resources.excludes += setOf("META-INF/*.version", "kotlin/**", "DebugProbesKt.bin")
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
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Telegram: prebuilt TDLib 1.8.66 with a typed coroutine API and native libs for every ABI
    implementation(libs.tdl.coroutines)
    implementation(libs.zxing.core)

    // Playback: Media3 core, the ready-made leanback TV player UI, and NextLib's
    // FFmpeg audio renderers for DTS / TrueHD / E-AC3 tracks the stick can't decode natively.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.ui.leanback)
    implementation(libs.androidx.leanback)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.nextlib.media3ext)

    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
