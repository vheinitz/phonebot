package de.phonebot

/**
 * Baut die Textbefehle des USB-Serial-Protokolls (siehe docs/PROTOCOL.md).
 * Hält keine Verbindung – nur reine Zeichenketten, damit die Logik testbar bleibt.
 */
object CommandProtocol {
    private fun clampSpeed(v: Int) = v.coerceIn(-255, 255)

    /** Ein Rad setzen: `SET,LEFT,120\n` */
    fun setLeft(v: Int)  = "SET,LEFT,${clampSpeed(v)}\n"
    fun setRight(v: Int) = "SET,RIGHT,${clampSpeed(v)}\n"

    /** Beide Räder in einem Rutsch (spart Round-Trips). */
    fun setBoth(left: Int, right: Int) =
        "SET,LEFT,${clampSpeed(left)}\nSET,RIGHT,${clampSpeed(right)}\n"

    const val STOP        = "STOP\n"
    const val MODE_AUTO   = "MODE,AUTO\n"
    const val MODE_MANUAL = "MODE,MANUAL\n"
    const val PING        = "PING\n"
    const val STATUS      = "STATUS\n"

    /**
     * Rechnet Regler-Korrektur + Grundgeschwindigkeit in Radsollwerte um.
     * Vorzeichenkonvention wie im Simulator:
     *   error>0 (Linie rechts) -> rechtes Rad schneller -> Roboter lenkt nach rechts.
     */
    fun wheelsFromCorrection(base: Int, correction: Double): Pair<Int, Int> {
        val corr = correction.coerceIn(-base.toDouble(), base.toDouble())
        val left  = (base - corr).toInt().coerceIn(0, 255)
        val right = (base + corr).toInt().coerceIn(0, 255)
        return left to right
    }
}
