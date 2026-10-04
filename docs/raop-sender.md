# RAOP (AirPlay 1) Sender — implementation research report

Date: 2026-09-23. Goal: pure-JVM (Kotlin, no JNI) RAOP **sender** to stream audio TO a HomePod.
Reference code fetched to `/tmp/raop-candidates/` (license notes in `/tmp/raop-candidates/LICENSES.md`).

---

## 1. Vendorizable JVM RAOP sender library? — VERDICT: none exists

Searched GitHub/Maven for a pure-Java/Kotlin RAOP *sender* (client that ANNOUNCEs and
streams RTP *to* a speaker). Result: **nothing vendorizable exists.**

| Candidate | Lang | Direction | License | Verdict |
|---|---|---|---|---|
| serezhka/java-airplay, java-airplay-lib | Java | **receiver** (pretends to be Apple TV) | — | wrong direction |
| **lox-airplay-sender** (jdnarvaez) | TypeScript/Node | sender (RAOP/AirPlay1 + AP2 auth) | **AGPL-3.0** declared in package.json (no LICENSE file) | NOT vendorizable; best protocol reference |
| **pyatv** (postlund) | Python/asyncio | sender (RAOP path) | **MIT** | vendorizable *as reference*, not JVM drop-in |
| OwnTone (owntone-server) | C | sender | GPL-2.0 | NOT vendorizable; reference only |
| node-libraop / libraop | C/Node | sender+receiver | — | not JVM |
| shairport (abrasive) | C | **receiver** | — | wrong direction; used only to extract the public RSA key |

**Recommendation:** clean-room Kotlin implementation from the byte-level spec below
(~600–800 lines: RTSP client, 3 UDP sockets, AES-CBC, mDNS via Android NsdManager).
No JNI needed — `javax.crypto` handles AES-128-CBC and (if wanted) RSA-OAEP.

### Which sender path to implement: CLASSIC encrypted RAOP (no pairing)

Two sender paths exist:

- **Path A — classic RAOP (RECOMMENDED):** ANNOUNCE carries `a=rsaaeskey:` (AES key
  RSA-OAEP-encrypted with the well-known leaked AirPort Express public key) +
  `a=aesiv:`. RTP payloads AES-128-CBC-encrypted. **No HomeKit pairing, no user
  ceremony.** This is what AirMusic / doubleTwist use — and the user already streams
  to his HomePod from Android today via exactly this kind of app. HomePods accept it.
