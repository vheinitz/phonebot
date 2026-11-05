package de.phonebot

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Camera2-Steuerung für Phonebot.
 *
 * Bewusst OHNE Hardware-Vorschau (TextureView): Es gibt genau EINEN Live-Strom
 * (YUV), aus dem die App das Bild selbst erzeugt – die App dreht es einmal und
 * nutzt dasselbe gedrehte Bild für Anzeige UND Analyse. So können Live- und
 * Analysebild nicht auseinanderlaufen. Dazu ein umschaltbarer Foto-Stream
 * (RAW_SENSOR→DNG oder JPEG@Größe), der nur bei der Aufnahme angesteuert wird.
 *
 * Manuelles: Belichtungszeit+ISO (AE off), Fokus (AF off). Drehung macht der
 * Detektor; dieser Controller liefert nur [sensorOrientation] als Startwert.
 */
class CameraController(
    private val context: Context,
    private val cb: Callbacks,
) {
    interface Callbacks {
        fun onYuv(y: ByteArray, width: Int, height: Int)
        fun onStatus(text: String)
        fun onCaps(caps: Caps)
        fun onDng(path: String?, error: String?)
    }

    enum class CaptureMode { RAW, JPEG }

    data class Caps(
        val hasRaw: Boolean,
        val hasManualSensor: Boolean,
        val exposureNs: Range<Long>?,
        val iso: Range<Int>?,
        val minFocusDiopters: Float,
        val rawSizes: List<Size>,
        val jpegSizes: List<Size>,
    )

    @Volatile var aeManual = false
    @Volatile var exposureNs = 10_000_000L
    @Volatile var iso = 200
    @Volatile var afManual = false
    @Volatile var focusDiopters = 0f

    var sensorOrientation = 90; private set

    @Volatile var captureMode = CaptureMode.RAW
        private set
    private var jpegSize: Size? = null

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraId: String? = null
    private lateinit var characteristics: CameraCharacteristics
    private var caps = Caps(false, false, null, null, 0f, emptyList(), emptyList())

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var yuvReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var jpegReader: ImageReader? = null
    private var useRaw = false

    private var yuvSize = Size(352, 288)
    private var yuvSizes: List<Size> = emptyList()
    private var rawSize: Size? = null

    private var bg: HandlerThread? = null
    private var handler: Handler? = null

    private var pendingRawResult: TotalCaptureResult? = null
    private var pendingRawImage: Image? = null

    // --------------------------------------------------------------------
    fun start() {
        bg = HandlerThread("cam2").also { it.start() }
        handler = Handler(bg!!.looper)
        try { selectCamera(); configureSizes(); cb.onCaps(caps); openCamera() }
        catch (e: Exception) { cb.onStatus("Kamera-Fehler: ${e.message}") }
    }

    private fun selectCamera() {
        var chosen: String? = null; var fallback: String? = null
        for (id in manager.cameraIdList) {
            val ch = manager.getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            if (fallback == null) fallback = id
            val c = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            if (c.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)) { chosen = id; break }
        }
        cameraId = chosen ?: fallback ?: manager.cameraIdList.firstOrNull() ?: throw IllegalStateException("keine Kamera")
        characteristics = manager.getCameraCharacteristics(cameraId!!)
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val c = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        useRaw = c.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)
    }

    private fun configureSizes() {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("keine StreamConfigurationMap")
        yuvSizes = (map.getOutputSizes(ImageFormat.YUV_420_888) ?: arrayOf()).toList()
        yuvSize = pickClosest(yuvSizes.toTypedArray(), 352, 288)   // klein & stabil (Default)
        val rawSizes = (map.getOutputSizes(ImageFormat.RAW_SENSOR) ?: arrayOf()).sortedByDescending { it.width.toLong() * it.height }
        val jpegSizes = (map.getOutputSizes(ImageFormat.JPEG) ?: arrayOf()).sortedByDescending { it.width.toLong() * it.height }
        rawSize = rawSizes.firstOrNull(); if (rawSize == null) useRaw = false
        jpegSize = jpegSizes.firstOrNull()
        captureMode = if (useRaw) CaptureMode.RAW else CaptureMode.JPEG
        caps = Caps(useRaw, hasManual(), expRange(), isoRange(), minFocus(), rawSizes, jpegSizes)
    }

    private fun hasManual(): Boolean {
        val c = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        return c.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
    }
    private fun expRange() = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    private fun isoRange() = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    private fun minFocus() = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

    private fun pickClosest(sizes: Array<Size>?, w: Int, h: Int): Size {
        if (sizes == null || sizes.isEmpty()) return Size(w, h)
        val target = w.toLong() * h
        return sizes.minByOrNull { Math.abs(it.width.toLong() * it.height - target) } ?: sizes[0]
    }

    private fun openCamera() {
        val id = cameraId ?: return
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) { device = cam; createSession() }
                override fun onDisconnected(cam: CameraDevice) { cam.close(); device = null }
                override fun onError(cam: CameraDevice, e: Int) { cam.close(); device = null; cb.onStatus("Kamera-Fehler $e") }
            }, handler)
        } catch (se: SecurityException) { cb.onStatus("Kamera: Berechtigung fehlt") }
    }

    private fun closeReaders() {
        try { yuvReader?.close() } catch (_: Exception) {}
        try { rawReader?.close() } catch (_: Exception) {}
        try { jpegReader?.close() } catch (_: Exception) {}
        yuvReader = null; rawReader = null; jpegReader = null
    }

    private fun createSession() {
        val cam = device ?: return
        closeReaders()

        yuvReader = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val w = img.width; val h = img.height
                    val gray = ByteArray(w * h)
                    copyY(img.planes[0].buffer, img.planes[0].rowStride, w, h, gray)
                    cb.onYuv(gray, w, h)
                } catch (_: Exception) {} finally { img.close() }
            }, handler)
        }

        val surfaces = ArrayList<Surface>()
        surfaces.add(yuvReader!!.surface)
        if (captureMode == CaptureMode.RAW && useRaw && rawSize != null) {
            rawReader = ImageReader.newInstance(rawSize!!.width, rawSize!!.height, ImageFormat.RAW_SENSOR, 1).apply {
                setOnImageAvailableListener({ r -> onRawImage(r.acquireNextImage()) }, handler)
            }
            surfaces.add(rawReader!!.surface)
        } else if (captureMode == CaptureMode.JPEG && jpegSize != null) {
            jpegReader = ImageReader.newInstance(jpegSize!!.width, jpegSize!!.height, ImageFormat.JPEG, 1).apply {
                setOnImageAvailableListener({ r -> onJpegImage(r.acquireNextImage()) }, handler)
            }
            surfaces.add(jpegReader!!.surface)
        }

        try {
            @Suppress("DEPRECATION")
            cam.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; startRepeating(); cb.onStatus(modeStatus()) }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (captureMode == CaptureMode.RAW && useRaw) {
                        useRaw = false; try { rawReader?.close() } catch (_: Exception) {}; rawReader = null
                        cb.onStatus("YUV+RAW nicht möglich – RAW im Live-Betrieb aus"); createSession()
                    } else cb.onStatus("Kamera-Session fehlgeschlagen")
                }
            }, handler)
        } catch (e: Exception) { cb.onStatus("Session-Fehler: ${e.message}") }
    }

    private fun modeStatus(): String =
        if (captureMode == CaptureMode.JPEG && jpegSize != null) "Kamera: läuft · Live ${yuvSize.width}×${yuvSize.height} · Foto JPEG ${jpegSize!!.width}×${jpegSize!!.height}"
        else if (useRaw && rawSize != null) "Kamera: läuft · Live ${yuvSize.width}×${yuvSize.height} · Foto RAW ${rawSize!!.width}×${rawSize!!.height}"
        else "Kamera: läuft · Live ${yuvSize.width}×${yuvSize.height}"

    private fun startRepeating() {
        val cam = device ?: return; val s = session ?: return
        val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        yuvReader?.let { b.addTarget(it.surface) }
        applyControls(b)
        try { s.setRepeatingRequest(b.build(), null, handler) } catch (e: Exception) { cb.onStatus("Repeating-Fehler: ${e.message}") }
    }

    private fun applyControls(b: CaptureRequest.Builder) {
        if (aeManual && caps.hasManualSensor) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            caps.exposureNs?.let { b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs.coerceIn(it.lower, it.upper)) }
            caps.iso?.let { b.set(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(it.lower, it.upper)) }
        } else b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        if (afManual && caps.hasManualSensor) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters.coerceIn(0f, caps.minFocusDiopters))
        } else b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
    }

    fun applyAndRefresh() { handler?.post { startRepeating() } }

    /** Foto-Stream umstellen (RAW oder JPEG@Größe); JPEG≤~1000 wird auch Live-Größe. */
    fun setCaptureConfig(mode: CaptureMode, size: Size?) {
        handler?.post {
            captureMode = mode
            if (mode == CaptureMode.JPEG && size != null) {
                jpegSize = size
                if (Math.max(size.width, size.height) <= 1024)
                    yuvSize = pickClosest(yuvSizes.toTypedArray(), size.width, size.height)
            }
            if (mode == CaptureMode.RAW && !useRaw) { cb.onStatus("RAW auf diesem Gerät nicht verfügbar"); return@post }
            try { session?.close() } catch (_: Exception) {}
            session = null
            createSession()
        }
    }

    // --------------------------------------------------------------------
    fun capture() {
        handler?.post {
            val cam = device; val s = session
            if (cam == null || s == null) { cb.onDng(null, "Kamera nicht bereit"); return@post }
            try {
                if (captureMode == CaptureMode.RAW) {
                    if (!useRaw || rawReader == null) { cb.onDng(null, "RAW nicht verfügbar"); return@post }
                    pendingRawResult = null; pendingRawImage = null
                    val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    b.addTarget(rawReader!!.surface); applyControls(b)
                    s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(se: CameraCaptureSession, rq: CaptureRequest, res: TotalCaptureResult) {
                            pendingRawResult = res; tryWriteDng()
                        }
                    }, handler)
                } else {
                    if (jpegReader == null) { cb.onDng(null, "JPEG-Stream nicht bereit"); return@post }
                    val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    b.addTarget(jpegReader!!.surface); applyControls(b)
                    b.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                    s.capture(b.build(), null, handler)
                }
            } catch (e: Exception) { cb.onDng(null, "Capture-Fehler: ${e.message}") }
        }
    }

    private fun onRawImage(image: Image?) { if (image == null) return; pendingRawImage = image; tryWriteDng() }

    private fun tryWriteDng() {
        val result = pendingRawResult ?: return
        val image = pendingRawImage ?: return
        pendingRawResult = null; pendingRawImage = null
        val name = "phonebot_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".dng"
        try {
            saveToGallery(name, "image/x-adobe-dng") { out -> DngCreator(characteristics, result).use { it.writeImage(out, image) } }
            cb.onDng("Pictures/Phonebot/$name", null)
        } catch (e: Exception) { cb.onDng(null, "DNG-Fehler: ${e.message}") } finally { image.close() }
    }

    private fun onJpegImage(image: Image?) {
        if (image == null) return
        val name = "phonebot_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        try {
            val buf = image.planes[0].buffer
            val bytes = ByteArray(buf.remaining()); buf.get(bytes)
            saveToGallery(name, "image/jpeg") { out -> out.write(bytes) }
            cb.onDng("Pictures/Phonebot/$name", null)
        } catch (e: Exception) { cb.onDng(null, "JPEG-Fehler: ${e.message}") } finally { image.close() }
    }

    private fun saveToGallery(name: String, mime: String, write: (OutputStream) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Phonebot")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore lehnte ab")
            (resolver.openOutputStream(uri) ?: throw IllegalStateException("kein Ausgabestrom")).use { write(it) }
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0); resolver.update(uri, values, null, null)
        } else {
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), name)
            FileOutputStream(file).use { write(it) }
        }
    }

    private fun copyY(buffer: java.nio.ByteBuffer, rowStride: Int, w: Int, h: Int, out: ByteArray) {
        if (rowStride == w) buffer.get(out, 0, w * h)
        else {
            val rowTmp = ByteArray(rowStride)
            for (row in 0 until h) {
                buffer.position(row * rowStride)
                val len = Math.min(rowStride, buffer.remaining()); buffer.get(rowTmp, 0, len)
                System.arraycopy(rowTmp, 0, out, row * w, w)
            }
        }
    }

    fun stop() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        closeReaders()
        session = null; device = null
        bg?.quitSafely(); bg = null; handler = null
    }
}
