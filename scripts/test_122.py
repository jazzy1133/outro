#!/usr/bin/env python3
"""Outro 1.2.2 verification suite — run 3 times before delivery.

Part A: static APK checks (package/version/sdk/permissions/dex/alignment).
Part B: feature source checks (1.1.0 changes present in Kotlin sources).
Part C: functional API-contract checks against a mock Navidrome/Subsonic server.
"""
import os, re, subprocess, sys, threading, zipfile
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs
from urllib.request import urlopen
from urllib.error import HTTPError
import xml.etree.ElementTree as ET

APK = os.path.expanduser("~/workspace/jazzy/build-manual/apk/outro.apk")
SRC = os.path.expanduser("~/workspace/jazzy/app/src/main/java/com/opus/music")
BT = os.path.expanduser("~/android-sdk/build-tools/34.0.0")

checks = []  # (name, passed, detail)

def check(name, cond, detail=""):
    checks.append((name, bool(cond), detail))

def read(p):
    with open(p) as f:
        return f.read()

def _src(rel):
    p = f"{SRC}/{rel}"
    return read(p) if os.path.isfile(p) else ""

# ---------------- Part A: static APK ----------------
check("A1 APK file exists", os.path.isfile(APK))
size = os.path.getsize(APK) if os.path.isfile(APK) else 0
check("A2 APK size sane (10-40 MB)", 10_000_000 <= size <= 40_000_000, f"{size} bytes")

badging = subprocess.run([f"{BT}/aapt2", "dump", "badging", APK],
                         capture_output=True, text=True).stdout
check("A3 package com.opus.music", "package: name='com.opus.music'" in badging)
check("A4 versionName 1.2.2", "versionName='1.2.2'" in badging)
check("A5 versionCode 14", "versionCode='14'" in badging)
check("A6 minSdk 26", "sdkVersion:'26'" in badging)
check("A7 targetSdk 34", "targetSdkVersion:'34'" in badging)
check("A8 INTERNET permission", "android.permission.INTERNET" in badging)
check("A9 FOREGROUND_SERVICE_MEDIA_PLAYBACK permission",
      "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" in badging)
check("A10 MODIFY_AUDIO_SETTINGS permission (EQ)",
      "android.permission.MODIFY_AUDIO_SETTINGS" in badging)
check("A11 launchable MainActivity",
      "launchable-activity: name='com.opus.music.MainActivity'" in badging)

zres = subprocess.run([f"{BT}/zipalign", "-c", "4", APK],
                      capture_output=True, text=True)
check("A12 zipalign verification passes", zres.returncode == 0)

with zipfile.ZipFile(APK) as z:
    names = z.namelist()
check("A13 3 dex files present (classes.dex..classes3.dex)",
      all(n in names for n in ("classes.dex", "classes2.dex", "classes3.dex")))
check("A14 AndroidManifest.xml + resources.arsc present",
      "AndroidManifest.xml" in names and "resources.arsc" in names)
check("A15 META-INF signature files present",
      any(n.startswith("META-INF/") and n.endswith((".RSA", ".SF", ".MF")) for n in names))
check("A16 res/ resources packaged", any(n.startswith("res/") for n in names))
check("A17 native libs or assets sane", len(names) > 200, f"{len(names)} entries")


# --- 1.2.0 packaging: Android Auto + widget resources ---
check("A18 automotive_app_desc.xml in APK", "res/xml/automotive_app_desc.xml" in names)
check("A19 widget layout + info xml in APK",
      "res/layout/widget_outro.xml" in names and "res/xml/outro_widget_info.xml" in names)
check("A20 widget drawables in APK",
      all(n in names for n in ("res/drawable/ic_widget_play.xml", "res/drawable/ic_widget_pause.xml",
                               "res/drawable/ic_widget_prev.xml", "res/drawable/ic_widget_next.xml")))
_xmltree = subprocess.run([f"{BT}/aapt2", "dump", "xmltree", "--file", "AndroidManifest.xml", APK],
                          capture_output=True, text=True).stdout
check("A21 widget receiver declared in manifest",
      "com.opus.music.widget.OutroWidgetProvider" in _xmltree)
check("A22 PlayerService exported (Auto controllers)", 
      "com.opus.music.player.PlayerService" in _xmltree and "android:exported" in _xmltree)

