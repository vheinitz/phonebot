package de.phonebot

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.Menu
import android.view.MenuItem
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.view.KeyEvent as AndroidKeyEvent
import org.opencv.android.OpenCVLoader

/**
 * Phonebot – Hauptaktivität (Hochformat).
 *
 * Oben→unten: Vorschau-Box (klein/voll/aus; Live- oder Schwellwertbild),
 * Telemetrie, Knopfleiste, Interpreter-Fenster (Editor). Kamera über Camera2,
 * Erkennung mit OpenCV (`CvLineDetector`, Fehler normiert −1…+1), Steuerung
 * über die Mini-Sprache (`Interpreter`). ESP32 optional.
 */
class MainActivity : AppCompatActivity(), CameraController.Callbacks {

    private val detector = CvLineDetector()
    private val interpreter = Interpreter()
    private lateinit var usb: UsbSerialLink
    private var camera: CameraController? = null

    private lateinit var previewBox: LinearLayout
    private lateinit var liveview: ImageView
    private lateinit var thview: ImageView
    private lateinit var telemetry: TextView
    private lateinit var editor: EditText
    private lateinit var btnRun: Button
    private lateinit var thresholdBar: SeekBar
    private lateinit var thresholdVal: TextView

    @Volatile private var running = false
    @Volatile private var connected = false
    @Volatile private var thVisible = true
    @Volatile private var learnRequested = false
    private var cvReady = false
    private var cameraStarted = false
    @Volatile private var lastError = 0.0
    private var camCaps: CameraController.Caps? = null

    private enum class PreviewMode { SMALL, FULL, HIDDEN }
    private var previewMode = PreviewMode.SMALL

    private var linkThread: Thread? = null

    @Volatile private var usbStatus = "USB: ESP32 optional"
    @Volatile private var camStatus = "Kamera: –"
    @Volatile private var progStatus = "Programm: leer"
    private fun refreshTelemetry() = runOnUiThread { telemetry.text = "$usbStatus\n$camStatus\n$progStatus" }

