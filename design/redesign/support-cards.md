# Support cards: Buy Me a Coffee and Pay by UPI (D1, 2026-11-16)

## The problem Raju hits

- Settings, "Support TMPlayer" opens a window with one QR code that leads to tmplayer.org/support.
  To pay he must leave the app, find the page and choose there. The two ways to pay (Buy Me a
  Coffee, UPI) existed only in the occasional ask window, which no one can open on purpose.
- Three copies of the window (phone and TV in `:app`, desktop in `:desktop`), so each fix was made
  two or three times and they drifted (the desktop has no "Open in a UPI app", the phone has no
  clear step for the code).
- Nobody is told which way suits where they live: a person in India sees Buy Me a Coffee first,
  which takes cards and PayPal and not UPI.

## The new flow

One shared window (`SupportDialog`, `ui/src/shared/.../ui/about/`) built from shared cards
(`SupportCard`), used by the Settings row, About and the gentle reminder.

1. Heading with a heart in an accent disc and one plain sentence ("Voluntary. It unlocks nothing").
2. Two cards, in the order the region says (below). Each card is one big target: logo or icon, its
   name, one line of what happens.
   - Buy Me a Coffee: their yellow, their official cup, "Buy me a coffee". Opens the page (phone,
     desktop). A TV has no browser, so the card opens a step with the code and the address.
   - Pay by UPI: a plain phone icon (no NPCI logo, see `art/brand/LICENSE-NOTE.md`). Opens a step
     with the code, the UPI ID, Copy and, on Android, "Open in a UPI app" (a `upi://pay` intent;
     when no app answers the step says so, and the code and ID stay).
3. Under the cards: Sponsor on GitHub and Star on GitHub as quiet text buttons.
4. Ask mode (the reminder): the same window plus "Maybe later" and "Don't show again". Otherwise
   one "Close".
5. Nothing is opened or paid until a card or a button is pressed. A step has "Back" to the two
   cards; Back or Esc on the cards closes the window.

## Region

`SupportRegion` in core. No permission, no network, no IP lookup, no analytics. Android reads
`Locale.getDefault().country` and `TelephonyManager.simCountryIso` (no permission); the desktop
reads `Locale.getDefault().country`.

| Region | First card | Second |
| --- | --- | --- |
| IN (device region or SIM) | Pay by UPI | Buy Me a Coffee, full card |
| anything else, none, garbage | Buy Me a Coffee | a quiet "Pay by UPI (India)" row under it that opens the UPI step |

So the other way is always one press away, but UPI only takes a full card where it is used.
The existing reminder (notification on a phone, notice on the desktop) follows the same order: in
India its first button is "Pay by UPI" and opens the window; elsewhere it keeps "Buy me a coffee".

## Platforms

| | Phone | TV (remote) | Desktop (keyboard, mouse) | Web app |
| --- | --- | --- | --- | --- |
| Open | Settings Support row, About, reminder | same rows; the ask window on the chat list or Downloads, never in the player; no notification (TV never gets one) | Settings, About, reminder notice and window, not while a video plays | no support row, so no card (the site has its own) |
| Cards | full width, targets 56 dp or more, press scale | focus ring and fill, OK presses | hover lift, focus ring, Enter and Space | n/a |
| Focus | first card | first card; Down walks cards, rows, Close; Back closes (one level from a step) | Tab follows reading order; Esc closes (one level from a step) | n/a |
| Coffee | opens the browser | step with code and address | opens the browser | n/a |
| UPI | step: code, ID, Copy, Open in a UPI app | step: code and ID | step: code, ID, Copy | n/a |
| Large font | cards wrap, window scrolls | window scrolls | window scrolls | n/a |

## Store builds

| Build | Decision |
| --- | --- |
| GitHub APK, desktop | on |
| F-Droid (`-PtmFdroid=true`) | on. No rule against donation links; they go to the donor's own page, no payment library, no tracker |
| Amazon | on. `docs/distribution/amazon.md` already declares voluntary sponsor links that unlock nothing, as QR on TV |
| Google Play | off. Play bans outside payment prompts (`docs/distribution/play.md`). A `-PtmPlay=true` switch sets `BuildConfig.SUPPORT_LINKS` to false, the one gate (`SupportReminder.enabled`): Settings row, About group, window, reminder and the daily job all go. No Play build exists yet; the switch is what it will use |

## Edge cases

- No UPI app, or the intent is refused: the step says so; code and ID remain.
- No browser for the coffee link: the step with code and address.
- Clipboard refused: the ID stays on screen; the button does not claim success.
- Locale with no region ("en", "und"), lower case, a three-letter or numeric region, a SIM string
  with spaces, no SIM (tablet, TV, desktop): ordered by the table, never an exception.
- Window up while the phone rotates or the app goes to the background: the step is kept while the
  window lives; nothing re-opens by itself.
- Reminder: never while another window is up, never in the player, never a notification on TV,
  "Maybe later" and "Don't show again" are honoured (`SupportReminder`, cadence unchanged: window
  every 14 days after real use, notification every 30 days and 7 days after a window).
- A supporter who already gave: the heading says thanks; the cards stay for a second gift.
- Huge font, small phone: the window scrolls, cards wrap, nothing is cut.
- RTL: start and end only, so the cards mirror.
- Reduce motion: lift and press scale go; the focus fill stays.

## Logos

- Buy Me a Coffee: the official kit (https://buymeacoffee.com/brand), SVGs unchanged under
  `art/brand/bmc/`. The cup is drawn on their yellow as they show it.
- UPI: NPCI's brand centre refused an automated download (HTTP 403), so no official asset was
  obtained. The UPI card uses a plain phone icon and the words "Pay by UPI". To add the logo, take
  it from https://www.npci.org.in/brand-centre, keep clear space of half the logo height, and
  never recolour it.
