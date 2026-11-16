# Outreach batch 2 (researched 2026-10-20, nothing sent)

Drafts for every target with fit 4 or 5 and a published email are in `private/outreach-batch2.json`
(same keys as `outreach-messages.json`: `id`, `to`, `subject`, `body`). `outreach-send.py` reads only
`outreach-messages.json`, so either append these objects there once the other batch is done, or point
the script's `MESSAGES` path at this file for the run. Three good targets take a web form only; their
texts are at the bottom of this file and are not in the JSON.

Every address below was read on the outlet's own page (named in the "Contact found at" column).
Nothing was guessed or taken from an aggregator. Sizes are only given where the outlet shows them.

Each email: one reference to a specific piece of theirs, TMPlayer described as a player for the
videos already in your own Telegram chats, "unofficial, not affiliated with Telegram", one
tmplayer.org/legal/ link, the localized page and download page, an offer of screenshots or a demo
build, a sign-off as Nevil / Nevil Krishna K as developer, and a differently worded "reply no" line.
Checked by script: no em or en dashes, no exclamation marks, no movie/film/cinema words in any
language, no shared sentence between any two drafts, 17 distinct subjects.

Languages written natively: French, German, Italian, Spanish, Portuguese (Brazil), Indonesian,
Traditional Chinese. Written in plain English on purpose (and the email says so): Polish
(android.com.pl), Turkish (ShiftDelete), Arabic (AIT News).

## Send order (all today)

Leave 20 to 40 minutes between sends; the script already pauses 90 seconds within one run, but
batches of two or three spread across the day look less like a blast.

| # | id | Why this slot |
|---|---|---|
| 1 | korben | Fit 5, French, Telegram-tool coverage in June |
| 2 | caschy | Fit 4, German, tip address made for this |
| 3 | androidauthority | Fit 4, English, news@ is for news and press releases |
| 4 | tuttoandroid | Fit 4, Italian, marco@ is their news tip address |
| 5 | hipertextual | Fit 4, Spanish, prensa@ |
| 6 | techpp | Fit 4, India, tips@ |
| 7 | diolinux | Fit 4, Brazil, Linux audience |
| 8 | telefonino | Fit 4, Italian, Fire TV app list |
| 9 | maketecheasier | Fit 4, English, news tips address |
| 10 | gadgetjunction | Fit 4, India, Android TV focused |
| 11 | briian | Fit 4, Taiwan, free software blog |
| 12 | gadgetren | Fit 4, Indonesia |
| 13 | androidcompl | Fit 4, Poland |
| 14 | shiftdelete | Fit 4, Turkey |
| 15 | aitnews | Fit 4, Arabic |
| 16 | thelinuxexp | Fit 4, English Linux YouTube |
| 17 | xatakahome | Fit 4, Spanish. Last on purpose: SEO-PLAN.md says to send it after the Xataka Android correction (same Webedia group). If that correction is not going today, hold this one for tomorrow. |

Form targets, by hand, any time today: Liliputing, SmartWorld. LinuxLinks is on hold (see below).

## Targets

Fit: 5 = exact audience and a recent piece on the same problem, 4 = clear audience match,
3 = plausible but weak hook or general contact only (no draft), 1 to 2 = off-topic.

