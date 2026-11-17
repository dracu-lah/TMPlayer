package androidx.activity.result.contract

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResult

abstract class ActivityResultContract<I, O> {
    abstract fun createIntent(context: Context, input: I): Intent
    abstract fun parseResult(resultCode: Int, intent: Intent?): O
}

/** The contracts :app uses, each answering with its "nothing chosen" result on iOS. */
object ActivityResultContracts {
    class OpenDocumentTree : ActivityResultContract<Uri?, Uri?>() {
        override fun createIntent(context: Context, input: Uri?): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = null
    }

    class OpenDocument : ActivityResultContract<Array<String>, Uri?>() {
        override fun createIntent(context: Context, input: Array<String>): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = null
    }

    class CreateDocument(private val mimeType: String = "*/*") : ActivityResultContract<String, Uri?>() {
        override fun createIntent(context: Context, input: String): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = null
    }

    class GetContent : ActivityResultContract<String, Uri?>() {
        override fun createIntent(context: Context, input: String): Intent = Intent(Intent.ACTION_GET_CONTENT)
        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = null
    }

    class StartActivityForResult : ActivityResultContract<Intent, ActivityResult>() {
        override fun createIntent(context: Context, input: Intent): Intent = input
        override fun parseResult(resultCode: Int, intent: Intent?): ActivityResult = ActivityResult(resultCode, intent)
    }

    class RequestPermission : ActivityResultContract<String, Boolean>() {
        override fun createIntent(context: Context, input: String): Intent = Intent()
        override fun parseResult(resultCode: Int, intent: Intent?): Boolean = true
    }

    class RequestMultiplePermissions : ActivityResultContract<Array<String>, Map<String, Boolean>>() {
        override fun createIntent(context: Context, input: Array<String>): Intent = Intent()
        override fun parseResult(resultCode: Int, intent: Intent?): Map<String, Boolean> = emptyMap()
    }
}
