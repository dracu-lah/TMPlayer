package com.tmplayer.ui.auth

import com.tmplayer.i18n.L
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.nav.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.onFillData
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme as M3
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.AuthState
import com.tmplayer.data.CodeDelivery
import com.tmplayer.data.CodeInput
import com.tmplayer.data.Country
import com.tmplayer.data.DeviceCountry
import com.tmplayer.data.EmailReset
import com.tmplayer.data.Failures
import com.tmplayer.data.PhoneCountries
import com.tmplayer.data.QrPhase
import com.tmplayer.data.SignInMethod
import com.tmplayer.data.Td
import com.tmplayer.ui.components.BigError
import com.tmplayer.ui.components.BigLoader
import com.tmplayer.ui.components.BrandLoader
import com.tmplayer.ui.components.Pane
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.components.dpadLeavesField
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.paneAction
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * What the loader says over the sign in screens while Telegram has nothing to ask yet, or null
 * once it has. The host draws [com.tmplayer.ui.components.BrandLoaderHandOff] with it over the
 * window, so the loader fades out over whichever screen comes next; [LoginScreen] leaves those
 * states empty under it.
 */
fun loaderLabel(state: AuthState, s: com.tmplayer.i18n.Messages): String? = when (state) {
    is AuthState.Connecting -> s.stateConnecting
    is AuthState.ChooseMethod -> s.loginGettingReady
    else -> null
}

/**
 * Runs one sign in request that answers with an error or null, the error landing under the
 * field: the host clears the last error, launches [block] and shows what it returns. Used by the
 * steps that need nothing more than that (forgot password, recovery, the account and email
 * resets), so each does not need a callback of its own.
 */
typealias SignInRun = (suspend () -> String?) -> Unit

@Composable
fun LoginScreen(
    state: AuthState,
    onSubmitPassword: (String) -> Unit,
    submitError: String?,
    onStartOver: () -> Unit = {},
    onChooseMethod: (SignInMethod) -> Unit = {},
    onSubmitPhoneNumber: (String) -> Unit = {},
    onSubmitCode: (String) -> Unit = {},
    onCancelPhoneEntry: () -> Unit = {},
    onResendCode: () -> Unit = {},
    onSubmitEmailAddress: (String) -> Unit = {},
    onSubmitEmailCode: (String) -> Unit = {},
    onRefreshQr: () -> Unit = {},
    onRun: SignInRun = {},
) {
    val s = LocalStrings.current
    // "Wrong number? Edit" on the code pane. TDLib is happy to be told a different number while it
    // is waiting for a code, so this is a change of screen and nothing more: no logout, no lost
    // session, and the code that was already sent stays valid if the user comes straight back.
    var changingNumber by rememberSaveable(state::class) { mutableStateOf(false) }

    val touch = isTouch()
    // The two moments sign in is about, felt as well as read: a wrong code or number, and in.
    val haptic = com.tmplayer.ui.components.rememberHaptics()
    LaunchedEffect(submitError) { if (submitError != null) haptic(com.tmplayer.ui.components.Haptic.Reject) }
    val signedIn = state is AuthState.Ready
    LaunchedEffect(signedIn) { if (signedIn) haptic(com.tmplayer.ui.components.Haptic.Confirm) }

    when (state) {
        // Under the host's loader (see [loaderLabel]).
        is AuthState.Connecting -> Box(Modifier.fillMaxSize())
        // Answered by the device rather than by the viewer: typing a number on a television costs
        // a minute of D-pad, and scanning a code with the phone displaying it cannot be done at
        // all. Each device opens on the way in that suits it and carries a button to the other.
        is AuthState.ChooseMethod -> {
            LaunchedEffect(touch) {
                onChooseMethod(if (touch) SignInMethod.Phone else SignInMethod.Qr)
            }
            Box(Modifier.fillMaxSize())
        }
        is AuthState.Qr -> QrPane(
            link = state.link,
            scanned = state.scanned,
            onUsePhone = { onChooseMethod(SignInMethod.Phone) },
            onRefresh = onRefreshQr,
        )
        is AuthState.Phone -> PhonePane(
            state = state,
            error = submitError,
            onSubmit = onSubmitPhoneNumber,
            // The first screen on a phone, so there is nothing behind it; on a television the QR
            // pane is, and going back there is what [onCancelPhoneEntry] arranges.
            onBack = if (touch) null else onCancelPhoneEntry,
            onUseQr = { onChooseMethod(SignInMethod.Qr) },
            initial = null,
        )
        is AuthState.Code ->
            if (changingNumber) {
                PhonePane(
                    state = state,
                    error = submitError,
                    onSubmit = onSubmitPhoneNumber,
                    onBack = { changingNumber = false },
                    onUseQr = { onChooseMethod(SignInMethod.Qr) },
                    initial = state.phoneNumber,
                )
            } else {
                CodePane(
                    state = state,
                    error = submitError,
                    onSubmit = onSubmitCode,
                    onResend = onResendCode,
                    onChangeNumber = { changingNumber = true },
                    onStartOver = onStartOver,
                    onUseQr = onRefreshQr,
                )
            }
        is AuthState.Password ->
            PasswordPane(state, submitError, onSubmitPassword, onStartOver, onRun)
        is AuthState.PasswordRecovery ->
            PasswordRecoveryPane(state, submitError, onRun)
        is AuthState.EmailAddress ->
            EmailAddressPane(state, submitError, onSubmitEmailAddress, onStartOver)
        is AuthState.EmailCode ->
            EmailCodePane(state, submitError, onSubmitEmailCode, onStartOver, onRun)
        is AuthState.Ready -> BrandLoader(s.loginSigningInStatus)
        // Starting over is the way out of every failure here, including a client that could not
        // start at all, which it tries again. A flood wait holds the button until it has passed.
        is AuthState.Failed -> FailedPane(state, onStartOver)
    }
}

/**
 * A failure, with Try again; when Telegram said how long to wait, the wait counts down in the
 * message and Try again appears once it is over, rather than failing the same way at once.
 */
