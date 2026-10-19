/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus

import de.neemann.digital.core.ObservableValues
import de.neemann.digital.core.extern.PortDefinition
import de.neemann.digital.core.extern.handler.ProcessInterface
import java.io.IOException

/**
 * A small VHDL simulator used if GHDL is not available.
 * It supports the synthesizable subset found in course libraries and in the VHDL files written by Quartus
 * from block diagrams: std_logic, std_logic_vector, unsigned, signed and integer signals, concurrent and
 * conditional assignments, processes with sensitivity list or a single WAIT UNTIL, IF, CASE, variables,
 * rising_edge/falling_edge/'EVENT, logical, relational and arithmetic operators.
 * Component instances are not supported.
 * The simulation uses delta cycles like a VHDL simulator. Undefined values ('U', 'X', 'Z') are treated as '0'.
 */
class VhdlInterpreter private constructor(private val design: Design) {

    /** a value: the bits and the width */
    private class V(val v: Long, val w: Int) {
        val bool: Boolean get() = (v and 1L) != 0L
    }

    // ---------------------------------------------------------------------------------
    // design
    // ---------------------------------------------------------------------------------

    private class Sig(val name: String, val width: Int, val low: Int, val ascending: Boolean, val integer: Boolean, val init: Long) {
        var value = init
        val mask: Long = if (width >= 64) -1L else (1L shl width) - 1
    }

    private sealed class Expr {
        class Const(val v: Long, val w: Int) : Expr()
        class Others(val bit: Long) : Expr()
        class Ref(val name: String) : Expr()
        class Index(val name: String, val index: Expr) : Expr()
        class Slice(val name: String, val a: Expr, val b: Expr) : Expr()
        class Unary(val op: String, val e: Expr) : Expr()
        class Bin(val op: String, val a: Expr, val b: Expr) : Expr()
        class Call(val name: String, val args: List<Expr>) : Expr()
        class Event(val name: String) : Expr()
    }

    private class Target(val name: String, val index: Expr?, val sliceA: Expr?, val sliceB: Expr?)

    private sealed class Stmt {
        class Assign(val target: Target, val value: Expr, val variable: Boolean) : Stmt()
        class If(val conds: List<Expr>, val bodies: List<List<Stmt>>, val otherwise: List<Stmt>?) : Stmt()
        class Case(val sel: Expr, val choices: List<Pair<List<Expr>?, List<Stmt>>>) : Stmt()
    }

    private class Process(val sensitivity: Set<String>, val waitUntil: Expr?, val body: List<Stmt>, val variables: Map<String, Sig>)

    private class Design(
        val name: String,
        val signals: Map<String, Sig>,
        val inputs: List<String>,
        val outputs: List<String>,
        val processes: List<Process>
    )

    // ---------------------------------------------------------------------------------
    // simulation
    // ---------------------------------------------------------------------------------

    private val pending = HashMap<Sig, Long>()
    private var events: Set<Sig> = emptySet()
    private var locals: Map<String, Sig> = emptyMap()

    init {
        // every process without a WAIT is executed once at the beginning
        for (p in design.processes) if (p.waitUntil == null) run(p)
        settle()
    }

    private fun sig(name: String): Sig = locals[name] ?: design.signals[name] ?: throw IOException("unknown signal '$name'")

    private fun run(p: Process) {
        locals = p.variables
        exec(p.body)
        locals = emptyMap()
    }

    /* the delta cycles: the pending assignments are applied until nothing changes */
    private fun settle() {
        var n = 0
        while (pending.isNotEmpty()) {
            if (++n > MAX_DELTAS) throw IOException("${design.name}: the VHDL simulation does not settle (combinational loop?)")
            val changed = HashSet<Sig>()
            for ((s, v) in pending) if (s.value != v) {
                s.value = v
                changed.add(s)
            }
            pending.clear()
            if (changed.isEmpty()) break
            events = changed
            val names = changed.map { it.name }.toSet()
            for (p in design.processes) {
                if (p.sensitivity.none { it in names }) continue
                if (p.waitUntil != null) {
                    locals = p.variables
                    val go = eval(p.waitUntil).bool
                    locals = emptyMap()
                    if (!go) continue
                }
                run(p)
            }
            events = emptySet()
        }
    }

