# Search, AI answers and calls to action (CP72)

**Status (2026-10-15, live through 16fdad5):** Phases 1 to 4 shipped. Phase 1: titles, descriptions
and JSON-LD say what TMPlayer is without borrowing another app's name, and the FAQ says TMPlayer is
not "TMP - Telegram Media Player". Phase 2: eight more FAQ answers (18 in all), the comparison
table in the "without casting" guide, a /guides/ hub, a /desktop/ page, "Updated" dates. Phase 3:
a header Download button, closing download cards, a phone bar after the page head (no pop-ups) and
a next-steps panel after a download click. Phase 4: robots.txt names every AI crawler, Markdown
twins with `Accept: text/markdown` redirects, llms-full.txt, and IndexNow on every site push (first
run: 284 URLs, answered 200). Also: smaller screenshot copies for phones, the stylesheet minified,
Lighthouse SEO, accessibility and best practices at 100 on production (performance 90 on the home
page, 95 to 100 elsewhere), a real /favicon.ico, and every new string in all 20 languages.
Phase 5 (measure) is due on 2026-11-12, 28 days after the deploy.

A plan, not yet started. Written 2026-10-15 from the Search Console export of the last three
months (Web, 2026-07-17 to 2026-10-14) and a look at what search engines and AI assistants
reward in 2026. Each item says what changes, where, and how we will know it worked.

## What the data says

Three months: 400 clicks from 2,738 impressions (CTR 14.6 per cent), average position about 6.
Impressions are rising fast (about 50 a day in September, 136 and 160 on 11 and 12 October) while
CTR falls (from around 20 per cent to 6 to 12), because the new impressions are broader queries
where the result does not yet look like the answer.

1. **People search for the long name.** TMPlayer stands for Telegram Media Player (the v1 design
   doc says so), and the long name is the second query: "telegram media player" 214 impressions at
   position 4.9, plus "... apk" (40, 4.6), "... for android tv" (15, 2.7, CTR 47 per cent),
   "tmp telegram media player" (28, 7.1) and "tmp - telegram media player" (15, 8.3). About 340
   impressions in all. The site never says "Telegram Media Player" in a title, heading or
   sentence; it appears once, inside a JSON-LD caption. We rank 5 to 8 for our own name.
2. **The short name is ambiguous.** "tmp player" (62, 2.7) and "tm player" (42, 1.7) work. "tmp
   apk" (51, 5.7), "tmp app" (7, 6.6, no clicks) and "tmp app download apk for android tv" (4,
   6.5, none) do not: other things called TMP sit above us. "tmp tv" and "tmp live" are probably
   someone else's product and are not worth chasing.
3. **Generic queries are where growth is, and where we are weak.** "telegram player" 55
   impressions at 7.7 with CTR 5 per cent; "telegram player apk" 12.5; "telegram video player"
   22; "telegram player for pc" 5 with no clicks; "tvgramplayer" (another app) 11.
4. **The home page carries everything.** `/` has 2,451 of the 2,738 impressions. `/download/` has
   437 at position 7.3 with CTR 6.2 per cent, the weakest big number on the site. `/install/`,
   `/android-tv/` and `/features/` sit at position 4 to 6 with 22 to 27 impressions and no clicks.
5. **The languages are barely found.** Every translated page together has under 250 impressions.
   The localized guides get some (it, id, ar, de, es) and no clicks.
6. **Where the readers are.** India 652 impressions (24 per cent), Myanmar 166 (CTR 25 per cent,
   no Burmese translation), United States 150 (CTR 5.3 per cent), Italy 121, Philippines 100,
   Malaysia 99, Germany 86 (CTR 4.7), Spain 82, Brazil 73. English readers in the US, UK and
   Germany click least: the snippet does not answer their query.
7. **Devices.** Mobile 1,863 impressions at position 5.0; desktop 741 at 9.0. People search on a
   phone for something to put on the TV.
8. **No rich results.** "Search appearance" is empty.

Still missing from the data, and asked of the user below: which queries each page ranks for, the
Page indexing report, and Bing.

## Export of 2026-10-19 (last 3 months, web, data to 2026-10-17)

524 clicks from 4,407 impressions (CTR 11.9 per cent), up from 400 and 2,738 four days earlier.
Impressions per day keep climbing (301 on 15 October, 446 on the 16th, 427 on the 17th) at an
average position of 5 to 6. Mobile 3,190 impressions at 5.2, desktop 1,063 at 8.4, tablet 154 at
4.2. Search appearance is still empty.

**Queries the site did not answer, and what was done (2026-10-19):**
- Casting: "cast from telegram to tv", "telegram airplay", "telegram to tv", "telegram on tv".
  1.27.0 added casting to a Chromecast or Google TV and the site never said so. New FAQ entry
  "Can I cast Telegram videos to a Chromecast or Google TV?" (FAQPage JSON-LD and the page; says
  plainly that AirPlay is not supported), and a Casting line in `llms.txt`.
- Music and audio: "telegram audio player" (position 37.5), "telegram music player" (40). The FAQ
  answered "No, videos only", which 1.27.0's Audio list per chat made wrong. The answer now says
  yes and what is not listed yet (audio sent as a plain document); the guide's comparison row and
  `llms.txt` follow.
- The safety answer said crash reports exist "on Android" only; it now holds for every platform
  (CP102g).

