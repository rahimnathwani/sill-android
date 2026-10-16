#!/bin/bash
# Takes a screenshot of the connected phone for the Play listing: scripts/screenshots.sh <name>
# Hide anything private on the Mac first. Saves play/graphics/screenshots/<name>.png.
set -euo pipefail
cd "$(dirname "$0")/.."
[[ -n "${1:-}" ]] || { echo "usage: scripts/screenshots.sh <name>" >&2; exit 2; }
mkdir -p play/graphics/screenshots
adb exec-out screencap -p > "play/graphics/screenshots/$1.png"
echo "play/graphics/screenshots/$1.png"
