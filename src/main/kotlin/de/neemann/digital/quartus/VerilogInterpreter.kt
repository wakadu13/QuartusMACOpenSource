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
 * A small Verilog simulator used if Icarus Verilog is not available.
 * It supports the synthesizable subset used in courses:
 *  - modules with ANSI and non-ANSI port declarations, parameters and localparams, also overridden by #(...)
 *  - wire, reg, logic and integer objects with ranges, memories like reg [7:0] mem [0:15], initial values
 *  - continuous assignments, gate primitives (and, or, not, ...), always @(posedge clk or negedge rst),
 *    always @(*), always @(a or b), always_ff, always_comb, initial blocks
 *  - blocking and non-blocking assignments with the scheduling of a Verilog simulator
 *  - if/else, case, casez and casex with default, begin/end blocks, for, while and repeat loops, functions
 *  - all operators: bitwise, logical, reduction, arithmetic, shifts, comparisons, the conditional operator,
 *    bit and part selects (also +: and -:), concatenations and replications, $signed, $unsigned and $clog2,
 *    the expression widths and the signedness follow the rules of Verilog
 *  - sized and unsized literals like 4'b1010, 8'hFF or 'd5, `define macros and `ifdef
 *  - module instances (the instantiated module must be in the same code, the design is flattened),
 *    generate for and generate if
 *
 * Not supported are tasks, vectors wider than 64 bits, hierarchical names and several event controls in one block.
 * Delays are ignored. Undefined values (x, z) are treated as 0.
 */
class VerilogInterpreter private constructor(private val name: String) {

    // ---------------------------------------------------------------------------------
    // design
    // ---------------------------------------------------------------------------------

    /** a net or variable, the range is [left:right], memories store the words in [mem] */
    private class Sig(val name: String, val width: Int, val signed: Boolean, val left: Long, val right: Long) {
        var value = 0L
        var mem: LongArray? = null
        var memLow = 0L
        val listeners = ArrayList<Trigger>()
    }

    private class Trigger(val proc: Proc, val edge: Int)

    /** the names declared in a module, a generate block, a function or a named block */
    private class Scope(val parent: Scope?, val prefix: String) {
        /** the values are signals, parameters (Expr.Const), functions or genvars */
        val names = HashMap<String, Any>()
        fun find(n: String): Any? = names[n] ?: parent?.find(n)
    }

    private sealed class Expr {
        /** the self-determined width, -1 if not yet known */
        var w = -1

        /** the signedness: -1 unknown, 0 unsigned, 1 signed */
        var sg = -1

        class Const(val v: Long, val width: Int, val signed: Boolean, val dcz: Long = 0, val dcx: Long = 0) : Expr()
        class Ref(val scope: Scope, val name: String, val line: Int) : Expr() {
            var sig: Sig? = null

            /** an undeclared name becomes an implicit net */
            var implicit = false
        }

        /** a bit select, a word of a memory or a part select */
        class Select(val base: Expr, val sel: Sel) : Expr()
        class Unary(val op: String, val e: Expr) : Expr()
        class Bin(val op: String, val a: Expr, val b: Expr) : Expr()
        class Cond(val c: Expr, val a: Expr, val b: Expr) : Expr()
        class Concat(val parts: List<Expr>, val count: Expr?) : Expr()
        class Call(val scope: Scope, val name: String, val args: List<Expr>, val line: Int) : Expr() {
            var f: Func? = null
        }

        class Sys(val name: String, val args: List<Expr>) : Expr()
    }

    /** a select: [a] if b is null, [a:b], [a+:b] or [a-:b] */
    private class Sel(val a: Expr, val b: Expr?, val mode: Int)

    /** the left hand side of an assignment */
    private sealed class LV {
        class Simple(val ref: Expr.Ref, val sels: List<Sel>) : LV()
        class Cat(val parts: List<LV>) : LV()
    }

    private sealed class Stmt {
        class Block(val body: List<Stmt>) : Stmt()
        class Assign(val lv: LV, val rhs: Expr, val nb: Boolean) : Stmt()
        class If(val c: Expr, val a: Stmt?, val b: Stmt?) : Stmt()
        class Case(val sel: Expr, val kind: Int, val items: List<Pair<List<Expr>?, Stmt?>>) : Stmt()
        class For(val init: Stmt?, val c: Expr, val step: Stmt?, val body: Stmt?) : Stmt()

        /** a while loop if [count] is null, otherwise a repeat loop */
        class While(val c: Expr?, val count: Expr?, val body: Stmt?) : Stmt()
    }

    /** a process: [events] is null if the process is sensitive to all signals it reads */
    private class Proc(val body: Stmt, val events: List<Pair<Expr.Ref, Int>>?) {
        var queued = false
    }

    private class Func(val name: String, val ret: Sig, val inputs: List<Sig>, val locals: List<Sig>, val body: Stmt) {
        var active = 0
    }

    /** an update of a part of a signal or of a memory word */
    private class Upd(val sig: Sig, val word: Int, val lo: Int, val w: Int, val v: Long)

    // ---------------------------------------------------------------------------------
    // simulation
    // ---------------------------------------------------------------------------------

    private val active = ArrayDeque<Proc>()
    private val nba = ArrayList<Upd>()
    private var current: Proc? = null
    private var depth = 0

    private fun init(procs: List<Proc>, initials: List<Proc>) {
        for (p in procs) {
            if (p.events == null) {
                val reads = LinkedHashSet<Sig>()
                val loopVars = HashSet<Sig>()
                collect(p.body, reads, loopVars)
                reads.removeAll(loopVars)
                for (s in reads) s.listeners.add(Trigger(p, ANY))
            } else for ((r, e) in p.events) sig(r).listeners.add(Trigger(p, e))
        }
        for (p in initials) {
            current = p
            exec(p.body)
            current = null
        }
        // the combinational processes are executed once at the beginning
        for (p in procs) if (p.events == null || p.events.all { it.second == ANY }) schedule(p)
        settle()
    }

    private fun sig(r: Expr.Ref): Sig = r.sig ?: throw IOException("'${r.name}' is not a constant")

    private fun schedule(p: Proc) {
        if (!p.queued) {
            p.queued = true
            active.add(p)
        }
    }

    /* the active events are processed, then the non-blocking assignments are applied, until nothing changes */
    private fun settle() {
        var n = 0
        while (true) {
            while (active.isNotEmpty()) {
                val p = active.removeFirst()
                p.queued = false
                if (++n > MAX_STEPS) {
                    active.forEach { it.queued = false }
                    active.clear()
                    throw IOException("$name: the Verilog simulation does not settle (combinational loop?)")
                }
                current = p
                try {
                    exec(p.body)
                } finally {
                    current = null
                }
            }
            if (nba.isEmpty()) break
            val l = ArrayList(nba)
            nba.clear()
            for (u in l) apply(u)
        }
    }

    private fun apply(u: Upd) {
        val s = u.sig
        val m = s.mem
        if (u.word >= 0 && m != null) {
            val old = m[u.word]
            val nv = merge(old, u.lo, u.w, u.v) and mask(s.width)
            if (nv != old) {
                m[u.word] = nv
                notify(s, 0, 0)
            }
        } else {
            val old = s.value
            val nv = merge(old, u.lo, u.w, u.v) and mask(s.width)
            if (nv != old) {
                s.value = nv
                notify(s, old, nv)
            }
        }
    }

    private fun notify(s: Sig, old: Long, nv: Long) {
        for (t in s.listeners) {
            if (t.proc === current) continue
            val fire = when (t.edge) {
                POS -> (old and 1L) == 0L && (nv and 1L) == 1L
                NEG -> (old and 1L) == 1L && (nv and 1L) == 0L
                else -> true
            }
            if (fire) schedule(t.proc)
        }
    }

    private fun exec(s: Stmt?) {
        when (s) {
            null -> {}
            is Stmt.Block -> for (x in s.body) exec(x)
            is Stmt.Assign -> {
                val lw = lvWidth(s.lv)
                val v = ev(s.rhs, maxOf(lw, width(s.rhs)), signed(s.rhs)) and mask(lw)
                val l = ArrayList<Upd>(1)
                targets(s.lv, v, l)
                if (s.nb) nba.addAll(l) else l.forEach { apply(it) }
            }
            is Stmt.If -> if (truth(s.c)) exec(s.a) else exec(s.b)
            is Stmt.Case -> exec(caseBody(s))
            is Stmt.For -> {
                exec(s.init)
                var n = 0
                while (truth(s.c)) {
                    if (++n > MAX_LOOP) throw IOException("$name: endless loop")
                    exec(s.body)
                    exec(s.step)
                }
            }
            is Stmt.While -> {
                if (s.count != null) {
                    val c = num(s.count)
                    if (c > MAX_LOOP) throw IOException("$name: endless loop")
                    for (i in 0 until c) exec(s.body)
                } else {
                    var n = 0
                    while (truth(s.c!!)) {
                        if (++n > MAX_LOOP) throw IOException("$name: endless loop")
                        exec(s.body)
                    }
                }
            }
        }
    }

