package de.phonebot

/**
 * Linienerkennung auf dem Telefon – bewusst leichtgewichtig (ohne OpenCV),
 * damit sie mit wenig CPU-Last bei 10–15 Bildern/Sekunde läuft.
 *
 * Pipeline (identisch zum Simulator):
 *   1. Kamerabild in kleiner Auflösung (von CameraX als Graustufen geliefert)
 *   2. nur einen unteren Bildausschnitt (ROI) betrachten
 *   3. in einer Detektionszeile die dunklen Pixel (Linie) suchen
 *   4. Schwerpunkt (Mittelpunkt) der dunklen Pixel berechnen
 *   5. Abweichung von der Bildmitte zurückgeben
 *
 * Die eigentliche Regelung macht [PidController].
 */
class LineDetector(
    /** Grauschwellwert: Pixel darunter gelten als Linie (dunkel). */
    var threshold: Int = 95,
    /** Relative Höhe der Detektionszeile im ROI (0 = oben/fern, 1 = unten/nah). */
    var detectRowFraction: Double = 0.87,
) {
    /** Keine Linie gefunden. */
    data class Result(val found: Boolean, val error: Double, val centerX: Double)

    /**
     * @param gray   Graustufenbild, Zeile für Zeile (0..255), Länge = width*height
     * @param width  Bildbreite
     * @param height Bildhöhe
     * @param roiTop erste Zeile des ROI (z. B. height*2/3); darunter wird gesucht
     */
    fun detect(gray: ByteArray, width: Int, height: Int, roiTop: Int): Result {
        val row = (roiTop + (height - roiTop) * detectRowFraction).toInt()
            .coerceIn(0, height - 1)
        val base = row * width

        var sum = 0L
        var count = 0
        for (x in 0 until width) {
            val v = gray[base + x].toInt() and 0xFF
            if (v < threshold) { sum += x; count++ }
        }
        if (count == 0) return Result(false, 0.0, -1.0)

        val center = sum.toDouble() / count
        val error = center - (width - 1) / 2.0   // >0 = Linie rechts
        return Result(true, error, center)
    }
}
