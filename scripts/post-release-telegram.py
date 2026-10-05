#!/usr/bin/env python3
"""Announces a release on the TMPlayer Telegram channel, through the release bot.

The release job runs it as its last step, and the same script posts by hand for a release that
went out before the job could, so every announcement has one format:

    TMPlayer X.Y.Z is out
    <the annotated tag's notes>
    Download, full release notes, and "installed copies offer each update"

    scripts/post-release-telegram.py --version 1.22.0 --notes "$NOTES"
    scripts/post-release-telegram.py --version 1.16.0 --notes "..." --dry-run

TELEGRAM_BOT_TOKEN and TELEGRAM_CHANNEL (an @handle or a chat id) come from the environment. With
no token the script says so and exits 0, so a fork's release is not failed by a missing secret.
"""

import argparse
import html
import json
import os
import sys
import urllib.parse
import urllib.request

REPO = "dracu-lah/TMPlayer"
DOWNLOAD = "https://tmplayer.org/download/"
# Telegram allows 4,096 characters in a message; the frame around the notes takes the rest.
NOTES_LIMIT = 3500


def message(version: str, notes: str) -> str:
    release = f"https://github.com/{REPO}/releases/tag/v{version}"
    parts = [f"<b>TMPlayer {html.escape(version)} is out</b> 🎉"]
    notes = notes.strip()
    if notes:
        if len(notes) > NOTES_LIMIT:
            notes = notes[:NOTES_LIMIT].rsplit(" ", 1)[0] + " …"
        parts.append(html.escape(notes))
    parts.append(
        f'⬇️ <a href="{DOWNLOAD}">Download</a>\n'
        f'📝 <a href="{release}">Full release notes</a>'
    )
    parts.append("Installed copies offer each update.")
    return "\n\n".join(parts)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--version", required=True, help="the version without the v, 1.22.0")
    parser.add_argument("--notes", default="", help="the tag's notes, blank for none")
    parser.add_argument("--dry-run", action="store_true", help="print the message, send nothing")
    args = parser.parse_args()

    text = message(args.version, args.notes)
    if args.dry_run:
        print(text)
        return 0

    token = os.environ.get("TELEGRAM_BOT_TOKEN", "")
    chat = os.environ.get("TELEGRAM_CHANNEL", "")
    if not token or not chat:
        print("No TELEGRAM_BOT_TOKEN or TELEGRAM_CHANNEL; not announcing.")
        return 0

    body = urllib.parse.urlencode({
        "chat_id": chat,
        "text": text,
        "parse_mode": "HTML",
        "disable_web_page_preview": "true",
    }).encode()
    request = urllib.request.Request(f"https://api.telegram.org/bot{token}/sendMessage", body)
    with urllib.request.urlopen(request, timeout=30) as response:
        reply = json.load(response)
    if not reply.get("ok"):
        print(f"Telegram refused the message: {reply.get('description')}", file=sys.stderr)
        return 1
    print(f"Announced {args.version} as message {reply['result']['message_id']}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