@Composable
private fun FailedPane(state: AuthState.Failed, onStartOver: () -> Unit) {
    val s = LocalStrings.current
    val left = rememberCountdown(state.waitSeconds, state)
    if (left > 0) {
        BigError("${state.message}\n${s.loginTryAgainIn(SignInWords.clock(left))}", onRetry = null)
    } else {
        BigError(state.message, onRetry = onStartOver)
    }
}

/**
 * One answer on its way to Telegram, and whether it has arrived.
 *
 * The pane hands over a number or a code and is told nothing more about it. What it does see is the
 * answer, either as a new [AuthState] or as an error under the field, and that releases the button.
 *
 * The guard lives in [start] rather than only in the button's `enabled`, because a remote can send
 * two centre presses inside one frame and a second phone number sent while the first is in flight
 * is a second code sent to the user.
 */
@Stable
internal class Submission {
    var busy by mutableStateOf(false)
        private set

    /**
     * The answer has not come within the ceiling ([PENDING_CEILING_MS]): on a network that blocks
     * Telegram the code never arrives, and the proxy settings (hidden under the keyboard in the
     * footer) are offered in the pane itself (ON-17). Cleared by the next press.
     */
    var slow by mutableStateOf(false)
        private set

    fun start(send: () -> Unit) {
        if (busy) return
        busy = true
        slow = false
        send()
    }

    fun finish() {
        busy = false
    }

    /** The ceiling passed with the answer still out. */
    fun giveUp() {
        if (busy) slow = true
        busy = false
    }
}

/**
 * A [Submission] that releases itself when the answer lands.
 *
 * A rejection is an error and a success is a different [AuthState], so those two are the release.
 * The ceiling covers the one call that can succeed silently: asking for the code again leaves both
 * the state and the error unchanged, and the button would otherwise stay busy for good.
 */
@Composable
private fun rememberSubmission(state: AuthState, error: String?): Submission {
    val submission = remember { Submission() }
    LaunchedEffect(state) { submission.finish() }
    // Only a real message counts. Clearing the error is the first thing a fresh attempt does, so
    // treating that as an answer would release the button in the same breath as pressing it.
    LaunchedEffect(error) { if (error != null) submission.finish() }
    LaunchedEffect(submission.busy) {
        if (!submission.busy) return@LaunchedEffect
        delay(PENDING_CEILING_MS)
        submission.giveUp()
    }
    return submission
}

/**
 * The number pane, which is where a phone's sign-in begins.
 *
 * Back means two different things on the two devices, and both are what the viewer expects. On a
 * phone this is the first screen, so Back leaves the app.
 * On a television it was reached from the QR pane, so Back goes there. The one exception is a
 * number being changed part way through a sign-in, where Back belongs to the code pane that sent
 * the viewer here, and that is what [onBack] carries. [initial] is the number being changed.
 */
@Composable
private fun PhonePane(
    state: AuthState,
    error: String?,
    onSubmit: (String) -> Unit,
    onBack: (() -> Unit)?,
    onUseQr: () -> Unit,
    initial: String?,
) {
    val s = LocalStrings.current
    // Nothing has been sent yet, so leaving costs nothing either way.
    // On the first screen there is no handler: Back leaves the app at once (no exit confirmation).
    if (onBack != null) BackHandler(onBack = onBack)

    if (isTouch()) {
        TouchPhonePane(state, error, onSubmit, onBack, onUseQr, initial)
        return
    }

    val context = LocalContext.current
    val countries = rememberSignInCountries()
    // "+" and digits, nothing else: the grouping is drawn ([InternationalGrouping]), never typed,
    // so the caret stays where the remote's keyboard left it.
    var number by rememberSaveable { mutableStateOf(initial?.let(::keepInternational) ?: "+") }
    var picking by rememberSaveable { mutableStateOf(false) }
    val submission = rememberSubmission(state, error)
    val (country, pattern) = rememberInternational(number, countries)

    // The country is filled in for the viewer: the one Telegram guesses from the address it sees,
    // else the device's own. The field stays closed until then, so the remote's keyboard never
    // races the code going in; only while nothing has been typed, never for a number being changed.
    val detecting = rememberCountryGuess(
        countries = countries,
        wanted = initial == null && number == "+",
        localIso = { DeviceCountry.code(context) },
        onFound = { found -> if (number == "+") number = "+${found.dialCode}" },
    )
    val typedCode = number.length > 1

    if (picking) {
        CountryPicker(
            countries = countries,
            selected = country,
            onPick = { picked ->
                val national = PhoneCountries.splitInternational(number, countries)?.second.orEmpty()
                number = "+${picked.dialCode}$national"
                picking = false
            },
            onDismiss = { picking = false },
        )
        return
    }

    LoginForm(
        title = s.loginPhoneTitle,
        blurb = s.loginPhoneBlurbTv,
        note = country?.let { "${it.flag}  ${it.name}" } ?: s.loginInvalidCountryCode.takeIf { typedCode && countries.isNotEmpty() },
        noteLoading = detecting,
        enabled = !detecting,
        value = number,
        // The keyboard type is a request, not a guarantee: a leanback remote's on-screen keyboard
        // and every hardware keyboard will happily send letters to a field that asked for a
        // dialpad. What a number is made of is decided here instead.
        onValueChange = { number = PhoneRules.international(it, countries) },
        placeholder = "+44 7700 900000",
        keyboardType = KeyboardType.Phone,
        masked = false,
        transformation = InternationalGrouping(countries, pattern),
        error = error,
        submitLabel = s.loginSendCode,
        busyLabel = s.loginSendingCode,
        busy = submission.busy,
        canSubmit = country != null && number.count { it.isDigit() } >= PhoneRules.MIN_DIGITS,
        onSubmit = { submission.start { onSubmit(number) } },
        // On a television the other way in is one press away, and it is the quicker one, so the
        // secondary button says where it goes rather than only that it goes back.
        backLabel = if (onBack == null) s.loginUseQr else s.commonBack,
        onBack = onBack ?: onUseQr,
        extras = {
            TmSecondaryButton(onClick = { picking = true }, enabled = !detecting) { Text(s.loginChooseCountry) }
            if (submission.slow) ProxyLink()
            // Reached from the QR pane, Back already returns there; from the code pane it does
            // not, so the other way in gets its own button.
            if (onBack != null) TmSecondaryButton(onClick = onUseQr) { Text(s.loginUseQr) }
        },
    )
}

