pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TMPlayer"
include(":app")
include(":core")
include(":ui")
include(":desktop")
// The iOS and iPadOS app (docs/IOS.md): on a Mac, or on any host with -Ptmplayer.ios=true, as in
// :core and :ui. Off by default elsewhere, so a Linux or Windows build stays as it was.
val iosBuild = providers.gradleProperty("tmplayer.ios").orNull?.toBooleanStrictOrNull()
    ?: System.getProperty("os.name").orEmpty().startsWith("Mac")
if (iosBuild) {
    // The Kotlin half of the iOS and iPadOS app; the Xcode project is iosApp/.
    include(":ios")
    // The framework the Xcode project links, TMPlayerKit, with only what Swift calls in its API.
    include(":ios:kit")
}