    private fun exec(stmts: List<Stmt>) {
        for (s in stmts) when (s) {
            is Stmt.Assign -> assign(s)
            is Stmt.If -> {
                var done = false
                for (i in s.conds.indices) if (eval(s.conds[i]).bool) {
                    exec(s.bodies[i])
                    done = true
                    break
                }
                if (!done) s.otherwise?.let { exec(it) }
            }
            is Stmt.Case -> {
                val v = eval(s.sel).v
                for ((choices, body) in s.choices) {
                    if (choices == null || choices.any { eval(it).v == v }) {
                        exec(body)
                        break
                    }
                }
            }
        }
    }

    private fun assign(a: Stmt.Assign) {
        val s = sig(a.target.name)
        val old = if (a.variable) s.value else pending[s] ?: s.value
        val e = a.value
        val nv: Long = when {
            a.target.index != null -> {
                val bit = bitPos(s, eval(a.target.index).v.toInt())
                val b = if (e is Expr.Others) e.bit else eval(e).v and 1
                (old and (1L shl bit).inv()) or (b shl bit)
            }
            a.target.sliceA != null -> {
                val p1 = bitPos(s, eval(a.target.sliceA).v.toInt())
                val p2 = bitPos(s, eval(a.target.sliceB!!).v.toInt())
                val lo = minOf(p1, p2)
                val w = maxOf(p1, p2) - lo + 1
                val m = if (w >= 64) -1L else (1L shl w) - 1
                val v = if (e is Expr.Others) (if (e.bit != 0L) m else 0) else eval(e).v and m
                (old and (m shl lo).inv()) or (v shl lo)
            }
            e is Expr.Others -> if (e.bit != 0L) s.mask else 0
            else -> eval(e).v and s.mask
        }
        if (a.variable) s.value = nv else pending[s] = nv
    }

    /* the position of the bit with the given VHDL index in the value */
    private fun bitPos(s: Sig, index: Int) = if (s.ascending) s.width - 1 - (index - s.low) else index - s.low

    private fun eval(e: Expr): V = when (e) {
        is Expr.Const -> V(e.v, e.w)
        is Expr.Others -> V(if (e.bit != 0L) -1L else 0, 64)
        is Expr.Ref -> sig(e.name).let { V(it.value, it.width) }
        is Expr.Index -> sig(e.name).let { V((it.value ushr bitPos(it, eval(e.index).v.toInt())) and 1, 1) }
        is Expr.Slice -> sig(e.name).let { s ->
            val p1 = bitPos(s, eval(e.a).v.toInt())
            val p2 = bitPos(s, eval(e.b).v.toInt())
            val lo = minOf(p1, p2)
            val w = maxOf(p1, p2) - lo + 1
            V((s.value ushr lo) and if (w >= 64) -1L else (1L shl w) - 1, w)
        }
        is Expr.Event -> V(if (sig(e.name) in events) 1 else 0, 1)
        is Expr.Unary -> eval(e.e).let { x ->
            when (e.op) {
                "not" -> V(x.v.inv() and mask(x.w), x.w)
                "-" -> V((-x.v) and mask(x.w), x.w)
                else -> x
            }
        }
        is Expr.Bin -> binary(e.op, eval(e.a), eval(e.b))
        is Expr.Call -> call(e)
    }

    private fun call(e: Expr.Call): V {
        val args = e.args
        return when (e.name) {
            "rising_edge", "falling_edge" -> {
                val r = args[0] as? Expr.Ref ?: throw IOException("${e.name} needs a signal")
                val s = sig(r.name)
                val level = if (e.name == "rising_edge") 1L else 0L
                V(if (s in events && (s.value and 1) == level) 1 else 0, 1)
            }
            "conv_std_logic_vector", "to_unsigned", "to_signed", "conv_unsigned", "conv_signed" -> {
                val w = eval(args[1]).v.toInt()
                V(eval(args[0]).v and mask(w), w)
            }
            "resize" -> {
                val w = eval(args[1]).v.toInt()
                V(eval(args[0]).v and mask(w), w)
            }
            "shift_left", "shl" -> eval(args[0]).let { x -> V((x.v shl eval(args[1]).v.toInt()) and mask(x.w), x.w) }
            "shift_right", "shr" -> eval(args[0]).let { x -> V(x.v ushr eval(args[1]).v.toInt(), x.w) }
            // type conversions do not change the bits
            else -> eval(args[0])
        }
    }

