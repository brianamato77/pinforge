package io.pinforge.upload

import java.io.ByteArrayOutputStream
import java.io.File

/** Contiguous firmware image starting at [base]. */
class Image(val base: Long, val data: ByteArray)

object IntelHex {
    fun parse(f: File): Image {
        val mem = sortedMapOf<Long, Byte>()
        var upper = 0L
        f.forEachLine { raw ->
            val line = raw.trim()
            if (!line.startsWith(":") || line.length < 11) return@forEachLine
            val bytes = line.substring(1).chunked(2).map { it.toInt(16) }
            val len = bytes[0]
            val addr = (bytes[1] shl 8) or bytes[2]
            when (bytes[3]) {
                0x00 -> for (i in 0 until len) mem[upper + addr + i] = bytes[4 + i].toByte()
                0x02 -> upper = (((bytes[4] shl 8) or bytes[5]).toLong()) shl 4
                0x04 -> upper = (((bytes[4] shl 8) or bytes[5]).toLong()) shl 16
            }
        }
        require(mem.isNotEmpty()) { "Empty hex file" }
        val base = mem.firstKey()
        val size = (mem.lastKey() - base + 1).toInt()
        val data = ByteArray(size) { 0xFF.toByte() }
        for ((a, b) in mem) data[(a - base).toInt()] = b
        return Image(base, data)
    }
}

object Uf2 {
    fun fromImage(img: Image, familyId: Long): ByteArray {
        val payload = 256
        val total = (img.data.size + payload - 1) / payload
        val out = ByteArrayOutputStream(total * 512)
        for (i in 0 until total) {
            val block = ByteArray(512)
            fun put(off: Int, v: Long) { for (k in 0 until 4) block[off + k] = (v shr (8 * k)).toByte() }
            put(0, 0x0A324655L); put(4, 0x9E5D5157L)
            put(8, 0x00002000L)                       // familyID present
            put(12, img.base + i.toLong() * payload)
            put(16, payload.toLong())
            put(20, i.toLong()); put(24, total.toLong())
            put(28, familyId)
            val start = i * payload
            val n = minOf(payload, img.data.size - start)
            System.arraycopy(img.data, start, block, 32, n)
            for (k in n until payload) block[32 + k] = 0xFF.toByte()
            put(508, 0x0AB16F30L)
            out.write(block)
        }
        return out.toByteArray()
    }
}

/** STK500v1 (Optiboot / old Nano bootloader) for ATmega328P boards. */
class Stk500(private val io: SerialIo, private val log: (String) -> Unit) {
    private val OK = byteArrayOf(0x14, 0x10)

    private fun reset() {
        io.dtr(false); io.rts(false); Thread.sleep(250)
        io.dtr(true); io.rts(true); Thread.sleep(50)
    }

    private fun xfer(cmd: ByteArray, respLen: Int, timeoutMs: Int = 500): ByteArray {
        io.write(cmd)
        val out = ByteArrayOutputStream()
        val end = System.currentTimeMillis() + timeoutMs
        while (out.size() < respLen && System.currentTimeMillis() < end) {
            out.write(io.read(50))
        }
        return out.toByteArray()
    }

    private fun expectOk(cmd: ByteArray, what: String) {
        val r = xfer(cmd, 2)
        if (r.size < 2 || r[0] != OK[0] || r[r.size - 1] != OK[1]) throw IllegalStateException("$what: no OK from bootloader")
    }

    fun flash(img: Image, progress: (Float) -> Unit) {
        require(img.base == 0L) { "Unexpected AVR base address ${img.base}" }
        var synced = false
        for (attempt in 0 until 4) {
            reset()
            io.drain()
            for (i in 0 until 8) {
                val r = xfer(byteArrayOf(0x30, 0x20), 2, 200)
                if (r.size >= 2 && r[0] == OK[0] && r[1] == OK[1]) { synced = true; break }
            }
            if (synced) break
        }
        if (!synced) throw IllegalStateException("AVR bootloader not responding (check board type / baud)")
        log("Bootloader in sync")
        expectOk(byteArrayOf(0x50, 0x20), "enter progmode")
        val page = 128
        val pages = (img.data.size + page - 1) / page
        for (p in 0 until pages) {
            val addr = p * page
            val word = addr / 2
            expectOk(byteArrayOf(0x55, (word and 0xFF).toByte(), (word shr 8).toByte(), 0x20), "load address")
            val chunk = ByteArray(page) { 0xFF.toByte() }
            System.arraycopy(img.data, addr, chunk, 0, minOf(page, img.data.size - addr))
            expectOk(byteArrayOf(0x64, 0x00, page.toByte(), 0x46) + chunk + byteArrayOf(0x20), "program page")
            progress((p + 1).toFloat() / (pages * 2))
        }
        // verify
        for (p in 0 until pages) {
            val addr = p * page
            val word = addr / 2
            expectOk(byteArrayOf(0x55, (word and 0xFF).toByte(), (word shr 8).toByte(), 0x20), "load address")
            val r = xfer(byteArrayOf(0x74, 0x00, page.toByte(), 0x46, 0x20), page + 2, 1000)
            if (r.size < page + 2) throw IllegalStateException("verify read short at 0x%X".format(addr))
            val n = minOf(page, img.data.size - addr)
            for (i in 0 until n) if (r[1 + i] != img.data[addr + i]) throw IllegalStateException("verify mismatch at 0x%X".format(addr + i))
            progress(0.5f + (p + 1).toFloat() / (pages * 2))
        }
        log("Verified ✓")
        expectOk(byteArrayOf(0x51, 0x20), "leave progmode")
        log("Done — sketch running.")
    }
}
