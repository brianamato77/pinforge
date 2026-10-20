package io.pinforge.upload

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Blocking serial I/O over a USB-OTG port. */
class SerialIo(private val port: UsbSerialPort) {
    private val buf = ByteArray(16384)

    fun setBaud(baud: Int) =
        port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

    fun dtr(v: Boolean) = try { port.setDTR(v) } catch (_: Exception) {}
    fun rts(v: Boolean) = try { port.setRTS(v) } catch (_: Exception) {}

    fun write(b: ByteArray) = port.write(b, 2000)

    /** Returns whatever arrives within [timeoutMs] (possibly empty). */
    fun read(timeoutMs: Int): ByteArray {
        val n = port.read(buf, timeoutMs)
        return if (n <= 0) ByteArray(0) else buf.copyOf(n)
    }

    fun drain() {
        try { port.purgeHwBuffers(true, true) } catch (_: Exception) {}
        val end = System.currentTimeMillis() + 50
        while (System.currentTimeMillis() < end) if (read(10).isEmpty()) break
    }

    fun close() = try { port.close() } catch (_: Exception) {}
}

class UsbLink(private val ctx: Context) {
    private val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

    fun devices(): List<UsbDevice> = mgr.deviceList.values.toList()

    fun describe(d: UsbDevice): String {
        val name = listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").ifEmpty { d.deviceName }
        return "%s  [%04X:%04X]".format(name, d.vendorId, d.productId)
    }

    fun firstDevice(): UsbDevice? = devices().firstOrNull()

    private fun driverFor(d: UsbDevice): UsbSerialDriver =
        UsbSerialProber.getDefaultProber().probeDevice(d) ?: CdcAcmSerialDriver(d)

    suspend fun requestPermission(d: UsbDevice): Boolean {
        if (mgr.hasPermission(d)) return true
        return suspendCancellableCoroutine { cont ->
            val action = "${ctx.packageName}.USB_PERMISSION"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
            ContextCompat.registerReceiver(ctx, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, 0, Intent(action).setPackage(ctx.packageName), flags)
            mgr.requestPermission(d, pi)
            cont.invokeOnCancellation { try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {} }
        }
    }

    /** Opens the first serial interface on [d]. Permission must already be granted. */
    fun open(d: UsbDevice, baud: Int): SerialIo {
        val driver = driverFor(d)
        val conn = mgr.openDevice(d) ?: throw IllegalStateException("Could not open USB device")
        val port = driver.ports.first()
        port.open(conn)
        val io = SerialIo(port)
        io.setBaud(baud)
        return io
    }

    /** "1200-baud touch": tells SAMD/nRF52/RP2040 boards to reboot into their bootloader. */
    fun touch1200(d: UsbDevice) {
        val io = open(d, 1200)
        io.dtr(true)
        Thread.sleep(50)
        io.dtr(false)
        Thread.sleep(50)
        io.close()
    }
}
