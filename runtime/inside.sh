#!/usr/bin/env bash
# Runs inside debian:bullseye-slim (arm64). glibc 2.31 is used on purpose: newer glibc
# (2.34+) calls clone3/rseq/faccessat2 at startup, which Android's app seccomp kills (SIGSYS).
# Produces /out/runtime-aarch64.tar.gz with:
#   bin/  arduino-cli (static Go), bash, python3, shims
#   lib/  glibc + shared libs the toolchains need (ld-linux-aarch64.so.1 is the loader)
#   py/   python stdlib (PYTHONHOME)
# On the phone the app rewrites each ELF's interpreter to /data/data/io.pinforge/l.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
# Bullseye is EOL: fall back to archive.debian.org when the live mirrors drop it.
if ! apt-get update -qq; then
  echo "deb http://archive.debian.org/debian bullseye main" > /etc/apt/sources.list
  echo "deb http://archive.debian.org/debian-security bullseye-security main" >> /etc/apt/sources.list
  rm -f /etc/apt/sources.list.d/*
  apt-get -o Acquire::Check-Valid-Until=false update -qq
fi
apt-get install -y -qq --no-install-recommends \
  bash python3-minimal libpython3-stdlib ca-certificates curl \
  libstdc++6 libgcc-s1 zlib1g libzstd1 liblzma5 libbz2-1.0 libexpat1 \
  libncursesw6 libtinfo6 libffi7 libssl1.1 libusb-1.0-0 libudev1 libuuid1 >/dev/null

RT=/tmp/rt
mkdir -p "$RT/bin" "$RT/lib" "$RT/py/lib"

# Shared libraries (dereferenced so the tarball holds no symlinks)
for d in /lib/aarch64-linux-gnu /usr/lib/aarch64-linux-gnu; do
  find "$d" -maxdepth 1 \( -type f -o -type l \) -name '*.so.*' -exec cp -L {} "$RT/lib/" \; 2>/dev/null || true
done
cp -L /lib/ld-linux-aarch64.so.1 "$RT/lib/ld-linux-aarch64.so.1"
# Unversioned names could shadow Android's bionic libs via LD_LIBRARY_PATH; drop them.
find "$RT/lib" -name '*.so' -delete

# bash + python
cp -L /bin/bash "$RT/bin/bash"
PYV="$(python3 -c 'import sys;print("%d.%d" % sys.version_info[:2])')"
cp -L "/usr/bin/python$PYV" "$RT/bin/python3"
cp -L "/usr/bin/python$PYV" "$RT/bin/python"
cp -a "/usr/lib/python$PYV" "$RT/py/lib/"
rm -rf "$RT/py/lib/python$PYV/"{test,idlelib,tkinter,ensurepip,lib2to3,turtledemo}
ldd --version | head -1

# arduino-cli (statically linked Go binary)
curl -fsSL https://downloads.arduino.cc/arduino-cli/arduino-cli_latest_Linux_ARM64.tar.gz \
  | tar -xz -C "$RT/bin" arduino-cli
"$RT/bin/arduino-cli" version || true

# Shims for host tools that some cores call but PinForge doesn't need on a phone
cat > "$RT/bin/adafruit-nrfutil" <<'EOF'
#!/system/bin/sh
echo "adafruit-nrfutil skipped on Android (PinForge uploads nRF52 via UF2)"
exit 0
EOF
chmod 755 "$RT/bin/"*

cp /src/VERSION "$RT/VERSION"
tar --owner=0 --group=0 -czf /out/runtime-aarch64.tar.gz -C "$RT" .
