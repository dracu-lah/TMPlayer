package com.tmplayer.ui.auth

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.AuthState
import com.tmplayer.ui.components.BigError
import com.tmplayer.ui.components.BigLoader
import com.tmplayer.ui.theme.Accent
import com.tmplayer.ui.theme.SurfaceDark
import com.tmplayer.ui.theme.TextMuted
import com.tmplayer.ui.theme.TextPrimary
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.tmButtonColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LoginScreen(
    state: AuthState,
    onSubmitPassword: (String) -> Unit,
    passwordError: String?,
) {
    when (state) {
        is AuthState.Connecting -> BigLoader("Connecting to Telegram…")
        is AuthState.Qr -> QrPane(state.link)
        is AuthState.Password -> PasswordPane(state.hint, passwordError, onSubmitPassword)
        is AuthState.Ready -> BigLoader("Signing in…")
        is AuthState.Failed -> BigError(state.message, onRetry = null)
    }
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
                "The code refreshes on its own. TMPlayer only ever connects to Telegram.",
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
private fun PasswordPane(hint: String, error: String?, onSubmit: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
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
            Text("Two-step verification", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Your Telegram account has a password. Enter it to finish signing in.",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (hint.isNotBlank()) {
                Text("Hint: $hint", style = MaterialTheme.typography.bodyMedium)
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
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.copy(color = TextPrimary),
                    cursorBrush = SolidColor(Accent),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSubmit(password) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    // Placeholder and field must be stacked, not siblings: emitted flat they
                    // are laid out one after the other and the text lands off to the side.
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (password.isEmpty()) {
                                Text(
                                    "Password",
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
                // so a wrong password painted in it reads as a status line rather than a problem.
                Text(error, style = MaterialTheme.typography.bodyLarge, color = Danger)
            }

            Button(
                onClick = { onSubmit(password) },
                colors = tmButtonColors(),
                enabled = password.isNotEmpty(),
            ) {
                Text("Sign in")
            }
        }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
}

private const val QR_PIXELS = 560

// Duplicated from StateScaffold.kt, which declares the same value privately. Both should move into
// the theme as a named error colour when someone next touches Theme.kt.
private val Danger = Color(0xFFE5484D)
