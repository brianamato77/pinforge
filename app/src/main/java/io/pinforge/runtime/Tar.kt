package io.pinforge.runtime

import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** Minimal tar.gz extractor: regular files, dirs, symlinks, GNU long names and pax paths. */
object Tar {
    fun extractTarGz(src: File, dest: File) {
        dest.mkdirs()
        val root = dest.canonicalPath
        GZIPInputStream(BufferedInputStream(FileInputStream(src), 1 shl 16)).use { inp ->
            val header = ByteArray(512)
            var longName: String? = null
            var paxPath: String? = null
            while (true) {
                if (!readFully(inp, header)) break
                if (header.all { it.toInt() == 0 }) break
                val type = header[156].toInt().toChar()
                val size = parseSize(header)
                when (type) {
                    'L' -> { longName = String(readData(inp, size), Charsets.UTF_8).trimEnd('\u0000'); continue }
                    'x' -> { paxPath = parsePax(String(readData(inp, size), Charsets.UTF_8)); continue }
                    'g' -> { readData(inp, size); continue }
                }
                var name = cstr(header, 0, 100)
                val magic = cstr(header, 257, 6)
                if (magic.startsWith("ustar")) {
                    val prefix = cstr(header, 345, 155)
                    if (prefix.isNotEmpty()) name = "$prefix/$name"
                }
                longName?.let { name = it }; longName = null
                paxPath?.let { name = it }; paxPath = null
                val link = cstr(header, 157, 100)
                val mode = parseOctal(header, 100, 8).toInt()

                name = name.removePrefix("./").trimEnd('/')
                if (name.isEmpty() || name == ".") { skipData(inp, size); continue }
                val out = File(dest, name)
                if (!out.canonicalPath.startsWith(root)) { skipData(inp, size); continue }

                when (type) {
                    '5' -> out.mkdirs()
                    '2' -> {
                        out.parentFile?.mkdirs()
                        out.delete()
                        try { Os.symlink(link, out.path) } catch (_: Exception) {}
                    }
                    '0', '\u0000', '7' -> {
                        out.parentFile?.mkdirs()
                        out.delete()
                        FileOutputStream(out).use { fo -> copyN(inp, fo, size) }
                        skipPadding(inp, size)
                        if (mode and 0b001_001_001 != 0) out.setExecutable(true, false)
                        out.setReadable(true, false)
                    }
                    else -> skipData(inp, size)
                }
            }
        }
    }

    private fun readFully(inp: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun copyN(inp: InputStream, out: java.io.OutputStream, size: Long) {
        val buf = ByteArray(1 shl 16)
        var left = size
        while (left > 0) {
            val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw java.io.EOFException("truncated tar")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun readData(inp: InputStream, size: Long): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        copyN(inp, bos, size)
        skipPadding(inp, size)
        return bos.toByteArray()
    }

    private fun skipData(inp: InputStream, size: Long) {
        copyN(inp, NullOut, size)
        skipPadding(inp, size)
    }

    private object NullOut : java.io.OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    private fun skipPadding(inp: InputStream, size: Long) {
        val pad = ((512 - (size % 512)) % 512).toInt()
        if (pad > 0) readFully(inp, ByteArray(pad))
    }

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun parseOctal(b: ByteArray, off: Int, len: Int): Long {
        val s = cstr(b, off, len).trim()
        return if (s.isEmpty()) 0 else s.toLong(8)
    }

    private fun parseSize(b: ByteArray): Long {
        if (b[124].toInt() and 0x80 != 0) {           // GNU base-256 encoding
            var v = 0L
            for (i in 125 until 136) v = (v shl 8) or (b[i].toLong() and 0xFF)
            return v
        }
        return parseOctal(b, 124, 12)
    }

    private fun parsePax(s: String): String? {
        for (line in s.split('\n')) {
            val sp = line.indexOf(' ')
            if (sp < 0) continue
            val kv = line.substring(sp + 1)
            if (kv.startsWith("path=")) return kv.removePrefix("path=")
        }
        return null
    }
}