/**
 * The number pane as Telegram itself asks for a number: country first, then a narrow dial code
 * beside a wide national number.
 *
 * The television's single international field works there because one field is a mercy on a remote;
 * on a phone it asks people to know a dial code they have never had to type.
 */
@Composable
private fun TouchPhonePane(
    state: AuthState,
    error: String?,
    onSubmit: (String) -> Unit,
    /** Null when this is the first screen of the sign-in and there is nothing behind it. */
    onBack: (() -> Unit)?,
    onUseQr: () -> Unit,
    initial: String?,
) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val countries = rememberSignInCountries()
    // Plain digits in both fields, exactly as typed. Do not write grouped text back into a field:
    // a space inserted to the left of a still cursor moves the caret a character back, and on
    // keyboards that report a selection of their own it jumps further still. [PhoneGrouping]
    // draws it. The rules (pastes, trunk zero, other scripts' digits, shared codes) are
    // [PhoneEntry]'s, shared with the desktop and the browser.
    val entry = rememberPhoneEntry(initial, countries)
    var picking by rememberSaveable { mutableStateOf(false) }
    val country = rememberEntryCountry(entry, countries)
    val pattern = rememberPhonePattern(entry.dial, entry.national)
    val field = remember { FocusRequester() }
    val dialField = remember { FocusRequester() }
    val submission = rememberSubmission(state, error)

    // The country Telegram guesses from the address it sees, else the SIM's or the network's.
    // Both fields stay closed until it is in (three seconds at most), so typing never races the
    // code going in. Nothing is guessed for a number being changed from the code step.
    val detecting = rememberCountryGuess(
        countries = countries,
        wanted = initial == null && !entry.touched,
        localIso = { DeviceCountry.code(context) },
        onFound = entry::guess,
    )

    if (picking) {
        CountryPicker(
            countries = countries,
            selected = country,
            onPick = {
                entry.pick(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
        return
    }

    val ready = entry.ready(country)
    val submit = { if (ready) submission.start { onSubmit(entry.full) } }

    Pane {
        // No arrow on the first screen: an arrow that leaves the app is not what a back arrow
        // means anywhere else, and the pane's own buttons are the two ways on from here.
        if (onBack != null) {
            PaneHeader(onBack = onBack)
        } else {
            // The first screen of all: which app is asking for the number.
            com.tmplayer.ui.components.BrandLockup(
                size = com.tmplayer.ui.components.BrandSize.Medium,
                layout = com.tmplayer.ui.components.BrandLayout.Row,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Text(s.loginPhoneTitle, style = MaterialTheme.typography.headlineLarge)
        Text(
            s.loginPhoneBlurb,
            style = MaterialTheme.typography.bodyLarge,
        )

        // Telegram's own order: the country, then the code beside the number. A code that no
        // country has says so here rather than showing somebody else's flag.
        PaneChooser(
            label = when {
                country != null -> "${country.flag}  ${country.name}"
                entry.dial.isNotEmpty() && countries.isNotEmpty() -> s.loginInvalidCountryCode
                else -> s.loginChooseCountry
            },
            onClick = { picking = true },
            // The chip: "India +91", the whole row pressable, so the country and its code read as
            // one thing and the small code box below is not the only place the code shows.
            trailing = country?.let { SignInWords.dialChip(entry.dial) },
            loading = detecting,
        )

        // A number reads left to right in every language, Arabic and Persian included.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(DIAL_WIDTH)) {
                    PaneField(
                        value = entry.dial,
                        // Typing the code is the other half of the picker: people who know their
                        // own reach for it before they will scroll a list to find their country.
                        onValueChange = { entry.typeDial(it, countries) },
                        placeholder = if (detecting) "" else "00",
                        // The placeholder is the shape of a dial code, which is no use as a name for
                        // the field once it is filled in, so the floating label has words of its own.
                        label = if (detecting) "" else s.loginCode,
                        prefix = if (detecting) null else "+",
                        enabled = !detecting,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Phone,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(dialField),
                    )
                    // The code's shimmer, faded out as the guessed code comes in under it.
                    GuessFade(detecting, skeletonWidth = 48.dp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp)) {}
                }
                PaneField(
                    value = entry.national,
                    // Digits and nothing else. The dialpad is a hint the IME may ignore, and a
                    // letter typed here would sit in the field looking accepted while being
                    // quietly dropped from the number that is actually sent.
                    onValueChange = { entry.typeNational(it, countries) },
                    placeholder = s.loginPhoneNumber,
                    visualTransformation = PhoneGrouping(pattern),
                    enabled = !detecting,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Done,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(field)
                        // Backspace in an empty number goes back into the code, as in Telegram.
                        .onPreviewKeyEvent { event ->
                            val back = event.type == KeyEventType.KeyDown && event.key == Key.Backspace && entry.national.isEmpty()
                            if (back) runCatching { dialField.requestFocus() }
                            back
                        }
                        .semantics { contentType = ContentType.PhoneNumberNational },
                )
            }
        }

        if (error != null) {
            PaneError(error)
        }

        TmButton(
            onClick = submit,
            enabled = ready,
            loading = submission.busy,
            busyLabel = s.loginSendingCode,
            modifier = Modifier.paneAction(),
        ) {
            Text(s.loginSendCode)
        }
        // No code after the wait: the network may block Telegram, and the footer's proxy entry is
        // hidden while the keyboard is up, so the way to it is here (ON-17).
        if (submission.slow) ProxyLink()
        TmSecondaryButton(onClick = onUseQr, modifier = Modifier.paneAction()) {
            Text(s.loginUseQr)
        }
    }

    // The keyboard is up either way on this pane, so it may as well be pointed at the field the
    // user came here to fill, the moment it opens.
    LaunchedEffect(detecting) { if (!detecting) runCatching { field.requestFocus() } }
}