    private fun caseBody(s: Stmt.Case): Stmt? {
        val sw = width(s.sel)
        var def: Stmt? = null
        var hasDefault = false
        for ((items, body) in s.items) {
            if (items == null) {
                def = body
                hasDefault = true
                continue
            }
            for (it in items) {
                val cw = maxOf(sw, width(it))
                val cs = signed(s.sel) && signed(it)
                var dc = 0L
                if (it is Expr.Const) {
                    when (s.kind) {
                        CASEZ -> dc = it.dcz
                        CASEX -> dc = it.dcz or it.dcx
                        else -> if ((it.dcz or it.dcx) != 0L) continue
                    }
                }
                if (((ev(s.sel, cw, cs) xor ev(it, cw, cs)) and dc.inv() and mask(cw)) == 0L) return body
            }
        }
        return if (hasDefault) def else null
    }

    private fun truth(e: Expr) = ev(e, width(e), signed(e)) != 0L

    /* the value of an index or a count */
    private fun num(e: Expr): Long {
        val w = width(e)
        val v = ev(e, w, signed(e))
        return if (signed(e)) sext(v, w) else v
    }

    // ---- assignments ----------------------------------------------------------------

    private fun lvWidth(lv: LV): Int = when (lv) {
        is LV.Cat -> lv.parts.sumOf { lvWidth(it) }
        is LV.Simple -> {
            val s = sig(lv.ref)
            val sels = if (s.mem != null) lv.sels.drop(1) else lv.sels
            when {
                sels.isEmpty() -> s.width
                sels.size > 1 -> throw IOException("the target of the assignment is not supported")
                sels[0].b == null -> 1
                else -> partRange(s.left, s.right, sels[0]).second
            }
        }
    }

    /* the updates for the assignment of v to the left hand side */
    private fun targets(lv: LV, v: Long, out: MutableList<Upd>) {
        when (lv) {
            is LV.Cat -> {
                var x = v
                for (p in lv.parts.reversed()) {
                    val pw = lvWidth(p)
                    targets(p, x and mask(pw), out)
                    x = shr(x, pw)
                }
            }
            is LV.Simple -> {
                val s = sig(lv.ref)
                var sels = lv.sels
                var word = -1
                val m = s.mem
                if (m != null) {
                    if (sels.isEmpty() || sels[0].b != null) throw IOException("the memory '${s.name}' needs an index")
                    val p = num(sels[0].a) - s.memLow
                    if (p < 0 || p >= m.size) return
                    word = p.toInt()
                    sels = sels.drop(1)
                }
                when {
                    sels.isEmpty() -> out.add(Upd(s, word, 0, s.width, v))
                    sels[0].b == null -> {
                        val p = bitPos(s.left, s.right, num(sels[0].a))
                        if (p in 0 until s.width) out.add(Upd(s, word, p, 1, v))
                    }
                    else -> {
                        val (lo, w) = partRange(s.left, s.right, sels[0])
                        if (lo >= 0 && lo + w <= s.width) out.add(Upd(s, word, lo, w, v))
                    }
                }
            }
        }
    }

    /* the lowest bit position and the width of a part select */
    private fun partRange(left: Long, right: Long, sel: Sel): Pair<Int, Int> {
        val a = num(sel.a)
        val b = num(sel.b!!)
        val (i1, i2) = when (sel.mode) {
            PLUS -> a to a + b - 1
            MINUS -> a - b + 1 to a
            else -> a to b
        }
        val p1 = bitPos(left, right, i1)
        val p2 = bitPos(left, right, i2)
        return minOf(p1, p2) to Math.abs(p1 - p2) + 1
    }

    // ---- expressions ----------------------------------------------------------------

    private fun isWord(e: Expr.Select) = e.sel.b == null && e.base is Expr.Ref && sig(e.base).mem != null

    private fun width(e: Expr): Int {
        if (e.w >= 0) return e.w
        val w = when (e) {
            is Expr.Const -> e.width
            is Expr.Ref -> sig(e).let {
                if (it.mem != null) throw IOException("the memory '${it.name}' needs an index")
                it.width
            }
            is Expr.Select -> when {
                isWord(e) -> sig(e.base as Expr.Ref).width
                e.sel.b == null -> 1
                e.sel.mode == PART -> return Math.abs(num(e.sel.a) - num(e.sel.b)).toInt() + 1
                else -> num(e.sel.b).toInt()
            }
            is Expr.Unary -> if (e.op == "~" || e.op == "-" || e.op == "+") width(e.e) else 1
            is Expr.Bin -> when (e.op) {
                in ARITH -> maxOf(width(e.a), width(e.b))
                in SHIFTS, "**" -> width(e.a)
                else -> 1
            }
            is Expr.Cond -> maxOf(width(e.a), width(e.b))
            is Expr.Concat -> e.parts.sumOf { width(it) } * (e.count?.let { num(it).toInt() } ?: 1)
            is Expr.Call -> e.f!!.ret.width
            is Expr.Sys -> if (e.name == "\$signed" || e.name == "\$unsigned") width(e.args[0]) else 32
        }
        if (w > 64) throw IOException("vectors wider than 64 bits are not supported")
        if (w < 0) throw IOException("negative width")
        e.w = w
        return w
    }

    private fun signed(e: Expr): Boolean {
        if (e.sg >= 0) return e.sg == 1
        val s = when (e) {
            is Expr.Const -> e.signed
            is Expr.Ref -> sig(e).signed
            is Expr.Select -> isWord(e) && sig(e.base as Expr.Ref).signed
            is Expr.Unary -> (e.op == "~" || e.op == "-" || e.op == "+") && signed(e.e)
            is Expr.Bin -> when (e.op) {
                in ARITH -> signed(e.a) && signed(e.b)
                in SHIFTS, "**" -> signed(e.a)
                else -> false
            }
            is Expr.Cond -> signed(e.a) && signed(e.b)
            is Expr.Concat -> false
            is Expr.Call -> e.f!!.ret.signed
            is Expr.Sys -> e.name != "\$unsigned"
        }
        e.sg = if (s) 1 else 0
        return s
    }

    /* the value and the range of the base of a select */
    private fun base(e: Expr): Triple<Long, Long, Long> {
        if (e is Expr.Ref) {
            val s = sig(e)
            return Triple(s.value, s.left, s.right)
        }
        if (e is Expr.Select && isWord(e)) {
            val s = sig(e.base as Expr.Ref)
            return Triple(ev(e, s.width, false), s.left, s.right)
        }
        val w = width(e)
        return Triple(ev(e, w, signed(e)), w - 1L, 0L)
    }

    /* evaluates the expression with the width w and the signedness s of the context, the result is masked to w */
    private fun ev(e: Expr, w: Int, s: Boolean): Long = when (e) {
        is Expr.Const -> ext(e.v, e.width, w, s)
        is Expr.Ref -> sig(e).let { ext(it.value, it.width, w, s) }
        is Expr.Select -> {
            if (isWord(e)) {
                val sg = sig(e.base as Expr.Ref)
                val m = sg.mem!!
                val p = num(e.sel.a) - sg.memLow
                ext(if (p >= 0 && p < m.size) m[p.toInt()] else 0, sg.width, w, s)
            } else {
                val (v, left, right) = base(e.base)
                val bw = Math.abs(left - right) + 1
                if (e.sel.b == null) {
                    val p = bitPos(left, right, num(e.sel.a))
                    if (p in 0 until bw) (v ushr p) and 1 else 0
                } else {
                    val (lo, pw) = partRange(left, right, e.sel)
                    if (lo < 0) 0 else shr(v, lo) and mask(pw) and mask(w)
                }
            }
        }
        is Expr.Unary -> unary(e, w, s)
        is Expr.Bin -> binary(e, w, s)
        is Expr.Cond -> if (truth(e.c)) ev(e.a, w, s) else ev(e.b, w, s)
        is Expr.Concat -> {
            var r = 0L
            val n = e.count?.let { num(it) } ?: 1
            for (i in 0 until n) for (p in e.parts) {
                val pw = width(p)
                r = shl(r, pw) or ev(p, pw, signed(p))
            }
            r and mask(w)
        }
        is Expr.Call -> ext(call(e), e.f!!.ret.width, w, s)
        is Expr.Sys -> when (e.name) {
            "\$signed", "\$unsigned" -> {
                val a = e.args[0]
                val aw = width(a)
                ext(ev(a, aw, signed(a)), aw, w, s)
            }
            else -> {
                // $clog2
                val x = num(e.args[0])
                var r = 0L
                while (r < 63 && (1L shl r.toInt()) < x) r++
                ext(r, 32, w, s)
            }
        }
    }

    private fun unary(e: Expr.Unary, w: Int, s: Boolean): Long {
        val m = mask(w)
        return when (e.op) {
            "~" -> ev(e.e, w, s).inv() and m
            "-" -> (-ev(e.e, w, s)) and m
            "+" -> ev(e.e, w, s)
            "!" -> if (truth(e.e)) 0 else 1
            else -> {
                val aw = width(e.e)
                val am = mask(aw)
                val x = ev(e.e, aw, signed(e.e))
                when (e.op) {
                    "&" -> if (x == am) 1 else 0
                    "~&" -> if (x == am) 0 else 1
                    "|" -> if (x != 0L) 1 else 0
                    "~|" -> if (x != 0L) 0 else 1
                    "^" -> (java.lang.Long.bitCount(x) and 1).toLong()
                    else -> (java.lang.Long.bitCount(x) and 1 xor 1).toLong()
                }
            }
        }
    }