    private fun binary(op: String, a: V, b: V): V {
        val w = maxOf(a.w, b.w)
        val m = mask(w)
        return when (op) {
            "and" -> V(a.v and b.v, w)
            "or" -> V(a.v or b.v, w)
            "xor" -> V(a.v xor b.v, w)
            "nand" -> V((a.v and b.v).inv() and m, w)
            "nor" -> V((a.v or b.v).inv() and m, w)
            "xnor" -> V((a.v xor b.v).inv() and m, w)
            "=" -> V(if (a.v == b.v) 1 else 0, 1)
            "/=" -> V(if (a.v != b.v) 1 else 0, 1)
            "<" -> V(if (a.v < b.v) 1 else 0, 1)
            "<=" -> V(if (a.v <= b.v) 1 else 0, 1)
            ">" -> V(if (a.v > b.v) 1 else 0, 1)
            ">=" -> V(if (a.v >= b.v) 1 else 0, 1)
            "+" -> V((a.v + b.v) and m, w)
            "-" -> V((a.v - b.v) and m, w)
            "*" -> V((a.v * b.v) and mask(minOf(64, a.w + b.w)), minOf(64, a.w + b.w))
            "/" -> V(if (b.v == 0L) 0 else a.v / b.v, a.w)
            "mod", "rem" -> V(if (b.v == 0L) 0 else a.v % b.v, a.w)
            "&" -> V((a.v shl b.w) or (b.v and mask(b.w)), minOf(64, a.w + b.w))
            else -> throw IOException("operator '$op' is not supported")
        }
    }

    // ---------------------------------------------------------------------------------
    // interface used by Digital
    // ---------------------------------------------------------------------------------

    private inner class Interface(private val ins: List<Sig>, private val outs: List<Sig>) : ProcessInterface {
        override fun writeValues(values: ObservableValues) {
            for (i in ins.indices) {
                val s = ins[i]
                val v = values[i].value and s.mask
                if (v != s.value) pending[s] = v
            }
            settle()
        }

        override fun readValues(values: ObservableValues) {
            for (i in outs.indices) values[i].value = outs[i].value
        }

        override fun close() {}
    }

    companion object {
        private const val MAX_DELTAS = 10000

        private fun mask(w: Int) = if (w >= 64) -1L else (1L shl w) - 1

        /**
         * Creates the simulation of the given entity.
         *
         * @param entity  the name of the entity
         * @param code    the VHDL code
         * @param inputs  the inputs in the order Digital uses
         * @param outputs the outputs in the order Digital uses
         * @throws IOException if the code is not supported
         */
        @JvmStatic
        @Throws(IOException::class)
        fun start(entity: String, code: String, inputs: PortDefinition, outputs: PortDefinition): ProcessInterface {
            val d = Parser(code).parse(entity)
            val sim = VhdlInterpreter(d)
            fun ports(pd: PortDefinition) = pd.map { p ->
                d.signals[p.name.lowercase()] ?: throw IOException("port '${p.name}' not found in entity '$entity'")
            }
            return sim.Interface(ports(inputs), ports(outputs))
        }

        /** returns null if the given entity can be simulated or the reason why not */
        @JvmStatic
        fun check(entity: String, code: String): String? = try {
            Parser(code).parse(entity)
            null
        } catch (e: IOException) {
            e.message
        }
    }

    // ---------------------------------------------------------------------------------
    // parser
    // ---------------------------------------------------------------------------------

    private class Tok(val kind: Char, val text: String, val line: Int)

    private class Parser(code: String) {
        private val toks = tokenize(code)
        private var pos = 0
        private val signals = LinkedHashMap<String, Sig>()
        private val constants = HashMap<String, Expr>()

        private fun peek(o: Int = 0) = toks.getOrNull(pos + o)
        private fun next(): Tok = toks.getOrNull(pos++) ?: throw IOException("unexpected end of the VHDL code")
        private fun isWord(w: String, o: Int = 0) = peek(o)?.let { it.kind == 'w' && it.text == w } ?: false
        private fun isSym(s: String, o: Int = 0) = peek(o)?.let { it.kind == 's' && it.text == s } ?: false
        private fun err(msg: String): Nothing = throw IOException("VHDL line ${peek()?.line ?: "end"}: $msg")
        private fun word(w: String) {
            if (!isWord(w)) err("'$w' expected but found '${peek()?.text}'")
            pos++
        }

        private fun sym(s: String) {
            if (!isSym(s)) err("'$s' expected but found '${peek()?.text}'")
            pos++
        }

