package android.annotation

/** Silences an Android lint check; lint does not run on iOS. */
@Retention(AnnotationRetention.SOURCE)
annotation class SuppressLint(vararg val value: String)

@Retention(AnnotationRetention.SOURCE)
annotation class TargetApi(val value: Int)
