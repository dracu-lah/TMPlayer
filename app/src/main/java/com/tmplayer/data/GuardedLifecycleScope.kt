package com.tmplayer.data

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.tmplayer.platform.JobFailures
import kotlinx.coroutines.CoroutineScope

/**
 * The activity's `lifecycleScope` with [JobFailures]' handler added (HX10): the same job, so
 * everything is still cancelled when the activity goes, but a job that throws is logged and
 * reported instead of ending the process. The activities import it under the stock name
 * (`import com.tmplayer.data.guardedLifecycleScope as lifecycleScope`), so every
 * `lifecycleScope.launch` in them is covered without touching each call.
 */
val LifecycleOwner.guardedLifecycleScope: CoroutineScope
    get() = CoroutineScope(lifecycleScope.coroutineContext + handler)

private val handler = JobFailures.handler("an activity")