/**
 * "Proxy settings" in the pane, once Send code has had no answer for the ceiling (ON-17): the same
 * [com.tmplayer.ui.connect.ProxyScreenState] the footer opens. A plain secondary button on a
 * television so the remote reaches it, a quiet text link under the button on a phone.
 */
@Composable
private fun ProxyLink() {
    val s = LocalStrings.current
    val open = { com.tmplayer.ui.connect.ProxyScreenState.open = true }
    if (isTouch()) {
        androidx.compose.material3.TextButton(onClick = open, modifier = Modifier.paneAction()) {
            Text(s.loginProxySettings)
        }
    } else {
        TmSecondaryButton(onClick = open) { Text(s.loginProxySettings) }
    }
}

@Composable
private fun CodePane(
    state: AuthState.Code,
    error: String?,
    onSubmit: (String) -> Unit,
    onResend: () -> Unit,
    onChangeNumber: () -> Unit,
    onStartOver: () -> Unit,
    onUseQr: () -> Unit,
) {
    val s = LocalStrings.current
    BackHandler(onBack = onStartOver)

    if (isTouch()) {
        TouchCodePane(state, error, onSubmit, onResend, onChangeNumber, onStartOver, onUseQr)
        return
    }

    var code by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    // Most deliveries are a run of digits, but a couple are a word or a phrase, and those have to
    // keep their letters. The phone's version of this pane already asks the same question.
    val digits = SignInWords.isDigits(state.delivery)
    val length = SignInWords.codeLength(state)
    val submission = rememberSubmission(state, error)
    val resend = rememberSubmission(state, error)
    var resent by remember { mutableStateOf(0) }
    val remaining = rememberCountdown(state.timeout, state.phoneNumber to resent)

    val submit = {
        submission.start {
            submitted = code
            onSubmit(code)
        }
    }
    // The last digit typed with the remote is the whole answer: no trip down to the button.
    LaunchedEffect(code) { if (CodeInput.shouldAutoSubmit(code, length, digits, submitted)) submit() }

    LoginForm(
        title = s.loginCodeTitle,
        blurb = SignInWords.codeBlurb(state),
        note = null,
        value = code,
        // A number keyboard is a hint the leanback remote is free to ignore, so a code that is
        // only ever digits is held to that here rather than trusted to the keyboard.
        onValueChange = { entered -> code = CodeInput.clean(entered, digits, if (digits) length else 0) },
        placeholder = s.loginCode,
        keyboardType = if (digits) KeyboardType.Number else KeyboardType.Text,
        masked = false,
        error = error,
        submitLabel = s.loginSignIn,
        busyLabel = s.loginSigningIn,
        busy = submission.busy,
        canSubmit = code.isNotEmpty() && code != submitted,
        onSubmit = submit,
        // Not "Back": the code has been sent, so the way out is a fresh start, which is what
        // restarting the sign-in actually does.
        backLabel = s.loginStartOver,
        onBack = onStartOver,
        extras = {
            // TDLib sends a code again only by a route it offers next ([AuthState.Code.next]);
            // with none, a resend button would only ever answer with an error.
            if (state.canResend) {
                TmSecondaryButton(
                    onClick = {
                        resend.start {
                            resent++
                            submitted = null
                            onResend()
                        }
                    },
                    enabled = remaining <= 0,
                    loading = resend.busy,
                    busyLabel = s.loginSendingCode,
                ) {
                    Text(if (remaining > 0) s.loginResendIn(SignInWords.clock(remaining)) else SignInWords.resendLabel(state.next))
                }
            } else {
                // Never just gone: with no other route the row says what is left to do.
                SignInWords.resendNone(state)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tone.muted) }
            }
            if (SignInWords.qrInstead(state)) TmSecondaryButton(onClick = onUseQr) { Text(s.loginUseQr) }
            TmSecondaryButton(onClick = onChangeNumber) { Text(s.loginWrongNumber) }
        },
    )
}

/**
 * The code pane a phone gets: a row of boxes, a countdown, and a way back to the number.
 *
 * The boxes are one field wearing a costume. Five real fields would each have to hand focus to the
 * next, and none of them would take a pasted code or an autofill from a text message, which is how
 * most codes on a phone are entered.
 */
