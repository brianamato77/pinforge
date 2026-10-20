package io.pinforge.model

enum class Role { SIG, ANALOG, VCC, GND, SDA, SCL, MOSI, MISO, SCK, CS }

/** A pin on a part. [input] = the MCU only reads it (so input-only MCU pins are fine). */
data class CPin(val name: String, val role: Role, val input: Boolean = false)

/**
 * A part you can drop on the wiring canvas. Code templates use {id} (instance, e.g. led1)
 * and {ID} (e.g. LED1). Each wired pin becomes `#define {ID}_{PIN} <board pin>` in wiring.h.
 */
class PartType(
    val id: String,
    val name: String,
    val desc: String,
    val pins: List<CPin>,
    val wants5v: Boolean = false,
    val archOnly: Set<Arch>? = null,
    val libs: (Arch) -> List<String> = { emptyList() },
    val includes: (Arch) -> List<String> = { emptyList() },
    val globals: String = "",
    val setup: String = "",
    val loop: String = "",
    val periodic: String = "",
) {
    val usesI2c get() = pins.any { it.role == Role.SDA }
    val usesSpi get() = pins.any { it.role == Role.SCK }
}

object Parts {
    private fun p(vararg s: String): List<CPin> = s.map {
        val (n, r) = it.split(":")
        val input = r.endsWith("<")
        CPin(n, Role.valueOf(r.removeSuffix("<")), input)
    }

