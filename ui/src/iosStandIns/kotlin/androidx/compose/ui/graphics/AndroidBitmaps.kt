package androidx.compose.ui.graphics

/** Compose's picture as Android's Bitmap, as on Android; on iOS both are Skia's, so the pixels pass across. */
fun ImageBitmap.asAndroidBitmap(): android.graphics.Bitmap =
    android.graphics.Bitmap.fromSkia(org.jetbrains.skia.Image.makeFromBitmap(asSkiaBitmap()))