| Name | Type | Language / country | URL | Size | Recent piece that makes it a fit | Contact (where found) | Fit |
|---|---|---|---|---|---|---|---|
| Korben | blog + newsletter | French, France | https://korben.info/ | not shown | "Telegram Drive, stockage illimité" (2026-06-12) https://korben.info/telegram-drive-stockage-illimite.html | korben@korben.info (korben.info/contact/, given for "Présenter un outil ou un projet") | 5 |
| Caschys Blog | blog | German, Germany | https://stadt-bremerhaven.de/ | not shown (media kit exists) | "Telegram: Native App mittlerweile auch für Wear OS verfügbar" (2026-06-12) https://stadt-bremerhaven.de/telegram-native-app-mittlerweile-auch-fuer-wear-os-verfuegbar/ | gruenkohl.chiffre.01@icloud.com ("Lieber per E-Mail?" on stadt-bremerhaven.de/tipp-senden/) | 4 |
| Android Authority | blog + YouTube | English, US/global | https://www.androidauthority.com/ | not shown | "This iconic ad-free Android TV launcher is finally getting a makeover" (late Sept 2026) https://www.androidauthority.com/android-tv-sideload-launcher-3716555/ ; also "11 great open-source alternatives... (2026 guide)" | news@androidauthority.com ("Leaks, press releases, and news", /contact/) | 4 |
| TuttoAndroid | blog | Italian, Italy | https://www.tuttoandroid.net/ | not shown | "Un launcher per Google TV che si ispira ad Apple TV: ecco Arc Launcher" (2026-04-13) https://www.tuttoandroid.net/news/2026/04/13/arc-launcher-google-tv-apple-tv-open-source-1148607/ | marco@tuttoandroid.net ("Per segnalarci una notizia", /contatti/; no guest posts or paid links) | 4 |
| Telefonino.net | blog | Italian, Italy | https://www.telefonino.net/ | not shown | "Migliori app gratuite per Fire TV Stick: cosa installare subito" (2026-02-04) https://www.telefonino.net/notizie/migliori-app-gratuite-per-fire-tv-stick-cosa-installare-subito/ | redazione@telefonino.net (/contatti/) | 4 |
| SmartWorld | blog | Italian, Italy | https://www.smartworld.it/ | not shown | "6 app gratuite che trasformano il Fire TV Stick in qualcosa di molto più potente" (Vincenzo Ronca, 2026-09-22) https://www.smartworld.it/news/migliori-app-fire-tv-stick.html | form only, reason "Segnalazione notizie / Tip news" (smartworld.it/contatti) | 4 (form) |
| Hipertextual | blog | Spanish, Spain/LatAm | https://hipertextual.com/ | not shown | "Esta alternativa a Android TV es más rápida, no tiene anuncios y se parece a Apple TV" (2026-05-03) https://hipertextual.com/tecnologia/arc-launcher-alternativa-android-tv/ | prensa@hipertextual.com ("Envío de notas de prensa", /contacto) | 4 |
| Xataka Smart Home | blog | Spanish, Spain | https://www.xatakahome.com/ | not shown | "Telegram planta cara WhatsApp y añade una función brutal..." (Jose Antonio Carmona, 2025-03-12; site active daily) https://www.xatakahome.com/centro-multimedia/telegram-planta-cara-whatsapp-anade-funcion-brutal-puedes-ver-videos-fotos-que-te-mandan-tele | contacto@xatakahome.com (/quienes-somos; SEO-PLAN's prensa@ was not on the page today) | 4 |
| Diolinux | blog + YouTube | Portuguese, Brazil | https://diolinux.com.br/ | not checked | "Nextcloud: é possível substituir o Google Workspace por uma solução open source?" (video, 2026-10-18) https://diolinux.com.br/video/nextcloud-google-workspace.html | blogdiolinux@gmail.com (the organisation email in diolinux.com.br/contato's own structured data) | 4 |
| android.com.pl | blog | Polish, Poland | https://android.com.pl/ | not shown | "Android TV vs. Google TV vs. Tizen. Co wybrać i dlaczego" (2026-04-06) https://android.com.pl/tech/1032311-android-tv-vs-google-tv-vs-tizen/ | press@android.com.pl (/kontakt/) | 4 |
| TechPP | blog | English, India | https://techpp.com/ | not shown | "Arc Launcher Fixes Everything Wrong With Google TV" (2026-04-25) https://techpp.com/2026/04/25/arc-launcher-for-google-tv/ | tips@techpp.com ("news tips or scoops", /contact/) | 4 |
| Gadget Junction | blog | English, India | https://gadgetjunction.in/ | not shown | "12 Smartphone Apps You Can Install On Android TV" (2025-06-03; posts daily, latest 2026-10-15) https://gadgetjunction.in/smartphone-apps-on-android-tv/ | contactus@gadgetjunction.in (/contact-us/) | 4 |
| Make Tech Easier | blog | English, global | https://maketecheasier.com/ | not shown | "Turn Your Media Library Into TV Channels With This Open-Source Tool" (2026-01-16) https://maketecheasier.com/ersatztv-turn-media-library-into-tv-channels/ | news@maketecheasier.com ("News tips", /contact/; asks for a primary source) | 4 |
| Gadgetren | blog | Indonesian, Indonesia | https://gadgetren.com/ | not shown | "Cara Nonton Video Bareng di Telegram Pakai Share Screen" (2024-05-29; site active) https://gadgetren.com/2024/05/29/nonton-video-bareng-telegram-274840/ | redaksi(at)gadgetren.com (/kontak/) | 4 |
| 重灌狂人 (Briian) | blog | Traditional Chinese, Taiwan | https://briian.com/ | not shown | "Screen Off 安卓手機螢幕鎖，點一下鎖定" (2026-06-14) https://briian.com/88742/screen-off.html ; keeps a Telegram download page (2024-12-05) | briiancom@gmail.com (briian.com/about/) | 4 |
| ShiftDelete.Net | blog + YouTube | Turkish, Turkey | https://shiftdelete.net/ | not shown | "Telegram'a Kişiselleştirilmiş Haber Akışı Özelliği Geliyor" (2026-10-18) https://shiftdelete.net/telegram-kisisellestirilmis-haber-akisi | info@shiftdelete.net (/iletisim) | 4 |
| AIT News (البوابة العربية للأخبار التقنية) | blog | Arabic, Saudi Arabia / region | https://aitnews.com/ | not shown | Telegram live streams in Stories (2025-11-20; site active 2026-10-14) https://aitnews.com/2025/11/20/تيليجرام-تطلق-تحديثًا-جديدًا-يتيح-بثًا/ | editorial@aitnews.com ("لإرسال بيان صحفي", /contact-us/) | 4 |
| The Linux Experiment | YouTube + podcast | English, global | https://thelinuxexp.com/ | 340K+ subscribers (per linuxiac.com) | "Linux & Open Source News" episode of 2026-07-17 (egos in open source projects, 432 kernel CVEs) | t.linuxexperiment@gmail.com ("Contact me" on thelinuxexp.com) | 4 |
| Liliputing | blog | English, US | https://liliputing.com/ | not shown | "F-Droid 2.0" in "Linux On Snapdragon, Pine 64, and F-Droid" roundup (2026-09-25) https://liliputing.com/linux-on-snapdragon-pine-64-and-f-droid-liliputing-news-roundup/ | form only, "send a news tip" (liliputing.com/contact/) | 4 (form) |
| LinuxLinks | blog | English, UK | https://www.linuxlinks.com/ | not shown | ongoing open source app reviews; asks for suggestions | form at linuxlinks.com/suggest-open-source-program/ | 4 (form, on hold) |
| 電腦王阿達 (kocpc) | blog + YouTube | Traditional Chinese, Taiwan | https://www.kocpc.com.tw/ | 150K YouTube, 460K Facebook (its own about page) | general daily tech news (2026-10-20); last Telegram-related piece "T-Drive" is from 2022 | koc@kocpc.com.tw (/contact) | 3 |
| Spider's Web | blog | Polish, Poland | https://spidersweb.pl/ | not shown | no recent Android TV or Telegram piece found (last Android TV tag 2025-05) | news@spidersweb.pl (/kontakt) | 3 |
| GenK | blog | Vietnamese, Vietnam | https://genk.vn/ | not shown | general tech news, no Telegram on TV piece found | info@genk.vn (footer, general) | 3 |
| Cord Cutters News | blog + YouTube | English, US | https://cordcuttersnews.com/ | not shown | "Top 10 Hidden Fire TV App Every Fire TV Owner Should Try In 2026" | form only (/contact-us/) | 3 (legal cord cutting, but their audience wants services, not Telegram; new Fire TV sticks cannot sideload) |
| 9to5Linux | blog | English, global | https://9to5linux.com/ | not shown | release news for big Linux apps and distros | form only (/contact) | 3 |
| LinuxNews.de | blog | German, Germany | https://linuxnews.de/ | not shown | "Softwareperlen" section | form only (/kontakt/) | 3 |
| Webtekno | blog + YouTube | Turkish, Turkey | https://www.webtekno.com/ | not shown | phone to TV mirroring guide (older) | form only (/iletisim, "İçerik Departmanı") | 3 |
| Lifehacker.ru | blog | Russian, Russia | https://lifehacker.ru/ | not shown | app reviews in general | ask@lifehacker.ru (/about/, but only for expert columns) | 3, hold |
| Genbeta | blog | Spanish, Spain | https://www.genbeta.com/ | not shown | last open source app list found is from 2023 | contacto@genbeta.com (/quienes-somos) | 3 |
| TechRadar | blog | English, UK/US | https://www.techradar.com/ | not shown | no fitting piece found | news@techradar.com (homepage) | 2 |
| GIGAZINE | blog | Japanese, Japan | https://gigazine.net/ | not shown | covers open source tools often | JS-only tip forms (/news/contact2/ to /contact4/) | 2 |

