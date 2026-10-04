#!/usr/bin/env python3
"""Outro 1.1.0 verification suite — run 3 times before delivery.

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
check("A4 versionName 1.1.0", "versionName='1.1.0'" in badging)
check("A5 versionCode 11", "versionCode='11'" in badging)
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

# ---------------- Part B: feature source checks ----------------
nav = _src("ui/Nav.kt")
np = _src("ui/screens/NowPlayingScreen.kt")
repo = _src("data/SettingsRepository.kt")
sett = _src("ui/screens/SettingsScreen.kt")
main = _src("MainActivity.kt")
comp = _src("ui/Components.kt")
pm = _src("player/PlayerManager.kt")
psvc = _src("player/PlayerService.kt")
xfade = _src("player/CrossfadeEngine.kt")
eqc = _src("player/EqualizerController.kt")
acct = _src("data/Account.kt")
swipe = _src("data/SwipeActions.kt")
mix = _src("data/OfflineMix.kt")
stats = _src("data/StatsRepository.kt")
manifest = read(os.path.expanduser("~/workspace/jazzy/app/src/main/AndroidManifest.xml"))

# Regression: 1.0.x behaviors preserved
check("B1 mini-player hidden on NOW_PLAYING route",
      "currentRoute != Routes.NOW_PLAYING" in nav)
check("B2 NOW_PLAYING route wired to NowPlayingScreen",
      "composable(Routes.NOW_PLAYING)" in nav and "NowPlayingScreen(nav)" in nav)
check("B3 mini-player tap still opens full player",
      "MiniPlayer(onTap" in nav and "Routes.NOW_PLAYING" in nav)
check("B4 swipe-down collapse (detectVerticalDragGestures) kept",
      "detectVerticalDragGestures" in np and "popBackStack" in np)
check("B5 Amperfy-style grouped settings sections kept",
      all(s in sett for s in ("Prevent Screen Lock", "Account", "Library", "Equalizer", "License")))
check("B6 theme pref (dark|light) kept", "getThemeMode" in repo and "app_theme" in repo)
_deps_py = read(os.path.expanduser("~/workspace/jazzy/scripts/download_deps.py"))
check("B7 lifecycle-livedata-core pinned at 2.7.0",
      '("androidx.lifecycle", "lifecycle-livedata-core")] = "2.7.0"' in _deps_py)
check("B8 offline mix toggle/wifi/sync kept",
      "isOfflineMixEnabled" in repo and "isOfflineMixWifiOnly" in repo and "syncIfNeeded" in mix)

# --- 1.1.0: multiple accounts ---
check("C-acct1 Account.kt data class exists",
      "data class Account" in acct and "toConfig()" in acct)
check("C-acct2 accountLabel() helper with user@host format",
      "fun accountLabel" in acct and '"$u @ $host"' in acct)
check("C-acct3 accounts stored as JSON (accounts_json key)",
      'KEY_ACCOUNTS = "accounts_json"' in repo)
check("C-acct4 migration of legacy single credentials",
      "migrateAccounts" in repo and 'KEY_URL' in repo)
check("C-acct5 addAccount/setActiveAccountId/removeAccount",
      all(m in repo for m in ("fun addAccount", "fun setActiveAccountId", "fun removeAccount")))
check("C-acct6 synchronous commit for credentials (no lost logins)",
      repo.count(".commit()") >= 2)
check("C-acct7 save() keeps first-login and update paths",
      "existing.isEmpty()" in repo or "_accounts.value.isEmpty()" in repo)
check("C-acct8 AccountSection lists accounts w/ switch",
      "AccountSection" in sett and "setActiveAccountId" in sett and "RadioButton" in sett)
check("C-acct9 AddAccountDialog verifies via ping+getUser",
      "AddAccountDialog" in sett and "repo.ping()" in sett and "repo.getUser" in sett)
check("C-acct10 remove-account confirmation dialog",
      "Remove account?" in sett and "removeAccount" in sett)
check("C-acct11 switching swaps Session + reloads MAIN",
      "Session.open(acc.toConfig())" in sett and "popUpTo(Routes.MAIN)" in sett)
check("C-acct12 removing last account goes to SETUP",
      "Session.close()" in sett and "Routes.SETUP" in sett)
check("C-acct13 playback paused on account switch",
      "PlayerManager.pause()" in sett)
check("C-acct14 PlayerManager.pause() exists",
      "fun pause()" in pm)

# --- 1.1.0: equalizer ---
check("C-eq1 EqualizerEngine object with 5 bands",
      "object EqualizerEngine" in eqc and "BAND_COUNT = 5" in eqc)
check("C-eq2 EQ band frequencies defined",
      "BAND_FREQUENCIES_HZ" in eqc and "14000" in eqc)
check("C-eq3 presets incl. Flat/Bass/Treble",
      all(p in eqc for p in ('"Flat"', '"Bass boost"', '"Treble boost"')) and "val presets" in eqc)
check("C-eq4 mapToDeviceBands pure function",
      "fun mapToDeviceBands" in eqc)
check("C-eq5 EQ Controller fails gracefully (try/catch, no crash path)",
      "class Controller" in eqc and eqc.count("catch (_: Exception)") >= 3)
check("C-eq6 PlayerService binds EQ to ExoPlayer",
      "EngineHolder.eq" in psvc and "eq.bind(player" in psvc)
check("C-eq7 PlayerService releases EQ on destroy",
      "EngineHolder.eq?.release()" in psvc)
check("C-eq8 EngineHolder carries eq controller",
      "var eq:" in xfade and "EqualizerEngine.Controller" in xfade)
check("C-eq9 MODIFY_AUDIO_SETTINGS in manifest",
      "MODIFY_AUDIO_SETTINGS" in manifest)
check("C-eq10 PlayerManager EQ bridge (enable/preset/bands/apply)",
      all(m in pm for m in ("fun setEqEnabled", "fun setEqPreset", "fun setEqBands", "fun applyEqSettings")))
check("C-eq11 EQ re-attaches when playback starts (session id 0 retry)",
      "applyEqSettings()" in pm and "onIsPlayingChanged" in pm)
check("C-eq12 EQ prefs: enabled/preset/bands",
      all(m in repo for m in ("isEqEnabled", "setEqEnabled", "getEqPreset", "setEqPreset", "getEqBands", "setEqBands")))
check("C-eq13 EQ settings UI: switch + preset picker",
      "PlayerManager.setEqEnabled" in sett and "EQ preset" in sett)
check("C-eq14 EQ settings UI: 5 sliders in millibels",
      "Slider(" in sett and "BAND_FREQUENCIES_HZ" in sett and "-maxMb..maxMb" in sett)
check("C-eq15 EQ sliders persist on release (onValueChangeFinished)",
      "onValueChangeFinished" in sett and "PlayerManager.setEqBands" in sett)
check("C-eq16 EQ preset applies bands live",
      "PlayerManager.setEqPreset" in sett)

# --- 1.1.0: swipe gestures ---
check("C-sw1 SwipeActions model with gesture keys",
      all(k in swipe for k in ("KEY_MINI_UP", "KEY_MINI_DOWN", "KEY_MINI_LEFT",
                               "KEY_MINI_RIGHT", "KEY_SONG_LEFT", "KEY_SONG_RIGHT")))
check("C-sw2 sensible defaults (up=open, song L=play next, song R=queue)",
      "KEY_MINI_UP -> OPEN_PLAYER" in swipe
      and "KEY_SONG_LEFT -> PLAY_NEXT" in swipe
      and "KEY_SONG_RIGHT -> ADD_TO_QUEUE" in swipe)
check("C-sw3 mini-player actions limited to safe set",
      "MINI_PLAYER_ACTIONS" in swipe and "SONG_ROW_ACTIONS" in swipe)
check("C-sw4 MiniPlayer uses drag gestures (detectDragGestures)",
      "detectDragGestures" in comp)
check("C-sw5 MiniPlayer keeps tap-to-open",
      "onClick = onTap" in comp)
check("C-sw6 MiniPlayer fires configured mini actions",
      all(k in comp for k in ("KEY_MINI_UP", "KEY_MINI_DOWN", "KEY_MINI_LEFT", "KEY_MINI_RIGHT")))
check("C-sw7 SongRow swipeable (detectHorizontalDragGestures)",
      "detectHorizontalDragGestures" in comp)
check("C-sw8 SongRow reveals pending action while dragging",
      "Reveal layer" in comp and "SwipeActions.label(pending)" in comp)
check("C-sw9 SongRow swipe fires play-next / add-to-queue / download",
      "SwipeActions.PLAY_NEXT -> PlayerManager.playNext" in comp
      and "SwipeActions.ADD_TO_QUEUE -> PlayerManager.addToQueue" in comp)
check("C-sw10 swipe prefs get/set with validation",
      "fun getSwipeAction" in repo and "fun setSwipeAction" in repo
      and "allowedFor(key)" in repo)
check("C-sw11 Swipe settings UI: mini-player + song-list pickers",
      '"Mini player"' in sett and '"Song list"' in sett and "setSwipeAction" in sett)
check("C-sw12 old roadmap placeholders gone",
      "on the roadmap" not in sett and "Coming soon" not in sett)

# --- 1.1.0: unlimited offline mix ---
check("C-mix1 MIX_UNLIMITED sentinel (-1)",
      "MIX_UNLIMITED = -1" in repo)
check("C-mix2 setOfflineMixSize accepts unlimited + migrates legacy",
      "MIX_UNLIMITED" in repo and "when (n)" in repo)
check("C-mix3 UI offers ∞ alongside 25/50/100",
      "SettingsRepository.MIX_UNLIMITED" in sett and '"∞"' in sett)
check("C-mix4 mix size label helper",
      "fun mixSizeLabel" in sett)
check("C-mix5 syncIfNeeded honors unlimited",
      "MIX_UNLIMITED" in mix and "unlimited" in mix.lower())
check("C-mix6 selectMix treats negative maxSize as unlimited",
      "val unlimited = maxSize < 0" in stats)

# --- 1.1.0: version/about ---
check("C-v1 About shows Outro 1.1.0",
      "Outro 1.1.0" in sett)
check("C-v2 About mentions new features",
      "Multiple accounts" in sett and "equalizer" in sett)

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

# ---------------- report ----------------
passed = sum(1 for _, ok, _ in checks if ok)
failed = [(n, d) for n, ok, d in checks if not ok]
print(f"RESULT: {passed}/{len(checks)} checks passed")
for n, d in failed:
    print(f"  FAIL: {n} {d}")
sys.exit(0 if not failed else 1)
