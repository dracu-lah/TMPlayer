# Screenshots

Every screenshot in the repository is a WebP, and stays one. Convert before committing:

```sh
magick shot.png -define webp:method=6 -define webp:sharp-yuv=true -quality 88 docs/screenshots/name.webp
```

Quality 88 is settled (about 39 to 44 dB PSNR on this app's screens, so the TV's small text stays
sharp, at 60 to 90 per cent less than the PNG). Do not commit the PNG beside it and do not lower
the quality. `docs/screenshots/` is the README's; the site's are in `site/public/screenshots/`
(see [site/AGENTS.md](site/AGENTS.md) for their variants and references). Three places stay PNG on
purpose: `site/public/og.png` (link preview crawlers cannot be relied on to decode WebP),
`site/public/icon-512.png` (the PWA icon) and `iosApp/screenshots/` (the iPhone and iPad pictures of the
AltStore and SideStore source: they read PNG or JPEG only, never WebP, and are kept at the size the
generator wrote, no compression or downscale; see "Pictures for the source" in
[docs/IOS.md](../IOS.md)).
