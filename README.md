# PinForge

Arduino-style IDE for Android: write sketches, wire parts to a board on a canvas, pull board
packages and libraries, compile **on the phone**, and upload over USB-OTG, Wi-Fi OTA or UF2.

## Install

1. Push this repo to GitHub (`main` branch). The **build** workflow:
   - builds the toolchain runtime (glibc, bash, python3, arduino-cli for arm64) → release `runtime`
   - builds the APK → release `latest` (`PinForge.apk`) and a workflow artifact
2. On the phone open the `latest` release, download `PinForge.apk`, allow "Install unknown apps".
3. Open PinForge → gear icon → **Install** (one-time ~60 MB). Run **Self-test**.
4. Packages → Boards → install the cores you need (ESP32 is ~1.5 GB unpacked).

## Boards

| Board | Upload |
|---|---|
| Arduino Uno, Nano (new + old bootloader) | USB, STK500 (native) |
| ESP32 DevKitC, ESP32-S3 DevKitC, ESP32-C3 SuperMini, Adafruit Feather ESP32-S3 | USB (native ROM-loader flasher) or Wi-Fi OTA |
| Raspberry Pi Pico | UF2 drive |
| Adafruit Feather M4 Express, Feather nRF52840 | UF2 drive |

Boards are defined in `app/src/main/java/io/pinforge/model/Boards.kt` (pinout + fqbn); add more there.

## How on-phone compiling works

Arduino publishes linux-aarch64 toolchains, but they're built for glibc and expect
`/lib/ld-linux-aarch64.so.1`, which Android doesn't have. PinForge:

- ships glibc's loader at `/data/data/io.pinforge/l` and rewrites each toolchain ELF's
  `PT_INTERP` to that path (`ElfPatcher.kt`) after every core install;
- targets SDK 28 so it may execute files in its own data dir (same approach as Termux);
- runs a localhost HTTP proxy so arduino-cli (Go, no `/etc/resolv.conf` on Android) never needs DNS.

If a toolchain fails with "permission denied" or "not found", use **Re-patch** in setup.
Keep the package id short — the loader path must fit the 27-byte interpreter slot.

## Wiring canvas

Add parts, tap a part pin then a board pin to wire, drag to move, long-press a pin to unwire.
**Auto-wire** fills open pins sensibly (power/GND/I²C/SPI/analog, avoiding boot pins).
**Generate code** writes `wiring.h` (`#define LED1_SIG 13` …, `PF_WIRE_BEGIN()`, `PF_SPI_BEGIN()`),
optionally a full starter sketch, and installs the parts' libraries.
Part templates live in `model/Components.kt`.

## Known limits

- Mega 2560 (STK500v2) and Leonardo/Micro (AVR109) uploaders aren't implemented yet.
- BLE is a Nordic UART serial console/config channel, not a firmware uploader.
- ESP32 native-USB boards may need BOOT held to enter the bootloader.
- Long compiles stop if Android kills the app in the background; the screen is kept on while busy.
