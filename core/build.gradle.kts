import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// Everything TMPlayer does that is not a screen and not Android's: the TDLib client, the
// repositories and models, settings, resume records, cache policy, the download queue, update
// checks and the streaming byte window. Shared by the Android app and the desktop app.
//
// Two app targets, android and jvm; the pure half also builds for the browser (js, for the web
// player's rules in webapp/, see the kotlin block). A source set shared only by JVM targets may use
// the JDK, so commonMain is ordinary JVM Kotlin (java.io.File, HttpURLConnection and the rest);
// what genuinely differs per platform comes in through the small interfaces in
// com.tmplayer.platform and the runner interfaces next to the code that needs them.
//
// The Android target uses AGP's own Kotlin Multiplatform library plugin rather than
// com.android.library with androidTarget(): AGP 9 does not let the two be combined, and a library
// with no resources and no build variants is exactly what the plugin is for (one variant,
// consumed by every build type of :app).

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // Not for any UI: the Compose compiler infers which classes are stable and records it in the
    // bytecode. Without it every model here would look unstable to :app's screens, which would
    // then redraw rows that had not changed.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The UI text catalog, i18n/en.json, turned into the typed `Strings` interface and `object L`
// (com.tmplayer.i18n), so code asks for `L.commonRetry` or `L.browseVideosCount(n)` and a key
// that is not in the catalog fails the build. Each key becomes its dotted path in camel case;
// each ICU argument becomes a parameter: a plural takes a Number, a select a String, a plain
// `{name}` Any. The Kotlin parser in Icu.kt is the real one; this reads only the argument names,
// and CatalogTest checks the two agree.
class IcuArgs(private val src: String) {
    val args = LinkedHashMap<String, String>()
    private var pos = 0
    private var pluralDepth = 0

    init {
        message()
        if (pos < src.length) fail("unmatched }")
    }

    private fun fail(message: String): Nothing = throw GradleException("i18n: $message at $pos in \"$src\"")

    private fun skip() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun word(): String {
        skip()
        val start = pos
        while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] in "_=:")) pos++
        if (start == pos) fail("expected a name")
        return src.substring(start, pos)
    }

    private fun expect(c: Char) {
        skip()
        if (src.getOrNull(pos) != c) fail("expected '$c'")
        pos++
    }

    private fun message() {
        while (pos < src.length) {
            val c = src[pos]
            when {
                c == '\'' -> {
                    val next = src.getOrNull(pos + 1)
                    if (next == '{' || next == '}' || (pluralDepth > 0 && next == '#')) {
                        pos = src.indexOf('\'', pos + 1).takeIf { it >= 0 } ?: fail("unterminated quote")
                    }
                    pos++
                    if (next == '\'') pos++
                }
                c == '{' -> argument()
                c == '}' -> return
                else -> pos++
            }
        }
    }

    private fun argument() {
        expect('{')
        val name = word()
        skip()
        if (src.getOrNull(pos) == '}') {
            pos++
            args.putIfAbsent(name, "VALUE")
            return
        }
        expect(',')
        val type = word()
        if (type != "plural" && type != "select") fail("unsupported type '$type'")
        args[name] = type.uppercase()
        expect(',')
        while (true) {
            skip()
            if (pos >= src.length) fail("unterminated argument")
            if (src[pos] == '}') break
            word()
            expect('{')
            if (type == "plural") pluralDepth++
            message()
            if (type == "plural") pluralDepth--
            expect('}')
        }
        expect('}')
    }
}

fun flattenCatalog(prefix: String, node: Map<*, *>, out: MutableMap<String, String>) {
    for ((name, value) in node) {
        val key = if (prefix.isEmpty()) "$name" else "$prefix.$name"
        when (value) {
            is Map<*, *> -> flattenCatalog(key, value, out)
            is String -> out[key] = value
            else -> throw GradleException("i18n: $key is not text")
        }
    }
}

fun accessorName(key: String): String {
    val words = key.split('.', '_').filter { it.isNotEmpty() }
    val name = words.first() + words.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    if (!Regex("[a-z][A-Za-z0-9]*").matches(name)) throw GradleException("i18n: key '$key' makes no Kotlin name")
    return name
}

