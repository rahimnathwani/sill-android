# Changelog

## 0.1.0

First release. An unofficial Android client for [Sill](https://getsill.app) on the Mac.

- Finds Macs running Sill on the same Wi-Fi; pairs with the 12-digit code the Mac shows, or by
  scanning the Mac's pairing QR code with the phone's camera.
- Streams the Mac's Desktop or any one window, picked from a list with live thumbnails, and opens
  the Mac's apps.
- Tap to click, drag to scroll (with momentum), hold then move to drag, hold or two-finger tap to
  right-click, pinch to zoom the picture on the phone.
- Draw mode: one finger presses and drags the mouse button, for drawing apps.
- Software keyboard with a key row (esc, tab, ⌘ ⌃ ⌥ ⇧, arrows, Spotlight); hardware keyboards.
- Away from home through Tailscale or another VPN, with Remote Access on the Mac.
- Everything between phone and Mac is TLS 1.3, both ends pinned; the phone's key lives in Android
  Keystore.
