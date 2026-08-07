package com.tmplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import com.tmplayer.data.AuthState
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.Td
import com.tmplayer.player.PlayerActivity
import com.tmplayer.ui.auth.LoginScreen
import com.tmplayer.ui.browse.ChatListScreen
import com.tmplayer.ui.browse.MediaGridScreen
import com.tmplayer.ui.settings.SettingsScreen
import com.tmplayer.ui.theme.TMPlayerTheme
import kotlinx.coroutines.launch

/** Where the user is. Deliberately three screens deep and no more. */
private sealed interface Screen {
    data object Chats : Screen
    data class Media(val chat: ChatSummary) : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Td.start(this)
        setContent {
            TMPlayerTheme { Root() }
        }
    }
}

@Composable
private fun Root() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth by Td.auth.collectAsStateWithLifecycle()

    var screen by remember { mutableStateOf<Screen>(Screen.Chats) }
    var passwordError by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (auth !is AuthState.Ready) {
            // Signing out drops straight back to the QR code, so forget where we were.
            LaunchedEffect(Unit) { screen = Screen.Chats }
            LoginScreen(
                state = auth,
                passwordError = passwordError,
                onSubmitPassword = { password ->
                    passwordError = null
                    scope.launch { passwordError = Td.submitPassword(password) }
                },
            )
            return@Box
        }

        when (val current = screen) {
            is Screen.Chats -> ChatListScreen(
                onOpenChat = { screen = Screen.Media(it) },
                onOpenSettings = { screen = Screen.Settings },
            )

            is Screen.Media -> {
                BackHandler { screen = Screen.Chats }
                MediaGridScreen(
                    chatId = current.chat.id,
                    chatTitle = current.chat.title,
                    onPlay = { context.startActivity(PlayerActivity.intent(context, it)) },
                )
            }

            is Screen.Settings -> {
                BackHandler { screen = Screen.Chats }
                SettingsScreen(onLoggedOut = { screen = Screen.Chats })
            }
        }
    }
}
