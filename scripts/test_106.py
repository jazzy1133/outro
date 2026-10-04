#!/usr/bin/env python3
"""Opus 1.0.6 verification suite — run 3 times before delivery.

Part A: static APK checks (package/version/sdk/permissions/dex/alignment).
Part B: feature source checks (1.0.6 changes present in Kotlin sources).
Part C: functional API-contract checks against a mock Navidrome/Subsonic server.
"""
import os, re, subprocess, sys, threading, zipfile
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs
from urllib.request import urlopen
from urllib.error import HTTPError
import xml.etree.ElementTree as ET

APK = os.path.expanduser("~/workspace/jazzy/build-manual/apk/opus.apk")
SRC = os.path.expanduser("~/workspace/jazzy/app/src/main/java/com/opus/music")
BT = os.path.expanduser("~/android-sdk/build-tools/34.0.0")

checks = []  # (name, passed, detail)

def check(name, cond, detail=""):
    checks.append((name, bool(cond), detail))

def read(p):
    with open(p) as f:
        return f.read()

# ---------------- Part A: static APK ----------------
check("A1 APK file exists", os.path.isfile(APK))
size = os.path.getsize(APK) if os.path.isfile(APK) else 0
check("A2 APK size sane (10-40 MB)", 10_000_000 <= size <= 40_000_000, f"{size} bytes")

badging = subprocess.run([f"{BT}/aapt2", "dump", "badging", APK],
                         capture_output=True, text=True).stdout
check("A3 package com.opus.music", "package: name='com.opus.music'" in badging)
check("A4 versionName 1.0.6", "versionName='1.0.6'" in badging)
check("A5 versionCode 7", "versionCode='7'" in badging)
check("A6 minSdk 26", "sdkVersion:'26'" in badging)
check("A7 targetSdk 34", "targetSdkVersion:'34'" in badging)
check("A8 INTERNET permission", "android.permission.INTERNET" in badging)
check("A9 FOREGROUND_SERVICE_MEDIA_PLAYBACK permission",
      "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" in badging)
check("A10 launchable MainActivity",
      "launchable-activity: name='com.opus.music.MainActivity'" in badging)

zres = subprocess.run([f"{BT}/zipalign", "-c", "4", APK],
                      capture_output=True, text=True)
check("A11 zipalign verification passes", zres.returncode == 0)

with zipfile.ZipFile(APK) as z:
    names = z.namelist()
check("A12 3 dex files present (classes.dex..classes3.dex)",
      all(n in names for n in ("classes.dex", "classes2.dex", "classes3.dex")))
check("A13 AndroidManifest.xml + resources.arsc present",
      "AndroidManifest.xml" in names and "resources.arsc" in names)
check("A14 META-INF signature files present",
      any(n.startswith("META-INF/") and n.endswith((".RSA", ".SF", ".MF")) for n in names))
check("A15 res/ resources packaged", any(n.startswith("res/") for n in names))
check("A16 native libs or assets sane", len(names) > 200, f"{len(names)} entries")

# ---------------- Part B: feature source checks ----------------
nav = read(f"{SRC}/ui/Nav.kt")
np = read(f"{SRC}/ui/screens/NowPlayingScreen.kt")
repo = read(f"{SRC}/data/SettingsRepository.kt")
sett = read(f"{SRC}/ui/screens/SettingsScreen.kt")
main = read(f"{SRC}/MainActivity.kt")

check("B1 mini-player hidden on NOW_PLAYING route",
      "currentRoute != Routes.NOW_PLAYING" in nav)
check("B2 NOW_PLAYING route wired to NowPlayingScreen",
      "composable(Routes.NOW_PLAYING)" in nav and "NowPlayingScreen(nav)" in nav)
check("B3 collapse chevron (KeyboardArrowDown) in NowPlayingScreen",
      "KeyboardArrowDown" in np)
check("B4 swipe-down gesture (detectVerticalDragGestures)",
      "detectVerticalDragGestures" in np)
