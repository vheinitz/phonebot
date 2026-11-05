package de.phonebot

/**
 * Interpreter der Phonebot-Mini-Sprache – 1:1-Port aus dem Simulator
 * (simulator/index.html, Abschnitt "SPRACHE"/"LAUFZEIT").
 *
 * Sprache (Kurzfassung):
 *   - Blöcke über Einrückung (wie Python); ':' beendet die Kopfzeile.
 *     ';' trennt mehrere Anweisungen in EINER Zeile. '#' = Kommentar.
 *   - Abschnitte/Ereignisse nur auf oberster Ebene:
 *       start:  loop:  key <taste>:  bstop:  bat_low:
 *     Bare Anweisungen oben = implizite Hauptschleife (wie loop).
 *   - Steuerung: if … : / else: , for i = A to B: , stop
 *   - Werte: eigene Variablen, + - * /, Vergleiche, and/or/not
 *   - Lesbarer Kontext aus dem Kamerabild: cam.line.{found,lost,error,x,
 *     left,center,right,far_left,far_right}; schreibbar: mot.left, mot.right
 *
 * Ausführungsmodell identisch zum Simulator: start einmal, dann pro Takt erst
 * die Ereignisse (key/bstop/bat_low), danach implizite Schleife + loop.
 * for läuft synchron (Iterationsschutz 20000). bstop/stop fordern Halt an.
 */

/** Kontext eines Taktes – was die Kamera liefert + Motor-Sollwerte + Variablen. */
class CamContext {
    var found = false
    var error = 0.0          // Abweichung in Pixeln (+ = rechts)
    var x = 0.0              // normiert -1..+1
    var left = false; var right = false; var center = false
    var farLeft = false; var farRight = false
    var motLeft = 0.0; var motRight = 0.0
    val vars = HashMap<String, Double>()
    val lost: Boolean get() = !found
}

/** Zonengrenzen auf der normierten x-Position (resolutionsunabhängig). */
private const val CENTER_TOL_X = 0.12
private const val FAR_TOL_X = 0.40

/**
 * Baut den Kontext aus Detektorergebnis. [holdError] = letzter Fehler*0.9,
 * damit bei kurz verlorener Linie die Richtung gehalten wird (wie im Simulator).
 */
fun buildContext(
    found: Boolean, error: Double,
    motLeft: Double, motRight: Double, vars: HashMap<String, Double>
): CamContext {
    val c = CamContext()
    c.found = found
    c.error = error                 // bereits normiert [-1,1], + = rechts
    c.x = error.coerceIn(-1.0, 1.0)
    c.left = c.x < -CENTER_TOL_X; c.right = c.x > CENTER_TOL_X
    c.center = kotlin.math.abs(c.x) <= CENTER_TOL_X
    c.farLeft = c.x < -FAR_TOL_X; c.farRight = c.x > FAR_TOL_X
    c.motLeft = motLeft; c.motRight = motRight
    c.vars.putAll(vars)
    return c
}

private typealias Expr = (CamContext) -> Double

private val READABLE: Map<String, Expr> = mapOf(
    "cam.line.error" to { c -> c.error },
    "cam.line.x" to { c -> c.x },
    "cam.line.found" to { c -> if (c.found) 1.0 else 0.0 },
    "cam.line.lost" to { c -> if (c.lost) 1.0 else 0.0 },
    "cam.line.left" to { c -> if (c.left) 1.0 else 0.0 },
    "cam.line.right" to { c -> if (c.right) 1.0 else 0.0 },
    "cam.line.center" to { c -> if (c.center) 1.0 else 0.0 },
    "cam.line.far_left" to { c -> if (c.farLeft) 1.0 else 0.0 },
    "cam.line.far_right" to { c -> if (c.farRight) 1.0 else 0.0 },
    "mot.left" to { c -> c.motLeft },
    "mot.right" to { c -> c.motRight },
)
private val WRITABLE = setOf("mot.left", "mot.right")
private val RESERVED = setOf(
    "if", "else", "for", "to", "and", "or", "not", "true", "false",
    "start", "loop", "key", "bstop", "bat_low", "stop"
)

