import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// Everything TMPlayer does that is not a screen and not Android's: the TDLib client, the
// repositories and models, settings, resume records, cache policy, the download queue, update
// checks and the streaming byte window. Shared by the Android app and the desktop app.
//
// Two targets, android and jvm, and nothing else. A source set shared only by JVM targets may use
// the JDK, so commonMain is ordinary JVM Kotlin (java.io.File, HttpURLConnection and the rest);
// what genuinely differs per platform comes in through the small interfaces in
// com.tmplayer.platform and the runner interfaces next to the code that needs them.
//
// The Android target uses AGP's own Kotlin Multiplatform library plugin rather than
// com.android.library with androidTarget(): AGP 9 no longer lets the two be combined, so this is
// the shape the project will need anyway, and a library with no resources and no build variants
// is exactly what the plugin is for (one variant, consumed by every build type of :app).

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // Not for any UI: the Compose compiler infers which classes are stable and records it in the
    // bytecode. Without it every model here would look unstable to :app's screens, which would
    // then redraw rows that had not changed.
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    androidLibrary {
        namespace = "com.tmplayer.core"
        // tdl-coroutines refuses anything older, as in :app.
        compileSdk = 37
        minSdk = 26
    }
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(libs.tdl.coroutines)
            api(libs.kotlinx.coroutines.core)
            api(libs.androidx.datastore.preferences.core)
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.runtime)
        }
        jvmMain.dependencies {
            // Android ships org.json in the platform, and bundling it there would shadow the
            // framework's copy; a plain JVM has none, so only this target carries it.
            implementation(libs.json)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The same bytecode level as `:app`. A toolchain would be tidier, but the build machines run a
// newer JDK and have no 17 to provision, so the target is pinned instead.
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}
tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}
