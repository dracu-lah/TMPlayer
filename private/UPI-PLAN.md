# Taking UPI (GPay) as well as Buy Me a Coffee

Asked for by the user on 2026-11-01: Buy Me a Coffee has no UPI or GPay, and most of TMPlayer's
users are in India, so the people most likely to chip in cannot. The UPI ID goes everywhere the
coffee button already is, with the same wording: voluntary, nothing unlocked, any amount counts.

**The details.** UPI ID `nevilnicks4321@okaxis` (Nevil Krishna, Google Pay / Axis). The pay link is
`upi://pay?pa=nevilnicks4321@okaxis&pn=TMPlayer&cu=INR&tn=TMPlayer%20support`, which is also what
the QR encodes, so any UPI app reads it: GPay, PhonePe, Paytm, BHIM, a bank app.

The user's own Google Pay QR picture (with their photo and legal name) is NOT committed: the QR is
drawn from the pay link with `qrencode`, so it carries no photo and can be redrawn.

## What each platform does

| Where | What the supporter sees |
| --- | --- |
| Site, every page | Under the coffee button: "Pay by UPI". Opens a dialog with the QR, the UPI ID with a copy button, and an "Open a UPI app" link on a touch device |
| Site, /support/ | The same, in the open in the Chip in section |
| Phone (Android) | A UPI row under the coffee button: opens the UPI app chooser. No UPI app installed means the QR and the ID with Copy instead |
| TV | No browser and no UPI app: the QR beside the coffee QR, with the ID under it in text |
| Desktop | The QR and the ID with a Copy button (no UPI app on a computer) |
| Web app | The same as the site: the link on a phone, the QR and the ID elsewhere |

## Edge cases to handle

- **No UPI app** (any non-India device, a TV, a computer): never a dead button. The pay link is
  only offered where something can answer it; everywhere else the QR and the copyable ID.
- **`ActivityNotFoundException`** on Android when nothing handles `upi://`: caught, falls back to
  the QR sheet rather than crashing.
- **Copy refused** (no clipboard permission, a browser without it): the ID stays selectable text,
  and the site's existing `data-copy` fallback prompts with the value.
- **The QR is scanned from the device it is shown on**: on a phone the link is offered first, since
  a phone cannot scan its own screen.
- **Kids lock / app lock**: the support screens are already behind the same rules; nothing new.
- **Nothing is locked**: no "supporter" state, no receipts, no checks. The app never learns whether
  anybody paid, as with the coffee link today.
- **Store builds**: the Play and Amazon flavours drop donation entries (CP35); the UPI row follows
  the same switch as the coffee button, not a separate one.

## Phases

- **A. Site** (`site/`): `lib/support.ts` gets the ID and the link, `SponsorOffer` gets the row,
  a `UpiDialog` holds the QR and the copy button, the support page shows it in the open, app.js
  wires the dialog. English strings only: the other catalogs are the user's langsync run.
- **B. App** (`ui/`, `app/`, `desktop/`): `About` gets `UPI_ID` and `upiLink()`, the phone's
  Support screen gets the row and the fallback sheet, the TV its QR, the desktop its QR and Copy.
  New strings in `core/.../i18n/en.json`.
- **C. Web app** (`webapp/`): the same row in its support entry.
- **D. The post**: the milestone post links straight to the support page so the dialog is one tap.

## Progress / handoff

- 2026-11-01: plan written. QR drawn to `site/public/brand/upi-qr.svg` from the pay link and
  decoded back with `zbarimg` to check it.
- 2026-11-01, Phase A done (site): `lib/support.ts` holds `UPI_ID`, `UPI_LINK` and `UPI_QR`;
  `UpiButton` is Google Pay's mark on white (`public/brand/gpay-mark.png`, cropped from the user's
  own GPay QR picture, circle only, no photo or name), under the coffee button in `SponsorOffer`
  and on `/support/`; `UpiDialog` holds the mark, the QR, the ID and Copy, opened by app.js on a
  mouse pointer and left as a real `upi://` link on a touch one. English strings only
  (`home.pay_by_upi`, `upi_scan`, `upi_open_app`, `upi_id_label`, `upi_copy_id`, `upi_any_amount`):
  the other 20 catalogs are the user's langsync run. Checked over CDP: the dialog opens, the QR
  renders at 220 px, the open-in-app button stays hidden without a touch pointer, Copy carries the
  ID. Build, site-i18n-check and site-links all pass.
- 2026-11-01, Phase B done (the app): `About.UPI_ID` and `About.UPI_LINK` in `ui/`; the phone's
  support window has a Pay by UPI button that hands the link to whichever UPI app is installed and
  falls back to the code plus the ID with Copy when nothing answers (`runCatching` round
  `startActivity`); the television shows `UpiCode()` beside the sponsors code; the desktop's
  support window shows `UpiRow()` (code, ID, Copy through `LocalClipboardManager`). Strings added
  to `core/.../i18n/en.json` under `support.` (sorted): any_amount, pay_upi, scan_upi, upi_copied,
  upi_copy, upi_id, upi_no_app. `:desktop:compileKotlin` and `:app:compileDebugKotlin` pass.
  No GPay mark in the app: the shared UI draws marks as vectors in code (`BmcMark.kt`) and there is
  no official Google Pay SVG in the repo to copy, so the app uses its own button until one is added.
- 2026-11-01, Phase C done (web app): `Chip()` in `webapp/src/screens/Settings.tsx` above About, with
  the coffee link and a UPI row carrying Google Pay's mark (`webapp/public/gpay-mark.png`); the row
  is the `upi://` link on a touch pointer and the site's support page on anything else. Strings
  added to `strings.config.ts`. `pnpm build` passes.
- Not done: Phase B's old next step. `About.kt` gets `UPI_ID` and the pay link, the phone's Support screen a
  Pay by UPI row with the chooser (catch `ActivityNotFoundException`, fall back to the QR sheet),
  the TV a QR beside the coffee one, the desktop the QR with Copy. Strings go in
  `core/src/commonMain/resources/i18n/en.json` (keys sorted, English only).
