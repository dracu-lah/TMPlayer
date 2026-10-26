package com.tmplayer.desktop

import java.io.File
import java.util.zip.ZipFile
import kotlin.system.exitProcess

/**
 * Loads and links every class of the app's own code (com.tmplayer.* from :core, :ui and :desktop),
 * so the JVM's bytecode verifier passes over each one at once rather than the first time a viewer
 * reaches it. 1.28.1 shipped a SeriesKt whose SeriesPanel failed verification (HX1): a VerifyError
 * on the first show poster drawn, on Home, in a chat or on a video's page, and the app restarted.
 *
 * The fault was ProGuard's, not the compiler's: the classes kotlinc wrote verify, and the release
 * build's copy, with stack map frames ProGuard computed again, did not. So it runs three times: by
 * `ClassLinkCheckTest` over the compiler's classes on every `:desktop:test`; by
 * `verifyReleaseClasses` over ProGuard's jars before any release image is made; and by
 * [SelfTest] in the packaged app on its bundled runtime, on CI for every installer, so what is
 * checked is exactly what ships.
 *
 * Linking without initialising: `Class.forName(name, false, loader)` only loads, and HotSpot
 * verifies at link time. Asking for the declared fields links the class (HotSpot links it before
 * listing them) without running its static initialiser, so nothing of the app starts up.
 */
object ClassLinkCheck {

    private const val PACKAGE = "com/tmplayer/"

    /** One class per module, to find where each module's classes are: a folder or a jar. */
    private val ANCHORS = listOf(
        "com.tmplayer.data.MediaItem", // :core
        "com.tmplayer.ui.browse.SeriesKt", // :ui
        "com.tmplayer.desktop.ClassLinkCheck", // :desktop
    )

    class Result(val roots: List<File>, val linked: Int, val failures: List<String>) {
        val passed: Boolean get() = linked > 0 && failures.isEmpty()
    }

    /** The folders or jars that hold the app's own classes, on this class's own classpath. */
    fun roots(loader: ClassLoader = ClassLinkCheck::class.java.classLoader): List<File> =
        ANCHORS.map { name ->
            val source = Class.forName(name, false, loader).protectionDomain?.codeSource?.location
                ?: error("no code source for $name")
            File(source.toURI())
        }.distinct()

    /** Every com.tmplayer class name in [roots] (class folders or jars), sorted. */
    fun classNames(roots: List<File>): List<String> {
        val names = sortedSetOf<String>()
        for (root in roots) {
            if (root.isDirectory) {
                val base = File(root, PACKAGE)
                base.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach {
                    names += toName(it.relativeTo(root).invariantSeparatorsPath)
                }
            } else if (root.isFile && root.name.endsWith(".jar")) {
                ZipFile(root).use { zip ->
                    for (entry in zip.entries()) {
                        if (entry.name.startsWith(PACKAGE) && entry.name.endsWith(".class")) names += toName(entry.name)
                    }
                }
            }
        }
        return names.toList()
    }

    private fun toName(path: String) = path.removeSuffix(".class").replace('/', '.')

    /** Loads and links each class in [roots] through [loader], collecting every one that fails. */
    fun run(
        loader: ClassLoader = ClassLinkCheck::class.java.classLoader,
        roots: List<File> = roots(loader),
    ): Result {
        var linked = 0
        val failures = mutableListOf<String>()
        for (name in classNames(roots)) {
            try {
                Class.forName(name, false, loader).declaredFields
                linked++
            } catch (e: Throwable) {
                val lines = e.message.orEmpty().lines()
                // A VerifyError names the method after "Location:"; its descriptor is cut short.
                val method = lines.indexOfFirst { it.trim() == "Location:" }.takeIf { it >= 0 }
                    ?.let { lines.getOrNull(it + 1)?.trim()?.substringBefore('(') }
                failures += "$name: ${e::class.java.name}: ${lines.first()}" + (method?.let { " in $it" } ?: "")
            }
        }
        return Result(roots, linked, failures)
    }

    /**
     * For `verifyReleaseClasses` in desktop/build.gradle.kts, run on ProGuard's output jars as its
     * classpath before the release image is made. Exits 1 when any class fails.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val result = run()
        result.failures.forEach { System.err.println("FAILED $it") }
        println("Linked ${result.linked} com.tmplayer classes from ${result.roots.joinToString { it.name }}, ${result.failures.size} failed.")
        exitProcess(if (result.passed) 0 else 1)
    }
}
