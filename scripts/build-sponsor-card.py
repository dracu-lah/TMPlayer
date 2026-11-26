#!/usr/bin/env python3
"""Draws the sponsor card in site/public/social/ (the portrait one posted on Telegram with the
Buy Me a Coffee link): light, a TV with three phones, "Still free. Still one person.", the three
stats and the coffee button.

    python3 scripts/build-sponsor-card.py --downloads 27.3K

The page is written to scripts/social-card-sponsor.html and rendered by headless Chromium at
1080x1350 (4:5, what Telegram shows without cropping). Poppins, the site's own light screenshots,
and Buy Me a Coffee's official cup from site/public/brand, never redrawn.
"""

import argparse
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / 'site' / 'public' / 'social'


def page(downloads: str, platforms: int, languages: int) -> str:
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>TMPlayer: still free, still one person</title>
<style>
  @font-face {{ font-family: Poppins; src: url('../site/public/fonts/poppins-400.woff2') format('woff2'); font-weight: 400; }}
  @font-face {{ font-family: Poppins; src: url('../site/public/fonts/poppins-500.woff2') format('woff2'); font-weight: 500; }}
  @font-face {{ font-family: Poppins; src: url('../site/public/fonts/poppins-600.woff2') format('woff2'); font-weight: 600; }}
  @font-face {{ font-family: Poppins; src: url('../site/public/fonts/poppins-700.woff2') format('woff2'); font-weight: 700; }}
  * {{ margin: 0; padding: 0; box-sizing: border-box; }}
  body {{ width: 1080px; height: 1350px; overflow: hidden; background: #f5f6f8; color: #1a1c20;
    font-family: Poppins, sans-serif; text-align: center; }}
  .brand {{ position: absolute; top: 58px; left: 0; right: 0; display: flex; align-items: center;
    justify-content: center; gap: 18px; }}
  .brand img {{ width: 66px; height: 66px; border-radius: 16px; }}
  .brand span {{ font-size: 38px; font-weight: 600; letter-spacing: -0.01em; }}

  /* The stage is the home page's hero (site/src/components/home/HeroStage.astro, styles.css
     "Hero stage"), same numbers: a box of 100:84 whose children are percentages of its width, the
     television at the back (8% in, 84% wide), the three phones standing on the floor, 24% wide,
     each turning about its own foot, the sides at -9 and 9 degrees and 0.9 scale, the middle one
     in front. The frames are the site's device shells. */
  .stage {{ position: absolute; top: 142px; left: 220px; width: 640px; aspect-ratio: 100 / 84; }}
  .tv {{ position: absolute; top: 0; left: 8%; width: 84%; padding-bottom: 16px; }}
  .tv .screen {{ aspect-ratio: 16 / 9; border: 4px solid #202429; border-bottom-width: 10.4px;
    border-radius: 6px; box-shadow: 0 0 0 1px rgba(15,23,42,0.24); overflow: hidden; background: #000; }}
  .tv .screen img {{ display: block; width: 100%; height: 100%; object-fit: cover; }}
  .tv .stand {{ position: absolute; bottom: 0; left: 50%; width: 11%; height: 22px;
    transform: translateX(-50%); background: #202429;
    clip-path: polygon(0 100%, 40% 0, 60% 0, 100% 100%, 82% 100%, 50% 30%, 18% 100%); }}
  .slot {{ position: absolute; bottom: 0; width: 24%; transform-origin: 50% 100%; }}
  .slot.l {{ left: 24.5%; rotate: -9deg; scale: 0.9; z-index: 1; }}
  .slot.r {{ left: 51.5%; rotate: 9deg; scale: 0.9; z-index: 1; }}
  .slot.c {{ left: 38%; z-index: 2; }}
  .phone {{ padding: 4px; background: #202429; border-radius: 18px;
    box-shadow: 0 0 0 1px rgba(15,23,42,0.24); }}
  .phone .screen {{ aspect-ratio: 9 / 20; border-radius: 14px; overflow: hidden; background: #000; }}
  .phone .screen img {{ display: block; width: 100%; height: 100%; object-fit: cover; }}

  h1 {{ position: absolute; top: 712px; left: 0; right: 0; font-size: 86px; line-height: 1.02;
    font-weight: 700; letter-spacing: -0.045em; }}
  h1 span {{ display: block; color: #6b6f78; }}
  .sub {{ position: absolute; top: 916px; left: 0; right: 0; font-size: 28px; line-height: 1.5;
    color: #6b6f78; }}
  .sub b {{ font-weight: 600; color: #1a1c20; }}

  .stats {{ position: absolute; top: 1032px; left: 90px; right: 90px; display: flex;
    justify-content: space-between; }}
  .stat {{ width: 280px; }}
  .stat b {{ display: block; font-size: 56px; line-height: 1.1; font-weight: 700;
    letter-spacing: -0.03em; }}
  .stat span {{ font-size: 26px; color: #6b6f78; }}

  .cta {{ position: absolute; top: 1146px; left: 84px; right: 84px; height: 112px;
    border-radius: 56px; background: #FFDD00; display: flex; align-items: center;
    justify-content: center; gap: 22px; font-size: 42px; font-weight: 700;
    letter-spacing: -0.01em; color: #0d0c22; }}
  .cta img {{ height: 52px; }}
  .note {{ position: absolute; top: 1280px; left: 0; right: 0; font-size: 22px; color: #6b6f78; }}
</style>
</head>
<body>
  <div class="brand"><img src="../site/public/icon-512.png" alt=""><span>TMPlayer</span></div>

  <div class="stage">
    <div class="tv"><div class="screen"><img src="../site/public/screenshots/tv-home-light.webp" alt=""></div><div class="stand"></div></div>
    <div class="slot l"><div class="phone"><div class="screen"><img src="../site/public/screenshots/phone-chats-light.webp" alt=""></div></div></div>
    <div class="slot r"><div class="phone"><div class="screen"><img src="../site/public/screenshots/phone-grid-light.webp" alt=""></div></div></div>
    <div class="slot c"><div class="phone"><div class="screen"><img src="../site/public/screenshots/phone-show-light.webp" alt=""></div></div></div>
  </div>

  <h1>Still free.<span>Still one person.</span></h1>
  <div class="sub">Your Telegram videos on TV, phone and computer.<br>If you love it, <b>a coffee keeps it going.</b></div>

  <div class="stats">
    <div class="stat"><b>{downloads}</b><span>downloads</span></div>
    <div class="stat"><b>{platforms}</b><span>platforms</span></div>
    <div class="stat"><b>{languages}</b><span>languages</span></div>
  </div>

  <div class="cta"><img src="../site/public/brand/bmc-cup-on-yellow.svg" alt="">buymeacoffee.com/nevil.dev</div>
  <div class="note">Completely optional. It unlocks nothing; TMPlayer stays free for everyone.</div>
</body>
</html>
"""


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument('--downloads', default='27.3K')
    ap.add_argument('--platforms', type=int, default=7)
    ap.add_argument('--languages', type=int, default=22)
    ap.add_argument('--name', default='sponsor')
    args = ap.parse_args()

    html = ROOT / 'scripts' / f'social-card-{args.name}.html'
    html.write_text(page(args.downloads, args.platforms, args.languages))
    OUT.mkdir(parents=True, exist_ok=True)
    png = OUT / f'{args.name}.png'
    subprocess.run([
        'chromium-browser', '--headless=new', '--disable-gpu', '--no-sandbox', '--hide-scrollbars',
        '--allow-file-access-from-files', '--window-size=1080,1350',
        f'--screenshot={png}', '--virtual-time-budget=6000', f'file://{html}',
    ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print(f'{png} from {html}')


if __name__ == '__main__':
    main()
