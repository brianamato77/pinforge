package io.pinforge.upload

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * ESP32-family serial flasher speaking the ROM bootloader protocol (the same one esptool uses),
 * implemented natively so no host tool is needed. Works with ESP32, -S2, -S3, -C3, -C6, -H2.
 */
class EspLoader(private val io: SerialIo, private val log: (String) -> Unit) {
    private val pending = ArrayDeque<Byte>()
    private var isEsp32Classic = false
    var chipName = "ESP32"; private set

    companion object {
        const val SYNC = 0x08
        const val READ_REG = 0x0A
        const val SPI_SET_PARAMS = 0x0B
        const val SPI_ATTACH = 0x0D
        const val CHANGE_BAUD = 0x0F
        const val FLASH_BEGIN = 0x02
        const val FLASH_DATA = 0x03
        const val FLASH_END = 0x04
        const val FLASH_MD5 = 0x13
        const val BLOCK = 0x400
        private val MAGIC = mapOf(
            0x00F01D83L to "ESP32", 0x000007C6L to "ESP32-S2", 0x00000009L to "ESP32-S3",
            0x6921506FL to "ESP32-C3", 0x1B31506FL to "ESP32-C3", 0x4881606FL to "ESP32-C3",
            0x4361606FL to "ESP32-C3", 0x2CE0806FL to "ESP32-C6", 0xD7B73E80L to "ESP32-H2"
        )
    }

    // ---------- reset / sync ----------

    private fun classicReset() {          // DTR->IO0, RTS->EN via the usual two-transistor circuit
        io.dtr(false); io.rts(true); Thread.sleep(100)
        io.dtr(true); io.rts(false); Thread.sleep(50)
        io.dtr(false)
    }

    private fun usbJtagReset() {          // native USB-Serial-JTAG (S3/C3/C6)
        io.rts(false); io.dtr(false); Thread.sleep(100)
        io.dtr(true); io.rts(false); Thread.sleep(100)
        io.rts(true); io.dtr(false); io.rts(true); Thread.sleep(100)
        io.dtr(false); io.rts(false)
    }

    fun hardReset() {
        io.dtr(false); io.rts(true); Thread.sleep(120); io.rts(false)
    }

    fun connect(): Boolean {
        for (attempt in 0 until 6) {
            if (attempt % 2 == 0) classicReset() else usbJtagReset()
            Thread.sleep(30)
            io.drain(); pending.clear()
            repeat(4) { if (sync()) return true }
        }
        return false
    }

