package io.pinforge.model

enum class Arch { AVR, ESP32, SAMD, NRF52, RP2040 }
enum class Uploader { ESPTOOL, STK500, UF2 }

/**
 * A header pin. [code] is what goes in the sketch (#define X_PIN <code>); null = not assignable.
 * Caps: D digital, IN input-only, A analog, P PWM, SDA/SCL, MOSI/MISO/SCK, RX/TX,
 * X avoid (boot strapping / USB / serial), PWR3/PWR5 power out, GND, NC no-connect.
 */
data class Pin(val label: String, val code: String?, val caps: Set<String>) {
    fun has(c: String) = c in caps
    val isGnd get() = has("GND")
    val isPower get() = has("PWR3") || has("PWR5")
}

data class Board(
    val id: String,
    val name: String,
    val fqbn: String,
    val core: String,
    val arch: Arch,
    val uploader: Uploader,
    val baud: Int = 115200,
    val uf2Family: Long = 0,
    val volts: String = "3.3V",
    val left: List<Pin>,
    val right: List<Pin>,
    val note: String = ""
) {
    fun pin(key: String): Pin? {
        val i = key.drop(1).toIntOrNull() ?: return null
        return when (key.firstOrNull()) {
            'L' -> left.getOrNull(i)
            'R' -> right.getOrNull(i)
            else -> null
        }
    }

    fun keyed(): List<Pair<String, Pin>> =
        left.mapIndexed { i, p -> "L$i" to p } + right.mapIndexed { i, p -> "R$i" to p }

    val supportsOta get() = arch == Arch.ESP32
}

data class CoreInfo(val id: String, val name: String, val sizeHint: String)

private fun pins(spec: String): List<Pin> = spec.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { e ->
    val p = e.split("|")
    Pin(
        p[0].trim(),
        p.getOrNull(1)?.trim()?.ifEmpty { null },
        p.getOrNull(2)?.trim()?.split(" ")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    )
}

object Boards {
    val cores = listOf(
        CoreInfo("arduino:avr", "Arduino AVR (Uno, Nano)", "~150 MB"),
        CoreInfo("esp32:esp32", "Espressif ESP32 (all variants)", "~1.5 GB"),
        CoreInfo("rp2040:rp2040", "Raspberry Pi Pico / RP2040 (Earle Philhower)", "~600 MB"),
        CoreInfo("adafruit:samd", "Adafruit SAMD (Feather M0/M4)", "~500 MB"),
        CoreInfo("adafruit:nrf52", "Adafruit nRF52 (Feather nRF52840)", "~500 MB"),
    )

    private val featherRight = pins(
        "BAT||NC, EN||NC, USB||PWR5, 13|13|D P, 12|12|D P, 11|11|D P, 10|10|D P, 9|9|D A P, " +
            "6|6|D P, 5|5|D P, SCL|SCL|D SCL, SDA|SDA|D SDA"
    )