fun kotlinString(text: String): String =
    "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("\n", "\\n") + "\""

val generateStrings = tasks.register("generateStrings") {
    val source = file("src/commonMain/resources/i18n/en.json")
    val out = layout.buildDirectory.dir("generated/i18n")
    inputs.file(source)
    outputs.dir(out)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val root = groovy.json.JsonSlurper().parse(source, "UTF-8") as Map<String, Any?>
        val catalog = sortedMapOf<String, String>().also { flattenCatalog("", root, it) }
        val names = HashMap<String, String>()
        val members = StringBuilder()
        for ((key, text) in catalog) {
            val name = accessorName(key)
            names.put(name, key)?.let { throw GradleException("i18n: '$it' and '$key' both make $name") }
            val args = IcuArgs(text).args
            val doc = text.replace("*/", "*\\/").replace("\n", " ")
            members.append("\n    /** $doc */\n")
            if (args.isEmpty()) {
                members.append("    val $name: String get() = messages.text(${kotlinString(key)})\n")
            } else {
                val params = args.entries.joinToString(", ") { (arg, kind) ->
                    val type = when (kind) { "PLURAL" -> "Number"; "SELECT" -> "String"; else -> "Any" }
                    "$arg: $type"
                }
                val pairs = args.keys.joinToString(", ") { "${kotlinString(it)} to $it" }
                members.append("    fun $name($params): String = messages.format(${kotlinString(key)}, $pairs)\n")
            }
        }
        val dir = out.get().asFile.resolve("com/tmplayer/i18n").apply { mkdirs() }
        dir.resolve("Strings.kt").writeText(
            "package com.tmplayer.i18n\n\n" +
                "// Generated by :core's generateStrings from i18n/en.json. Do not edit.\n\n" +
                "/** Every UI string, typed. [Messages] is one language's; [L] is always the current one. */\n" +
                "interface Strings {\n    val messages: Messages\n$members}\n\n" +
                "/** The current UI language's strings, for code outside Compose. Compose reads LocalStrings. */\n" +
                "object L : Strings {\n    override val messages: Messages get() = Translator.messages\n}\n\n" +
                "/** Every catalog key, sorted, as the build saw it. */\n" +
                "internal val CATALOG_KEYS: List<String> = listOf(\n" +
                catalog.keys.joinToString("") { "    ${kotlinString(it)},\n" } + ")\n",
        )
    }
}

// The JVM tests of the pure half that the web player's rules rest on (core/js-test-sources.txt),
// run on Kotlin/JS as well: the browser's RegExp, numbers and collation are not the JDK's, and a
// name the apps group one way must group the same way in the web player. Copied into jsTest only;
// org.junit's few names they use are stood in for under core/src/jsTest/kotlin/org/junit.
val coreJsTests = tasks.register<Sync>("coreJsTests") {
    val list = layout.projectDirectory.file("js-test-sources.txt")
    inputs.file(list)
    val names = list.asFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    from(layout.projectDirectory.dir("src/jvmTest/kotlin")) { include(names) }
    into(layout.buildDirectory.dir("core-js-test/kotlin"))
}

// iOS and iPadOS (docs/IOS.md): configured on a Mac, or on any host with -Ptmplayer.ios=true (the
// klibs then cross-compile, nothing links). Off elsewhere by default, so an Android, desktop or web
// build on Linux or Windows neither configures nor downloads anything for it.
val iosBuild = providers.gradleProperty("tmplayer.ios").orNull?.toBooleanStrictOrNull()
    ?: org.jetbrains.kotlin.konan.target.HostManager.hostIsMac