// --- Fehler mit (optionaler) Zeilennummer -------------------------------------
private class ParseEx(val ln: Int?, val m: String) : Exception(m)

// --- Tokenizer ----------------------------------------------------------------
private class Tok(val v: String, val num: Double?, val id: Boolean)

private fun tokenize(s: String): List<Tok> {
    val out = ArrayList<Tok>()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == ' ' || c == '\t') { i++; continue }
        if (i + 1 < s.length) {
            val two = s.substring(i, i + 2)
            if (two == "<=" || two == ">=" || two == "==" || two == "!=") {
                out.add(Tok(two, null, false)); i += 2; continue
            }
        }
        if ("+-*/()<>=;".indexOf(c) >= 0) { out.add(Tok(c.toString(), null, false)); i++; continue }
        if (c.isDigit() || c == '.') {
            var j = i
            while (j < s.length && (s[j].isDigit() || s[j] == '.')) j++
            val num = s.substring(i, j).toDoubleOrNull() ?: throw ParseEx(null, "Zahl ungültig")
            out.add(Tok(s.substring(i, j), num, false)); i = j; continue
        }
        if (c.isLetter() || c == '_') {
            var j = i
            while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_' || s[j] == '.')) j++
            out.add(Tok(s.substring(i, j), null, true)); i = j; continue
        }
        throw ParseEx(null, "Unerwartetes Zeichen '$c'")
    }
    return out
}

// --- Parser (Ausdrücke, Bedingungen, Ziele) -----------------------------------
private sealed class Target
private class MotTarget(val side: String) : Target()   // "left"/"right"
private class VarTarget(val name: String) : Target()

private class Parser(private val toks: List<Tok>) {
    private var p = 0
    private fun peek(): Tok? = if (p < toks.size) toks[p] else null
    private fun next(): Tok = toks[p++]
    fun eat(v: String) { val t = peek(); if (t == null || t.v != v) throw ParseEx(null, "'$v' erwartet"); p++ }
    fun atEnd(): Boolean = p >= toks.size
    fun peekV(): String = peek()?.v ?: ""

    fun expr(): Expr = addsub()
    private fun addsub(): Expr {
        var l = muldiv()
        while (peek() != null && (peek()!!.v == "+" || peek()!!.v == "-")) {
            val op = next().v; val r = muldiv(); val L = l
            l = if (op == "+") { c -> L(c) + r(c) } else { c -> L(c) - r(c) }
        }
        return l
    }
    private fun muldiv(): Expr {
        var l = unary()
        while (peek() != null && (peek()!!.v == "*" || peek()!!.v == "/")) {
            val op = next().v; val r = unary(); val L = l
            l = if (op == "*") { c -> L(c) * r(c) } else { c -> val d = r(c); L(c) / (if (d == 0.0) 1e-9 else d) }
        }
        return l
    }
    private fun unary(): Expr {
        if (peek() != null && peek()!!.v == "-") { next(); val r = unary(); return { c -> -r(c) } }
        return primary()
    }
    private fun primary(): Expr {
        val tk = peek() ?: throw ParseEx(null, "Ausdruck erwartet")
        if (tk.num != null) { next(); val v = tk.num; return { _ -> v } }
        if (tk.v == "true") { next(); return { _ -> 1.0 } }
        if (tk.v == "false") { next(); return { _ -> 0.0 } }
        if (tk.v == "(") { next(); val e = expr(); eat(")"); return e }
        if (tk.id) {
            next(); val name = tk.v
            READABLE[name]?.let { g -> return { c -> g(c) } }
            if (name.contains('.')) throw ParseEx(null, "Unbekannte Variable '$name'")
            if (RESERVED.contains(name)) throw ParseEx(null, "'$name' ist ein Schlüsselwort")
            return { c -> c.vars[name] ?: 0.0 }
        }
        throw ParseEx(null, "Unerwartet '${tk.v}'")
    }