**Already answered, no change:** Android TV, Google TV and Fire TV (`telegram para fire stick`,
`google tv telegram`, `telegram tv 版`, the Persian, Russian, Indonesian, French and Hindi "how do
I watch Telegram on the TV" queries, all with a localized guide), PC and Windows (`/desktop/`),
external players such as VLC, "is it free" (`alguna que no sea de pago`), the long and short
names. "telegram player for windows" sits at 13.3 and "telegram player download" or "apk" at 10
to 11: `/desktop/` and `/download/` are the pages; watch them in the next export before
changing their titles.

**Not chased, on purpose:** "telegram porn", "telegram 🔥 videos", "videos de telegram gratis",
the "mod apk" queries and "tmp live" or "tmp tv" (someone else's product). TMPlayer is a player
for the videos already in your own chats, and the site does not court searches for free content
or modified APKs (CLAUDE.md wording rules). "tvgramplayer" and "teleplayer" are other apps; the
comparison table in the no-casting guide is their landing place, with no digs.

**Still open:** "telegram link video app" (opening a t.me link in TMPlayer, CP79) waits for the
release that ships it; then a FAQ entry. A Burmese catalog (Myanmar: 175 impressions, CTR 24 per
cent) remains the user's call. Next export: 2026-11-12, as Phase 5 says.

## Competitors (checked 2026-10-15)

Telegram has no TV app of its own, so the results for these queries are third-party apps, mostly
on Google Play, and how-to articles that suggest casting, mirroring or sideloading Telegram:

| App | Where | Note |
|---|---|---|
| TMP - Telegram Media Player | Google Play `com.tmp.com`, Amazon | developer "T.M.P", 100,000+ installs, cached listings last updated 2020 to 2021; recommended by Spanish and Italian how-tos |
| VelaTV - Telegram Media Player | Google Play `com.velatv.app` | uses the same generic phrase in its name |
| TvGramPlayer | Google Play `com.network.tvgramplayer.playstore` | the "tvgramplayer" query (12 impressions, position 11) |
| Telegram Video Player: TvGram | Google Play `dev.vishnuv.tvgram` | |
| Echogram - Telegram Player | Google Play `com.liori.echogram` | MKV, subtitle and audio tracks, Cast and DLNA |
| KiloGram | APK sites | a full Telegram client for Android TV, TDLib, PIN lock |
| Tevegram | APKMirror | a full Telegram client for Android TV, last listed 2024 |

What this changes:

- **"TMP" is someone else's name, and "Telegram Media Player" is a generic phrase several apps
  use.** Many "tmp ..." and "telegram media player" searches are people looking for the older TMP
  app. So the site describes TMPlayer as a Telegram media player in lowercase, never claims "TMP"
  or "Telegram Media Player" as its own name (no `alternateName`), and answers the confusion head
  on: the FAQ asks "Is TMPlayer the same app as TMP - Telegram Media Player?" and says no, and
  `llms.txt` says the same. That earns the clicks honestly and keeps clear of passing off.
- **Every competitor is on Google Play; TMPlayer is not.** Play listings rank for app queries and
  carry ratings and install counts that a website cannot show. A Play (and Amazon Appstore)
  listing would be the largest gap in reach, but it is not planned for now (user, 2026-10-15), so
  the site carries the weight: the download page, the install steps and the FAQ.
- **A fair comparison table** in the "without casting" guide can name these apps by what they
  are (a full Telegram client, a player, a casting tool) and what each needs, without claims about
  their quality. Facts to be checked against each listing on the day it is written.

## Competitors, rechecked 2026-10-19

The 2026-10-15 notes above, checked again listing by listing for the new `/alternatives/` page
(`site/src/pages/[...lang]/alternatives/`, its rows in `site/src/data/alternatives.json`). The
queries it is meant to answer: "tmp tv", "tmp apk", "tmp app", "tmp - telegram media player",
"telegram media player", "telegram player", "telegram for android tv", "telegram for fire tv",
"tvgramplayer", "teleplayer".

| App (developer) | What it is | Platforms | Price | Open source | Store facts on 2026-10-19 | Source |
|---|---|---|---|---|---|---|
| TMPlayer (dracu-lah) | player for your chats | Android phones, Android TV, Google TV, Fire TV, Windows, Linux, macOS | free, no ads, no purchases | yes, GPL-3.0 | release 1.27.1 on 2026-10-19 | github.com/dracu-lah/TMPlayer |
| TMP - Telegram Media Player (T.M.P) | player for your chats on TV | Android TV, Fire TV | free | no | Google Play `com.tmp.com` "not found"; last archived Play listing (December 2020) showed 10.0.21, updated 2020-10-06; Amazon still up, 10.0.25, 2.2/5 from 3,219 ratings | amazon.com/dp/B088DY5LTT |
| Tevegram (Cassian Lemans / clapps) | full Telegram client for TV | Android TV, Google TV, Apple TV | in-app Premium (lifetime or yearly); on Apple TV videos need Premium, $2.99 a month, $5.99 a year or $13.99 lifetime | no | Play 1M+ installs, 2.4 stars from 4.38K reviews, updated 2026-10-12 | play.google.com `cassian.telegram.ooa.pro`, apps.apple.com/app/id6477915879 |
| Echogram - Player for Telegram (Illuzion Apps) | player for your chats, casts (Google Cast, DLNA) | phones, Android TV, Fire TV | free with in-app purchases (Premium for live streams) | no | Play 50K+, updated 2026-09-18; Amazon 3.9/5 from 29 ratings | play.google.com `com.liori.echogram` |
| Telegram Video Player: TvGram (Vishnu V) | player for your chats | phones, Android TV | free with in-app purchases | not checked | Play 50K+, updated 2026-10-05 | play.google.com `dev.vishnuv.tvgram` |
| TeleV: Telegram for Android TV (telev.app) | viewer for TV, QR sign in | Android TV | free, says no ads | not checked | Play 10K+, updated 2026-10-14 | play.google.com `com.telev.tv` |
| VelaTV: Telegram Player for TV (Vela Studio) | media player for Telegram and other sources (local, NAS, WebDAV, Google Drive) | phones, Android TV, Google TV, Fire TV | 10-day free trial, then subscription or one-time purchase | not checked | Play 1K+, updated 2026-10-11 | play.google.com `com.velatv.app` |
| KiloGram (D3NMOH) | full Telegram client for TV | Android TV, Google TV | free | not checked | Play 1K+, updated 2026-09-02 | play.google.com `xyz.d3nmoh.kilogram` |
| TvGramPlayer (Lucas Rosillo Fuste) | full Telegram client with a media player | Android TV, Fire TV, Chromecast with Google TV | $2.49 | not checked | Play 100+, updated 2026-10-11 | play.google.com `com.network.tvgramplayer.playstore` |
| tv for Telegram (Phil Trevor) | full client on Apple TV | Apple TV | $1.99 plus in-app purchases | not checked | not checked | apps.apple.com/app/id6642679198 |
| Telegram Desktop (Telegram) | the official desktop app, plays videos itself | Windows, macOS, Linux | free | yes | not in a store row | desktop.telegram.org |

"Not checked" on the page means the research did not confirm it (open source for TvGram, TeleV,
VelaTV, KiloGram, TvGramPlayer and tv for Telegram; the last update of tv for Telegram and
Telegram Desktop). Fill them in at the next recheck rather than guessing.

New since 2026-10-15, not in the table (smaller, or not a TV app; watch them):

- TeleV (now in the table), and a second "TvGram" by Ghost Dev.
- BingeGram, TeleMedia TV and Teleplayer (the "teleplayer" query).
- Vexeron's "Telegram Player" holds the generic name on Google Play, with 1M+ installs: the
  likeliest app behind a plain "telegram player" search.
- Tortugram and gPlayer on the Amazon Appstore.
- SyncGram on Apple TV.

What the page claims, and why:

- **Facts with a date and a source, nothing else.** Every row has a `checked` date and links its
  listing with `rel="nofollow noopener"` (naming an app is not endorsing it). The page says
  "Checked on 19 October 2026; stores change". No "best", no ranking, no remarks on quality.
- **TMP is answered, not borrowed.** The page and its description say what TMP's listings showed
  (Google Play not found, Amazon at 10.0.25 from 2020) so "tmp tv" and "tmp apk" searchers see at
  once that this is a different, maintained app. TMPlayer never calls itself "TMP" or "Telegram
  Media Player" (no `alternateName`), and the page says it is a separate, unrelated project. The
  FAQ answer about TMP carries the same facts and links the page.
- **What TMPlayer does differently** is limited to what the table proves: free with nothing to
  buy and no ads of its own; open source GPL-3.0, built by GitHub from public code; the only app
  in the table on Android phones, TVs and Windows, Linux and macOS; TDLib with no server or bot.
  QR sign in, MKV, subtitles, audio tracks and casting are listed as things it does, not as
  unique, since other apps have them.
- **Apple TV** is answered honestly: TMPlayer does not run there and casts to Chromecast or Google
  TV, not AirPlay; Tevegram and tv for Telegram are named with their prices.
- Images are TMPlayer's own demo screenshots only, never another app's screenshots, logos or icons.

### Outreach (user, 2026-10-19: "i will do it later"; nothing sent yet)

One correction email each, from hello@tmplayer.org, one follow-up after two weeks at most. No payment
and no request for a "dofollow" link (Google counts paid links as spam). Before sending, open the
two localized guide links once to check they are live. When one of them adds TMPlayer, note the
date here.

| Site | Article | Where to write | Language | Sent |
|---|---|---|---|---|
| Xataka Android | "Cómo ver todo el contenido de Telegram en tu tele con Android TV o Google TV" (Jose Antonio Carmona, 2025-01-07): links TMP's Google Play listing, now not found | the editors' addresses on https://www.xatakandroid.com/quienes-somos (or its "Contacta con nosotros" link); name the article | Spanish | no |
| Líbero (Peru) | "cómo ver chats, videos, fotos de Telegram en tu televisor Android TV" (2022-04-17): recommends only TMP | infolibero@glr.pe, the address its https://libero.pe/politicas-y-estandares page gives for corrections | Spanish | 2026-10-19 (email) |
| YourLifeUpdated (Italy) | "Come avere Telegram su Fire TV Stick con Telegram Media Player" (Riccardo Conti, 2020-12-04): its own banner says TMP no longer works | yourlifeupdated@gmail.com, from https://www.yourlifeupdated.net/contatti (a one-person site, slow replies; not "COLLABORAZIONE" in the subject, which is for paid work) | Italian | 2026-10-19 (email) |

**Spanish draft** (Xataka Android; for Líbero change the greeting and the article name):

> **Asunto:** Corrección: el enlace a TMP en «Cómo ver todo el contenido de Telegram en tu tele»
>
> Hola,
>
> En vuestro artículo sobre cómo ver Telegram en Android TV se recomienda TMP - Telegram Media
> Player con un enlace a Google Play. Esa ficha ya no existe: el 19 de octubre de 2026 la página de
> Google Play devolvía «no encontrada», y su última versión allí es de octubre de 2020.
>
> Por si os sirve para actualizarlo: desarrollo TMPlayer, un reproductor gratuito y de código
> abierto (GPL-3.0) para los vídeos que ya están en tus propios chats de Telegram. Funciona en
> Android TV, Google TV, Fire TV, móviles Android, Windows, Linux y macOS, inicia sesión con un
> código QR mediante la librería oficial de Telegram, y no tiene anuncios ni compras. No es la
> misma app que TMP.
>
> - Web y descarga: https://tmplayer.org/
> - Guía para Android TV: https://tmplayer.org/es/guides/telegram-videos-on-android-tv/
> - Código fuente: https://github.com/dracu-lah/TMPlayer
> - Aviso legal: https://tmplayer.org/legal
>
> Gracias por vuestro trabajo,
> Nevil, TMPlayer

**Italian draft** (YourLifeUpdated):

> **Oggetto:** Segnalazione: l'app TMP nell'articolo su Telegram per Fire TV Stick
>
> Ciao Riccardo,
>
> nel tuo articolo «Come avere Telegram su Fire TV Stick con Telegram Media Player» il banner dice
> già che l'app non funziona più. Ti scrivo nel caso tu voglia indicare un'alternativa funzionante.
>
> Sviluppo TMPlayer, un lettore gratuito e open source (GPL-3.0) per i video che hai già nelle tue
> chat di Telegram. Gira su Fire TV (Fire OS 7 o successivo), Android TV, Google TV, telefoni
> Android, Windows, Linux e macOS. Si installa sulla Fire TV con l'app Downloader e si accede con
> un codice QR, tramite la libreria ufficiale di Telegram. Non ha pubblicità né acquisti, e non è
> la stessa app di TMP.
>
> - Sito e download: https://tmplayer.org/
> - Guida per Fire TV: https://tmplayer.org/it/guides/telegram-videos-on-fire-tv/
> - Codice sorgente: https://github.com/dracu-lah/TMPlayer
> - Note legali: https://tmplayer.org/legal
>
> Grazie,
> Nevil, TMPlayer

#### More targets (researched 2026-10-19, ranked; nothing sent)

Every address below was read on the publisher's own page (named in the row). Send one at a
time, a few a day, each written to that article or outlet. The same text sent to many outlets at
once is what spam filters and editors both discard.

| # | Site | Why | Where to write | Language | Sent |
|---|---|---|---|---|---|
| 1 | AFTVnews | Fire TV and Google TV news, covers sideloaded apps (makes Downloader) | contact@aftvnews.com (aftvnews.com/contact/); support questions are not answered, so send it as news | English | 2026-10-19 (email) |
| 2 | 9to5Google | Covered Telegram's Chromecast casting (2025-03-10) | gtips@9to5g.com (9to5google.com/contact/); a tip may be published as sent | English | 2026-10-19 (email) |
| 3 | XDA Developers | "Open source tool" and smart TV app columns (2026-08-12 list) | editorial@xda-developers.com ("topic ideas, corrections", xda-developers.com/contact/; no form) | English | 2026-10-19 (email) |
| 4 | Android Police | Google TV app lists (2025-02-10 "apps you've never heard of") | editorial@androidpolice.com (androidpolice.com/contact/; no form) | English | 2026-10-19 (email) |
| 5 | OMG! Ubuntu | Linux apps; the AppImage and AUR | contact@omgubuntu.co.uk (app developers use the address, not the form) | English | 2026-10-19 (email) |
| 6 | It's FOSS | Open source apps that "should reach more people" | the form at itsfoss.com/contact-us/ | English | 2026-10-19 (form; the first try had a wrong reply address). Correction (reply to hello@tmplayer.org): sent 2026-10-20 through the form (the contact page has no email address) |
| 7 | TuAppPara | "Telegram en Smart TV" (updated 2026-09-09) says no TV app exists | the form at tuapppara.com/contacto/ | Spanish | no |
| 8 | Aranzulla | "Come collegare Telegram alla TV", a top Italian result, names no TV app | errori@aranzulla.it, for changes to an existing article; no press releases | Italian | 2026-10-19 (email) |
| 9 | Olhar Digital | Wrote up Telegram casting (2025-03-11) | redacao@olhardigital.com.br (/fale-conosco/) | Portuguese | 2026-10-19 (email) |
| 10 | Trashbox | Russian app catalogue, already lists Tevegram | self-publish: register, then "Добавить пост" (trashbox.ru/link/how-to-publish-apps); free apps only, the APK is checked | Russian | no |

Next in line, if the first ten go quiet: El Grupo Informático (noticias@elgrupoinformatico.com,
Spanish), Androidphoria (contacto@androidphoria.com, Spanish), Xataka Home (prensa@xatakahome.com,
send after the Xataka Android correction, same group), TuttoAndroid (marco@tuttoandroid.net,
Italian, no guest posts or paid links), Telefonino.net (redazione@telefonino.net, Italian, its
2026-02-04 Fire TV app list), Beebom (contact@beebom.com, India), JalanTikus (info@jalantikus.com,
Indonesian), Linux Uprising (contact@linuxuprising.com), LinuxLinks (the form at
linuxlinks.com/suggest-open-source-program/; it judges AI-built projects case by case),
smarttvs.org (hi@smarttvs.org, its Android TV list updated 2026-10-07), gadgetjunction.in
(contactus@gadgetjunction.in, India).

Sent from the next in line on 2026-10-20 (by private/outreach-send.py, each written to one article):

| Site | Article or angle | Where to write | Language | Sent |
|---|---|---|---|---|
| smarttvs.org | "Best Android TV Apps in 2026: 18 Tested and Ranked" (updated 2026-10-07), its media players section (VLC, Kodi, Nova) | hi@smarttvs.org (smarttvs.org/contact/: corrections and content suggestions) | English | 2026-10-20 (email) |
| Linux Uprising | Desktop app introductions (NormCap, GPU Screen Recorder); the homepage's newest post read 2026-10-20 was from May 2023, so it may be dormant | contact@linuxuprising.com (linuxuprising.com/p/contact.html) | English | 2026-10-20 (email) |
| Beebom | "20 Best Android TV Apps You Should be Using" (updated 2025-10-15); Hindi, Bengali, Malayalam | contact@beebom.com (beebom.com/contact-us/) | English | 2026-10-20 (email) |

gadgetjunction.in was not sent: no article of theirs on Telegram or TV apps turned up.

Skipped on purpose: kodibeginner.com and emoticonwiki.de (both recommend TMP, but one is a Kodi
and IPTV site and the other mentions pirated videos in groups, which the wording rule keeps us
away from), firestickhacks, troypoint and the other jailbreak or IPTV list sites, vendor pages
(AirDroid, Apowersoft) that promote their own mirroring apps, paid "partner content" (techloy),
Habr (bans AI-written text and limits self-promotion), and Zoomit (Telegram is filtered in Iran).
Fire TV pitches say Fire OS 7 or later: the newest Fire TV Sticks run Vega OS and cannot sideload.

**One draft per target.** Each is cut to what that outlet runs: a tip line wants three facts and
a link, a correction names the article and the one thing that changed, a Linux site wants the
package. Send each by hand from hello@tmplayer.org, a few a day.

**1. AFTVnews** (contact@aftvnews.com). They make Downloader, so lead with it:

> **Subject:** Open source Telegram video player for Fire TV, installs with Downloader (tmplayer.org/apk)
>
> Hi Elias,
>
> TMPlayer is a free, GPL-3.0 player for the videos in your own Telegram chats, built for the
> remote: QR sign in through Telegram's TDLib, MKV, subtitles, audio tracks. On Fire OS 7 or later
> it installs by typing tmplayer.org/apk into Downloader. No ads, nothing to buy.
>
> https://tmplayer.org/guides/telegram-videos-on-fire-tv/ · https://github.com/dracu-lah/TMPlayer
>
> Nevil, TMPlayer (legal: https://tmplayer.org/legal)

**2. 9to5Google** (gtips@9to5g.com). A tip, so three lines:

> **Subject:** Tip: open source Telegram video app for Google TV
>
> You covered Telegram's Chromecast casting in March 2025. TMPlayer is a free, open source
> (GPL-3.0) Google TV and Android TV app for the videos in your own Telegram chats: QR sign in,
> streams while it downloads, casts from phone or desktop. https://tmplayer.org/ ·
> https://github.com/dracu-lah/TMPlayer
>
> Nevil, TMPlayer

**3. XDA Developers** (editorial@xda-developers.com). Their angle is open source tools worth sideloading:

> **Subject:** Open source Google TV app: TMPlayer, a player for your own Telegram videos
>
> TMPlayer is a GPL-3.0 Kotlin/Compose app on TDLib that plays the videos in your own Telegram
> chats on Android TV, Google TV, Fire TV, phones, Windows, Linux and macOS. Every release is
> built by GitHub Actions from the public code. Telegram has no TV app of its own, so this fills a
> gap your "smart TV apps that deserve more attention" readers might like.
> https://github.com/dracu-lah/TMPlayer · https://tmplayer.org/
>
> Nevil

**4. Android Police** (editorial@androidpolice.com). Their "Google TV apps you've never heard of" list:

> **Subject:** For your Google TV app lists: TMPlayer
>
> A free, open source app that plays the videos in your own Telegram chats on Google TV, with a
> QR sign in and subtitles, no ads. Telegram has no Google TV app. https://tmplayer.org/
>
> Nevil, TMPlayer

**5. OMG! Ubuntu** (contact@omgubuntu.co.uk). They want the Linux side and how to get it:

> **Subject:** TMPlayer: open source Telegram video player for Linux (AppImage)
>
> Hi Joey,
>
> TMPlayer plays the videos in your own Telegram chats on Linux (AppImage), Windows, macOS and
> Android TV. GPL-3.0, TDLib, QR sign in, streams while downloading, casts to Chromecast from the
> desktop. Screenshots and download: https://tmplayer.org/download/ · source:
> https://github.com/dracu-lah/TMPlayer
>
> Nevil

**6. It's FOSS** (contact form). Their reason to write is "an open source product that should
reach more people":

> Hi, TMPlayer is a GPL-3.0 player for the videos in your own Telegram chats, on Linux, Windows,
> macOS and Android TV, with releases built in public on GitHub. It is a one-person project with
> few users so far and might fit your open source app coverage. https://tmplayer.org/ ·
> https://github.com/dracu-lah/TMPlayer . Nevil

**7. TuAppPara** (form, Spanish). A correction to one sentence:

> Hola: vuestro artículo «Telegram en Smart TV: cómo verlo sin app en 2026» (actualizado el 9 de
> septiembre) dice que no hay app de Telegram para la tele. Existe TMPlayer, gratuita y de código
> abierto (GPL-3.0), para los vídeos de tus propios chats en Android TV, Google TV y Fire TV, con
> inicio de sesión por QR mediante la librería oficial de Telegram: https://tmplayer.org/es/ .
> Por si queréis añadirla. Gracias, Nevil

**8. Aranzulla** (errori@aranzulla.it, Italian). They take changes to existing articles, not
press releases:

> **Oggetto:** Aggiornamento a «Come collegare Telegram alla TV»
>
> Buongiorno, nell'articolo non è indicata nessuna app per vedere i video di Telegram sulla TV.
> Ne esiste una gratuita e open source, TMPlayer, per Android TV, Google TV e Fire TV, che
> riproduce i video delle proprie chat e accede con un codice QR: https://tmplayer.org/it/ .
> Grazie, Nevil

**9. Olhar Digital** (redacao@olhardigital.com.br, Portuguese). A story idea:

> **Assunto:** Pauta: app aberto para ver os vídeos do Telegram na TV
>
> Olá, vocês noticiaram em março de 2025 o envio de vídeos do Telegram ao Chromecast. O TMPlayer
> é um app gratuito e de código aberto que roda direto na Android TV, Google TV e Fire TV, além de
> Windows, Linux e macOS, e reproduz os vídeos dos seus próprios chats com login por QR, sem
> anúncios: https://tmplayer.org/pt/ · https://github.com/dracu-lah/TMPlayer . Abraço, Nevil

**10. Trashbox** (self-publish, Russian). Not an email: the catalogue post itself. Fill their form
with the APK from the GitHub release and this description:

> TMPlayer: бесплатный плеер с открытым кодом (GPL-3.0) для видео из ваших собственных чатов
> Telegram. Android TV, Google TV, Fire TV и телефоны; вход по QR-коду через TDLib, потоковое
> воспроизведение во время загрузки, MKV, субтитры, звуковые дорожки, трансляция на Chromecast.
> Без рекламы и покупок. Сайт: https://tmplayer.org/ru/ · Код: https://github.com/dracu-lah/TMPlayer

Before sending: open each localized link once, check the names in greetings against the site
(AFTVnews is Elias Saba, OMG! Ubuntu is Joey Sneddon; drop the name if unsure), and note the date
in the table's Sent column.

Keep it true:

- **Recheck the table monthly** (each listing's update date, installs, rating and price), move the
  `checked` dates and the page's `lastmod` with it, and add a row only for an app with a real
  listing.
- An **Amazon Appstore listing** would be where Fire TV owners search, and TMP's Amazon page is
  still up with 3,219 ratings. Whether to list there is the user's decision.

## How search and AI answers work now (sources at the end)

- Google says AI Overviews and AI Mode run on its normal Search systems, with "query fan-out"
  across related searches; ordinary SEO is what earns a place in them. There is no separate AI
  index to submit to.
- ChatGPT search and Copilot answer from Bing's index. Bing Webmaster Tools has had an AI
  Performance report (Copilot citations, grounding queries) since February 2026. IndexNow is the
  way to tell Bing a page changed.
- Assistants quote passages, not pages: a section that answers one question in 40 to 180 words,
  under a heading that is the question, is what gets lifted.
- The same facts in the same words everywhere (site, GitHub, README, store listings, directories)
  make an assistant confident enough to name you.
- llms.txt is a community proposal, used by some assistants and coding agents, ignored by Google.
  Cheap to keep, so we keep it accurate, but it is not where the gains are.
- Agents increasingly ask for Markdown (`Accept: text/markdown`, or a `.md` twin linked from the
  page with `<link rel="alternate" type="text/markdown">`). A twin that 404s or redirects is worse
  than none.
- Mobile pop-ups that cover the content when a visitor arrives from search, or while they scroll,
  are a Google ranking demotion for that page. Slim banners are fine.

## Indexing (Search Console, Page indexing report, 2026-10-15)

- **Discovered, currently not indexed: 220 URLs**, never crawled. Every language has 7 to 12
  pages in it, and so do 12 English pages (`/android-tv/`, `/features/`, `/faq/`, `/fire-tv/`,
  `/formats/`, both device guides, `/changelog/`, `/security/`, `/support/`, `/translate/`,
  `/privacy`). The pages are fine (sitemap, internal links, canonicals, hreflang all check out);
  Google is rationing crawls for a two-month-old domain with few links to it. What helps: links
  from other sites (Phase 4, third-party presence) and "Request indexing" in URL Inspection for
  the English pages first (about ten a day).
- **Crawled, currently not indexed: 11 URLs**: four translate pages, two Turkish, two Italian, one
  Chinese FAQ, the English "without casting" guide, and `https://tmplayer.org/legal` without its
  slash. The slashless address came from the app's About screen (`About.kt`), fixed for the next
  release; Vercel's 308 covers installed copies meanwhile. The guide gains its comparison table
  in Phase 2, which gives it more to index.
- **Alternate page with proper canonical: `https://www.tmplayer.org/`**, last crawled
  2026-09-22. www now answers with a 308 to the bare domain, so this clears on the next crawl.
- **Page with redirect**: `http://tmplayer.org/` and `http://www.tmplayer.org/`, as they should.
- Vercel firewall: Bot Protection off, Attack Challenge Mode off (user, 2026-10-15), so assistants
  fetching pages for a user are not challenged.

## Chromium audit (2026-10-15)

Lighthouse on a local copy of the build, before and after the fixes (mobile):

| Page | Before (Perf / A11y / BP / SEO) | After |
|---|---|---|
| / | 87 / 96 / 96 / 92 | 87 / 100 / 96 / 100 |
| /download/ | 88 / 100 / 96 / 92 | 87 / 100 / 96 / 100 |
| /faq/ | 90 / 100 / 96 / 92 (LCP 3.1 s) | 96 / 100 / 96 / 100 (LCP 2.4 s) |

Best practices stays at 96 locally only because `/_vercel/insights/script.js` does not exist off
Vercel. Fixed: the notice bar's "Learn more" link (the one failing SEO audit, on every page);
scroll reveal no longer hides what is already on screen; the header fits at 320 px; breadcrumb and
inline link contrast; accessible names that contain their visible text; release dates in UTC (the
download page said 15 October for a release of 14 October); the stylesheet ships minified (111 KB
to 65 KB). Still open: smaller image variants for phones (about 250 to 300 KB a page; needs the
user's say, since source images are not downscaled without asking) and a Content-Security-Policy
header (not a ranking factor; needs care with the inline theme script and the Vercel and GitHub
requests).

## Two guides, profiles and release stories (2026-10-19)

**Done 2026-10-19**, in all 21 languages, each in `lastmod.json` (2026-10-19), `llms.txt`, the
guides hub (now five guides) and linked from the related pages:

- **`/guides/telegram-on-samsung-or-lg-tv/`**, "Telegram on a Samsung or LG TV: what works", for
  "telegram on samsung tv", "telegram lg tv", "telegram smart tv". Says plainly that Tizen and
  webOS install no Android apps, so neither Telegram nor TMPlayer goes on them, then compares a TV
  stick in the HDMI port, casting from TMPlayer, screen mirroring and Telegram Web in a table, and
  answers the questions people type as H2s (install, cast, which TVs have Google Cast, mirroring,
  Telegram Web, which to pick). Article JSON-LD, no HowTo, no FAQPage. Linked from the
  no-casting guide (a Samsung or LG card), `/alternatives/` and the casting guide.
- **`/guides/cast-telegram-videos-to-chromecast/`**, "Cast Telegram videos to a Chromecast or
  Google TV", with a four step HowTo. TMPlayer's facts come only from the 1.27.0 and 1.27.1 tag
  notes and CP73 in `docs/PLAN.md`: Android phone or computer, Cast in the player (top bar on a
  phone, player bar on a computer), same Wi-Fi, the device keeps the controls, MKV as HLS cut at
  its cues, a soundtrack the TV cannot play converted to AAC on a phone (not on a computer, CP73
  "Not done"), subtitles on the TV (1.27.1), a 4K video on a 1080p TV says so, Reconnect and Play
  here, the place kept for Continue watching, no AirPlay, the player stays open while casting
  (CP73 "Not done": a cast that outlives the player). Telegram's app: only what its own
  announcement says. Linked from the FAQ's casting answer (its `more` link), the no-casting
  guide's table note and `/alternatives/`.
- **Organization and SoftwareApplication `sameAs`** on the home page now name the Telegram
  channel (t.me/tmplayerapp) and group (t.me/tmplayer_chat) beside GitHub.
- **Release pages** link the developer's write-up where there is one, from the hand-kept
  `site/src/data/release-posts.json`: 1.26.0 and 1.27.0 (both answered 200 on 2026-10-19). Only
  on that release's own page.

Sources for every brand and Telegram claim on the two guides, checked 2026-10-19:

- Telegram's apps exist for Android, iOS, Windows, macOS, Linux and the web, none for Tizen or
  webOS: [telegram.org/apps](https://telegram.org/apps).
- Telegram's Android app casts to Chromecast: Telegram, [Star Messages, Pinned Gifts,
  Verification Platform 2.0, and More](https://telegram.org/blog/star-messages-gateway-2-0-and-more)
  (7 March 2025: "Stream to Chromecast on Android", "Open a video, tap the Settings icon, and
  select Chromecast", Android only), and [9to5Google, 10 March 2025](https://9to5google.com/2025/03/10/telegram-chromecast-support-android-update/)
  (videos, in the latest Android update; no version, button or limits stated). Neither says
  which formats, soundtracks or subtitles it handles, so the guide says that.
- Samsung: [SamMobile, 24 March 2026](https://www.sammobile.com/news/samsung-tvs-launched-2026-feature-google-cast/)
  (Google Cast in the specifications of the 2026 Crystal UHD, Mini LED and Neo QLED models on
  Samsung's US site; before that only commercial and hotel TVs, since 2025);
  [9to5Google, 2 April 2026](https://9to5google.com/2026/04/02/google-cast-support-rolling-out-to-samsung-tvs-and-not-just-the-newest-ones/)
  (a One UI on Tizen update brings it to at least some older models, an S90D owner reported).
- LG: [9to5Google, 14 October 2024](https://9to5google.com/2024/10/14/lg-2023-tvs-google-cast-update/)
  (Google Cast started on 2024 TVs, then the 2023 C3 and G3 OLED with firmware 23.20.50);
  [What Hi-Fi](https://www.whathifi.com/news/lg-brings-google-chromecast-and-a-major-filmmaker-mode-upgrade-to-its-2022-oled-tvs)
  (webOS 24 brings Chromecast to LG's 2022 OLED TVs; the page body did not load for us, the
  headline and search summary did, so the guide says "some of its 2022 OLED TVs").
- Sony: [Sony support, How to use Google Cast on Sony's Google TV or Android TV](https://www.sony.ca/en/electronics/support/articles/00135368)
  (Google TV and Android TV yes, other TVs no; same Wi-Fi). The page answers 403 to fetchers;
  read through search results.
- Google TV makers: [tv.google/products](https://tv.google/products/) ("Meet our partner
  brands": Hisense, Sony, TCL). The page does not mention Cast, so the guide credits Cast on
  Google TV sets to Sony and Philips only.
- Philips: Philips Google TV spec sheets on documents.philips.com list "Chromecast built-in"
  (for example the 7000 series, [one sheet](https://www.documents.philips.com/assets/20230823/e9c83ef3572a4a22a81eb0670076917b.pdf)).
- Vizio: [vizio.com/google](https://vizio.com/google) ("Chromecast built-in" on VIZIO Smart TVs;
  no model list).

Left out because no source confirmed it: TCL and Hisense having Google Cast (only retailer and
spec-site listings, no maker page), Samsung's update version number and regions, LG's exact
2022 models, the names of Samsung's and LG's mirroring features, and any test of TMPlayer's
casting on a Samsung or LG set (the guide says it was tried on an Android TV stick only).
Recheck the Samsung and LG lines in a few months: both are rolling out.

## The plan

Ordered by what moves the most for the least. Strings change in `site/i18n/en.json` only; the
translations follow through the usual catalog sync, which the user runs. Every title stays under
about 60 characters and every description 120 to 160 (CLAUDE.md).

### Phase 1: say our own name (titles, descriptions, entity)

| Page | Now | Proposed |
|---|---|---|
| `/` title | TMPlayer: free, unofficial Telegram video player for Android TV and PC (73) | TMPlayer: unofficial Telegram media player for TV and PC (56) |
| `/` description | Stream the videos in your own Telegram chats on ... | TMPlayer is a free Telegram media player that streams the videos in your own chats on Android TV, phones, Windows, macOS and Linux. Open source, no ads. |
| `/` hero line | A free, unofficial Telegram video player that starts in seconds ... | TMPlayer is a free, unofficial Telegram media player that starts in seconds and downloads while you watch. |
| `/download/` title | Download TMPlayer for Android TV, Windows, macOS and Linux | Download TMPlayer APK for Android TV, Windows, Mac, Linux (57) |
| `/download/` description | Download TMPlayer free: the APK for ... | Download the free TMPlayer APK, a Telegram media player for Android TV and phones, plus a Windows installer, a Linux AppImage and a macOS preview. |
| `/android-tv/` title | TMPlayer: unofficial Telegram video player for Android TV and Google TV | Telegram on Android TV: watch your chat videos with TMPlayer (59) |
| `/fire-tv/` title | How to install TMPlayer on Fire TV Stick and Fire TV Cube | Telegram on Fire TV Stick: how to install TMPlayer (50) |

"Unofficial" stays in the home title, and "Telegram media player" is written in lowercase as a
description, never as a name (see Competitors: TMP - Telegram Media Player is another app).

Entity work, same phase (done 2026-10-15):

- JSON-LD on `/`: the `alternateName` sentence is removed, and no alternate name replaces it.
  The old sentence's `ld.*` key left every catalog (a removal, not a translation).
- `/download/` and `/android-tv/` get a SoftwareApplication of their own (they only carried a
  BreadcrumbList): name, `softwareVersion` from `releases.json`, `downloadUrl`,
  `operatingSystem`, `offers` at 0 (`site/src/lib/app-ld.ts`). No ratings: there are none to
  show, and made up ones are a manual action waiting to happen.
- The FAQ answers "Is TMPlayer the same app as TMP - Telegram Media Player?".
- One sentence for what TMPlayer is, used word for word in the README, `site.webmanifest` and
  `llms.txt`: "TMPlayer is a free, open source, unofficial Telegram media player that plays the
  videos in your own Telegram chats on Android TV, Fire TV, Android phones, Windows, macOS and
  Linux, while they are still downloading." The user puts the same sentence in the GitHub
  repository description and the Telegram channel bio.

Done when: CTR on `/download/` is above 10 per cent and "telegram player" moves from position 7.7
into the top 5, 28 days after the change.

### Phase 2: answer the questions people ask (content)

- **FAQ entries written as the question people type**, each answer complete in 40 to 180 words:
  "Can I watch Telegram videos on Android TV?", "Is there a Telegram app for Fire TV Stick?",
  "How do I play Telegram videos on a PC?", "Is TMPlayer free? Is there a paid version?",
  "Is TMPlayer safe? Does it see my messages?", "Does it play music and audio files?" (it plays
  MP3, FLAC and the rest inside video; say plainly what it does with audio-only files), "Is it
  the same as the official Telegram app?", "Can I use it without a phone?". Several come straight
  from the queries ("telegram para fire stick", "can i download telegram on tv", "alguna que no
  sea de pago", "telegram music player", "а для пк?"). FAQPage JSON-LD already exists on `/faq/`
  and picks them up.
- **A desktop page, `/desktop/`** ("Telegram video player for Windows, Mac and Linux"): desktop
  ranks 9 against mobile's 5, and the PC queries have nowhere to land but the home page. Keyboard
  shortcuts, mpv, the PowerShell one-liner, the MSI, the AppImage, the DMG. Localized like the
  other pages, in `lastmod.json`, `llms.txt` and the footer.
- **A comparison table in the existing guide `/guides/telegram-on-tv-without-casting/`** (not a
  new page, which would compete with it for the same queries): casting from the phone, screen
  mirroring, the regular Telegram Android app sideloaded on the TV, Telegram Web in the TV's
  browser, and TMPlayer, with what each needs and what breaks. Assistants lean on comparison
  tables, and it is where "alternative of this", "tvgramplayer" and "telegram to tv" readers end
  up. Facts only, no digs at other apps.
- **Home page: a "Telegram media player" section** that says in one paragraph what the long name
  means and what the app does on each device, so the phrase is in the page's text, not only its
  head.
- **Visible "Updated" dates** on the guides and FAQ (from `lastmod.json`), and `dateModified` in
  their JSON-LD, so freshness is visible to readers and machines.
- **Languages:** the data argues for Burmese (Myanmar: 166 impressions, the best CTR of any big
  country) and possibly Malay. Adding a language is the user's call and the user's sync; the site
  side is one entry in `LANGS`.

### Phase 3: calls to action (no pop-ups)

The site today: download is a plain nav link; four pages have no download button at all
(`/fire-tv/`, `/formats/`, the Fire TV guide, `/translate/`).

- **A "Download" button in the header**, styled as a button on every page, in every language
  (`common.download`), next to the language picker. The one change that reaches every visitor.
- **A closing download card** on the four pages without one, reusing the download band the guides
  have.
- **A slim sticky bar on phones**, shown after the visitor has scrolled past the hero, never on
  arrival: "Get TMPlayer for your TV" with one button, dismissible, the dismissal remembered like
  the notice bar's. A bar, not a modal, so it is not an intrusive interstitial.
- **After the download click, an inline panel on `/download/`** (not an overlay): the next three
  steps for the file just chosen (Downloader code `tmplayer.org/apk` for TV, the PowerShell line
  for Windows, "Open Anyway" for Mac), then "Join the Telegram channel for updates" and "Star it
  on GitHub". This is where a visitor is most willing to do one more thing, and it costs nothing
  in rankings.
- **Pop-ups: not recommended.** A modal on arrival or while scrolling is a Google demotion on
  mobile for the page that shows it, most of the traffic is mobile, and CLAUDE.md already rules
  out pop-ups and nags for sponsoring. Everything a pop-up would do is covered by the bar and the
  panel above.
- **Measure:** Vercel Web Analytics custom events on the header button, the bar, the download
  rows and the panel's links (if the plan allows events; otherwise outbound clicks), plus GitHub
  download counts (`scripts/download-counts.sh`).

### Phase 4: AI assistants and agents

- **robots.txt stays open to everyone**, and says so per bot for clarity: OAI-SearchBot,
  ChatGPT-User, GPTBot, Claude-SearchBot, Claude-User, ClaudeBot, PerplexityBot, Perplexity-User,
  Google-Extended, Applebot-Extended, Bingbot. Being in the training data is how an assistant
  knows TMPlayer without searching, so training bots stay allowed too (the user may decide
  otherwise; it is one line each).
- **Vercel's firewall must not challenge them.** During the cutover checks the Security Checkpoint
  started challenging scripted requests from one machine. If Bot Protection or Attack Challenge
  Mode is on, assistants fetching a page for a user (ChatGPT-User, Claude-User, Perplexity-User)
  can get the challenge page instead of the content. The user checks Firewall settings in the
  Vercel dashboard; verified search crawlers are exempt, user-triggered fetchers may not be.
- **Markdown twins.** An Astro endpoint writes `/index.md`, `/download/index.md` and so on for
  every English page: the page's own text, headings and tables, no navigation. Each HTML page
  links its twin with `<link rel="alternate" type="text/markdown">`, and `vercel.json` rewrites a
  request carrying `Accept: text/markdown` to the twin (a `has` header rule), with `Vary: Accept`.
  `scripts/site-links.py` checks every twin exists and answers 200 with `text/markdown`.
- **`llms.txt` and a new `llms-full.txt`.** `llms.txt` gains the long name and the short one,
  the one-sentence definition, the new pages, a "For agents" block (the stable machine addresses:
  `https://tmplayer.org/latest.json` for the newest version and its files, `/apk` for the
  Android APK, `install.ps1` for Windows, the RSS feed), and the eight FAQ answers above in
  short form. `llms-full.txt` is every English page's Markdown twin joined, built at deploy time
  so it never drifts.
- **IndexNow on every site change, not only releases.** A small job on pushes to `main` that touch
  `site/` pings IndexNow with the URLs whose content changed, so Bing (and through it ChatGPT
  search and Copilot) sees a change within hours.
- **Bing Webmaster Tools** is set up (data from 2026-10-09; the first four days: 34 impressions,
  3 clicks, about 9 per cent CTR, roughly a fortieth of Google's daily volume). The sitemap is
  submitted there (user, 2026-10-15). Read the AI Performance report every month next to Search
  Console.
- **Third-party presence** (user, ongoing): assistants cite what others say about you. Listings
  where people look for apps like this, all with the same sentence and link: AlternativeTo, the
  awesome-android-tv and awesome-telegram lists on GitHub, an XDA Developers thread, Reddit
  (r/AndroidTV, r/fireTV, r/Telegram) when someone asks, and the IzzyOnDroid or F-Droid listing
  if it ever happens. No paid placements, no fake reviews.

### Phase 5: measure and repeat

28 days after each phase ships: export Search Console again (same filters), compare position and
CTR for the queries above, Bing's AI Performance report, Vercel events, and GitHub downloads.
Keep what moved, revert what did not.

## What I need from the user

1. **Per-page queries**: in Search Console, Performance, click the page, then the Queries tab,
   and export, for `/`, `/download/`, `/install/` and `/android-tv/`. This says which page each
   query lands on, which the site-wide export cannot.
2. **Page indexing report** (Indexing, Pages): any "Crawled, currently not indexed", "Discovered,
   currently not indexed", "Duplicate without user-selected canonical" or "Alternate page with
   proper canonical" rows, exported. It shows whether the translated pages are indexed at all.
3. **The Vercel firewall settings** (Firewall, Bot Protection and Attack Challenge Mode: on or off).
4. **Bing**: the Queries and Pages tabs of Search Performance, and the AI Performance export once it has a month of data.
5. **Decisions** (user, 2026-10-15: "do everything that's best"): training bots stay allowed;
   smaller image copies for phones, originals kept; the header button, phone bar and download
   panel built as described; Claude translates the catalogs directly; no Google Play listing for
   now.

## Sources

- Google, [AI features and your website](https://developers.google.com/search/docs/appearance/ai-features)
- [Google's official guide to optimizing for generative AI features](https://gigazine.net/gsc_news/en/20260518-google-guide-optimizing-generative-ai) (May 2026)
- [Search Engine Journal on Google's AI Overviews and AI Mode advice](https://www.searchenginejournal.com/googles-official-advice-on-optimizing-for-ai-overviews-ai-mode/547341/)
- Vercel, [Make your documentation readable by AI agents](https://vercel.com/kb/guide/make-your-documentation-readable-by-ai-agents)
- [Serving Markdown to AI agents](https://flaviocopes.com/serving-markdown-to-ai-agents/), and [Lumar on Markdown twins](https://agentic-readiness.lumar.io/docs/content/markdown-twin)
- [AI crawler user agents in 2026](https://geo.tenten.co/en/blog/ai-crawler-user-agent-list-2026), [robots.txt for AI crawlers](https://specification.website/spec/agent-readiness/robots-for-ai-crawlers.md)
- [State of llms.txt 2026](https://presenc.ai/research/state-of-llms-txt-2026) (a vendor report)
- [Bing Webmaster Tools, IndexNow and the AI Performance report](https://geotoolbox.ai/blog/bing-webmaster-tools), [What 20,000 Copilot citations taught us](https://www.searchinfluence.com/blog/bing-ai-performance-report-copilot-citations/)
- [GEO best practices: what is actually proven in 2026](https://crowdo.net/blog/?p=2755)
- [Search Engine Journal on intrusive mobile pop-ups](https://www.searchenginejournal.com/google-devalue-pages-intrusive-pop-ups-mobile-search/171534/), [Guide to intrusive interstitials](https://seosly.com/blog/intrusive-interstitials/)

## Search Console review, export 2026-11-17 (28 days, 2026-10-19 to 2026-11-15) vs 2026-11-11 (2026-10-13 to 2026-11-09)
Files: `seo-data/tmplayer.org-Performance-on-Search-2026-11-17.zip` and `...-11-11.zip` (git-ignored).
- Totals: clicks 5,743 (was 5,051, +14%), impressions 32,048 (27,397, +17%), CTR 17.9% (18.4%), average position 5.71 (5.67).
  Mobile 84% of clicks. India 3,637 clicks (63%), then UAE 445, Saudi Arabia 148, Germany 106 (pos 6.5).
- Brand ("tmplayer", "tm player", "tmp player"): 3,915 of the 4,470 query clicks, position 1.0 to 1.2. Non-brand 555 clicks.
- Non-brand head, all steady: "telegram video player" 276 clicks (pos 2.1), "telegram player" 43 (4.4), "telegram media player" 19 (2.1),
  "telegram player for pc" 13 (2.3). The TV set ("telegram tv", "telegram android tv", "telegram for tv", "telegram tv app", "tv telegram",
  about 700 impressions together) sits at pos 6.5 to 7.5 with 3 to 4% CTR: the guides answer it, no new page.
- Pages: `/web/` clicks 44 to 72 (the web app), `/guides/telegram-on-samsung-or-lg-tv/` 10 to 19, `/download/` 276 to 314, `/desktop/` 65 to 84.
  `/features/` (2,044 impressions, 3 clicks, pos 1.6), `/android/`, `/install/`, `/android-tv/`: sitelink-style impressions under the brand result,
  no click problem to fix.
- New queries are all 1 to 7 impressions (AirPlay, "telegram en smart tv", Portuguese and Spanish TV how-tos): the cast guide, FAQ and
  llms.txt already say "No AirPlay"; the localised guides already cover the TV questions.
- "tmp app" (59 impressions, 0 clicks, pos 7.2), "tmp apk" (38, 7 clicks), "tmp download": the known short-name collision (see the
  ambiguity notes above). No page of ours should chase another product's name.
- Decision (Claude, 2026-11-17): no site change this round. Nothing in the export is a gap the site does not answer already, and padding
  pages would break "look before adding". Re-check after the next 28 days; watch: `/web/` growth, Spanish, Italian and Portuguese guide
  positions (6 to 9), "telegram videos" (pos 12.5).
