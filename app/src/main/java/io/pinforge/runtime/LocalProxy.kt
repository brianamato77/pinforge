package io.pinforge.runtime

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

/**
 * A tiny HTTP/CONNECT proxy on 127.0.0.1. Linux Go binaries (arduino-cli) can't do DNS on
 * Android (no /etc/resolv.conf), but when they talk to a proxy at a literal IP they never
 * need to resolve anything themselves — the JVM resolves hosts here instead.
 */
class LocalProxy {
    @Volatile private var server: ServerSocket? = null

    val port: Int get() = server?.localPort ?: 0

    @Synchronized
    fun start() {
        if (server?.isClosed == false) return
        val s = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = s
        thread(isDaemon = true, name = "proxy-accept") {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { handle(c) }
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.use {
                val cin = BufferedInputStream(client.getInputStream())
                val head = readHead(cin) ?: return
                val lines = head.split("\r\n")
                val parts = lines[0].split(" ")
                if (parts.size < 3) return
                val method = parts[0]
                val target = parts[1]
                if (method.equals("CONNECT", true)) {
                    val hp = target.split(":")
                    val up = Socket(hp[0], hp.getOrElse(1) { "443" }.toInt())
                    up.use {
                        client.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                        client.getOutputStream().flush()
                        tunnel(cin, client, up)
                    }
                } else {
                    val u = URL(target)
                    val up = Socket(u.host, if (u.port > 0) u.port else 80)
                    up.use {
                        val sb = StringBuilder()
                        sb.append("$method ${u.file.ifEmpty { "/" }} ${parts[2]}\r\n")
                        for (l in lines.drop(1)) {
                            if (l.isEmpty()) continue
                            val k = l.substringBefore(':').trim().lowercase()
                            if (k.startsWith("proxy-") || k == "connection") continue
                            sb.append(l).append("\r\n")
                        }
                        sb.append("Connection: close\r\n\r\n")
                        up.getOutputStream().write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                        tunnel(cin, client, up)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun tunnel(cin: InputStream, client: Socket, up: Socket) {
        val t = thread(isDaemon = true) {
            pump(cin, up.getOutputStream())
            try { up.shutdownOutput() } catch (_: Exception) {}
        }
        pump(up.getInputStream(), client.getOutputStream())
        try { client.shutdownOutput() } catch (_: Exception) {}
        t.join(2000)
    }

    private fun pump(i: InputStream, o: OutputStream) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                o.write(buf, 0, n)
                o.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun readHead(i: InputStream): String? {
        val bos = ByteArrayOutputStream()
        var m = 0
        while (bos.size() < 64 * 1024) {
            val b = i.read()
            if (b < 0) return null
            bos.write(b)
            m = when {
                (m == 0 || m == 2) && b == '\r'.code -> m + 1
                (m == 1 || m == 3) && b == '\n'.code -> m + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (m == 4) return String(bos.toByteArray(), Charsets.ISO_8859_1).trimEnd()
        }
        return null
    }
}