        private fun ident(): String {
            val t = next()
            if (t.kind != 'w') err("name expected but found '${t.text}'")
            return t.text
        }

        private fun skipTo(s: String) {
            while (!isSym(s)) next()
            pos++
        }

        fun parse(entity: String): Design {
            val name = entity.lowercase()
            // the entity
            while (true) {
                if (peek() == null) throw IOException("entity '$entity' not found")
                if (isWord("entity") && peek(1)?.text == name && isWord("is", 2)) break
                pos++
            }
            pos += 3
            val ins = ArrayList<String>()
            val outs = ArrayList<String>()
            if (isWord("generic")) err("generics are not supported")
            word("port")
            sym("(")
            while (!isSym(")")) {
                val names = arrayListOf(ident())
                while (isSym(",")) {
                    pos++
                    names.add(ident())
                }
                sym(":")
                val mode = ident()
                val t = type()
                if (isSym(":=")) {
                    pos++
                    expr()
                }
                for (n in names) {
                    signals[n] = Sig(n, t.width, t.low, t.ascending, t.integer, 0)
                    if (mode == "in") ins.add(n) else outs.add(n)
                }
                if (isSym(";")) pos++
            }
            sym(")")
            sym(";")
            // the architecture
            while (true) {
                if (peek() == null) throw IOException("no architecture of '$entity' found")
                if (isWord("architecture") && isWord("of", 2) && peek(3)?.text == name) break
                pos++
            }
            pos += 5
            declarations(signals)
            word("begin")
            val processes = ArrayList<Process>()
            while (!isWord("end")) processes.add(concurrent())
            return Design(name, signals, ins, outs, processes)
        }

        private class Type(val width: Int, val low: Int, val ascending: Boolean, val integer: Boolean)

        private fun type(): Type {
            val t = ident()
            return when (t) {
                "std_logic", "std_ulogic", "bit", "boolean" -> Type(1, 0, false, false)
                "std_logic_vector", "std_ulogic_vector", "bit_vector", "unsigned", "signed" -> {
                    sym("(")
                    val a = constValue(expr())
                    val asc = when {
                        isWord("to") -> true
                        isWord("downto") -> false
                        else -> err("'downto' expected")
                    }
                    pos++
                    val b = constValue(expr())
                    sym(")")
                    Type(Math.abs((a - b).toInt()) + 1, minOf(a, b).toInt(), asc, false)
                }
                "integer", "natural", "positive" -> {
                    if (isWord("range")) {
                        pos++
                        val a = constValue(expr())
                        if (!isWord("to") && !isWord("downto")) err("'to' expected")
                        pos++
                        val b = constValue(expr())
                        val max = maxOf(a, b)
                        Type(maxOf(1, 64 - java.lang.Long.numberOfLeadingZeros(max)), 0, false, true)
                    } else Type(32, 0, false, true)
                }
                else -> err("the type '$t' is not supported")
            }
        }

        private fun constValue(e: Expr): Long = when (e) {
            is Expr.Const -> e.v
            is Expr.Bin -> {
                val a = constValue(e.a)
                val b = constValue(e.b)
                when (e.op) {
                    "+" -> a + b
                    "-" -> a - b
                    "*" -> a * b
                    "/" -> a / b
                    else -> err("constant expression expected")
                }
            }
            else -> err("constant expression expected")
        }

        /* signal, constant, variable, type and attribute declarations */
        private fun declarations(into: MutableMap<String, Sig>) {
            while (!isWord("begin")) {
                when {
                    isWord("signal") || isWord("variable") || isWord("shared") -> {
                        if (isWord("shared")) pos++
                        pos++
                        val names = arrayListOf(ident())
                        while (isSym(",")) {
                            pos++
                            names.add(ident())
                        }
                        sym(":")
                        val t = type()
                        var init = 0L
                        if (isSym(":=")) {
                            pos++
                            val e = expr()
                            init = if (e is Expr.Others) (if (e.bit != 0L) mask(t.width) else 0) else constValue(e)
                        }
                        sym(";")
                        for (n in names) into[n] = Sig(n, t.width, t.low, t.ascending, t.integer, init)
                    }
                    isWord("constant") -> {
                        pos++
                        val n = ident()
                        sym(":")
                        type()
                        sym(":=")
                        constants[n] = expr()
                        sym(";")
                    }
                    isWord("component") -> {
                        while (!(isWord("end") && isWord("component", 1))) pos++
                        pos += 2
                        skipTo(";")
                    }
                    isWord("attribute") -> skipTo(";")
                    isWord("type") || isWord("subtype") -> err("type declarations are not supported")
                    isWord("function") || isWord("procedure") -> err("functions and procedures are not supported")
                    else -> err("unexpected '${peek()?.text}'")
                }
            }
        }

