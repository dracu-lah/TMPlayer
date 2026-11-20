package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * HX87: the sync channel. A fake [ChannelProbe] stands in for TDLib, so every blocked case of the
 * plan is driven through the same rule the app uses: a private channel is used only while it is
 * provably the viewer's alone, and every other state means no write and Saved Messages.
 */
class SyncDestinationsTest {

    private val me = 42L
    private val saved = 42L
    private val channel = -1001L

    private fun facts(
        role: ChannelFacts.Role = ChannelFacts.Role.Owner,
        count: Int = 1,
        isChannel: Boolean = true,
        public: Boolean = false,
        restricted: Boolean = false,
        title: String = "TMPlayer sync",
        linked: Boolean = false,
        forum: Boolean = false,
    ) = ProbeAnswer.Facts(
        ChannelFacts(
            channel, title, isChannel, public, hasLinkedChat = linked, isForum = forum,
            role = role, restricted = restricted, memberCount = count,
        ),
    )

    private fun judged(answer: ProbeAnswer) = SyncChannelCheck.judge(answer)

    private fun blockOf(answer: ProbeAnswer) = (judged(answer) as SyncJudgement.Blocked).block

    // ---- the rule ------------------------------------------------------------------------------

    @Test
    fun onlyAnOwnedPrivateChannelWithExactlyOneMemberIsUsed() {
        assertTrue(judged(facts()) is SyncJudgement.Use)
    }

    @Test
    fun anUnknownMemberCountBlocksWriting() {
        // supergroupFullInfo.member_count is 0 when Telegram has not said: not "only you".
        assertEquals(SyncChannelBlock.CountUnknown, blockOf(facts(count = 0)))
        assertEquals(SyncChannelBlock.CountUnknown, blockOf(facts(count = -3)))
    }

    @Test
    fun twoOrMoreMembersBlockWriting() {
        assertEquals(SyncChannelBlock.MoreMembers(2), blockOf(facts(count = 2)))
        assertEquals(SyncChannelBlock.MoreMembers(900), blockOf(facts(count = 900)))
    }

    @Test
    fun theOwnerLeavingOrNotBeingTheOwnerBlocksWriting() {
        assertEquals(SyncChannelBlock.OwnerLeft, blockOf(facts(role = ChannelFacts.Role.OwnerLeft)))
        assertEquals(SyncChannelBlock.NotOwner, blockOf(facts(role = ChannelFacts.Role.Admin)))
        assertEquals(SyncChannelBlock.NotOwner, blockOf(facts(role = ChannelFacts.Role.Member)))
        assertEquals(SyncChannelBlock.Gone, blockOf(facts(role = ChannelFacts.Role.Outside)))
    }

    @Test
    fun aDeletedChannelBlocksWriting() {
        assertEquals(SyncChannelBlock.Gone, blockOf(ProbeAnswer.Gone))
    }

    @Test
    fun aChannelMadePublicBlocksWriting() {
        assertEquals(SyncChannelBlock.Public, blockOf(facts(public = true)))
    }

    @Test
    fun aLinkedDiscussionGroupBlocksEvenWithOneMember() {
        // The group's members read the channel's posts, so "1 member" is not "only you".
        assertEquals(SyncChannelBlock.LinkedGroup, blockOf(facts(count = 1, linked = true)))
        assertTrue(SyncChannelBlock.LinkedGroup.words().isNotBlank())
        assertEquals(SyncChannelBlock.LinkedGroup, SyncChannelBlock.decode(SyncChannelBlock.LinkedGroup.encode()))
    }

    @Test
    fun aForumOrBroadcastGroupIsNeverUsed() {
        assertEquals(SyncChannelBlock.NotAChannel, blockOf(facts(forum = true)))
    }

    @Test
    fun aMemberJoinedByInviteLinkOrRequestCountsAsAMember() {
        // An invite link or a join request adds nobody until they are in; once in, the count says 2.
        assertTrue(judged(facts(count = 1)) is SyncJudgement.Use)
        assertEquals(SyncChannelBlock.MoreMembers(2), blockOf(facts(count = 2)))
    }

    @Test
    fun withoutTheFullInfoNothingIsKnownAndNothingIsWritten() {
        val failed = ChannelFactsAssembler.assemble(
            channel, "T", true, false, false, ChannelFacts.Role.Owner, false,
            groupMemberCount = 1, groupHasLinkedChat = false, full = null, failure = "no line",
        )
        assertEquals(ProbeAnswer.Unreachable("no line"), failed)
        assertEquals(SyncJudgement.Retry("no line"), judged(failed))
    }