    val all = listOf(
        Board(
            "uno", "Arduino Uno", "arduino:avr:uno", "arduino:avr", Arch.AVR, Uploader.STK500, 115200, volts = "5V",
            left = pins(
                "IOREF||NC, RESET||NC, 3V3||PWR3, 5V||PWR5, GND||GND, GND||GND, VIN||NC, " +
                    "A0|A0|D A, A1|A1|D A, A2|A2|D A, A3|A3|D A, A4|A4|D A SDA, A5|A5|D A SCL"
            ),
            right = pins(
                "SCL|A5|SCL, SDA|A4|SDA, AREF||NC, GND||GND, D13|13|D SCK, D12|12|D MISO, D11|11|D P MOSI, " +
                    "D10|10|D P, D9|9|D P, D8|8|D, D7|7|D, D6|6|D P, D5|5|D P, D4|4|D, D3|3|D P, D2|2|D, " +
                    "D1|1|TX X, D0|0|RX X"
            )
        ),
        Board(
            "nano", "Arduino Nano", "arduino:avr:nano:cpu=atmega328", "arduino:avr", Arch.AVR, Uploader.STK500, 115200, volts = "5V",
            left = nanoLeft(), right = nanoRight()
        ),
        Board(
            "nano_old", "Arduino Nano (old bootloader / clones)", "arduino:avr:nano:cpu=atmega328old", "arduino:avr",
            Arch.AVR, Uploader.STK500, 57600, volts = "5V", left = nanoLeft(), right = nanoRight()
        ),
        Board(
            "esp32", "ESP32 DevKitC (38-pin)", "esp32:esp32:esp32", "esp32:esp32", Arch.ESP32, Uploader.ESPTOOL,
            left = pins(
                "3V3||PWR3, EN||NC, VP|36|A IN, VN|39|A IN, 34|34|A IN, 35|35|A IN, 32|32|D A P, 33|33|D A P, " +
                    "25|25|D A P, 26|26|D A P, 27|27|D A P, 14|14|D A P, 12|12|D A P X, GND||GND, 13|13|D A P, " +
                    "SD2||NC, SD3||NC, CMD||NC, 5V||PWR5"
            ),
            right = pins(
                "GND||GND, 23|23|D P MOSI, 22|22|D P SCL, TX|1|TX X, RX|3|RX X, 21|21|D P SDA, GND||GND, " +
                    "19|19|D P MISO, 18|18|D P SCK, 5|5|D P X, 17|17|D P, 16|16|D P, 4|4|D A P, 0|0|D P X, " +
                    "2|2|D A P X, 15|15|D A P X, SD1||NC, SD0||NC, CLK||NC"
            )
        ),
        Board(
            "esp32s3", "ESP32-S3 DevKitC-1", "esp32:esp32:esp32s3", "esp32:esp32", Arch.ESP32, Uploader.ESPTOOL,
            left = pins(
                "3V3||PWR3, 3V3||PWR3, RST||NC, 4|4|D A P, 5|5|D A P, 6|6|D A P, 7|7|D A P, 15|15|D A P, " +
                    "16|16|D A P, 17|17|D A P, 18|18|D A P, 8|8|D A P SDA, 3|3|D A P X, 46|46|D P X, 9|9|D A P SCL, " +
                    "10|10|D A P, 11|11|D A P MOSI, 12|12|D A P SCK, 13|13|D A P MISO, 14|14|D A P, 5V||PWR5, GND||GND"
            ),
            right = pins(
                "GND||GND, TX|43|TX X, RX|44|RX X, 1|1|D A P, 2|2|D A P, 42|42|D P, 41|41|D P, 40|40|D P, " +
                    "39|39|D P, 38|38|D P, 37|37|D P X, 36|36|D P X, 35|35|D P X, 0|0|D P X, 45|45|D P X, " +
                    "48|48|D P, 47|47|D P, 21|21|D P, 20|20|D A P X, 19|19|D A P X, GND||GND, GND||GND"
            ),
            note = "Use the UART port (CH343) for uploads; 35-37 are taken by octal PSRAM on N8R8 modules."
        ),
        Board(
            "esp32c3", "ESP32-C3 SuperMini", "esp32:esp32:esp32c3:CDCOnBoot=cdc", "esp32:esp32", Arch.ESP32, Uploader.ESPTOOL,
            left = pins("5|5|D A P MISO, 6|6|D P MOSI, 7|7|D P, 8|8|D P SDA X, 9|9|D P SCL X, 10|10|D P, RX|20|RX, TX|21|TX"),
            right = pins("5V||PWR5, GND||GND, 3V3||PWR3, 4|4|D A P SCK, 3|3|D A P, 2|2|D A P X, 1|1|D A P, 0|0|D A P"),
            note = "Native USB. If it won't connect: hold BOOT, plug in, release."
        ),
        Board(
            "feather_s3", "Adafruit Feather ESP32-S3", "esp32:esp32:adafruit_feather_esp32s3", "esp32:esp32",
            Arch.ESP32, Uploader.ESPTOOL,
            left = featherLeft(rx = "RX", tx = "TX"), right = featherRight,
            note = "Native USB. If upload can't connect: hold BOOT, tap RESET, release BOOT."
        ),
        Board(
            "pico", "Raspberry Pi Pico", "rp2040:rp2040:rpipico", "rp2040:rp2040", Arch.RP2040, Uploader.UF2,
            uf2Family = 0xE48BFF56L,
            left = pins(
                "GP0|0|D P TX, GP1|1|D P RX, GND||GND, GP2|2|D P, GP3|3|D P, GP4|4|D P SDA, GP5|5|D P SCL, GND||GND, " +
                    "GP6|6|D P, GP7|7|D P, GP8|8|D P, GP9|9|D P, GND||GND, GP10|10|D P, GP11|11|D P, GP12|12|D P, " +
                    "GP13|13|D P, GND||GND, GP14|14|D P, GP15|15|D P"
            ),
            right = pins(
                "VBUS||PWR5, VSYS||NC, GND||GND, 3V3_EN||NC, 3V3||PWR3, ADC_VREF||NC, GP28|28|D A P, AGND||GND, " +
                    "GP27|27|D A P, GP26|26|D A P, RUN||NC, GP22|22|D P, GND||GND, GP21|21|D P, GP20|20|D P, " +
                    "GP19|19|D P MOSI, GP18|18|D P SCK, GND||GND, GP17|17|D P, GP16|16|D P MISO"
            )
        ),
        Board(
            "feather_m4", "Adafruit Feather M4 Express", "adafruit:samd:adafruit_feather_m4", "adafruit:samd",
            Arch.SAMD, Uploader.UF2, uf2Family = 0x55114460L,
            left = featherLeft(rx = "0", tx = "1", last = "D4|4|D P"), right = featherRight
        ),
        Board(
            "feather_nrf52840", "Adafruit Feather nRF52840", "adafruit:nrf52:feather52840", "adafruit:nrf52",
            Arch.NRF52, Uploader.UF2, uf2Family = 0xADA52840L,
            left = featherLeft(rx = null, tx = null, last = "D2|2|D P"), right = featherRight
        ),
    )

    fun byId(id: String?): Board = all.firstOrNull { it.id == id } ?: all.first()

    private fun nanoLeft() = pins(
        "D1|1|TX X, D0|0|RX X, RST||NC, GND||GND, D2|2|D, D3|3|D P, D4|4|D, D5|5|D P, D6|6|D P, D7|7|D, " +
            "D8|8|D, D9|9|D P, D10|10|D P, D11|11|D P MOSI, D12|12|D MISO"
    )

    private fun nanoRight() = pins(
        "D13|13|D SCK, 3V3||PWR3, AREF||NC, A0|A0|D A, A1|A1|D A, A2|A2|D A, A3|A3|D A, A4|A4|D A SDA, " +
            "A5|A5|D A SCL, A6|A6|A IN, A7|A7|A IN, 5V||PWR5, RST||NC, GND||GND, VIN||NC"
    )

    private fun featherLeft(rx: String?, tx: String?, last: String = "NC||NC") = pins(
        "RST||NC, 3V||PWR3, AREF||NC, GND||GND, A0|A0|D A, A1|A1|D A, A2|A2|D A, A3|A3|D A, A4|A4|D A, " +
            "A5|A5|D A, SCK|SCK|D SCK, MO|MOSI|D MOSI, MI|MISO|D MISO, " +
            "RX|${rx ?: ""}|${if (rx != null) "D " else ""}RX, TX|${tx ?: ""}|${if (tx != null) "D " else ""}TX, $last"
    )
}
