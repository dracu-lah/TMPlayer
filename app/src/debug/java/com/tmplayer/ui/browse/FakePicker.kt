package com.tmplayer.ui.browse

import com.tmplayer.data.ChatCountCalls
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Debug builds only (HX76): the picker over [SyntheticAccount]'s 3,000 chats and 1,500 archived,
 * with its video counts and their awkward chats, in real time. Reached by name from
 * [DebugFakeChats], since the release build has no such class. Turn on with
 * `adb shell am start -n com.tmplayer/.MainActivity --ez fakechats true`.
 */
object FakePicker {
    private val start = System.currentTimeMillis()
    private val account by lazy {
        SyntheticAccount(CoroutineScope(SupervisorJob() + Dispatchers.Default), { System.currentTimeMillis() - start })
    }

    /** Main list, then the archive; every third chat that is not a channel is a person so the People entry has rows. */
    @JvmStatic
    fun chats(): List<ChatSummary> =
        account.mainList().map { if (it.kind == ChatKind.Group && it.id % 3 == 0L) it.copy(kind = ChatKind.Direct) else it } + account.archivedList()

    @JvmStatic
    fun counts(): ChatCountCalls = account.chatCounts
}
