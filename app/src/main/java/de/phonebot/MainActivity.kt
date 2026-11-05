package de.phonebot

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Phonebot – Hauptaktivität.
 *
 * Regelkreis komplett auf dem Telefon:
 *   CameraX (unteres ROI) -> LineDetector -> PidController
 *                         -> CommandProtocol -> UsbSerialLink (USB-OTG -> ESP32)
 * Der ESP32 steuert nur die Motoren.
 *
 * Diese Fassung stellt die USB-Verbindung tatsächlich her: sie fordert die
 * USB-Berechtigung an, erkennt den ESP32-C3/S3 (303A:1001) über eine eigene
 * ProbeTable in [UsbSerialLink] und zeigt den Link per PING/PONG in der
 * Telemetrie an. Ohne Kamera-Berechtigung läuft der USB-Link trotzdem (PING
 * im Leerlauf), damit man den ESP32 unabhängig testen kann.
 *
 * Noch offen (siehe README.md): Port des Mini-Sprach-Interpreters nach Kotlin.
 */
class MainActivity : AppCompatActivity() {

    private val detector = LineDetector()
    private val pid = PidController()
    private lateinit var usb: UsbSerialLink

    private var baseSpeed = 30
    private var autoMode = true

    private var lastFrameNs = 0L
    private var lastError = 0.0

    @Volatile private var connected = false
    @Volatile private var cameraActive = false

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var linkThread: Thread? = null

    private lateinit var telemetry: TextView
    private lateinit var preview: PreviewView

    // --- Statuszeilen getrennt halten, damit beide Quellen sich nicht überschreiben ---
    @Volatile private var usbStatus = "USB: suche ESP32…"
    @Volatile private var camStatus = "Kamera: –"
    private fun refreshTelemetry() = runOnUiThread {
        telemetry.text = "$usbStatus\n$camStatus"
    }

    // ---------------- USB-Permission ----------------
    private val actionUsbPermission get() = "$packageName.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                actionUsbPermission -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) connectUsb()
                    else { usbStatus = "USB: Berechtigung abgelehnt"; refreshTelemetry() }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> connectUsb()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    connected = false
                    usb.close()
                    usbStatus = "USB: ESP32 abgezogen"; refreshTelemetry()
                }
            }
        }
    }

    // ---------------- Kamera-Berechtigung ----------------
    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else { camStatus = "Kamera: keine Berechtigung"; refreshTelemetry() }
        }

    // --------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        telemetry = findViewById(R.id.telemetry)
        preview = findViewById(R.id.preview)
        refreshTelemetry()

        usb = UsbSerialLink(this)
        pid.reset()

        val filter = IntentFilter(actionUsbPermission).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        connectUsb()   // evtl. schon angesteckt
        requestCameraIfNeeded()
    }

    private fun requestCameraIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            camStatus = "Kamera: frage Berechtigung…"; refreshTelemetry()
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    // ---------------- USB verbinden ----------------
    private fun connectUsb() {
        if (connected) return
        val driver = usb.findDriver()
        if (driver == null) {
            usbStatus = "USB: kein ESP32 gefunden"; refreshTelemetry(); return
        }
        val device: UsbDevice = driver.device
        if (!usb.hasPermission(device)) {
            usbStatus = "USB: frage Berechtigung…"; refreshTelemetry()
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(actionUsbPermission).setPackage(packageName), flags
            )
            (getSystemService(Context.USB_SERVICE) as UsbManager).requestPermission(device, pi)
            return
        }
        if (usb.open(driver)) {
            connected = true
            usb.send(CommandProtocol.MODE_AUTO)
            usbStatus = "USB: verbunden ✓ (${device.productName ?: "ESP32"})"
            refreshTelemetry()
            startLinkThread()
        } else {
            usbStatus = "USB: Öffnen fehlgeschlagen"; refreshTelemetry()
        }
    }

    /**
     * Hält den Link wach und sichtbar: Wenn die Kamera NICHT fährt, wird hier
     * zyklisch PING gesendet (füttert zugleich den Watchdog). Antworten des
     * ESP32 werden gelesen und angezeigt – so sieht man den Link auch ohne Kamera.
     */
    private fun startLinkThread() {
        linkThread = Thread {
            try {
                while (connected && !Thread.currentThread().isInterrupted) {
                    if (!cameraActive) usb.send(CommandProtocol.PING)
                    val line = usb.readLine()
                    if (line.isNotEmpty()) { usbStatus = "USB: $line"; refreshTelemetry() }
                    Thread.sleep(250)
                }
            } catch (_: InterruptedException) {
            }
        }.also { it.isDaemon = true; it.start() }
    }

    // ---------------- Kamera ----------------
    private fun startCamera() {
        cameraActive = true
        camStatus = "Kamera: läuft"; refreshTelemetry()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val previewUseCase = Preview.Builder().build().also {
                it.setSurfaceProvider(preview.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor, ::analyzeFrame)

            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(image: ImageProxy) {
        try {
            if (image.format != ImageFormat.YUV_420_888) return
            val width = image.width
            val height = image.height

            val yPlane = image.planes[0]
            val gray = ByteArray(width * height)
            copyY(yPlane.buffer, yPlane.rowStride, width, height, gray)

            val roiTop = height * 2 / 3
            val result = detector.detect(gray, width, height, roiTop)

            val now = System.nanoTime()
            val dt = if (lastFrameNs == 0L) 1.0 / 15 else (now - lastFrameNs) / 1e9
            lastFrameNs = now

            val error = if (result.found) { lastError = result.error; result.error }
                        else lastError * 0.9

            if (autoMode && connected) {
                val terms = pid.update(error, dt)
                val (left, right) = CommandProtocol.wheelsFromCorrection(baseSpeed, terms.output)
                usb.send(CommandProtocol.setBoth(left, right))
                camStatus = "Linie: ${if (result.found) "ok" else "verloren"}  " +
                        "e=%.1f  L=%d R=%d".format(error, left, right)
                refreshTelemetry()
            }
        } catch (e: Exception) {
            Log.e("Phonebot", "analyze", e)
        } finally {
            image.close()
        }
    }

    private fun copyY(buffer: java.nio.ByteBuffer, rowStride: Int, w: Int, h: Int, out: ByteArray) {
        if (rowStride == w) {
            buffer.get(out, 0, w * h)
        } else {
            val rowTmp = ByteArray(rowStride)
            for (row in 0 until h) {
                buffer.position(row * rowStride)
                buffer.get(rowTmp, 0, rowStride)
                System.arraycopy(rowTmp, 0, out, row * w, w)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connected = false
        linkThread?.interrupt()
        if (usb.isOpen) { usb.send(CommandProtocol.STOP); usb.close() }
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        analysisExecutor.shutdown()
    }
}
