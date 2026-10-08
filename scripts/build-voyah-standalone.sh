#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export DIPLAY_AUTH_ASSETS_DIR="${DIPLAY_AUTH_ASSETS_DIR:-$PWD/local-auth}"
for name in identity.pk8 certificate.p7b; do
    test -s "$DIPLAY_AUTH_ASSETS_DIR/offline-mfi/$name" || { echo "Missing CarPlay authentication file: $name" >&2; exit 1; }
done
bash ./gradlew :mobile:assembleStandaloneDebug "$@"