    fun cond(): Expr = orE()
    private fun orE(): Expr {
        var l = andE()
        while (peek() != null && peek()!!.v == "or") { next(); val r = andE(); val L = l; l = { c -> if (L(c) != 0.0 || r(c) != 0.0) 1.0 else 0.0 } }
        return l
    }
    private fun andE(): Expr {
        var l = notE()
        while (peek() != null && peek()!!.v == "and") { next(); val r = notE(); val L = l; l = { c -> if (L(c) != 0.0 && r(c) != 0.0) 1.0 else 0.0 } }
        return l
    }
    private fun notE(): Expr {
        if (peek() != null && peek()!!.v == "not") { next(); val r = notE(); return { c -> if (r(c) != 0.0) 0.0 else 1.0 } }
        return cmp()
    }
    private fun cmp(): Expr {
        val l = expr(); val tk = peek()
        if (tk != null && tk.v in listOf("<", ">", "<=", ">=", "==", "!=")) {
            val op = next().v; val r = expr(); val L = l
            return when (op) {
                "<" -> { c -> if (L(c) < r(c)) 1.0 else 0.0 }
                ">" -> { c -> if (L(c) > r(c)) 1.0 else 0.0 }
                "<=" -> { c -> if (L(c) <= r(c)) 1.0 else 0.0 }
                ">=" -> { c -> if (L(c) >= r(c)) 1.0 else 0.0 }
                "==" -> { c -> if (L(c) == r(c)) 1.0 else 0.0 }
                else -> { c -> if (L(c) != r(c)) 1.0 else 0.0 }
            }
        }
        return l
    }
    fun varName(): String {
        val tk = if (p < toks.size) next() else throw ParseEx(null, "Variablenname erwartet")
        if (!tk.id || tk.v.contains('.') || RESERVED.contains(tk.v) || READABLE.containsKey(tk.v))
            throw ParseEx(null, "Variablenname erwartet")
        return tk.v
    }
    fun target(): Target {
        val tk = if (p < toks.size) next() else throw ParseEx(null, "Motor- oder Variablenname erwartet")
        if (!tk.id) throw ParseEx(null, "Motor- oder Variablenname erwartet")
        if (WRITABLE.contains(tk.v)) return MotTarget(if (tk.v == "mot.left") "left" else "right")
        if (tk.v.contains('.')) throw ParseEx(null, "'${tk.v}' kann nicht gesetzt werden")
        if (RESERVED.contains(tk.v)) throw ParseEx(null, "'${tk.v}' ist ein Schlüsselwort")
        return VarTarget(tk.v)
    }
}

// --- AST-Knoten ---------------------------------------------------------------
private sealed class Node
private class Assign(val target: Target, val fn: Expr) : Node()
private class IfNode(val cond: Expr, val body: MutableList<Node>, var elseBody: MutableList<Node>?) : Node()
private class ForNode(val name: String, val fromFn: Expr, val toFn: Expr, val body: MutableList<Node>) : Node()
private object StopNode : Node()

// Kopf-Knoten (Abschnitte) nur während des Parsens:
private class Header(val t: String, var body: MutableList<Node>, val key: String? = null,
                     val cond: Expr? = null, var elseBody: MutableList<Node>? = null,
                     val name: String? = null, val fromFn: Expr? = null, val toFn: Expr? = null)

private val SECTIONS = setOf("start", "loop", "bstop", "bat_low", "key")

private fun compileCond(str: String, ln: Int): Expr {
    try {
        val mp = Parser(tokenize(str)); val f = mp.cond()
        if (!mp.atEnd()) throw ParseEx(null, "Bedingung nicht verstanden bei '${mp.peekV()}'")
        return f
    } catch (e: ParseEx) { throw ParseEx(ln, e.m) }
}

private fun compileStmtTokens(toks: List<Tok>): Node {
    if (toks.size == 1 && toks[0].id && toks[0].v == "stop") return StopNode
    val mp = Parser(toks); val target = mp.target(); mp.eat("="); val fn = mp.expr()
    if (!mp.atEnd()) throw ParseEx(null, "Zuviel in Anweisung bei '${mp.peekV()}'")
    return Assign(target, fn)
}

