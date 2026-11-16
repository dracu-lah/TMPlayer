#!/usr/bin/env python3
"""Send the outreach emails in private/outreach-messages.json from a Gmail account, one at a time.

The address and the Gmail app password come from local.properties (gitignored), never from the
command line or the repository:

    OUTREACH_FROM=you@gmail.com
    OUTREACH_APP_PASSWORD=abcdabcdabcdabcd

    python3 private/outreach-send.py              # list the messages and whether each was sent
    python3 private/outreach-send.py --show ID    # print one message as it would go out
    python3 private/outreach-send.py --send ID... # send those, a pause between each

Each message is written for one outlet, so nothing goes out in bulk; a sent one is recorded in
private/outreach-sent.json and is not sent twice.
"""
import argparse
import json
import smtplib
import sys
import time
from email.message import EmailMessage
from email.utils import formataddr, formatdate, make_msgid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MESSAGES = ROOT / "private" / "outreach-messages.json"
SENT = ROOT / "private" / "outreach-sent.json"
PAUSE_SECONDS = 90


def properties():
    values = {}
    path = ROOT / "local.properties"
    if path.exists():
        for line in path.read_text().splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip()
    return values


def build(message, sender):
    mail = EmailMessage()
    mail["From"] = formataddr(("Nevil (TMPlayer)", sender))
    mail["To"] = message["to"]
    mail["Subject"] = message["subject"]
    mail["Date"] = formatdate(localtime=True)
    mail["Message-ID"] = make_msgid(domain=sender.split("@")[1])
    mail.set_content(message["body"])
    return mail


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--show")
    parser.add_argument("--send", nargs="+")
    args = parser.parse_args()

    messages = {m["id"]: m for m in json.loads(MESSAGES.read_text())}
    sent = json.loads(SENT.read_text()) if SENT.exists() else {}
    props = properties()
    sender = props.get("OUTREACH_FROM", "")

    if args.show:
        print(build(messages[args.show], sender or "you@example.com"))
        return
    if not args.send:
        for m in messages.values():
            print(f"{m['id']:16} {m['to']:32} {sent.get(m['id'], 'not sent')}")
        return

    password = props.get("OUTREACH_APP_PASSWORD", "").replace(" ", "")
    if not sender or not password:
        sys.exit("Put OUTREACH_FROM and OUTREACH_APP_PASSWORD in local.properties first.")
    unknown = [i for i in args.send if i not in messages]
    if unknown:
        sys.exit(f"Unknown ids: {', '.join(unknown)}")

    with smtplib.SMTP_SSL("smtp.gmail.com", 465) as smtp:
        smtp.login(sender, password)
        for n, mid in enumerate(args.send):
            if mid in sent:
                print(f"{mid}: already sent {sent[mid]}, skipped")
                continue
            if n:
                time.sleep(PAUSE_SECONDS)
            smtp.send_message(build(messages[mid], sender))
            sent[mid] = time.strftime("%Y-%m-%d %H:%M")
            SENT.write_text(json.dumps(sent, indent=2) + "\n")
            print(f"{mid}: sent to {messages[mid]['to']}")


if __name__ == "__main__":
    main()
