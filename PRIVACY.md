# Privacy policy: Sill for Android (unofficial)

*Last updated: October 16, 2026*

Sill for Android is an unofficial client for the Sill app on your Mac. It is made by Rahim Nathwani,
not by Sill's developer.

**The short version:** the app sends nothing to its developer or to anyone else. It talks only to
the Macs you pair it with. There are no accounts, no analytics, no ads and no crash reporting.

## What the app connects to

- **Your Mac, directly.** The phone connects to the Sill app on your Mac over your own network, or
  over a VPN you set up (such as Tailscale). The Mac's picture comes to the phone; your taps, typing
  and choices go to the Mac. The connection is encrypted (TLS 1.3), and each end checks the other's
  key, so a different computer cannot pose as your Mac.
- **Your local network, to find Macs.** The app looks for Macs running Sill on the same Wi-Fi
  (Bonjour, also called mDNS). That traffic stays on your local network.

Nothing else. The app has no server, and its developer runs none.

## What the app tells your Mac

When it connects, the app tells your Mac this phone's name (from Android's settings), its model and
the app's version. The Mac shows them in its list of paired devices and its log. While you stream,
the app sends your input to the Mac and short connection statistics (frame rate, delay), which the
Mac shows in its menu.

## What the app keeps on the phone

- A key that identifies this phone to your Macs, made in Android Keystore. It never leaves the phone.
- The Macs you paired with: each one's name, ID, key fingerprint, a recognition key (to find it on the
  network) and the last address that worked.
- Addresses you typed in "Connect by address", and whether Draw mode is on.

These stay in the app's private storage, are excluded from backups and device transfers, and are
deleted when you uninstall the app. "Paired Macs…" forgets a Mac.

## Permissions

- **Internet, network state, Wi-Fi state, Wi-Fi multicast:** to find your Mac on the local network
  and connect to it.

The app does not use your location, contacts, camera, microphone or files.

## Children

The app is not directed at children and collects no personal information from anyone.

## Changes and contact

Changes to this policy appear in this file, with its date. Questions:
https://github.com/rahimnathwani/sill-android/issues
