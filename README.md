# Sill for Android (unofficial)

An Android client for [Sill](https://getsill.app), the free, open-source app that streams your
Mac's windows to a phone. Install Sill on the Mac as usual, install this app on an Android phone,
and the phone finds the Mac, pairs with a code, and shows and controls its windows.

**Unofficial:** not made, endorsed or supported by Sill's developer. It speaks Sill's own wire
protocol ([Saffsanity/sill](https://github.com/Saffsanity/sill), `Sources/StreamProtocol`), so the
Mac needs no changes. Problems with this app go to
[this repo's issues](https://github.com/rahimnathwani/sill-android/issues), not Sill's.

## Use it

1. **On the Mac:** install Sill from [getsill.app/download](https://getsill.app/download) and open it
   (allow Screen Recording and Accessibility when it asks).
2. **On the phone:** download `sill-android-<version>.apk` from
   [Releases](https://github.com/rahimnathwani/sill-android/releases/latest) and open it (allow
   installs from your browser when Android asks). Android 11 or later. A Google Play listing is on
   its way. To be told of updates, add this repo to [Obtainium](https://obtainium.imranr.dev).
3. **Connect:** with both on the same Wi-Fi, open Sill on the phone and tap your Mac. The Mac shows
   a pairing window with a 12-digit code; type it on the phone. Next time it connects at once.

Away from home: turn on Remote Access in the Mac's Sill settings, put both on Tailscale (or another
VPN), and use **Connect by address…** with the Mac's Tailscale name or address (port 7455 unless you
changed it). Pair at home first, or open **Pair iPhone or iPad…** in the Mac's Sill menu and type
the code it shows. Scanning the Mac's pairing QR code with the phone's camera also opens Sill and
pairs.

If the Mac forgets this phone (Sill › Settings › Devices › Remove, or Sill set up again), the phone
says so and asks for a new code; it does not keep redialing (the Mac shuts out an address after
five refusals in a minute).

The phone's key is made in Android Keystore and never leaves it.

## Controls

| Gesture | On the Mac |
|---|---|
| **Tap** | Click |
| **Drag one finger** | Scroll, with momentum |
| **Hold, then move** | Drag (select text, move a window) |
| **Hold and lift** / **two-finger tap** | Right-click |
| **Draw** (bar toggle) | One finger presses the mouse button and drags: draw in tldraw, Figma, Preview. Tap Draw again to scroll |
| **Pinch / two-finger move** | Zoom and pan the picture on the phone only; **Fit** resets |
| **Windows** | Pick a window, the Desktop, or open an app |
| **Keyboard** | Type; the key row adds esc, tab, ⌘ ⌃ ⌥ ⇧ (tap to latch for the next key or click), arrows and Spotlight |
| **Hardware keyboard** | Keys go to the Mac; Ctrl acts as ⌘ (Ctrl-C copies), Meta as ⌃ |

## What's not here (yet)

- Trackpad mode, three-finger gestures, the Mac's menus, the clipboard, the Mac's settings panel,
  and the Mac's pointer drawn on the phone.
- Held hardware keys don't repeat on the Mac (one press per press).
- Direct Wireless (AWDL is Apple-only) and pairing over a USB cable (the Mac recognises only
  iPhones and iPads there): pair with the code.

## Privacy

The app talks only to your own Macs, encrypted; nothing reaches its developer or anyone else. See
[PRIVACY.md](PRIVACY.md).

## Build

Needs JDK 17 and the Android SDK (platform 36).

```
./gradlew :protocol:test          # protocol unit tests (crypto vectors, framing, JSON, HEVC)
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk (io.github.rahimnathwani.sill.debug)
```

A debug build installs beside the release one and also lists plain-TCP test hosts.

## Release

```
scripts/release.sh 0.2.0            # tests, lint, signed APK and Play bundle in dist/, signature checked
scripts/release.sh 0.2.0 --publish  # then tag v0.2.0 and create the GitHub release with the APK
```

Add a `## 0.2.0` section to CHANGELOG.md first; it becomes the release notes. The upload key is
`~/.config/sill-android/upload.jks` with its password in the macOS keychain
(`sill-android-upload-key`); it never enters the repository or CI. Google Play: `play/checklist.md`.

### Live test against a Mac host

From a checkout of Saffsanity/sill, run a test host (loopback only, never posts input):

```
SILL_TEST_LOOPBACK=1 SILL_TEST_SOFTWARE_ENCODER=1 SILL_TEST_NO_ROUTER=1 \
SILL_TEST_REMOTE_DIR=$T/host SILL_TEST_ASK_FROM_THIS_MAC=1 \
.build/release/SillHost --synthetic --pairing > $T/host.log
```

then, with its port from `lsof -nP -iTCP -sTCP:LISTEN | grep SillHost`:

```
SILL_LIVE_HOST=127.0.0.1 SILL_LIVE_PORT=<port> SILL_LIVE_CODE_FILE=$T/host.log \
./gradlew :protocol:test --tests '*LiveHostTest*' --rerun
```

An emulator reaches that host at `10.0.2.2:<port>` through **Connect by address…**.

## Layout

- `protocol/` — pure Kotlin/JVM, no Android: framing (`Wire`), JSON in Swift Codable's shapes
  (`Json`, `Messages`), pairing crypto (`Crypto`: PBKDF2 proofs, Mac ID, recognition tag, Damm),
  the device's P-256 key and self-signed certificate (`Identity`), TLS 1.3 with SPKI pinning and
  ALPN `sill/1` / `sill-pair/1` (`Tls`), the pairing exchanges (`Pairing`), a session with hello,
  viewport, ping, liveness and stats (`Session`), HEVC hvcC → Annex B and SPS size (`Hevc`).
- `app/` — the Android app (plain-TCP doors, development hosts only, are listed in debug builds
  alone): Bonjour discovery (`Discovery`, NsdManager), MediaCodec decoding
  (`VideoDecoder`), touch and keyboard (`StreamView`, `HidKeys`), screens and flow (`MainActivity`),
  storage (`Store`).

## License

Apache-2.0. See [NOTICE](NOTICE) for how this relates to Sill.
