package com.tmplayer.ui.audit

import androidx.compose.runtime.remember
import com.tmplayer.data.AuthState
import com.tmplayer.data.CodeDelivery
import com.tmplayer.ui.auth.LoginScreen
import com.tmplayer.ui.browse.AddChatsViewModel
import com.tmplayer.ui.browse.AddChatsScreen
import com.tmplayer.ui.connect.ProxyScreen
import com.tmplayer.ui.onboarding.NotificationsStepScreen
import com.tmplayer.ui.onboarding.StepOf
import com.tmplayer.ui.onboarding.SupportStepScreen
import com.tmplayer.ui.onboarding.TourPage
import com.tmplayer.ui.onboarding.TourProgress
import com.tmplayer.ui.onboarding.TourScreen

/** First run: sign in, the notification and support steps, the tour, Add chats, the proxy page. */
internal fun auditScreens4(): List<AuditScreen> = buildList {
    fun login(name: String, state: AuthState, error: String? = null) =
        add(AuditScreen(name) { LoginScreen(state = state, onSubmitPassword = {}, submitError = error) })
    login("login-phone", AuthState.Phone())
    login("login-phone-wrong", AuthState.Phone(wrong = true), error = "That number is not valid.")
    login("login-code", AuthState.Code(phoneNumber = "+39 000 000 0000", delivery = CodeDelivery.Sms, length = 5, timeout = 45, canResend = true))
    login("login-code-wrong", AuthState.Code(phoneNumber = "+39 000 000 0000", wrong = true, delivery = CodeDelivery.TelegramApp, length = 5), error = "Wrong code")
    login("login-qr", AuthState.Qr("tg://login?token=AAAAAAAAAAAAAAAA"))
    login("login-password", AuthState.Password(hint = "your first pet", recoveryEmail = "a***@example.com"))

    add(AuditScreen("step-notifications") { NotificationsStepScreen(settingsRoute = false, onAllow = {}, onOpenSettings = {}, onSkip = {}, position = StepOf(1, 2)) })
    add(AuditScreen("step-notifications-settings") { NotificationsStepScreen(settingsRoute = true, onAllow = {}, onOpenSettings = {}, onSkip = {}, position = StepOf(1, 2)) })
    add(AuditScreen("step-support") { SupportStepScreen(onOpenPage = {}, onSkip = {}, position = StepOf(2, 2)) })
    for (page in listOf(TourPage.Videos, TourPage.Home, TourPage.Player, TourPage.Controls, TourPage.Downloads, TourPage.Settings)) {
        add(AuditScreen("tour-${page.name.lowercase()}") {
            val state = remember { TourProgress(listOf(TourPage.Videos, TourPage.Home, TourPage.Player, TourPage.Controls, TourPage.Downloads, TourPage.Settings), start = page.ordinal.coerceAtMost(5)) }
            TourScreen(state = state, onDone = {})
        })
    }

    add(AuditScreen("add-chats") {
        val model = remember { AddChatsViewModel(write = {}) }
        AddChatsScreen(chats = bigChatsForAudit, listComplete = true, starred = setOf(1L), model = model, onClose = {})
    })
    add(AuditScreen("proxy") { ProxyScreen(onClose = {}) })
}

internal val bigChatsForAudit = listOf(
    com.tmplayer.data.ChatSummary(1, "Harbour Lights fans", null, 0, com.tmplayer.data.ChatKind.Group, unreadCount = 12, isPinned = true),
    com.tmplayer.data.ChatSummary(2, "Weekend Club", null, 0, com.tmplayer.data.ChatKind.Channel),
    com.tmplayer.data.ChatSummary(3, "Harbour photos and a very long channel name that should not fit on one line", null, 0, com.tmplayer.data.ChatKind.Channel, isMuted = true),
    com.tmplayer.data.ChatSummary(4, "Asha Rao", null, 0, com.tmplayer.data.ChatKind.Direct),
    com.tmplayer.data.ChatSummary(5, "Saved Messages", null, 0, com.tmplayer.data.ChatKind.Saved),
    com.tmplayer.data.ChatSummary(6, "Mountain railway", null, 0, com.tmplayer.data.ChatKind.Group),
)