private fun parseStatements(str: String, ln: Int): MutableList<Node> {
    val toks = try { tokenize(str) } catch (e: ParseEx) { throw ParseEx(ln, e.m) }
    val chunks = ArrayList<MutableList<Tok>>(); var cur = ArrayList<Tok>()
    for (t in toks) { if (t.v == ";") { chunks.add(cur); cur = ArrayList() } else cur.add(t) }
    if (cur.isNotEmpty()) chunks.add(cur)
    val out = ArrayList<Node>()
    for (ch in chunks) {
        if (ch.isEmpty()) continue
        try { out.add(compileStmtTokens(ch)) } catch (e: ParseEx) { throw ParseEx(ln, e.m) }
    }
    if (out.isEmpty()) throw ParseEx(ln, "keine gültige Anweisung")
    return out
}

private fun parseHeader(headerStr: String, ln: Int): Header {
    val parts = headerStr.trim().split(Regex("\\s+"))
    val w = parts[0]
    when (w) {
        "if" -> return Header("if", ArrayList(), cond = compileCond(headerStr.substring(headerStr.indexOf("if") + 2), ln))
        "for" -> {
            val src = headerStr.substring(headerStr.indexOf("for") + 3)
            try {
                val mp = Parser(tokenize(src)); val name = mp.varName(); mp.eat("="); val fromFn = mp.expr()
                mp.eat("to"); val toFn = mp.expr(); if (!mp.atEnd()) throw ParseEx(null, "Rest nach 'to B'")
                return Header("for", ArrayList(), name = name, fromFn = fromFn, toFn = toFn)
            } catch (e: ParseEx) { throw ParseEx(ln, "for erwartet 'for i = A to B': ${e.m}") }
        }
        "start", "loop", "bstop", "bat_low" -> {
            if (parts.size > 1 && parts[1].isNotEmpty()) throw ParseEx(ln, "'$w:' erwartet keine Angabe")
            return Header(w, ArrayList())
        }
        "key" -> {
            val rest = headerStr.substring(3).trim()
            if (rest.isEmpty() || Regex("\\s").containsMatchIn(rest)) throw ParseEx(ln, "'key <Taste>:' erwarten, z. B. key w:")
            return Header("key", ArrayList(), key = rest.lowercase())
        }
        "else" -> throw ParseEx(ln, "'else' ohne vorangehendes 'if'")
    }
    throw ParseEx(ln, "Unbekannter Block '$w'")
}

private class Line(val indent: Int, val text: String, val ln: Int)

private fun preprocess(src: String): List<Line> {
    val raw = src.replace("\t", "  ").split("\n")
    val lines = ArrayList<Line>()
    for (i in raw.indices) {
        var s = raw[i]; val h = s.indexOf('#'); if (h >= 0) s = s.substring(0, h)
        if (s.isBlank()) continue
        val indent = s.length - s.trimStart(' ').length
        lines.add(Line(indent, s.trim(), i + 1))
    }
    return lines
}

private class BlockResult(val nodes: MutableList<Node>, val next: Int)

/** Baut Knotenliste; Header werden hier in die konkreten Node-Typen überführt. */
private fun parseBlock(lines: List<Line>, start: Int, indent: Int, topLevel: Boolean, sink: MutableList<Header>?): BlockResult {
    val nodes = ArrayList<Node>()
    var i = start
    while (i < lines.size && lines[i].indent >= indent) {
        if (lines[i].indent > indent) throw ParseEx(lines[i].ln, "unerwartete Einrückung")
        val L = lines[i]; val colon = L.text.indexOf(':')
        if (colon >= 0) {
            val header = parseHeader(L.text.substring(0, colon).trim(), L.ln)
            if (SECTIONS.contains(header.t) && !topLevel) throw ParseEx(L.ln, "'${header.t}:' nur ganz links (oberste Ebene)")
            val inline = L.text.substring(colon + 1).trim()
            if (inline.isNotEmpty()) { header.body = parseStatements(inline, L.ln); i++ }
            else {
                if (i + 1 >= lines.size || lines[i + 1].indent <= indent) throw ParseEx(L.ln, "eingerückter Block nach ':' fehlt")
                val r = parseBlock(lines, i + 1, lines[i + 1].indent, false, null); header.body = r.nodes; i = r.next
            }
            if (header.t == "if" && i < lines.size && lines[i].indent == indent && Regex("^else\\s*:").containsMatchIn(lines[i].text)) {
                val e = lines[i]; val ec = e.text.indexOf(':'); val einline = e.text.substring(ec + 1).trim()
                if (einline.isNotEmpty()) { header.elseBody = parseStatements(einline, e.ln); i++ }
                else {
                    if (i + 1 >= lines.size || lines[i + 1].indent <= indent) throw ParseEx(e.ln, "Block nach 'else:' fehlt")
                    val r = parseBlock(lines, i + 1, lines[i + 1].indent, false, null); header.elseBody = r.nodes; i = r.next
                }
            }
            if (topLevel && sink != null && SECTIONS.contains(header.t)) sink.add(header)
            else nodes.add(headerToNode(header))
        } else {
            val fw = L.text.split(Regex("\\s+"))[0]
            if (fw in setOf("if", "for", "else", "start", "loop", "key", "bstop", "bat_low"))
                throw ParseEx(L.ln, "':' am Ende der Kopfzeile fehlt")
            for (st in parseStatements(L.text, L.ln)) nodes.add(st)
            i++
        }
    }
    return BlockResult(nodes, i)
}

