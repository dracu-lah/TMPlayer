package androidx.activity.result

abstract class ActivityResultLauncher<I> {
    abstract fun launch(input: I)
    fun launch(input: I, options: Any?) = launch(input)
    abstract fun unregister()
}

class ActivityResult(val resultCode: Int, val data: android.content.Intent?)

/** What an activity result is handed to, androidx's. */
fun interface ActivityResultCallback<O> {
    fun onActivityResult(result: O)
}
