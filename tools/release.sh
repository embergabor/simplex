#!/usr/bin/env bash
#
# release.sh: builds Simplex with the release key and publishes it as a GitHub release, which the app's
# update check offers on the TV. Run it from a clean, pushed main.
#
# Needs: the release key in ~/.simplex-release (see app/build.gradle.kts), JDK 17+, the Android SDK, gh.
#
# Usage: tools/release.sh ["release notes"]

set -euo pipefail
cd "$(dirname "$0")/.."

[ -f ~/.simplex-release/keystore.properties ] || { echo "No release key in ~/.simplex-release" >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Commit or stash your changes first." >&2; exit 1; }
git fetch -q origin
[ "$(git rev-parse HEAD)" = "$(git rev-parse '@{u}')" ] || { echo "Push main first, so the release matches GitHub." >&2; exit 1; }

./gradlew -q clean assembleRelease
apk=app/build/outputs/apk/release/app-release.apk
version=$(git rev-list --count HEAD)
name="0.2.$version"

# The APK must carry the release key, or it can't update a TV that has a release installed.
expected=$(keytool -list -keystore ~/.simplex-release/simplex-release.jks \
    -storepass "$(sed -n 's/^storePassword=//p' ~/.simplex-release/keystore.properties)" 2>/dev/null |
    sed -n 's/.*SHA-256): //p' | tr -d ':' | tr 'A-F' 'a-f')
apksigner="$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/*/ | tail -1)apksigner"
actual=$("$apksigner" verify --print-certs "$apk" |
    sed -n 's/.*SHA-256 digest: //p' | head -1)
[ "$expected" = "$actual" ] || { echo "The APK isn't signed with the release key." >&2; exit 1; }

cp "$apk" "build/simplex-$name.apk"
gh release create "v$name" "build/simplex-$name.apk" --title "Simplex $name" --notes "${1:-}"
echo "Released v$name"
