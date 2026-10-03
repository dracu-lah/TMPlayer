package com.tmplayer.data

/**
 * What kind of screen the app is on, which decides how it is driven.
 *
 * [Tv] is a remote with a D-pad, [Phone] is a finger, [Desktop] is a mouse and a keyboard. On
 * Android the answer comes from `FormFactor`, which only ever says [Tv] or [Phone].
 */
enum class DeviceForm {
    Phone,
    Tv,
    Desktop,
}
