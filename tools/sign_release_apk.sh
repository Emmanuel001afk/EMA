#!/usr/bin/env bash
# Sign a release APK with a persistent, privately supplied key. Never use the debug key.
set -euo pipefail
if [[ $# -ne 3 ]]; then
  echo "Usage: sign_release_apk.sh INPUT.apk OUTPUT.apk ANDROID_BUILD_TOOLS_DIR" >&2
  exit 2
fi
input=$1 output=$2 tools=$3
for name in EMA_SIGNING_KEYSTORE_BASE64 EMA_SIGNING_STORE_PASSWORD EMA_SIGNING_KEY_ALIAS EMA_SIGNING_KEY_PASSWORD; do
  if [[ -z ${!name:-} ]]; then echo "Missing required release signing setting: $name" >&2; exit 1; fi
done
[[ -s "$input" && -x "$tools/apksigner" && -x "$tools/zipalign" ]] || { echo 'APK or Android build tools missing.' >&2; exit 1; }
umask 077
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
printf '%s' "$EMA_SIGNING_KEYSTORE_BASE64" | base64 --decode > "$tmp/key.jks"
[[ -s "$tmp/key.jks" ]] || { echo 'Empty signing keystore.' >&2; exit 1; }
"$tools/zipalign" -f -p 4 "$input" "$tmp/aligned.apk"
"$tools/apksigner" sign --ks "$tmp/key.jks" --ks-key-alias "$EMA_SIGNING_KEY_ALIAS" \
  --ks-pass env:EMA_SIGNING_STORE_PASSWORD --key-pass env:EMA_SIGNING_KEY_PASSWORD \
  --out "$tmp/signed.apk" "$tmp/aligned.apk"
"$tools/apksigner" verify --verbose --print-certs "$tmp/signed.apk"
"$tools/zipalign" -c -p 4 "$tmp/signed.apk"
mkdir -p "$(dirname "$output")"
cp "$tmp/signed.apk" "$output"
