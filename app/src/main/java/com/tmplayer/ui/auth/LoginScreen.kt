package com.tmplayer.ui.auth

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.material3.MaterialTheme as M3
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.AuthState
import com.tmplayer.data.CodeDelivery
import com.tmplayer.data.Country
import com.tmplayer.data.PhoneCountries
import com.tmplayer.data.SignInMethod
import com.tmplayer.data.Td
import com.tmplayer.ui.components.BigError
import com.tmplayer.ui.components.BigLoader
import com.tmplayer.ui.components.Pane
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.paneAction
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
) {
    // "Change number" on the code pane. TDLib is happy to be told a different number while it is
    // waiting for a code, so this is a change of screen and nothing more: no logout, no lost
    // session, and the code that was already sent stays valid if the user comes straight back.
    var changingNumber by rememberSaveable(state::class) { mutableStateOf(false) }

    when (state) {
        is AuthState.Connecting -> BigLoader("Connecting to Telegram…")
        is AuthState.ChooseMethod -> MethodPane(onChooseMethod)
        is AuthState.Qr -> QrPane(state.link, onBack = onStartOver)
        is AuthState.Phone -> PhonePane(
            error = submitError,
            onSubmit = onSubmitPhoneNumber,
            onBack = onCancelPhoneEntry,
            onUseQr = { onChooseMethod(SignInMethod.Qr) },
        )
        is AuthState.Code ->
            if (changingNumber) {
                PhonePane(
                    error = submitError,
                    onSubmit = onSubmitPhoneNumber,
                    onBack = { changingNumber = false },
                    onUseQr = { onChooseMethod(SignInMethod.Qr) },
                )
            } else {
                CodePane(
                    state = state,
                    error = submitError,
                    onSubmit = onSubmitCode,
                    onResend = onResendCode,
                    onChangeNumber = { changingNumber = true },
                    onStartOver = onStartOver,
                )
            }
        is AuthState.Password ->
            PasswordPane(state.hint, submitError, onSubmitPassword, onStartOver)
        is AuthState.Ready -> BigLoader("Signing in…")
        is AuthState.Failed -> BigError(state.message, onRetry = null)
    }
}

/**
 * The first thing anybody sees. QR holds the focus because it is the right answer on a TV, where
 * typing a number costs a minute on an on-screen keyboard; the phone route is there for the device
 * that would otherwise have to scan a code being displayed on itself.
 */
