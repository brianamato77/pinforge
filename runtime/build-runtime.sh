#!/usr/bin/env bash
# Assembles runtime-aarch64.tar.gz inside an arm64 Debian container (via qemu).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$(pwd)/out"
mkdir -p "$out"
docker run --rm --platform linux/arm64 \
  -v "$here:/src:ro" -v "$out:/out" \
  debian:bullseye-slim bash /src/inside.sh
ls -lh "$out"
