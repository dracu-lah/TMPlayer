# Brand artwork: sources and terms

Trademarks belong to their owners. Their marks here only name the way to pay; they do not suggest
that either company endorses TMPlayer. Files are kept exactly as received: not recoloured, not
re-encoded, not compressed.

## Buy Me a Coffee (`bmc/`)

- Source: the brand kit linked from https://buymeacoffee.com/brand (a Dropbox folder), fetched
  2026-11-16. `bmc-full-logo.svg`, `bmc-logo.svg` and `bmc-button.svg` are the kit's
  "SVG Files" unchanged.
- `bmc-cup-on-yellow.svg` is the cup's 14 paths from `bmc-button.svg` in a tighter viewBox, as the
  site and `ui/.../about/BmcMark.kt` use it (the same file as `site/public/brand/`).
- Terms: the brand page states no usage terms beyond the site's general terms
  (https://buymeacoffee.com/terms). TMPlayer follows how the kit shows the mark: the cup only on
  the yellow `#FFDD00` with the ink `#0D0C22`, proportions kept, never stretched or recoloured, and
  the name written "Buy Me a Coffee". If the owner asks for a change, make it.

## UPI (NPCI)

- No UPI logo is stored. NPCI's brand centre (https://www.npci.org.in/brand-centre) answers
  automated downloads with HTTP 403, so no official asset could be obtained on 2026-11-16, and
  logos from blogs or logo sites are not official and are not used.
- The app draws a plain phone icon and the words "Pay by UPI". NPCI's guidelines ask for clear space
  of half the logo's height around it and for the logo never to be recoloured; whoever adds it
  later takes the file from the brand centre and follows its guideline PDF.
- `gpay-qr.jpg` at the repository root is the UPI QR the site already shows publicly. The app
  builds its own code from the same `upi://pay` link (`About.UPI_LINK`) and shows nothing beyond
  the UPI ID the repository already publishes.
