#!/usr/bin/env bash
# Assembles runtime-aarch64.tar.gz inside an arm64 Debian container (via qemu).
# On failure, the last log lines are emitted as workflow annotations so they're visible via the API.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$(pwd)/out"
mkdir -p "$out"
docker run --rm --platform linux/arm64 \
  -v "$here:/src:ro" -v "$out:/out" \
  debian:bullseye-slim bash /src/inside.sh 2>&1 | tee "$out/build.log"
rc=${PIPESTATUS[0]}
if [ "$rc" -ne 0 ]; then
  tail -n 25 "$out/build.log" | while IFS= read -r l; do echo "::error::${l//%/%25}"; done
  exit "$rc"
fi
ls -lh "$out"
