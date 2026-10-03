import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// The Compose half that the phone and the desktop share (B1.2 of the 2026-10-03 plan): the
// palette and the Material 3 theme, the icons, skeletons, the QR code, thumbnails, and the small
// abstractions a shared screen needs instead of Android's (back handling, a short notice, the
// device form). The television's tv-material screens stay in :app.
//
// Two targets, android and jvm, through the same AGP Kotlin Multiplatform library plugin :core
// uses. The one unusual thing is where the shared code lives. A Compose Multiplatform dependency
// in commonMain would resolve, on Android, to the androidx artifacts at Compose Multiplatform's
// own mapping (1.12 and a Material3 alpha), and Gradle would lift the Android app onto them. The
// app stays on its BOM instead: `src/shared/kotlin` is compiled into both targets as an ordinary
// source directory, against the BOM on Android and against Compose Multiplatform on the desktop.
// What differs per platform sits beside it in androidMain and jvmMain, under the same names, the
// way an expect and its actuals would. Shared code may only use API both versions carry.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    androidLibrary {
        namespace = "com.tmplayer.ui"
        compileSdk = 37
        minSdk = 26
    }
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.zxing.core)
        }
        androidMain {
            kotlin.srcDir("src/shared/kotlin")
            dependencies {
                implementation(project.dependencies.platform(libs.androidx.compose.bom))
                api(libs.androidx.compose.ui)
                api(libs.androidx.compose.foundation)
                api(libs.androidx.compose.material3)
                api(libs.androidx.compose.material.icons)
                implementation(libs.androidx.activity.compose)
            }
        }
        jvmMain {
            kotlin.srcDir("src/shared/kotlin")
            dependencies {
                api(libs.cmp.runtime)
                api(libs.cmp.ui)
                api(libs.cmp.foundation)
                api(libs.cmp.material3)
                api(libs.cmp.material.icons)
            }
        }
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}
tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}
