package com.tmplayer.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tmplayer.data.MobileData
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons

/** Test tags of the two tabs of [StreamStorageRow]. */
internal const val STREAM_KEEP_TAG = "stream-keep"
internal const val STREAM_SAVE_TAG = "stream-save"

/** The television's one stop for both tabs, the node that takes the focus ring. */
internal const val STREAM_SWITCH_TAG = "stream-switch"

/**
 * "While streaming: Keep video | Save space" (the stored `low_storage_mode`, on is Save space), as
 * two tabs with the chosen one's sentence under them (see [SegmentedChoiceRow]).
 */
@Composable
internal fun StreamStorageRow(
    saveSpace: Boolean,
    onPick: (saveSpace: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    SegmentedChoiceRow(
        title = s.settingsStreamTitle,
        icon = TmIcons.Archive,
        choices = listOf(false, true),
        selected = saveSpace,
        label = { if (it) s.settingsStreamSave else s.settingsStreamKeep },
        detail = if (saveSpace) s.settingsStreamSaveDetail else s.settingsStreamKeepDetail,
        tag = { if (it) STREAM_SAVE_TAG else STREAM_KEEP_TAG },
        switchTag = STREAM_SWITCH_TAG,
        onPick = onPick,
        modifier = modifier,
        details = listOf(s.settingsStreamKeepDetail, s.settingsStreamSaveDetail),
    )
}

/** Test tags of the three tabs of [MobileDataRow]. */
internal fun mobileDataTag(choice: MobileData): String = "mobile-data-${choice.stored}"

/** The one stop for the three tabs on a television (the row is not offered there, but a tablet set to TV has it). */
internal const val MOBILE_DATA_SWITCH_TAG = "mobile-data-switch"

/**
 * "On mobile data: Ask first | Never | Allow" (R2), one question where there were the twin switches
 * Wi-Fi only and Save mobile data, with the chosen answer's sentence under the tabs.
 */
@Composable
internal fun MobileDataRow(
    choice: MobileData,
    onPick: (MobileData) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    SegmentedChoiceRow(
        title = s.settingsMobileDataTitle,
        icon = TmIcons.Phone,
        choices = MobileData.entries,
        selected = choice,
        label = {
            when (it) {
                MobileData.Ask -> s.settingsMobileDataAsk
                MobileData.Never -> s.settingsMobileDataNever
                MobileData.Allow -> s.settingsMobileDataAllow
            }
        },
        detail = when (choice) {
            MobileData.Ask -> s.settingsMobileDataAskDetail
            MobileData.Never -> s.settingsMobileDataNeverDetail
            MobileData.Allow -> s.settingsMobileDataAllowDetail
        },
        tag = ::mobileDataTag,
        switchTag = MOBILE_DATA_SWITCH_TAG,
        onPick = onPick,
        modifier = modifier,
        details = listOf(s.settingsMobileDataAskDetail, s.settingsMobileDataNeverDetail, s.settingsMobileDataAllowDetail),
    )
}