// The same tests run on iOS too (iosSimulatorArm64Test): Foundation's collation, calendar and
// Unicode tables are not the JDK's either. Kotlin/Native refuses some characters in a name that the
// JVM and JS accept, such as the comma in a test called `a, b`, so the copy drops them from the
// names of test functions and changes nothing else.
val coreIosTests = tasks.register<Sync>("coreIosTests") {
    val list = layout.projectDirectory.file("js-test-sources.txt")
    inputs.file(list)
    val names = list.asFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    from(layout.projectDirectory.dir("src/jvmTest/kotlin")) { include(names) }
    into(layout.buildDirectory.dir("core-ios-test/kotlin"))
    val testName = Regex("(?<=fun )`[^`]+`")
    filter { line -> testName.replace(line) { name -> name.value.filterNot { it in ",()[]<>.:;/\\" } } }
}

// The JVM half's own helpers that need nothing the iOS stand ins lack (TDLib's country list, the
// DataStore adapter, the zone and collator overloads), compiled for iOS straight from jvmSharedMain.
// Upstream's files that use @Synchronized, which locks nothing on Kotlin/Native: iOS compiles
// copies with real locks instead (gradle/ios-real-locks.gradle.kts), named *.ios.kt so the
// originals can be left out by path.
apply(from = rootProject.file("gradle/ios-real-locks.gradle.kts"))
@Suppress("UNCHECKED_CAST")
val withRealLocks = extra["withRealLocks"] as (String) -> String
val commonSources = layout.projectDirectory.dir("src/commonMain/kotlin").asFile
val iosLeftOut = listOf("com/tmplayer/cast/", "com/tmplayer/data/CrashLimits.kt", "com/tmplayer/data/StutterReport.kt")
val lockedForIos = if (!iosBuild) emptyList() else commonSources.walkTopDown()
    .filter { it.isFile && it.extension == "kt" }
    .map { it.relativeTo(commonSources).invariantSeparatorsPath }
    .filter { path -> iosLeftOut.none { path.startsWith(it) } }
    .filter { path -> commonSources.resolve(path).readText().contains("@Synchronized") }
    .sorted()
    .toList()
val lockedSourcesForIos = tasks.register("lockedSourcesForIos") {
    val out = layout.buildDirectory.dir("ios-locked/kotlin")
    inputs.files(lockedForIos.map { commonSources.resolve(it) })
    inputs.file(rootProject.file("gradle/ios-real-locks.gradle.kts"))
    outputs.dir(out)
    doLast {
        val root = out.get().asFile.apply { deleteRecursively() }
        lockedForIos.forEach { path ->
            root.resolve(path.removeSuffix(".kt") + ".ios.kt").apply { parentFile.mkdirs() }
                .writeText(withRealLocks(commonSources.resolve(path).readText()))
        }
    }
}

// The Apple variant's own links in upstream's Feedback and WhatsNew
// (gradle/ios-apple-variant.gradle.kts): iOS compiles copies with them, named *.ios.kt like the
// copies above, and leaves the originals out.
apply(from = rootProject.file("gradle/ios-apple-variant.gradle.kts"))
@Suppress("UNCHECKED_CAST")
val forApple = extra["forApple"] as (String, String, String) -> String
@Suppress("UNCHECKED_CAST")
val appleForIos = (extra["appleVariant"] as Map<String, List<String>>)["core"].orEmpty()
    .onEach { require(it !in lockedForIos) { "$it needs real locks and the Apple variant at once" } }
val appleSourcesForIos = tasks.register("appleSourcesForIos") {
    val out = layout.buildDirectory.dir("ios-apple/kotlin")
    inputs.files(appleForIos.map { commonSources.resolve(it) })
    inputs.file(rootProject.file("gradle/ios-apple-variant.gradle.kts"))
    outputs.dir(out)
    doLast {
        val root = out.get().asFile.apply { deleteRecursively() }
        appleForIos.forEach { path ->
            root.resolve(path.removeSuffix(".kt") + ".ios.kt").apply { parentFile.mkdirs() }
                .writeText(forApple("core", path, commonSources.resolve(path).readText()))
        }
    }
}

