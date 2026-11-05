package de.phonebot

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Linienerkennung mit OpenCV.
 *
 * Ablauf: Y-Bild um [rotationDegrees] drehen → weichzeichnen → Schwellwert aufs
 * GANZE Bild (für die Anzeige, [lastDebug], gleiche Höhe wie das Live-Bild) →
 * im unteren Drittel (ROI) größte zusammenhängende Komponente → Schwerpunkt-x →
 * Abweichung normiert −1…+1.
 *
 * Schwellwert [fixedThreshold]: null = OTSU je Frame (adaptiv). Ein fester Wert
 * (per Schieber gesetzt oder über [learn] gelernt) ist stabiler.
 */
class CvLineDetector(
    var roiFraction: Double = 0.33,
    var minContrast: Double = 30.0,
    var minArea: Double = 20.0,
) {
    data class Result(val found: Boolean, val error: Double)

    /** Drehung des Analysebilds (0/90/180/270) – gilt für Live UND Auswertung. */
    @Volatile var rotationDegrees = 0
    /** Fester Schwellwert 0..255; null = OTSU adaptiv je Frame. */
    @Volatile var fixedThreshold: Double? = null

    /** Volles TH-Bild (gleiche Größe wie Live) nach [detect] in [lastDebug]. */
    @Volatile var debug = false
    @Volatile var lastDebug: Bitmap? = null
    /** Gedrehtes Vollbild (wie analysiert) nach [detect] in [lastLive]. */
    @Volatile var liveDebug = false
    @Volatile var lastLive: Bitmap? = null

    private val full by lazy { Mat() }
    private val rotated by lazy { Mat() }
    private val blurred by lazy { Mat() }
    private val binFull by lazy { Mat() }
    private val binRoi by lazy { Mat() }
    private val roiCopy by lazy { Mat() }
    private val labels by lazy { Mat() }
    private val stats by lazy { Mat() }
    private val centroids by lazy { Mat() }

    /** Dreht das Vollbild und legt die weichgezeichnete Fassung in [blurred] ab. */
    private fun prep(y: ByteArray, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || y.size < width * height) return false
        if (full.rows() != height || full.cols() != width) full.create(height, width, CvType.CV_8UC1)
        full.put(0, 0, y)
        when (rotationDegrees) {
            90 -> Core.rotate(full, rotated, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(full, rotated, Core.ROTATE_180)
            270 -> Core.rotate(full, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> full.copyTo(rotated)
        }
        Imgproc.GaussianBlur(rotated, blurred, Size(5.0, 5.0), 0.0)
        return true
    }

    fun detect(y: ByteArray, width: Int, height: Int): Result {
        if (!prep(y, width, height)) return Result(false, 0.0)
        val rw = blurred.cols(); val rh = blurred.rows()
        val roiH = (rh * roiFraction).toInt().coerceAtLeast(1); val roiTop = rh - roiH

        val roiBlur = blurred.submat(roiTop, rh, 0, rw)
        val mm = Core.minMaxLoc(roiBlur)
        val hasContrast = (mm.maxVal - mm.minVal) >= minContrast
        // Schwellwert: fest (Schieber/gelernt) oder OTSU auf dem ROI.
        val t = fixedThreshold ?: Imgproc.threshold(roiBlur, binRoi, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
        roiBlur.release()

        // Volles TH-Bild (für die Anzeige, gleiche Höhe wie Live).
        Imgproc.threshold(blurred, binFull, t, 255.0, Imgproc.THRESH_BINARY_INV)
        if (liveDebug) {
            val lv = Bitmap.createBitmap(rotated.cols(), rotated.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rotated, lv); lastLive = lv
        }
        if (debug) {
            val bmp = Bitmap.createBitmap(binFull.cols(), binFull.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(binFull, bmp); lastDebug = bmp
        }

        if (!hasContrast) return Result(false, 0.0)

        // Erkennung nur im unteren Drittel des vollen Binärbilds.
        val roiBin = binFull.submat(roiTop, rh, 0, rw)
        val n = Imgproc.connectedComponentsWithStats(roiBin, labels, stats, centroids)
        var best = -1; var bestArea = 0.0
        for (i in 1 until n) { val a = stats.get(i, Imgproc.CC_STAT_AREA)[0]; if (a > bestArea) { bestArea = a; best = i } }
        roiBin.release()
        val roiArea = roiH.toDouble() * rw
        if (best < 0 || bestArea < minArea || bestArea > roiArea * 0.6) return Result(false, 0.0)

        val cx = centroids.get(best, 0)[0]
        val error = (cx - (rw - 1) / 2.0) / (rw / 2.0)   // normiert [-1,1], + = rechts
        return Result(true, error)
    }

    /**
     * Linie lernen: OTSU auf den ROI, Median der Grauwerte der dunklen Fläche
     * als festen [fixedThreshold] übernehmen. false, wenn keine dunkle Fläche.
     */
    fun learn(y: ByteArray, width: Int, height: Int): Boolean {
        if (!prep(y, width, height)) return false
        val rw = blurred.cols(); val rh = blurred.rows()
        val roiH = (rh * roiFraction).toInt().coerceAtLeast(1); val roiTop = rh - roiH
        val roiBlur = blurred.submat(roiTop, rh, 0, rw)
        val t = Imgproc.threshold(roiBlur, binRoi, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
        roiBlur.copyTo(roiCopy)   // für dichten Pixelzugriff
        roiBlur.release()
        val count = roiCopy.rows() * roiCopy.cols()
        if (count <= 0) return false
        val buf = ByteArray(count); roiCopy.get(0, 0, buf)
        val dark = ArrayList<Int>(count / 2)
        for (b in buf) { val v = b.toInt() and 0xFF; if (v < t) dark.add(v) }
        if (dark.size < 20) return false
        dark.sort()
        fixedThreshold = dark[dark.size / 2].toDouble()
        return true
    }
}