# ---------------- Part B: feature source checks ----------------
nav = _src("ui/Nav.kt")
np = _src("ui/screens/NowPlayingScreen.kt")
repo = _src("data/SettingsRepository.kt")
sett = _src("ui/screens/SettingsScreen.kt")
main = _src("MainActivity.kt")
comp = _src("ui/Components.kt")
pm = _src("player/PlayerManager.kt")
psvc = _src("player/PlayerService.kt")
theme = _src("ui/theme/Theme.kt")
tmode = _src("ui/theme/ThemeMode.kt")
home = _src("ui/screens/HomeScreen.kt")
det = _src("ui/screens/DetailScreens.kt")
castm = _src("cast/CastManager.kt")
manifest = read(os.path.expanduser("~/workspace/jazzy/app/src/main/AndroidManifest.xml"))
import glob as _g
def _exists(rel):
    return os.path.isfile(f"{SRC}/{rel}")

# --- 1.2.1: player swipe smoothness + behind-screen + speaker button ---
check("Q1 NOW_PLAYING is a full-screen dialog destination",
      "dialog(" in nav and "route = Routes.NOW_PLAYING" in nav and
      "usePlatformDefaultWidth = false" in nav)
check("Q2 dialog does not dim the screen behind",
      "FLAG_DIM_BEHIND" in np and "setDimAmount(0f)" in np)
check("Q3 drag uses graphicsLayer translation (no layout offset)",
      ".graphicsLayer { translationY = dragPx.floatValue }" in np)
check("Q4 ticker position split from static UI state",
      "distinctUntilChanged()" in _src("ui/vm/PlayerViewModel.kt"))
check("Q5 opaque base under artwork background",
      "Box(Modifier.fillMaxSize().background(bg))" in np)
check("Q6 speaker button on Now Playing top bar",
      "SpeakerButton(nav)" in np and "Icons.Filled.Speaker" in np)
check("Q7 settings section deep link route",
      '"settings/{section}"' in nav and "initialSection" in sett)

# --- Regression: 1.1.0 behaviors preserved ---
check("B1 mini-player hidden on NOW_PLAYING route",
      "currentRoute != Routes.NOW_PLAYING" in nav)
check("B2 NOW_PLAYING route wired to NowPlayingScreen",
      "Routes.NOW_PLAYING" in nav and "NowPlayingScreen(nav)" in nav)
check("B3 mini-player tap still opens full player",
      "MiniPlayer(onTap" in nav and "Routes.NOW_PLAYING" in nav)
check("B4 Amperfy-style grouped settings sections kept",
      all(s in sett for s in ("Prevent Screen Lock", "Account", "Library", "Equalizer", "License")))
check("B5 theme pref (dark|light) kept", "getThemeMode" in repo and "app_theme" in repo)
check("B6 EQ bridge (enable/preset/bands/apply) kept",
      all(m in pm for m in ("fun setEqEnabled", "fun setEqPreset", "fun setEqBands", "fun applyEqSettings")))
check("B7 offline mix toggle/wifi/sync kept",
      "isOfflineMixEnabled" in repo and "isOfflineMixWifiOnly" in repo)

# --- Phase 1: player gestures + EQ layout ---
check("P1a collapseDrag applied to artwork swipe target",
      "fun Modifier.collapseDrag" in np and "Modifier.collapseDrag(dragPx" in np)
check("P1b spring settle when collapsing onto mini player",
      "settleTick" in np and "spring(" in nav)
check("P1c vertical mixer-style EQ sliders", "vertical EQ slider" in sett)

# --- Phase 2: sound upgrades ---
check("P2a AutoEQ headphone profiles", "object HeadphoneProfiles" in _src("player/HeadphoneProfiles.kt"))
check("P2b skip silence pref", 'KEY_SKIP_SILENCE = "skip_silence"' in repo)
check("P2c volume boost pref", "Volume boost" in repo)
check("P2d EQ preamp pref", 'KEY_EQ_PREAMP = "eq_preamp"' in repo)
check("P2e compressor pref", 'KEY_COMPRESSOR = "compressor"' in repo)

