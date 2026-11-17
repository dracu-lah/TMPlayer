package com.tmplayer.ios

import android.content.res.ResourceFiles
import android.view.LayoutInflater
import com.tmplayer.R
import com.tmplayer.player.FineTimeBar
import com.tmplayer.player.PlayPauseIcon
import com.tmplayer.player.SheetActions
import com.tmplayer.player.StatusSheet

/** The app's own View classes the inflater makes by name, and :app's R ids for the toolkit. */
internal object AndroidViews {
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        // R.id.x is the id a layout's @+id/x gets, R.layout and R.color resolve (ui/src/iosStandIns).
        ResourceFiles.install(R.references)
        // Kotlin on iOS has no reflection, so each View class the player's layouts name is listed
        // here (every <com.tmplayer...> tag in app/src/main/res/layout).
        LayoutInflater.register("com.tmplayer.player.PlayPauseIcon") { context, attrs -> PlayPauseIcon(context, attrs) }
        LayoutInflater.register("com.tmplayer.player.FineTimeBar") { context, attrs -> FineTimeBar(context, attrs) }
        LayoutInflater.register("com.tmplayer.player.StatusSheet") { context, attrs -> StatusSheet(context, attrs) }
        LayoutInflater.register("com.tmplayer.player.SheetActions") { context, attrs -> SheetActions(context, attrs) }
        // Media3's, named in activity_player.xml.
        LayoutInflater.register("androidx.media3.ui.SubtitleView") { context, attrs -> androidx.media3.ui.SubtitleView(context, attrs) }
    }
}
