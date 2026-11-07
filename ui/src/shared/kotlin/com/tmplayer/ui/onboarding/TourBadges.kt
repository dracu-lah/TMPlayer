package com.tmplayer.ui.onboarding

import androidx.compose.ui.geometry.Offset
import com.tmplayer.data.DeviceForm

// Written by scripts/tour-badges.py from the tour specs in scripts/annotations/. Do not edit by hand.

/**
 * Where each numbered badge sits on a tour page's picture, as fractions of its width and height, in
 * the order of the page's lines. One list per page and form; a light and a dark picture share it.
 */
internal object TourBadges {
    fun of(page: TourPage, form: DeviceForm): List<Offset> = all[page to form].orEmpty()

    private val all: Map<Pair<TourPage, DeviceForm>, List<Offset>> = mapOf(
        (TourPage.Videos to DeviceForm.Phone) to listOf(Offset(0.065f, 0.14f), Offset(0.065f, 0.5f), Offset(0.606f, 0.922f)),
        (TourPage.Videos to DeviceForm.Tv) to listOf(Offset(0.057f, 0.424f), Offset(0.335f, 0.065f), Offset(0.052f, 0.172f)),
        (TourPage.Videos to DeviceForm.Desktop) to listOf(Offset(0.016f, 0.185f), Offset(0.016f, 0.273f), Offset(0.714f, 0.023f)),
        (TourPage.Home to DeviceForm.Phone) to listOf(Offset(0.033f, 0.625f), Offset(0.102f, 0.487f), Offset(0.282f, 0.926f)),
        (TourPage.Home to DeviceForm.Tv) to listOf(Offset(0.047f, 0.611f), Offset(0.047f, 0.339f), Offset(0.342f, 0.065f)),
        (TourPage.Home to DeviceForm.Desktop) to listOf(Offset(0.016f, 0.65f), Offset(0.021f, 0.37f), Offset(0.45f, 0.9f)),
        (TourPage.Player to DeviceForm.Phone) to listOf(Offset(0.468f, 0.822f), Offset(0.093f, 0.895f), Offset(0.633f, 0.036f)),
        (TourPage.Player to DeviceForm.Tv) to listOf(Offset(0.471f, 0.839f), Offset(0.044f, 0.915f), Offset(0.734f, 0.909f)),
        (TourPage.Player to DeviceForm.Desktop) to listOf(Offset(0.562f, 0.887f), Offset(0.023f, 0.932f), Offset(0.885f, 0.802f)),
        (TourPage.Controls to DeviceForm.Phone) to listOf(Offset(0.037f, 0.333f), Offset(0.824f, 0.183f), Offset(0.398f, 0.4f)),
        (TourPage.Controls to DeviceForm.Tv) to listOf(Offset(0.05f, 0.5f), Offset(0.042f, 0.043f), Offset(0.342f, 0.065f)),
        (TourPage.Controls to DeviceForm.Desktop) to listOf(Offset(0.411f, 0.461f), Offset(0.856f, 0.936f), Offset(0.651f, 0.167f)),
        (TourPage.Downloads to DeviceForm.Phone) to listOf(Offset(0.074f, 0.701f), Offset(0.074f, 0.478f), Offset(0.074f, 0.174f)),
        (TourPage.Downloads to DeviceForm.Tv) to listOf(Offset(0.151f, 0.883f), Offset(0.224f, 0.215f), Offset(0.068f, 0.338f)),
        (TourPage.Downloads to DeviceForm.Desktop) to listOf(Offset(0.312f, 0.213f), Offset(0.316f, 0.143f), Offset(0.021f, 0.148f)),
        (TourPage.TvHome to DeviceForm.Tv) to listOf(Offset(0.054f, 0.089f), Offset(0.065f, 0.256f)),
        (TourPage.Settings to DeviceForm.Phone) to listOf(Offset(0.078f, 0.317f), Offset(0.078f, 0.738f), Offset(0.078f, 0.83f), Offset(0.078f, 0.232f)),
        (TourPage.Settings to DeviceForm.Tv) to listOf(Offset(0.074f, 0.381f), Offset(0.074f, 0.804f), Offset(0.074f, 0.91f), Offset(0.074f, 0.275f)),
        (TourPage.Settings to DeviceForm.Desktop) to listOf(Offset(0.03f, 0.331f), Offset(0.03f, 0.672f), Offset(0.03f, 0.754f), Offset(0.03f, 0.237f)),
    )
}