private fun headerToNode(h: Header): Node = when (h.t) {
    "if" -> IfNode(h.cond!!, h.body, h.elseBody)
    "for" -> ForNode(h.name!!, h.fromFn!!, h.toFn!!, h.body)
    else -> throw ParseEx(null, "interner Fehler: Abschnitt '${h.t}' als Anweisung")
}

/** Übersetztes Programm (dateiprivat – nur der Interpreter arbeitet damit). */
private class Program {
    val start = ArrayList<Node>()
    val loop = ArrayList<Node>()
    val implicitLoop = ArrayList<Node>()
    val bstop = ArrayList<Node>()
    val batLow = ArrayList<Node>()
    val keys = HashMap<String, MutableList<Node>>()

    fun summary(): String {
        val s = ArrayList<String>()
        if (start.isNotEmpty()) s.add("start")
        if (loop.isNotEmpty() || implicitLoop.isNotEmpty()) s.add("loop")
        if (keys.isNotEmpty()) s.add("${keys.size}× key")
        if (bstop.isNotEmpty()) s.add("bstop")
        if (batLow.isNotEmpty()) s.add("bat_low")
        return "übersetzt" + (if (s.isNotEmpty()) " (" + s.joinToString(", ") + ")" else "")
    }
}

/** Ergebnis des Übersetzens. */
private class CompileResult(val program: Program?, val error: String?)

private fun compileProgram(src: String): CompileResult {
    val lines = try { preprocess(src) } catch (e: ParseEx) { return CompileResult(null, e.m) }
    if (lines.isEmpty()) return CompileResult(Program(), null)
    if (lines[0].indent != 0) return CompileResult(null, "Zeile ${lines[0].ln}: darf nicht eingerückt sein")
    val sink = ArrayList<Header>()
    val topNodes: MutableList<Node>
    try {
        val r = parseBlock(lines, 0, 0, true, sink)
        topNodes = r.nodes
        if (r.next < lines.size) throw ParseEx(lines[r.next].ln, "Einrückung passt nicht")
    } catch (e: ParseEx) {
        return CompileResult(null, (if (e.ln != null) "Zeile ${e.ln}: " else "") + e.m)
    }
    val prog = Program()
    for (h in sink) when (h.t) {
        "start" -> prog.start.addAll(h.body)
        "loop" -> prog.loop.addAll(h.body)
        "bstop" -> prog.bstop.addAll(h.body)
        "bat_low" -> prog.batLow.addAll(h.body)
        "key" -> prog.keys.getOrPut(h.key!!) { ArrayList() }.addAll(h.body)
    }
    prog.implicitLoop.addAll(topNodes)
    return CompileResult(prog, null)
}

/** Ereignisse, die zwischen Takten anfallen. */
sealed class Event
class KeyEvent(val key: String) : Event()
object BstopEvent : Event()
object BatLowEvent : Event()

