package com.tmplayer.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.AuthState
import com.tmplayer.data.SignInMethod
import com.tmplayer.ui.components.BigError
import com.tmplayer.ui.components.BigLoader
import com.tmplayer.ui.theme.Danger
import com.tmplayer.ui.theme.Accent
import com.tmplayer.ui.theme.SurfaceDark
import com.tmplayer.ui.theme.TextMuted
import com.tmplayer.ui.theme.TextPrimary
import com.tmplayer.ui.theme.Tv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TmSecondaryButton

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
) {
    when (state) {
        is AuthState.Connecting -> BigLoader("Connecting to Telegram…")
        is AuthState.ChooseMethod -> MethodPane(onChooseMethod)
        is AuthState.Qr -> QrPane(state.link)
        is AuthState.Phone ->
            PhonePane(submitError, onSubmitPhoneNumber, onCancelPhoneEntry)
        is AuthState.Code ->
            CodePane(state.phoneNumber, submitError, onSubmitCode, onStartOver)
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

    Box(
        Modifier.fillMaxSize().padding(horizontal = Tv.SafeH, vertical = Tv.SafeV),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(640.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Sign in to Telegram", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Two ways in, and both end up at the same account.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(8.dp))

            TmButton(
                onClick = { onChoose(SignInMethod.Qr) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            ) {
                Text("Scan a QR code")
            }
            Text(
                "Quickest here: hold up the phone that already has your account.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
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
                color = TextMuted,
            )
        }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun PhonePane(
    error: String?,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
) {
    var number by remember { mutableStateOf("+") }

    // Back is what anybody who picked the wrong route reaches for, and nothing has been sent yet.
    BackHandler(onBack = onBack)

    LoginForm(
        title = "Your phone number",
        blurb = "The number your Telegram account is registered to, with its country code.",
        note = null,
        value = number,
        onValueChange = { number = it },
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

@Composable
private fun CodePane(
    phoneNumber: String,
    error: String?,
    onSubmit: (String) -> Unit,
    onStartOver: () -> Unit,
) {
    var code by remember { mutableStateOf("") }

    BackHandler(onBack = onStartOver)

    LoginForm(
        title = "Enter your code",
        blurb = "Telegram has sent a code to $phoneNumber. It arrives in the Telegram app first, " +
            "and by text only if you are not signed in anywhere.",
        note = null,
        value = code,
        onValueChange = { code = it },
        placeholder = "Code",
        keyboardType = KeyboardType.Number,
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

@Composable
private fun QrPane(link: String) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = Tv.SafeH, vertical = Tv.SafeV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        // The code itself gets a white plate; contrast is what makes it scannable across a room.
        Box(
            Modifier
                .size(300.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = link) {
                value = withContext(Dispatchers.Default) { QrCode.render(link, QR_PIXELS) }
            }
            val rendered = bitmap
            if (rendered == null) {
                BigLoader(null)
            } else {
                Image(
                    bitmap = rendered.asImageBitmap(),
                    contentDescription = "Telegram login QR code",
                    modifier = Modifier.size(268.dp),
                )
            }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sign in to Telegram", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Scan this code with the phone that has your Telegram account.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(8.dp))
            Step(1, "Open Telegram on your phone")
            Step(2, "Go to Settings → Devices")
            Step(3, "Tap “Link Desktop Device” and point the camera here")
            Spacer(Modifier.height(8.dp))
            Text(
                "The code refreshes on its own. It is read by Telegram and nobody else.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).background(Accent),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.bodyLarge, color = Color.White)
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
 * The one typed pane, worn by the number, the code and the password in turn.
 *
 * All three ask a single question, take a single line, and offer the same way back out, so they
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

    // imePadding + scroll: the TV keyboard covers the lower half of the screen, and without
    // both of these the field being typed into sits behind it.
    Box(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(vertical = Tv.SafeV),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(640.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            Text(blurb, style = MaterialTheme.typography.bodyLarge)
            if (note != null) {
                Text(note, style = MaterialTheme.typography.bodyMedium)
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceDark)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.copy(color = TextPrimary),
                    cursorBrush = SolidColor(Accent),
                    visualTransformation = if (masked) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    // Placeholder and field must be stacked, not siblings: emitted flat they
                    // are laid out one after the other and the text lands off to the side.
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty()) {
                                Text(
                                    placeholder,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = TextMuted,
                                )
                            }
                            inner()
                        }
                    },
                )
            }

            if (error != null) {
                // Red, not the accent blue: the accent is the focus colour on this very screen,
                // so a rejection painted in it reads as a status line rather than as a problem.
                Text(error, style = MaterialTheme.typography.bodyLarge, color = Danger)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TmButton(
                    onClick = onSubmit,
                    enabled = canSubmit,
                ) {
                    Text(submitLabel)
                }
                TmSecondaryButton(onClick = onBack) {
                    Text(backLabel)
                }
            }
        }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
}

private const val QR_PIXELS = 560

// The shortest national numbers in use run to seven digits once the country code is counted, so
// anything below this cannot be a real number and the button stays out of reach.
private const val MIN_PHONE_DIGITS = 7

// Duplicated from StateScaffold.kt, which declares the same value privately. Both should move into
// the theme as a named error colour when someone next touches Theme.kt.