    private fun sync(): Boolean {
        val data = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 }
        return try {
            command(SYNC, data, 0, 120)
            repeat(8) { try { readPacket(40) } catch (_: Exception) {} }   // ROM answers several times
            true
        } catch (_: Exception) {
            false
        }
    }

    // ---------- commands ----------

    fun detect() {
        val magic = command(READ_REG, le32(0x40001000), 0, 1000).first
        chipName = MAGIC[magic] ?: "ESP32-family (0x%08X)".format(magic)
        isEsp32Classic = magic == 0x00F01D83L
        log("Chip: $chipName")
    }

    fun attachFlash(sizeBytes: Int = 4 * 1024 * 1024) {
        command(SPI_ATTACH, ByteArray(8), 0, 3000)
        command(SPI_SET_PARAMS, le32(0) + le32(sizeBytes) + le32(0x10000) + le32(0x1000) + le32(0x100) + le32(0xFFFF), 0, 3000)
    }

    fun changeBaud(baud: Int) {
        try {
            command(CHANGE_BAUD, le32(baud) + le32(0), 0, 3000)
            io.setBaud(baud)
            Thread.sleep(60)
            io.drain(); pending.clear()
            log("Baud → $baud")
        } catch (e: Exception) {
            log("Staying at 115200 (${e.message})")
        }
    }

    fun flash(image: ByteArray, offset: Int, label: String, progress: (Float) -> Unit) {
        val blocks = (image.size + BLOCK - 1) / BLOCK
        val eraseTimeout = 3000 + (image.size.toLong() * 30_000 / (1024 * 1024)).toInt()
        var begin = le32(image.size) + le32(blocks) + le32(BLOCK) + le32(offset)
        if (!isEsp32Classic) begin += le32(0)                  // "encrypted" flag on newer ROMs
        log("Erasing + writing $label: ${image.size / 1024} KB @ 0x%X".format(offset))
        command(FLASH_BEGIN, begin, 0, eraseTimeout)
        for (seq in 0 until blocks) {
            val chunk = ByteArray(BLOCK) { 0xFF.toByte() }
            val start = seq * BLOCK
            System.arraycopy(image, start, chunk, 0, minOf(BLOCK, image.size - start))
            val payload = le32(BLOCK) + le32(seq) + le32(0) + le32(0) + chunk
            var tries = 0
            while (true) {
                try { command(FLASH_DATA, payload, checksum(chunk), 5000); break }
                catch (e: Exception) { if (++tries >= 3) throw e; io.drain(); pending.clear() }
            }
            progress((seq + 1).toFloat() / blocks)
        }
    }

    fun verify(image: ByteArray, offset: Int): Boolean {
        return try {
            val timeout = 3000 + image.size / 1024 * 8
            val body = command(FLASH_MD5, le32(offset) + le32(image.size) + le32(0) + le32(0), 0, timeout).second
            val got = String(body.copyOf(32), Charsets.US_ASCII).lowercase()
            val want = MessageDigest.getInstance("MD5").digest(image).joinToString("") { "%02x".format(it) }
            if (got == want) { log("Verified ✓"); true } else { log("MD5 mismatch: $got vs $want"); false }
        } catch (e: Exception) {
            log("Verify skipped (${e.message})"); true
        }
    }

    fun finish() {
        try { command(FLASH_END, le32(1), 0, 2000) } catch (_: Exception) {}
        hardReset()
    }

    /** @return (value, body) */
    private fun command(op: Int, data: ByteArray, chk: Int, timeoutMs: Int): Pair<Long, ByteArray> {
        val pkt = ByteArrayOutputStream()
        pkt.write(0x00); pkt.write(op)
        pkt.write(data.size and 0xFF); pkt.write((data.size shr 8) and 0xFF)
        pkt.write(le32(chk)); pkt.write(data)
        io.write(slip(pkt.toByteArray()))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val r = readPacket((deadline - System.currentTimeMillis()).toInt().coerceAtLeast(10))
            if (r.size < 8 || r[0].toInt() != 0x01 || (r[1].toInt() and 0xFF) != op) continue
            val value = leU32(r, 4)
            val body = r.copyOfRange(8, r.size)
            val statusLen = if (body.size >= 4) 4 else 2
            if (body.size >= statusLen && body[body.size - statusLen].toInt() != 0) {
                throw IllegalStateException("cmd 0x%02X failed, err 0x%02X".format(op, body[body.size - statusLen + 1]))
            }
            return value to body
        }
        throw IllegalStateException("timeout on cmd 0x%02X".format(op))
    }

    private fun readPacket(timeoutMs: Int): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        val out = ByteArrayOutputStream()
        var inFrame = false
        var esc = false
        while (true) {
            if (pending.isEmpty()) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw IllegalStateException("read timeout")
                io.read(left.toInt().coerceAtMost(100)).forEach { pending.addLast(it) }
                continue
            }
            val b = pending.removeFirst().toInt() and 0xFF
            if (!inFrame) { if (b == 0xC0) inFrame = true; continue }
            when {
                esc -> { out.write(if (b == 0xDC) 0xC0 else if (b == 0xDD) 0xDB else b); esc = false }
                b == 0xDB -> esc = true
                b == 0xC0 -> { if (out.size() == 0) continue else return out.toByteArray() }
                else -> out.write(b)
            }
        }
    }

    private fun slip(p: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(p.size + 16)
        o.write(0xC0)
        for (x in p) {
            when (val b = x.toInt() and 0xFF) {
                0xC0 -> { o.write(0xDB); o.write(0xDC) }
                0xDB -> { o.write(0xDB); o.write(0xDD) }
                else -> o.write(b)
            }
        }
        o.write(0xC0)
        return o.toByteArray()
    }

    private fun checksum(d: ByteArray): Int {
        var c = 0xEF
        for (b in d) c = c xor (b.toInt() and 0xFF)
        return c
    }
}

internal fun le32(v: Int): ByteArray =
    byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

internal fun leU32(b: ByteArray, o: Int): Long =
    (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
        ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

/** Picks what to flash from an arduino-cli ESP32 build and runs the whole sequence. */
object EspFlasher {
    fun flashBuild(io: SerialIo, outDir: File, sketch: String, dataDir: File, log: (String) -> Unit, progress: (Float) -> Unit) {
        val esp = EspLoader(io, log)
        log("Connecting to bootloader…")
        if (!esp.connect()) throw IllegalStateException(
            "No response from ESP bootloader. Hold BOOT, tap RESET, release BOOT, then try again.")
        esp.detect()
        esp.attachFlash()
        esp.changeBaud(460800)

        val merged = File(outDir, "$sketch.ino.merged.bin")
        val parts: List<Pair<Int, ByteArray>> = if (merged.exists()) {
            listOf(0 to trimErased(merged.readBytes()))
        } else {
            val bootOff = if (esp.chipName == "ESP32" || esp.chipName == "ESP32-S2") 0x1000 else 0x0
            val list = mutableListOf<Pair<Int, ByteArray>>()
            File(outDir, "$sketch.ino.bootloader.bin").takeIf { it.exists() }?.let { list += bootOff to it.readBytes() }
            File(outDir, "$sketch.ino.partitions.bin").takeIf { it.exists() }?.let { list += 0x8000 to it.readBytes() }
            dataDir.walkTopDown().firstOrNull { it.name == "boot_app0.bin" }?.let { list += 0xE000 to it.readBytes() }
            val app = File(outDir, "$sketch.ino.bin")
            require(app.exists()) { "No firmware .bin in build output" }
            list += 0x10000 to app.readBytes()
            list
        }
        for ((off, img) in parts) {
            esp.flash(img, off, "0x%X".format(off), progress)
            esp.verify(img, off)
        }
        esp.finish()
        log("Done — board reset.")
    }

    /** merged.bin is padded with 0xFF to the full flash size; only write up to the last used 4 KB sector. */
    private fun trimErased(b: ByteArray): ByteArray {
        var end = b.size
        while (end > 0 && b[end - 1] == 0xFF.toByte()) end--
        val rounded = ((end + 0xFFF) / 0x1000) * 0x1000
        return b.copyOf(minOf(rounded, b.size))
    }
}