    private fun binary(e: Expr.Bin, w: Int, s: Boolean): Long {
        val m = mask(w)
        when (e.op) {
            "+", "-", "*", "/", "%", "&", "|", "^", "~^", "^~" -> {
                val x = ev(e.a, w, s)
                val y = ev(e.b, w, s)
                return when (e.op) {
                    "+" -> x + y
                    "-" -> x - y
                    "*" -> x * y
                    "/" -> if (y == 0L) 0 else if (s) sext(x, w) / sext(y, w) else java.lang.Long.divideUnsigned(x, y)
                    "%" -> if (y == 0L) 0 else if (s) sext(x, w) % sext(y, w) else java.lang.Long.remainderUnsigned(x, y)
                    "&" -> x and y
                    "|" -> x or y
                    "^" -> x xor y
                    else -> (x xor y).inv()
                } and m
            }
            "**" -> {
                val x = ev(e.a, w, s)
                var r = 1L
                for (i in 0 until minOf(num(e.b), 64)) r *= x
                return r and m
            }
            "<<", "<<<" -> return shl(ev(e.a, w, s), shiftAmount(e.b)) and m
            ">>" -> return shr(ev(e.a, w, s), shiftAmount(e.b))
            ">>>" -> {
                val x = ev(e.a, w, s)
                val n = shiftAmount(e.b)
                return if (s) (sext(x, w) shr minOf(n, 63)) and m else shr(x, n)
            }
            "&&" -> return if (truth(e.a) && truth(e.b)) 1 else 0
            "||" -> return if (truth(e.a) || truth(e.b)) 1 else 0
            else -> {
                // the comparisons: the operands are evaluated with their common width
                val cw = maxOf(width(e.a), width(e.b))
                val cs = signed(e.a) && signed(e.b)
                val x = ev(e.a, cw, cs)
                val y = ev(e.b, cw, cs)
                val c = if (cs) sext(x, cw).compareTo(sext(y, cw)) else java.lang.Long.compareUnsigned(x, y)
                val r = when (e.op) {
                    "==", "===" -> c == 0
                    "!=", "!==" -> c != 0
                    "<" -> c < 0
                    "<=" -> c <= 0
                    ">" -> c > 0
                    ">=" -> c >= 0
                    else -> throw IOException("operator '${e.op}' is not supported")
                }
                return if (r) 1 else 0
            }
        }
    }

    private fun shiftAmount(e: Expr): Int {
        val v = ev(e, width(e), false)
        return if (v < 0 || v > 64) 64 else v.toInt()
    }

    private fun call(e: Expr.Call): Long {
        val f = e.f!!
        if (depth > MAX_CALL_DEPTH) throw IOException("${f.name}: the recursion is too deep")
        val args = e.args.mapIndexed { i, a ->
            val iw = f.inputs.getOrNull(i)?.width ?: throw IOException("${f.name}: too many arguments")
            ev(a, maxOf(iw, width(a)), signed(a)) and mask(iw)
        }
        // the variables of a function are static, they are saved if the function is called recursively
        val saved = if (f.active > 0) f.locals.map { it.value } else null
        f.active++
        depth++
        try {
            for ((i, s) in f.inputs.withIndex()) s.value = args.getOrElse(i) { 0 }
            f.ret.value = 0
            exec(f.body)
            return f.ret.value
        } finally {
            depth--
            f.active--
            if (saved != null) f.locals.forEachIndexed { i, s -> s.value = saved[i] }
        }
    }

    private fun collect(s: Stmt?, into: MutableSet<Sig>, loopVars: MutableSet<Sig>) {
        when (s) {
            null -> {}
            is Stmt.Block -> s.body.forEach { collect(it, into, loopVars) }
            is Stmt.Assign -> {
                collect(s.rhs, into)
                collect(s.lv, into)
            }
            is Stmt.If -> {
                collect(s.c, into)
                collect(s.a, into, loopVars)
                collect(s.b, into, loopVars)
            }
            is Stmt.Case -> {
                collect(s.sel, into)
                for ((items, b) in s.items) {
                    items?.forEach { collect(it, into) }
                    collect(b, into, loopVars)
                }
            }
            is Stmt.For -> {
                (s.init as? Stmt.Assign)?.lv?.let { if (it is LV.Simple) loopVars.add(sig(it.ref)) }
                collect(s.init, into, loopVars)
                collect(s.c, into)
                collect(s.step, into, loopVars)
                collect(s.body, into, loopVars)
            }
            is Stmt.While -> {
                s.c?.let { collect(it, into) }
                s.count?.let { collect(it, into) }
                collect(s.body, into, loopVars)
            }
        }
    }

    private fun collect(lv: LV, into: MutableSet<Sig>) {
        when (lv) {
            is LV.Cat -> lv.parts.forEach { collect(it, into) }
            is LV.Simple -> lv.sels.forEach { collect(it, into) }
        }
    }

    private fun collect(sel: Sel, into: MutableSet<Sig>) {
        collect(sel.a, into)
        sel.b?.let { collect(it, into) }
    }

    private fun collect(e: Expr, into: MutableSet<Sig>) {
        when (e) {
            is Expr.Ref -> into.add(sig(e))
            is Expr.Select -> {
                collect(e.base, into)
                collect(e.sel, into)
            }
            is Expr.Unary -> collect(e.e, into)
            is Expr.Bin -> {
                collect(e.a, into)
                collect(e.b, into)
            }
            is Expr.Cond -> {
                collect(e.c, into)
                collect(e.a, into)
                collect(e.b, into)
            }
            is Expr.Concat -> e.parts.forEach { collect(it, into) }
            is Expr.Call -> e.args.forEach { collect(it, into) }
            is Expr.Sys -> e.args.forEach { collect(it, into) }
            is Expr.Const -> {}
        }
    }

    // ---------------------------------------------------------------------------------
    // interface used by Digital
    // ---------------------------------------------------------------------------------

    private inner class Interface(private val ins: List<Sig>, private val outs: List<Sig>) : ProcessInterface {
        override fun writeValues(values: ObservableValues) {
            for (i in ins.indices) {
                val s = ins[i]
                val v = values[i].value and mask(s.width)
                if (v != s.value) apply(Upd(s, -1, 0, s.width, v))
            }
            settle()
        }

        override fun readValues(values: ObservableValues) {
            for (i in outs.indices) values[i].value = outs[i].value
        }

        override fun close() {}
    }