        private fun concurrent(): Process {
            // an optional label
            if (peek()?.kind == 'w' && isSym(":", 1)) {
                if (!isWord("process", 2)) err("component instances are not supported")
                pos += 2
            }
            return when {
                isWord("process") -> process()
                isWord("with") -> selected()
                else -> conditional()
            }
        }

        private fun process(): Process {
            word("process")
            val sens = LinkedHashSet<String>()
            if (isSym("(")) {
                pos++
                while (!isSym(")")) {
                    val n = ident()
                    if (n == "all") err("process(all) is not supported")
                    sens.add(n)
                    if (isSym(",")) pos++
                }
                pos++
            }
            if (isWord("is")) pos++
            val vars = LinkedHashMap<String, Sig>()
            declarations(vars)
            word("begin")
            var wait: Expr? = null
            if (sens.isEmpty()) {
                if (!isWord("wait")) err("a process needs a sensitivity list or a WAIT UNTIL")
                pos++
                word("until")
                wait = expr()
                sym(";")
                collect(wait, sens)
            }
            val body = statements(setOf("end"))
            word("end")
            word("process")
            if (peek()?.kind == 'w') pos++
            sym(";")
            return Process(sens, wait, body, vars)
        }

        /* target <= a WHEN c ELSE b; */
        private fun conditional(): Process {
            val t = target()
            sym("<=")
            val values = ArrayList<Expr>()
            val conds = ArrayList<Expr>()
            values.add(expr())
            while (isWord("when")) {
                pos++
                conds.add(expr())
                word("else")
                values.add(expr())
            }
            sym(";")
            val body: List<Stmt> = if (conds.isEmpty()) listOf(Stmt.Assign(t, values[0], false))
            else listOf(Stmt.If(conds, conds.indices.map { listOf(Stmt.Assign(t, values[it], false)) }, listOf(Stmt.Assign(t, values.last(), false))))
            return implicit(body)
        }

        /* WITH sel SELECT target <= a WHEN c1, b WHEN OTHERS; */
        private fun selected(): Process {
            word("with")
            val sel = expr()
            word("select")
            val t = target()
            sym("<=")
            val choices = ArrayList<Pair<List<Expr>?, List<Stmt>>>()
            while (true) {
                val v = expr()
                word("when")
                choices.add(choiceList() to listOf(Stmt.Assign(t, v, false)))
                if (isSym(",")) pos++ else break
            }
            sym(";")
            return implicit(listOf(Stmt.Case(sel, choices)))
        }

        /* a concurrent statement is a process sensitive to all signals it reads */
        private fun implicit(body: List<Stmt>): Process {
            val sens = LinkedHashSet<String>()
            body.forEach { collect(it, sens) }
            return Process(sens, null, body, emptyMap())
        }

        private fun choiceList(): List<Expr>? {
            if (isWord("others")) {
                pos++
                return null
            }
            val l = arrayListOf(expr())
            while (isSym("|")) {
                pos++
                l.add(expr())
            }
            return l
        }

        private fun target(): Target {
            val n = ident()
            if (!isSym("(")) return Target(n, null, null, null)
            pos++
            val a = expr()
            if (isWord("downto") || isWord("to")) {
                pos++
                val b = expr()
                sym(")")
                return Target(n, null, a, b)
            }
            sym(")")
            return Target(n, a, null, null)
        }

        private fun statements(end: Set<String>): List<Stmt> {
            val l = ArrayList<Stmt>()
            while (peek()?.let { it.kind == 'w' && it.text in end } != true) {
                if (peek() == null) err("unexpected end")
                statement()?.let { l.add(it) }
            }
            return l
        }