@Composable
private fun MethodPane(onChoose: (SignInMethod) -> Unit) {
    val focus = remember { FocusRequester() }
    val touch = isTouch()
    val activity = LocalActivity.current
    val toast = rememberToast()
    // The top of the sign-in, so Back here means leaving the app. Two presses, the same guard the
    // chat list uses: on a remote Back sits right beside the D-pad, and before this a stray press
    // dropped the viewer out of the app in the middle of scanning a code.
    var exitArmed by remember { mutableStateOf(false) }
    BackHandler {
        if (exitArmed) activity?.finish() else { exitArmed = true; toast("Press Back again to leave") }
    }
    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            delay(EXIT_WINDOW_MS)
            exitArmed = false
        }
    }

    Pane {
        Text("Sign in to Telegram", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Two ways in, and both end up at the same account.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))

        // On a phone the account almost always lives on this very device, so the number is the
        // first thing offered and the QR code the second. On a TV it is the other way round.
        if (touch) {
            TmButton(
                onClick = { onChoose(SignInMethod.Phone) },
                modifier = Modifier.paneAction(),
            ) {
                Text("Use my phone number")
            }
            Text(
                "Telegram sends a code to your account, and you type it in here.",
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.muted,
            )
            Spacer(Modifier.height(8.dp))
            TmSecondaryButton(
                onClick = { onChoose(SignInMethod.Qr) },
                modifier = Modifier.paneAction(),
            ) {
                Text("Scan a QR code")
            }
            Text(
                "For when your account is on another phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.muted,
            )
            return@Pane
        }

        TmButton(
            onClick = { onChoose(SignInMethod.Qr) },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        ) {
            Text("Scan a QR code")
        }
        Text(
            "Quickest here: hold up the phone that already has your account.",
            style = MaterialTheme.typography.bodyMedium,
            color = Tone.muted,
        )
        Spacer(Modifier.height(8.dp))

        TmButton(
            onClick = { onChoose(SignInMethod.Phone) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Use my phone number")
        }
        Text(
            "Telegram sends a code to your account, and you type it in here.",
            style = MaterialTheme.typography.bodyMedium,
            color = Tone.muted,
        )
    }

    // Only the remote needs somewhere to start. Asking for focus on a phone opens the keyboard
    // over a pane that has no field on it.
    if (!touch) LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun PhonePane(
    error: String?,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
    onUseQr: () -> Unit,
) {
    // Back is what anybody who picked the wrong route reaches for, and nothing has been sent yet.
    BackHandler(onBack = onBack)

    if (isTouch()) {
        TouchPhonePane(error, onSubmit, onBack, onUseQr)
        return
    }

    var number by rememberSaveable { mutableStateOf("+") }

    LoginForm(
        title = "Your phone number",
        blurb = "The number your Telegram account is registered to, with its country code.",
        note = null,
        value = number,
        // The keyboard type is a request, not a guarantee: a leanback remote's on-screen keyboard
        // and every hardware keyboard will happily send letters to a field that asked for a
        // dialpad. What a number is made of is decided here instead.
        onValueChange = { number = it.keepPhoneCharacters() },
        placeholder = "+44 7700 900000",
        keyboardType = KeyboardType.Phone,
        masked = false,
        error = error,
        submitLabel = "Send me a code",
        canSubmit = number.count { it.isDigit() } >= MIN_PHONE_DIGITS,
        onSubmit = { onSubmit(number) },
        backLabel = "Back",
        onBack = onBack,
    )
}

/**
 * The number pane as Telegram itself asks for a number: country first, then a narrow dial code
 * beside a wide national number.
 *
 * The single international field the television uses works there because the remote is slow enough
 * that one field is a mercy; on a phone it asks people to know a dial code they have never had to
 * type, and it formats nothing, so a fifteen digit run comes out as a wall.
 */