/**
 * Laufzeit: hält das aktuelle Programm, die Variablen und die Ereigniswarteschlange.
 * Alle Methoden sind auf dem Analyse-Thread aufzurufen (einfädig).
 */
class Interpreter {
    private var program: Program? = null
    var error: String? = null; private set
    var status: String = "leer"; private set

    private val vars = HashMap<String, Double>()
    private var motLeft = 0.0
    private var motRight = 0.0
    private var halt = false
    private var forGuard = 0
    private val events = ArrayDeque<Event>()

    val motorLeft: Int get() = Math.round(motLeft).toInt()
    val motorRight: Int get() = Math.round(motRight).toInt()

    /** Quelltext übersetzen. Gibt true bei Erfolg, sonst steht die Meldung in [error]. */
    fun compile(src: String): Boolean {
        val r = compileProgram(src)
        return if (r.error != null) {
            error = r.error; program = null; status = "⚠ ${r.error}"; false
        } else {
            error = null; program = r.program; status = "✓ ${r.program!!.summary()}"; true
        }
    }

    fun queue(ev: Event) { events.add(ev) }

    /** start-Abschnitt einmal ausführen (Variablen zurücksetzen). */
    fun runStart(found: Boolean, errNorm: Double) {
        vars.clear(); motLeft = 0.0; motRight = 0.0; halt = false; events.clear()
        val p = program ?: return
        if (p.start.isNotEmpty()) { forGuard = 0; runBody(p.start, ctx(found, errNorm)) }
    }

    /**
     * Einen Takt ausführen: erst Ereignisse, dann implizite Schleife + loop.
     * @param errNorm Abweichung normiert [-1,1] (+ = rechts).
     * @return true, wenn Halt angefordert wurde (stop/bstop) → Aufrufer stoppt.
     */
    fun step(found: Boolean, errNorm: Double): Boolean {
        val p = program
        if (p == null || error != null) { motLeft = 0.0; motRight = 0.0; return false }
        val c = ctx(found, errNorm)
        forGuard = 0
        while (events.isNotEmpty()) {
            when (val ev = events.removeFirst()) {
                is KeyEvent -> p.keys[ev.key]?.let { runBody(it, c) }
                is BstopEvent -> { runBody(p.bstop, c); halt = true }
                is BatLowEvent -> runBody(p.batLow, c)
            }
        }
        runBody(p.implicitLoop, c)
        runBody(p.loop, c)
        motLeft = c.motLeft; motRight = c.motRight
        if (halt) { halt = false; return true }
        return false
    }

    private fun ctx(found: Boolean, errNorm: Double): CamContext =
        buildContext(found, errNorm, motLeft, motRight, vars)

    private fun runBody(list: List<Node>, c: CamContext) {
        for (n in list) { execNode(n, c); if (halt) return }
    }

    private fun execNode(n: Node, c: CamContext) {
        when (n) {
            is Assign -> {
                var v = n.fn(c)
                if (v.isNaN() || v.isInfinite()) v = 0.0
                when (val t = n.target) {
                    is MotTarget -> {
                        v = v.coerceIn(-255.0, 255.0)
                        if (t.side == "left") c.motLeft = v else c.motRight = v
                    }
                    is VarTarget -> c.vars[t.name] = v
                }
            }
            is IfNode -> if (n.cond(c) != 0.0) runBody(n.body, c) else n.elseBody?.let { runBody(it, c) }
            is ForNode -> {
                val a = Math.round(n.fromFn(c)).toInt(); val b = Math.round(n.toFn(c)).toInt()
                var k = a
                while (k <= b) {
                    c.vars[n.name] = k.toDouble(); runBody(n.body, c)
                    if (++forGuard > 20000) { halt = true; return }
                    if (halt) return
                    k++
                }
            }
            StopNode -> halt = true
        }
    }
}

/** Standardprogramm für den Editor beim ersten Start. */
const val DEFAULT_PROGRAM = """# Proportionaler Linienfolger
# cam.line.error ist normiert auf -1..+1 (+ = Linie rechts).
loop:
  mot.left  = 60 - cam.line.error * 50
  mot.right = 60 + cam.line.error * 50
  if cam.line.lost:
    mot.left  = 0
    mot.right = 0
"""