@Composable
private fun TouchCodePane(
    state: AuthState.Code,
    error: String?,
    onSubmit: (String) -> Unit,
    onResend: () -> Unit,
    onChangeNumber: () -> Unit,
    onStartOver: () -> Unit,
    onUseQr: () -> Unit,
) {
    val s = LocalStrings.current
    val boxed = SignInWords.isDigits(state.delivery)
    val length = SignInWords.codeLength(state)
    var code by rememberSaveable { mutableStateOf("") }
    // Which code has already been sent, so that a rejected one does not resubmit itself forever:
    // the field stays full after a wrong code, and auto-submit would fire on every recomposition.
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    var resent by rememberSaveable { mutableStateOf(0) }
    val remaining = rememberCountdown(state.timeout, state.phoneNumber to resent)
    val field = remember { FocusRequester() }
    // Two answers can be on their way at once, and they are not the same answer: the code being
    // checked and a fresh code being asked for. One flag between them would leave the button that
    // was not pressed looking busy.
    val signIn = rememberSubmission(state, error)
    val resend = rememberSubmission(state, error)

    val submit = {
        signIn.start {
            submitted = code
            onSubmit(code)
        }
    }

    // Typing the last digit is the whole answer, so asking for a button press afterwards is asking
    // twice. Only for the fixed-length kinds: a word or a phrase has no last box to notice.
    LaunchedEffect(code) {
        if (CodeInput.shouldAutoSubmit(code, length, boxed, submitted)) submit()
    }

    // A code that went by text can be filled from that text, with the viewer's say so in a system
    // prompt, and only in a build with Play Services ([SmsCodeConsent]).
    SmsCodeConsent(
        active = state.delivery == CodeDelivery.Sms || state.delivery == CodeDelivery.SmsWord || state.delivery == CodeDelivery.SmsPhrase,
        onMessage = { message -> code = CodeInput.clean(message, boxed, if (boxed) length else 0) },
    )

    Pane {
        PaneHeader(onBack = onStartOver)
        Text(s.loginCodeTitle, style = MaterialTheme.typography.headlineLarge)
        Text(SignInWords.codeBlurb(state), style = MaterialTheme.typography.bodyLarge)

        if (boxed) {
            CodeBoxes(
                code = code,
                length = length,
                // A paste or an autofill can be the whole text message; only its code is kept.
                onChange = { code = CodeInput.clean(it, true, length) },
                modifier = Modifier.focusRequester(field),
            )
        } else {
            PaneField(
                value = code,
                onValueChange = { code = it },
                placeholder = s.loginCode,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(field),
            )
        }

        if (error != null) {
            PaneError(error)
        }

        TmButton(
            onClick = submit,
            enabled = code.isNotEmpty() && code != submitted,
            loading = signIn.busy,
            busyLabel = s.loginSigningIn,
            modifier = Modifier.paneAction(),
        ) {
            Text(s.loginSignIn)
        }

        // Only where TDLib offers a next route: with none it refuses the resend.
        if (state.canResend) {
            TmSecondaryButton(
                onClick = {
                    resend.start {
                        resent++
                        submitted = null
                        onResend()
                    }
                },
                enabled = remaining <= 0,
                loading = resend.busy,
                busyLabel = s.loginSendingCode,
                modifier = Modifier.paneAction(),
            ) {
                Text(if (remaining > 0) s.loginResendIn(SignInWords.clock(remaining)) else SignInWords.resendLabel(state.next))
            }
        } else {
            // Never just gone: with no other route the row says what is left to do.
            SignInWords.resendNone(state)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tone.muted) }
        }

        if (SignInWords.qrInstead(state)) {
            TmSecondaryButton(onClick = onUseQr, modifier = Modifier.paneAction()) {
                Text(s.loginUseQr)
            }
        }

        TmSecondaryButton(onClick = onChangeNumber, modifier = Modifier.paneAction()) {
            Text(s.loginWrongNumber)
        }
        TmSecondaryButton(onClick = onStartOver, modifier = Modifier.paneAction()) {
            Text(s.loginStartOver)
        }
    }

    LaunchedEffect(Unit) { field.requestFocus() }
}

/**
 * One text field drawn as [length] separate boxes.
 *
 * The real field is stacked on top of the boxes with transparent text and no cursor, so it keeps
 * the tap target, the keyboard, the paste menu and the autofill suggestion, while what is read on
 * screen is drawn box by box below it.
 */
@Composable
private fun CodeBoxes(
    code: String,
    length: Int,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = code,
        onValueChange = onChange,
        singleLine = true,
        // Invisible, in both senses: the glyphs and the caret. The boxes below are the readout.
        textStyle = MaterialTheme.typography.titleLarge.copy(color = Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
            autoCorrectEnabled = false,
        ),
        modifier = modifier
            .fillMaxWidth()
            // A remote's arrows leave the code box (a one line field keeps them for its cursor).
            .then(if (isTouch()) Modifier else Modifier.dpadLeavesField())
            .semantics {
                contentType = ContentType.SmsOtpCode
                onFillData { data ->
                    val text = data.textValue?.toString() ?: return@onFillData false
                    onChange(text)
                    true
                }
            },
        decorationBox = { inner ->
            Box {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (index in 0 until length) {
                        val filled = index < code.length
                        // Only the box waiting for the next digit is outlined: without it the row
                        // is identical rectangles, with nothing to say where a tap will land.
                        val active = index == code.length
                        Box(
                            Modifier
                                .weight(1f)
                                .height(FIELD_HEIGHT)
                                .clip(M3.shapes.medium)
                                .background(Tone.surfaceHigh)
                                .border(
                                    width = if (active) 2.dp else 1.dp,
                                    color = if (active) Tone.accent else Tone.outline,
                                    shape = M3.shapes.medium,
                                )
                                .padding(2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (filled) code[index].toString() else "",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Tone.text,
                                textAlign = TextAlign.Center,
                            )
                            // A thin bar under the box being typed into, standing in for the
                            // caret that was turned off above.
                            if (active) {
                                Box(
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 8.dp)
                                        .width(20.dp)
                                        .height(2.dp)
                                        .background(Tone.accent),
                                )
                            }
                        }
                    }
                }
                inner()
            }
        },
    )
}

/** The touch pane's top row: an arrow out, and nothing at all on a television. */
@Composable
private fun PaneHeader(onBack: () -> Unit) {
    if (!isTouch()) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BackArrow(onBack = onBack)
    }
}

/**
 * The QR pane, which is where a television's sign-in begins.
 *
 * The way off is the other way in, [onUsePhone], for anybody who picked QR and then changed their
 * mind. Nothing is lost either way, because nobody is signed in yet.
 *
 * Back means two different things on the two devices, and both are what the viewer expects. On a
 * television this is the first screen, so Back leaves the app. On a phone it was reached from the
 * number pane, so Back goes there.
 */