- **Path B — pair-verified (pyatv's approach):** HomeKit pair-verify first, then
  ANNOUNCE *without* crypto lines, plaintext RTP (`a=rtpmap:96 L16/44100/2`).
  Requires the sender to hold HomeKit pairing credentials for the HomePod
  (i.e. a pairing ceremony with the 8-digit Home app code). Worse UX; skip it.

This report specifies **Path A**.

---

## 2. mDNS discovery

- Service type: **`_raop._tcp`** (all RAOP receivers: AirPort Express, Apple TV, HomePod, shairport, …).
- Instance name format: **`<device-id>@<display-name>`**, e.g. `AA:BB:CC:DD:EE:FF@Kitchen HomePod`.
  The display name is everything after the first `@`. (On Android, `NsdManager`
  gives you the service name; split at `@`.)
- **Port** comes from the SRV record — typically **5000** for RAOP. That port is the
  RTSP TCP port. Connect the RTSP socket there.
- TXT record keys (from shairport's advertiser, `mdns.h`; Apple devices add more):
  `tp=UDP sm=false ek=1 et=0,1 cn=0,1 ch=2 ss=16 sr=44100 vn=3 txtvers=1 da=true md=0,1,2 pw=true|false`
  plus on real Apple hardware: `am=` (model), `fv=` (firmware), `vs=`, `ft=`/`features=`
  (feature bitmask), `sf=`/`flags=` (status), `pk=` (AirPlay 2 pairing key), `ov=`.
- Keys that matter for us:
  - `et=0,1` — encryption types offered; `1` = RSA → our Path A works.
  - `cn=0,1` (or `0,1,2,3`) — codecs; `1` = ALAC.
  - `pw=false` — no password. If `pw=true`, do the Digest flow (§9).
  - `am=` — model id. HomePods (confirmed via pyatv's device table):
    `AudioAccessory1,1` / `AudioAccessory1,2` = HomePod gen 1,
    `AudioAccessory5,1` = HomePod mini,
    `AudioAccessory6,1` = HomePod gen 2.
- Android: `NsdManager.discoverServices("_raop._tcp", NsdManager.PROTOCOL_DNS_SD, …)`.
  Needs `CHANGE_WIFI_MULTICAST_STATE` (already in our manifest for Sonos).

---

## 3. RTSP handshake as the sender — exact sequence and bytes

TCP connect to `<receiver-ip>:<raop-port>`. All requests are ASCII, lines end `\r\n`,
blank line (`\r\n`) terminates headers. **CSeq starts at 0 or 1 and increments per
request; the server echoes it — match responses by CSeq.**

Common headers on every request (both lox-airplay-sender and pyatv agree):
```
CSeq: <n>
User-Agent: iTunes/11.3.1 (Windows; Microsoft Windows 10 x64 (Build 19044); x64) (dt:2)
DACP-ID: <16 uppercase hex chars, random per session>
Active-Remote: <random uint32>
Client-Instance: <same value as DACP-ID>
Session: <id>            ← only AFTER the SETUP response; value = Session response header
```

### 3a. OPTIONS (optional but harmless; REQUIRED as the 15 s heartbeat later)
```
OPTIONS * RTSP/1.0
CSeq: 0
User-Agent: iTunes/11.3.1 (Windows; Microsoft Windows 10 x64 (Build 19044); x64) (dt:2)
DACP-ID: <hex16>
Active-Remote: <u32>
Client-Instance: <hex16>
Apple-Challenge: SdX9kFJVxgKVMFof/Znj4Q

```
The challenge can be **static** — the server's `Apple-Response` is ignored
(neither reference implementation verifies it). pyatv skips OPTIONS entirely and
works, so treat it as optional; but send it anyway since it doubles as the heartbeat.

### 3b. ANNOUNCE — the SDP body (this is the heart of the handshake)
```
ANNOUNCE rtsp://<local-ip>/<announce-id> RTSP/1.0
<common headers>
Content-Type: application/sdp
Content-Length: <body byte length>

v=0
o=iTunes <announce-id> 0 IN IP4 <local-ip>
s=iTunes
c=IN IP4 <receiver-ip>
t=0 0
m=audio 0 RTP/AVP 96
a=rtpmap:96 AppleLossless
a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100
a=rsaaeskey:<344-char base64, §4>
a=aesiv:ePRBLI0XN5ArFaaz7ncNZw
```
Notes:
- `<announce-id>`: any small integer (lox uses 0–9 random; pyatv uses a random uint32).
  It appears both in the request URI and the `o=` line — keep them consistent.
- `<local-ip>`: the phone's LAN IPv4 address (the socket's local address).
- `c=` line: lox puts the *local* IP, pyatv puts the *receiver* IP — **both are seen
  in the wild; receivers accept either.** (Guess: irrelevant field.)
- fmtp fields = the ALAC magic cookie: `352` frames/packet, `0` compat version,
  `16` bits, `40` pb, `10` mb, `14` kb, `2` channels, `255` maxRun, `0` maxFrameBytes,
  `0` avgBitRate, `44100` sample rate. Byte-identical to the cookie in the ALAC
  research (`a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100`).
- If the speaker has a password you get `401` here → do the Digest retry (§9),
  then continue.

### 3c. SETUP — open the UDP back-channels
Bind **two UDP sockets** on the phone first (ephemeral ports): control + timing.
```
SETUP rtsp://<local-ip>/<announce-id> RTSP/1.0
<common headers>
Transport: RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=<C>;timing_port=<T>

```
(`<C>`, `<T>` = our bound local UDP ports.)
Parse the 200 response:
- `Session:` header → numeric session id; send as `Session:` on all later requests.
- `Transport:` header → regex out `server_port=`, `control_port=`, `timing_port=`:
  - `server_port` = where we send **audio RTP** (UDP to receiver-ip).
  - `control_port` = where we send **sync packets** (UDP to receiver-ip).
  - `timing_port` = informational (receiver's timing port; we don't send there —
    the *receiver* sends timing *requests* to OUR timing port).

### 3d. RECORD — start playback
```
RECORD rtsp://<local-ip>/<announce-id> RTSP/1.0
<common headers>
Session: <id>
Range: npt=0-
RTP-Info: seq=<nextSeq>;rtptime=<nextSeq*352+88200>

```
`nextSeq` = the sequence number of the first audio packet you are about to send
(normally 0). `rtptime` must equal that packet's RTP timestamp (§5).
After 200 OK → immediately begin streaming RTP audio (pacing §6).

### 3e. While playing
- Stream RTP audio packets (§5), paced in real time.
- Every 126 packets (or ~1 s) send a **sync packet** to the receiver's control port (§7).
- Answer **timing requests** arriving on our timing UDP socket (§7).
- **Heartbeat**: `OPTIONS *` (same as §3a) every **15 s** — HomePods drop the RTSP
  session without it (noted in lox's config: "some RTSP (like HomePod) servers
  requires heartbeat"). pyatv instead POSTs `/feedback` every 25 s; either is a
  keep-alive, but use the 15 s OPTIONS for HomePod.
- Volume changes → `SET_PARAMETER` (§8).

### 3f. TEARDOWN
```
TEARDOWN rtsp://<local-ip>/<announce-id> RTSP/1.0
<common headers>
Session: <id>

```
then close the TCP socket and the UDP sockets. (lox alternatively sends
`TEARDOWN  RTSP/1.0` with an empty URI — the Session header is what matters;
prefer the URI form, it matches pyatv and RTSP conventions.)

### 3g. FLUSH (optional — track change / seek)
```
FLUSH rtsp://<local-ip>/<announce-id> RTSP/1.0
<common headers>
Session: <id>
RTP-Info: seq=<nextSeq>;rtptime=<nextRtptime>

```
Tells the receiver to drop its buffered audio and restart at the given seq/rtptime.
Not needed for gapless album playback if you keep seq/timestamp continuous.

---

## 4. Crypto — the RSA key and the verified AES triple

The `a=rsaaeskey` value is the 16-byte AES session key encrypted with the **leaked
AirPort Express 2048-bit RSA public key** using **RSA-OAEP with SHA-1**
(proven: shairport's `rsa_apply()` uses `RSA_private_decrypt(..., RSA_PKCS1_OAEP_PADDING)`
for `RSA_MODE_KEY`; OpenSSL OAEP default = SHA-1).

**You do NOT need to do RSA at runtime.** The standard sender trick (used by
lox-airplay-sender and many others): hard-code the triple —
every receiver on earth holds the same private key, so one fixed blob works everywhere.

Triple (all verified 2026-09-23 — see verification note):
- **AES-128 key**: `14497dcc98e137a855c1455a6bc0c979`
- **IV**: `78f4412c8d1737902b15a6b3ee770d67` (base64 in SDP: `ePRBLI0XN5ArFaaz7ncNZw`)
- **`a=rsaaeskey` blob** (send verbatim, one line): stored at
  `/tmp/raop-candidates/raop_rsaaeskey_b64.txt` (344 chars). NOTE: the string as it
  appears in lox-airplay-sender's `config.ts` is 342 chars — it is missing its `==`
  base64 padding; append `==`.

**Verification performed:** extracted the AirPort Express RSA *private* key from
shairport's `common.c` (`super_secret_key`), decrypted the blob with RSA-OAEP-SHA1,
and recovered exactly `14497dcc98e137a855c1455a6bc0c979` — which is also
**byte-for-byte identical** to the hard-coded `aes_key` in lox-airplay-sender's
`encryptAES()` and the `isv` array matches the IV. Two independent confirmations.

Key provenance for the report/code comments:
- Private key: `shairport/common.c` → `static char super_secret_key[]`
  (https://github.com/abrasive/shairport). **Do NOT ship the private key in the app**
  — only the public key is needed, and only if you choose to encrypt a fresh AES key
  at runtime instead of hard-coding the triple.
- Public key: derived PEM saved at `/tmp/raop-candidates/apex_pub.pem`
  (X.509 SubjectPublicKeyInfo — load in Java via
  `KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der))`).
- Runtime RSA (only if generating a fresh key): `Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")`.

---

## 5. RTP audio packet layout (byte-level)

One UDP datagram per ALAC frame → receiver's `server_port`. Total size for a
352-sample stereo/16-bit verbatim frame: **12 + ~1416 = ~1428 bytes** (well under MTU).

```
Byte  Content
0-1   0x80E0 for the very first packet (seq 0), else 0x8060
        → RTP version 2, padding/CSRC=0, marker=1 only on first packet, payload type 96
2-3   Sequence number, uint16 big-endian, wraps mod 65536
4-7   RTP timestamp, uint32 big-endian:  timestamp = seq*352 + 88200  (mod 2^32)
8-11  SSRC, uint32 big-endian: random per session (any value)
12..  ALAC frame, AES-128-CBC encrypted (§5a)
```

### 5a. Payload encryption — AES-128-CBC, no padding, FIXED IV
- Cipher: `AES/CBC/NoPadding`, key `14497dcc98e137a855c1455a6bc0c979`,
  IV `78f4412c8d1737902b15a6b3ee770d67` — **the same IV for every packet**
  (this matches the `a=aesiv` announced in the SDP; this is how classic RAOP works).
- Encrypt every complete 16-byte block: `encrypted = CBC(blocks[0 .. floor(n/16)-1])`.
  **Trailing `n % 16` bytes are sent UNENCRYPTED, appended after the ciphertext**
  (verbatim-ALAC frames are not multiples of 16; this partial-block-in-clear
  behavior is exactly what lox's `encryptAES()` does and what receivers expect).
- Android: `javax.crypto.Cipher.getInstance("AES/CBC/NoPadding")` — no extra provider needed.

### 5b. Timestamps and pacing
- First packet: seq=0, timestamp=88200 (= 2×44100 — the classic iTunes "start
  2 seconds in the future" so the device can buffer).
- Packet N: `seq = N mod 65536`, `timestamp = (N*352 + 88200) mod 2^32`.
- **Pacing:** packet N should hit the wire at wall-clock `startTime + N*352/44100`
  seconds. Send slightly ahead of real time; the device buffers ~2 s. A simple
  correct approach: compute `targetNs = t0 + N * 352 * 1e9 / 44100` per packet and
  sleep until then (burst-sending far ahead risks receiver buffer overrun; sending
  late causes underrun).
- 352 samples @ 44100 Hz = one packet every **7.98 ms** (~125.3 packets/s).

---

## 6. Retransmit handling — safe to IGNORE (with a caveat)

- The receiver asks for resends on **our control UDP socket**: any datagram whose
  **byte[1] == 0xD5** is a resend request; `missedSeq` = uint16BE at bytes 4–5,
  `count` = uint16BE at bytes 6–7. (Confirmed in both lox `udpServers.ts` and
  pyatv `packets.py` → `RetransmitRequest`.)
- Correct behavior: keep a ring buffer of recently sent packets (~260 packets ≈
  2.1 s, per lox's `packets_in_buffer`) and re-send the requested range.
- **Pragmatic behavior: lox-airplay-sender ships with the resend handler
  commented out and works fine.** Ignoring = occasional tiny glitch on Wi-Fi
  packet loss only. Recommendation: v1 ignores resends; add the history buffer
  later if glitches are reported. (Marking this "safe" is based on a shipped
  implementation, not on the RAOP spec.)

## 7. Timing port + sync packets

### Timing replies (implement — ~20 lines, cheap insurance)
- The receiver sends 32-byte timing **requests** to OUR timing UDP port.
- Reply with 32 bytes to the requester's address/port:
```
 0-1:  0x80D3
 2-3:  0x0007
 4-7:  0x00000000
 8-11: echo of request bytes 24-27
12-15: echo of request bytes 28-31
16-23: current NTP time (8 bytes big-endian)
24-31: current NTP time again (8 bytes big-endian)
```
- **NTP time**: `seconds = unixTime + 2208988800` (0x83AA7E80), `fraction =
  floor(millisWithinSecond * 4294967.296)` (= ms/1000 × 2³²). 8 bytes BE.
- REQUIRED? **Unsure — mark as guess.** AirPort-era devices used this for clock
  sync; several minimal senders reportedly play without it. Implement it anyway;
  it cannot hurt.

### Sync packets (implement — the receiver uses these for A/V sync & buffering)
- Every **126 packets** (≈ every 1.0 s), send 20 bytes to the receiver's
  **control port** (from SETUP):
```
 0-1:  0x80D4
 2-3:  0x0007
 4-7:  (seq*352) mod 2^32            — RTP timestamp of that packet
 8-15: current NTP time (8 bytes BE)
16-19: (seq*352 + 88200) mod 2^32    — "rtptime", same base as RECORD's RTP-Info
```
  `seq` = the sequence number of the most recently sent (or next) audio packet.

---

## 8. Volume control

```
SET_PARAMETER rtsp://<local-ip>/<announce-id> RTSP/1.0
<common headers>
Session: <id>
Content-Type: text/parameters
Content-Length: <len>

volume: <attenuation>
```
- `<attenuation>` = `-144.0` if volume == 0, else `(-30.0) * (100 - volume) / 100.0`
  (volume 0–100). So 100 → `0.0`, 50 → `-15.0`, 0 → `-144.0` (mute).
- Body is literally `volume: -15.0\r\n` (one line, CRLF-terminated).
- This is the standard DACP-style volume; works on HomePods.

---

## 9. Password-protected speakers (Digest auth)

- TXT `pw=true` → the first ANNOUNCE returns **401** with
  `WWW-Authenticate: Digest realm="<realm>", nonce="<nonce>"`.
- Parse: split the header value on `"` → index 1 = realm, index 3 = nonce
  (pyatv does exactly this).
- Retry ANNOUNCE (and attach to all later requests) with:
  `Authorization: Digest username="<anything>", realm="<realm>", nonce="<nonce>", uri="<request-uri>", response="<hex>"`
  where `ha1 = md5("username:realm:password")`, `ha2 = md5("METHOD:uri")`,
  `response = md5("ha1:nonce:ha2")`. Username is arbitrary (pyatv uses "pyatv");
  the device only verifies the password. HomePods in a home normally have
  `pw=false`, so this path is rarely hit.

---

## 10. HomePod-specific notes

- **No pairing needed** for classic RAOP — the user's lived experience (streams from
  Android to his HomePod today via AirMusic) plus lox-airplay-sender's design confirm
  HomePods accept the RSA/AES Path A handshake with no HomeKit involvement.
- **Apple-Challenge**: send the static `SdX9kFJVxgKVMFof/Znj4Q`; never verify
  `Apple-Response`. (No device checks that *we* are genuine.)
- **Heartbeat is the one HomePod quirk found in code**: send `OPTIONS *` every
  15 s while streaming or the HomePod drops the session.
- RAOP port is 5000 on HomePods (always read it from the SRV record anyway).
- Model ids for UI labels: `AudioAccessory1,1`/`1,2` = HomePod, `AudioAccessory5,1`
  = HomePod mini, `AudioAccessory6,1` = HomePod gen 2.
- ALAC is the right codec (`a=rtpmap:96 AppleLossless`) — what iTunes used; the
  verbatim-frame encoder (352 samples, stereo, 16-bit, 44.1 kHz) matches the fmtp.
  The phone must resample/transcode to 44.1 kHz stereo 16-bit before encoding
  (RAOP v1 is fixed at 44100/2ch).
- **Open guesses (do not treat as fact):** whether the HomePod *requires* timing-port
  replies (implement them — §7); whether it tolerates a missing OPTIONS pre-ANNOUNCE
  (send it anyway); exact minimum heartbeat interval (15 s is the value a shipped
  sender uses for HomePods).

---

## 11. Suggested Kotlin module shape

`cast/RaopSender.kt` (~600–800 lines), no new dependencies:
- `discover()` — Android `NsdManager` on `_raop._tcp`; parse `@`-name, SRV port,
  TXT (`et`, `cn`, `pw`, `am`).
- `connect(host, port)` — TCP socket; CSeq counter; `sendRequest()` matching
  responses by CSeq with a 4 s timeout (pyatv's value).
- `announce()` — build SDP with the hard-coded triple (§4); Digest retry (§9).
- `setup()` — bind 2 `DatagramSocket`s; parse `Session` + `server_port`/`control_port`.
- `record()` → start sender thread: ALAC-encode 352-sample frames → AES-CBC/NoPadding
  (partial tail in clear) → RTP header → paced UDP to `server_port`.
- Control socket listener: answer timing requests (§7); optionally handle 0xD5 resends (§6).
- `setVolume(v)` (§8); 15 s OPTIONS heartbeat (Handler/coroutine); `teardown()` (§3f).
- Existing `CastManager` gains a RAOP transport next to the Sonos/Chromecast ones.

Security hygiene: the hard-coded AES key/IV/blob are public reverse-engineered
constants (not secrets) — safe to embed as code constants with a comment citing
provenance. Do NOT embed `apex_priv.pem`.

---

## 12. What was NOT verified

- No physical HomePod/AirPort test was possible from here (no device, no LAN
  multicast in this environment) — the whole spec is reconstructed from shipped
  open-source senders (lox-airplay-sender's AirTunes path, pyatv's RAOP path) and
  the shairport receiver, cross-checked against each other where they overlap
  (RTP header, resend format, SDP fmtp, crypto triple — all agree).
- lox-airplay-sender is AGPL-3.0 with no LICENSE file: **do not copy its code**;
  this report is a clean-room spec (facts/protocols aren't copyrightable; the
  code expression is).

---

## 13. Implementation (2026-09-23) — Outro's RAOP stack

Implemented as a clean-room Kotlin port from this spec (no lox code copied):

- `cast/raop/AlacEncoder.kt` — verbatim ALAC frame encoder, ported from
  technicallyalac (Aritile/technicallyalac, 0BSD, John Regan). 352-sample
  stereo 16-bit frames encode to exactly 1415 bytes. Pure JVM; unit-tested
  (round-trip through a bit-level test decoder, header byte vectors).
- `cast/raop/RaopCrypto.kt` — AES-128 key/IV constants (matching lox's
  triple), AES-128-CBC/NoPadding over complete 16-byte blocks with the
  trailing partial block left clear, RTP header sent in the clear.
  `RSA_AES_KEY_B64` is intentionally blank until the 344-char blob is
  fetched from lox-airplay-sender's `src/config.ts` and verified
  byte-for-byte; `RaopConnection.connect()` refuses to start while blank.
- `cast/raop/RaopConnection.kt` — RTSP handshake
  (OPTIONS → ANNOUNCE → SETUP → RECORD), paced encrypted RTP
  (12-byte header `80 60`, PT 96), timing requests + replies, 1-second
  sync packets, 15-second OPTIONS heartbeat, FLUSH-pause / RECORD-resume,
  SET_PARAMETER volume (-30..0 dB), TEARDOWN. Retransmit requests
  (0x80 0xD5) answered from a 256-packet history ring.
- `cast/raop/RaopPcmPipeline.kt` — MediaExtractor + MediaCodec decode of
  the Subsonic stream URL → mono/stereo/multi-channel normalize → linear
  resample to 44.1 kHz → 352-sample frames.
- `cast/raop/RaopTransport.kt` — NsdManager `_raop._tcp` discovery
  (`<id>@<name>` parsing) and a `UrlSpeakerTransport` for
  `SpeakerKind.AIRPLAY`, so `CastManager` routes it like Sonos/Chromecast.
  Pause keeps the RTSP session and resumes in place; track changes start
  a fresh session (~300 ms on LAN).

Deliberately v1-scoped (follow-ups): DAAP metadata/artwork, device volume
UI wiring, gapless track transitions (currently teardown per track),
seek-resume inside a track (resume restarts via CastManager.play),
AirPlay 2 / paired devices (password/pin flows not implemented).

Device verification still required: physical HomePod test by the user.
Key byte-layout risks to confirm on device: RTP header in clear vs
encrypted, timing/sync packet field positions, FLUSH resume behavior.

---

## 14. Addendum 2026-09-25 — the 0.0.0.0 ANNOUNCE bug (fixed in Outro 1.2.3)

**Root cause of the 1.2.1/1.2.2 ANNOUNCE 406:** `RaopTransport.localIp()` used
`WifiManager.getConnectionInfo().ipAddress`. On Android 10+ without
`ACCESS_FINE_LOCATION` (which Outro does not request), this returns 0 — so the
app ANNOUNCE'd `rtsp://0.0.0.0/<session-id>` with `o=iTunes ... IN IP4 0.0.0.0`.
The HomePod rejects the malformed URI with 406. OPTIONS survived because its
URI is `*` (no IP needed).

**Fix (1.2.3):** `RaopConnection.connect()` now refreshes the local IP from
the connected RTSP socket's own local address
(`socket.localAddress.hostAddress`) — always the true source IP, no permission
needed, exactly what lox-airplay-sender / node_airtunes do
(`socket.address().address`). `resolveEffectiveIp()` accepts only IPv4,
rejecting blank / `0.0.0.0` / IPv6 (the SDP is `IN IP4`, RAOP is IPv4-only).

**Crypto revert (1.2.3):** the per-session RSA/AES experiment was reverted.
The static triple (verified 2026-09-23 by decrypting the blob with the real
private key; byte-identical to node-airplay-sender's working triple) is what
every shipped open-source sender uses — a valid static OAEP blob cannot cause
a 406, and a dynamically generated one cannot fix it. The 2048-bit public-key
modulus transcribed from the centuryplay doc turned out truncated (341 chars)
and was discarded, not shipped.

## In-app connection logger (1.2.4)

The HomePod still rejected ANNOUNCE with 406 on 1.2.3, and the failure cannot
be reproduced in this VM (no HomePod, no multicast). Guessing blind is over —
so the app now records the handshake itself.

**`RaopLogger`** (`cast/raop/RaopLogger.kt`): pure-JVM (no Android APIs, so the
JVM unit tests compile it) in-memory ring buffer of the last 400 timestamped
lines, observable via addListener/removeListener. Nothing leaves the device
unless the user copies/shares it.

What it records, in order:
1. `connect: TCP <host>:<port>` — the resolved speaker address from discovery.
2. `discover: '<name>' @ <host>:<port> et=… am=… pw=… md=… vn=…` — the RAOP
   TXT record attributes (et=5 is classic RAOP; pw tells us if the speaker
   wants a password).
3. `connect: localIp=… effectiveLocalIp=…` — proves whether the 1.2.3 socket
   refresh actually produced a real IPv4 address on the device.
4. Every RTSP exchange as `RTSP → <METHOD> <uri>` (full request: request line,
   all headers, SDP body) followed by `RTSP ← <METHOD>` (status, all response
   headers, and the response **body** — 1.2.4 now captures it; a 406 may carry
   an explanation).
5. `connect FAILED: <message>` on handshake exceptions.

**Viewer:** Settings → Speakers → "AirPlay connection log" (added in 1.2.4):
monospace scroll view (auto-scrolls to newest), with Copy / Share / Clear
buttons. The user taps Cast, waits for the failure, opens the log and shares
it — no logcat capture needed.

JVM coverage: `scripts/unit_tests_raop_logger.kt` (7 checks: empty snapshot,
listener notify/remove, logBlock layout, ring-buffer cap, clear) wired into
`scripts/test_124.py` Part E.

## The et discovery (1.2.5) — root cause of the 406

The 1.2.4 in-app logger captured the HomePod mini's own TXT record:

```
discover: 'Bedroom' @ 10.0.0.148:7000 et=0,3,5 am=AudioAccessory5,1 pw=null md=0,1,2 vn=65537
```

and proved the 1.2.3 IP fix worked (`effectiveLocalIp=10.0.0.88`, ANNOUNCE to
`rtsp://10.0.0.88/<session>`), yet ANNOUNCE was still rejected with a bare
406 (no body, no extra headers).

`et` declares the encryption types the speaker accepts:
- `et=0` — clear (no encryption)
- `et=1` — classic RSA key exchange (`a=rsaaeskey:` + `a=aesiv:`)
- `et=3` / `et=5` — FairPlay SAPv1/SAPv2 (needs Apple's secret sender keys)

Our ANNOUNCE offered `a=rsaaeskey:` — the et=1 mode — which this speaker
never advertised. Offering an unadvertised mode gets the ANNOUNCE rejected.
(The 1.2.1 `a=fpaeskey:` → `a=rsaaeskey:` change was correct per the classic
spec; the attribute was never the problem — the mode was.)

**Fix (1.2.5):** the crypto mode now follows the speaker's TXT `et`
(`AirPlaySpeaker.et`, plumbed from discovery through `RaopTransport.play`
into `RaopConnection(encryptAudio=…)`):
- et advertises `1` → RSA mode (unchanged: key lines + AES-128-CBC RTP)
- et advertises `0` but not `1` → **clear mode**: SDP omits BOTH key lines
  (receivers treat "both absent" as unencrypted, "exactly one" as 456) and
  RTP carries plain ALAC frames
- otherwise (FairPlay-only) → fail fast with "requires FairPlay" instead of
  a cryptic 406

The chosen mode is written to the connection log (`crypto mode: …`) so the
next device test shows exactly what was offered. FairPlay (et=3/5) remains
unsupported — it requires Apple sender credentials no public project has.
