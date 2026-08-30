#!/usr/bin/env bash
#
# Download the speech models Sentry needs.
#
# They are not in git: three third-party binaries totalling ~140 MB, and the
# speaker model carries no licence statement, so vendoring them into a public
# repository is not something to do casually. Run this once before building.
set -euo pipefail

ASSETS="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets"
BASE="https://alphacephei.com/vosk/models"

# name-in-assets : archive : directory inside the archive
MODELS=(
    "model-en-in:vosk-model-small-en-in-0.4.zip:vosk-model-small-en-in-0.4"
    "model-en-us:vosk-model-small-en-us-0.15.zip:vosk-model-small-en-us-0.15"
    "model-spk:vosk-model-spk-0.4.zip:vosk-model-spk-0.4"
)

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v unzip >/dev/null || { echo "unzip is required" >&2; exit 1; }

mkdir -p "$ASSETS"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

for entry in "${MODELS[@]}"; do
    IFS=: read -r target archive inner <<<"$entry"

    if [ -d "$ASSETS/$target" ]; then
        echo "✓ $target already present"
        continue
    fi

    echo "↓ $archive"
    # --retry, because these are large files from a single small host and a
    # truncated model fails at runtime rather than at download time.
    curl -fL --retry 3 --retry-delay 2 -o "$tmp/$archive" "$BASE/$archive"

    unzip -tq "$tmp/$archive" >/dev/null || {
        echo "✗ $archive is corrupt — re-run this script" >&2
        exit 1
    }

    unzip -q "$tmp/$archive" -d "$tmp"
    mv "$tmp/$inner" "$ASSETS/$target"
    echo "✓ $target"
done

echo
echo "Done. Models are in app/src/main/assets/ and are gitignored."
