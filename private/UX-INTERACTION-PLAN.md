# UX interaction plan: search, inputs, lists, filters, keyboard, D-pad, gestures

Started 2026-11-16 (user: "you've done a lot of ux mistakes ... all types like dpad touch, gestures,
keyboard shortcuts everywhere"). The visual pass is `docs/ux-audit.md` (U19); this one is about
behaviour: what a control does when pressed, cleared, left, repeated, or driven without a touch.

## Rules learned (sources in the last section)

Search and inputs
1. A search box has a visible clear control; clearing keeps focus in the box (phone, desktop).
2. Escape clears first, then closes (desktop). Back clears first, then closes (phone, TV remote).
3. The query is per visit: leaving the screen clears it, except while a page opened from a result is
   up (Back returns to the results). Main Search keeps its 2 minute rule.
4. IME action matches the field: Search for a search box, Next between fields, Done at the end; the
   keyboard type matches the content (url, number, password); no autocorrect on codes and names.
5. No results says what to try (remove a filter, check spelling) and offers the one-tap way back.
6. Recent searches (if shown) have a per-item remove and a Clear all; none shown for private chats.
7. Ctrl+F (or `/`) focuses the search box on desktop; never rebind Ctrl+C, V, Z, F, A.

Lists, selection, filters
8. Ctrl+A selects all, Shift+click a range, Ctrl+click toggles, Esc clears the selection; a count shows.
9. A selection that changes while work runs is stable; empty selection disables the action.
10. A filter or sort is visible as chips or a label, one tap to clear, "Clear all" when two or more.
11. After Back, scroll position and the focused row come back; a removed row hands focus to a neighbour.
12. Empty, loading, error and huge states exist for every list (3,000 chats, 100,000 videos).

Remote, keyboard, gestures
13. D-pad: every control reachable, focus always visible, no trap, Back goes one level up, no exit
    confirmation, focus returns to the control that opened a dialog or sheet.
14. Keyboard: Tab order follows reading order, no keyboard trap, Esc closes the topmost transient
    layer only (dialog, menu, sheet), Enter activates, Space toggles, arrows move in lists.
15. Touch: targets 48 dp, a gesture always has a visible button twin, predictive back works,
    swipe and long-press actions are discoverable, every press answers (haptic or pressed state).

## Skills

No install needed: `impeccable` and `web-design-guidelines` (already available) cover review and
polish. An unvetted third-party skill is a supply-chain risk for no gain. Workers may call them.

## Tasks (3 workers, own worktree each, `files:` do not overlap)

Each worker: audit its surface against the rules above on phone, TV, desktop and web (read the
code, then fix), write a failing machine test first where a behaviour is wrong (focus/D-pad test,
Compose UI test, render test), fix, commit locally section-wise, list what is left in its
Progress section. No phone or emulator pass (user rule).

### T1 Search boxes and text inputs  (model: sonnet, depends: none)
files: `app/src/main/java/com/tmplayer/ui/settings/SettingsScreen.kt`, `SettingsSearchBox.kt`,
`desktop/src/main/kotlin/com/tmplayer/desktop/ui/Settings.kt`, the search page and every
`TextField`/`OutlinedTextField` outside the files owned by T2 and T3, plus their tests.
Done when:
- Settings search is cleared on leaving Settings (phone and desktop), with a Compose test per
  platform: type, open a result page, Back keeps the query, leave Settings, return, box empty.
- Every search/input box obeys rules 1 to 7 (list the boxes found, fix or say why not).
- IME actions and keyboard types reviewed for every field (sign in phone/code/password, proxy,
  TMDB key, rename, filters).
- Edge cases: rotation keeps the query while Settings is up; TV has no box (no change).

### T2 Lists, selection, filters, sort, empty states  (model: sonnet, depends: none)
files: `app/src/main/java/com/tmplayer/ui/browse/**` (except search), `ui/downloads/**`,
`desktop/.../ui/GridSelection.kt`, `Downloads.kt`, the chat and library list screens, their tests.
Done when: rules 8 to 12 hold on phone, TV and desktop, with a D-pad test for focus restore after
Back and after removing a row, a Ctrl+A / Shift+click / Esc desktop test, a filter-clear test, and
an empty-state check for each list.

### T3 Keyboard shortcuts, remote and gestures in the player and shell  (model: sonnet, depends: none)
files: `app/src/main/java/com/tmplayer/player/**`, `desktop/.../player/**`, `desktop/.../ui/Shell.kt`,
`desktop/.../Main.kt`, dialog and sheet components under `ui/src/shared/**`, `MainActivity.kt`
(only the back handling, minimal edits), their tests.
Done when: rules 13 to 15 hold; a table of every shortcut and remote key per platform is written
to `docs/shortcuts.md` (it exists only if missing: read first), conflicts with the universal
shortcuts fixed, Esc closes one layer at a time, focus returns to the opener after a dialog/sheet,
predictive back and the back gesture checked, targets under 48 dp listed or fixed.

## Not in this pass
Low storage mode rename and the two-tab control (waiting for the user to pick A, B or C), store
text, the site.

## Progress / handoff
- 2026-11-16: plan written. Fresh-install default for the streaming mode is committed separately
  (`armSetup`). Workers T1, T2, T3 start from that commit.

## Decisions made by Claude
- Three workers, not more: the files are split by surface and `MainActivity.kt` is shared, so only
  T3 touches it and only for back handling.

## Sources
- Search patterns: https://carbondesignsystem.com/patterns/search-pattern ,
  https://developer.apple.com/design/human-interface-guidelines/search-fields.md ,
  https://design.visa.com/patterns/search
- Android TV navigation: https://developer.android.com/training/tv/start/navigation ,
  https://developer.android.com/design/ui/tv/guides/foundations/navigation-on-tv
- Desktop keyboard: https://developer.android.com/design/ui/desktop/guides/interaction/keyboard ,
  https://developer.gnome.org/hig/reference/keyboard.html ,
  https://w3.org/WAI/ARIA/apg/patterns/listbox
- IME actions: https://developer.android.com/training/keyboard-input/style
- Gaps (not found in the searches): official focus-restore guidance, inline validation and predictive
  back. Check m3.material.io and developer.android.com directly before relying on them.
