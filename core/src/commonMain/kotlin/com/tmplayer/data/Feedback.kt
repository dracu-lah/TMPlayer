package com.tmplayer.data

import java.net.URLEncoder

/**
 * "Report a problem": a GitHub bug report with the facts already filled in, or an email for
 * somebody without a GitHub account.
 *
 * What goes in is the app version, the platform, the device or system, and the UI language. No
 * account, no chat, no file name and nothing else: those are the viewer's to add, or not.
 *
 * The prefilled fields are the ids of `.github/ISSUE_TEMPLATE/bug_report.yml`, and the platform
 * is one of its dropdown's options word for word, or GitHub leaves the dropdown empty. The labels
 * in the email are for the maintainer, who reads the reports in English, so they are not in the
 * catalog.
 */
object Feedback {

    const val ISSUES = "https://github.com/dracu-lah/TMPlayer/issues/new"
    const val TEMPLATE = "bug_report.yml"
    const val FEATURE_TEMPLATE = "feature_request.yml"
    const val EMAIL = "hello@tmplayer.org"

    /** The bug template's dropdown options. */
    enum class Platform(val option: String) {
        AndroidTv("Android TV or Google TV"),
        FireTv("Fire TV"),
        Phone("Android phone or tablet"),
        Windows("Windows"),
        Linux("Linux"),

        /** Not in the dropdown (no macOS build is released), so it is left for the reporter. */
        Other(""),
    }

    /**
     * What a report says about where it came from. [device] is the model or the system ("POCO X5,
     * Android 14", "Windows 11"), [language] the UI language's tag.
     */
    data class Facts(
        val version: String,
        val platform: Platform,
        val device: String,
        val language: String,
    )

    /** The "Device or computer" field: the device, then the UI language when it is not English. */
    fun deviceLine(facts: Facts): String =
        if (facts.language.isBlank() || facts.language == "en") facts.device else "${facts.device}, app language ${facts.language}"

    /** The new issue page with the template and its fields filled in. */
    fun issueUrl(facts: Facts): String {
        val params = buildList {
            add("template" to TEMPLATE)
            if (facts.platform.option.isNotEmpty()) add("platform" to facts.platform.option)
            add("version" to facts.version)
            add("device" to deviceLine(facts))
        }
        return ISSUES + "?" + params.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
    }

    /**
     * The new feature request page (HX64), with the platform ticked: the template asks only where
     * it is needed, so nothing about the device goes in.
     */
    fun featureUrl(facts: Facts): String {
        val where = when (facts.platform) {
            Platform.AndroidTv, Platform.FireTv -> "TV (Android TV, Google TV, Fire TV)"
            Platform.Phone -> "Phone or tablet"
            Platform.Windows -> "Windows"
            Platform.Linux -> "Linux"
            Platform.Other -> ""
        }
        val params = buildList {
            add("template" to FEATURE_TEMPLATE)
            if (where.isNotEmpty()) add("platform" to where)
        }
        return ISSUES + "?" + params.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
    }

    /** The same facts as an email, for somebody without a GitHub account. [feature] for an idea rather than a problem. */
    fun mailto(facts: Facts, feature: Boolean = false): String {
        val subject = if (feature) "TMPlayer ${facts.version}: a feature idea" else "TMPlayer ${facts.version}: a problem"
        val body = buildString {
            appendLine("Platform: ${facts.platform.option.ifEmpty { "other" }}")
            appendLine("App version: ${facts.version}")
            appendLine("Device: ${facts.device}")
            appendLine("App language: ${facts.language}")
            appendLine()
            appendLine(if (feature) "What I would like TMPlayer to do:" else "What happened:")
            appendLine()
        }
        return "mailto:$EMAIL?subject=${encode(subject)}&body=${encode(body)}"
    }

    /**
     * Percent encoding for a query value. URLEncoder writes a space as "+", which GitHub reads as
     * a space but a mail program, following RFC 6068, does not, so spaces become %20.
     */
    internal fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
