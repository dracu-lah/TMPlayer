# Desktop account popover: library tiles with counts

Report (2026-11-25): in the profile popover, Favourites, History and Watch later look misaligned
when their count is shown below the name.

Cause: `LibraryTiles` (ui/shared, used by the desktop popover and the phone sheet) centred each
tile's icon, name and counts as a group, so the three icons stood at three heights (tops 46, 38 and
53 px at font 1.0) whenever the tiles carried different numbers of count lines. Fix: icon at a fixed
top, name under it, counts under the name; a count only grows the tile downward.

Pictures are the 320 dp card (4 dp inset) as the popover draws it, PNG, uncompressed. Names are
`x<font>-<counts>-<state>.png`, states rest, hover (History) and focus (Favourites).

- before/: old layout. Only the cases that ran before the invariant failed: x1.0 and x1.3 with
  12 / 3 / 1,234, and every count set at x2.0.
- after/: new layout, all count sets (12/3/1234, none, 1 starred, 1 watching, 7 watched, long
  counts) at x1.0, x1.3 and x2.0 (stacked rows), three states each, plus
  `real-popover-1280x800-long-name.png`, the whole popover with a 60 character name.

Compare: `before/x1.0-all-12-3-1234-rest.png` with `after/x1.0-all-12-3-1234-rest.png`.

Not changed: counts too long for a third of the card (2,147,483,647) still end in an ellipsis.
The window size does not change the popover (fixed 320 dp), so 800x600 is the same card.