    companion object {
        private const val MAX_STEPS = 1000000
        private const val MAX_LOOP = 100000
        private const val MAX_CALL_DEPTH = 200
        private const val MAX_INSTANCE_DEPTH = 32
        private const val MAX_MEMORY = 1 shl 20

        private const val ANY = 0
        private const val POS = 1
        private const val NEG = 2

        private const val PART = 0
        private const val PLUS = 1
        private const val MINUS = 2

        private const val CASE = 0
        private const val CASEZ = 1
        private const val CASEX = 2

        private val ARITH = setOf("+", "-", "*", "/", "%", "&", "|", "^", "~^", "^~")
        private val SHIFTS = setOf("<<", ">>", "<<<", ">>>")

        /** marks a genvar in a scope */
        private val GENVAR = Any()

        private fun mask(w: Int) = if (w >= 64) -1L else if (w <= 0) 0L else (1L shl w) - 1

        private fun sext(v: Long, w: Int): Long = if (w >= 64) v else if (w <= 0) 0 else (v shl (64 - w)) shr (64 - w)

        private fun shl(v: Long, n: Int): Long = if (n >= 64 || n < 0) 0 else v shl n

        private fun shr(v: Long, n: Int): Long = if (n >= 64 || n < 0) 0 else v ushr n

        /* extends a value of the given width to the width w, signed if s is set */
        private fun ext(v: Long, from: Int, to: Int, s: Boolean): Long {
            val m = v and mask(from)
            return (if (s && from < to) sext(m, from) else m) and mask(to)
        }

        private fun merge(old: Long, lo: Int, w: Int, v: Long): Long {
            val m = shl(mask(w), lo)
            return (old and m.inv()) or (shl(v and mask(w), lo) and m)
        }

        /* the position of the bit with the given index */
        private fun bitPos(left: Long, right: Long, index: Long): Int {
            val p = if (left >= right) index - right else right - index
            return if (p < 0 || p > 64) -1 else p.toInt()
        }

        private fun build(module: String, code: String): Pair<VerilogInterpreter, Scope> {
            val sh = Shared(tokenize(code))
            val root = Parser(sh, "").module(module, emptyList(), null)
            sh.resolve()
            sh.engine.init(sh.procs, sh.initials)
            return sh.engine to root
        }

        /**
         * Creates the simulation of the given module.
         *
         * @param module  the name of the module
         * @param code    the Verilog code, it has to contain all modules instantiated by the module
         * @param inputs  the inputs in the order Digital uses
         * @param outputs the outputs in the order Digital uses
         * @throws IOException if the code is not supported
         */
        @JvmStatic
        @Throws(IOException::class)
        fun start(module: String, code: String, inputs: PortDefinition, outputs: PortDefinition): ProcessInterface {
            val (sim, root) = build(module, code)
            fun ports(pd: PortDefinition) = pd.map { p ->
                // escaped identifiers like \~Q are stored without the backslash
                val n = p.name.trim().removePrefix("\\")
                (root.names[n] ?: root.names.entries.firstOrNull { it.key.equals(n, true) }?.value) as? Sig
                    ?: throw IOException("port '${p.name}' not found in module '$module'")
            }
            return sim.Interface(ports(inputs), ports(outputs))
        }

        /** returns null if the given module can be simulated or the reason why not */
        @JvmStatic
        fun check(module: String, code: String): String? = try {
            build(module, code)
            null
        } catch (e: IOException) {
            e.message
        }

        // ---- tokenizer and preprocessor ---------------------------------------------

        private val SKIPPED_DIRECTIVES = setOf("timescale", "default_nettype", "resetall", "celldefine", "endcelldefine",
            "unconnected_drive", "nounconnected_drive", "pragma", "line", "begin_keywords", "end_keywords")

        /* token kinds: w word, n decimal number, q based number, s symbol, S string */
        private fun tokenize(code: String): List<Tok> {
            val l = ArrayList<Tok>()
            Lexer(code, HashMap(), l, 0).run()
            return l
        }

        private class Lexer(val code: String, val defines: HashMap<String, String>, val out: MutableList<Tok>, val level: Int) {
            private var i = 0
            private var line = 1
            private val n = code.length

            /* the conditional compilation: true if the text is used */
            private val cond = ArrayList<Boolean>()
            private val done = ArrayList<Boolean>()
            private fun active() = cond.all { it }

            private fun err(msg: String): Nothing = throw IOException("Verilog line $line: $msg")

            private fun restOfLine(): String {
                val sb = StringBuilder()
                while (i < n && code[i] != '\n') {
                    if (code[i] == '\\' && i + 1 < n && code[i + 1] == '\n') {
                        i += 2
                        line++
                        sb.append(' ')
                        continue
                    }
                    if (code[i] == '/' && i + 1 < n && code[i + 1] == '/') {
                        while (i < n && code[i] != '\n') i++
                        break
                    }
                    sb.append(code[i++])
                }
                return sb.toString().trim()
            }

            private fun word(): String {
                val s = i
                while (i < n && (code[i].isLetterOrDigit() || code[i] == '_' || code[i] == '$')) i++
                return code.substring(s, i)
            }

            private fun skipSpaces() {
                while (i < n && code[i] != '\n' && code[i].isWhitespace()) i++
            }

            fun run() {
                if (level > 20) err("the macros are nested too deep")
                while (i < n) {
                    val c = code[i]
                    when {
                        c == '\n' -> {
                            line++
                            i++
                        }
                        c.isWhitespace() -> i++
                        c == '/' && i + 1 < n && code[i + 1] == '/' -> while (i < n && code[i] != '\n') i++
                        c == '/' && i + 1 < n && code[i + 1] == '*' -> {
                            val e = code.indexOf("*/", i + 2)
                            val end = if (e < 0) n else e + 2
                            for (k in i until end) if (code[k] == '\n') line++
                            i = end
                        }
                        c == '`' -> {
                            i++
                            directive(word())
                        }
                        !active() -> i++
                        c.isLetter() || c == '_' || c == '$' -> out.add(Tok('w', word(), line))
                        c == '\\' -> {
                            // an escaped identifier
                            val s = ++i
                            while (i < n && !code[i].isWhitespace()) i++
                            out.add(Tok('w', code.substring(s, i), line))
                        }
                        c.isDigit() -> {
                            val s = i
                            while (i < n && (code[i].isDigit() || code[i] == '_')) i++
                            val text = code.substring(s, i).replace("_", "")
                            if (i + 1 < n && code[i] == '.' && code[i + 1].isDigit()) {
                                // the fraction of a real number is ignored
                                i++
                                while (i < n && code[i].isDigit()) i++
                            }
                            out.add(Tok('n', text, line))
                        }
                        c == '\'' -> {
                            // a based number like 'hFF, the size is the number before
                            var k = i + 1
                            var sgn = ""
                            if (k < n && (code[k] == 's' || code[k] == 'S')) {
                                sgn = "s"
                                k++
                            }
                            if (k >= n || code[k].lowercaseChar() !in "bodh") err("unsupported literal")
                            val base = code[k].lowercaseChar()
                            k++
                            while (k < n && (code[k] == ' ' || code[k] == '\t')) k++
                            val s = k
                            while (k < n && (code[k].isLetterOrDigit() || code[k] == '_' || code[k] == '?')) k++
                            if (s == k) err("digits expected")
                            out.add(Tok('q', sgn + base + code.substring(s, k).replace("_", "").lowercase(), line))
                            i = k
                        }
                        c == '"' -> {
                            val s = ++i
                            while (i < n && code[i] != '"') {
                                if (code[i] == '\\') i++
                                i++
                            }
                            out.add(Tok('S', code.substring(s, minOf(i, n)), line))
                            i++
                        }
                        c == '(' && i + 1 < n && code[i + 1] == '*' && !(i + 2 < n && code[i + 2] == ')') -> {
                            // an attribute (* ... *)
                            val e = code.indexOf("*)", i + 2)
                            i = if (e < 0) n else e + 2
                        }
                        else -> {
                            val three = if (i + 2 < n) code.substring(i, i + 3) else ""
                            val two = if (i + 1 < n) code.substring(i, i + 2) else ""
                            when {
                                three in SYMBOLS3 -> {
                                    out.add(Tok('s', three, line))
                                    i += 3
                                }
                                two in SYMBOLS2 -> {
                                    out.add(Tok('s', two, line))
                                    i += 2
                                }
                                else -> {
                                    out.add(Tok('s', c.toString(), line))
                                    i++
                                }
                            }
                        }
                    }
                }
                if (cond.isNotEmpty()) err("`endif missing")
            }

            private fun directive(d: String) {
                when (d) {
                    "ifdef", "ifndef" -> {
                        skipSpaces()
                        val m = word()
                        val c = (m in defines) == (d == "ifdef")
                        cond.add(c)
                        done.add(c)
                    }
                    "elsif" -> {
                        if (cond.isEmpty()) err("`elsif without `ifdef")
                        skipSpaces()
                        val m = word()
                        val c = !done.last() && m in defines
                        cond[cond.size - 1] = c
                        if (c) done[done.size - 1] = true
                    }
                    "else" -> {
                        if (cond.isEmpty()) err("`else without `ifdef")
                        cond[cond.size - 1] = !done.last()
                        done[done.size - 1] = true
                    }
                    "endif" -> {
                        if (cond.isEmpty()) err("`endif without `ifdef")
                        cond.removeAt(cond.size - 1)
                        done.removeAt(done.size - 1)
                    }
                    else -> {
                        if (!active()) return
                        when (d) {
                            "define" -> {
                                skipSpaces()
                                val m = word()
                                if (i < n && code[i] == '(') err("macros with arguments are not supported")
                                defines[m] = restOfLine()
                            }
                            "undef" -> {
                                skipSpaces()
                                defines.remove(word())
                            }
                            "include" -> err("`include is not supported")
                            in SKIPPED_DIRECTIVES -> restOfLine()
                            else -> {
                                val body = defines[d] ?: err("the macro `$d is not defined")
                                val sub = ArrayList<Tok>()
                                Lexer(body, defines, sub, level + 1).run()
                                for (t in sub) out.add(Tok(t.kind, t.text, line))
                            }
                        }
                    }
                }
            }
        }

        private val SYMBOLS3 = setOf("===", "!==", "<<<", ">>>")
        private val SYMBOLS2 = setOf("==", "!=", "<=", ">=", "&&", "||", "<<", ">>", "~&", "~|", "~^", "^~", "**",
            "+:", "-:", "++", "--", "+=", "-=", "->", "::")

        private val NET_TYPES = setOf("wire", "tri", "tri0", "tri1", "wand", "wor", "triand", "trior", "supply0", "supply1", "uwire")
        private val VAR_TYPES = setOf("reg", "logic", "integer", "bit", "byte", "time", "int", "shortint", "longint")
        private val GATES = setOf("and", "or", "nand", "nor", "xor", "xnor", "not", "buf")
    }

    // ---------------------------------------------------------------------------------
    // parser
    // ---------------------------------------------------------------------------------

    private class Tok(val kind: Char, val text: String, val line: Int)

    /** a port of a module: the signal is created when the direction is known */
    private class Port(val name: String) {
        var output = false
        var sig: Sig? = null
    }

    /** the flattened design */
    private class Shared(val toks: List<Tok>) {
        val procs = ArrayList<Proc>()
        val initials = ArrayList<Proc>()
        val refs = ArrayList<Expr.Ref>()
        val calls = ArrayList<Expr.Call>()
        val engine = VerilogInterpreter("")
        var depth = 0
        var genBlocks = 0

        /* resolves the names when all modules are parsed */
        fun resolve() {
            for (r in refs) if (r.sig == null) {
                r.sig = when (val x = r.scope.find(r.name)) {
                    is Sig -> x
                    null -> if (r.implicit) Sig(r.scope.prefix + r.name, 1, false, 0, 0).also { r.scope.names[r.name] = it }
                    else throw IOException("Verilog line ${r.line}: unknown signal '${r.name}'")
                    else -> throw IOException("Verilog line ${r.line}: '${r.name}' is not a signal")
                }
            }
            for (c in calls) c.f = c.scope.find(c.name) as? Func ?: throw IOException("Verilog line ${c.line}: unknown function '${c.name}'")
        }
    }

    /** parses a module, the signals of instances get the prefix "instance." */
    private class Parser(private val sh: Shared, prefix: String) {
        private val toks = sh.toks
        private var pos = 0
        private val root = Scope(null, prefix)
        private var scope = root
        private val ports = ArrayList<Port>()

