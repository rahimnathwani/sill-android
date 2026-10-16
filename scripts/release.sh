#!/bin/bash
# Builds a signed release: dist/sill-android-<version>.apk (GitHub, sideloading) and .aab (Google Play),
# with SHA-256 files. With --publish it also tags v<version>, pushes the tag and creates the GitHub
# release with the APK and the CHANGELOG's section as its notes.
#
#   scripts/release.sh 0.1.0             # build and verify only
#   scripts/release.sh 0.1.0 --publish   # then tag and publish on GitHub
#
# The upload key: ~/.config/sill-android/upload.jks (override with SILL_KEYSTORE), its password in the
# macOS keychain under the service "sill-android-upload-key". Never in the repository.
set -euo pipefail

VERSION="${1:-}"
PUBLISH="${2:-}"
cd "$(dirname "$0")/.."

die() { echo "release: $*" >&2; exit 1; }

[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "usage: scripts/release.sh X.Y.Z [--publish]"
[[ -z "$PUBLISH" || "$PUBLISH" == "--publish" ]] || die "unknown option $PUBLISH"
IFS=. read -r MAJ MIN PAT <<< "$VERSION"
(( MIN < 100 && PAT < 100 )) || die "minor and patch must be under 100 (versionCode = X*10000 + Y*100 + Z)"

[[ -z "$(git status --porcelain)" ]] || die "the working tree has changes; commit them first"
grep -q "^## $VERSION" CHANGELOG.md || die "CHANGELOG.md has no '## $VERSION' section"
if [[ "$PUBLISH" == "--publish" ]]; then
  [[ "$(git rev-parse --abbrev-ref HEAD)" == "main" ]] || die "publish from main"
  git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null && die "tag v$VERSION exists already"
  gh auth status >/dev/null 2>&1 || die "gh is not logged in"
fi

export SILL_KEYSTORE="${SILL_KEYSTORE:-$HOME/.config/sill-android/upload.jks}"
[[ -f "$SILL_KEYSTORE" ]] || die "no keystore at $SILL_KEYSTORE"
SILL_KEYSTORE_PASSWORD="$(security find-generic-password -s sill-android-upload-key -w)" || die "no password in the keychain"
export SILL_KEYSTORE_PASSWORD

echo "== tests, lint, signed APK and bundle for $VERSION"
./gradlew --quiet clean :protocol:test :app:lintRelease :app:assembleRelease :app:bundleRelease -PversionName="$VERSION"

APK=app/build/outputs/apk/release/app-release.apk
AAB=app/build/outputs/bundle/release/app-release.aab
[[ -f "$APK" && -f "$AAB" ]] || die "the build made no signed APK or bundle (is the keystore readable?)"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
APKSIGNER="$(ls -d "$SDK"/build-tools/*/apksigner | sort -V | tail -1)"
WANT="$(keytool -list -keystore "$SILL_KEYSTORE" -storepass "$SILL_KEYSTORE_PASSWORD" -alias upload 2>/dev/null \
  | grep -i 'SHA-256' | sed 's/.*: //; s/://g' | tr 'A-F' 'a-f')"
GOT="$("$APKSIGNER" verify --print-certs "$APK" | grep 'SHA-256 digest' | head -1 | sed 's/.*: //')"
[[ -n "$WANT" && "$WANT" == "$GOT" ]] || die "the APK is not signed with the upload key ($GOT)"
"$APKSIGNER" verify "$APK"
jarsigner -verify "$AAB" >/dev/null || die "the bundle's signature does not verify"
unset SILL_KEYSTORE_PASSWORD

mkdir -p dist
OUT_APK="dist/sill-android-$VERSION.apk"
OUT_AAB="dist/sill-android-$VERSION.aab"
cp "$APK" "$OUT_APK"; cp "$AAB" "$OUT_AAB"
(cd dist && shasum -a 256 "$(basename "$OUT_APK")" > "$(basename "$OUT_APK").sha256" \
         && shasum -a 256 "$(basename "$OUT_AAB")" > "$(basename "$OUT_AAB").sha256")
echo "== built"
ls -la "$OUT_APK" "$OUT_AAB"
echo "signed by: $GOT"

if [[ "$PUBLISH" == "--publish" ]]; then
  NOTES="$(awk -v v="## $VERSION" '$0 ~ "^"v {on=1; next} /^## / {on=0} on' CHANGELOG.md)"
  git tag -a "v$VERSION" -m "Sill for Android $VERSION"
  git push origin "v$VERSION"
  gh release create "v$VERSION" "$OUT_APK" "$OUT_APK.sha256" \
    --title "Sill for Android $VERSION (unofficial)" --notes "$NOTES" --verify-tag
  echo "== published v$VERSION; upload $OUT_AAB to Google Play Console by hand"
fi
