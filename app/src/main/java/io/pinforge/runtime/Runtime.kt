package io.pinforge.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The on-phone Linux userland: glibc + arduino-cli + bash + python3, unpacked into the app's
 * private data dir. Toolchains that arduino-cli downloads (avr-gcc, xtensa-gcc, arm-gcc…) are
 * stock linux-aarch64 builds; we patch their ELF interpreter after every core install.
 */
class Runtime(private val ctx: Context) {
    val base: File = ctx.dataDir
    val rt = File(base, "rt")
    val bin = File(rt, "bin")
    val lib = File(rt, "lib")
    val interpFile = File(base, "l")
    val interpPath = "/data/data/${ctx.packageName}/l"
    val ard = File(base, "ard")
    val dataDir = File(ard, "data")
    val userDir = File(ard, "user")
    val home = File(base, "home")
    val tmp = File(ctx.cacheDir, "tmp")
    val configFile = File(ard, "arduino-cli.yaml")
    val cli get() = File(bin, "arduino-cli")
    val proxy = LocalProxy()

    @Volatile private var current: Process? = null

    fun isInstalled() = cli.exists() && interpFile.exists()

    fun version(): String = File(rt, "VERSION").takeIf { it.exists() }?.readText()?.trim() ?: "none"

    suspend fun install(url: String, log: (String) -> Unit) = withContext(Dispatchers.IO) {
        log("Interpreter path: $interpPath (${interpPath.length} chars)")
        require(interpPath.length <= 26) { "Package id too long for interpreter patching" }
        val archive = File(ctx.cacheDir, "runtime.tar.gz")
        download(url, archive, log)
        log("Extracting runtime…")
        rt.deleteRecursively()
        Tar.extractTarGz(archive, rt)
        archive.delete()
        val ld = File(lib, "ld-linux-aarch64.so.1")
        require(ld.exists()) { "Runtime is missing ld-linux-aarch64.so.1" }
        interpFile.delete()
        ld.copyTo(interpFile, overwrite = true)
        interpFile.setReadable(true, false)
        interpFile.setExecutable(true, false)
        bin.listFiles()?.forEach { it.setExecutable(true, false) }
        val n = ElfPatcher.patchTree(rt, interpPath, log)
        log("Patched $n runtime binaries")
        // Toolchains installed earlier (e.g. after a reinstall) need the same treatment.
        val m = ElfPatcher.patchTree(File(dataDir, "packages"), interpPath, log)
        if (m > 0) log("Re-patched $m toolchain binaries")
        listOf(dataDir, userDir, home, tmp).forEach { it.mkdirs() }
        writeConfig()
        log("Runtime ${version()} installed.")
    }

    /** Re-patch toolchain binaries arduino-cli just downloaded. */
    fun patchToolchains(log: (String) -> Unit): Int =
        ElfPatcher.patchTree(File(dataDir, "packages"), interpPath, log)

    fun writeConfig() {
        proxy.start()
        ard.mkdirs()
        configFile.writeText(
            """
            board_manager:
              additional_urls:
                - https://espressif.github.io/arduino-esp32/package_esp32_index.json
                - https://adafruit.github.io/arduino-board-index/package_adafruit_index.json
                - https://github.com/earlephilhower/arduino-pico/releases/download/global/package_rp2040_index.json
            directories:
              data: ${dataDir.path}
              downloads: ${File(ard, "staging").path}
              user: ${userDir.path}
            network:
              proxy: http://127.0.0.1:${proxy.port}
            library:
              enable_unsafe_install: true
            updater:
              enable_notification: false
            """.trimIndent() + "\n"
        )
    }

    private fun env(pb: ProcessBuilder) {
        val e = pb.environment()
        e["PATH"] = "${bin.path}:/system/bin:/system/xbin"
        e["LD_LIBRARY_PATH"] = lib.path
        e["HOME"] = home.path
        e["TMPDIR"] = tmp.path
        e["PYTHONHOME"] = File(rt, "py").path
        e["PYTHONDONTWRITEBYTECODE"] = "1"
        e["SSL_CERT_DIR"] = "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts"
        e["LC_ALL"] = "C"
        // Belt-and-braces: never register rseq (blocked by Android seccomp on many versions).
        e["GLIBC_TUNABLES"] = "glibc.pthread.rseq=0"
        e["TERM"] = "dumb"
        e["ARDUINO_UPDATER_ENABLE_NOTIFICATION"] = "false"
    }

    /** Run a command with the runtime environment, streaming merged output line by line. */
    suspend fun exec(cmd: List<String>, cwd: File? = null, onLine: (String) -> Unit): Int =
        withContext(Dispatchers.IO) {
            listOf(home, tmp).forEach { it.mkdirs() }
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            if (cwd != null) pb.directory(cwd)
            env(pb)
            val p = pb.start()
            current = p
            try {
                p.inputStream.bufferedReader().useLines { seq -> seq.forEach(onLine) }
                p.waitFor()
            } finally {
                current = null
            }
        }

    suspend fun cli(args: List<String>, onLine: (String) -> Unit): Int {
        writeConfig()
        return exec(listOf(cli.path, "--config-file", configFile.path) + args, null, onLine)
    }

    fun cancel() {
        current?.destroy()
    }

    suspend fun selfTest(log: (String) -> Unit): Boolean {
        var ok = true
        suspend fun t(name: String, cmd: List<String>) {
            log("▶ $name")
            val code = try { exec(cmd) { log("  $it") } } catch (e: Exception) { log("  ${e.message}"); -1 }
            log(if (code == 0) "  ✓ ok" else "  ✗ exit $code")
            if (code != 0) ok = false
        }
        t("arduino-cli", listOf(cli.path, "version"))
        t("bash (glibc)", listOf(File(bin, "bash").path, "-c", "echo bash \$BASH_VERSION"))
        t("python3 (glibc)", listOf(File(bin, "python3").path, "-c",
            "import sys,hashlib,struct,argparse; print('python', sys.version.split()[0])"))
        return ok
    }

    private fun download(url: String, dest: File, log: (String) -> Unit) {
        var u = URL(url)
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = u.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 8) {
                u = URL(u, conn.getHeaderField("Location"))
                conn.disconnect()
                continue
            }
            if (code != 200) throw IllegalStateException("Download failed: HTTP $code for $url")
            break
        }
        val total = conn.contentLengthLong
        log("Downloading ${if (total > 0) "${total / 1_000_000} MB" else ""} from ${u.host}…")
        conn.inputStream.use { i ->
            FileOutputStream(dest).use { o ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                var lastPct = -1
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct / 10 != lastPct / 10) { log("  $pct%"); lastPct = pct }
                    }
                }
            }
        }
    }
}
