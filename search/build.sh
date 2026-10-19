#!/usr/bin/env bash
# Build the optional network-enabled search companion. Does not contact a TV.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ../launcher/version.env
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}}
JAR=$SDK/platforms/android-28/android.jar
BT=$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
[[ -f $JAR && -x $BT/aapt2 && -x ${JAVA_HOME:-}/bin/javac ]] || {
  echo 'Needs JAVA_HOME (JDK 17) and ANDROID_HOME (API 28, build-tools).' >&2; exit 1;
}
KEYSTORE=${DUMBTV_KEYSTORE:-$PWD/../launcher/release.keystore}
KEYPASS=${DUMBTV_KEYPASS:-dumbtvhome}
if [[ ! -f $KEYSTORE ]]; then
  echo 'Build the launcher first. The companion uses the same signing key for weather IPC.' >&2
  exit 1
fi
WORK=$(mktemp -d "${TMPDIR:-/tmp}/evma-search.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/gen" "$WORK/classes" build
"$BT/aapt2" compile --dir res -o "$WORK/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml "$WORK/res.zip" \
  --min-sdk-version 28 --target-sdk-version 28 --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" --java "$WORK/gen" -o "$WORK/app.apk"
find src "$WORK/gen" -name '*.java' > "$WORK/sources.txt"
"$JAVA_HOME/bin/javac" --release 8 -Xlint:all -Xlint:-options -Werror -encoding UTF-8 \
  -classpath "$JAR" -d "$WORK/classes" @"$WORK/sources.txt"
find "$WORK/classes" -name '*.class' > "$WORK/classes.txt"
"$BT/d8" --release --min-api 28 --lib "$JAR" --output "$WORK" @"$WORK/classes.txt"
(cd "$WORK" && zip -q -X app.apk classes.dex)
"$BT/zipalign" -f -p 4 "$WORK/app.apk" "$WORK/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYPASS" --key-pass "pass:$KEYPASS" \
  --out build/evma-search.apk "$WORK/aligned.apk"
"$BT/apksigner" verify build/evma-search.apk
perms=$("$BT/aapt2" dump permissions build/evma-search.apk)
[[ $(printf '%s\n' "$perms" | grep -c uses-permission) == 1 ]] &&
  printf '%s\n' "$perms" | grep -q "name='android.permission.INTERNET'" || {
    echo 'Search must request only INTERNET permission.' >&2; exit 1;
  }
echo 'Built build/evma-search.apk; signature and permission check passed.'
