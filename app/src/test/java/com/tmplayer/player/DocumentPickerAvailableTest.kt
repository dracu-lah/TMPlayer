package com.tmplayer.player

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** WP-81: the television's "Load a subtitle file" line is offered only where a document picker answers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DocumentPickerAvailableTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun openDocument() = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")

    @Test
    fun `no document picker installed, the line is left out`() {
        assertFalse(documentPickerAvailable(context))
    }

    @Test
    fun `a document picker that answers the open document intent, the line is shown`() {
        val info = ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = "com.android.documentsui"
                name = "com.android.documentsui.picker.PickActivity"
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(openDocument(), info)
        assertTrue(documentPickerAvailable(context))
    }

    @Test
    fun `the TV framework stubs that only say no app can do it count as no picker (PB2-08)`() {
        val info = ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = "com.android.tv.frameworkpackagestubs"
                name = "com.android.tv.frameworkpackagestubs.Stubs\$DocumentsStub"
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(openDocument(), info)
        assertFalse(documentPickerAvailable(context))
    }
}