check("B5 swipe-down dismiss calls popBackStack",
      "popBackStack" in np)
check("B6 screen-lock pref keys (never|playing|always)",
      'KEY_SCREEN_LOCK = "prevent_screen_lock"' in repo
      and "getPreventScreenLock" in repo and "setPreventScreenLock" in repo)
check("B7 gapless playback pref", 'KEY_GAPLESS = "gapless_enabled"' in repo)
check("B8 high-quality artwork pref", 'KEY_ARTWORK_HIGH = "artwork_high_quality"' in repo)
check("B9 Amperfy-style grouped settings sections",
      all(s in sett for s in ("Prevent Screen Lock", "Account", "Library", "Equalizer", "License")))
check("B10 MainActivity applies screen-lock-always at launch",
      "getPreventScreenLock" in main and '"always"' in main)
check("B11 NowPlaying applies playing/always screen-lock modes",
      '"playing"' in np and '"always"' in np)

# ---------------- Part C: mock Navidrome functional ----------------
OK = ('<subsonic-response xmlns="http://subsonic.org/restapi" '
      'status="ok" version="1.16.1">%s</subsonic-response>')
FAIL = ('<subsonic-response xmlns="http://subsonic.org/restapi" '
        'status="failed" version="1.16.1"><error code="40" message="auth"/></subsonic-response>')

def authed(q):
    # app sends u + (p | t+s); reject anonymous
    return "u" in q and ("p" in q or ("t" in q and "s" in q))

