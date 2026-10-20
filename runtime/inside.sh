#!/usr/bin/env bash
# Runs inside debian:bookworm-slim (arm64). Produces /out/runtime-aarch64.tar.gz with:
#   bin/  arduino-cli (static Go), bash, python3, shims
#   lib/  glibc + shared libs the toolchains need (ld-linux-aarch64.so.1 is the loader)
#   py/   python stdlib (PYTHONHOME)
# On the phone the app rewrites each ELF's interpreter to /data/data/io.pinforge/l.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq --no-install-recommends \
  bash python3-minimal libpython3.11-stdlib ca-certificates curl \
  libstdc++6 libgcc-s1 zlib1g libzstd1 liblzma5 libbz2-1.0 libexpat1 \
  libncursesw6 libtinfo6 libffi8 libssl3 libusb-1.0-0 libudev1 libuuid1 >/dev/null

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
cp -L /usr/bin/python3.11 "$RT/bin/python3"
cp -L /usr/bin/python3.11 "$RT/bin/python"
cp -a /usr/lib/python3.11 "$RT/py/lib/"
rm -rf "$RT/py/lib/python3.11/"{test,idlelib,tkinter,ensurepip,lib2to3,turtledemo}

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
