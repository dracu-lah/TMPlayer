package androidx.compose.material3

import android.content.Context

// Material You's wallpaper colours exist on Android 12 and later only. On iOS the screens ask
// whether to offer them (wallpaperColoursOffered is false) before calling these, so these only
// have to answer with the plain schemes.

fun dynamicDarkColorScheme(context: Context): ColorScheme = darkColorScheme()

fun dynamicLightColorScheme(context: Context): ColorScheme = lightColorScheme()
