package io.pinforge.runtime

import java.io.File
import java.io.RandomAccessFile

/**
 * Android has no /lib/ld-linux-aarch64.so.1, so stock Linux arm64 toolchains can't start.
 * We ship glibc's loader at a short path inside the app (/data/data/io.pinforge/l) and
 * rewrite every executable's PT_INTERP to point at it. The new path must fit inside the
 * existing PT_INTERP slot (normally 27 bytes), which is why the package id is short.
 */
object ElfPatcher {
    private val skipExt = setOf(
        "h", "hpp", "c", "cpp", "cc", "S", "s", "a", "o", "ld", "txt", "json", "py", "pyc",
        "md", "html", "csv", "bin", "elf", "map", "png", "jpg", "svg", "xml", "yml", "yaml",
        "cmake", "mk", "ini", "cfg", "inc", "specs", "rst", "pdf", "gz", "zip", "tcl", "lua"
    )

    fun patchTree(root: File, interp: String, log: (String) -> Unit = {}): Int {
        if (!root.exists()) return 0
        var n = 0
        root.walkTopDown()
            .onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && it.length() > 64 && it.extension !in skipExt }
            .forEach { f ->
                try {
                    if (patch(f, interp)) n++
                } catch (e: Exception) {
                    log("patch failed: ${f.name}: ${e.message}")
                }
            }
        return n
    }

    /** @return true if the file's interpreter was rewritten. */
    fun patch(f: File, interp: String): Boolean {
        val head = ByteArray(64)
        RandomAccessFile(f, "r").use { r -> if (r.length() < 64) return false; r.readFully(head) }
        if (head[0].toInt() != 0x7f || head[1] != 'E'.code.toByte() ||
            head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()) return false
        if (head[4].toInt() != 2 || head[5].toInt() != 1) return false   // ELF64, little-endian

        val phoff = le64(head, 32)
        val phentsize = le16(head, 54)
        val phnum = le16(head, 56)
        if (!f.canWrite()) f.setWritable(true, true)
        RandomAccessFile(f, "rw").use { r ->
            val ph = ByteArray(phentsize)
            for (i in 0 until phnum) {
                r.seek(phoff + i.toLong() * phentsize)
                r.readFully(ph)
                if (le32(ph, 0) != 3L) continue                       // PT_INTERP
                val off = le64(ph, 8)
                val size = le64(ph, 32).toInt()
                val cur = ByteArray(size)
                r.seek(off); r.readFully(cur)
                val curStr = String(cur, Charsets.US_ASCII).trimEnd('\u0000')
                if (curStr == interp) { f.setExecutable(true, false); return false }
                if (!curStr.contains("ld-linux")) return false
                val bytes = interp.toByteArray(Charsets.US_ASCII)
                require(bytes.size + 1 <= size) { "interp slot too small ($size)" }
                val out = ByteArray(size)
                System.arraycopy(bytes, 0, out, 0, bytes.size)
                r.seek(off); r.write(out)
                f.setExecutable(true, false)
                return true
            }
        }
        return false
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }
    private fun le64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }
}
