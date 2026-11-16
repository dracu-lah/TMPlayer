package com.tmplayer.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.tmplayer.data.DeviceForm
import com.tmplayer.i18n.Strings

/**
 * The quick tour (CP69): after the setup's choices, a few pages that show rather than ask. Each is
 * one cropped, marked screenshot of the part of the app it is about, with numbered badges over it
 * and the same numbers in front of a short line each, so nothing in a picture needs translating.
 *
 * A fresh sign in gets it straight after the setup; somebody updating from a version without it is
 * offered it once ([com.tmplayer.data.SettingsStore.tourState]); Settings opens it at any time.
 */
enum class TourPage {
    /** Where the videos come from: Saved Messages, chats and channels, search. */
    Videos,

    /** Home's rows, and how a chat gets starred into them. */
    Home,

    /** The player: what the timebar's lighter part means, the track buttons, Skip intro, Lock the screen. */
    Player,

    /** Gestures on a phone, the remote's keys on a television, the keyboard on a desktop. */
    Controls,

    /** Downloading a video or a season, and the Downloads screen. */
    Downloads,

    /** The row TMPlayer keeps on the Android TV home screen. Television only. */
    TvHome,

    /** Which Settings page holds what. */
    Settings,
    ;

    /** The page's name in its pictures' file names: `tour_<name>` on Android, `tour-<name>` on the desktop. */
    val imageName: String get() = name.lowercase()
}

object Tour {

    /** The pages [form] gets, in order. */
    fun pages(form: DeviceForm): List<TourPage> =
        TourPage.entries.filter { it != TourPage.TvHome || form == DeviceForm.Tv }
}

/** Where the tour is: Next and Back move, Back on the first page leaves it as Skip all does. */
class TourProgress(val pages: List<TourPage>, start: Int = 0) {
    init {
        require(pages.isNotEmpty()) { "a tour with no pages is not shown" }
    }

    var index by mutableIntStateOf(start.coerceIn(0, pages.lastIndex))
        private set

    val page: TourPage get() = pages[index]
    val isFirst: Boolean get() = index == 0
    val isLast: Boolean get() = index == pages.lastIndex

    /** Moves on a page; false when this was the last, which means the tour is done. */
    fun next(): Boolean {
        if (isLast) return false
        index++
        return true
    }

    /** Moves back a page; false on the first. */
    fun back(): Boolean {
        if (isFirst) return false
        index--
        return true
    }
}

/** A tour page's heading, and its numbered lines in the order of the badges on its picture. */
data class TourCopy(val title: String, val lines: List<String>)

fun Strings.tourCopy(page: TourPage, form: DeviceForm): TourCopy {
    val tv = form == DeviceForm.Tv
    val formKey = when (form) {
        DeviceForm.Tv -> "tv"
        DeviceForm.Desktop -> "desktop"
        DeviceForm.Phone -> "phone"
    }
    return when (page) {
        TourPage.Videos -> TourCopy(
            tourVideosTitle,
            listOf(tourVideosSaved, tourVideosChats, tourVideosSearch(formKey)),
        )
        TourPage.Home -> TourCopy(
            tourHomeTitle,
            listOf(tourHomeContinue, tourHomeRecent, tourHomeStar(formKey)),
        )
        TourPage.Player -> TourCopy(
            tourPlayerTitle,
            // Lock the screen is on the menu, not in the picture, so its line has no badge.
            listOf(tourPlayerTimebar, tourPlayerTracks, tourPlayerSkip, tourPlayerLock(formKey)),
        )
        TourPage.Controls -> when (form) {
            DeviceForm.Phone -> TourCopy(tourControlsTitlePhone, listOf(tourControlsPhoneJump, tourControlsPhoneSwipe, tourControlsPhoneTap))
            DeviceForm.Tv -> TourCopy(tourControlsTitleTv, listOf(tourControlsTvOk, tourControlsTvRail, tourControlsTvBack))
            DeviceForm.Desktop -> TourCopy(tourControlsTitleDesktop, listOf(tourControlsDesktopKeys, tourControlsDesktopFullscreen, tourControlsDesktopMenu))
        }
        TourPage.Downloads -> TourCopy(
            tourDownloadsTitle,
            listOf(tourDownloadsSave, tourDownloadsList, tourDownloadsRemove),
        )
        TourPage.TvHome -> TourCopy(tourTvHomeTitle, listOf(tourTvHomeRow, tourTvHomeAdd))
        TourPage.Settings -> TourCopy(
            tourSettingsTitle,
            // The pages as U20 grouped them, one line each, in the order the picture's badges mark:
            // Playback (which says where the languages went), Storage and downloads, Privacy and
            // lock, Appearance.
            listOf(
                tourSettingsPlaybackPage,
                if (tv) tourSettingsDownloadsPageTv else tourSettingsDownloadsPage,
                tourSettingsPrivacyPage,
                tourSettingsAppearance,
            ) +
                // Where Settings is, last and without a badge: the picture is the page itself, and
                // on every device it opens from the profile picture's menu rather than a place on
                // the bar (2026-10-21).
                listOf(tourSettingsWhereDesktop),
        )
    }
}
