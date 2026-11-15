# Strings no code uses, kept in en.json only so CatalogTest stays green

CatalogTest fails when a locale holds a key en.json does not. The 21 locale files are the user's
(langsync), so removing a key from en.json alone breaks the build. Delete these keys from en.json AND
all locales together when the user next syncs, then drop the line here.

- main.back_again (T3 removed the exit confirmation, 2026-11-16)
- picker.count_about, picker.count_again, picker.count_many, picker.count_unknown_keys,
  picker.count_unknown_touch, picker.total_counting, picker.total_videos (T5, no video counts)
- picker.below_bar (old text with {minimum}; the new text is picker.below_bar_many)
- login.press_back_again (T1 removed ExitOnBack)
- support.note_touch, support.note_tv, support.popup_note, support.star_note, support.upi_note (D1)
- New text to translate: picker.reason_many, picker.reason_some, picker.below_bar_many, and D1's support card strings.