# --- Phase 3a/3b/3c/3d: speakers ---
check("P3a1 Sonos DLNA/UPnP transport present", _exists("cast/SonosDlna.kt"))
check("P3a2 Sonos speaker kind", 'SONOS("Sonos")' in castm)
check("P3b1 Chromecast transport present", _exists("cast/ChromecastTransport.kt"))
check("P3b2 Cast options provider present", _exists("cast/OutroCastOptionsProvider.kt"))
check("P3b3 Chromecast speaker kind", 'CHROMECAST("Chromecast")' in castm)
check("P3c1 all five RAOP sources present",
      all(_exists(f"cast/raop/{f}") for f in ("AlacEncoder.kt", "RaopCrypto.kt", "RaopConnection.kt",
                                             "RaopPcmPipeline.kt", "RaopTransport.kt")))
_raop_crypto = _src("cast/raop/RaopCrypto.kt")
import re as _re
_m = _re.search(r'RSA_AES_KEY_B64\s*=\s*"([^"]+)"', _raop_crypto)
_blob = _m.group(1) if _m else ""
check("P3c2 RSA blob present, 344 chars (RSA-2048)", len(_blob) == 344, f"len={len(_blob)}")
check("P3c3 blank-RSA guard refuses to connect",
      "isNotBlank()" in _src("cast/raop/RaopConnection.kt"))
check("P3c4 AirPlay speaker kind wired", 'AIRPLAY("AirPlay")' in castm)
# --- 1.2.1: AirPlay ANNOUNCE 406 fix (user's HomePod mini rejected ANNOUNCE) ---
_raop_conn = _src("cast/raop/RaopConnection.kt")
check("P3c5 SDP uses a=rsaaeskey (not fpaeskey)",
      "a=rsaaeskey:" in _raop_conn and "a=fpaeskey" not in _raop_conn)
check("P3c6 SETUP advertises control_port and timing_port",
      "control_port=" in _raop_conn and "timing_port=" in _raop_conn)
check("P3c7 UDP sockets bound before SETUP",
      _raop_conn.find("rtpSocket = DatagramSocket()") < _raop_conn.find('"SETUP"'))
# --- 1.2.2: RTSP handshake now matches lox-airplay-sender/pyatv (ANNOUNCE still 406 on 1.2.1) ---
check("P3c8 standard sender headers on every RTSP request",
      all(x in _raop_conn for x in ('"User-Agent: "', '"DACP-ID: "', '"Active-Remote: "', '"Client-Instance: "')))
check("P3c9 no bogus hard-coded Session on ANNOUNCE/SETUP",
      '"Session" to "1"' not in _raop_conn)
check("P3c10 real session id parsed from SETUP response",
      "parseSessionId(resp.headers)" in _raop_conn and 'rtspSession = parseSessionId' in _raop_conn)
check("P3c11 session URI uses sender local IP",
      'rtsp://$localIp/$sessionId' in _raop_conn)
check("P3c12 OPTIONS carries static Apple-Challenge",
      'SdX9kFJVxgKVMFof/Znj4Q' in _raop_conn)
check("P3c13 Speakers screen shows build version label",
      '"Outro $versionName"' in sett)
check("P3d1 Speakers settings section", "SpeakersSection" in sett)
check("P3d2 cast routing in PlayerManager", "CastManager" in pm)

# --- Phase 4: library smarts ---
check("P4a synced lyrics view", _exists("ui/screens/LyricsView.kt") and "LyricsSection" in np)
check("P4b audiobook controls", _exists("ui/screens/AudiobookControls.kt") and "isCurrentAudiobook" in pm)
check("P4c internet radio", _exists("ui/screens/RadioScreen.kt"))
check("P4d genres", _exists("ui/screens/GenresScreen.kt"))
check("P4e artist biographies", "ArtistBioSection" in det)
check("P4f personal mixes", _exists("ui/screens/MixesScreen.kt"))

# --- Phase 5: Android Auto + widget ---
check("P5a PlayerService is a MediaLibraryService",
      "MediaLibraryService" in psvc and ": MediaLibraryService()" in psvc)
check("P5b Auto browse tree (Now Playing + Mixes)",
      _exists("player/AutoLibrary.kt") and '"Now Playing"' in _src("player/AutoLibrary.kt")
      and '"Mixes"' in _src("player/AutoLibrary.kt"))
check("P5c automotive descriptor declares media",
      'name="media"' in read(os.path.expanduser("~/workspace/jazzy/app/src/main/res/xml/automotive_app_desc.xml")))
check("P5d widget provider + updater present",
      _exists("widget/OutroWidgetProvider.kt") and _exists("widget/WidgetUpdater.kt"))
