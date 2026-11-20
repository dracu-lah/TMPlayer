package com.tmplayer.ui.browse

import com.tmplayer.data.AddedChoice
import com.tmplayer.data.AddedChats
import com.tmplayer.i18n.Strings
import com.tmplayer.ui.components.MenuAction
import com.tmplayer.ui.icons.TmIcons

/**
 * The chat menu's last entry on the phone, the tablet and the television (HX86).
 *
 * "Remove from TMPlayer" unticks the chat in the viewer's own picks. While the "All chats" switch is
 * on there is no pick to untick, and the viewer chose strictly every chat or their own picks, so a
 * chat is never carved out of "all": the entry says so and offers the switch ("Turn off All chats")
 * in Remove's place. Turning it off brings the kept picks back, and Remove returns with them.
 */
fun chatRemoveAction(
    s: Strings,
    choice: AddedChoice,
    onRemove: () -> Unit,
    onTurnOffAllChats: () -> Unit,
): MenuAction =
    if (AddedChats.removable(choice)) {
        MenuAction(
            label = s.browseRemoveChat,
            icon = TmIcons.VisibilityOff,
            detail = s.browseRemoveChatDetailChosen,
            onSelect = onRemove,
        )
    } else {
        MenuAction(
            label = s.browseTurnOffAllChats,
            icon = TmIcons.VisibilityOff,
            detail = s.browseTurnOffAllChatsDetail,
            onSelect = onTurnOffAllChats,
        )
    }