        private fun statement(): Stmt? {
            when {
                isWord("null") -> {
                    pos++
                    sym(";")
                    return null
                }
                isWord("if") -> {
                    pos++
                    val conds = arrayListOf(expr())
                    word("then")
                    val bodies = arrayListOf(statements(setOf("elsif", "else", "end")))
                    var otherwise: List<Stmt>? = null
                    while (true) {
                        if (isWord("elsif")) {
                            pos++
                            conds.add(expr())
                            word("then")
                            bodies.add(statements(setOf("elsif", "else", "end")))
                        } else if (isWord("else")) {
                            pos++
                            otherwise = statements(setOf("end"))
                        } else break
                    }
                    word("end")
                    word("if")
                    sym(";")
                    return Stmt.If(conds, bodies, otherwise)
                }
                isWord("case") -> {
                    pos++
                    val sel = expr()
                    word("is")
                    val choices = ArrayList<Pair<List<Expr>?, List<Stmt>>>()
                    while (isWord("when")) {
                        pos++
                        val c = choiceList()
                        sym("=>")
                        choices.add(c to statements(setOf("when", "end")))
                    }
                    word("end")
                    word("case")
                    sym(";")
                    return Stmt.Case(sel, choices)
                }
                isWord("wait") -> err("only one WAIT at the beginning of a process is supported")
                isWord("for") || isWord("while") || isWord("loop") -> err("loops are not supported")
                else -> {
                    val t = target()
                    val variable = isSym(":=")
                    if (!variable) sym("<=") else pos++
                    val v = expr()
                    if (isWord("after")) {
                        // delays are ignored
                        pos++
                        expr()
                        if (peek()?.kind == 'w') pos++
                    }
                    sym(";")
                    return Stmt.Assign(t, v, variable)
                }
            }
        }

        // ---- expressions --------------------------------------------------------------

        private val logical = setOf("and", "or", "xor", "nand", "nor", "xnor")
        private val relational = setOf("=", "/=", "<", "<=", ">", ">=")

        fun expr(): Expr {
            var e = relation()
            while (peek()?.let { it.kind == 'w' && it.text in logical } == true) {
                val op = next().text
                e = Expr.Bin(op, e, relation())
            }
            return e
        }

        private fun relation(): Expr {
            val a = simple()
            if (peek()?.let { it.kind == 's' && it.text in relational } == true) {
                val op = next().text
                return Expr.Bin(op, a, simple())
            }
            return a
        }

        private fun simple(): Expr {
            var e = if (isSym("-")) {
                pos++
                Expr.Unary("-", term())
            } else term()
            while (isSym("+") || isSym("-") || isSym("&")) {
                val op = next().text
                e = Expr.Bin(op, e, term())
            }
            return e
        }

        private fun term(): Expr {
            var e = factor()
            while (isSym("*") || isSym("/") || isWord("mod") || isWord("rem")) {
                val op = next().text
                e = Expr.Bin(op, e, factor())
            }
            return e
        }

        private fun factor(): Expr {
            if (isWord("not")) {
                pos++
                return Expr.Unary("not", factor())
            }
            return primary()
        }

        private fun primary(): Expr {
            val t = next()
            return when (t.kind) {
                'n' -> Expr.Const(t.text.toLong(), 32)
                'c' -> Expr.Const(if (t.text == "1" || t.text == "h") 1 else 0, 1)
                'b' -> Expr.Const(if (t.text.isEmpty()) 0 else java.lang.Long.parseUnsignedLong(t.text, 2), maxOf(1, t.text.length))
                's' -> when (t.text) {
                    "(" -> {
                        if (isWord("others")) {
                            pos++
                            sym("=>")
                            val v = primary()
                            sym(")")
                            Expr.Others(if (v is Expr.Const) v.v and 1 else 0)
                        } else {
                            val e = expr()
                            sym(")")
                            e
                        }
                    }
                    else -> err("unexpected '${t.text}'")
                }
                'w' -> when {
                    t.text == "true" -> Expr.Const(1, 1)
                    t.text == "false" -> Expr.Const(0, 1)
                    isSym("'") && isWord("event", 1) -> {
                        pos += 2
                        Expr.Event(t.text)
                    }
                    isSym("(") -> {
                        pos++
                        if (t.text !in FUNCTIONS) {
                            val a = expr()
                            if (isWord("downto") || isWord("to")) {
                                pos++
                                val b = expr()
                                sym(")")
                                Expr.Slice(t.text, a, b)
                            } else {
                                sym(")")
                                Expr.Index(t.text, a)
                            }
                        } else {
                            val args = arrayListOf(expr())
                            while (isSym(",")) {
                                pos++
                                args.add(expr())
                            }
                            sym(")")
                            Expr.Call(t.text, args)
                        }
                    }
                    constants.containsKey(t.text) -> constants[t.text]!!
                    else -> Expr.Ref(t.text)
                }
                else -> err("unexpected '${t.text}'")
            }
        }

