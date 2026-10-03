package com.tmplayer.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tmplayer.data.AuthState
import com.tmplayer.data.CodeDelivery
import com.tmplayer.data.SignInMethod
import com.tmplayer.data.Td
import com.tmplayer.ui.auth.QrCode
import com.tmplayer.ui.components.AppLogo
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Signing in on the desktop (B2.1): the QR code first, since the phone in the viewer's hand is
 * already signed in, with "Log in by phone number" under it; then the code and the two-step
 * password. The states and their order are [com.tmplayer.data.AuthReducer]'s, shared with Android.
 */
@Composable
fun SignInScreen(auth: AuthState) {
    // No choice screen on a desktop: the QR code is the first answer, and the phone number is a
    // link under it.
    LaunchedEffect(auth) {
        if (auth == AuthState.ChooseMethod) Td.chooseSignInMethod(SignInMethod.Qr)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(AppLogo.Mark, contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
            when (auth) {
                AuthState.Connecting, AuthState.ChooseMethod, AuthState.Ready -> Waiting("Connecting to Telegram…")
                is AuthState.Qr -> QrPane(auth.link)
                is AuthState.Phone -> PhonePane(auth.wrong)
                is AuthState.Code -> CodePane(auth)
                is AuthState.Password -> PasswordPane(auth)
                is AuthState.Failed -> FailedPane(auth.message)
            }
        }
    }
}

@Composable
private fun Waiting(label: String) {
    CircularProgressIndicator()
    Text(label, color = Tone.muted)
}

@Composable
private fun Heading(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Text(body, color = Tone.muted, textAlign = TextAlign.Center)
}

@Composable
private fun QrPane(link: String) {
    val scope = rememberCoroutineScope()
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = link) {
        value = withContext(Dispatchers.Default) { QrCode.render(link, QR_PIXELS) }
    }
    Heading(
        "Sign in to TMPlayer",
        "On your phone, open Telegram, go to Settings, then Devices, then Link Desktop Device, and point it at this code.",
    )
    // Black on a white plate whatever the theme: contrast is what makes a code scannable.
    Box(
        Modifier
            .size(280.dp)
            .clip(RoundedCornerShape(Corner.ExtraLarge))
            .background(Color.White)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        val rendered = bitmap
        if (rendered == null) {
            CircularProgressIndicator()
        } else {
            Image(rendered, contentDescription = "Telegram login QR code", modifier = Modifier.fillMaxSize())
        }
    }
    TextButton(onClick = { scope.launch { Td.chooseSignInMethod(SignInMethod.Phone) } }) {
        Text("Log in by phone number")
    }
}

@Composable
private fun PhonePane(wrong: Boolean) {
    val scope = rememberCoroutineScope()
    var number by remember { mutableStateOf("+") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    // Telegram guesses the country from the address it sees, which saves typing the dial code.
    LaunchedEffect(Unit) {
        focus.requestFocus()
        val guess = runCatching { Td.guessedCountryCode() }.getOrDefault("")
        if (guess.isBlank() || number != "+") return@LaunchedEffect
        val dial = runCatching { Td.countries() }.getOrDefault(emptyList())
            .firstOrNull { it.countryCode == guess }?.callingCodes?.firstOrNull()
        if (dial != null && number == "+") number = "+$dial "
    }

    val submit = {
        if (!busy) {
            busy = true
            scope.launch {
                error = Td.submitPhoneNumber(number.filter { it.isDigit() || it == '+' })
                busy = false
            }
        }
    }
    Heading("Your phone number", "The number your Telegram account uses, with its country code.")
    OutlinedTextField(
        value = number,
        onValueChange = { number = it; error = null },
        label = { Text("Phone number") },
        singleLine = true,
        isError = error != null || wrong,
        supportingText = { error?.let { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { submit() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    Button(onClick = { submit() }, enabled = !busy && number.count { it.isDigit() } >= 5, modifier = Modifier.fillMaxWidth()) {
        Text(if (busy) "Sending…" else "Next")
    }
    TextButton(onClick = { scope.launch { Td.cancelPhoneEntry() } }) { Text("Use a QR code instead") }
}

@Composable
private fun CodePane(state: AuthState.Code) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val submit = {
        if (!busy && code.isNotBlank()) {
            busy = true
            scope.launch {
                error = Td.submitCode(code.trim())
                busy = false
            }
        }
    }
    Heading("Enter the code", whereTheCodeWent(state))
    OutlinedTextField(
        value = code,
        onValueChange = { code = it; error = null },
        label = { Text("Code") },
        singleLine = true,
        isError = error != null || state.wrong,
        supportingText = { error?.let { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    Button(onClick = { submit() }, enabled = !busy && code.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        Text(if (busy) "Checking…" else "Sign in")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.next != null) {
            TextButton(onClick = { scope.launch { error = Td.resendCode() } }) { Text("Send it another way") }
        }
        TextButton(onClick = { scope.launch { Td.restartSignIn() } }) { Text("Start over") }
    }
}

private fun whereTheCodeWent(state: AuthState.Code): String = when (state.delivery) {
    CodeDelivery.TelegramApp -> "Telegram sent a code to your account on another device. It arrives as a message from Telegram."
    CodeDelivery.Sms, CodeDelivery.SmsWord, CodeDelivery.SmsPhrase -> "Telegram sent a text to ${state.phoneNumber}."
    CodeDelivery.Call -> "Telegram is calling ${state.phoneNumber} to read the code out."
    CodeDelivery.MissedCall, CodeDelivery.FlashCall -> "Telegram is calling ${state.phoneNumber}. The code is in the calling number."
    CodeDelivery.Fragment -> "The code is waiting for you on Fragment."
    CodeDelivery.Firebase, CodeDelivery.Unknown -> "Telegram sent a code to ${state.phoneNumber}."
}

@Composable
private fun PasswordPane(state: AuthState.Password) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val submit = {
        if (!busy && password.isNotEmpty()) {
            busy = true
            scope.launch {
                error = Td.submitPassword(password)
                busy = false
            }
        }
    }
    Heading(
        "Your Telegram password",
        if (state.hint.isBlank()) "This account has two-step verification on." else "Hint: ${state.hint}",
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it; error = null },
        label = { Text("Password") },
        singleLine = true,
        isError = error != null || state.wrong,
        supportingText = { error?.let { Text(it) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    Button(onClick = { submit() }, enabled = !busy && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
        Text(if (busy) "Checking…" else "Sign in")
    }
    TextButton(onClick = { scope.launch { Td.restartSignIn() } }) { Text("Start over") }
}

@Composable
private fun FailedPane(message: String) {
    val scope = rememberCoroutineScope()
    Heading("Could not sign in", message)
    Button(onClick = { scope.launch { Td.restartSignIn() } }) { Text("Try again") }
}

private const val QR_PIXELS = 560