        /** in dry mode the code is only parsed, used for the generate branches not taken */
        private var dry = false
        private var implicitRefs = false

        private fun peek(o: Int = 0) = toks.getOrNull(pos + o)
        private fun next(): Tok = toks.getOrNull(pos++) ?: throw IOException("unexpected end of the Verilog code")
        private fun isWord(w: String, o: Int = 0) = peek(o)?.let { it.kind == 'w' && it.text == w } ?: false
        private fun isSym(s: String, o: Int = 0) = peek(o)?.let { it.kind == 's' && it.text == s } ?: false
        private fun err(msg: String): Nothing = throw IOException("Verilog line ${peek()?.line ?: "end"}: $msg")
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
            if (t.kind != 'w') {
                pos--
                err("name expected but found '${t.text}'")
            }
            return t.text
        }

        private fun optLabel() {
            if (isSym(":")) {
                pos++
                ident()
            }
        }

        private fun const(e: Expr): Expr.Const {
            try {
                val w = sh.engine.width(e)
                val s = sh.engine.signed(e)
                return Expr.Const(sh.engine.ev(e, w, s), w, s)
            } catch (ex: IOException) {
                err(ex.message ?: "constant expression expected")
            }
        }

        private fun constNum(e: Expr): Long = const(e).let { if (it.signed) sext(it.v, it.width) else it.v }

        /* the last declaration of the module is used, so a file may be preceded by the files it uses */
        private fun findModule(name: String): Int {
            for (i in toks.indices.reversed()) {
                val t = toks[i]
                if (t.kind == 'w' && (t.text == "module" || t.text == "macromodule") && toks.getOrNull(i + 1)?.text == name) return i + 2
            }
            throw IOException("module '$name' not found")
        }

        /**
         * Parses the module. The parameters are overridden by the given values,
         * if [conns] is not null the ports are connected to the given expressions of the parent.
         */
        fun module(name: String, params: List<Pair<String?, Expr.Const>>, conns: List<Pair<String?, Expr?>>?): Scope {
            pos = findModule(name)
            overrides = params
            if (isSym("#")) {
                pos++
                sym("(")
                while (!isSym(")")) {
                    if (isWord("parameter") || isWord("localparam")) paramDecl(isWord("localparam").also { pos++ }, true)
                    else paramDecl(false, true)
                    if (isSym(",")) pos++
                }
                pos++
            }
            if (isSym("(")) portHeader()
            sym(";")
            while (!isWord("endmodule")) item()
            pos++
            if (conns != null) connect(conns)
            for (p in ports) if (p.sig == null) err("the direction of the port '${p.name}' of '$name' is missing")
            return root
        }

        private var overrides: List<Pair<String?, Expr.Const>> = emptyList()
        private var paramIndex = 0

        /* a parameter declaration, without the keyword */
        private fun paramDecl(local: Boolean, header: Boolean) {
            var signed = false
            var range: Pair<Long, Long>? = null
            while (true) {
                when {
                    isWord("signed") -> signed = true
                    isWord("integer") || isWord("int") -> signed = true
                    isWord("real") || isWord("unsigned") || isWord("logic") || isWord("bit") -> {}
                    else -> break
                }
                pos++
            }
            if (isSym("[")) range = rangeDecl()
            while (true) {
                val n = ident()
                sym("=")
                val def = expr()
                var v: Expr.Const = if (local) const(def)
                else overrides.firstOrNull { it.first == n }?.second
                    ?: overrides.getOrNull(paramIndex)?.takeIf { it.first == null }?.second ?: const(def)
                if (!local) paramIndex++
                if (range != null) {
                    val w = (Math.abs(range.first - range.second) + 1).toInt()
                    v = Expr.Const(ext(v.v, v.width, w, v.signed), w, signed)
                } else if (signed && !v.signed) v = Expr.Const(v.v, v.width, true)
                if (!dry || scope !== root) scope.names[n] = v
                if (header || !isSym(",")) return
                pos++
                // in the header the next parameter may have a type
                if (isWord("parameter") || isWord("localparam")) return
            }
        }

        private fun rangeDecl(): Pair<Long, Long> {
            sym("[")
            val a = constNum(expr())
            sym(":")
            val b = constNum(expr())
            sym("]")
            return a to b
        }

        private fun portHeader() {
            sym("(")
            if (isSym(")")) {
                pos++
                return
            }
            if (isWord("input") || isWord("output") || isWord("inout")) {
                // ANSI style: the declarations are in the header
                var output = false
                var signed = false
                var range: Pair<Long, Long>? = null
                var variable = false
                while (true) {
                    if (isWord("input") || isWord("output") || isWord("inout")) {
                        output = isWord("output")
                        pos++
                        signed = false
                        range = null
                        variable = false
                        while (true) {
                            when {
                                peek()?.text in NET_TYPES -> {}
                                peek()?.text in VAR_TYPES -> {
                                    variable = true
                                    if (isWord("integer") || isWord("int")) {
                                        signed = true
                                        range = 31L to 0L
                                    }
                                }
                                isWord("signed") -> signed = true
                                isWord("unsigned") || isWord("var") -> {}
                                else -> break
                            }
                            pos++
                        }
                        if (isSym("[")) range = rangeDecl()
                    }
                    val n = ident()
                    val p = Port(n)
                    p.output = output
                    p.sig = newSig(n, range, signed, null)
                    ports.add(p)
                    if (isSym("=")) {
                        pos++
                        val init = expr()
                        if (variable) initValue(p.sig!!, init)
                    }
                    if (isSym(",")) pos++ else break
                }
                sym(")")
            } else {
                while (!isSym(")")) {
                    if (isSym(".")) err("port expressions are not supported")
                    ports.add(Port(ident()))
                    if (isSym(",")) pos++
                }
                pos++
            }
        }

        private fun newSig(n: String, range: Pair<Long, Long>?, signed: Boolean, mem: Pair<Long, Long>?): Sig {
            val (l, r) = range ?: (0L to 0L)
            val w = Math.abs(l - r) + 1
            if (w > 64) err("vectors wider than 64 bits are not supported")
            val s = Sig(scope.prefix + n, w.toInt(), signed, l, r)
            if (mem != null) {
                val size = Math.abs(mem.first - mem.second) + 1
                if (size > MAX_MEMORY) err("the memory '$n' is too large")
                s.mem = LongArray(size.toInt())
                s.memLow = minOf(mem.first, mem.second)
            }
            val old = scope.names[n]
            if (old != null && (old !is Sig || ports.none { it.sig === old })) err("'$n' is declared twice")
            scope.names[n] = s
            return s
        }

        private fun initValue(s: Sig, init: Expr) {
            s.value = const(init).let { ext(it.v, it.width, s.width, it.signed) }
        }

        /* an input, output or inout declaration in the body of a module */
        private fun portDecl() {
            val output = isWord("output")
            pos++
            var signed = false
            var variable = false
            var range: Pair<Long, Long>? = null
            while (true) {
                when {
                    peek()?.text in NET_TYPES -> {}
                    peek()?.text in VAR_TYPES -> {
                        variable = true
                        if (isWord("integer")) {
                            signed = true
                            range = 31L to 0L
                        }
                    }
                    isWord("signed") -> signed = true
                    isWord("unsigned") -> {}
                    else -> break
                }
                pos++
            }
            if (isSym("[")) range = rangeDecl()
            while (true) {
                val n = ident()
                val p = ports.firstOrNull { it.name == n } ?: err("'$n' is not a port")
                p.output = output
                val existing = p.sig
                if (existing == null || range != null || signed) {
                    val r = range ?: existing?.let { it.left to it.right }
                    p.sig = newSig(n, r, signed || existing?.signed == true, null)
                }
                if (isSym("=")) {
                    pos++
                    val init = expr()
                    if (variable) initValue(p.sig!!, init)
                }
                if (isSym(",")) pos++ else break
            }
            sym(";")
        }

        /* a wire, reg, integer, ... declaration */
        private fun declaration() {
            val kw = ident()
            if (kw == "genvar") {
                while (true) {
                    scope.names[ident()] = GENVAR
                    if (isSym(",")) pos++ else break
                }
                sym(";")
                return
            }
            val net = kw in NET_TYPES
            var signed = kw == "integer" || kw == "int" || kw == "shortint" || kw == "longint" || kw == "byte"
            var range: Pair<Long, Long>? = when (kw) {
                "integer", "int" -> 31L to 0L
                "time", "longint" -> 63L to 0L
                "shortint" -> 15L to 0L
                "byte" -> 7L to 0L
                else -> null
            }
            while (true) {
                when {
                    isWord("signed") -> signed = true
                    isWord("unsigned") || isWord("reg") || isWord("logic") || isWord("wire") || isWord("var") ||
                        isWord("vectored") || isWord("scalared") -> {}
                    isSym("(") -> {
                        // a drive strength
                        while (!isSym(")")) next()
                    }
                    else -> break
                }
                pos++
            }
            if (isSym("[")) range = rangeDecl()
            if (isSym("#")) skipDelay()
            while (true) {
                val n = ident()
                var mem: Pair<Long, Long>? = null
                if (isSym("[")) {
                    mem = rangeDecl()
                    if (isSym("[")) err("multi dimensional arrays are not supported")
                }
                val s = declare(n, range, signed, mem)
                if (isSym("=")) {
                    pos++
                    val init = expr()
                    if (net) addComb(Stmt.Assign(LV.Simple(ref(s), emptyList()), init, false)) else initValue(s, init)
                }
                if (kw == "supply1") s.value = mask(s.width)
                if (isSym(",")) pos++ else break
            }
            sym(";")
        }

