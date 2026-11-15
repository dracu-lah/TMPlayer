# Lists: one selection, one focus handoff

Problem Raju hits: on the desktop, a chat's grid could only be picked from with the mouse (no
Ctrl+A, no key to add one), Downloads had its own pick set (Ctrl+A only once something was
picked, no Shift+click range, no Ctrl+click), and a deleted row dropped the keyboard to the window.
Three copies of "what is picked", one of "where focus goes after a delete" (TV Downloads only).

New flow (shared parts, old copies deleted):

| Part | Where | Replaces |
| --- | --- | --- |
| `GridSelection` (ids, anchor, range, pick, remove, keepOnly) | desktop `GridSelection.kt` | Downloads' `picked` set and anchor |
| `isSelectAllChord`, `isToggleChord`, `selectAllKey` | desktop `GridSelection.kt` | per page Ctrl+A handlers |
| `rowAfterRemoving` (neighbour after, else before) | `ui/src/shared/.../RowHandoff.kt` | the copy in the TV Downloads screen |
| `RowFocusHandoff` / `handoffRow` | desktop `RowFocusHandoff.kt` | nothing before: focus fell to the window |

| Platform | Behaviour |
| --- | --- |
| Phone | Unchanged: tap ticks in select mode, Back leaves it. |
| TV | Unchanged keys; `rowAfterRemoving` now one shared function, tested in `ui`. |
| Desktop | Ctrl+A picks all (from nothing too), Ctrl+click or Ctrl+Space toggles, Shift+click a range, Enter or Space picks while a selection is up, Esc clears, a count shows; a deleted row hands focus to the next row, else the one above. |
| Web | Not in this pass (its own grid; listed in the plan's Progress). |

Edge cases: empty list (Ctrl+A is left to the focus), a video the chat will not let anyone keep
(left out of Ctrl+A), rows deleted while picked (`keepOnly`, `remove`), the last row deleted
(the one above), the only row deleted (focus stays on the page), 100,000 rows (selection keeps ids
only, `indexOf` on click is the only linear step).
