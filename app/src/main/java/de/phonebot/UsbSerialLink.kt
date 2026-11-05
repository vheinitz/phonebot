package de.phonebot

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * USB-OTG-Verbindung zum ESP32.
 *
 * Nutzt `usb-serial-for-android` (CDC-ACM). Wichtig für dieses Projekt:
 * Der angeschlossene ESP32-C3 (wie auch S3) meldet sich über sein natives
 * USB-Serial/JTAG mit VID:PID 303A:1001. Diese Kennung steht NICHT in der
 * Standardliste des Default-Probers – darum fand die App bisher kein Gerät.
 * Hier wird sie über eine eigene ProbeTable auf den CdcAcmSerialDriver gelegt;
 * als Rückfall wird zusätzlich der Default-Prober versucht (CP2102, CH340, FTDI).
 *
 * Die Klasse kapselt nur Finden/Öffnen/Schreiben/Lesen/Schließen – bewusst klein.
 * Die USB-Berechtigung muss vor [open] erteilt sein (siehe MainActivity).
 */
class UsbSerialLink(context: Context) {

    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var port: UsbSerialPort? = null

    val isOpen: Boolean get() = port != null

    /**
     * Erstes passendes USB-Serial-Gerät suchen (eigene Kennung zuerst, dann
     * Standardliste). Gibt null zurück, wenn nichts angesteckt/erkannt ist.
     */
    fun findDriver(): UsbSerialDriver? {
        customProber.findAllDrivers(manager).firstOrNull()?.let { return it }
        return UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
    }

    fun hasPermission(device: UsbDevice): Boolean = manager.hasPermission(device)

    /** Gerät öffnen (Berechtigung muss vorliegen). true bei Erfolg. */
    fun open(driver: UsbSerialDriver, baudRate: Int = 115200): Boolean {
        val connection = manager.openDevice(driver.device) ?: return false
        val p = driver.ports.first()
        p.open(connection)
        p.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        // DTR setzen – manche CDC-Firmwares senden sonst nicht (beim C3 unkritisch).
        try { p.dtr = true } catch (_: Exception) {}
        port = p
        return true
    }

    /** Befehl(e) senden. [CommandProtocol] liefert die fertigen Strings. */
    @Synchronized
    fun send(command: String) {
        try {
            port?.write(command.toByteArray(Charsets.US_ASCII), WRITE_TIMEOUT_MS)
        } catch (_: Exception) {
        }
    }

    /** Antwort lesen (kurz blockierend). Leerer String = nichts angekommen. */
    fun readLine(): String {
        val p = port ?: return ""
        val buf = ByteArray(64)
        return try {
            val n = p.read(buf, READ_TIMEOUT_MS)
            if (n > 0) String(buf, 0, n, Charsets.US_ASCII).trim() else ""
        } catch (_: Exception) {
            ""
        }
    }

    fun close() {
        try { port?.close() } catch (_: Exception) {}
        port = null
    }

    companion object {
        private const val WRITE_TIMEOUT_MS = 200
        private const val READ_TIMEOUT_MS = 100

        /** Eigene Kennung des ESP32-C3/S3 (natives USB-Serial/JTAG). */
        private val customProber: UsbSerialProber by lazy {
            val table = ProbeTable()
            table.addProduct(0x303A, 0x1001, CdcAcmSerialDriver::class.java)
            UsbSerialProber(table)
        }
    }
}
