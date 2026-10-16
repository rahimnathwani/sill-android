# Google Play: first release checklist

1. **Developer account** (https://play.google.com/console, $25 once). Personal accounts made after
   November 2023 must run a **closed test with at least 12 testers for 14 days** before production.
   Identity verification can take a few days.
2. **Create the app**: name "Sill for Android (unofficial)", default language English (US), App,
   Free. Accept the declarations.
3. **Play App Signing**: on the first upload, let Google generate and hold the app signing key. Your
   key (`~/.config/sill-android/upload.jks`) becomes the *upload key*. Consequence: the Play build
   and the GitHub APK are signed differently, so a phone can't switch between them without
   uninstalling (and pairing again). Alternatively choose "Use a key from Java keystore" and upload
   this same key as the app signing key, so both channels update each other; the trade-off is that a
   lost key can then never be replaced. Either way, back up `upload.jks` and its keychain password
   (`security find-generic-password -s sill-android-upload-key -w`) somewhere safe now.
4. **App content**: fill in every section from `play/app-content.md`.
5. **Store listing**: text from `play/listing.md`; graphics from `play/graphics/`:
   - App icon 512 × 512: `icon-512.png`
   - Feature graphic 1024 × 500: `feature-1024x500.png`
   - Phone screenshots, 2 to 8, 16:9 or 9:16, 320–3840 px: take them on the phone while streaming
     (`scripts/screenshots.sh name`), with nothing private on the Mac's screen.
6. **Closed testing** → create a track, add the testers' Google accounts (12+), upload
   `dist/sill-android-<version>.aab`, release notes from CHANGELOG.md. Share the opt-in link.
7. After 14 days with 12+ testers opted in: **apply for production access** (Dashboard), answer the
   questions about the test, then promote the release to Production.

Each later release: bump the version in CHANGELOG.md, run `scripts/release.sh X.Y.Z --publish`
(GitHub), upload the new `.aab` to Play.
