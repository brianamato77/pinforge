package io.pinforge

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.pinforge.model.Board
import io.pinforge.model.Boards
import io.pinforge.model.CodeGen
import io.pinforge.model.Parts
import io.pinforge.model.Placed
import io.pinforge.model.Project
import io.pinforge.model.ProjectStore
import io.pinforge.model.Uploader
import io.pinforge.model.Wire
import io.pinforge.model.Wiring
import io.pinforge.runtime.ArduinoCli
import io.pinforge.runtime.LibInfo
import io.pinforge.runtime.Runtime
import io.pinforge.upload.ArduinoOta
import io.pinforge.upload.BleUart
import io.pinforge.upload.EspFlasher
import io.pinforge.upload.IntelHex
import io.pinforge.upload.OtaDiscovery
import io.pinforge.upload.SerialIo
import io.pinforge.upload.Stk500
import io.pinforge.upload.Uf2
import io.pinforge.upload.UsbLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class OtaHost(val name: String, val ip: String, val port: Int)
data class BleDev(val device: BluetoothDevice, val name: String, val rssi: Int)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val main = Handler(Looper.getMainLooper())
    val rt = Runtime(app)
    val cli = ArduinoCli(rt)
    val store = ProjectStore(File(app.filesDir, "projects"))
    val usb = UsbLink(app)

    // ---------- global ----------
    var tab by mutableStateOf(0)
    var showSetup by mutableStateOf(false)
    var busy by mutableStateOf<String?>(null)
    var progress by mutableStateOf<Float?>(null)
    var runtimeReady by mutableStateOf(rt.isInstalled())
    var runtimeUrl by mutableStateOf(BuildConfig.RUNTIME_URL)
    val console = mutableStateListOf<String>()

    // ---------- project ----------
    var projects by mutableStateOf(store.list())
    var project by mutableStateOf<Project?>(null)
    var code by mutableStateOf(TextFieldValue(""))
    var dirty by mutableStateOf(false)
    var wiring by mutableStateOf(Wiring())
    val board: Board get() = Boards.byId(project?.boardId)

    // ---------- packages ----------
    var libResults by mutableStateOf(listOf<LibInfo>())
    var installedLibs by mutableStateOf(mapOf<String, String>())
    var installedCores by mutableStateOf(mapOf<String, String>())

    // ---------- device ----------
    var pendingUf2 by mutableStateOf<File?>(null)
    var monitorBaud by mutableStateOf(115200)
    var monitorOn by mutableStateOf(false)
    val monitor = mutableStateListOf<String>()
    private var monitorIo: SerialIo? = null
    private var monitorJob: Job? = null
    val otaHosts = mutableStateListOf<OtaHost>()
    var otaPassword by mutableStateOf("")
    private val discovery = OtaDiscovery(app) { n, ip, port ->
        main.post { if (otaHosts.none { it.ip == ip }) otaHosts += OtaHost(n, ip, port) }
    }
    val bleDevices = mutableStateListOf<BleDev>()
    var bleState by mutableStateOf("Idle")
    val ble = BleUart(app,
        onLine = { l -> main.post { addMonitor("[BLE] $l") } },
        onState = { s -> main.post { bleState = s } })

    init {
        if (runtimeReady) refreshPackages()
    }

    fun log(line: String) {
        main.post {
            console.add(line)
            if (console.size > 3000) console.removeRange(0, console.size - 2500)
        }
    }

    private fun addMonitor(line: String) {
        monitor.add(line)
        if (monitor.size > 2000) monitor.removeRange(0, monitor.size - 1500)
    }

    private fun job(name: String, block: suspend () -> Unit) {
        if (busy != null) { log("Busy: $busy"); return }
        busy = name
        progress = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                log("✗ ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = null
                progress = null
            }
        }
    }

    fun cancel() = rt.cancel()

    private fun requireRuntime(): Boolean {
        if (!runtimeReady) {
            log("The toolchain runtime isn't installed yet — open Settings (gear icon) and install it.")
            showSetup = true
        }
        return runtimeReady
    }

    // ---------- runtime ----------
    fun installRuntime() = job("Installing runtime") {
        rt.install(runtimeUrl.trim(), ::log)
        runtimeReady = rt.isInstalled()
        if (rt.selfTest(::log)) log("✓ Runtime self-test passed") else log("⚠ Self-test had failures (see above)")
        refreshPackagesNow()
    }

    fun selfTest() = job("Self-test") { rt.selfTest(::log) }

    fun repairToolchains() = job("Patching toolchains") {
        val n = withContext(Dispatchers.IO) { rt.patchToolchains(::log) }
        log("Patched $n binaries")
    }

    // ---------- projects ----------
    fun refreshProjects() { projects = store.list() }

    fun createProject(name: String, boardId: String) {
        try {
            val p = store.create(name, boardId)
            refreshProjects()
            open(p.name)
        } catch (e: Exception) {
            log("✗ ${e.message}")
        }
    }

    fun open(name: String) {
        saveAll()
        val p = store.load(name)
        project = p
        code = TextFieldValue(p.sketch.readText())
        wiring = store.readWiring(p)
        dirty = false
        tab = 1
    }

    fun deleteProject(name: String) {
        if (project?.name == name) project = null
        store.delete(name)
        refreshProjects()
    }

    fun setBoard(id: String) {
        val p = project ?: return
        val np = p.copy(boardId = id)
        store.saveMeta(np)
        project = np
        // Board pins differ, so drop wires that no longer point at a real pin.
        val b = Boards.byId(id)
        setWiring(wiring.copy(wires = wiring.wires.filter { b.pin(it.board) != null }))
    }

    fun setMethod(m: String) {
        val p = project ?: return
        val np = p.copy(method = m)
        store.saveMeta(np)
        project = np
    }

    fun setOtaHost(h: String) {
        val p = project ?: return
        val np = p.copy(otaHost = h)
        store.saveMeta(np)
        project = np
    }

    fun editCode(v: TextFieldValue) {
        if (v.text != code.text) dirty = true
        code = v
    }

    fun insertText(s: String) {
        val v = code
        val start = minOf(v.selection.start, v.selection.end)
        val end = maxOf(v.selection.start, v.selection.end)
        val t = v.text.substring(0, start) + s + v.text.substring(end)
        editCode(TextFieldValue(t, TextRange(start + s.length)))
    }

    fun saveAll() {
        val p = project ?: return
        if (dirty) { p.sketch.writeText(code.text); dirty = false }
        store.writeWiring(p, wiring)
    }

    // ---------- wiring ----------
    fun setWiring(w: Wiring, persist: Boolean = true) {
        wiring = w
        if (persist) project?.let { store.writeWiring(it, w) }
    }

    fun addPart(type: String) {
        val t = Parts.byId(type) ?: return
        val existing = wiring.parts.map { it.id }.toSet()
        var n = 1
        while ("${t.id}$n" in existing) n++
        val idx = wiring.parts.size
        val right = idx % 2 == 0
        val x = if (right) 230f else -250f
        val y = 10f + (idx / 2) * 120f
        setWiring(wiring.copy(parts = wiring.parts + Placed("${t.id}$n", type, x, y)))
    }

    fun movePart(id: String, dx: Float, dy: Float) {
        setWiring(wiring.copy(parts = wiring.parts.map { if (it.id == id) it.copy(x = it.x + dx, y = it.y + dy) else it }), persist = false)
    }

    fun removePart(id: String) =
        setWiring(Wiring(wiring.parts.filter { it.id != id }, wiring.wires.filter { it.part != id }))

    fun connect(part: String, pin: String, boardKey: String) =
        setWiring(wiring.copy(wires = wiring.wires.filter { !(it.part == part && it.pin == pin) } + Wire(part, pin, boardKey)))

    fun disconnect(part: String, pin: String) =
        setWiring(wiring.copy(wires = wiring.wires.filter { !(it.part == part && it.pin == pin) }))

    fun autoWire() = setWiring(CodeGen.autoWire(board, wiring))

    fun writeHeader() {
        val p = project ?: return
        File(p.dir, "wiring.h").writeText(CodeGen.header(board, wiring))
        log("wiring.h updated")
    }

    fun applyStarter() {
        writeHeader()
        code = TextFieldValue(CodeGen.starter(board, wiring))
        dirty = true
        saveAll()
        tab = 1
        log("Sketch replaced with starter code for ${wiring.parts.size} part(s)")
    }

    fun installPartLibs() {
        val libs = CodeGen.libraries(board, wiring).filter { it !in installedLibs }
        if (libs.isEmpty()) { log("All libraries for this wiring are installed"); return }
        if (!requireRuntime()) return
        job("Installing libraries") {
            for (l in libs) { log("▶ lib install $l"); cli.installLib(l, ::log) }
            installedLibs = cli.installedLibs()
        }
    }

    // ---------- packages ----------
    fun refreshPackages() = viewModelScope.launch { refreshPackagesNow() }

    private suspend fun refreshPackagesNow() {
        try {
            installedCores = cli.installedCores()
            installedLibs = cli.installedLibs()
        } catch (e: Exception) {
            log("Couldn't list packages: ${e.message}")
        }
    }

    fun updateIndex() {
        if (!requireRuntime()) return
        job("Updating index") { cli.updateIndex(::log); refreshPackagesNow() }
    }

    fun installCore(id: String) {
        if (!requireRuntime()) return
        job("Installing $id") {
            if (installedCores.isEmpty()) cli.updateIndex(::log)
            log("▶ core install $id (large download — keep the app open)")
            if (cli.installCore(id, ::log)) log("✓ $id installed") else log("✗ $id install failed")
            refreshPackagesNow()
        }
    }

    fun searchLibs(q: String) {
        if (!requireRuntime()) return
        job("Searching") {
            libResults = cli.searchLibs(q)
            if (libResults.isEmpty()) log("No libraries found for \"$q\" (run Update index first if this is a fresh install)")
        }
    }

    fun installLib(name: String) {
        if (!requireRuntime()) return
        job("Installing $name") {
            if (cli.installLib(name, ::log)) log("✓ $name installed")
            installedLibs = cli.installedLibs()
        }
    }

    // ---------- build & upload ----------
    private suspend fun compileNow(): Boolean {
        val p = project ?: return false
        saveAll()
        val b = board
        if (b.core !in installedCores) {
            log("Board package ${b.core} isn't installed — installing it first.")
            if (installedCores.isEmpty()) cli.updateIndex(::log)
            if (!cli.installCore(b.core, ::log)) return false
            refreshPackagesNow()
        }
        val missing = CodeGen.libraries(b, wiring).filter { it !in installedLibs }
        for (l in missing) { log("▶ lib install $l"); cli.installLib(l, ::log) }
        if (missing.isNotEmpty()) installedLibs = cli.installedLibs()
        log("▶ Compiling ${p.name} for ${b.name}…")
        val t0 = System.currentTimeMillis()
        val ok = cli.compile(p.dir, b.fqbn, p.buildDir, ::log)
        log(if (ok) "✓ Compiled in ${(System.currentTimeMillis() - t0) / 1000}s" else "✗ Compile failed")
        return ok
    }

    fun verify() {
        if (!requireRuntime()) return
        job("Compiling") { compileNow() }
    }

    fun upload() {
        if (!requireRuntime()) return
        job("Uploading") {
            val p = project ?: return@job
            val b = board
            if (!compileNow()) return@job
            val out = p.buildDir
            val prefix = "${p.name}.ino"
            when {
                p.method == "ota" && b.supportsOta -> {
                    val host = p.otaHost.ifBlank { throw IllegalStateException("Set the board's IP on the Device tab") }
                    val bin = File(out, "$prefix.bin")
                    withContext(Dispatchers.IO) {
                        ArduinoOta.upload(host, 3232, otaPassword, bin, ::log) { f -> main.post { progress = f } }
                    }
                }
                b.uploader == Uploader.UF2 -> {
                    val uf2 = File(out, "$prefix.uf2").takeIf { it.exists() }
                        ?: File(out, "firmware.uf2").also { f ->
                            val hex = File(out, "$prefix.hex")
                            require(hex.exists()) { "No .uf2 or .hex in build output" }
                            f.writeBytes(Uf2.fromImage(IntelHex.parse(hex), b.uf2Family))
                        }
                    val dev = usb.firstDevice()
                    if (dev != null && usb.requestPermission(dev)) {
                        stopMonitor()
                        log("Rebooting board into its UF2 bootloader…")
                        withContext(Dispatchers.IO) { try { usb.touch1200(dev) } catch (_: Exception) {} }
                    } else {
                        log("No USB connection — double-tap RESET on the board to enter the bootloader.")
                    }
                    log("When the board's drive appears, pick it in the folder picker.")
                    pendingUf2 = uf2
                }
                else -> {
                    val dev = usb.firstDevice() ?: throw IllegalStateException("No USB device. Connect the board with a USB-OTG cable.")
                    if (!usb.requestPermission(dev)) throw IllegalStateException("USB permission denied")
                    stopMonitor()
                    log("▶ Uploading via ${usb.describe(dev)}")
                    withContext(Dispatchers.IO) {
                        val io = usb.open(dev, if (b.uploader == Uploader.STK500) b.baud else 115200)
                        try {
                            val prog: (Float) -> Unit = { f -> main.post { progress = f } }
                            when (b.uploader) {
                                Uploader.ESPTOOL -> EspFlasher.flashBuild(io, out, p.name, rt.dataDir, ::log, prog)
                                Uploader.STK500 -> Stk500(io, ::log).flash(IntelHex.parse(File(out, "$prefix.hex")), prog)
                                Uploader.UF2 -> {}
                            }
                        } finally {
                            io.close()
                        }
                    }
                }
            }
        }
    }

    fun writeUf2(tree: Uri?) {
        val f = pendingUf2 ?: return
        pendingUf2 = null
        if (tree == null) { log("UF2 copy cancelled"); return }
        job("Copying UF2") {
            withContext(Dispatchers.IO) {
                val ctx = getApplication<Application>()
                val dir = DocumentFile.fromTreeUri(ctx, tree) ?: throw IllegalStateException("Can't open that drive")
                log("Drive: ${dir.name}")
                val doc = dir.createFile("application/octet-stream", "firmware.uf2")
                    ?: throw IllegalStateException("Couldn't create file on drive")
                ctx.contentResolver.openOutputStream(doc.uri)?.use { o ->
                    val data = f.readBytes()
                    var off = 0
                    while (off < data.size) {
                        val n = minOf(64 * 1024, data.size - off)
                        o.write(data, off, n)
                        off += n
                        main.post { progress = off.toFloat() / data.size }
                    }
                    o.flush()
                } ?: throw IllegalStateException("Couldn't write to drive")
            }
            log("✓ UF2 copied — the board reboots into your sketch.")
        }
    }

    // ---------- serial monitor ----------
    fun startMonitor() = viewModelScope.launch {
        if (monitorOn) return@launch
        try {
            val dev = usb.firstDevice() ?: run { addMonitor("No USB device connected"); return@launch }
            if (!usb.requestPermission(dev)) { addMonitor("USB permission denied"); return@launch }
            val io = withContext(Dispatchers.IO) { usb.open(dev, monitorBaud) }
            io.dtr(true); io.rts(false)
            monitorIo = io
            monitorOn = true
            addMonitor("— connected ${usb.describe(dev)} @ $monitorBaud —")
            monitorJob = viewModelScope.launch(Dispatchers.IO) {
                val sb = StringBuilder()
                while (isActive) {
                    val d = try { io.read(200) } catch (e: Exception) { main.post { addMonitor("— disconnected —"); stopMonitor() }; break }
                    if (d.isEmpty()) continue
                    for (b in d) {
                        val c = (b.toInt() and 0xFF).toChar()
                        if (c == '\n') { val l = sb.toString().trimEnd('\r'); sb.clear(); main.post { addMonitor(l) } }
                        else sb.append(c)
                    }
                    if (sb.length > 300) { val l = sb.toString(); sb.clear(); main.post { addMonitor(l) } }
                }
            }
        } catch (e: Exception) {
            addMonitor("✗ ${e.message}")
        }
    }

    fun stopMonitor() {
        monitorJob?.cancel()
        monitorJob = null
        monitorIo?.close()
        monitorIo = null
        monitorOn = false
    }

    fun sendSerial(text: String) {
        val io = monitorIo
        if (io != null) {
            viewModelScope.launch(Dispatchers.IO) { try { io.write((text + "\n").toByteArray()) } catch (_: Exception) {} }
        }
        if (ble.connected) viewModelScope.launch(Dispatchers.IO) { ble.send(text) }
        addMonitor("> $text")
    }

    // ---------- OTA discovery ----------
    fun discoverOta() {
        otaHosts.clear()
        try { discovery.start() } catch (e: Exception) { log("mDNS: ${e.message}") }
    }

    // ---------- BLE ----------
    fun bleScan() {
        bleDevices.clear()
        ble.scan { d, n, r -> main.post { if (bleDevices.none { it.device.address == d.address }) bleDevices += BleDev(d, n, r) } }
    }

    fun bleConnect(d: BleDev) = ble.connect(d.device)
    fun bleDisconnect() { ble.disconnect(); bleState = "Disconnected" }

    override fun onCleared() {
        saveAll()
        stopMonitor()
        discovery.stop()
        ble.disconnect()
    }
}