val jvmSharedPiecesForIos = tasks.register<Sync>("jvmSharedPiecesForIos") {
    from(layout.projectDirectory.dir("src/jvmSharedMain/kotlin")) {
        include(
            "com/tmplayer/data/PhoneCountriesJvm.kt",
            "com/tmplayer/data/JvmOverloads.kt",
            "com/tmplayer/data/DataStoreKeyValueStore.kt",
            "com/tmplayer/online/OnlineJvm.kt",
            // The app log's files on the disk (AppLog), plain files as on Android.
            "com/tmplayer/platform/PlainLogFiles.kt",
        )
    }
    into(layout.buildDirectory.dir("ios-jvm-shared-pieces/kotlin"))
}

// The jsTest stand in for org.junit. Not its JDK names (System, String.format, MediaMapper): on
// iOS the real ones are compiled (core/src/iosStandIns), nor its runBlocking, which Kotlin/Native has.
val coreIosTestStandIns = tasks.register<Sync>("coreIosTestStandIns") {
    from(layout.projectDirectory.dir("src/jsTest/kotlin")) {
        include("org/junit/Junit.kt")
    }
    into(layout.buildDirectory.dir("core-ios-test-stand-ins/kotlin"))
}

// The English catalog for the JS tests: in the browser the worker provides it (WebRules.provideCatalog),
// and there is no classpath to read it from.
val jsTestCatalog = tasks.register("jsTestCatalog") {
    val source = file("src/commonMain/resources/i18n/en.json")
    val out = layout.buildDirectory.dir("generated/js-test-catalog")
    inputs.file(source)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile.resolve("com/tmplayer/web").apply { mkdirs() }
        dir.resolve("EnCatalog.kt").writeText(
            "@file:OptIn(ExperimentalStdlibApi::class)\n\npackage com.tmplayer.web\n\n" +
                "// Generated by :core's jsTestCatalog from i18n/en.json. Do not edit.\n\n" +
                "@Suppress(\"DEPRECATION\")\n@EagerInitialization\n" +
                "private val provided = com.tmplayer.i18n.Translator.provide(\"en\", " + kotlinString(source.readText()) + ")\n",
        )
    }
}

