# Working on TMPlayer

## Screenshots

Every screenshot in the repository is a WebP, and stays one. A screenshot off the stick arrives as
a 1920x1080 PNG somewhere north of half a megabyte, which is a page weight the site does not need
and a blob nobody wants in the git history.

When a screenshot changes, or a new one is added, convert it before committing:

```sh
magick shot.png -define webp:method=6 -define webp:sharp-yuv=true -quality 88 docs/screenshots/name.webp
```

Quality 88 is the settled figure: it holds around 39 to 44 dB PSNR on this app's screens, so the
small text in the TV UI stays sharp at 1:1, while the file lands 60 to 90 per cent smaller than the
PNG. Do not commit the PNG alongside it, and do not lower the quality to save a few more kilobytes.

Where they live and who reads them:

- `docs/screenshots/` is the README. GitHub renders WebP.
- `site/screenshots/` is the site, referenced from `site/index.html`, `site/sitemap.xml` and
  `site/site.webmanifest`. Changing a file name means changing all three, and the manifest carries
  a `"type": "image/webp"` next to each one.

Two images are deliberately still PNG. `site/og.png` is the social preview, and the crawlers that
fetch it (WhatsApp and LinkedIn among them) cannot be relied on to decode WebP, so a link would
arrive with no picture at all. `site/icon-512.png` is the PWA icon, where PNG is what installers
expect. Both are small. Leave them as they are.

## Writing

No em dashes or en dashes, anywhere: comments, KDoc, commit messages, UI strings, site copy. Write
the sentence with a colon, a comma or a full stop instead. Before committing:

```sh
grep -rInP '\x{2014}|\x{2013}' --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle .
```