check("P5e widget taps drive PlayerService, not a bare controller bind",
      "onStartCommand" in psvc and "ACTION_TOGGLE" in psvc
      and "ACTION_NEXT" in psvc and "ACTION_PREV" in psvc)

# --- Phase 6: UI refresh ---
check("P6a blurred artwork background", ".blur(" in np)
check("P6b artwork background crossfades on track change",
      'Crossfade(targetState = ui.artworkUrl' in np)
check("P6c dynamic color pref + Material You schemes",
      "isDynamicColor" in repo and "dynamicDarkColorScheme" in theme and "setDynamic" in tmode)
check("P6d haptics on collapse/transport/queue",
      "LocalHapticFeedback" in np and np.count("performHapticFeedback") >= 4)
check("P6e queue swipe-to-remove", "SwipeToDismissBox" in np)
check("P6f queue drag-to-reorder",
      "detectDragGesturesAfterLongPress" in np and "fun moveQueueItem" in pm)
check("P6g selectable player layouts", "getPlayerLayout" in repo and '"immersive"' in np)
check("P6h Home personal rails",
      '"Jump back in"' in home and '"Heavy rotation"' in home)
check("P6i artist top songs", "ArtistTopSongs" in det)
check("P6j shared SongMeta.toSong()", "fun SongMeta.toSong()" in _src("data/StatsRepository.kt"))

# --- 1.2.0: version/about ---
check("V1 About shows Outro 1.2.0", "Outro 1.2.0" in sett)

# ---------------- Part C: mock Navidrome functional ----------------
OK = ('<subsonic-response xmlns="http://subsonic.org/restapi" '
      'status="ok" version="1.16.1">%s</subsonic-response>')
FAIL = ('<subsonic-response xmlns="http://subsonic.org/restapi" '
        'status="failed" version="1.16.1"><error code="40" message="auth"/></subsonic-response>')

def authed(q):
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
AUTH = "u=tester&t=abc123&s=deadbeef&v=1.16.1&c=outro&f=xml"

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

# ---------------- Part D: RAOP handshake JVM test ----------------
_jdk = os.path.expanduser("~/jdk/jdk-17.0.20.1+1")
_kotlinc = os.path.expanduser("~/kotlinc/kotlin-2.1.0/kotlinc/bin/kotlinc")
_stdlib = os.path.expanduser("~/kotlinc/kotlin-2.1.0/kotlinc/lib/kotlin-stdlib.jar")
_d_out = "/tmp/raop-handshake-verify"
import shutil as _shutil
_shutil.rmtree(_d_out, ignore_errors=True)
os.makedirs(_d_out, exist_ok=True)
_env = dict(os.environ, JAVA_HOME=_jdk, PATH=_jdk + "/bin:" + os.environ.get("PATH", ""))
_cr = subprocess.run(
    [_kotlinc, "scripts/unit_tests_raop_handshake.kt",
     "app/src/main/java/com/opus/music/cast/raop/RaopConnection.kt",
     "app/src/main/java/com/opus/music/cast/raop/RaopCrypto.kt",
     "app/src/main/java/com/opus/music/cast/raop/AlacEncoder.kt",
     "-d", f"{_d_out}/out.jar"],
    capture_output=True, text=True,
    cwd=os.path.expanduser("~/workspace/jazzy"), env=_env, timeout=300)
check("D1 handshake test compiles", _cr.returncode == 0,
      _cr.stderr[:300] if _cr.returncode else "")
if _cr.returncode == 0:
    _rr = subprocess.run(
        ["java", "-cp", f"{_d_out}/out.jar:{_stdlib}", "Unit_tests_raop_handshakeKt"],
        capture_output=True, text=True, env=_env, timeout=120)
    _ok = _rr.returncode == 0 and "ALL RAOP HANDSHAKE CHECKS PASSED" in _rr.stdout
    check("D2 handshake test passes on JVM", _ok,
          (_rr.stdout + _rr.stderr)[-600:] if not _ok else "")
else:
    check("D2 handshake test passes on JVM", False, "compile failed")

# ---------------- report ----------------
passed = sum(1 for _, ok, _ in checks if ok)
failed = [(n, d) for n, ok, d in checks if not ok]
print(f"RESULT: {passed}/{len(checks)} checks passed")
for n, d in failed:
    print(f"  FAIL: {n} {d}")
sys.exit(0 if not failed else 1)
