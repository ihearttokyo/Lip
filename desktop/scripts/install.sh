#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RELEASE_TOOL="$ROOT/../tools/release_identity.py"
DISPLAY_NAME=$(python3 "$RELEASE_TOOL" --check --check-source --field display_name)
echo "Building $DISPLAY_NAME · local source candidate, not a published release"
DEST="$HOME/Applications/Lip Local.app"
if [ -e "$DEST" ]; then
  echo "Existing app preserved. Move it aside before reinstalling: $DEST" >&2
  exit 1
fi
# A stable Apple-issued identity is required for persistent macOS privacy grants.
# Do not silently fall back to ad-hoc signing (whose DR changes with each binary).
IDENTITY="${LIP_CODESIGN_IDENTITY:-}"
if [ -z "$IDENTITY" ]; then
  IDENTITIES=$(security find-identity -v -p codesigning | awk '/"Developer ID Application:/ {print $2}')
  COUNT=$(printf '%s\n' "$IDENTITIES" | awk 'NF {n++} END {print n+0}')
  if [ "$COUNT" -ne 1 ]; then
    echo 'Set LIP_CODESIGN_IDENTITY to an existing Apple-issued signing identity; ad-hoc signing is intentionally refused.' >&2
    exit 1
  fi
  IDENTITY="$IDENTITIES"
fi
swift build --package-path "$ROOT" -c release --arch arm64
mkdir -p "$DEST/Contents/MacOS" "$DEST/Contents/Resources"
cp "$ROOT/.build/release/LipLocal" "$DEST/Contents/MacOS/"
cp "$ROOT/Info.plist" "$DEST/Contents/"
# SwiftPM dependency resource bundles must remain beside the executable.
find "$ROOT/.build/release/" -maxdepth 1 -name '*.bundle' -exec cp -R {} "$DEST/Contents/MacOS/" \;
cp "$ROOT/THIRD_PARTY.md" "$DEST/Contents/Resources/"
cp "$ROOT/.build/checkouts/FluidAudio/LICENSE" "$DEST/Contents/Resources/FluidAudio-LICENSE"
cp "$ROOT/../LICENSE" "$DEST/Contents/Resources/Lip-LICENSE"
cp "$ROOT/../docs/assets/bark-CC-BY-4.0.txt" "$ROOT/../docs/assets/bark-NOTICE.txt" "$DEST/Contents/Resources/"
# Generated AFTER source commit, outside tracked inputs; never claim an old HEAD
# describes dirty production files. App path/bundle ID remain stable for grants.
python3 "$RELEASE_TOOL" --stamp-app "$DEST" >/dev/null
# Local-only signing; no notarization/upload and no timestamp-server request.
codesign --force --deep --timestamp=none --sign "$IDENTITY" "$DEST"
codesign --verify --deep --strict "$DEST"
echo "Installed $DISPLAY_NAME at $DEST"
echo 'Open Lip Local, then choose Enable Fn controls / check permissions in its menu.'
