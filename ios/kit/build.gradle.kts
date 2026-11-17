// The framework the Xcode project in iosApp/ links (TMPlayerKit): the few classes Swift calls, the
// app's one view controller and the player's stream. TMPlayer's screens and shell are in :ios,
// which this module depends on without exporting: a framework exports its own module's public API
// to Objective-C, and the whole app's is about 60,000 lines of header that also trip over Darwin's
// macros (BIG_ENDIAN). See docs/IOS.md.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "TMPlayerKit"
            isStatic = true
            // For AllocProfile (-perfAllocs) to see Kotlin's own objects: with -PtmSystemAllocator=true,
            // or ORG_GRADLE_PROJECT_tmSystemAllocator=true as an xcodebuild setting, every Kotlin
            // object comes from the system's malloc. Slower: a developer's build only.
            if (project.findProperty("tmSystemAllocator") == "true") freeCompilerArgs += "-Xallocator=std"
            // Kotlin/Native's binary options to try on a device before keeping one, as
            // "name=value,name=value" (-PtmBinaryOptions=..., or ORG_GRADLE_PROJECT_tmBinaryOptions
            // as an xcodebuild setting), for example latin1Strings=true,pagedAllocator=false.
            (project.findProperty("tmBinaryOptions") as? String)?.split(',')?.forEach { option ->
                val name = option.substringBefore('=').trim()
                val value = option.substringAfter('=', "").trim()
                if (name.isNotEmpty() && value.isNotEmpty()) binaryOption(name, value)
            }
            // Compiler options to try, separated by ";" (-PtmCompilerArgs=..., or
            // ORG_GRADLE_PROJECT_tmCompilerArgs as an xcodebuild setting), for example
            // -Xdisable-phases=EscapeAnalysis when a Release link needs less memory.
            (project.findProperty("tmCompilerArgs") as? String)?.split(';')?.map { it.trim() }
                ?.filter { it.isNotEmpty() }?.let { freeCompilerArgs += it }
        }
        // PerfSampler's look at another thread, in C (src/nativeInterop/cinterop/sampler.def).
        target.compilations.getByName("main").cinterops.create("sampler") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/sampler.def"))
        }
        // AllocProfile's hook in libmalloc's logger, in C (src/nativeInterop/cinterop/allocs.def).
        target.compilations.getByName("main").cinterops.create("allocs") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/allocs.def"))
        }
    }

    sourceSets {
        iosMain {
            dependencies {
                implementation(project(":ios"))
                implementation(project(":core"))
                implementation(project(":ui"))
                implementation(libs.tdl.coroutines)
                implementation(libs.cmp.runtime)
                implementation(libs.cmp.ui)
                implementation(libs.cmp.foundation)
                implementation(libs.cmp.material3)
            }
        }
    }
}