    private val actionUsbPermission get() = "$packageName.USB_PERMISSION"

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) tryStartCamera()
            else { camStatus = "Kamera: keine Berechtigung"; refreshTelemetry() }
        }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                actionUsbPermission -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) connectUsb() else { usbStatus = "USB: Berechtigung abgelehnt"; refreshTelemetry() }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> connectUsb()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    connected = false; usb.close(); usbStatus = "USB: ESP32 abgezogen"; refreshTelemetry()
                }
            }
        }
    }

    // --------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewBox = findViewById(R.id.previewBox)
        liveview = findViewById(R.id.liveview)
        thview = findViewById(R.id.thview)
        telemetry = findViewById(R.id.telemetry)
        editor = findViewById(R.id.editor)
        btnRun = findViewById(R.id.btnRun)
        findViewById<Button>(R.id.btnLearn).setOnClickListener { learnRequested = true }
        findViewById<Button>(R.id.btnPreview).setOnClickListener { cyclePreview() }
        findViewById<Button>(R.id.btnRaw).setOnClickListener { camera?.capture() }
        btnRun.setOnClickListener { toggleRun() }

        thresholdBar = findViewById(R.id.thresholdBar)
        thresholdVal = findViewById(R.id.thresholdVal)
        thresholdBar.setOnSeekBarChangeListener(simpleSeek { p ->
            detector.fixedThreshold = p.toDouble(); thresholdVal.text = p.toString()
        })

        editor.setText(DEFAULT_PROGRAM)
        detector.liveDebug = true    // Livebild = gedrehtes Analysebild (eine Quelle)
        detector.debug = thVisible   // TH-Bild daneben

        cvReady = try { OpenCVLoader.initLocal() } catch (_: Throwable) { false }
        camStatus = if (cvReady) "Kamera: –" else "OpenCV konnte nicht laden"

        usb = UsbSerialLink(this)
        camera = CameraController(this, this)

        val filter = IntentFilter(actionUsbPermission).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        connectUsb()
        requestCameraIfNeeded()
        refreshTelemetry()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraIfNeeded() {
        if (hasCameraPermission()) tryStartCamera()
        else { camStatus = "Kamera: frage Berechtigung…"; refreshTelemetry(); requestCamera.launch(Manifest.permission.CAMERA) }
    }

    private fun tryStartCamera() {
        if (cameraStarted || !hasCameraPermission()) return
        cameraStarted = true
        camera?.start()
    }

    // ---------------- Programm starten/stoppen ----------------
    private fun toggleRun() {
        if (running) { stopProgram("Programm: gestoppt"); return }
        val ok = interpreter.compile(editor.text.toString())
        progStatus = "Programm: ${interpreter.status}"
        if (ok) {
            interpreter.runStart(false, 0.0)
            running = true
            btnRun.text = getString(R.string.btn_stop)
            if (connected) usb.send(CommandProtocol.MODE_AUTO)
        }
        refreshTelemetry()
    }

    private fun stopProgram(msg: String) {
        running = false
        progStatus = msg
        if (connected) usb.send(CommandProtocol.STOP)
        runOnUiThread { btnRun.text = getString(R.string.btn_run) }
        refreshTelemetry()
    }

    // ---------------- Kamera-Callbacks (Kamera-Thread) ----------------
    override fun onYuv(y: ByteArray, width: Int, height: Int) {
        if (learnRequested && cvReady) {
            learnRequested = false
            val ok = detector.learn(y, width, height)
            val thv = detector.fixedThreshold
            runOnUiThread {
                toast(if (ok) "Linie gelernt (Schwelle ${thv?.toInt() ?: "?"})" else "keine Linie erkannt")
                if (ok && thv != null) { thresholdBar.progress = thv.toInt(); thresholdVal.text = thv.toInt().toString() }
            }
        }

        val res = if (cvReady) detector.detect(y, width, height) else CvLineDetector.Result(false, 0.0)
        val error = if (res.found) { lastError = res.error; res.error } else lastError * 0.9

        if (running) {
            val halt = interpreter.step(res.found, error)
            val l = interpreter.motorLeft; val r = interpreter.motorRight
            if (connected) usb.send(CommandProtocol.setBoth(l, r))
            camStatus = "Linie: ${if (res.found) "ok" else "–"}  e=%.2f  L=%d R=%d".format(error, l, r)
            if (halt) stopProgram("Programm: angehalten (stop)")
        } else {
            camStatus = "Kamera: läuft (Linie ${if (res.found) "ok" else "–"}, e=%.2f)".format(error)
        }
        val live = detector.lastLive; if (live != null) runOnUiThread { liveview.setImageBitmap(live) }
        if (thVisible) { val bmp = detector.lastDebug; if (bmp != null) runOnUiThread { thview.setImageBitmap(bmp) } }
        refreshTelemetry()
    }

    override fun onStatus(text: String) { camStatus = text; refreshTelemetry() }
    override fun onCaps(caps: CameraController.Caps) {
        camCaps = caps
        detector.rotationDegrees = camera?.sensorOrientation ?: 90
    }
    override fun onDng(path: String?, error: String?) = runOnUiThread {
        val msg = if (path != null) "Aufnahme gespeichert:\n$path" else "Aufnahme fehlgeschlagen: $error"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        camStatus = if (path != null) "Gespeichert: ${path.substringAfterLast('/')}" else "Aufnahme: $error"
        refreshTelemetry()
    }

    // ---------------- Vorschau klein/voll/aus ----------------
    private fun cyclePreview() {
        previewMode = when (previewMode) {
            PreviewMode.SMALL -> PreviewMode.FULL
            PreviewMode.FULL -> PreviewMode.HIDDEN
            PreviewMode.HIDDEN -> PreviewMode.SMALL
        }
        val dp240 = (240 * resources.displayMetrics.density).toInt()
        val pl = previewBox.layoutParams as LinearLayout.LayoutParams
        val el = editor.layoutParams as LinearLayout.LayoutParams
        when (previewMode) {
            PreviewMode.SMALL -> { previewBox.visibility = View.VISIBLE; pl.height = dp240; pl.weight = 0f; editor.visibility = View.VISIBLE; el.height = 0; el.weight = 1f }
            PreviewMode.FULL -> { previewBox.visibility = View.VISIBLE; pl.height = 0; pl.weight = 1f; editor.visibility = View.GONE }
            PreviewMode.HIDDEN -> { previewBox.visibility = View.GONE; editor.visibility = View.VISIBLE; el.height = 0; el.weight = 1f }
        }
        previewBox.layoutParams = pl; editor.layoutParams = el
    }

    // ---------------- Menü ----------------
    override fun onCreateOptionsMenu(menu: Menu): Boolean { menuInflater.inflate(R.menu.main_menu, menu); return true }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_exposure -> showExposureDialog()
            R.id.menu_focus -> showFocusDialog()
            R.id.menu_size -> showSizeDialog()
            R.id.menu_rotate -> { detector.rotationDegrees = (detector.rotationDegrees + 90) % 360; toast("Bild gedreht: ${detector.rotationDegrees}°") }
            R.id.menu_thview -> toggleThView()
            R.id.menu_thauto -> { detector.fixedThreshold = null; thresholdVal.text = "Auto"; toast("Schwelle: Auto (OTSU)") }
            R.id.menu_raw -> camera?.capture()
            R.id.menu_bstop -> if (running) interpreter.queue(BstopEvent) else toast("Erst Start drücken")
            R.id.menu_batlow -> if (running) interpreter.queue(BatLowEvent) else toast("Erst Start drücken")
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun toggleThView() {
        thVisible = !thVisible
        detector.debug = thVisible
        thview.visibility = if (thVisible) View.VISIBLE else View.GONE
        toast(if (thVisible) "TH-Bild an (neben dem Livebild)" else "TH-Bild aus")
    }

    private fun showSizeDialog() {
        val cam = camera ?: return
        val caps = camCaps ?: run { toast("Kamera noch nicht bereit"); return }
        val items = ArrayList<String>(); val actions = ArrayList<Pair<CameraController.CaptureMode, Size?>>()
        if (caps.hasRaw) for (s in caps.rawSizes) { items.add("RAW (DNG) · ${s.width}×${s.height}"); actions.add(CameraController.CaptureMode.RAW to s) }
        for (s in caps.jpegSizes) { items.add("JPEG · ${s.width}×${s.height}"); actions.add(CameraController.CaptureMode.JPEG to s) }
        if (items.isEmpty()) { toast("Kamera meldet keine Größen"); return }
        AlertDialog.Builder(this).setTitle("Aufnahme-Größe (von der Kamera gemeldet)")
            .setItems(items.toTypedArray()) { _, i ->
                val (mode, size) = actions[i]
                cam.setCaptureConfig(mode, size)
                toast("Aufnahme: ${items[i]}")
            }.show()
    }

    private fun showExposureDialog() {
        val cam = camera ?: return
        val caps = camCaps
        if (caps == null || !caps.hasManualSensor || caps.exposureNs == null || caps.iso == null) { toast("Gerät meldet kein manuelles Sensor-Setting"); return }
        val expR = caps.exposureNs; val isoR = caps.iso
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val sw = Switch(this).apply { text = "manuelle Belichtung (AE aus)"; isChecked = cam.aeManual }
        val lblExp = TextView(this); val sbExp = SeekBar(this).apply { max = 1000 }
        val lblIso = TextView(this); val sbIso = SeekBar(this).apply { max = 1000 }
        fun expToProgress(ns: Long): Int {
            val lo = expR.lower.toDouble().coerceAtLeast(1.0); val hi = expR.upper.toDouble().coerceAtLeast(lo + 1)
            return (Math.log(ns.toDouble() / lo) / Math.log(hi / lo)).coerceIn(0.0, 1.0).times(1000).toInt()
        }
        fun progressToExp(p: Int): Long {
            val lo = expR.lower.toDouble().coerceAtLeast(1.0); val hi = expR.upper.toDouble().coerceAtLeast(lo + 1)
            return Math.round(lo * Math.pow(hi / lo, p / 1000.0))
        }
        fun progressToIso(p: Int) = isoR.lower + (isoR.upper - isoR.lower) * p / 1000
        fun isoToProgress(v: Int) = ((v - isoR.lower).toLong() * 1000 / Math.max(1, isoR.upper - isoR.lower)).toInt()
        fun update() { lblExp.text = "Belichtungszeit: %.2f ms".format(cam.exposureNs / 1_000_000.0); lblIso.text = "ISO: ${cam.iso}" }
        sbExp.progress = expToProgress(cam.exposureNs); sbIso.progress = isoToProgress(cam.iso); update()
        sbExp.setOnSeekBarChangeListener(simpleSeek { cam.exposureNs = progressToExp(it); update(); if (cam.aeManual) cam.applyAndRefresh() })
        sbIso.setOnSeekBarChangeListener(simpleSeek { cam.iso = progressToIso(it); update(); if (cam.aeManual) cam.applyAndRefresh() })
        sw.setOnCheckedChangeListener { _, c -> cam.aeManual = c; cam.applyAndRefresh() }
        box.addView(sw); box.addView(lblExp); box.addView(sbExp); box.addView(lblIso); box.addView(sbIso)
        AlertDialog.Builder(this).setTitle("Belichtung").setView(box).setPositiveButton("OK", null).show()
    }

    private fun showFocusDialog() {
        val cam = camera ?: return
        val caps = camCaps
        if (caps == null || !caps.hasManualSensor || caps.minFocusDiopters <= 0f) { toast("Gerät meldet keinen manuellen Fokus"); return }
        val minF = caps.minFocusDiopters
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val sw = Switch(this).apply { text = "manueller Fokus (AF aus)"; isChecked = cam.afManual }
        val lbl = TextView(this); val sb = SeekBar(this).apply { max = 1000 }
        fun update() { val d = cam.focusDiopters; lbl.text = if (d <= 0.001f) "Fokus: unendlich" else "Fokus: %.2f dpt  (~%.2f m)".format(d, 1f / d) }
        sb.progress = ((cam.focusDiopters / minF).coerceIn(0f, 1f) * 1000).toInt(); update()
        sb.setOnSeekBarChangeListener(simpleSeek { cam.focusDiopters = minF * it / 1000f; update(); if (cam.afManual) cam.applyAndRefresh() })
        sw.setOnCheckedChangeListener { _, c -> cam.afManual = c; cam.applyAndRefresh() }
        box.addView(sw); box.addView(lbl); box.addView(sb)
        AlertDialog.Builder(this).setTitle("Fokus").setView(box).setPositiveButton("OK", null).show()
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------------- Tastatur-Ereignisse ----------------
    override fun dispatchKeyEvent(event: AndroidKeyEvent): Boolean {
        if (running && !editor.hasFocus() && event.action == AndroidKeyEvent.ACTION_UP) {
            val ch = event.unicodeChar.toChar()
            if (ch.isLetterOrDigit()) { interpreter.queue(KeyEvent(ch.lowercaseChar().toString())); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------- USB (optional) ----------------
    private fun connectUsb() {
        if (connected) return
        val driver = usb.findDriver() ?: run { usbStatus = "USB: kein ESP32 (optional)"; refreshTelemetry(); return }
        val device: UsbDevice = driver.device
        if (!usb.hasPermission(device)) {
            usbStatus = "USB: frage Berechtigung…"; refreshTelemetry()
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(actionUsbPermission).setPackage(packageName), flags)
            (getSystemService(Context.USB_SERVICE) as UsbManager).requestPermission(device, pi)
            return
        }
        if (usb.open(driver)) {
            connected = true
            usbStatus = "USB: verbunden ✓ (${device.productName ?: "ESP32"})"
            startLinkThread()
        } else usbStatus = "USB: Öffnen fehlgeschlagen"
        refreshTelemetry()
    }

    private fun startLinkThread() {
        linkThread = Thread {
            try {
                while (connected && !Thread.currentThread().isInterrupted) {
                    if (!running) usb.send(CommandProtocol.PING)
                    val line = usb.readLine()
                    if (line.isNotEmpty()) { usbStatus = "USB: $line"; refreshTelemetry() }
                    Thread.sleep(250)
                }
            } catch (_: InterruptedException) {}
        }.also { it.isDaemon = true; it.start() }
    }

    // --------------------------------------------------------------------
    override fun onDestroy() {
        super.onDestroy()
        running = false; connected = false
        linkThread?.interrupt()
        if (usb.isOpen) { usb.send(CommandProtocol.STOP); usb.close() }
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        camera?.stop()
    }
}