    val all = listOf(
        PartType(
            "led", "LED", "LED with series resistor (220–330 Ω) to GND.", p("SIG:SIG", "GND:GND"),
            setup = "pinMode({ID}_SIG, OUTPUT);",
            loop = "digitalWrite({ID}_SIG, (millis() / 500) % 2);"
        ),
        PartType(
            "button", "Push button", "Momentary switch to GND; uses the internal pull-up.", p("SIG:SIG<", "GND:GND"),
            setup = "pinMode({ID}_SIG, INPUT_PULLUP);",
            periodic = "Serial.print(F(\"{id}: \")); Serial.println(digitalRead({ID}_SIG) == LOW ? F(\"pressed\") : F(\"released\"));"
        ),
        PartType(
            "pot", "Potentiometer", "10 kΩ pot: ends to VCC/GND, wiper to an analog pin.",
            p("VCC:VCC", "WIPER:ANALOG<", "GND:GND"),
            periodic = "Serial.print(F(\"{id}: \")); Serial.println(analogRead({ID}_WIPER));"
        ),
        PartType(
            "ldr", "Light sensor (LDR module)", "Photoresistor module with analog out.",
            p("VCC:VCC", "AO:ANALOG<", "GND:GND"),
            periodic = "Serial.print(F(\"{id} light: \")); Serial.println(analogRead({ID}_AO));"
        ),
        PartType(
            "pir", "PIR motion sensor", "HC-SR501 style; output is high on motion.", p("VCC:VCC", "OUT:SIG<", "GND:GND"),
            wants5v = true,
            setup = "pinMode({ID}_OUT, INPUT);",
            periodic = "Serial.print(F(\"{id} motion: \")); Serial.println(digitalRead({ID}_OUT));"
        ),
        PartType(
            "relay", "Relay module", "1-channel relay board (active-high IN).", p("VCC:VCC", "IN:SIG", "GND:GND"),
            wants5v = true,
            setup = "pinMode({ID}_IN, OUTPUT);",
            loop = "digitalWrite({ID}_IN, (millis() / 2000) % 2);"
        ),
        PartType(
            "buzzer", "Piezo buzzer", "Passive piezo; beeps once a second.", p("SIG:SIG", "GND:GND"),
            periodic = "tone({ID}_SIG, 1000, 50);"
        ),
        PartType(
            "servo", "Servo (SG90)", "Hobby servo; signal on a PWM-capable pin.", p("VCC:VCC", "SIG:SIG", "GND:GND"),
            wants5v = true,
            libs = { a -> when (a) { Arch.ESP32 -> listOf("ESP32Servo"); Arch.RP2040, Arch.NRF52 -> emptyList(); else -> listOf("Servo") } },
            includes = { a -> listOf(if (a == Arch.ESP32) "ESP32Servo.h" else "Servo.h") },
            globals = "Servo {id};",
            setup = "{id}.attach({ID}_SIG);",
            periodic = "{id}.write((millis() / 1000) % 2 ? 30 : 150);"
        ),
        PartType(
            "neopixel", "NeoPixel strip (WS2812)", "8-LED addressable strip; data in on DIN.",
            p("VCC:VCC", "DIN:SIG", "GND:GND"), wants5v = true,
            libs = { listOf("Adafruit NeoPixel") }, includes = { listOf("Adafruit_NeoPixel.h") },
            globals = "Adafruit_NeoPixel {id}(8, {ID}_DIN, NEO_GRB + NEO_KHZ800);",
            setup = "{id}.begin();\n{id}.setBrightness(40);",
            loop = "for (uint16_t i = 0; i < {id}.numPixels(); i++) {id}.setPixelColor(i, {id}.ColorHSV((uint16_t)(millis() * 20 + i * 8000)));\n{id}.show();"
        ),
        PartType(
            "dht22", "DHT22 temp/humidity", "Single-wire sensor; 10 kΩ pull-up on DATA (modules have one).",
            p("VCC:VCC", "DATA:SIG", "GND:GND"),
            libs = { listOf("DHT sensor library", "Adafruit Unified Sensor") }, includes = { listOf("DHT.h") },
            globals = "DHT {id}({ID}_DATA, DHT22);",
            setup = "{id}.begin();",
            periodic = "Serial.print(F(\"{id} C: \")); Serial.print({id}.readTemperature()); Serial.print(F(\"  RH%: \")); Serial.println({id}.readHumidity());"
        ),
        PartType(
            "ds18b20", "DS18B20 temperature", "1-Wire probe; 4.7 kΩ pull-up DATA→VCC.", p("VCC:VCC", "DATA:SIG", "GND:GND"),
            libs = { listOf("OneWire", "DallasTemperature") }, includes = { listOf("OneWire.h", "DallasTemperature.h") },
            globals = "OneWire {id}Bus({ID}_DATA);\nDallasTemperature {id}(&{id}Bus);",
            setup = "{id}.begin();",
            periodic = "{id}.requestTemperatures(); Serial.print(F(\"{id} C: \")); Serial.println({id}.getTempCByIndex(0));"
        ),
        PartType(
            "hcsr04", "HC-SR04 ultrasonic", "Distance sensor. ECHO is 5 V — use a divider on 3.3 V boards.",
            p("VCC:VCC", "TRIG:SIG", "ECHO:SIG<", "GND:GND"), wants5v = true,
            setup = "pinMode({ID}_TRIG, OUTPUT);\npinMode({ID}_ECHO, INPUT);",
            periodic = "digitalWrite({ID}_TRIG, LOW); delayMicroseconds(2); digitalWrite({ID}_TRIG, HIGH); delayMicroseconds(10); digitalWrite({ID}_TRIG, LOW);\n" +
                "Serial.print(F(\"{id} cm: \")); Serial.println(pulseIn({ID}_ECHO, HIGH, 30000UL) / 58.0);"
        ),
        PartType(
            "ssd1306", "OLED 128×64 (SSD1306 I²C)", "0.96\" I²C OLED at address 0x3C.",
            p("VCC:VCC", "GND:GND", "SCL:SCL", "SDA:SDA"),
            libs = { listOf("Adafruit SSD1306", "Adafruit GFX Library") },
            includes = { listOf("Adafruit_GFX.h", "Adafruit_SSD1306.h") },
            globals = "Adafruit_SSD1306 {id}(128, 64, &Wire, -1);",
            setup = "if (!{id}.begin(SSD1306_SWITCHCAPVCC, 0x3C)) Serial.println(F(\"{id} not found\"));\n" +
                "{id}.clearDisplay(); {id}.setTextColor(SSD1306_WHITE); {id}.setTextSize(2);\n" +
                "{id}.setCursor(0, 0); {id}.print(F(\"PinForge\")); {id}.display();"
        ),
        PartType(
            "bme280", "BME280 env sensor (I²C)", "Temp / humidity / pressure at 0x76.",
            p("VCC:VCC", "GND:GND", "SCL:SCL", "SDA:SDA"),
            libs = { listOf("Adafruit BME280 Library", "Adafruit Unified Sensor") },
            includes = { listOf("Adafruit_BME280.h") },
            globals = "Adafruit_BME280 {id};",
            setup = "if (!{id}.begin(0x76)) Serial.println(F(\"{id} not found\"));",
            periodic = "Serial.print(F(\"{id} C: \")); Serial.print({id}.readTemperature()); Serial.print(F(\"  hPa: \")); Serial.println({id}.readPressure() / 100.0);"
        ),
        PartType(
            "mpu6050", "MPU-6050 IMU (I²C)", "Accelerometer + gyro at 0x68.",
            p("VCC:VCC", "GND:GND", "SCL:SCL", "SDA:SDA"),
            libs = { listOf("Adafruit MPU6050", "Adafruit Unified Sensor") },
            includes = { listOf("Adafruit_MPU6050.h", "Adafruit_Sensor.h") },
            globals = "Adafruit_MPU6050 {id};",
            setup = "if (!{id}.begin()) Serial.println(F(\"{id} not found\"));",
            periodic = "{ sensors_event_t a, g, t; {id}.getEvent(&a, &g, &t); Serial.print(F(\"{id} ax: \")); Serial.println(a.acceleration.x); }"
        ),
        PartType(
            "sdcard", "microSD card (SPI)", "SPI card module; CS on any digital pin.",
            p("VCC:VCC", "GND:GND", "MISO:MISO<", "MOSI:MOSI", "SCK:SCK", "CS:CS"),
            libs = { a -> if (a == Arch.AVR || a == Arch.SAMD) listOf("SD") else emptyList() },
            includes = { listOf("SD.h") },
            setup = "if (!SD.begin({ID}_CS)) Serial.println(F(\"{id}: no card\"));"
        ),
        PartType(
            "wifi_ota", "Wi-Fi + OTA updates", "Joins Wi-Fi and accepts uploads from PinForge over the air. Edit the SSID/password.",
            emptyList(), archOnly = setOf(Arch.ESP32),
            includes = { listOf("WiFi.h", "ArduinoOTA.h") },
            globals = "const char* WIFI_SSID = \"your-ssid\";\nconst char* WIFI_PASS = \"your-password\";",
            setup = "WiFi.mode(WIFI_STA);\nWiFi.begin(WIFI_SSID, WIFI_PASS);\n" +
                "while (WiFi.status() != WL_CONNECTED && millis() < 15000) delay(250);\n" +
                "Serial.print(F(\"IP: \")); Serial.println(WiFi.localIP());\n" +
                "ArduinoOTA.setHostname(\"pinforge-{id}\");\nArduinoOTA.begin();",
            loop = "ArduinoOTA.handle();"
        ),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }
}