        /* declares a signal, a declaration of a port only changes the type of the port */
        private fun declare(n: String, range: Pair<Long, Long>?, signed: Boolean, mem: Pair<Long, Long>?): Sig {
            val p = if (scope === root) ports.firstOrNull { it.name == n } else null
            if (p != null) {
                val existing = p.sig
                if (existing != null && mem == null) {
                    if (range == null && !signed) return existing
                    val s = newSig(n, range ?: (existing.left to existing.right), signed || existing.signed, null)
                    p.sig = s
                    return s
                }
                p.sig = newSig(n, range, signed, mem)
                return p.sig!!
            }
            return newSig(n, range, signed, mem)
        }

        /* a reference to a signal which is already known */
        private fun ref(s: Sig) = Expr.Ref(scope, s.name, 0).also { it.sig = s }

        private fun addComb(s: Stmt) {
            if (!dry) sh.procs.add(Proc(s, null))
        }

        /* connects the ports of an instance to the expressions of the parent */
        private fun connect(conns: List<Pair<String?, Expr?>>) {
            if (conns.any { it.first != null } && conns.any { it.first == null }) err("mixed port connections are not supported")
            for (c in conns) if (c.first != null && ports.none { it.name == c.first }) err("the module has no port '${c.first}'")
            for ((i, p) in ports.withIndex()) {
                val c = conns.firstOrNull { it.first == p.name } ?: conns.getOrNull(i)?.takeIf { it.first == null }
                val actual = c?.second ?: continue
                val s = p.sig ?: err("the direction of the port '${p.name}' is missing")
                if (actual is Expr.Ref) {
                    val ps = actual.scope.find(actual.name) as? Sig
                    if (ps != null && ps.mem == null && ps.width == s.width && ps.signed == s.signed && ps.left == s.left && ps.right == s.right) {
                        // the port is the signal of the parent
                        root.names[p.name] = ps
                        p.sig = ps
                        continue
                    }
                }
                if (p.output) sh.procs.add(Proc(Stmt.Assign(toLV(actual), ref(s), false), null))
                else sh.procs.add(Proc(Stmt.Assign(LV.Simple(ref(s), emptyList()), actual, false), null))
            }
        }

        private fun toLV(e: Expr): LV = when (e) {
            is Expr.Ref -> LV.Simple(e, emptyList())
            is Expr.Select -> {
                val sels = ArrayList<Sel>()
                var b: Expr = e
                while (b is Expr.Select) {
                    sels.add(0, b.sel)
                    b = b.base
                }
                LV.Simple(b as? Expr.Ref ?: err("the expression cannot be assigned"), sels)
            }
            is Expr.Concat -> {
                if (e.count != null) err("the expression cannot be assigned")
                LV.Cat(e.parts.map { toLV(it) })
            }
            else -> err("the expression cannot be assigned")
        }

        // ---- module items ---------------------------------------------------------------

        private fun item() {
            val t = peek() ?: err("'endmodule' expected")
            if (t.kind == 's' && t.text == ";") {
                pos++
                return
            }
            if (t.kind != 'w') err("unexpected '${t.text}'")
            when (t.text) {
                "input", "output", "inout" -> portDecl()
                "genvar" -> declaration()
                in NET_TYPES, in VAR_TYPES -> declaration()
                "parameter", "localparam" -> {
                    pos++
                    paramDecl(t.text == "localparam", false)
                    sym(";")
                }
                "defparam" -> err("defparam is not supported")
                "assign" -> contAssign()
                "always", "always_ff", "always_comb", "always_latch" -> always()
                "initial" -> {
                    pos++
                    val body = stmt()
                    if (!dry && body != null) sh.initials.add(Proc(body, emptyList()))
                }
                "function" -> function()
                "task" -> err("tasks are not supported")
                "generate", "endgenerate" -> pos++
                "for" -> genFor()
                "if" -> genIf()
                "case" -> err("case generate is not supported")
                "begin" -> genItem()
                "specify" -> {
                    while (!isWord("endspecify")) next()
                    pos++
                }
                in GATES -> gate()
                else -> if (isSym("#", 1) || (peek(1)?.kind == 'w' && isSym("(", 2))) instance(ident())
                else err("unexpected '${t.text}'")
            }
        }

        private fun contAssign() {
            word("assign")
            if (isSym("(")) {
                // a drive strength
                while (!isSym(")")) next()
                pos++
            }
            if (isSym("#")) skipDelay()
            while (true) {
                val lv = lvalue(true)
                sym("=")
                addComb(Stmt.Assign(lv, expr(), false))
                if (isSym(",")) pos++ else break
            }
            sym(";")
        }

        private fun always() {
            val kind = ident()
            var events: List<Pair<Expr.Ref, Int>>? = null
            if (isSym("@")) {
                pos++
                if (isSym("*")) pos++
                else {
                    sym("(")
                    if (isSym("*")) {
                        pos++
                        sym(")")
                    } else {
                        val l = ArrayList<Pair<Expr.Ref, Int>>()
                        while (true) {
                            val edge = when {
                                isWord("posedge") -> POS
                                isWord("negedge") -> NEG
                                else -> ANY
                            }
                            if (edge != ANY) pos++
                            var e = expr()
                            while (e is Expr.Select) e = e.base
                            l.add((e as? Expr.Ref ?: err("signal expected")) to edge)
                            if (isWord("or") || isSym(",")) pos++ else break
                        }
                        sym(")")
                        events = l
                    }
                }
            } else if (kind == "always" || kind == "always_ff") err("always blocks without event control are not supported")
            val body = stmt() ?: return
            if (!dry) sh.procs.add(Proc(body, events))
        }

        private fun function() {
            word("function")
            if (isWord("automatic") || isWord("static")) pos++
            var signed = false
            var range: Pair<Long, Long>? = null
            while (true) {
                when {
                    isWord("signed") -> signed = true
                    isWord("integer") || isWord("int") -> {
                        signed = true
                        range = 31L to 0L
                    }
                    isWord("reg") || isWord("logic") || isWord("bit") || isWord("unsigned") -> {}
                    else -> break
                }
                pos++
            }
            if (isSym("[")) range = rangeDecl()
            val n = ident()
            val saved = scope
            val fs = Scope(scope, scope.prefix + n + ".")
            scope = fs
            val ret = newSig(n, range, signed, null)
            val inputs = ArrayList<Sig>()
            if (isSym("(")) {
                pos++
                var r: Pair<Long, Long>? = null
                var sg = false
                while (!isSym(")")) {
                    if (isWord("input")) {
                        pos++
                        r = null
                        sg = false
                        while (peek()?.text in setOf("wire", "reg", "logic", "signed", "unsigned", "integer", "int")) {
                            if (isWord("signed") || isWord("integer") || isWord("int")) sg = true
                            if (isWord("integer") || isWord("int")) r = 31L to 0L
                            pos++
                        }
                        if (isSym("[")) r = rangeDecl()
                    }
                    inputs.add(newSig(ident(), r, sg, null))
                    if (isSym(",")) pos++
                }
                pos++
            }
            sym(";")
            val body = ArrayList<Stmt>()
            while (!isWord("endfunction")) {
                when {
                    isWord("input") -> {
                        pos++
                        var sg = false
                        var r: Pair<Long, Long>? = null
                        while (peek()?.text in setOf("wire", "reg", "logic", "signed", "unsigned", "integer", "int")) {
                            if (isWord("signed") || isWord("integer") || isWord("int")) sg = true
                            if (isWord("integer") || isWord("int")) r = 31L to 0L
                            pos++
                        }
                        if (isSym("[")) r = rangeDecl()
                        while (true) {
                            inputs.add(newSig(ident(), r, sg, null))
                            if (isSym(",")) pos++ else break
                        }
                        sym(";")
                    }
                    peek()?.text in VAR_TYPES -> declaration()
                    isWord("parameter") || isWord("localparam") -> {
                        pos++
                        paramDecl(true, false)
                        sym(";")
                    }
                    else -> stmt()?.let { body.add(it) }
                }
            }
            pos++
            optLabel()
            scope = saved
            scope.names[n] = Func(n, ret, inputs, fs.names.values.filterIsInstance<Sig>(), Stmt.Block(body))
        }

        /* a primitive gate like and g1(y, a, b); */
        private fun gate() {
            val kind = ident()
            if (isSym("(") && peek(1)?.text?.let { it.endsWith("0") || it.endsWith("1") } == true && isSym(",", 2)) {
                // a drive strength
                while (!isSym(")")) next()
                pos++
            }
            if (isSym("#")) skipDelay()
            while (true) {
                if (peek()?.kind == 'w') ident()
                if (isSym("[")) err("arrays of instances are not supported")
                sym("(")
                implicitRefs = true
                val terms = ArrayList<Expr>()
                while (true) {
                    terms.add(expr())
                    if (isSym(",")) pos++ else break
                }
                implicitRefs = false
                sym(")")
                if (terms.size < 2) err("the gate '$kind' needs at least two terminals")
                if (kind == "not" || kind == "buf") {
                    val input = terms.last()
                    for (o in terms.dropLast(1)) addComb(Stmt.Assign(toLV(o), if (kind == "not") Expr.Unary("~", input) else input, false))
                } else {
                    val op = when (kind) {
                        "and", "nand" -> "&"
                        "or", "nor" -> "|"
                        else -> "^"
                    }
                    var e = terms[1]
                    for (x in terms.drop(2)) e = Expr.Bin(op, e, x)
                    if (kind == "nand" || kind == "nor" || kind == "xnor") e = Expr.Unary("~", e)
                    addComb(Stmt.Assign(toLV(terms[0]), e, false))
                }
                if (isSym(",")) pos++ else break
            }
            sym(";")
        }

