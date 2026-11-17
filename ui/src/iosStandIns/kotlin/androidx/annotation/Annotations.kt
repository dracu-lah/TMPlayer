package androidx.annotation

@Retention(AnnotationRetention.SOURCE)
annotation class ChecksSdkIntAtLeast(val api: Int = -1, val codename: String = "", val parameter: Int = -1, val lambda: Int = -1)

@Retention(AnnotationRetention.SOURCE)
annotation class RequiresApi(val value: Int = 1, val api: Int = 1)

@Retention(AnnotationRetention.SOURCE)
annotation class VisibleForTesting(val otherwise: Int = 2)

@Retention(AnnotationRetention.SOURCE)
annotation class StringRes

@Retention(AnnotationRetention.SOURCE)
annotation class DrawableRes

@Retention(AnnotationRetention.SOURCE)
annotation class MainThread

@Retention(AnnotationRetention.SOURCE)
annotation class WorkerThread

/** Android lint's opt-in to an unstable API; Kotlin's own opt-in checks are what count. */
annotation class OptIn(vararg val markerClass: kotlin.reflect.KClass<out Annotation>)