    @Test
    fun theFullInfoAddsALinkedGroupAndTheLargerCountWins() {
        fun assembled(groupCount: Int, groupLinked: Boolean, full: FullInfoFacts) = ChannelFactsAssembler.assemble(
            channel, "T", true, false, false, ChannelFacts.Role.Owner, false, groupCount, groupLinked, full, "x",
        )
        // The supergroup says 1 and no link; the full info says a group is linked.
        assertEquals(SyncChannelBlock.LinkedGroup, blockOf(assembled(1, false, FullInfoFacts(1, linkedChatId = -1002L))))
        // The supergroup says it is linked; the full info does not.
        assertEquals(SyncChannelBlock.LinkedGroup, blockOf(assembled(1, true, FullInfoFacts(1, 0L))))
        // Either count over 1 blocks, and either 0 with the other 1 is still 1.
        assertEquals(SyncChannelBlock.MoreMembers(2), blockOf(assembled(1, false, FullInfoFacts(2, 0L))))
        assertEquals(SyncChannelBlock.MoreMembers(2), blockOf(assembled(2, false, FullInfoFacts(1, 0L))))
        assertTrue(judged(assembled(0, false, FullInfoFacts(1, 0L))) is SyncJudgement.Use)
        assertEquals(SyncChannelBlock.CountUnknown, blockOf(assembled(0, false, FullInfoFacts(0, 0L))))
    }

    @Test
    fun aGroupIsNeverUsed() {
        assertEquals(SyncChannelBlock.NotAChannel, blockOf(facts(isChannel = false)))
    }

    @Test
    fun aRestrictedChannelBlocksPosting() {
        assertTrue(blockOf(facts(restricted = true)) is SyncChannelBlock.PostingBlocked)
    }

    @Test
    fun noAnswerDecidesNothing() {
        assertEquals(SyncJudgement.Retry("offline"), judged(ProbeAnswer.Unreachable("offline")))
    }

    @Test
    fun publicWinsOverTheCountSoTheReasonIsTheMostSerious() {
        // A public channel with many members: say it is public, the thing to fix first.
        assertEquals(SyncChannelBlock.Public, blockOf(facts(public = true, count = 50)))
    }

    @Test
    fun telegramsRefusalToPostIsToldFromAnOutage() {
        assertTrue(SyncChannelCheck.refusedPosting("CHAT_WRITE_FORBIDDEN"))
        assertTrue(SyncChannelCheck.refusedPosting("Have no rights to send a message"))
        assertTrue(SyncChannelCheck.refusedPosting("USER_BANNED_IN_CHANNEL"))
        assertFalse(SyncChannelCheck.refusedPosting("FLOOD_WAIT_30"))
        assertFalse(SyncChannelCheck.refusedPosting("Timeout"))
        assertFalse(SyncChannelCheck.refusedPosting(null))
    }

    @Test
    fun everyBlockHasWordsAndSurvivesTheStore() {
        val all = listOf(
            SyncChannelBlock.CountUnknown, SyncChannelBlock.MoreMembers(3), SyncChannelBlock.Gone,
            SyncChannelBlock.NotAChannel, SyncChannelBlock.Public, SyncChannelBlock.NotOwner,
            SyncChannelBlock.OwnerLeft, SyncChannelBlock.PostingBlocked(1234L),
        )
        for (block in all) {
            assertEquals(block, SyncChannelBlock.decode(block.encode()))
            assertTrue(block.words().isNotBlank())
            // Each reason says where the file is meanwhile, and none shows a code.
            assertFalse(block.words().contains("_"))
        }
        assertNull(SyncChannelBlock.decode(null))
        assertNull(SyncChannelBlock.decode(""))
        assertNull(SyncChannelBlock.decode("nonsense"))
        assertNull(SyncChannelBlock.decode("members:abc"))
    }

    // ---- resolving against the viewer's choice ---------------------------------------------------

