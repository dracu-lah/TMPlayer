#!/usr/bin/env python3
"""Capture what a UPnP / DLNA MediaRenderer says, for TMPlayer's test fixtures (HX40).

Read only by default: it sends an SSDP M-SEARCH, fetches each renderer's description and service
documents, and asks the questions a control point asks (GetProtocolInfo, GetTransportInfo,
GetPositionInfo, GetMediaInfo, GetCurrentTransportActions, GetVolume, GetMute, GetDeviceCapabilities),
plus a few that must fail (so the UPnP fault shape is on file). With --play URL it also loads and
plays that URL, seeks, polls and stops, which changes what the renderer is showing: say so to
whoever owns the TV first.

Everything goes to one folder per renderer, with names, serials, MACs, UUIDs and addresses
replaced by stable placeholders (--no-scrub keeps them). Zip the folder and send it.

  python3 scripts/dlna/capture.py --iface 192.168.1.20 --out capture
  python3 scripts/dlna/capture.py --iface 192.168.1.20 --out capture --play http://192.168.1.20:8000/a.mp4

Standard library only; Python 3.8 or newer. Nothing leaves this machine.
"""
import argparse
import hashlib
import os
import re
import socket
import sys
import time
import urllib.parse
import urllib.request
from xml.etree import ElementTree as ET

SSDP_ADDR = ("239.255.255.250", 1900)
SEARCH_TARGETS = ["urn:schemas-upnp-org:device:MediaRenderer:1", "ssdp:all"]
MAX_BODY = 512 * 1024


def msearch(iface, target, wait):
    """Send M-SEARCH from iface three times; return (raw reply text, sender address) pairs."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(iface))
    sock.bind((iface, 0))
    msg = (
        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n"
        f"MX: 2\r\nST: {target}\r\nUSER-AGENT: TMPlayer-capture/1.0\r\n\r\n"
    ).encode()
    for _ in range(3):
        sock.sendto(msg, SSDP_ADDR)
        time.sleep(0.3)
    sock.settimeout(0.5)
    end = time.time() + wait
    out = []
    while time.time() < end:
        try:
            data, addr = sock.recvfrom(8192)
        except socket.timeout:
            continue
        out.append((data.decode("utf-8", "replace"), addr[0]))
    sock.close()
    return out


def listen_notify(iface, seconds):
    """Collect NOTIFY datagrams for a while (needs port 1900 shareable)."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
    except (AttributeError, OSError):
        pass
    try:
        sock.bind(("", 1900))
        mreq = socket.inet_aton("239.255.255.250") + socket.inet_aton(iface)
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
    except OSError as e:
        print(f"  (NOTIFY listening skipped: {e})")
        return []
    sock.settimeout(0.5)
    end = time.time() + seconds
    out = []
    while time.time() < end:
        try:
            data, addr = sock.recvfrom(8192)
        except socket.timeout:
            continue
        text = data.decode("utf-8", "replace")
        if text.startswith("NOTIFY"):
            out.append((text, addr[0]))
    sock.close()
    return out


def header(text, name):
    m = re.search(rf"^{name}:\s*(.+?)\s*$", text, re.I | re.M)
    return m.group(1) if m else None