@Composable
private fun TouchPhonePane(
    error: String?,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
    onUseQr: () -> Unit,
) {
    val context = LocalContext.current
    var countries by remember { mutableStateOf(emptyList<Country>()) }
    var dial by rememberSaveable { mutableStateOf("") }
    // Plain digits, exactly as typed. Telegram's own grouping used to be pulled in and written back
    // into the field a moment after typing, which meant the text under the cursor changed length
    // while the cursor sat still: a space appearing to the left of it moved the caret a character
    // back, and on keyboards that report a selection of their own it jumped further still. The
    // grouping was never worth that, so the field is now left alone and the spaces are gone.
    var national by rememberSaveable { mutableStateOf("") }
    var picking by rememberSaveable { mutableStateOf(false) }
    val country = remember(countries, dial) { PhoneCountries.byDialCode(countries, dial) }
    val field = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // Off the main thread's critical path by construction: the pane draws with an empty list
        // and fills in when TDLib answers, so a slow first connection never holds up the frame.
        countries = PhoneCountries.from(Td.countries())
        if (dial.isNotEmpty()) return@LaunchedEffect
        val iso = Td.guessedCountryCode().ifBlank { PhoneCountries.deviceCountryCode(context) }
        if (dial.isEmpty()) dial = PhoneCountries.byIso(countries, iso)?.dialCode.orEmpty()
    }

    if (picking) {
        CountryPicker(
            countries = countries,
            onPick = {
                dial = it.dialCode
                picking = false
            },
            onDismiss = { picking = false },
        )
        return
    }

    val submit = { onSubmit("+$dial$national") }

    Pane {
        PaneHeader(onBack = onBack)
        Text("Your phone number", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Telegram will send a code to the account this number belongs to.",
            style = MaterialTheme.typography.bodyLarge,
        )

        PaneChooser(
            label = country?.let { "${it.flag}  ${it.name}" } ?: "Choose a country",
            trailing = "Change",
            onClick = { picking = true },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PaneField(
                value = dial,
                // Typing the code is the other half of the picker: people who know their own
                // reach for it before they will scroll a list to find their country.
                onValueChange = { dial = it.filter { c -> c.isDigit() }.take(MAX_DIAL_DIGITS) },
                placeholder = "00",
                // The placeholder is the shape of a dial code, which is no use as a name for the
                // field once it is filled in, so the floating label is given words of its own.
                label = "Code",
                prefix = "+",
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.width(DIAL_WIDTH),
            )
            PaneField(
                value = national,
                // Digits and nothing else. The dialpad is a hint the IME may ignore, and a letter
                // typed here would sit in the field looking accepted while being quietly dropped
                // from the number that is actually sent.
                onValueChange = { national = it.keepNationalCharacters() },
                placeholder = "Phone number",
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(field)
                    .semantics { contentType = ContentType.PhoneNumberNational },
            )
        }

        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodyLarge, color = Tone.danger)
        }

        TmButton(
            onClick = submit,
            enabled = (dial + national).length >= MIN_PHONE_DIGITS,
            modifier = Modifier.paneAction(),
        ) {
            Text("Send me a code")
        }
        TmSecondaryButton(onClick = onUseQr, modifier = Modifier.paneAction()) {
            Text("Scan a QR code instead")
        }
    }

    // The keyboard is up either way on this pane, so it may as well be pointed at the field the
    // user came here to fill.
    LaunchedEffect(Unit) { field.requestFocus() }
}

@Composable
private fun CodePane(
    state: AuthState.Code,
    error: String?,
    onSubmit: (String) -> Unit,
    onResend: () -> Unit,
    onChangeNumber: () -> Unit,
    onStartOver: () -> Unit,
) {
    BackHandler(onBack = onStartOver)

    if (isTouch()) {
        TouchCodePane(state, error, onSubmit, onResend, onChangeNumber, onStartOver)
        return
    }

    var code by rememberSaveable { mutableStateOf("") }
    // Most deliveries are a run of digits, but a couple are a word or a phrase, and those have to
    // keep their letters. The phone's version of this pane already asks the same question.
    val digits = state.delivery.isDigits()

    LoginForm(
        title = "Enter your code",
        blurb = codeBlurb(state),
        note = null,
        value = code,
        // A number keyboard is a hint the leanback remote is free to ignore, so a code that is
        // only ever digits is held to that here rather than trusted to the keyboard.
        onValueChange = { entered ->
            code = if (digits) entered.filter(Char::isDigit).take(MAX_CODE_LENGTH) else entered
        },
        placeholder = "Code",
        keyboardType = if (digits) KeyboardType.Number else KeyboardType.Text,
        masked = false,
        error = error,
        submitLabel = "Sign in",
        canSubmit = code.isNotEmpty(),
        onSubmit = { onSubmit(code) },
        // Not "Back": the code has been sent, so the way out is a fresh start, which is what
        // restarting the sign-in actually does.
        backLabel = "Start over",
        onBack = onStartOver,
    )
}

/**
 * The code pane a phone gets: a row of boxes, a countdown, and a way back to the number.
 *
 * The boxes are one field wearing a costume. Five real fields would each have to hand focus to the
 * next, and none of them would take a pasted code or an autofill from a text message, which is how
 * most codes on a phone are actually entered: the user taps a suggestion above the keyboard and
 * never reads the digits at all.
 */