## Excluded, and why

| Candidate | Reason |
|---|---|
| Androidphoria (Spanish) | Has the best-matching article ("EchoGram: la mejor app de Telegram para Android TV"), but the same site publishes "RBTV77 APK", "Telelatino APK... pelis y series", "canales de Telegram para descargar APK de fútbol gratis" and "canales para ver vídeos filtrados en Telegram". Piracy-adjacent. |
| El Grupo Informático (Spanish) | SEO-PLAN's next-in-line pick, but its streaming section is built around IPTV ("9 mejores apps de IPTV para tu Smart TV", a TiviMate install guide). Too close to the IPTV rule. |
| JalanTikus (Indonesian) | Long history of "nonton film gratis" style lists; the only address (info@) is unlabelled and hidden behind obfuscation. |
| Gadgets To Use (India) | Its Telegram coverage is about getting around channel save restrictions ("Download Restricted Telegram Channel Videos"); TMPlayer keeps Telegram's restrictions on. The only email is for "General Queries and Advertising". |
| TROYPOINT, Firestick Tricks, Tech Doctor UK, Kodi build channels, most Hindi and Malayalam "Android TV box apps" YouTube channels | IPTV, Kodi builds or free streaming apps. |
| Hipertextual's "Nuvio, la alternativa a Stremio" piece | Not excluded as an outlet, but do not reference that article: Stremio addons are piracy-adjacent. The draft references the Arc Launcher piece instead. |
| Telefonino's "Centinaia di canali di pirateria streaming scoperti su Telegram" | Same: the outlet is fine, the draft points at the Fire TV app list, never at this article. |
| AndroidInsider.ru (Russian) | Only an advertising address (advert@i10.ru) is published. |
| Guiding Tech (English) | Only an advertising address (pr@guidingtechmedia.com); otherwise a form. |
| Ausdroid (Australia) | The domain now serves an online casino site. |
| ITC.ua, Mezha, AIN (Ukrainian), Tech Shohor (Bengali), Quantrimang (Vietnamese) | Contact pages blocked our fetch (403/400), so no address could be read from the outlet itself. Worth a manual look in a browser. |
| Russian mainstream press | Russia put Pavel Durov on its terror list in July 2026 and outlets there are wary of Telegram pieces; Trashbox (self-publish, already in SEO-PLAN) remains the Russian route. |
| Persian outlets (Zoomit, Digiato) | Telegram is filtered in Iran; already skipped in SEO-PLAN. |
| Mainland Chinese outlets (sspai etc.) | Telegram is blocked in mainland China. |
| Korean and Japanese outlets | Telegram has little use there; no fitting recent piece found. Japanese only reachable via GIGAZINE's script forms. |
| Malayalam and Bengali creators | No creator found who is both on topic, clearly non-piracy, and publishes a business address on their own page. A Malayalam angle exists (the developer is in Kerala), so this is worth a manual search on YouTube About pages. |
| The Streamable, Ghacks, How-To Geek, Android Headlines, MobileSyrup | No editorial address published (form only, ads only, or a Softonic staff address). |