def http(method, url, body=None, headers=None, timeout=5):
    """Return (raw response text: status line, headers, blank line, body). Never raises."""
    req = urllib.request.Request(url, data=body, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            status, reason, hdrs, data = r.status, r.reason, r.headers, r.read(MAX_BODY)
    except urllib.error.HTTPError as e:
        status, reason, hdrs, data = e.code, e.reason, e.headers, e.read(MAX_BODY)
    except Exception as e:  # timeouts, refused, DNS
        return f"FAILED {type(e).__name__}: {e}\n"
    head = "".join(f"{k}: {v}\n" for k, v in hdrs.items())
    return f"HTTP {status} {reason}\n{head}\n{data.decode('utf-8', 'replace')}"


def envelope(service_type, action, args):
    inner = "".join(f"<{k}>{xml_escape(v)}</{k}>" for k, v in args)
    return (
        '<?xml version="1.0" encoding="utf-8"?>'
        '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
        's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
        f'<u:{action} xmlns:u="{service_type}">{inner}</u:{action}></s:Body></s:Envelope>'
    )


def xml_escape(s):
    return str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def soap(control_url, service_type, action, args):
    body = envelope(service_type, action, args)
    resp = http(
        "POST",
        control_url,
        body.encode(),
        {
            "Content-Type": 'text/xml; charset="utf-8"',
            "SOAPAction": f'"{service_type}#{action}"',
            "User-Agent": "TMPlayer-capture/1.0",
        },
    )
    return body, resp


def local(tag):
    return tag.rsplit("}", 1)[-1]


def parse_description(xml_text, location):
    """Return (friendly name, [(serviceType, controlURL absolute, SCPDURL absolute)])."""
    root = ET.fromstring(xml_text)
    base = location
    for el in root.iter():
        if local(el.tag) == "URLBase" and (el.text or "").strip():
            base = el.text.strip()
    services = []
    name = ""
    for el in root.iter():
        t = local(el.tag)
        if t == "friendlyName" and not name:
            name = (el.text or "").strip()
        if t == "service":
            f = {local(c.tag): (c.text or "").strip() for c in el}
            if f.get("serviceType") and f.get("controlURL"):
                services.append(
                    (
                        f["serviceType"],
                        urllib.parse.urljoin(base, f["controlURL"]),
                        urllib.parse.urljoin(base, f.get("SCPDURL", "")) if f.get("SCPDURL") else "",
                    )
                )
    return name, services


class Scrubber:
    """Stable placeholders so two runs of the same TV agree and nothing personal is kept."""

    def __init__(self, enabled, iface):
        self.enabled = enabled
        self.map = {}
        self.names = []
        self.iface = iface

    def learn(self, xml_text):
        if not self.enabled:
            return
        for tag in ("friendlyName", "serialNumber", "UDN", "presentationURL"):
            for m in re.finditer(rf"<{tag}>([^<]+)</{tag}>", xml_text):
                self.names.append(m.group(1))

    def alias(self, kind, value):
        key = (kind, value)
        if key not in self.map:
            n = sum(1 for k in self.map if k[0] == kind) + 1
            self.map[key] = f"{kind}{n}"
        return self.map[key]

    def __call__(self, text):
        if not self.enabled:
            return text
        for v in sorted(set(self.names), key=len, reverse=True):
            if v and len(v) > 2:
                if v.startswith("uuid:"):
                    text = text.replace(v, "uuid:00000000-0000-0000-0000-000000000001")
                else:
                    text = text.replace(v, "SCRUBBED-" + hashlib.sha1(v.encode()).hexdigest()[:6])
        text = re.sub(
            r"uuid:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
            "uuid:00000000-0000-0000-0000-000000000001",
            text,
        )
        # Any other UUID (the 01-NLS header, a time based one, carries the machine's MAC).
        text = re.sub(
            r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b",
            "00000000-0000-0000-0000-000000000002",
            text,
        )
        # The OS and kernel in a Server header.
        text = re.sub(r"(Linux|Windows|Darwin|FreeBSD)/[^\s,;]+", r"\1/0", text)
        text = re.sub(r"(?<![0-9A-Fa-f])([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f])", "00:00:00:00:00:00", text)
        text = re.sub(
            r"\b(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b",
            lambda m: self._ip(m.group(0)),
            text,
        )
        return text

    def _ip(self, ip):
        if ip in ("239.255.255.250", "0.0.0.0", "127.0.0.1", "1.0.0.0", "1.1.0.0"):
            return ip
        # Keep the shape (private /24) but not the network: 192.0.2.x is the documentation range.
        host = int(ip.split(".")[-1]) % 256
        return f"192.0.2.{host}"


def save(folder, name, text, scrub):
    os.makedirs(os.path.dirname(os.path.join(folder, name)), exist_ok=True)
    with open(os.path.join(folder, name), "w", encoding="utf-8", newline="\n") as f:
        f.write(scrub(text))


def capture_renderer(location, replies, notifies, out_root, iface, play_url, scrub_on):
    desc = http("GET", location)
    body = desc.split("\n\n", 1)[1] if "\n\n" in desc else ""
    if not desc.startswith("HTTP 200") or not body.strip():
        print(f"  {location}: description not readable, skipped")
        return None
    scrub = Scrubber(scrub_on, iface)
    scrub.learn(body)
    try:
        name, services = parse_description(body, location)
    except ET.ParseError as e:
        print(f"  {location}: description is not XML ({e}), kept raw only")
        name, services = "", []
    slug = re.sub(r"[^a-z0-9]+", "-", (name or "renderer").lower()).strip("-")[:24] or "renderer"
    if scrub_on:
        slug = "renderer-" + hashlib.sha1(location.encode()).hexdigest()[:6]
    folder = os.path.join(out_root, slug)
    print(f"  {name or location} -> {folder}")
    save(folder, "ssdp-replies.txt", "\n----\n".join(r for r, _ in replies), scrub)
    if notifies:
        save(folder, "ssdp-notify.txt", "\n----\n".join(r for r, _ in notifies[:40]), scrub)
    save(folder, "description.http", desc, scrub)
    av = rc = cm = None
    for stype, control, scpd in services:
        short = stype.split(":service:")[-1].split(":")[0] if ":service:" in stype else stype
        if scpd:
            save(folder, f"scpd-{short}.http", http("GET", scpd), scrub)
        if "AVTransport" in stype:
            av = (stype, control)
        elif "RenderingControl" in stype:
            rc = (stype, control)
        elif "ConnectionManager" in stype:
            cm = (stype, control)

    def call(service, action, args=(), label=None):
        if not service:
            return None
        req, resp = soap(service[1], service[0], action, list(args))
        label = label or action
        save(folder, f"soap/{label}.request.xml", req, scrub)
        save(folder, f"soap/{label}.response.http", resp, scrub)
        status = resp.split("\n", 1)[0]
        print(f"    {label}: {status}")
        return resp

    call(cm, "GetProtocolInfo")
    call(cm, "GetCurrentConnectionIDs")
    call(cm, "GetCurrentConnectionInfo", [("ConnectionID", "0")])
    call(av, "GetTransportInfo", [("InstanceID", "0")])
    call(av, "GetPositionInfo", [("InstanceID", "0")])
    call(av, "GetMediaInfo", [("InstanceID", "0")])
    call(av, "GetCurrentTransportActions", [("InstanceID", "0")])
    call(av, "GetDeviceCapabilities", [("InstanceID", "0")])
    call(av, "GetTransportSettings", [("InstanceID", "0")])
    call(rc, "GetVolume", [("InstanceID", "0"), ("Channel", "Master")])
    call(rc, "GetMute", [("InstanceID", "0"), ("Channel", "Master")])
    # Faults on purpose: an action that does not exist, and a bad argument.
    call(av, "NoSuchAction", [("InstanceID", "0")], "Fault-NoSuchAction")
    call(av, "Seek", [("InstanceID", "0"), ("Unit", "REL_TIME"), ("Target", "not-a-time")], "Fault-SeekBadTarget")
    call(av, "SetAVTransportURI", [("InstanceID", "9"), ("CurrentURI", ""), ("CurrentURIMetaData", "")], "Fault-BadInstance")

    if play_url and av:
        mime = "video/mp4"
        didl = (
            '<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" '
            'xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">'
            '<item id="1" parentID="0" restricted="1"><dc:title>TMPlayer capture</dc:title>'
            "<upnp:class>object.item.videoItem</upnp:class>"
            f'<res protocolInfo="http-get:*:{mime}:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000">'
            f"{xml_escape(play_url)}</res></item></DIDL-Lite>"
        )
        call(av, "SetAVTransportURI", [("InstanceID", "0"), ("CurrentURI", play_url), ("CurrentURIMetaData", didl)])
        call(av, "GetTransportInfo", [("InstanceID", "0")], "AfterLoad-GetTransportInfo")
        call(av, "Play", [("InstanceID", "0"), ("Speed", "1")])
        time.sleep(3)
        call(av, "GetTransportInfo", [("InstanceID", "0")], "Playing-GetTransportInfo")
        call(av, "GetPositionInfo", [("InstanceID", "0")], "Playing-GetPositionInfo")
        call(av, "GetCurrentTransportActions", [("InstanceID", "0")], "Playing-GetCurrentTransportActions")
        call(av, "Seek", [("InstanceID", "0"), ("Unit", "REL_TIME"), ("Target", "00:00:05")])
        time.sleep(2)
        call(av, "GetPositionInfo", [("InstanceID", "0")], "AfterSeek-GetPositionInfo")
        call(av, "Pause", [("InstanceID", "0")])
        time.sleep(1)
        call(av, "GetTransportInfo", [("InstanceID", "0")], "Paused-GetTransportInfo")
        call(av, "GetCurrentTransportActions", [("InstanceID", "0")], "Paused-GetCurrentTransportActions")
        call(av, "Stop", [("InstanceID", "0")])
        time.sleep(1)
        call(av, "GetTransportInfo", [("InstanceID", "0")], "Stopped-GetTransportInfo")
        call(av, "GetPositionInfo", [("InstanceID", "0")], "Stopped-GetPositionInfo")
        call(av, "Play", [("InstanceID", "0"), ("Speed", "1")], "Fault-PlayAfterStop")
        call(av, "Stop", [("InstanceID", "0")], "StopAgain")
        # A URL that answers 404 (a file that is gone) and a type the renderer cannot know.
        call(av, "SetAVTransportURI", [("InstanceID", "0"), ("CurrentURI", play_url + ".missing"), ("CurrentURIMetaData", "")], "Fault-LoadMissing")
        call(av, "Play", [("InstanceID", "0"), ("Speed", "1")], "Fault-PlayMissing")
        time.sleep(2)
        call(av, "GetTransportInfo", [("InstanceID", "0")], "Missing-GetTransportInfo")
        odd = didl.replace("video/mp4", "video/x-bogus")
        call(av, "SetAVTransportURI", [("InstanceID", "0"), ("CurrentURI", play_url), ("CurrentURIMetaData", odd)], "Fault-LoadBogusMime")
        call(av, "Stop", [("InstanceID", "0")], "FinalStop")
    return folder


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--iface", required=True, help="this machine's LAN address, e.g. 192.168.1.20")
    ap.add_argument("--out", default="dlna-capture", help="folder to write (default dlna-capture)")
    ap.add_argument("--wait", type=float, default=4.0, help="seconds to collect M-SEARCH replies")
    ap.add_argument("--notify", type=float, default=0.0, help="seconds to also listen for NOTIFY")
    ap.add_argument("--play", metavar="URL", help="also load and play this URL (changes what the TV shows)")
    ap.add_argument("--only", metavar="ADDR", help="only the renderer at this address")
    ap.add_argument("--no-scrub", action="store_true", help="keep names, UUIDs, MACs and addresses")
    a = ap.parse_args()

    found = {}
    for target in SEARCH_TARGETS[:1]:
        for text, addr in msearch(a.iface, target, a.wait):
            loc = header(text, "LOCATION")
            if loc:
                found.setdefault(loc, []).append((text, addr))
    notifies = listen_notify(a.iface, a.notify) if a.notify else []
    if a.notify:  # the renderer may have restarted on another port while we listened
        found = {}
        for text, addr in msearch(a.iface, SEARCH_TARGETS[0], a.wait):
            loc = header(text, "LOCATION")
            if loc:
                found.setdefault(loc, []).append((text, addr))
    if a.only:
        found = {k: v for k, v in found.items() if urllib.parse.urlparse(k).hostname == a.only}
    if not found:
        print("No MediaRenderer answered. Same Wi-Fi? Guest network or AP isolation? Firewall on this machine?")
        return 1
    print(f"{len(found)} renderer(s):")
    for loc, replies in found.items():
        host = urllib.parse.urlparse(loc).hostname
        mine = [n for n in notifies if n[1] == host]
        capture_renderer(loc, replies, mine, a.out, a.iface, a.play, not a.no_scrub)
    return 0


if __name__ == "__main__":
    sys.exit(main())