    private class Fixture {
        val dir = Files.createTempDirectory("tm-sync-dest").toFile()
        val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))
    }

    private fun probe(answer: () -> ProbeAnswer) = ChannelProbe { answer() }

    private fun Fixture.choose() = runBlocking { settings.setSyncChannel(SyncChannelChoice(channel, "Old name", me)) }

    private fun Fixture.resolve(answer: ProbeAnswer, now: Long = 1_000L, account: Long = me): SyncPlace =
        runBlocking { SyncDestinations.resolve(settings, account, saved, probe { answer }, now) }

    @Test
    fun savedMessagesIsTheDefault() {
        val f = Fixture()
        assertEquals(SyncPlace.SavedMessages(saved), f.resolve(facts()))
        assertNull(runBlocking { f.settings.syncChannelNow() })
    }

    @Test
    fun aSafeChannelIsUsedAndItsTitleIsKeptCurrent() {
        val f = Fixture().also { it.choose() }
        assertEquals(SyncPlace.Channel(channel, "TMPlayer sync"), f.resolve(facts()))
        assertEquals("TMPlayer sync", runBlocking { f.settings.syncChannelNow() }?.title)
    }

    @Test
    fun aSecondMemberJoiningLaterStopsWritingAndLeavingBringsItBack() {
        val f = Fixture().also { it.choose() }
        assertTrue(f.resolve(facts()) is SyncPlace.Channel)
        // Somebody joins by the invite link.
        val blocked = f.resolve(facts(count = 2))
        assertEquals(SyncPlace.SavedMessages(saved, SyncChannelBlock.MoreMembers(2)), blocked)
        assertEquals(SyncChannelBlock.MoreMembers(2), runBlocking { f.settings.syncChannelBlock.first() })
        // The choice is kept, so the channel comes back once it is the viewer's alone again.
        assertEquals(channel, runBlocking { f.settings.syncChannelNow() }?.chatId)
        assertTrue(f.resolve(facts(count = 1)) is SyncPlace.Channel)
        assertNull(runBlocking { f.settings.syncChannelBlock.first() })
    }

    @Test
    fun everyBlockedStateFallsBackToSavedMessagesWithItsReason() {
        val cases = mapOf(
            facts(count = 0) to SyncChannelBlock.CountUnknown,
            facts(role = ChannelFacts.Role.OwnerLeft) to SyncChannelBlock.OwnerLeft,
            facts(role = ChannelFacts.Role.Admin) to SyncChannelBlock.NotOwner,
            facts(public = true) to SyncChannelBlock.Public,
            facts(isChannel = false) to SyncChannelBlock.NotAChannel,
            ProbeAnswer.Gone to SyncChannelBlock.Gone,
        )
        for ((answer, block) in cases) {
            val f = Fixture().also { it.choose() }
            val place = f.resolve(answer)
            assertEquals(SyncPlace.SavedMessages(saved, block), place)
            assertEquals(block, runBlocking { f.settings.syncChannelBlock.first() })
            assertTrue(place.chatId == saved)
        }
    }

    @Test
    fun aChannelThatCannotBeCheckedWritesNothingAndChangesNothing() {
        val f = Fixture().also { it.choose() }
        runBlocking { f.settings.setSyncChannelBlock(SyncChannelBlock.Public) }
        val place = f.resolve(ProbeAnswer.Unreachable("no line"))
        assertEquals(SyncPlace.Unreachable("no line"), place)
        assertEquals(channel, runBlocking { f.settings.syncChannelNow() }?.chatId)
        assertEquals(SyncChannelBlock.Public, runBlocking { f.settings.syncChannelBlock.first() })
    }

    @Test
    fun anotherAccountNeverUsesTheChannelOfTheFirst() {
        val f = Fixture().also { it.choose() }
        val place = f.resolve(facts(), account = 99L)
        assertEquals(SyncPlace.SavedMessages(saved), place)
        assertNull(runBlocking { f.settings.syncChannelNow() })
    }

    @Test
    fun signingOutForgetsTheChannel() {
        val f = Fixture().also { it.choose() }
        runBlocking { f.settings.clearEverything() }
        assertNull(runBlocking { f.settings.syncChannelNow() })
        assertEquals(SyncPlace.SavedMessages(saved), f.resolve(facts()))
    }

    @Test
    fun aRefusedPostFallsBackForAnHourThenTriesTheChannelAgain() {
        val f = Fixture().also { it.choose() }
        runBlocking { SyncDestinations.notePostingRefused(f.settings, now = 10_000L) }
        val soon = f.resolve(facts(), now = 10_000L + 60_000L)
        assertTrue(soon is SyncPlace.SavedMessages && soon.why is SyncChannelBlock.PostingBlocked)
        val later = f.resolve(facts(), now = 10_000L + SyncDestinations.POSTING_RETRY_MS + 1)
        assertTrue(later is SyncPlace.Channel)
        assertNull(runBlocking { f.settings.syncChannelBlock.first() })
    }

    @Test
    fun theBackupGoesToSavedMessagesUnlessTheChannelIsSafe() = runBlocking {
        val f = Fixture().also { it.choose() }
        assertEquals(channel, SyncDestinations.chatForBackup(f.settings, me, saved, probe { facts() }))
        assertEquals(saved, SyncDestinations.chatForBackup(f.settings, me, saved, probe { facts(count = 2) }))
        // Not checkable: the viewer must still be able to sign out with a backup in Saved Messages.
        assertEquals(saved, SyncDestinations.chatForBackup(f.settings, me, saved, probe { ProbeAnswer.Unreachable("x") }))
        assertEquals(saved, SyncDestinations.chatForBackup(null, me, saved, probe { facts() }))
    }

    @Test
    fun theCheckRunsAgainRightBeforeEveryWrite() = runBlocking {
        val f = Fixture().also { it.choose() }
        var answer: ProbeAnswer = facts()
        val live = probe { answer }
        // Safe at the start of the sync...
        assertTrue(SyncDestinations.resolve(f.settings, me, saved, live, 1_000L) is SyncPlace.Channel)
        assertNull(SyncDestinations.verifyBeforeWrite(f.settings, live, channel))
        // ...somebody joins while the sync merges: the write is refused with the reason, and kept.
        answer = facts(count = 2)
        assertEquals(SyncChannelBlock.MoreMembers(2).words(), SyncDestinations.verifyBeforeWrite(f.settings, live, channel))
        assertEquals(SyncChannelBlock.MoreMembers(2), f.settings.syncChannelBlock.first())
        // A group linked in between, a name made public, the owner changed, no answer: each stops the write.
        for (unsafe in listOf(facts(linked = true), facts(public = true), facts(role = ChannelFacts.Role.Admin), ProbeAnswer.Gone)) {
            answer = unsafe
            assertTrue(SyncDestinations.verifyBeforeWrite(f.settings, live, channel) != null)
        }
        answer = ProbeAnswer.Unreachable("no line")
        assertEquals("no line", SyncDestinations.verifyBeforeWrite(f.settings, live, channel))
    }

    // ---- picking and moving ----------------------------------------------------------------------

    @Test
    fun aPickIsCheckedAndARefusedPickChangesNothing() = runBlocking {
        val f = Fixture()
        val refused = SyncDestinations.choose(f.settings, probe { facts(count = 2) }, me, channel)
        assertEquals(ChooseResult.Refused(SyncChannelBlock.MoreMembers(2)), refused)
        val failed = SyncDestinations.choose(f.settings, probe { ProbeAnswer.Unreachable("offline") }, me, channel)
        assertEquals(ChooseResult.Failed("offline"), failed)
        val picked = SyncDestinations.choose(f.settings, probe { facts() }, me, channel)
        assertEquals(ChooseResult.Picked("TMPlayer sync", created = false), picked)
        assertEquals(SyncChannelChoice(channel, "TMPlayer sync", me), f.settings.syncChannelNow())
    }

    @Test
    fun movingTheDestinationLeavesTheOldCopyAndSaysWhere() = runBlocking {
        val f = Fixture()
        f.settings.setResumeSyncFile(777L)
        f.settings.setSyncFileChat(saved)
        val told = ResumeSync.changeDestination(f.settings, SyncChannelChoice(channel, "TMPlayer sync", me))
        assertEquals("Saved Messages", told)
        assertEquals(0L, f.settings.resumeSyncFileNow())
        assertEquals("Saved Messages", f.settings.syncOldCopy.first())
        // The new place has no file yet; nothing about the old one is deleted by this call.
        assertEquals(channel, f.settings.syncChannelNow()?.chatId)
    }

    @Test
    fun movingBackAndForthKeepsTheNoteHonest() = runBlocking {
        val f = Fixture()
        val choice = SyncChannelChoice(channel, "TMPlayer sync", me)
        // Saved Messages had a file; move to the channel, which gets none before the viewer moves back.
        f.settings.setResumeSyncFile(5L)
        ResumeSync.changeDestination(f.settings, choice)
        assertEquals("Saved Messages", f.settings.syncOldCopy.first())
        assertNull(ResumeSync.changeDestination(f.settings, null))
        // Back where the older copy was: the note about it is stale now and goes.
        assertEquals("", f.settings.syncOldCopy.first())
        // Move to the channel again, this time with a file written in Saved Messages meanwhile.
        f.settings.setResumeSyncFile(6L)
        assertEquals("Saved Messages", ResumeSync.changeDestination(f.settings, choice))
        // The channel wrote a file; moving back names the channel as the older copy.
        f.settings.setResumeSyncFile(9L)
        assertEquals("TMPlayer sync", ResumeSync.changeDestination(f.settings, null))
        assertEquals("TMPlayer sync", f.settings.syncOldCopy.first())
    }

    @Test
    fun choosingTheSamePlaceAgainDoesNothing() = runBlocking {
        val f = Fixture()
        f.settings.setResumeSyncFile(5L)
        assertNull(ResumeSync.changeDestination(f.settings, null))
        assertEquals(5L, f.settings.resumeSyncFileNow())
    }

    @Test
    fun theLimitOnOwnedChannelsIsSaidInWords() {
        assertEquals(com.tmplayer.i18n.L.syncchannelTooMany, SyncDestinations.createFailure("CHANNELS_TOO_MUCH"))
        assertEquals(com.tmplayer.i18n.L.syncchannelTooMany, SyncDestinations.createFailure("400: CHANNELS_ADMIN_PUBLIC_TOO_MUCH"))
        assertTrue(SyncDestinations.createFailure("FLOOD_WAIT_30").isNotBlank())
    }

    // ---- two writers --------------------------------------------------------------------------------

    private class Device(name: String) {
        val dir = Files.createTempDirectory("tm-sync-ch-$name").toFile()
        val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))
        val watched = WatchedStore(WatchedStore.openDataStore(dir.resolve(WatchedStore.FILE_NAME)))
    }

    private fun mark(message: Long, at: Long) = WatchedRecord(
        chatId = -100L, messageId = message, fileId = 40 + message.toInt(), title = "Video $message", chatTitle = "Films",
        sizeBytes = 1_000, durationSec = 600, watchedAt = at, manual = false,
    )

    private fun key(message: Long) = ProgressKeys.of(-100L, message)

    /** [ResumeSync.run]'s merge for one device against whatever file the chosen chat holds ([chats]). */
    private fun sync(device: Device, chats: MutableMap<Long, ByteArray>, chat: Long, now: Long) = runBlocking {
        val remote = chats[chat]?.let { (SyncDocuments.decode(it, 1L) as SyncRead.Ok).doc } ?: SyncDocument(account = 1L)
        val base = device.settings.resumeSyncBaseNow()
        val local = SyncDocuments.collect(device.settings.rawEntries(), device.watched.rawEntries(), base, remote.favourites, 1L, now, "test")
        val (merged, _) = SyncDocuments.mergeCounting(remote, local, now)
        val apply = SyncDocuments.apply(local, merged)
        device.watched.applySync(apply)
        device.settings.setResumeSyncBase(
            SyncBase(
                local.resume.mapTo(HashSet()) { ProgressKeys.of(it.chatId, it.messageId) },
                local.favourites.keys - apply.favouritesOut + apply.favouritesIn,
                now,
            ),
        )
        chats[chat] = SyncDocuments.encode(merged.copy(updatedAt = now)).bytes
    }

    private fun marks(device: Device) = runBlocking { device.watched.watched.first().keys }

    @Test
    fun aSecondDevicePickingTheSameChannelReadsFirstThenMergesAndLosesNothing() {
        val phone = Device("phone")
        val tv = Device("tv")
        val chats = HashMap<Long, ByteArray>()
        runBlocking { phone.watched.markWatched(mark(1, 1_000)) }
        // Both on Saved Messages: the phone writes its file there.
        sync(phone, chats, saved, 2_000)
        // The phone moves to the channel: its file is written there, the old one stays.
        sync(phone, chats, channel, 3_000)
        assertTrue(saved in chats && channel in chats)
        // The TV marks a video, still on Saved Messages, then picks the same channel.
        runBlocking { tv.watched.markWatched(mark(2, 3_500)) }
        sync(tv, chats, saved, 4_000)
        sync(tv, chats, channel, 5_000)
        sync(phone, chats, channel, 6_000)
        assertEquals(setOf(key(1), key(2)), marks(tv))
        assertEquals(setOf(key(1), key(2)), marks(phone))
    }

    @Test
    fun aDeviceOfflineForDaysMergesInsteadOfOverwriting() {
        val phone = Device("phone2")
        val tv = Device("tv2")
        val chats = HashMap<Long, ByteArray>()
        val day = 86_400_000L
        runBlocking { phone.watched.markWatched(mark(1, 1_000)); tv.watched.markWatched(mark(1, 1_000)) }
        sync(phone, chats, channel, 2_000)
        sync(tv, chats, channel, 3_000)
        // Days pass: the TV is offline and marks one more; the phone marks another and syncs twice.
        runBlocking { phone.watched.markWatched(mark(2, 2 * day)) }
        sync(phone, chats, channel, 2 * day + 1)
        runBlocking { phone.watched.markWatched(mark(3, 3 * day)) }
        sync(phone, chats, channel, 3 * day + 1)
        runBlocking { tv.watched.markWatched(mark(4, 4 * day)) }
        sync(tv, chats, channel, 5 * day)
        sync(phone, chats, channel, 5 * day + 1)
        val all = setOf(key(1), key(2), key(3), key(4))
        assertEquals(all, marks(tv))
        assertEquals(all, marks(phone))
    }

    // ---- review fixes (HX87) ---------------------------------------------------------------------

    private fun outcome(sent: Boolean, raw: String? = null) =
        SavedMessagesBackup.PostOutcome(if (sent) BackupSend.Sent else BackupSend.Failed("no"), raw)

    @Test
    fun aRefusedBackupPostGoesOnceToSavedMessagesAndNowhereElse() = runBlocking {
        val f = Fixture().also { it.choose() }
        val tried = mutableListOf<Long>()
        val result = SavedMessagesBackup.postWithFallback(channel, saved, f.settings) { to ->
            tried += to
            if (to == channel) outcome(false, "CHAT_WRITE_FORBIDDEN") else outcome(true)
        }
        assertEquals(BackupSend.Sent, result)
        assertEquals(listOf(channel, saved), tried)
        assertTrue(f.settings.syncChannelBlock.first() is SyncChannelBlock.PostingBlocked)
    }

    @Test
    fun aRefusedBackupResendIsNotRepeatedWhenSavedMessagesFailsToo() = runBlocking {
        val f = Fixture().also { it.choose() }
        val tried = mutableListOf<Long>()
        val result = SavedMessagesBackup.postWithFallback(channel, saved, f.settings) { to ->
            tried += to
            outcome(false, "CHAT_WRITE_FORBIDDEN")
        }
        assertTrue(result is BackupSend.Failed)
        assertEquals(listOf(channel, saved), tried)
    }

    @Test
    fun otherBackupFailuresAndSavedMessagesItselfAreNotResent() = runBlocking {
        val f = Fixture().also { it.choose() }
        var calls = 0
        // An outage is not a refusal: no second post, no block kept.
        SavedMessagesBackup.postWithFallback(channel, saved, f.settings) { calls++; outcome(false, "Timeout") }
        assertEquals(1, calls)
        assertNull(f.settings.syncChannelBlock.first())
        // Posting to Saved Messages in the first place never falls back.
        calls = 0
        SavedMessagesBackup.postWithFallback(saved, saved, f.settings) { calls++; outcome(false, "CHAT_WRITE_FORBIDDEN") }
        assertEquals(1, calls)
    }

    @Test
    fun turningTheSyncOffForgetsTheFileChatTheSentChatAndTheBlock() = runBlocking {
        val f = Fixture().also { it.choose() }
        f.settings.setResumeSync(true)
        f.settings.setSyncFileChat(channel)
        f.settings.setSyncSentChat(channel)
        f.settings.setSyncChannelBlock(SyncChannelBlock.PostingBlocked(5L))
        f.settings.setResumeSync(false)
        assertEquals(0L, f.settings.syncFileChatNow())
        assertEquals(0L, f.settings.syncSentChatNow())
        assertNull(f.settings.syncChannelBlock.first())
    }

    @Test
    fun theBackupReadsTheChannelAnewRightBeforeThePost() = runBlocking {
        val f = Fixture().also { it.choose() }
        var answer: ProbeAnswer = facts()
        var reads = 0
        val live = probe { reads++; answer }
        assertEquals(channel, SyncDestinations.chatForBackup(f.settings, me, saved, live))
        val afterResolve = reads
        assertEquals(channel, SyncDestinations.backupChatBeforeWrite(f.settings, live, channel, saved))
        assertEquals(afterResolve + 1, reads)
        // Somebody joins while the file is made: Saved Messages takes the backup.
        answer = facts(count = 2)
        assertEquals(saved, SyncDestinations.backupChatBeforeWrite(f.settings, live, channel, saved))
        // Saved Messages needs no check.
        reads = 0
        assertEquals(saved, SyncDestinations.backupChatBeforeWrite(f.settings, live, saved, saved))
        assertEquals(0, reads)
    }
}
