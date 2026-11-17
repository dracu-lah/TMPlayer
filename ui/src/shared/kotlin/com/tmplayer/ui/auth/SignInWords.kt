package com.tmplayer.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.tmplayer.data.AuthState
import com.tmplayer.data.CodeDelivery
import com.tmplayer.data.PhoneFormat
import com.tmplayer.data.Td
import com.tmplayer.i18n.L
import kotlinx.coroutines.delay

/**
 * The words and small clocks the sign in screens share, so the phone, the TV and the desktop say
 * the same thing about the same step.
 */
object SignInWords {

    /** Where the code went, in the viewer's words rather than TDLib's. */
    fun codeBlurb(state: AuthState.Code): String = when (state.delivery) {
        CodeDelivery.TelegramApp -> L.loginDeliveryApp
        CodeDelivery.Sms -> L.loginDeliverySms(state.phoneNumber)
        CodeDelivery.Call -> L.loginDeliveryCall(state.phoneNumber)
        CodeDelivery.MissedCall -> L.loginDeliveryMissedCall
        CodeDelivery.FlashCall -> L.loginDeliveryFlashCall(state.phoneNumber)
        CodeDelivery.Fragment -> L.loginDeliveryFragment
        CodeDelivery.SmsWord -> L.loginDeliveryWord
        CodeDelivery.SmsPhrase -> L.loginDeliveryPhrase
        CodeDelivery.Firebase -> L.loginDeliveryFirebase(state.phoneNumber)
        CodeDelivery.Unknown -> L.loginDeliveryUnknown(state.phoneNumber)
    }

    /** Named after what pressing it will actually do, which is not always "the same again". */
    fun resendLabel(next: CodeDelivery?): String = when (next) {
        CodeDelivery.Sms, CodeDelivery.SmsWord, CodeDelivery.SmsPhrase -> L.loginResendSms
        CodeDelivery.Call, CodeDelivery.MissedCall, CodeDelivery.FlashCall -> L.loginResendCall
        CodeDelivery.TelegramApp -> L.loginResendApp
        CodeDelivery.Fragment -> L.loginResendFragment
        else -> L.loginResendAgain
    }

    /**
     * True where the code step offers "Scan a QR code instead": the code went to Telegram on
     * another device, so there is a signed in Telegram to scan with, and the QR sign in needs no
     * code at all. Telegram can send a code only to its app, with no other route to resend by, and
     * then not deliver it: an iPad waited with nothing arriving and no way on but Start over
     * (2026-11-15), and the QR code signed it in at once.
     */
    fun qrInstead(state: AuthState.Code): Boolean = state.delivery == CodeDelivery.TelegramApp

    /** True where the code is digits of a known length, which a row of boxes can hold. */
    fun isDigits(delivery: CodeDelivery): Boolean = when (delivery) {
        CodeDelivery.SmsWord, CodeDelivery.SmsPhrase, CodeDelivery.FlashCall, CodeDelivery.MissedCall -> false
        else -> true
    }

    /** The code length to expect: TDLib's when it says, else Telegram's usual five. */
    fun codeLength(state: AuthState.Code): Int = state.length.takeIf { it in 1..MAX_CODE_LENGTH } ?: DEFAULT_CODE_LENGTH

    /**
     * The dial code as it sits at the end of the country row ("+91"), so the row reads "India +91"
     * and says what the number's first digits are without the viewer finding the small box.
     * Null while there is no code to show.
     */
    fun dialChip(dial: String): String? =
        dial.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.let { "+$it" }

    /**
     * The line that stands where the resend button would, when TDLib offers no other way to send
     * the code: the row is never just gone, it says what is left to do. Null when it can be sent
     * again (the button itself, greyed under its countdown, is the row then).
     */
    fun resendNone(state: AuthState.Code): String? = if (state.canResend) null else L.loginResendNone