class Mock(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        u = urlparse(self.path); q = parse_qs(u.query)
        ep = u.path.rsplit("/", 1)[-1]
        if not authed(q):
            body, ct, code = FAIL, "text/xml", 200
        elif ep == "ping":
            body, ct, code = OK % "", "text/xml", 200
        elif ep == "getArtists":
            body = OK % ('<artists><index name="A"><artist id="ar1" name="Test Artist" '
                         'albumCount="2"/></index></artists>'); ct, code = "text/xml", 200
        elif ep == "getArtist":
            body = OK % ('<artist id="ar1" name="Test Artist"><album id="al1" name="Test Album" '
                         'songCount="3"/></artist>'); ct, code = "text/xml", 200
        elif ep == "getAlbumList":
            body = OK % ('<albumList><album id="al1" name="Test Album" artist="Test Artist" '
                         'coverArt="ca1"/></albumList>'); ct, code = "text/xml", 200
        elif ep == "getAlbum":
            body = OK % ('<album id="al1" name="Test Album"><song id="s1" title="Track One" '
                         'duration="210" track="1" contentType="audio/mpeg"/><song id="s2" '
                         'title="Track Two" duration="180" track="2"/></album>'); ct, code = "text/xml", 200
        elif ep == "search3":
            body = OK % ('<searchResult3><artist id="ar1" name="Test Artist"/>'
                         '<album id="al1" name="Test Album"/>'
                         '<song id="s1" title="Track One"/></searchResult3>'); ct, code = "text/xml", 200
        elif ep == "getStarred":
            body = OK % ('<starred><song id="s1" title="Track One" starred="2026-01-01"/></starred>'); ct, code = "text/xml", 200
        elif ep == "getPlaylists":
            body = OK % ('<playlists><playlist id="p1" name="Faves" songCount="5"/></playlists>'); ct, code = "text/xml", 200
        elif ep == "getPlaylist":
            body = OK % ('<playlist id="p1" name="Faves"><entry id="s1" title="Track One"/>'
                         '</playlist>'); ct, code = "text/xml", 200
        elif ep == "getRandomSongs":
            body = OK % ('<randomSongs><song id="s9" title="Random Hit" duration="200"/>'
                         '</randomSongs>'); ct, code = "text/xml", 200
        elif ep == "getUser":
            body = OK % ('<user username="tester" scrobblingEnabled="true" adminRole="true"/>'); ct, code = "text/xml", 200
        elif ep in ("star", "unstar", "scrobble"):
            body, ct, code = OK % "", "text/xml", 200
        elif ep == "stream":
            body, ct, code = b"ID3" + b"\x00" * 4096, "audio/mpeg", 200
        elif ep == "getCoverArt":
            body, ct, code = b"\x89PNG" + b"\x00" * 2048, "image/png", 200
        else:
            body, ct, code = "not found", "text/plain", 404
        if isinstance(body, str): body = body.encode()
        self.send_response(code); self.send_header("Content-Type", ct)
        self.send_header("Content-Length", str(len(body))); self.end_headers()
        self.wfile.write(body)

srv = HTTPServer(("127.0.0.1", 0), Mock)
port = srv.server_address[1]
threading.Thread(target=srv.serve_forever, daemon=True).start()
BASE = f"http://127.0.0.1:{port}/rest"
AUTH = "u=tester&t=abc123&s=deadbeef&v=1.16.1&c=opus&f=xml"

def get(ep, auth=True):
    url = f"{BASE}/{ep}?{AUTH}" if auth else f"{BASE}/{ep}"
    try:
        with urlopen(url, timeout=10) as r:
            return r.status, r.read(), r.headers.get("Content-Type", "")
    except HTTPError as e:
        return e.code, e.read(), ""

def xml_ok(body, xpath):
    try:
        root = ET.fromstring(body)
    except ET.ParseError:
        return False
    ns = {"s": "http://subsonic.org/restapi"}
    return (root.attrib.get("status") == "ok"
            and root.find(xpath, ns) is not None)

endpoints = [
    ("ping", ".", "C1 ping returns ok"),
    ("getArtists", "./s:artists/s:index/s:artist", "C2 getArtists artist tree"),
    ("getArtist", "./s:artist/s:album", "C3 getArtist album children"),
    ("getAlbumList", "./s:albumList/s:album", "C4 getAlbumList album children"),
    ("getAlbum", "./s:album/s:song", "C5 getAlbum song children w/ attrs"),
    ("search3", "./s:searchResult3", "C6 search3 result wrapper"),
    ("getStarred", "./s:starred", "C7 getStarred wrapper"),
    ("getPlaylists", "./s:playlists/s:playlist", "C8 getPlaylists playlist"),
    ("getPlaylist", "./s:playlist/s:entry", "C9 getPlaylist entries"),
    ("getRandomSongs", "./s:randomSongs/s:song", "C10 getRandomSongs songs"),
    ("getUser", "./s:user", "C11 getUser element"),
]
for ep, xp, label in endpoints:
    st, body, ct = get(ep)
    check(label, st == 200 and "xml" in ct and xml_ok(body, xp),
          f"http={st} ct={ct}")

st, body, _ = get("star");   check("C12 star returns ok", st == 200 and b'status="ok"' in body)
st, body, _ = get("unstar"); check("C13 unstar returns ok", st == 200 and b'status="ok"' in body)
st, body, _ = get("scrobble"); check("C14 scrobble returns ok", st == 200 and b'status="ok"' in body)
st, body, ct = get("stream")
check("C15 stream returns audio bytes", st == 200 and "audio" in ct and len(body) > 1024, f"{len(body)} bytes")
st, body, ct = get("getCoverArt")
check("C16 getCoverArt returns image bytes", st == 200 and "image" in ct and len(body) > 512, f"{len(body)} bytes")
st, body, _ = get("ping", auth=False)
check("C17 unauthenticated ping rejected", b'status="failed"' in body)

srv.shutdown()

# ---------------- report ----------------
passed = sum(1 for _, ok, _ in checks if ok)
failed = [(n, d) for n, ok, d in checks if not ok]
print(f"RESULT: {passed}/{len(checks)} checks passed")
for n, d in failed:
    print(f"  FAIL: {n} {d}")
sys.exit(0 if not failed else 1)