kotlin {
    // Two halves. commonMain is pure Kotlin, read from src/pureMain/kotlin: no JDK, no TDLib, no
    // DataStore, so it compiles for the browser too (the js target). Everything else is the jvmShared
    // group, android plus jvm, which still reads src/commonMain/kotlin as ordinary JVM Kotlin
    // (java.io.File, HttpURLConnection and the rest), so no file had to move at once. A file moves
    // to pureMain once it stops needing the JDK; what blocks the rest is whatever still names the JDK.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withJvm()
                withCompilations { it.target.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm }
            }
        }
    }
    android {
        namespace = "com.tmplayer.core"
        // tdl-coroutines refuses anything older, as in :app.
        compileSdk = 37
        // As low as :app, which says why; :app desugars java.time and java.nio.file for this code.
        minSdk = 23
    }
    jvm()
    // iOS and iPadOS, one universal app (docs/IOS_PORT_PLAN.md). The pure half for now, with its
    // platform pieces from Foundation in src/iosMain; the TDLib half follows as files leave the JDK.
    if (iosBuild) {
        iosArm64()
        iosSimulatorArm64()
    }
    // The web player's rules (webapp/, CP104): the pure half as one small ES module, run in the
    // page's Telegram worker, so a chat groups, sorts and filters there exactly as in the apps.
    // Only what WebRules exports is kept; the module goes to webapp/src/rules by :core:webRules.
    js {
        outputModuleName = "tmplayer-rules"
        useEsModules()
        nodejs {
            testTask { useMocha { timeout = "20s" } }
        }
        binaries.executable()
        generateTypeScriptDefinitions()
    }

    sourceSets {
        commonMain {
            kotlin.setSrcDirs(listOf("src/pureMain/kotlin"))
            kotlin.srcDir(generateStrings)
            resources.setSrcDirs(emptyList<String>())
        }
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            // @Immutable and @Stable only, which the js target needs no runtime for.
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.runtime.annotation)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        val jvmSharedMain by getting {
            kotlin.srcDir("src/commonMain/kotlin")
            resources.srcDir("src/commonMain/resources")
            dependencies {
                api(libs.tdl.coroutines)
                api(libs.androidx.datastore.preferences.core)
                implementation(libs.androidx.compose.runtime)
                // The shared half of crash reporting (CrashRecord, CrashReportScrubber, CrashLimits),
                // which builds and trims Sentry's events with its protocol classes. Each app brings
                // the SDK up itself: Android through sentry-android-core, which is built on this very
                // artifact at the same version, and the desktop directly.
                api(libs.sentry)
            }
        }
        jsTest {
            kotlin.srcDir(coreJsTests)
            kotlin.srcDir(jsTestCatalog)
        }
        if (iosBuild) {
            iosMain {
                // The TDLib half too, compiled as upstream wrote it against stand ins for the JDK and
                // org.json (docs/IOS_PORT_PLAN.md, "Chosen instead, for now").
                kotlin.srcDir("src/commonMain/kotlin")
                kotlin.srcDir("src/iosStandIns/kotlin")
                kotlin.srcDir(jvmSharedPiecesForIos)
                kotlin.srcDir(lockedSourcesForIos)
                // Not on iOS yet: casting, and crash reports through Sentry's JVM SDK. The updater compiles
                // (WhatsNew reads its feed) but an iOS build never schedules it.
                // CastSource.kt is plain Kotlin, and Android's player names it; the rest waits on the
                // maintainer's decision about DLNA casting on iOS (docs/IOS_PORT_PLAN.md, layer 4).
                val castKept = setOf("com/tmplayer/cast/CastSource.kt", "com/tmplayer/cast/JvmDefaults.kt")
                kotlin.exclude { it.path.startsWith("com/tmplayer/cast/") && it.path !in castKept }
                kotlin.exclude(
                    "com/tmplayer/data/CrashContext.kt",
                    "com/tmplayer/data/CrashRecord.kt",
                    "com/tmplayer/data/CrashReportScrubber.kt",
                    "com/tmplayer/data/CrashLimits.kt",
                    "com/tmplayer/data/StutterReport.kt",
                )
                // The originals of the copies with real locks, and of the Apple variant's.
                kotlin.exclude(lockedForIos)
                kotlin.srcDir(appleSourcesForIos)
                kotlin.exclude(appleForIos)
                dependencies {
                    api(libs.tdl.coroutines)
                    api(libs.androidx.datastore.preferences.core)
                    implementation(libs.cmp.runtime)
                }
            }
            iosTest {
                kotlin.srcDir(coreIosTests)
                kotlin.srcDir(coreIosTestStandIns)
            }
        }
        jvmMain.dependencies {
            // Android ships org.json in the platform, and bundling it there would shadow the
            // framework's copy; a plain JVM has none, so only this target carries it.
            implementation(libs.json)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            // Reads back the pure QR encoder's codes (QrCodeTest).
            implementation(libs.zxing.core)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// On iOS the catalogs are files in the app bundle (bundledCatalog in src/iosMain). A test binary is
// not a bundle: Foundation's main bundle is the folder it sits in, so the catalogs go next to it.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink>()
    .matching { it.binary.outputKind == org.jetbrains.kotlin.gradle.plugin.mpp.NativeOutputKind.TEST }
    .configureEach {
        val catalogs = file("src/commonMain/resources/i18n")
        val binary = outputFile
        inputs.dir(catalogs)
        doLast { catalogs.copyRecursively(binary.get().parentFile.resolve("i18n"), overwrite = true) }
    }

// The Compose compiler only records stability for :app's and :ui's screens, which are JVM; the
// js target compiles the pure half without it.
composeCompiler {
    targetKotlinPlatforms.set(
        setOf(
            org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm,
            org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm,
            // iOS too, so :ui's screens there see core's models as stable, as on Android.
            org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.native,
        ),
    )
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

// The web player's copy of the rules module (webapp/src/rules/core, not committed): `pnpm rules`
// in webapp/ runs this, and `pnpm build` does first.
tasks.register<Sync>("webRules") {
    dependsOn("jsProductionExecutableCompileSync")
    from(layout.buildDirectory.dir("compileSync/js/main/productionExecutable/kotlin"))
    into(rootProject.layout.projectDirectory.dir("webapp/src/rules/core"))
}
