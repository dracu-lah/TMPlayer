package com.tmplayer.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
        Modifier.fillMaxSize().padding(horizontal = 72.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(72.dp),
    ) {
        // The code itself gets a white plate — contrast is what makes it scannable across a room.
        Box(
            Modifier
                .size(420.dp)
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
                    modifier = Modifier.size(380.dp),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Sign in to Telegram", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Scan this code with the phone your Telegram account is on.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(8.dp))
            Step(1, "Open Telegram on your phone")
            Step(2, "Go to Settings → Devices")
            Step(3, "Tap “Link Desktop Device” and point the camera here")
            Spacer(Modifier.height(8.dp))
            Text(
                "The code refreshes on its own. Nothing is sent anywhere except Telegram.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(17.dp)).background(Accent),
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

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(720.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text("Two-step verification", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Your account is protected by a password. Telegram requires it even after a QR sign-in.",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (hint.isNotBlank()) {
                Text("Hint: $hint", style = MaterialTheme.typography.bodyMedium)
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceDark)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge,
                    cursorBrush = SolidColor(Accent),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSubmit(password) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    decorationBox = { inner ->
                        if (password.isEmpty()) {
                            Text(
                                "Password",
                                style = MaterialTheme.typography.titleLarge,
                                color = TextMuted,
                            )
                        }
                        inner()
                    },
                )
            }

            if (error != null) {
                Text(error, style = MaterialTheme.typography.bodyLarge, color = Accent)
            }

            Button(onClick = { onSubmit(password) }, enabled = password.isNotEmpty()) {
                Text("Sign in")
            }
        }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
}

private const val QR_PIXELS = 760
