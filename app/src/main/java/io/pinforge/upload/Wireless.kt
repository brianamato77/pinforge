package io.pinforge.upload

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.UUID

private fun md5hex(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }

/** ArduinoOTA (espota) protocol: UDP invitation + optional MD5 auth, then the board pulls over TCP. */
object ArduinoOta {
    fun upload(host: String, port: Int, password: String, file: File, log: (String) -> Unit, progress: (Float) -> Unit) {
        val data = file.readBytes()
        val md5 = md5hex(data)
        val addr = InetAddress.getByName(host)
        ServerSocket(0).use { server ->
            server.soTimeout = 20000
            DatagramSocket().use { udp ->
                udp.soTimeout = 10000
                fun send(s: String) { val b = s.toByteArray(); udp.send(DatagramPacket(b, b.size, addr, port)) }
                fun recv(): String { val buf = ByteArray(128); val p = DatagramPacket(buf, buf.size); udp.receive(p); return String(buf, 0, p.length).trim() }
                log("Inviting $host:$port …")
                send("0 ${server.localPort} ${data.size} $md5\n")
                var reply = try { recv() } catch (e: Exception) { throw IllegalStateException("No reply from board (is ArduinoOTA running and on this Wi-Fi?)") }
                if (reply.startsWith("AUTH")) {
                    require(password.isNotEmpty()) { "Board requires an OTA password" }
                    val nonce = reply.split(" ").getOrElse(1) { "" }
                    val cnonce = md5hex("${file.name}${data.size}$md5$host${System.nanoTime()}".toByteArray())
                    val result = md5hex("${md5hex(password.toByteArray())}:$nonce:$cnonce".toByteArray())
                    send("200 $cnonce $result\n")
                    reply = recv()
                }
                if (!reply.contains("OK")) throw IllegalStateException("Board refused: $reply")
            }
            log("Board accepted, sending ${data.size / 1024} KB…")
            server.accept().use { s ->
                s.soTimeout = 15000
                s.tcpNoDelay = true
                val o = s.getOutputStream()
                val i = s.getInputStream()
                val ack = ByteArray(64)
                var off = 0
                while (off < data.size) {
                    val n = minOf(1460, data.size - off)
                    o.write(data, off, n); o.flush()
                    off += n
                    i.read(ack)
                    progress(off.toFloat() / data.size)
                }
                val end = System.currentTimeMillis() + 30000
                val sb = StringBuilder()
                while (System.currentTimeMillis() < end && !sb.contains("OK")) {
                    val n = try { i.read(ack) } catch (_: Exception) { -1 }
                    if (n < 0) break
                    sb.append(String(ack, 0, n))
                }
                if (!sb.contains("OK")) throw IllegalStateException("Board did not confirm the update: $sb")
            }
        }
        log("OTA done — board is rebooting.")
    }
}

/** Finds ArduinoOTA boards advertising _arduino._tcp over mDNS. */
class OtaDiscovery(ctx: Context, private val onFound: (String, String, Int) -> Unit) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        stop()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val h = i.host?.hostAddress ?: return
                        onFound(i.serviceName, h, i.port)
                    }
                })
            }
        }
        listener = l
        nsd.discoverServices("_arduino._tcp", NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        listener = null
    }
}

/** Nordic UART Service (NUS) client — the de-facto BLE serial for ESP32/nRF52 sketches. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BleUart(
    private val ctx: Context,
    private val onLine: (String) -> Unit,
    private val onState: (String) -> Unit
) {
    companion object {
        val SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val adapter get() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var scanCb: ScanCallback? = null
    private val lineBuf = StringBuilder()

    val connected get() = rx != null

    fun scan(onFound: (BluetoothDevice, String, Int) -> Unit) {
        val a = adapter
        if (a == null || !a.isEnabled) { onState("Bluetooth is off"); return }
        val sc = a.bluetoothLeScanner ?: run { onState("BLE scanner unavailable"); return }
        stopScan()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val name = r.device.name ?: r.scanRecord?.deviceName ?: return
                onFound(r.device, name, r.rssi)
            }
            override fun onScanFailed(errorCode: Int) { onState("Scan failed ($errorCode) — is Location on?") }
        }
        scanCb = cb
        sc.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        onState("Scanning…")
    }

    fun stopScan() {
        val cb = scanCb ?: return
        try { adapter?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {}
        scanCb = null
    }

    fun connect(device: BluetoothDevice) {
        stopScan()
        disconnect()
        onState("Connecting to ${device.name ?: device.address}…")
        gatt = device.connectGatt(ctx, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.requestMtu(247)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    rx = null
                    g.close()
                    onState("Disconnected")
                }
            }
            override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) {
                mtu = m
                g.discoverServices()
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val svc = g.getService(SERVICE)
                if (svc == null) { onState("Connected, but no Nordic UART service found"); return }
                rx = svc.getCharacteristic(RX)
                val tx = svc.getCharacteristic(TX)
                if (tx != null) {
                    g.setCharacteristicNotification(tx, true)
                    tx.getDescriptor(CCCD)?.let { d ->
                        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(d)
                    }
                }
                onState("Connected (MTU $mtu)")
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                val s = String(c.value ?: return, Charsets.UTF_8)
                for (ch in s) {
                    if (ch == '\n') { onLine(lineBuf.toString().trimEnd('\r')); lineBuf.clear() } else lineBuf.append(ch)
                }
                if (lineBuf.length > 200) { onLine(lineBuf.toString()); lineBuf.clear() }
            }
        }, BluetoothDevice.TRANSPORT_LE)
    }

    /** Blocking; call off the main thread. */
    fun send(text: String) {
        val g = gatt ?: return
        val c = rx ?: return
        val bytes = (text + "\n").toByteArray()
        val chunk = (mtu - 3).coerceAtLeast(20)
        c.writeType = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        var off = 0
        while (off < bytes.size) {
            val n = minOf(chunk, bytes.size - off)
            c.value = bytes.copyOfRange(off, off + n)
            g.writeCharacteristic(c)
            off += n
            Thread.sleep(30)
        }
    }

    fun disconnect() {
        gatt?.let { try { it.disconnect(); it.close() } catch (_: Exception) {} }
        gatt = null
        rx = null
    }
}