        private fun collect(s: Stmt, into: MutableSet<String>) {
            when (s) {
                is Stmt.Assign -> {
                    collect(s.value, into)
                    s.target.index?.let { collect(it, into) }
                }
                is Stmt.If -> {
                    s.conds.forEach { collect(it, into) }
                    s.bodies.forEach { b -> b.forEach { collect(it, into) } }
                    s.otherwise?.forEach { collect(it, into) }
                }
                is Stmt.Case -> {
                    collect(s.sel, into)
                    s.choices.forEach { (_, b) -> b.forEach { collect(it, into) } }
                }
            }
        }

        private fun collect(e: Expr, into: MutableSet<String>) {
            when (e) {
                is Expr.Ref -> into.add(e.name)
                is Expr.Index -> {
                    into.add(e.name)
                    collect(e.index, into)
                }
                is Expr.Slice -> into.add(e.name)
                is Expr.Event -> into.add(e.name)
                is Expr.Unary -> collect(e.e, into)
                is Expr.Bin -> {
                    collect(e.a, into)
                    collect(e.b, into)
                }
                is Expr.Call -> e.args.forEach { collect(it, into) }
                else -> {}
            }
        }

        companion object {
            private val FUNCTIONS = setOf("rising_edge", "falling_edge", "conv_std_logic_vector", "to_unsigned", "to_signed",
                "conv_unsigned", "conv_signed", "resize", "shift_left", "shift_right", "shl", "shr", "conv_integer", "to_integer",
                "unsigned", "signed", "std_logic_vector", "to_stdlogicvector", "to_bitvector", "std_ulogic_vector")

            /* token kinds: w word, n number, c character literal, b bit string, s symbol */
            private fun tokenize(code: String): List<Tok> {
                val l = ArrayList<Tok>()
                var i = 0
                var line = 1
                val n = code.length
                while (i < n) {
                    val c = code[i]
                    when {
                        c == '\n' -> {
                            line++
                            i++
                        }
                        c.isWhitespace() -> i++
                        c == '-' && i + 1 < n && code[i + 1] == '-' -> while (i < n && code[i] != '\n') i++
                        c.isLetter() -> {
                            val s = i
                            while (i < n && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                            val w = code.substring(s, i).lowercase()
                            // based bit strings like x"0F" or b"0101"
                            if ((w == "x" || w == "b" || w == "o") && i < n && code[i] == '"') {
                                val e = code.indexOf('"', i + 1)
                                val digits = code.substring(i + 1, e).replace("_", "")
                                val bits = when (w) {
                                    "x" -> digits.map { Integer.toBinaryString(Character.digit(it, 16)).padStart(4, '0') }.joinToString("")
                                    "o" -> digits.map { Integer.toBinaryString(Character.digit(it, 8)).padStart(3, '0') }.joinToString("")
                                    else -> digits
                                }
                                l.add(Tok('b', bits, line))
                                i = e + 1
                            } else l.add(Tok('w', w, line))
                        }
                        c.isDigit() -> {
                            val s = i
                            while (i < n && (code[i].isDigit() || code[i] == '_')) i++
                            l.add(Tok('n', code.substring(s, i).replace("_", ""), line))
                        }
                        c == '"' -> {
                            val e = code.indexOf('"', i + 1)
                            if (e < 0) throw IOException("VHDL line $line: unterminated string")
                            l.add(Tok('b', code.substring(i + 1, e).replace("_", "").lowercase()
                                .map { if (it == '1' || it == 'h') '1' else '0' }.joinToString(""), line))
                            i = e + 1
                        }
                        // a character literal, but not an attribute like clk'event
                        c == '\'' && i + 2 < n && code[i + 2] == '\'' -> {
                            l.add(Tok('c', code[i + 1].lowercase(), line))
                            i += 3
                        }
                        else -> {
                            val two = if (i + 1 < n) code.substring(i, i + 2) else ""
                            if (two in setOf("<=", ":=", "=>", "/=", ">=", "**")) {
                                l.add(Tok('s', two, line))
                                i += 2
                            } else {
                                l.add(Tok('s', c.toString(), line))
                                i++
                            }
                        }
                    }
                }
                return l
            }
        }
    }
}
