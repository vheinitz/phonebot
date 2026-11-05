package de.phonebot

/**
 * PID-Regler für den Line-Follower. Läuft auf dem Telefon.
 *
 * Eingang:  Abweichung der Linie von der Bildmitte (in Pixeln, siehe [LineDetector]).
 * Ausgang:  Korrekturwert, der auf die Grundgeschwindigkeit der Räder gerechnet wird
 *           (links = base - korrektur, rechts = base + korrektur).
 *
 * Die Werte P/I/D und die Grundgeschwindigkeit sind in der App einstellbar.
 * Defaults stammen aus dem Simulator (simulator/index.html) und sind dort auf
 * mehreren Teststrecken validiert.
 */
class PidController(
    var kp: Double = 1.4,
    var ki: Double = 0.0,
    var kd: Double = 6.0,
) {
    private var integral = 0.0
    private var prevError = 0.0
    private var derivativeFiltered = 0.0

    /** Anti-Windup-Grenze für den I-Anteil. */
    private val iLimit = 400.0
    /** Tiefpass auf den D-Anteil: dämpft Rauschen der Bild-Ableitung. */
    private val dAlpha = 0.18

    data class Terms(val p: Double, val i: Double, val d: Double, val output: Double)

    fun reset(seedError: Double = 0.0) {
        integral = 0.0
        prevError = seedError          // kein D-Ruck beim ersten Schritt
        derivativeFiltered = 0.0
    }

    /**
     * @param error aktuelle Abweichung (px), >0 = Linie rechts von der Bildmitte
     * @param dt    Zeitschritt in Sekunden
     */
    fun update(error: Double, dt: Double): Terms {
        integral = (integral + error * dt).coerceIn(-iLimit, iLimit)
        val derivativeRaw = (error - prevError) / dt
        derivativeFiltered = dAlpha * derivativeRaw + (1 - dAlpha) * derivativeFiltered
        prevError = error

        val p = kp * error
        val i = ki * integral
        val d = kd * derivativeFiltered
        return Terms(p, i, d, p + i + d)
    }
}
