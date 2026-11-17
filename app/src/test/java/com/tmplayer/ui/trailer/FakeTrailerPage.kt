package com.tmplayer.ui.trailer

import android.content.Context
import android.graphics.Color
import android.view.View

/** A page that records what the dialog asks of it, in place of the WebView, which Robolectric has not got. */
class FakeTrailerPage(context: Context, val html: String, val remote: Boolean, val events: TrailerPageEvents) : TrailerPage {
    override val view: View = View(context).apply { setBackgroundColor(Color.BLACK) }
    val scripts = mutableListOf<String>()
    var paused = 0
    var resumed = 0
    var destroyed = 0

    override fun run(script: String) {
        scripts += script
    }

    override fun pause() {
        paused++
    }

    override fun resume() {
        resumed++
    }

    override fun exitFullscreen(): Boolean = false

    override fun destroy() {
        destroyed++
    }
}

/** Makes [FakeTrailerPage]s and keeps the last one. */
class FakeTrailerPages : TrailerPageFactory {
    var last: FakeTrailerPage? = null
    var fail = false

    override fun create(context: Context, html: String, remote: Boolean, events: TrailerPageEvents): TrailerPage {
        if (fail) throw IllegalStateException("no WebView")
        return FakeTrailerPage(context, html, remote, events).also { last = it }
    }
}
