#!/usr/bin/env python3
"""Draws the cards in site/public/social/ (the ones made for posting on Telegram, LinkedIn or X).

The card is an HTML page rendered by headless Chromium at 1200x630, the size every network crops
to. It is drawn here rather than by hand so it can be redrawn when the figures move: pass the
download count and it writes both the page and the PNG.

    python3 scripts/build-social-card.py --downloads 10000

It stays in the site's own language: Poppins, the site's blue, one corner radius, borders rather
than shadows, and the platform marks from site/src/icons (the same real marks the home page's
"Runs on" row uses, never redrawn). The screenshots are the site's own, so the card is dark: the
app's dark screens belong in a dark card instead of floating on white.
"""

import argparse
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent.parent
ICONS = ROOT / 'site' / 'src' / 'icons'
SHOTS = ROOT / 'site' / 'public' / 'screenshots'
OUT = ROOT / 'site' / 'public' / 'social'

# The home page's "Runs on" order (site/src/components/home/RunsOn.astro).
PLATFORMS = ['tv-card', 'firetv-stick', 'android', 'windows-panel', 'apple', 'linux', 'globe']


def icon(name: str) -> str:
    svg = (ICONS / f'{name}.svg').read_text().strip()
    return svg.replace('width="22" height="22"', 'width="24" height="24"')


def page(downloads: str, languages: int) -> str:
    tiles = '\n      '.join(f'<span class="tile">{icon(name)}</span>' for name in PLATFORMS)
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>TMPlayer: {downloads} downloads</title>
<style>
  @font-face {{ font-family: Poppins; src: url('../site/public/fonts/poppins-400.woff2') format('woff2'); font-weight: 400; }}
  @font-face {{ font-family: Poppins; src: url('../site/public/fonts/poppins-500.woff2') format('woff2'); font-weight: 500; }}
  @font-face {{ font-family: Poppins; src: url('../site/public/fonts/poppins-600.woff2') format('woff2'); font-weight: 600; }}
  @font-face {{ font-family: Poppins; src: url('../site/public/fonts/poppins-700.woff2') format('woff2'); font-weight: 700; }}
  * {{ margin: 0; padding: 0; box-sizing: border-box; }}
  body {{
    width: 1200px; height: 630px; overflow: hidden;
    font-family: Poppins, sans-serif; color: #f2f5fa;
    background: #0b0e14;
  }}
  .card {{ position: relative; width: 100%; height: 100%; overflow: hidden; }}
  /* Light behind the devices, in the brand's blue, so the cluster sits in the card. */
  .glow {{ position: absolute; border-radius: 50%; }}
  .glow-blue {{ width: 820px; height: 820px; top: -300px; right: -230px;
    background: radial-gradient(circle, rgba(26,109,255,0.30), rgba(26,109,255,0) 62%); }}
  .glow-warm {{ width: 620px; height: 620px; bottom: -330px; left: -180px;
    background: radial-gradient(circle, rgba(255,181,64,0.12), rgba(255,181,64,0) 64%); }}

  .left {{ position: absolute; top: 56px; left: 64px; width: 540px; }}
  .brand {{ display: flex; align-items: center; gap: 13px; }}
  .brand img {{ width: 44px; height: 44px; border-radius: 12px; }}
  .brand span {{ font-size: 26px; font-weight: 600; letter-spacing: -0.01em; }}

  .count {{ margin-top: 34px; font-size: 118px; line-height: 0.92; font-weight: 700;
    letter-spacing: -0.045em; color: #ffffff; }}
  .count-word {{ margin-top: 6px; font-size: 38px; font-weight: 600; letter-spacing: -0.01em;
    color: #4f93ff; }}
  .line {{ margin-top: 22px; font-size: 23px; font-weight: 400; line-height: 1.45; color: #a7b0c2;
    max-width: 480px; }}

  .tiles {{ display: flex; gap: 10px; margin-top: 34px; }}
  .tile {{ display: flex; align-items: center; justify-content: center; width: 50px; height: 50px;
    border: 1px solid rgba(255,255,255,0.14); border-radius: 16px; background: rgba(255,255,255,0.05);
    color: #dfe5ef; }}

  .foot {{ position: absolute; left: 64px; bottom: 46px; display: flex; align-items: baseline; gap: 14px;
    font-size: 21px; }}
  .foot b {{ font-weight: 600; color: #4f93ff; }}
  .foot span {{ font-weight: 400; color: #8e97a9; }}

  /* The devices: a television with the computer and the phone in front of it, the three screens
     the app runs on, each in the same 1px border and the same radius family. */
  .stage {{ position: absolute; top: 0; right: 0; width: 600px; height: 630px; }}
  /* A ring of the card's own background around each screen, so where they overlap one reads as in
     front of the other instead of the three merging into one dark mass. */
  .screen {{ position: absolute; border: 1px solid rgba(255,255,255,0.18); border-radius: 16px;
    overflow: hidden; background: #000; outline: 7px solid #0b0e14; }}
  .screen img {{ display: block; width: 100%; }}
  .tv {{ width: 470px; top: 74px; right: 64px; }}
  .desk {{ width: 322px; top: 366px; right: 212px; border-radius: 13px; }}
  .phone {{ width: 124px; top: 300px; right: 64px; border-radius: 20px; }}
</style>
</head>
<body>
<div class="card">
  <div class="glow glow-blue"></div>
  <div class="glow glow-warm"></div>

  <div class="stage">
    <div class="screen tv"><img src="../site/public/screenshots/tv-home-1280w.webp" alt=""></div>
    <div class="screen desk"><img src="../site/public/screenshots/desktop-grid-960w.webp" alt=""></div>
    <div class="screen phone"><img src="../site/public/screenshots/phone-show-360w.webp" alt=""></div>
  </div>

  <div class="left">
    <div class="brand">
      <img src="../site/public/icon-512.png" alt="">
      <span>TMPlayer</span>
    </div>
    <div class="count">{downloads}</div>
    <div class="count-word">downloads</div>
    <div class="line">Your own Telegram videos, on the TV, your phone, your computer and the browser.</div>
    <div class="tiles">
      {tiles}
    </div>
  </div>

  <div class="foot"><b>tmplayer.org</b> <span>Free and open source, in {languages} languages</span></div>
</div>
</body>
</html>
"""


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument('--downloads', default='10,000')
    ap.add_argument('--languages', type=int, default=22)
    ap.add_argument('--name', default='milestone-10k')
    args = ap.parse_args()

    html = ROOT / 'scripts' / f'social-card-{args.name}.html'
    html.write_text(page(args.downloads, args.languages))
    OUT.mkdir(parents=True, exist_ok=True)
    png = OUT / f'{args.name}.png'
    subprocess.run([
        'chromium-browser', '--headless=new', '--disable-gpu', '--no-sandbox', '--hide-scrollbars',
        '--allow-file-access-from-files', '--window-size=1200,630',
        f'--screenshot={png}', '--virtual-time-budget=6000', f'file://{html}',
    ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print(f'{png} from {html}')


if __name__ == '__main__':
    main()
