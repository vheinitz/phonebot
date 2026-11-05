package de.phonebot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-Tests des Interpreters (Interpreter.kt). Fehler ist normiert auf −1…+1
 * (+ = rechts). Fälle: Linienfolger, Fehlermeldung mit Zeile, Ereignisse
 * (start/key), for-Schleife, bstop/stop, if/else + Zonen.
 */
class InterpreterTest {

    @Test fun proportionalerFolger() {
        val p = Interpreter()
        assertTrue(p.compile(DEFAULT_PROGRAM))
        p.runStart(false, 0.0)
        p.step(true, 0.0)
        assertEquals(60, p.motorLeft); assertEquals(60, p.motorRight)   // mittig
        p.step(true, 0.2)
        assertTrue("Linie rechts -> rechtes Rad schneller", p.motorRight > p.motorLeft)
        p.step(false, 0.2)
        assertEquals(0, p.motorLeft); assertEquals(0, p.motorRight)      // verloren
    }

    @Test fun fehlerMitZeilennummer() {
        val e = Interpreter()
        assertFalse(e.compile("loop:\n  mot.left = \n"))
        assertTrue((e.error ?: "").contains("Zeile"))
    }

    @Test fun startKeyVariablen() {
        val src = "start:\n  speed = 0\nkey w:\n  speed = 100\nloop:\n  mot.left = speed\n  mot.right = speed\n"
        val k = Interpreter()
        assertTrue(k.compile(src))
        k.runStart(false, 0.0)
        k.step(true, 0.0)
        assertEquals(0, k.motorLeft)
        k.queue(KeyEvent("w"))
        k.step(true, 0.0)
        assertEquals(100, k.motorLeft); assertEquals(100, k.motorRight)
    }

    @Test fun forSchleifeSynchron() {
        val f = Interpreter()
        assertTrue(f.compile("loop:\n  n = 0\n  for i = 1 to 5:\n    n = n + i\n  mot.left = n"))
        f.runStart(false, 0.0); f.step(true, 0.0)
        assertEquals(15, f.motorLeft)   // 1+2+3+4+5
    }

    @Test fun bstopHaltetAn() {
        val b = Interpreter()
        assertTrue(b.compile("loop:\n  mot.left = 50\nbstop:\n  mot.left = 0\n  stop"))
        b.runStart(false, 0.0)
        assertFalse(b.step(true, 0.0))
        b.queue(BstopEvent)
        assertTrue(b.step(true, 0.0))
    }

    @Test fun ifElseUndZonen() {
        val z = Interpreter()
        assertTrue(z.compile("loop:\n  if cam.line.right:\n    mot.left = 90\n  else:\n    mot.left = 10"))
        z.runStart(false, 0.0)
        z.step(true, 0.6); assertEquals(90, z.motorLeft)   // deutlich rechts
        z.step(true, 0.0); assertEquals(10, z.motorLeft)   // mittig -> else
    }
}