## Form texts (send by hand)

**Liliputing** (liliputing.com/contact/, English):

> Hi Brad, your F-Droid 2.0 note in the 25 September roundup is the reason for this tip. TMPlayer is
> a GPL-3.0 app for the videos already in your own Telegram chats, on Android TV, Fire TV (Fire OS 7
> or later), Android phones, Windows, Linux (AppImage, AUR) and macOS. Unofficial, not affiliated
> with Telegram. https://tmplayer.org/ , download https://tmplayer.org/download/ , source
> https://github.com/dracu-lah/TMPlayer , legal https://tmplayer.org/legal/ . Screenshots or a demo
> build on request. Nevil Krishna K, developer (https://nevil.dev). If it is not for you, a "no" and
> I will leave it there.

**SmartWorld** (smartworld.it/contatti, reason "Segnalazione notizie / Tip news", Italian):

> Buongiorno, ho letto «6 app gratuite che trasformano il Fire TV Stick in qualcosa di molto più
> potente» di Vincenzo Ronca. Propongo un'app da sideload che ci starebbe bene: TMPlayer, un lettore
> open source per i video già presenti nelle proprie chat di Telegram, con accesso tramite codice QR
> e interfaccia in italiano. Su Fire TV con Fire OS 7 o successivo si installa scrivendo
> tmplayer.org/apk in Downloader. App non ufficiale, non affiliata a Telegram.
> https://tmplayer.org/it/fire-tv/ , download https://tmplayer.org/it/download/ , note legali
> https://tmplayer.org/legal/ . Mando volentieri screenshot. Nevil Krishna K, sviluppatore. Basta un
> "no" per non ricevere altro.

**LinuxLinks** (on hold): the form asks whether generative AI was substantially used and, if yes,
requires a public disclosure link (README or docs). The README has no such statement today. Decide
what to disclose first; a submission without it "will not be considered".

## Notes

- Fire TV wording everywhere says Fire OS 7 or later; the new Vega OS sticks cannot sideload.
- Localized pages used: fr, de, it, es, pt, pl, tr, ar, id, zh-tw, hi, all answered 200 today.
- If any outlet adds TMPlayer, note the date in SEO-PLAN.md (that file was not touched here).