@Composable
private fun QrPane(link: String, scanned: Boolean, onUsePhone: () -> Unit, onRefresh: () -> Unit) {
    val s = LocalStrings.current
    if (isTouch()) BackHandler(onBack = onUsePhone)

    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, key1 = link) {
        value = withContext(Dispatchers.Default) { QrCode.render(link, QR_PIXELS) }
    }
    // How far the code is through its life: the bar under the plate, and the veil once TDLib
    // could not renew it (no line).
    val life = rememberQrClock(link)

    // The code itself gets a white plate; contrast is what makes it scannable across a room.
    // A fixed 300dp square is fine on a television and wider than the content column on the
    // narrowest phones, where it would be clipped: the plate is square either way, but on touch it
    // takes the width it is given rather than the width it wants.
    val plateSize = if (isTouch()) {
        Modifier.fillMaxWidth().widthIn(max = PLATE).aspectRatio(1f)
    } else {
        Modifier.size(PLATE)
    }

    val plate: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                plateSize
                    .clip(RoundedCornerShape(Corner.ExtraLarge))
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                val rendered = bitmap
                if (rendered == null) {
                    BigLoader(null)
                } else {
                    Image(
                        bitmap = rendered,
                        contentDescription = s.loginQrDescription,
                        // Inset from the plate's edge: a QR code needs a quiet margin of its own
                        // colour around it or a scanner cannot find its corners.
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                    )
                }
                QrScannedVeil(scanned, Modifier.matchParentSize())
                // A code TDLib could not renew (no line) is veiled, never left looking usable.
                QrExpiredVeil(life.phase == QrPhase.Expired && !scanned, onRefresh, Modifier.matchParentSize())
            }
            if (!scanned) QrLifeCue(life, Modifier.widthIn(max = PLATE).fillMaxWidth())
        }
    }

    val words: @Composable ColumnScope.() -> Unit = {
        Text(s.loginQrTitle, style = MaterialTheme.typography.headlineLarge)
        Text(
            s.loginQrBlurb,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        Step(1, s.loginQrStepOpen)
        Step(2, s.loginQrStepDevices)
        Step(3, s.loginQrStepLink)
        Spacer(Modifier.height(8.dp))
        Text(
            s.loginQrNote,
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    // Side by side is a television's shape: wide, and read from a sofa. A phone held upright has
    // no room beside a 300dp plate for a paragraph, so the same parts stack instead.
    if (isTouch()) {
        Pane(horizontalAlignment = Alignment.CenterHorizontally) {
            PaneHeader(onBack = onUsePhone)
            plate()
            words()
            Spacer(Modifier.height(8.dp))
            TmSecondaryButton(onClick = onUsePhone, modifier = Modifier.paneAction()) {
                Text(s.loginUsePhone)
            }
            QrRefreshButton(onRefresh)
        }
        return
    }

    val first = remember { FocusRequester() }
    Row(
        Modifier.fillMaxSize().padding(horizontal = Tv.SafeH, vertical = Tv.SafeV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        plate()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // A television's first screen: which app is asking to be let in.
            com.tmplayer.ui.components.BrandLockup(
                size = com.tmplayer.ui.components.BrandSize.Medium,
                layout = com.tmplayer.ui.components.BrandLayout.Row,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            words()
            Spacer(Modifier.height(8.dp))
            // On a remote these are the only focusable things on the pane, so they are also what
            // gives the D-pad somewhere to be. Without them the screen takes no input at all.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TmSecondaryButton(onClick = onUsePhone, modifier = Modifier.focusRequester(first)) { Text(s.loginUsePhone) }
                QrRefreshButton(onRefresh)
            }
        }
    }
    // The remote starts on a button, not nowhere, so the first press does something.
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The disc is a quiet marker, not a call to action, so on a phone it takes the scheme's
        // container pair rather than the accent itself: full accent here competes with the button
        // underneath it, and on a wallpaper palette it can be loud enough to read as the subject.
        val touch = isTouch()
        // As big as its number: a fixed 28 dp disc cut the digit at twice the font (phone audit, 2026-11-25).
        val disc = with(androidx.compose.ui.platform.LocalDensity.current) { maxOf(28.dp, 28.sp.toDp()) }
        Box(
            Modifier
                .size(disc)
                .clip(CircleShape)
                .background(if (touch) M3.colorScheme.primaryContainer else Tone.focusFill),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$number",
                style = MaterialTheme.typography.bodyLarge,
                color = if (touch) M3.colorScheme.onPrimaryContainer else Tone.onFocusFill,
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Two-step verification: the password with show and hide, the account's own hint, and "Forgot
 * password?". With a recovery email that mails a code ([PasswordRecoveryPane]); without one it
 * points to Telegram's own app and help page: TMPlayer never resets an account.
 */
@Composable
private fun PasswordPane(
    state: AuthState.Password,
    error: String?,
    onSubmit: (String) -> Unit,
    onStartOver: () -> Unit,
    onRun: SignInRun,
) {
    val s = LocalStrings.current
    var password by remember { mutableStateOf("") }
    var shown by rememberSaveable { mutableStateOf(false) }
    // The "no recovery email" step.
    var forgot by rememberSaveable { mutableStateOf(false) }
    val submission = rememberSubmission(state, error)
    val asking = rememberSubmission(state, error)

    if (forgot) {
        BackHandler { forgot = false }
        NoRecoveryPane(error = error, onBack = { forgot = false })
        return
    }

    // The remote's Back is what anybody stuck here reaches for first.
    BackHandler(onBack = onStartOver)

    val reveal = if (shown) s.loginHidePassword else s.loginShowPassword
    LoginForm(
        title = s.loginTelegramPasswordTitle,
        blurb = s.loginPasswordBlurb,
        note = state.hint.takeIf { it.isNotBlank() }?.let { s.loginPasswordHint(it) },
        value = password,
        onValueChange = { password = it },
        placeholder = s.loginPassword,
        keyboardType = KeyboardType.Password,
        masked = !shown,
        trailing = {
            androidx.compose.material3.IconButton(onClick = { shown = !shown }) {
                androidx.compose.material3.Icon(if (shown) TmIcons.VisibilityOff else TmIcons.Visibility, contentDescription = reveal)
            }
        },
        error = error,
        submitLabel = s.loginSignIn,
        busyLabel = s.loginSigningIn,
        busy = submission.busy,
        canSubmit = password.isNotEmpty(),
        onSubmit = { submission.start { onSubmit(password) } },
        // The way out: scanned with the wrong account, or the password is not to hand. Nothing is
        // lost, because nobody is signed in yet.
        backLabel = s.loginStartOver,
        onBack = onStartOver,
        extras = {
            if (!isTouch()) TmSecondaryButton(onClick = { shown = !shown }) { Text(reveal) }
            TmSecondaryButton(
                onClick = {
                    if (state.recoveryEmail == null) {
                        forgot = true
                    } else {
                        asking.start { onRun { Td.forgotPassword() } }
                    }
                },
                loading = asking.busy,
                busyLabel = s.loginSendingCode,
            ) { Text(s.loginForgotPassword) }
        },
    )
}

/** No password and no recovery email: Telegram's own app and help page are the way back in. */
@Composable
private fun NoRecoveryPane(
    error: String?,
    onBack: () -> Unit,
) {
    val s = LocalStrings.current
    val touch = isTouch()
    val back = remember { FocusRequester() }
    Pane {
        PaneHeader(onBack = onBack)
        Text(s.loginNoRecoveryTitle, style = MaterialTheme.typography.headlineLarge)
        Text(s.loginNoRecoveryHelp, style = MaterialTheme.typography.bodyLarge)
        if (error != null) PaneError(error)
        TmButton(onClick = onBack, modifier = (if (touch) Modifier.paneAction() else Modifier).focusRequester(back)) {
            Text(s.loginBackToPassword)
        }
    }
    LaunchedEffect(Unit) { if (!touch) runCatching { back.requestFocus() } }
}

/**
 * "Forgot password?" with a recovery email: the code Telegram mailed, then a new password and
 * its hint, or none, which turns two-step verification off and says so on the button.
 */
@Composable
private fun PasswordRecoveryPane(state: AuthState.PasswordRecovery, error: String?, onRun: SignInRun) {
    val s = LocalStrings.current
    val submission = rememberSubmission(state, error)
    val back = { onRun { Td.backToPassword(); null } }
    BackHandler(onBack = back)

    if (state.code == null) {
        var code by rememberSaveable { mutableStateOf("") }
        LoginForm(
            title = s.loginRecoveryTitle,
            blurb = s.loginRecoveryBody(state.pattern),
            note = null,
            value = code,
            onValueChange = { code = it.filter(Char::isLetterOrDigit).take(RECOVERY_CODE_MAX) },
            placeholder = s.loginRecoveryCodeLabel,
            keyboardType = KeyboardType.Number,
            masked = false,
            error = error,
            submitLabel = s.loginSignIn,
            busyLabel = s.loginSigningIn,
            busy = submission.busy,
            canSubmit = code.isNotBlank(),
            onSubmit = { submission.start { onRun { Td.submitRecoveryCode(code) } } },
            backLabel = s.loginBackToPassword,
            onBack = back,
        )
        return
    }

    var password by remember { mutableStateOf("") }
    var hint by rememberSaveable { mutableStateOf("") }
    var shown by rememberSaveable { mutableStateOf(false) }
    val reveal = if (shown) s.loginHidePassword else s.loginShowPassword
    LoginForm(
        title = s.loginRecoveryNewTitle,
        blurb = s.loginRecoveryNewBody,
        note = null,
        value = password,
        onValueChange = { password = it },
        placeholder = s.loginRecoveryNewLabel,
        keyboardType = KeyboardType.Password,
        masked = !shown,
        trailing = {
            androidx.compose.material3.IconButton(onClick = { shown = !shown }) {
                androidx.compose.material3.Icon(if (shown) TmIcons.VisibilityOff else TmIcons.Visibility, contentDescription = reveal)
            }
        },
        error = error,
        submitLabel = if (password.isEmpty()) s.loginRecoveryTurnOff else s.loginRecoverySave,
        busyLabel = s.loginSigningIn,
        busy = submission.busy,
        canSubmit = true,
        onSubmit = { submission.start { onRun { Td.finishPasswordRecovery(password, hint) } } },
        backLabel = s.loginBackToPassword,
        onBack = back,
        below = {
            if (password.isNotEmpty()) {
                PaneField(
                    value = hint,
                    onValueChange = { hint = it.take(HINT_MAX) },
                    placeholder = s.loginRecoveryHintLabel,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        extras = { if (!isTouch()) TmSecondaryButton(onClick = { shown = !shown }) { Text(reveal) } },
    )
}

/** Telegram asks this account for an email address to sign in with, and mails a code there next. */
@Composable
private fun EmailAddressPane(
    state: AuthState.EmailAddress,
    error: String?,
    onSubmit: (String) -> Unit,
    onStartOver: () -> Unit,
) {
    val s = LocalStrings.current
    var address by rememberSaveable { mutableStateOf("") }
    val submission = rememberSubmission(state, error)
    BackHandler(onBack = onStartOver)
    LoginForm(
        title = s.loginEmailAddressTitle,
        blurb = s.loginEmailAddressBody,
        note = null,
        value = address,
        onValueChange = { address = it.trim() },
        placeholder = s.loginEmailAddressLabel,
        keyboardType = KeyboardType.Email,
        masked = false,
        error = error,
        submitLabel = s.loginEmailSend,
        busyLabel = s.loginSendingCode,
        busy = submission.busy,
        canSubmit = '@' in address && '.' in address.substringAfter('@'),
        onSubmit = { submission.start { onSubmit(address) } },
        backLabel = s.loginStartOver,
        onBack = onStartOver,
    )
}

/**
 * The code Telegram mailed to the account's login email, which it uses in place of a text for some
 * accounts. Before this pane the sign in ended in "a step TMPlayer can't do", and such an account
 * could not sign in here at all. With the code again on request, and the email reset with its
 * waiting period as TDLib reports it, for somebody who no longer has that mailbox.
 */
@Composable
private fun EmailCodePane(
    state: AuthState.EmailCode,
    error: String?,
    onSubmit: (String) -> Unit,
    onStartOver: () -> Unit,
    onRun: SignInRun,
) {
    val s = LocalStrings.current
    var code by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    val submission = rememberSubmission(state, error)
    val asking = rememberSubmission(state, error)
    val length = state.length.takeIf { it > 0 } ?: SignInWords.MAX_CODE_LENGTH
    BackHandler(onBack = onStartOver)
    val submit = { submission.start { submitted = code; onSubmit(code) } }
    LaunchedEffect(code) { if (state.length > 0 && CodeInput.shouldAutoSubmit(code, state.length, true, submitted)) submit() }
    val pendingLeft = rememberCountdown((state.reset as? EmailReset.Pending)?.resetInSeconds ?: 0, state.reset)
    LoginForm(
        title = s.loginEmailCodeTitle,
        blurb = s.loginEmailCodeBody(state.pattern),
        note = when (val reset = state.reset) {
            is EmailReset.Available -> s.loginEmailResetAvailable(Failures.waitPhrase(reset.waitSeconds))
            is EmailReset.Pending -> s.loginEmailResetPending(Failures.waitPhrase(pendingLeft))
            EmailReset.None -> null
        },
        value = code,
        onValueChange = { code = CodeInput.clean(it, true, length) },
        placeholder = s.loginEmailCodeLabel,
        keyboardType = KeyboardType.Number,
        masked = false,
        error = error,
        submitLabel = s.loginSignIn,
        busyLabel = s.loginSigningIn,
        busy = submission.busy,
        canSubmit = code.isNotBlank() && code != submitted,
        onSubmit = submit,
        backLabel = s.loginStartOver,
        onBack = onStartOver,
        extras = {
            TmSecondaryButton(
                onClick = { asking.start { submitted = null; onRun { Td.resendCode() } } },
                loading = asking.busy,
                busyLabel = s.loginSendingCode,
            ) { Text(s.loginEmailResend) }
            if (state.reset is EmailReset.Available) {
                TmSecondaryButton(onClick = { asking.start { onRun { Td.resetEmailAddress() } } }) { Text(s.loginEmailReset) }
            }
        },
    )
}

/**
 * The one typed pane, worn by the password everywhere and by the number and the code on a
 * television.
 *
 * All of them ask a single question, take a single line and offer the same way back out, so they
 * are one layout with different words rather than three that have to be kept looking alike.
 * [extras] are the step's other ways on (resend, forgot password), under the two main buttons;
 * [below] sits under the field (the recovery's password hint).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LoginForm(
    title: String,
    blurb: String,
    note: String?,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    masked: Boolean,
    error: String?,
    submitLabel: String,
    /** What the submit button says while the answer is with Telegram. */
    busyLabel: String,
    busy: Boolean,
    canSubmit: Boolean,
    onSubmit: () -> Unit,
    backLabel: String,
    onBack: () -> Unit,
    transformation: VisualTransformation? = null,
    trailing: (@Composable () -> Unit)? = null,
    below: @Composable () -> Unit = {},
    extras: @Composable () -> Unit = {},
    /** False while the field waits on something (the country guess); it opens with the focus in it. */
    enabled: Boolean = true,
    /** The [note] is still being worked out: a shimmer stands in its place. */
    noteLoading: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    // Where a remote's focus waits while the field is closed: a disabled field cannot hold it,
    // and a screen with nothing focused leaves the D-pad with nowhere to start from.
    val waitFocus = remember { FocusRequester() }
    val touch = isTouch()
    // D-3: with the keyboard up the buttons stay in sight. A television opens it over the lower half
    // of the screen without moving the page, so its column starts at the top (the buttons end
    // above the keyboard); a phone scrolls to the buttons when the keyboard comes up, the field
    // staying in view above them.
    val scroll = rememberScrollState()
    val keyboardUp = WindowInsets.isImeVisible
    if (touch) {
        LaunchedEffect(keyboardUp, scroll.maxValue) {
            if (keyboardUp) runCatching { scroll.animateScrollTo(scroll.maxValue) }
        }
    }

    Pane(topOnTv = true, scroll = scroll) {
        PaneHeader(onBack = onBack)
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(blurb, style = MaterialTheme.typography.bodyLarge)
        if (note != null || noteLoading) {
            GuessFade(noteLoading, skeletonWidth = 180.dp) {
                Text(note.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            }
        }

        PaneField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { if (canSubmit) onSubmit() }),
            visualTransformation = when {
                masked -> PasswordVisualTransformation()
                transformation != null -> transformation
                else -> VisualTransformation.None
            },
            trailing = if (touch) trailing else null,
            isError = error != null,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        below()

        if (error != null) {
            // Red, not the accent blue: the accent is the focus colour on this very screen,
            // so a rejection painted in it reads as a status line rather than as a problem.
            PaneError(error)
        }

        if (touch) {
            TmButton(
                onClick = onSubmit,
                enabled = canSubmit,
                loading = busy,
                busyLabel = busyLabel,
                modifier = Modifier.paneAction(),
            ) {
                Text(submitLabel)
            }
            TmSecondaryButton(onClick = onBack, modifier = Modifier.paneAction()) {
                Text(backLabel)
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                extras()
            }
            return@Pane
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TmButton(onClick = onSubmit, enabled = canSubmit, loading = busy, busyLabel = busyLabel) {
                Text(submitLabel)
            }
            TmSecondaryButton(onClick = onBack, modifier = Modifier.focusRequester(waitFocus)) {
                Text(backLabel)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            extras()
        }
    }

    LaunchedEffect(enabled) {
        runCatching {
            when {
                enabled -> focus.requestFocus()
                !touch -> waitFocus.requestFocus()
            }
        }
    }
}

/**
 * How long a button may sit busy with nothing having come back.
 *
 * Only reached by a call whose success is silent, and the one of those is asking for the code
 * again, which the countdown beside it has already disabled for a minute or more. Everything else
 * answers with a state or with an error long before this.
 */
private const val PENDING_CEILING_MS = 8_000L

private const val QR_PIXELS = 560

private val PLATE = 300.dp

/**
 * Wide enough for "+888", which is the longest code Telegram issues, and now also for the floating
 * label above it: an outlined field that cannot fit its own label draws it clipped.
 */
private val DIAL_WIDTH = 104.dp

/** Telegram's recovery codes are six digits; room for a longer one, never a whole email. */
private const val RECOVERY_CODE_MAX = 12

/** Telegram cuts a password hint off well before this. */
private const val HINT_MAX = 64