    fun clock(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

    const val DEFAULT_CODE_LENGTH = 5
    const val MAX_CODE_LENGTH = 8
}

/**
 * Seconds left of [seconds], counting down once a second from when [key] last changed. Survives a
 * rotation, so turning the phone does not hand back a resend that Telegram would refuse.
 */
@Composable
fun rememberCountdown(seconds: Int, key: Any?): Int {
    var left by rememberSaveable(key, seconds) { mutableIntStateOf(seconds) }
    LaunchedEffect(key, left) {
        if (left <= 0) return@LaunchedEffect
        delay(1000)
        left -= 1
    }
    return left
}

/**
 * TDLib's layout for the national number typed after [dial] ([Td.formatPhone]), asked again as
 * the number grows, since the layout of some countries depends on the first digits.
 */
@Composable
fun rememberPhonePattern(dial: String, national: String): String? {
    val pattern by produceState<String?>(null, dial, national.take(PATTERN_PREFIX)) {
        value = runCatching { Td.formatPhone(dial, national) }.getOrNull()
    }
    return pattern
}

/**
 * Draws a field of bare digits grouped as [pattern] lays them out ([PhoneFormat]), with the caret
 * kept on the digit it was on. Nothing is written into the field itself.
 */
class PhoneGrouping(private val pattern: String?) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val formatted = PhoneFormat.format(text.text, pattern)
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = PhoneFormat.toFormatted(offset, formatted)
            override fun transformedToOriginal(offset: Int): Int = PhoneFormat.toRaw(offset, formatted).coerceAtMost(text.length)
        }
        return TransformedText(AnnotatedString(formatted), mapping)
    }

    override fun equals(other: Any?) = other is PhoneGrouping && other.pattern == pattern
    override fun hashCode() = pattern.hashCode()
}

/**
 * The television's and the desktop's one field ("+" and digits) drawn as "+44 7700 900123"
 * ([PhoneFormat.formatInternational]), the caret kept on its digit.
 */
class InternationalGrouping(
    private val countries: List<com.tmplayer.data.Country>,
    private val pattern: String?,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val formatted = PhoneFormat.formatInternational(text.text, countries, pattern)
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = PhoneFormat.toFormatted(offset, formatted)
            override fun transformedToOriginal(offset: Int): Int = PhoneFormat.toRaw(offset, formatted).coerceAtMost(text.length)
        }
        return TransformedText(AnnotatedString(formatted), mapping)
    }

    override fun equals(other: Any?) = other is InternationalGrouping && other.countries === countries && other.pattern == pattern
    override fun hashCode() = System.identityHashCode(countries) * 31 + pattern.hashCode()
}

/**
 * The international field's dial code and national number, and the country and layout behind
 * them, worked out as it is typed: what the TV and the desktop show under their one field.
 */
@Composable
fun rememberInternational(raw: String, countries: List<com.tmplayer.data.Country>): Pair<com.tmplayer.data.Country?, String?> {
    val split = com.tmplayer.data.PhoneCountries.splitInternational(raw, countries)
    val country = split?.let { com.tmplayer.data.PhoneCountries.byDialCode(countries, it.first) }
    val pattern = rememberPhonePattern(split?.first.orEmpty(), split?.second.orEmpty())
    return country to pattern
}

/**
 * What the international field keeps of what is typed or pasted: one leading "+" and digits.
 * A number typed without the "+" gets one, and "00" in front means the same as "+".
 */
fun keepInternational(raw: String): String {
    val trimmed = raw.trim()
    // Any script's digits, written as 0 to 9: an Arabic or Persian keyboard types its own.
    val digits = PhoneRules.digits(trimmed)
    val body = if (!trimmed.startsWith('+') && digits.startsWith("00")) digits.removePrefix("00") else digits
    return "+" + body.take(MAX_INTERNATIONAL_DIGITS)
}

/** E.164 allows fifteen digits, the dial code included. */
private const val MAX_INTERNATIONAL_DIGITS = 15

/** Enough of the number to tell its layout apart; past this the layout no longer changes. */
private const val PATTERN_PREFIX = 4