        /* a module instance, the instantiated module is parsed and connected */
        private fun instance(module: String) {
            val params = ArrayList<Pair<String?, Expr.Const>>()
            if (isSym("#")) {
                pos++
                sym("(")
                while (!isSym(")")) {
                    if (isSym(".")) {
                        pos++
                        val n = ident()
                        sym("(")
                        if (!isSym(")")) params.add(n to const(expr()))
                        sym(")")
                    } else params.add(null to const(expr()))
                    if (isSym(",")) pos++
                }
                pos++
            }
            while (true) {
                val inst = ident()
                if (isSym("[")) err("arrays of instances are not supported")
                sym("(")
                val conns = ArrayList<Pair<String?, Expr?>>()
                implicitRefs = true
                while (!isSym(")")) {
                    if (isSym(".")) {
                        pos++
                        if (isSym("*")) err(".* port connections are not supported")
                        val n = ident()
                        if (isSym("(")) {
                            pos++
                            conns.add(n to if (isSym(")")) null else expr())
                            sym(")")
                        } else conns.add(n to Expr.Ref(scope, n, peek()?.line ?: 0).also { register(it) })
                    } else conns.add(null to if (isSym(",")) null else expr())
                    if (isSym(",")) pos++
                }
                implicitRefs = false
                pos++
                if (!dry) {
                    if (toks.indices.none { toks[it].kind == 'w' && toks[it].text == "module" && toks.getOrNull(it + 1)?.text == module })
                        err("the module '$module' of the instance '$inst' is not found, it has to be in the same file")
                    if (++sh.depth > MAX_INSTANCE_DEPTH) err("too many nested instances")
                    try {
                        Parser(sh, "${scope.prefix}$inst.").module(module, params, conns)
                    } catch (e: IOException) {
                        throw IOException("${e.message} (instance ${scope.prefix}$inst of $module)", e)
                    }
                    sh.depth--
                }
                if (isSym(",")) pos++ else break
            }
            sym(";")
        }

        // ---- generate -------------------------------------------------------------------

        private fun genFor() {
            word("for")
            sym("(")
            if (isWord("genvar")) pos++
            val v = ident()
            sym("=")
            var value = constNum(expr())
            sym(";")
            val condPos = pos
            dryParse {
                scope.names[v] = Expr.Const(value, 32, true)
                expr()
            }
            sym(";")
            val stepPos = pos
            dryParse {
                scope.names[v] = Expr.Const(value, 32, true)
                genStep(v, value)
            }
            sym(")")
            val bodyPos = pos
            val blockName = if (isWord("begin") && isSym(":", 1)) peek(2)!!.text else "genblk${++sh.genBlocks}"
            var endPos = -1
            var n = 0
            val saved = scope
            while (true) {
                pos = condPos
                scope = Scope(saved, saved.prefix).also { it.names[v] = Expr.Const(value, 32, true) }
                val c = constNum(expr())
                scope = saved
                if (c == 0L) break
                if (++n > MAX_LOOP) err("endless generate loop")
                pos = bodyPos
                scope = Scope(saved, "${saved.prefix}$blockName[$value].").also { it.names[v] = Expr.Const(value, 32, true) }
                genItem()
                endPos = pos
                pos = stepPos
                scope = Scope(saved, saved.prefix).also { it.names[v] = Expr.Const(value, 32, true) }
                value = genStep(v, value)
                scope = saved
            }
            if (endPos < 0) {
                pos = bodyPos
                dryParse {
                    scope.names[v] = Expr.Const(value, 32, true)
                    genItem()
                }
                endPos = pos
            }
            pos = endPos
        }

        /* the step of a generate loop: i = i + 1, i++ or i += 1 */
        private fun genStep(v: String, value: Long): Long {
            if (ident() != v) err("the loop variable '$v' expected")
            return when {
                isSym("++") || isSym("--") -> value + if (next().text == "++") 1 else -1
                isSym("+=") || isSym("-=") -> {
                    val plus = next().text == "+="
                    val d = constNum(expr())
                    if (plus) value + d else value - d
                }
                else -> {
                    sym("=")
                    constNum(expr())
                }
            }
        }

        private fun genIf() {
            word("if")
            sym("(")
            val c = constNum(expr()) != 0L
            sym(")")
            if (c) genItem() else dryParse { genItem() }
            if (isWord("else")) {
                pos++
                if (c) dryParse { genItem() } else genItem()
            }
        }

        /* a generate block or a single module item */
        private fun genItem() {
            if (!isWord("begin")) {
                item()
                return
            }
            pos++
            val saved = scope
            if (isSym(":")) {
                pos++
                val n = ident()
                // the loop already creates a scope with the name of the block
                if (!scope.prefix.endsWith("].")) scope = Scope(saved, "${saved.prefix}$n.")
            }
            while (!isWord("end")) item()
            pos++
            optLabel()
            scope = saved
        }

        private fun <T> dryParse(f: () -> T): T {
            val savedDry = dry
            val savedScope = scope
            dry = true
            scope = Scope(scope, scope.prefix)
            try {
                return f()
            } finally {
                dry = savedDry
                scope = savedScope
            }
        }

        // ---- statements -----------------------------------------------------------------

        private fun stmt(): Stmt? {
            val t = peek() ?: err("unexpected end")
            if (t.kind == 's') {
                when (t.text) {
                    ";" -> {
                        pos++
                        return null
                    }
                    "#" -> {
                        skipDelay()
                        return stmt()
                    }
                    "@" -> err("only one event control at the beginning of an always block is supported")
                    "{" -> return assignment(true)
                }
                err("unexpected '${t.text}'")
            }
            when (t.text) {
                "begin" -> return block()
                "if" -> {
                    pos++
                    sym("(")
                    val c = expr()
                    sym(")")
                    val a = stmt()
                    var b: Stmt? = null
                    if (isWord("else")) {
                        pos++
                        b = stmt()
                    }
                    return Stmt.If(c, a, b)
                }
                "unique", "unique0", "priority" -> {
                    pos++
                    return stmt()
                }
                "case", "casez", "casex" -> return caseStmt()
                "for" -> {
                    pos++
                    sym("(")
                    val saved = scope
                    if (isWord("int") || isWord("integer") || isWord("genvar")) {
                        pos++
                        scope = Scope(scope, scope.prefix)
                        newSig(peek()!!.text, 31L to 0L, true, null)
                    }
                    val init = assignment(false)
                    sym(";")
                    val c = expr()
                    sym(";")
                    val step = assignment(false)
                    sym(")")
                    val body = stmt()
                    scope = saved
                    return Stmt.For(init, c, step, body)
                }
                "while" -> {
                    pos++
                    sym("(")
                    val c = expr()
                    sym(")")
                    return Stmt.While(c, null, stmt())
                }
                "repeat" -> {
                    pos++
                    sym("(")
                    val c = expr()
                    sym(")")
                    return Stmt.While(null, c, stmt())
                }
                "forever", "wait", "fork", "disable", "force", "release", "deassign" -> err("'${t.text}' is not supported")
            }
            if (t.text.startsWith("$")) {
                // system tasks like $display are ignored
                if (t.text.startsWith("\$readmem")) err("${t.text} is not supported")
                pos++
                if (isSym("(")) {
                    var d = 0
                    do {
                        if (isSym("(")) d++
                        if (isSym(")")) d--
                        next()
                    } while (d > 0)
                }
                sym(";")
                return null
            }
            return assignment(true)
        }

        private fun block(): Stmt {
            word("begin")
            val saved = scope
            if (isSym(":")) {
                pos++
                scope = Scope(scope, scope.prefix + ident() + ".")
            }
            val l = ArrayList<Stmt>()
            while (!isWord("end")) {
                if (peek()?.text in VAR_TYPES && peek()?.kind == 'w') {
                    if (scope === saved) scope = Scope(saved, saved.prefix + "blk${++sh.genBlocks}.")
                    declaration()
                } else if (isWord("parameter") || isWord("localparam")) {
                    pos++
                    if (scope === saved) scope = Scope(saved, saved.prefix + "blk${++sh.genBlocks}.")
                    paramDecl(true, false)
                    sym(";")
                } else stmt()?.let { l.add(it) }
            }
            pos++
            optLabel()
            scope = saved
            return Stmt.Block(l)
        }

