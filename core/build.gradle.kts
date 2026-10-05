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

val generateStrings by tasks.registering {
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

kotlin {
    androidLibrary {
        namespace = "com.tmplayer.core"
        // tdl-coroutines refuses anything older, as in :app.
        compileSdk = 37
        minSdk = 26
    }
    jvm()

    sourceSets {
        commonMain {
            kotlin.srcDir(generateStrings)
        }
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
