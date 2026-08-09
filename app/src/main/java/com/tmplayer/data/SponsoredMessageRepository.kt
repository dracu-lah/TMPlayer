package com.tmplayer.data

import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.dto.MessageAnimation
import dev.g000sha256.tdl.dto.MessagePhoto
import dev.g000sha256.tdl.dto.MessageSourceChatHistory
import dev.g000sha256.tdl.dto.MessageText
import dev.g000sha256.tdl.dto.MessageVideo
import dev.g000sha256.tdl.dto.ReportSponsoredResult
import dev.g000sha256.tdl.dto.ReportSponsoredResultAdsHidden
import dev.g000sha256.tdl.dto.ReportSponsoredResultFailed
import dev.g000sha256.tdl.dto.ReportSponsoredResultOk
import dev.g000sha256.tdl.dto.ReportSponsoredResultOptionRequired
import dev.g000sha256.tdl.dto.ReportSponsoredResultPremiumRequired
import dev.g000sha256.tdl.dto.SponsoredMessage

/** A Telegram-provided sponsored message, reduced to what the media list can present. */
data class SponsoredItem(
    val messageId: Long,
    val label: String,
    val title: String,
    val text: String,
    val buttonText: String,
    val sponsorInfo: String,
    val additionalInfo: String,
    val sponsorUrl: String,
    val canBeReported: Boolean,
    val miniThumbnail: ByteArray?,
    val thumbnailFileId: Int,
)

data class SponsoredBatch(
    val messages: List<SponsoredItem>,
    val messagesBetween: Int,
)

data class SponsoredReportOption(val id: ByteArray, val text: String)

sealed interface SponsoredReportOutcome {
    data class Options(val title: String, val options: List<SponsoredReportOption>) :
        SponsoredReportOutcome

    data object Reported : SponsoredReportOutcome
    data object AdsHidden : SponsoredReportOutcome
    data object PremiumRequired : SponsoredReportOutcome
    data object Unavailable : SponsoredReportOutcome
}

/**
 * The only app boundary allowed to perform Telegram sponsored-message operations.
 *
 * Keeping the protocol DTOs here makes it impossible for a sponsored card to become a playable
 * [MediaItem] by accident.
 */
class SponsoredMessageRepository(private val td: TdlClient) {

    suspend fun load(chatId: Long): SponsoredBatch {
        val response = td.getChatSponsoredMessages(chatId).value()
        return SponsoredBatch(
            messages = response.messages.map(::mapSponsoredMessage),
            messagesBetween = response.messagesBetween.coerceAtLeast(0),
        )
    }

    suspend fun markViewed(chatId: Long, messageId: Long) {
        td.viewMessages(
            chatId = chatId,
            messageIds = longArrayOf(messageId),
            source = MessageSourceChatHistory(),
            forceRead = false,
        ).value()
    }

    suspend fun click(chatId: Long, messageId: Long, media: Boolean) {
        td.clickChatSponsoredMessage(
            chatId = chatId,
            messageId = messageId,
            isMediaClick = media,
            fromFullscreen = false,
        ).value()
    }

    suspend fun report(
        chatId: Long,
        messageId: Long,
        optionId: ByteArray = byteArrayOf(),
    ): SponsoredReportOutcome = mapReportResult(
        td.reportChatSponsoredMessage(chatId, messageId, optionId).value(),
    )
}

internal fun mapSponsoredMessage(message: SponsoredMessage): SponsoredItem {
    val content = message.content
    val text = when (content) {
        is MessageText -> content.text.text
        is MessageAnimation -> content.caption.text
        is MessagePhoto -> content.caption.text
        is MessageVideo -> content.caption.text
        else -> ""
    }
    val mini = when (content) {
        is MessageAnimation -> content.animation.minithumbnail?.data
        is MessagePhoto -> content.photo.minithumbnail?.data
        is MessageVideo -> content.video.minithumbnail?.data
        else -> null
    }
    val thumbnailId = when (content) {
        is MessageAnimation -> content.animation.thumbnail?.file?.id ?: 0
        is MessagePhoto -> content.photo.sizes.maxByOrNull { it.width * it.height }?.photo?.id ?: 0
        is MessageVideo -> content.video.thumbnail?.file?.id ?: 0
        else -> 0
    }
    return SponsoredItem(
        messageId = message.messageId,
        label = if (message.isRecommended) "Recommended" else "Sponsored",
        title = message.title,
        text = text,
        buttonText = message.buttonText,
        sponsorInfo = message.sponsor.info,
        additionalInfo = message.additionalInfo,
        sponsorUrl = message.sponsor.url,
        canBeReported = message.canBeReported,
        miniThumbnail = mini,
        thumbnailFileId = thumbnailId,
    )
}

private fun mapReportResult(result: ReportSponsoredResult): SponsoredReportOutcome = when (result) {
    is ReportSponsoredResultOptionRequired -> SponsoredReportOutcome.Options(
        title = result.title,
        options = result.options.map { SponsoredReportOption(it.id.copyOf(), it.text) },
    )
    is ReportSponsoredResultOk -> SponsoredReportOutcome.Reported
    is ReportSponsoredResultAdsHidden -> SponsoredReportOutcome.AdsHidden
    is ReportSponsoredResultPremiumRequired -> SponsoredReportOutcome.PremiumRequired
    is ReportSponsoredResultFailed -> SponsoredReportOutcome.Unavailable
}

/**
 * Inserts sponsored cards without changing the media list or its count.
 *
 * A spacing of zero means Telegram wants one card after all ordinary items. Positive spacing is
 * the minimum number of ordinary items between cards; unused sponsor messages are not repeated.
 */
internal fun <T> placeSponsored(
    media: List<T>,
    sponsored: SponsoredBatch?,
): List<MediaFeedEntry<T>> {
    val ads = sponsored?.messages.orEmpty()
    if (ads.isEmpty()) return media.map { MediaFeedEntry.Media(it) }
    val spacing = sponsored?.messagesBetween ?: 0
    if (spacing == 0) {
        return media.map { MediaFeedEntry.Media(it) } + MediaFeedEntry.Sponsored(ads.first())
    }

    val output = ArrayList<MediaFeedEntry<T>>(media.size + ads.size)
    var adIndex = 0
    media.forEachIndexed { index, item ->
        output += MediaFeedEntry.Media(item)
        if ((index + 1) % spacing == 0 && adIndex < ads.size) {
            output += MediaFeedEntry.Sponsored(ads[adIndex++])
        }
    }
    return output
}

sealed interface MediaFeedEntry<out T> {
    data class Media<T>(val item: T) : MediaFeedEntry<T>
    data class Sponsored(val item: SponsoredItem) : MediaFeedEntry<Nothing>
}

internal fun isSponsoredTextFullyVisible(top: Float, bottom: Float, viewportHeight: Float): Boolean =
    viewportHeight > 0f && top >= 0f && bottom <= viewportHeight && bottom >= top