        private fun caseStmt(): Stmt {
            val kind = when (ident()) {
                "casez" -> CASEZ
                "casex" -> CASEX
                else -> CASE
            }
            sym("(")
            val sel = expr()
            sym(")")
            val items = ArrayList<Pair<List<Expr>?, Stmt?>>()
            while (!isWord("endcase")) {
                if (isWord("default")) {
                    pos++
                    if (isSym(":")) pos++
                    items.add(null to stmt())
                } else {
                    val l = arrayListOf(expr())
                    while (isSym(",")) {
                        pos++
                        l.add(expr())
                    }
                    sym(":")
                    items.add(l to stmt())
                }
            }
            pos++
            return Stmt.Case(sel, kind, items)
        }

        /* a blocking or non-blocking assignment, also i++ and i += 1 */
        private fun assignment(semicolon: Boolean): Stmt {
            val lv = lvalue(false)
            val st: Stmt = when {
                isSym("=") || isSym("<=") -> {
                    val nb = next().text == "<="
                    if (isSym("#")) skipDelay()
                    Stmt.Assign(lv, expr(), nb)
                }
                isSym("++") || isSym("--") || isSym("+=") || isSym("-=") -> {
                    val op = next().text
                    val r = lvExpr(lv)
                    val d = if (op.length == 2 && op[1] == '=') expr() else Expr.Const(1, 32, true)
                    Stmt.Assign(lv, Expr.Bin(op.substring(0, 1), r, d), false)
                }
                else -> err("'=' or '<=' expected but found '${peek()?.text}'")
            }
            if (semicolon) sym(";")
            return st
        }

        private fun lvExpr(lv: LV): Expr = when (lv) {
            is LV.Simple -> lv.sels.fold(Expr.Ref(lv.ref.scope, lv.ref.name, lv.ref.line).also { register(it) } as Expr) { b, s -> Expr.Select(b, s) }
            is LV.Cat -> Expr.Concat(lv.parts.map { lvExpr(it) }, null)
        }

        private fun lvalue(implicit: Boolean): LV {
            if (isSym("{")) {
                pos++
                val parts = arrayListOf(lvalue(implicit))
                while (isSym(",")) {
                    pos++
                    parts.add(lvalue(implicit))
                }
                sym("}")
                return LV.Cat(parts)
            }
            val line = peek()?.line ?: 0
            val n = ident()
            if (isSym(".")) err("hierarchical names are not supported")
            val r = Expr.Ref(scope, n, line)
            r.implicit = implicit
            register(r)
            val sels = ArrayList<Sel>()
            while (isSym("[")) sels.add(select())
            return LV.Simple(r, sels)
        }

        private fun register(r: Expr.Ref) {
            if (!dry) sh.refs.add(r)
        }

        private fun select(): Sel {
            sym("[")
            val a = expr()
            val s = when {
                isSym(":") -> {
                    pos++
                    Sel(a, expr(), PART)
                }
                isSym("+:") -> {
                    pos++
                    Sel(a, expr(), PLUS)
                }
                isSym("-:") -> {
                    pos++
                    Sel(a, expr(), MINUS)
                }
                else -> Sel(a, null, PART)
            }
            sym("]")
            return s
        }

        private fun skipDelay() {
            sym("#")
            if (isSym("(")) {
                var d = 0
                do {
                    if (isSym("(")) d++
                    if (isSym(")")) d--
                    next()
                } while (d > 0)
            } else {
                next()
                // a time unit like 10ns
                if (peek()?.text in setOf("s", "ms", "us", "ns", "ps", "fs")) pos++
            }
        }

        // ---- expressions ----------------------------------------------------------------

        private val levels = listOf(
            setOf("||"), setOf("&&"), setOf("|"), setOf("^", "^~", "~^"), setOf("&"),
            setOf("==", "!=", "===", "!=="), setOf("<", "<=", ">", ">="), SHIFTS, setOf("+", "-"), setOf("*", "/", "%"), setOf("**"))

        fun expr(): Expr {
            val c = binary(0)
            if (!isSym("?")) return c
            pos++
            val a = expr()
            sym(":")
            return Expr.Cond(c, a, expr())
        }

        private fun binary(level: Int): Expr {
            if (level == levels.size) return unary()
            var e = binary(level + 1)
            while (peek()?.let { it.kind == 's' && it.text in levels[level] } == true) {
                val op = next().text
                e = Expr.Bin(op, e, binary(level + 1))
            }
            return e
        }

        private fun unary(): Expr {
            val t = peek()
            if (t != null && t.kind == 's' && t.text in setOf("+", "-", "!", "~", "&", "~&", "|", "~|", "^", "~^", "^~")) {
                pos++
                return Expr.Unary(t.text, unary())
            }
            return primary()
        }

        private fun primary(): Expr {
            val t = next()
            return when (t.kind) {
                'n' -> {
                    if (peek()?.kind == 'q') based(next().text, t.text.toIntOrNull() ?: err("illegal size"))
                    else {
                        val v = t.text.toLongOrNull() ?: err("number too large: ${t.text}")
                        Expr.Const(v, maxOf(32, 65 - java.lang.Long.numberOfLeadingZeros(v)).coerceAtMost(64), true)
                    }
                }
                'q' -> based(t.text, -1)
                'S' -> Expr.Const(0, 8, false)
                's' -> when (t.text) {
                    "(" -> {
                        val e = expr()
                        sym(")")
                        e
                    }
                    "{" -> concat()
                    else -> {
                        pos--
                        err("unexpected '${t.text}'")
                    }
                }
                'w' -> name(t)
                else -> err("unexpected '${t.text}'")
            }
        }

        private fun name(t: Tok): Expr {
            val n = t.text
            if (n.startsWith("$")) {
                val args = ArrayList<Expr>()
                if (isSym("(")) {
                    pos++
                    while (!isSym(")")) {
                        args.add(expr())
                        if (isSym(",")) pos++
                    }
                    pos++
                }
                return when (n) {
                    "\$signed", "\$unsigned" -> {
                        if (args.size != 1) err("$n needs one argument")
                        Expr.Sys(n, args)
                    }
                    "\$clog2" -> {
                        if (args.size != 1) err("$n needs one argument")
                        const(Expr.Sys(n, args))
                    }
                    else -> err("the system function $n is not supported")
                }
            }
            val x = scope.find(n)
            if (x === GENVAR) err("the genvar '$n' is used outside of a generate loop")
            if (x is Expr.Const) return postfix(x)
            if (isSym("(")) {
                pos++
                val args = ArrayList<Expr>()
                while (!isSym(")")) {
                    args.add(expr())
                    if (isSym(",")) pos++
                }
                pos++
                val c = Expr.Call(scope, n, args, t.line)
                if (!dry) sh.calls.add(c)
                return c
            }
            if (isSym(".")) err("hierarchical names are not supported")
            val r = Expr.Ref(scope, n, t.line)
            r.implicit = implicitRefs
            register(r)
            return postfix(r)
        }

        private fun postfix(start: Expr): Expr {
            var e = start
            while (isSym("[")) e = Expr.Select(e, select())
            return e
        }

        private fun concat(): Expr {
            val parts = arrayListOf(concatElement())
            while (isSym(",")) {
                pos++
                parts.add(concatElement())
            }
            sym("}")
            return if (parts.size == 1 && parts[0] is Expr.Concat) parts[0] else Expr.Concat(parts, null)
        }

        /* an element of a concatenation, also a replication like 4{a} */
        private fun concatElement(): Expr {
            val first = expr()
            if (!isSym("{")) return first
            pos++
            val parts = arrayListOf(expr())
            while (isSym(",")) {
                pos++
                parts.add(expr())
            }
            sym("}")
            return Expr.Concat(parts, const(first))
        }

        /* a based number like b1010 or shff, the size is -1 if unsized */
        private fun based(text: String, size: Int): Expr.Const {
            val signed = text.startsWith("s")
            val t = if (signed) text.substring(1) else text
            val base = t[0]
            val digits = t.substring(1)
            var v = 0L
            var dcz = 0L
            var dcx = 0L
            var bits: Int
            if (base == 'd') {
                when {
                    digits.all { it == 'x' } -> dcx = -1L
                    digits.all { it == 'z' || it == '?' } -> dcz = -1L
                    else -> v = java.lang.Long.parseUnsignedLong(digits)
                }
                bits = 64 - java.lang.Long.numberOfLeadingZeros(v)
            } else {
                val bpd = when (base) {
                    'b' -> 1
                    'o' -> 3
                    else -> 4
                }
                bits = digits.length * bpd
                if (bits > 64 && size in 1..64) {
                    // more digits than needed: only the low bits are used
                    bits = 64
                }
                for (c in digits) {
                    v = shl(v, bpd)
                    dcz = shl(dcz, bpd)
                    dcx = shl(dcx, bpd)
                    when (c) {
                        'x' -> dcx = dcx or mask(bpd)
                        'z', '?' -> dcz = dcz or mask(bpd)
                        else -> {
                            val d = Character.digit(c, 1 shl bpd)
                            if (d < 0) err("illegal digit '$c'")
                            v = v or d.toLong()
                        }
                    }
                }
                // a leading x or z is extended
                val first = digits.firstOrNull()
                if (size > bits && (first == 'x' || first == 'z' || first == '?')) {
                    val ext = mask(size) and mask(bits).inv()
                    if (first == 'x') dcx = dcx or ext else dcz = dcz or ext
                }
            }
            val w = if (size > 0) size else maxOf(32, bits)
            if (w > 64) err("vectors wider than 64 bits are not supported")
            return Expr.Const(v and mask(w), w, signed, dcz and mask(w), dcx and mask(w))
        }
    }
}
