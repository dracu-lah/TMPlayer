package com.tmplayer.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.data.AndroidPaths
import com.tmplayer.data.AndroidStorage
import com.tmplayer.data.DiskSpace
import com.tmplayer.data.DownloadRelocation
import com.tmplayer.data.DownloadRelocationService
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.StorageRelocationPlan
import com.tmplayer.data.startForegroundServiceCompat
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where a download lands, chosen from a plain list of volumes rather than a document picker
 * (HX26): internal storage, always, plus whichever SD card or USB-OTG drive the device currently
 * has mounted. A list rather than Android's own folder picker because a television's remote has
 * to reach it too, and many TV boxes and launchers have no folder picker at all; the phone gets
 * the same list, which keeps the two in step and needs no permission from either. Left out of the
 * Storage page entirely on a device with nothing but internal storage: there is nothing to choose.
 *
 * TDLib's cache and database are never offered here and never move: only the Downloads folder
 * does ([DownloadRelocation]), so the watch cache (recently played videos) always stays on
 * internal storage.
 */
@Composable
internal fun DownloadLocationRow(onMoved: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val s = LocalStrings.current
    val toast = rememberToast()
    val scope = rememberCoroutineScope()
    val volumes = remember { AndroidStorage.volumes(context) }
    if (volumes.size <= 1) return

    val downloadRoot by settings.downloadRoot.collectAsStateWithLifecycle(initialValue = AndroidStorage.currentRoot)
    val moving by DownloadRelocation.moving.collectAsStateWithLifecycle(initialValue = null)
    var picking by remember { mutableStateOf(false) }
    var choice by remember { mutableStateOf<LocationChoice?>(null) }
    var refused by remember { mutableStateOf<String?>(null) }

    val current = volumes.firstOrNull { it.root?.path == downloadRoot.ifBlank { null } } ?: volumes.first()
    val reachable = downloadRoot.isBlank() || AndroidStorage.chosenIsReachable()

    ActionRow(
        title = s.storageLocation,
        subtitle = when {
            moving != null -> s.storageMovingProgress(
                StorageRelocationPlan.size(moving!!.doneBytes),
                StorageRelocationPlan.size(moving!!.totalBytes),
            )
            !reachable -> s.storageAndroidUnavailable(current.label)
            else -> current.label
        },
        icon = TmIcons.Folder,
        onClick = { if (!DownloadRelocation.running) picking = true },
    )

    if (picking) {
        ChoiceSheet(
            title = s.storageLocation,
            note = s.storageAndroidPickNote,
            onDismiss = { picking = false },
            onClose = { picking = false },
        ) {
            ChoiceList {
                volumes.forEach { volume ->
                    ChoiceLine(
                        title = volume.label,
                        detail = volume.root?.let { root ->
                            runCatching { s.storageFree(StorageRelocationPlan.size(DiskSpace.read(context, root).freeBytes)) }.getOrNull()
                        },
                        selected = volume.root?.path == current.root?.path,
                        onClick = {
                            picking = false
                            if (volume.root?.path != current.root?.path) {
                                scope.launch {
                                    when (val plan = planLocationChange(context, settings, volume)) {
                                        is LocationPlan.Refused -> refused = plan.reason
                                        is LocationPlan.Ready -> choice = LocationChoice(volume, plan)
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    refused?.let { reason ->
        TvConfirm(
            title = s.storageChooseAnother,
            message = reason,
            confirmLabel = s.commonOk,
            destructive = false,
            onConfirm = { refused = null },
            onDismiss = { refused = null },
        )
    }

    choice?.let { pick ->
        var moveToo by remember(pick) { mutableStateOf(false) }
        TvConfirm(
            title = s.storageAndroidTitle(pick.volume.label),
            message = s.storageMoveBody,
            detail = if (pick.plan.existingCount > 0 && !pick.plan.canAlsoMove) {
                s.storageAndroidMoveTooSmall(pick.plan.existingCount, StorageRelocationPlan.size(pick.plan.existingBytes))
            } else {
                null
            },
            confirmLabel = s.commonChange,
            destructive = false,
            extra = if (pick.plan.existingCount > 0 && pick.plan.canAlsoMove) {
                {
                    TickRow(
                        label = s.storageAndroidMoveLabel(pick.plan.existingCount, StorageRelocationPlan.size(pick.plan.existingBytes)),
                        checked = moveToo,
                        onToggle = { moveToo = !moveToo },
                    )
                }
            } else {
                null
            },
            onConfirm = {
                val root = pick.volume.root
                val applyMove = moveToo && pick.plan.canAlsoMove
                choice = null
                if (applyMove) {
                    val started = DownloadRelocation.start(
                        context = context,
                        settings = settings,
                        root = root,
                        label = pick.volume.label,
                        onOutcome = { outcome -> toast(outcome.message); onMoved() },
                        onCancelled = { toast(s.storageAndroidCancelled) },
                    )
                    if (started) {
                        runCatching { context.startForegroundServiceCompat(Intent(context, DownloadRelocationService::class.java)) }
                    }
                } else {
                    scope.launch {
                        DownloadRelocation.useWithoutMoving(settings, root)
                        toast(s.storageAndroidUsing(pick.volume.label))
                    }
                }
            },
            onDismiss = { choice = null },
        )
    }
}

/** A volume the viewer tapped, with what moving its existing downloads there would take. */
private data class LocationChoice(val volume: AndroidStorage.Volume, val plan: LocationPlan.Ready)

private sealed interface LocationPlan {
    data class Refused(val reason: String) : LocationPlan
    data class Ready(val existingCount: Int, val existingBytes: Long, val canAlsoMove: Boolean) : LocationPlan
}

/**
 * What choosing [volume] would mean: refused outright (not writable, the same place, one nested
 * in the other), or ready, with how many downloads already on the device would move if asked to,
 * and whether there is room for that on top of the folder itself being usable.
 */
private suspend fun planLocationChange(context: Context, settings: SettingsStore, volume: AndroidStorage.Volume): LocationPlan =
    withContext(Dispatchers.IO) {
        val records = runCatching { settings.downloadsNow() }.getOrDefault(emptyList())
        val root = volume.root
        if (root == null) {
            val to = File(context.filesDir, AndroidPaths.DOWNLOADS)
            val moving = StorageRelocationPlan.toMove(records, to)
            val existingBytes = sizeOf(moving)
            val disk = DiskSpace.read(context, context.filesDir)
            val canAlsoMove = existingBytes == 0L || disk.freeBytes >= existingBytes + StorageRelocationPlan.SPARE_BYTES
            return@withContext LocationPlan.Ready(moving.size, existingBytes, canAlsoMove)
        }
        val candidate = AndroidStorage.inspect(context, root)
        val currentRoot = DownloadRelocation.currentRoot()
        val currentDownloadsDir = AndroidStorage.downloadsDir(context)
        when (
            val verdict = StorageRelocationPlan.validate(
                candidate = candidate,
                currentFolders = listOf(currentDownloadsDir),
                currentRoot = currentRoot,
                downloadsBytes = 0,
            )
        ) {
            is StorageRelocationPlan.Verdict.Refused -> LocationPlan.Refused(verdict.reason)
            is StorageRelocationPlan.Verdict.Allowed -> {
                val to = AndroidStorage.downloadsDirOf(root)
                val moving = StorageRelocationPlan.toMove(records, to)
                val existingBytes = sizeOf(moving)
                val canAlsoMove = existingBytes == 0L || candidate.usableBytes >= existingBytes + StorageRelocationPlan.SPARE_BYTES
                LocationPlan.Ready(moving.size, existingBytes, canAlsoMove)
            }
        }
    }

private fun sizeOf(records: List<ResumeRecord>): Long = records.sumOf { File(it.localPath!!).length() }