@Composable
private fun TouchCodePane(
    state: AuthState.Code,
    error: String?,
    onSubmit: (String) -> Unit,
    onResend: () -> Unit,
    onChangeNumber: () -> Unit,
    onStartOver: () -> Unit,
) {
    val boxed = state.delivery.isDigits()
    val length = state.length.takeIf { it in 1..MAX_CODE_LENGTH } ?: DEFAULT_CODE_LENGTH
    var code by rememberSaveable { mutableStateOf("") }
    // Which code has already been sent, so that a rejected one does not resubmit itself forever:
    // the field stays full after a wrong code, and auto-submit would fire on every recomposition.
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    var remaining by rememberSaveable(state.timeout, state.phoneNumber) { mutableStateOf(state.timeout) }
    val field = remember { FocusRequester() }

    LaunchedEffect(remaining) {
        if (remaining <= 0) return@LaunchedEffect
        delay(1000)
        remaining -= 1
    }

    val submit = {
        submitted = code
        onSubmit(code)
    }

    // Typing the last digit is the whole answer, so asking for a button press afterwards is asking
    // twice. Only for the fixed-length kinds: a word or a phrase has no last box to notice.
    LaunchedEffect(code) {
        if (boxed && code.length == length && code != submitted) submit()
    }

    Pane {
        PaneHeader(onBack = onStartOver)
        Text("Enter your code", style = MaterialTheme.typography.headlineLarge)
        Text(codeBlurb(state), style = MaterialTheme.typography.bodyLarge)

        if (boxed) {
            CodeBoxes(
                code = code,
                length = length,
                onChange = { code = it.filter(Char::isDigit).take(length) },
                modifier = Modifier.focusRequester(field),
            )
        } else {
            PaneField(
                value = code,
                onValueChange = { code = it },
                placeholder = "Code",
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
            Text(error, style = MaterialTheme.typography.bodyLarge, color = Tone.danger)
        }

        TmButton(
            onClick = submit,
            enabled = code.isNotEmpty() && code != submitted,
            modifier = Modifier.paneAction(),
        ) {
            Text("Sign in")
        }

        TmSecondaryButton(
            onClick = {
                remaining = state.timeout
                submitted = null
                onResend()
            },
            enabled = remaining <= 0,
            modifier = Modifier.paneAction(),
        ) {
            Text(if (remaining > 0) "Resend in ${clock(remaining)}" else resendLabel(state.next))
        }

        TmSecondaryButton(onClick = onChangeNumber, modifier = Modifier.paneAction()) {
            Text("Change number")
        }
        TmSecondaryButton(onClick = onStartOver, modifier = Modifier.paneAction()) {
            Text("Start over")
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
                        // The box waiting for the next digit is the only one with anything to say,
                        // so it is the only one outlined: without it the row is five identical
                        // rectangles and nothing tells the eye where a tap will land.
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

/** Where the code went, in the user's words rather than TDLib's. */
private fun codeBlurb(state: AuthState.Code): String = when (state.delivery) {
    CodeDelivery.TelegramApp ->
        "We sent a code to the Telegram app on your other device. Check Telegram, not your texts."
    CodeDelivery.Sms -> "We texted a code to ${state.phoneNumber}."
    CodeDelivery.Call -> "You are about to get a call to ${state.phoneNumber} reading out the code."
    CodeDelivery.MissedCall, CodeDelivery.FlashCall ->
        "You are about to get a call you do not have to answer. The code is the last digits of " +
            "the number that calls."
    CodeDelivery.Fragment -> "Your code is waiting for you on Fragment."
    CodeDelivery.SmsWord -> "We texted you a word. Type the word, not a number."
    CodeDelivery.SmsPhrase -> "We texted you a phrase. Type the phrase, not a number."
    CodeDelivery.Firebase ->
        "We are checking this device with Google, then texting a code to ${state.phoneNumber}."
    CodeDelivery.Unknown -> "Telegram has sent a code to ${state.phoneNumber}."
}

/** Named after what pressing it will actually do, which is not always "the same again". */
private fun resendLabel(next: CodeDelivery?): String = when (next) {
    CodeDelivery.Sms -> "Send by SMS instead"
    CodeDelivery.Call, CodeDelivery.MissedCall, CodeDelivery.FlashCall -> "Call me instead"
    CodeDelivery.TelegramApp -> "Send to the Telegram app instead"
    CodeDelivery.Fragment -> "Send it to Fragment instead"
    else -> "Send the code again"
}

/** True where the code is digits of a known length, which is what a row of boxes can hold. */
private fun CodeDelivery.isDigits(): Boolean = when (this) {
    CodeDelivery.SmsWord, CodeDelivery.SmsPhrase, CodeDelivery.FlashCall,
    CodeDelivery.MissedCall,
    -> false
    else -> true
}

private fun clock(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

/** The touch pane's top row: an arrow out, and nothing at all on a television. */
@Composable
private fun PaneHeader(onBack: () -> Unit) {
    if (!isTouch()) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BackArrow(onBack = onBack)
    }
}

/**
 * The QR pane, with a way back off it.
 *
 * It used to have none: no header, no button, and no back handler, so anybody who picked QR and
 * then changed their mind (the account is on this very phone, the other phone is upstairs) had no
 * way out short of killing the app. Going back means abandoning the half-started sign-in, which is
 * what [onBack] does; nothing is lost, because nobody is signed in yet.
 */
@Composable
private fun QrPane(link: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = link) {
        value = withContext(Dispatchers.Default) { QrCode.render(link, QR_PIXELS) }
    }

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
                    bitmap = rendered.asImageBitmap(),
                    contentDescription = "Telegram login QR code",
                    // Inset from the plate's edge: a QR code needs a quiet margin of its own
                    // colour around it or a scanner cannot find its corners.
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                )
            }
        }
    }

    val words: @Composable ColumnScope.() -> Unit = {
        Text("Sign in to Telegram", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Scan this code with the phone that has your Telegram account.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        Step(1, "Open Telegram on your phone")
        Step(2, "Go to Settings, then Devices")
        Step(3, "Tap “Link Desktop Device” and point the camera here")
        Spacer(Modifier.height(8.dp))
        Text(
            "The code refreshes on its own. It is read by Telegram and nobody else.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    // Side by side is a television's shape: wide, and read from a sofa. A phone held upright has
    // no room beside a 300dp plate for a paragraph, so the same parts stack instead.
    if (isTouch()) {
        Pane(horizontalAlignment = Alignment.CenterHorizontally) {
            PaneHeader(onBack = onBack)
            plate()
            words()
            Spacer(Modifier.height(8.dp))
            TmSecondaryButton(onClick = onBack, modifier = Modifier.paneAction()) {
                Text("Sign in another way")
            }
        }
        return
    }

    Row(
        Modifier.fillMaxSize().padding(horizontal = Tv.SafeH, vertical = Tv.SafeV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        plate()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            words()
            Spacer(Modifier.height(8.dp))
            // On a remote this is the only focusable thing on the pane, so it is also what gives
            // the D-pad somewhere to be: before it, the screen took no input at all.
            TmSecondaryButton(onClick = onBack) { Text("Sign in another way") }
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The disc is a quiet marker, not a call to action, so on a phone it takes the scheme's
        // container pair rather than the accent itself: full accent here competes with the button
        // underneath it, and on a wallpaper palette it can be loud enough to read as the subject.
        val touch = isTouch()
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (touch) M3.colorScheme.primaryContainer else Tone.accent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$number",
                style = MaterialTheme.typography.bodyLarge,
                color = if (touch) M3.colorScheme.onPrimaryContainer else Color.White,
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PasswordPane(
    hint: String,
    error: String?,
    onSubmit: (String) -> Unit,
    onStartOver: () -> Unit,
) {
    var password by remember { mutableStateOf("") }

    // The remote's Back is what anybody stuck here reaches for first, and it did nothing.
    BackHandler(onBack = onStartOver)

    LoginForm(
        title = "Two-step verification",
        blurb = "Your Telegram account has a password. Enter it to finish signing in.",
        note = hint.takeIf { it.isNotBlank() }?.let { "Hint: $it" },
        value = password,
        onValueChange = { password = it },
        placeholder = "Password",
        keyboardType = KeyboardType.Password,
        masked = true,
        error = error,
        submitLabel = "Sign in",
        canSubmit = password.isNotEmpty(),
        onSubmit = { onSubmit(password) },
        // The way out: scanned with the wrong account, or the password is not to hand. Nothing is
        // lost, because nobody is signed in yet.
        backLabel = "Start over",
        onBack = onStartOver,
    )
}

/**
 * The one typed pane, worn by the password everywhere and by the number and the code on a
 * television.
 *
 * All of them ask a single question, take a single line, and offer the same way back out, so they
 * are one layout with different words rather than three that have to be kept looking alike.
 */
@Composable
private fun LoginForm(
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
    canSubmit: Boolean,
    onSubmit: () -> Unit,
    backLabel: String,
    onBack: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val touch = isTouch()

    Pane {
        PaneHeader(onBack = onBack)
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(blurb, style = MaterialTheme.typography.bodyLarge)
        if (note != null) {
            Text(note, style = MaterialTheme.typography.bodyMedium)
        }

        PaneField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            visualTransformation = if (masked) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )

        if (error != null) {
            // Red, not the accent blue: the accent is the focus colour on this very screen,
            // so a rejection painted in it reads as a status line rather than as a problem.
            Text(error, style = MaterialTheme.typography.bodyLarge, color = Tone.danger)
        }

        if (touch) {
            TmButton(onClick = onSubmit, enabled = canSubmit, modifier = Modifier.paneAction()) {
                Text(submitLabel)
            }
            TmSecondaryButton(onClick = onBack, modifier = Modifier.paneAction()) {
                Text(backLabel)
            }
            return@Pane
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TmButton(onClick = onSubmit, enabled = canSubmit) {
                Text(submitLabel)
            }
            TmSecondaryButton(onClick = onBack) {
                Text(backLabel)
            }
        }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** How long a first press of Back stays armed before it is forgotten, as on the chat list. */
private const val EXIT_WINDOW_MS = 2_000L

private const val QR_PIXELS = 560

private val PLATE = 300.dp

/**
 * Wide enough for "+888", which is the longest code Telegram issues, and now also for the floating
 * label above it: an outlined field that cannot fit its own label draws it clipped.
 */
private val DIAL_WIDTH = 104.dp

private const val MAX_DIAL_DIGITS = 4

/** Telegram's own code length, and what TDLib sends when it sends anything. */
private const val DEFAULT_CODE_LENGTH = 5

private const val MAX_CODE_LENGTH = 8

// The shortest national numbers in use run to seven digits once the country code is counted, so
// anything below this cannot be a real number and the button stays out of reach.
private const val MIN_PHONE_DIGITS = 7

/**
 * What may appear in the international field: digits, one leading plus, and the spaces that group
 * them. Written as a filter on the whole value rather than on the last keystroke, because a paste
 * or an autofill arrives as an entire string and would otherwise walk straight past a check that
 * only looked at what changed.
 */
private fun String.keepPhoneCharacters(): String {
    val kept = filter { it.isDigit() || it == '+' || it == ' ' }
    val plus = if (kept.startsWith('+')) "+" else ""
    return plus + kept.filter { it.isDigit() || it == ' ' }
}

/**
 * The national field: digits only. It carries no plus, because the dial code beside it is where
 * that lives, and no spaces, because nothing groups them any more.
 */
private fun String.keepNationalCharacters(): String = filter { it.isDigit() }
