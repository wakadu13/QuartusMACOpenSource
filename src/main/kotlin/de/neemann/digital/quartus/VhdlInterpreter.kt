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
 * from block diagrams:
 *  - std_logic, std_logic_vector, unsigned, signed, bit, boolean, integer, natural and positive objects,
 *    user defined enumeration types (state machines), integer range types, subtypes and one dimensional
 *    arrays of scalars or vectors (ROM tables, register files)
 *  - generics with default values or values given in a generic map, constants, aliases,
 *    packages (constants, types and functions) declared in the same code
 *  - concurrent, conditional (WHEN ELSE) and selected (WITH SELECT) signal assignments
 *  - processes with a sensitivity list, process(all), a single WAIT UNTIL at the beginning
 *    or a WAIT ON / WAIT at the end
 *  - IF, CASE (also with ranges), FOR, WHILE and plain loops with EXIT and NEXT, variables, functions
 *  - component and entity instances (the instantiated entity must be in the same code, the design is flattened),
 *    FOR GENERATE and IF GENERATE
 *  - rising_edge/falling_edge/'EVENT, the attributes 'LENGTH, 'RANGE, 'REVERSE_RANGE, 'HIGH, 'LOW, 'LEFT, 'RIGHT,
 *    'POS and 'VAL, aggregates like (others =&gt; '0') or (0 =&gt; '1', others =&gt; '0'), concatenation,
 *    logical, relational, shift and arithmetic operators
 *  - the functions of numeric_std, std_logic_arith, std_logic_unsigned and std_logic_misc like to_unsigned,
 *    to_signed, to_integer, conv_integer, conv_std_logic_vector, resize, shift_left, shift_right, rotate_left,
 *    rotate_right, ext, sxt and or_reduce
 *
 * Not supported are records, multi dimensional arrays, procedures, vectors wider than 64 bits
 * and processes with several WAIT statements.
 * The simulation uses delta cycles like a VHDL simulator. Undefined values ('U', 'X', 'Z', '-') are treated as '0'.
 */
class VhdlInterpreter private constructor(
    private val name: String,
    private val signals: Map<String, Sig>,
    private val processes: List<Process>,
    private val signedPkg: Boolean
) {

    // ---------------------------------------------------------------------------------
    // values, types and the design
    // ---------------------------------------------------------------------------------

    /** a value: the bits, the width, the numeric interpretation and the index range of a vector */
    private class V(val v: Long, val w: Int, val kind: Int = BITS, val low: Long = 0, val asc: Boolean = false) {
        val bool: Boolean get() = (v and 1L) != 0L

        /** the numeric value */
        val num: Long
            get() = when (kind) {
                INT -> v
                SIGNED -> sext(v, w)
                else -> v and mask(w)
            }
    }

    /**
     * A type. Scalars (std_logic, boolean, enumerations, integers) and vectors are stored in a long,
     * arrays in a long array.
     */
    private class Type(
        /** BITS, SIGNED or INT */
        val kind: Int,
        /** the bits of a scalar or vector, the number of elements of an array, -1 if unconstrained */
        val width: Int,
        val low: Long = 0,
        val asc: Boolean = false,
        val scalar: Boolean = false,
        val imin: Long = 0,
        val imax: Long = 0,
        /** the type of the elements of an array */
        val elem: Type? = null,
        val enumSize: Int = 0
    ) {
        val isArray: Boolean get() = elem != null

        fun constrain(a: Long, b: Long, ascending: Boolean): Type =
            if (kind == INT && scalar) intType(minOf(a, b), maxOf(a, b))
            else Type(kind, (Math.abs(a - b) + 1).toInt(), minOf(a, b), ascending, scalar, imin, imax, elem, enumSize)
    }

    /** a type mark with an optional range constraint which is evaluated when the object is created */
    private class TypeSpec(val base: Type, val rng: Rng?)

    private class Sig(val name: String, val type: Type) {
        var value = 0L
        var arr: LongArray? = if (type.isArray) LongArray(maxOf(0, type.width)) else null
    }

    private sealed class Expr {
        class Const(val v: V) : Expr()
        class Ref(val name: String) : Expr()

        /** a bit of a vector or an element of an array */
        class Index(val base: Expr, val index: Expr) : Expr()
        class Slice(val base: Expr, val r: Rng) : Expr()
        class Attr(val base: Expr, val attr: String) : Expr()
        class Unary(val op: String, val e: Expr) : Expr()
        class Bin(val op: String, val a: Expr, val b: Expr) : Expr()

        /** a built-in function */
        class Call(val name: String, val args: List<Expr>) : Expr()

        /** a user defined function */
        class User(val f: Func, val args: List<Expr>) : Expr()

        /** a type conversion or a qualified expression */
        class Convert(val t: Type, val e: Expr) : Expr()

        /** the items: the choices (null if positional) and the value */
        class Aggregate(val items: List<Pair<List<Choice>?, Expr>>) : Expr()

        /** a type used as range or as prefix of an attribute */
        class TypeE(val t: Type) : Expr()
    }

    /** a range, the direction is an expression because of the attribute 'RANGE */
    private class Rng(val left: Expr, val right: Expr, val asc: Expr)

    /** a choice: a value, a range or OTHERS if both are null */
    private class Choice(val e: Expr?, val r: Rng?)

    /** the target of an assignment: the name and the indices (Expr) or slices (Rng) */
    private class Target(val name: String, val suffixes: List<Any>)

    private sealed class Stmt {
        class Assign(val target: Target, val value: Expr, val variable: Boolean) : Stmt()
        class If(val conds: List<Expr>, val bodies: List<List<Stmt>>, val otherwise: List<Stmt>?) : Stmt()
        class Case(val sel: Expr, val choices: List<Pair<List<Choice>, List<Stmt>>>) : Stmt()
        class For(val v: String, val r: Rng, val body: List<Stmt>, val label: String?) : Stmt()
        class While(val cond: Expr?, val body: List<Stmt>, val label: String?) : Stmt()
        class Exit(val next: Boolean, val label: String?, val cond: Expr?) : Stmt()
        class Return(val e: Expr?, val type: TypeSpec?) : Stmt()
    }

    private class Param(val name: String, val local: String, val type: TypeSpec, val default: Expr?)

    private class Func(val name: String, val procedure: Boolean) {
        val params = ArrayList<Param>()
        var ret: TypeSpec? = null
        val vars = ArrayList<Triple<String, TypeSpec, Expr?>>()
        val loopVars = ArrayList<String>()
        var body: List<Stmt>? = null
    }

    /**
     * A process. Concurrent statements are processes sensitive to the signals they read.
     * Processes without a WAIT UNTIL are executed once at the beginning; if [once] is set only then.
     */
    private class Process(
        val sensitivity: Set<String>,
        val waitUntil: Expr?,
        val body: List<Stmt>,
        val locals: HashMap<String, Sig>,
        val once: Boolean
    )

    // ---------------------------------------------------------------------------------
    // simulation
    // ---------------------------------------------------------------------------------

    private val pending = LinkedHashMap<Sig, Long>()
    private val pendingArr = LinkedHashMap<Sig, LongArray>()
    private var events: Set<Sig> = emptySet()
    private var locals: Map<String, Sig> = emptyMap()
    private var retVal: V? = null
    private var ctlLabel: String? = null
    private var depth = 0
    private val listeners = HashMap<String, ArrayList<Process>>()

    init {
        for (p in processes) for (s in p.sensitivity) listeners.getOrPut(s) { ArrayList() }.add(p)
        // every process without a WAIT UNTIL is executed once at the beginning
        for (p in processes) if (p.waitUntil == null) run(p)
        settle()
    }

    private fun sig(name: String): Sig = locals[name] ?: signals[name] ?: throw IOException("'${display(name)}' is not a constant")

    private fun run(p: Process) {
        locals = p.locals
        exec(p.body)
        locals = emptyMap()
    }

    /* the delta cycles: the pending assignments are applied until nothing changes */
    private fun settle() {
        var n = 0
        while (pending.isNotEmpty() || pendingArr.isNotEmpty()) {
            if (++n > MAX_DELTAS) throw IOException("$name: the VHDL simulation does not settle (combinational loop?)")
            val changed = LinkedHashSet<Sig>()
            for ((s, v) in pending) if (s.value != v) {
                s.value = v
                changed.add(s)
            }
            for ((s, a) in pendingArr) if (!a.contentEquals(s.arr)) {
                s.arr = a
                changed.add(s)
            }
            pending.clear()
            pendingArr.clear()
            if (changed.isEmpty()) break
            events = changed
            val triggered = LinkedHashSet<Process>()
            for (s in changed) listeners[s.name]?.let { triggered.addAll(it) }
            // all signal assignments are delayed, so the order of the processes does not matter
            for (p in triggered) {
                if (p.once) continue
                if (p.waitUntil != null) {
                    locals = p.locals
                    val go = eval(p.waitUntil).bool
                    locals = emptyMap()
                    if (!go) continue
                }
                run(p)
            }
            events = emptySet()
        }
    }

    /* executes the statements, returns NORMAL, EXIT, NEXT or RETURN */
    private fun exec(stmts: List<Stmt>): Int {
        for (s in stmts) {
            val c = exec(s)
            if (c != NORMAL) return c
        }
        return NORMAL
    }

    private fun exec(s: Stmt): Int {
        when (s) {
            is Stmt.Assign -> assign(s)
            is Stmt.If -> {
                for (i in s.conds.indices) if (eval(s.conds[i]).bool) return exec(s.bodies[i])
                s.otherwise?.let { return exec(it) }
            }
            is Stmt.Case -> {
                val v = eval(s.sel)
                for ((choices, body) in s.choices) if (choices.any { matches(it, v) }) return exec(body)
            }
            is Stmt.For -> {
                val a = eval(s.r.left).num
                val b = eval(s.r.right).num
                val asc = eval(s.r.asc).bool
                val lv = sig(s.v)
                var i = a
                while (if (asc) i <= b else i >= b) {
                    lv.value = i
                    val c = exec(s.body)
                    if (c == RETURN) return c
                    if (c != NORMAL) {
                        if (ctlLabel != null && ctlLabel != s.label) return c
                        ctlLabel = null
                        if (c == EXIT) break
                    }
                    i += if (asc) 1 else -1
                }
            }
            is Stmt.While -> {
                var n = 0
                while (s.cond == null || eval(s.cond).bool) {
                    if (++n > MAX_LOOP) throw IOException("$name: endless loop")
                    val c = exec(s.body)
                    if (c == RETURN) return c
                    if (c != NORMAL) {
                        if (ctlLabel != null && ctlLabel != s.label) return c
                        ctlLabel = null
                        if (c == EXIT) break
                    }
                }
            }
            is Stmt.Exit -> if (s.cond == null || eval(s.cond).bool) {
                ctlLabel = s.label
                return if (s.next) NEXT else EXIT
            }
            is Stmt.Return -> {
                retVal = s.e?.let { e ->
                    val t = s.type?.let { resolve(it) }
                    if (t == null) eval(e) else cast(evalTyped(e, t), t)
                }
                return RETURN
            }
        }
        return NORMAL
    }

    private fun matches(c: Choice, v: V): Boolean = when {
        c.r != null -> {
            val a = eval(c.r.left).num
            val b = eval(c.r.right).num
            v.num in minOf(a, b)..maxOf(a, b)
        }
        c.e != null -> cmp(evalTyped(c.e, typeOf(v)), v) == 0
        else -> true
    }

    private fun assign(a: Stmt.Assign) {
        val s = sig(a.target.name)
        val suf = a.target.suffixes
        val arr = s.arr
        if (arr != null) {
            val t = s.type
            val nv: LongArray
            if (suf.isEmpty()) nv = evalArray(a.value, t)
            else {
                val cur = if (a.variable) arr else pendingArr[s] ?: arr
                val idx = suf[0] as? Expr ?: throw IOException("slices of arrays are not supported")
                val p = elemPos(t, eval(idx).num)
                if (p !in cur.indices) return
                nv = cur.copyOf()
                nv[p] = update(t.elem!!, cur[p], suf, 1, a.value)
            }
            if (a.variable) s.arr = nv else pendingArr[s] = nv
        } else {
            val old = if (a.variable) s.value else pending[s] ?: s.value
            val nv = update(s.type, old, suf, 0, a.value)
            if (a.variable) s.value = nv else pending[s] = nv
        }
    }

    /* the new value of a scalar or vector if the part given by the suffixes starting at i is assigned */
    private fun update(t: Type, old: Long, suf: List<Any>, i: Int, value: Expr): Long {
        if (i == suf.size) return store(t, evalTyped(value, t))
        if (i != suf.size - 1) throw IOException("the target of the assignment is not supported")
        return when (val x = suf[i]) {
            is Expr -> {
                val p = bitPos(t.width, t.low, t.asc, eval(x).num)
                if (p !in 0 until t.width) return old
                val b = eval(value).v and 1
                (old and (1L shl p).inv()) or (b shl p)
            }
            else -> {
                val r = x as Rng
                val l = eval(r.left).num
                val rr = eval(r.right).num
                val p1 = bitPos(t.width, t.low, t.asc, l)
                val p2 = bitPos(t.width, t.low, t.asc, rr)
                val lo = minOf(p1, p2)
                val w = maxOf(p1, p2) - lo + 1
                if (lo < 0 || lo + w > t.width) return old
                val m = mask(w)
                val v = evalTyped(value, Type(t.kind, w, minOf(l, rr), eval(r.asc).bool)).v and m
                (old and shl(m, lo).inv()) or shl(v, lo)
            }
        }
    }

    /* the value stored for the given type */
    private fun store(t: Type, x: V): Long = if (t.kind == INT) norm(t, x.num) else x.v and mask(t.width)

    /* converts the value to the given type */
    private fun cast(x: V, t: Type): V = when {
        t.kind == INT -> V(norm(t, x.num), t.width, INT)
        t.width < 0 || t.scalar -> V(x.v and mask(x.w), x.w, t.kind, x.low, x.asc)
        else -> V(x.v and mask(t.width), t.width, t.kind, t.low, t.asc)
    }

    private fun typeOf(x: V): Type = Type(x.kind, x.w, x.low, x.asc, x.kind == INT, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())

    private fun evalTyped(e: Expr, t: Type): V = if (e is Expr.Aggregate) aggregate(e, t) else eval(e)

    private fun eval(e: Expr): V = when (e) {
        is Expr.Const -> e.v
        is Expr.Ref -> sig(e.name).let { s ->
            if (s.arr != null) throw IOException("the array '${display(s.name)}' cannot be used as a value")
            V(s.value, s.type.width, s.type.kind, s.type.low, s.type.asc)
        }
        is Expr.Index -> index(e)
        is Expr.Slice -> eval(e.base).let { x ->
            val l = eval(e.r.left).num
            val r = eval(e.r.right).num
            val p1 = bitPos(x.w, x.low, x.asc, l)
            val p2 = bitPos(x.w, x.low, x.asc, r)
            val lo = minOf(p1, p2)
            val w = maxOf(p1, p2) - lo + 1
            V(shr(x.v, lo) and mask(w), w, if (x.kind == INT) BITS else x.kind, minOf(l, r), eval(e.r.asc).bool)
        }
        is Expr.Attr -> attr(e)
        is Expr.Unary -> unary(e.op, eval(e.e))
        is Expr.Bin -> {
            if (e.a is Expr.Aggregate) {
                val b = eval(e.b)
                binary(e.op, aggregate(e.a, typeOf(b)), b)
            } else {
                val a = eval(e.a)
                binary(e.op, a, evalTyped(e.b, typeOf(a)))
            }
        }
        is Expr.Call -> call(e)
        is Expr.User -> callUser(e.f, e.args)
        is Expr.Convert -> if (e.e is Expr.Aggregate) aggregate(e.e, e.t) else cast(eval(e.e), e.t)
        is Expr.Aggregate -> throw IOException("the type of the aggregate is unknown")
        is Expr.TypeE -> throw IOException("a type is not a value")
    }

    private fun index(e: Expr.Index): V {
        val b = e.base
        if (b is Expr.Ref) {
            val s = sig(b.name)
            val arr = s.arr
            if (arr != null) {
                val t = s.type
                val et = t.elem!!
                val p = elemPos(t, eval(e.index).num)
                return V(if (p in arr.indices) arr[p] else 0, et.width, et.kind, et.low, et.asc)
            }
        }
        val x = eval(b)
        val p = bitPos(x.w, x.low, x.asc, eval(e.index).num)
        return V(if (p in 0 until x.w) (x.v ushr p) and 1 else 0, 1)
    }

    private fun attr(e: Expr.Attr): V {
        val b = e.base
        if (e.attr == "event") return V(if (b is Expr.Ref && sig(b.name) in events) 1 else 0, 1)
        var w: Long
        var low: Long
        var asc: Boolean
        val t = when (b) {
            is Expr.TypeE -> b.t
            is Expr.Ref -> sig(b.name).type.takeIf { it.isArray }
            else -> null
        }
        if (t != null) {
            when {
                t.enumSize > 0 -> {
                    low = 0
                    w = t.enumSize.toLong()
                    asc = true
                }
                t.kind == INT && t.scalar -> {
                    low = t.imin
                    w = t.imax - t.imin + 1
                    asc = true
                }
                else -> {
                    low = t.low
                    w = t.width.toLong()
                    asc = t.asc
                }
            }
        } else {
            val x = eval(b)
            low = x.low
            w = x.w.toLong()
            asc = x.asc
        }
        val high = low + w - 1
        return when (e.attr) {
            "length" -> V(w, 32, INT)
            "low" -> V(low, 32, INT)
            "high" -> V(high, 32, INT)
            "left" -> V(if (asc) low else high, 32, INT)
            "right" -> V(if (asc) high else low, 32, INT)
            "ascending" -> V(if (asc) 1 else 0, 1)
            else -> throw IOException("the attribute '${e.attr}' cannot be used here")
        }
    }

    private fun aggregate(a: Expr.Aggregate, t: Type): V {
        if (t.isArray) throw IOException("an array aggregate cannot be used here")
        val positional = a.items.count { it.first == null }
        val w = if (t.width >= 0) t.width else positional
        val tt = if (t.width >= 0) t else Type(t.kind, w, 0, false)
        var r = 0L
        // OTHERS first, then the positional and named elements
        for ((choices, e) in a.items)
            if (choices != null && choices.any { it.e == null && it.r == null } && eval(e).bool) r = mask(w)
        var k = 0
        for ((choices, e) in a.items) {
            if (choices == null) {
                val p = w - 1 - k++
                if (p in 0 until w) r = setBit(r, p, eval(e).bool)
            } else for (c in choices) {
                if (c.e != null) {
                    val p = bitPos(w, tt.low, tt.asc, eval(c.e).num)
                    if (p in 0 until w) r = setBit(r, p, eval(e).bool)
                } else if (c.r != null) {
                    val x = eval(c.r.left).num
                    val y = eval(c.r.right).num
                    for (i in minOf(x, y)..maxOf(x, y)) {
                        val p = bitPos(w, tt.low, tt.asc, i)
                        if (p in 0 until w) r = setBit(r, p, eval(e).bool)
                    }
                }
            }
        }
        return V(r, w, if (t.kind == INT) BITS else t.kind, tt.low, tt.asc)
    }

    private fun evalArray(e: Expr, t: Type): LongArray {
        val et = t.elem!!
        val r = LongArray(maxOf(0, t.width)) { defaultValue(et) }
        when (e) {
            is Expr.Aggregate -> {
                for ((choices, x) in e.items)
                    if (choices != null && choices.any { it.e == null && it.r == null }) r.fill(store(et, evalTyped(x, et)))
                var k = 0
                for ((choices, x) in e.items) {
                    if (choices == null) {
                        if (k < r.size) r[k] = store(et, evalTyped(x, et))
                        k++
                    } else for (c in choices) {
                        if (c.e != null) {
                            val p = elemPos(t, eval(c.e).num)
                            if (p in r.indices) r[p] = store(et, evalTyped(x, et))
                        } else if (c.r != null) {
                            val a = eval(c.r.left).num
                            val b = eval(c.r.right).num
                            for (i in minOf(a, b)..maxOf(a, b)) {
                                val p = elemPos(t, i)
                                if (p in r.indices) r[p] = store(et, evalTyped(x, et))
                            }
                        }
                    }
                }
            }
            is Expr.Ref -> sig(e.name).arr?.let { src -> src.copyInto(r, 0, 0, minOf(src.size, r.size)) }
                ?: throw IOException("array value expected")
            else -> throw IOException("array value expected")
        }
        return r
    }

    private fun initSig(s: Sig, init: Expr?) {
        val t = s.type
        if (s.arr != null) s.arr = if (init == null) LongArray(maxOf(0, t.width)) { defaultValue(t.elem!!) } else evalArray(init, t)
        else s.value = if (init == null) defaultValue(t) else store(t, evalTyped(init, t))
    }

    private fun resolve(ts: TypeSpec): Type {
        val r = ts.rng ?: return ts.base
        val t = ts.base.constrain(eval(r.left).num, eval(r.right).num, eval(r.asc).bool)
        if (!t.isArray && !(t.kind == INT && t.scalar) && t.width > 64) throw IOException("vectors wider than 64 bits are not supported")
        return t
    }

    private fun call(e: Expr.Call): V {
        val a = e.args
        fun arg(i: Int) = eval(a.getOrNull(i) ?: throw IOException("${e.name}: argument missing"))
        return when (e.name) {
            "rising_edge", "falling_edge" -> {
                val r = a.getOrNull(0) as? Expr.Ref ?: throw IOException("${e.name} needs a signal")
                val s = sig(r.name)
                val level = if (e.name == "rising_edge") 1L else 0L
                V(if (s in events && (s.value and 1) == level) 1 else 0, 1)
            }
            "to_unsigned", "conv_unsigned", "conv_std_logic_vector" -> {
                val w = arg(1).num.toInt()
                V(arg(0).num and mask(w), w)
            }
            "to_signed", "conv_signed" -> {
                val w = arg(1).num.toInt()
                V(arg(0).num and mask(w), w, SIGNED)
            }
            "to_integer" -> V(arg(0).num, 32, INT)
            "conv_integer" -> arg(0).let { x -> V(if (x.kind == BITS && signedPkg) sext(x.v, x.w) else x.num, 32, INT) }
            "resize" -> resize(arg(0), arg(1).num.toInt())
            "ext" -> {
                val x = arg(0)
                val w = arg(1).num.toInt()
                V(x.v and mask(x.w) and mask(w), w, if (x.kind == INT) BITS else x.kind)
            }
            "sxt" -> {
                val x = arg(0)
                val w = arg(1).num.toInt()
                V(sext(x.v, x.w) and mask(w), w, if (x.kind == INT) BITS else x.kind)
            }
            "shift_left", "shl" -> arg(0).let { x -> V(shl(x.v, arg(1).num.toInt()) and mask(x.w), x.w, x.kind, x.low, x.asc) }
            "shift_right", "shr" -> arg(0).let { x ->
                val n = arg(1).num.toInt()
                val v = if (x.kind == SIGNED) sext(x.v, x.w) shr minOf(n, 63) else shr(x.v and mask(x.w), n)
                V(v and mask(x.w), x.w, x.kind, x.low, x.asc)
            }
            "rotate_left", "rotate_right" -> arg(0).let { x ->
                val w = maxOf(1, x.w)
                var n = Math.floorMod(arg(1).num, w.toLong()).toInt()
                if (e.name == "rotate_right") n = (w - n) % w
                val v = x.v and mask(w)
                V((shl(v, n) or shr(v, w - n)) and mask(w), x.w, x.kind, x.low, x.asc)
            }
            "and_reduce", "or_reduce", "xor_reduce", "nand_reduce", "nor_reduce", "xnor_reduce" ->
                unary(e.name.removeSuffix("_reduce") + "_r", arg(0))
            "minimum", "maximum" -> {
                val x = arg(0)
                val y = arg(1)
                if ((cmp(x, y) < 0) == (e.name == "minimum")) x else y
            }
            // type conversions which do not change the bits
            else -> arg(0)
        }
    }

    private fun resize(x: V, n: Int): V = when {
        x.kind == SIGNED && n < x.w && n > 0 -> {
            val s = sext(x.v, x.w)
            V((if (s < 0) 1L shl (n - 1) else 0L) or (s and mask(n - 1)), n, SIGNED)
        }
        x.kind == SIGNED -> V(sext(x.v, x.w) and mask(n), n, SIGNED)
        else -> V(x.v and mask(minOf(x.w, 64)) and mask(n), n, if (x.kind == INT) BITS else x.kind)
    }

    private fun callUser(f: Func, args: List<Expr>): V {
        val body = f.body ?: throw IOException("the function '${f.name}' has no body")
        if (depth > MAX_CALL_DEPTH) throw IOException("${f.name}: the recursion is too deep")
        val values = args.map { eval(it) }
        val saved = locals
        val loc = HashMap<String, Sig>()
        depth++
        try {
            locals = loc
            for ((i, p) in f.params.withIndex()) {
                val x = values.getOrNull(i) ?: p.default?.let { eval(it) }
                ?: throw IOException("${f.name}: the argument '${p.name}' is missing")
                var t = resolve(p.type)
                if (t.isArray) throw IOException("${f.name}: array parameters are not supported")
                if (t.width < 0) t = Type(t.kind, x.w, x.low, x.asc)
                val s = Sig(p.local, t)
                s.value = store(t, x)
                loc[p.local] = s
            }
            for ((n, ts, init) in f.vars) {
                val s = Sig(n, resolve(ts))
                initSig(s, init)
                loc[n] = s
            }
            for (n in f.loopVars) loc[n] = Sig(n, INTEGER)
            retVal = null
            exec(body)
            val r = retVal ?: throw IOException("the function '${f.name}' ends without RETURN")
            retVal = null
            return r
        } finally {
            locals = saved
            depth--
        }
    }

    private fun unary(op: String, x: V): V {
        val m = mask(x.w)
        return when (op) {
            "not" -> V(x.v.inv() and m, x.w, x.kind, x.low, x.asc)
            "-" -> if (x.kind == INT) V(-x.v, x.w, INT) else V((-x.num) and m, x.w, x.kind)
            "abs" -> if (x.kind == INT) V(Math.abs(x.v), x.w, INT) else V(Math.abs(x.num) and m, x.w, x.kind)
            "and_r", "nand_r" -> V(if ((x.v and m) == m) 1 else 0, 1).let { if (op == "nand_r") unary("not", it) else it }
            "or_r", "nor_r" -> V(if ((x.v and m) != 0L) 1 else 0, 1).let { if (op == "nor_r") unary("not", it) else it }
            "xor_r", "xnor_r" -> V((java.lang.Long.bitCount(x.v and m) and 1).toLong(), 1).let { if (op == "xnor_r") unary("not", it) else it }
            else -> x
        }
    }

    private fun cmp(a: V, b: V): Int =
        if (a.kind == BITS && b.kind == BITS) java.lang.Long.compareUnsigned(a.v and mask(a.w), b.v and mask(b.w))
        else a.num.compareTo(b.num)

    private fun binary(op: String, a: V, b: V): V {
        val w = maxOf(a.w, b.w)
        val m = mask(w)
        val wide = if (a.w >= b.w) a else b
        val kind = if (wide.kind == INT) BITS else wide.kind
        return when (op) {
            "and" -> V(a.v and b.v and m, w, kind, wide.low, wide.asc)
            "or" -> V((a.v or b.v) and m, w, kind, wide.low, wide.asc)
            "xor" -> V((a.v xor b.v) and m, w, kind, wide.low, wide.asc)
            "nand" -> V((a.v and b.v).inv() and m, w, kind, wide.low, wide.asc)
            "nor" -> V((a.v or b.v).inv() and m, w, kind, wide.low, wide.asc)
            "xnor" -> V((a.v xor b.v).inv() and m, w, kind, wide.low, wide.asc)
            "=" -> V(if (cmp(a, b) == 0) 1 else 0, 1)
            "/=" -> V(if (cmp(a, b) != 0) 1 else 0, 1)
            "<" -> V(if (cmp(a, b) < 0) 1 else 0, 1)
            "<=" -> V(if (cmp(a, b) <= 0) 1 else 0, 1)
            ">" -> V(if (cmp(a, b) > 0) 1 else 0, 1)
            ">=" -> V(if (cmp(a, b) >= 0) 1 else 0, 1)
            "&" -> {
                val cw = a.w + b.w
                if (cw > 64) throw IOException("vectors wider than 64 bits are not supported")
                val ck = if (a.kind == SIGNED || b.kind == SIGNED) SIGNED else BITS
                V(shl(a.v and mask(a.w), b.w) or (b.v and mask(b.w)), cw, ck)
            }
            "sll", "srl", "sla", "sra", "rol", "ror" -> {
                val n = b.num.toInt()
                val x = when (op) {
                    "sll", "sla" -> Expr.Call("shift_left", listOf(Expr.Const(a), Expr.Const(V(n.toLong(), 32, INT))))
                    "srl" -> Expr.Call("shift_right", listOf(Expr.Const(V(a.v, a.w, BITS, a.low, a.asc)), Expr.Const(V(n.toLong(), 32, INT))))
                    "sra" -> Expr.Call("shift_right", listOf(Expr.Const(V(a.v, a.w, SIGNED, a.low, a.asc)), Expr.Const(V(n.toLong(), 32, INT))))
                    "rol" -> Expr.Call("rotate_left", listOf(Expr.Const(a), Expr.Const(V(n.toLong(), 32, INT))))
                    else -> Expr.Call("rotate_right", listOf(Expr.Const(a), Expr.Const(V(n.toLong(), 32, INT))))
                }
                call(x).let { r -> V(r.v, r.w, a.kind, a.low, a.asc) }
            }
            else -> arith(op, a, b)
        }
    }

    private fun arith(op: String, a: V, b: V): V {
        if (a.kind == INT && b.kind == INT) {
            val x = a.v
            val y = b.v
            val r = when (op) {
                "+" -> x + y
                "-" -> x - y
                "*" -> x * y
                "/" -> if (y == 0L) 0 else x / y
                "mod" -> if (y == 0L) 0 else Math.floorMod(x, y)
                "rem" -> if (y == 0L) 0 else x % y
                "**" -> pow(x, y)
                else -> throw IOException("operator '$op' is not supported")
            }
            return V(r, 32, INT)
        }
        val wa = if (a.kind == INT) 0 else a.w
        val wb = if (b.kind == INT) 0 else b.w
        var w = when (op) {
            "*" -> if (wa > 0 && wb > 0) wa + wb else 2 * maxOf(wa, wb)
            "/" -> if (wa > 0) wa else wb
            else -> maxOf(wa, wb)
        }
        w = minOf(w, 64)
        val kind = if (a.kind == SIGNED || b.kind == SIGNED) SIGNED else BITS
        val x = a.num
        val y = b.num
        val r = when (op) {
            "+" -> x + y
            "-" -> x - y
            "*" -> x * y
            "/" -> if (y == 0L) 0 else x / y
            "mod" -> if (y == 0L) 0 else Math.floorMod(x, y)
            "rem" -> if (y == 0L) 0 else x % y
            "**" -> pow(x, y)
            else -> throw IOException("operator '$op' is not supported")
        }
        return V(r and mask(w), w, kind)
    }

    // ---------------------------------------------------------------------------------
    // interface used by Digital
    // ---------------------------------------------------------------------------------

    private inner class Interface(private val ins: List<Sig>, private val outs: List<Sig>) : ProcessInterface {
        override fun writeValues(values: ObservableValues) {
            for (i in ins.indices) {
                val s = ins[i]
                val v = norm(s.type, values[i].value)
                if (v != (pending[s] ?: s.value)) pending[s] = v
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
        private const val MAX_LOOP = 100000
        private const val MAX_CALL_DEPTH = 200
        private const val MAX_INSTANCE_DEPTH = 32

        private const val BITS = 0
        private const val SIGNED = 1
        private const val INT = 2

        private const val NORMAL = 0
        private const val EXIT = 1
        private const val NEXT = 2
        private const val RETURN = 3

        private fun mask(w: Int) = if (w >= 64) -1L else if (w <= 0) 0L else (1L shl w) - 1

        private fun sext(v: Long, w: Int): Long = if (w >= 64) v else if (w <= 0) 0 else (v shl (64 - w)) shr (64 - w)

        private fun shl(v: Long, n: Int): Long = if (n >= 64 || n < 0) 0 else v shl n

        private fun shr(v: Long, n: Int): Long = if (n >= 64 || n < 0) 0 else v ushr n

        private fun setBit(r: Long, p: Int, b: Boolean) = if (b) r or (1L shl p) else r and (1L shl p).inv()

        private fun pow(x: Long, y: Long): Long {
            var r = 1L
            for (i in 0 until minOf(y, 64)) r *= x
            return r
        }

        private fun bits(v: Long) = 64 - java.lang.Long.numberOfLeadingZeros(v)

        private fun intType(min: Long, max: Long): Type {
            val w = if (min < 0) 1 + bits(maxOf(max, -min - 1)) else bits(max)
            return Type(INT, minOf(64, maxOf(1, w)), scalar = true, imin = min, imax = max)
        }

        /* normalizes a value to the given type: integers are sign extended if the range contains negative numbers */
        private fun norm(t: Type, v: Long): Long =
            if (t.kind == INT && t.imin < 0) sext(v, t.width) else v and mask(t.width)

        private fun defaultValue(t: Type): Long = if (t.kind == INT && t.scalar && t.imin > 0) t.imin else 0

        /* the position of the bit with the given VHDL index in the value */
        private fun bitPos(w: Int, low: Long, asc: Boolean, index: Long): Int {
            val p = if (asc) w - 1 - (index - low) else index - low
            return if (p < Int.MIN_VALUE || p > Int.MAX_VALUE) -1 else p.toInt()
        }

        /* the position of the element with the given index in the array */
        private fun elemPos(t: Type, index: Long): Int {
            val p = if (t.asc) index - t.low else t.low + t.width - 1 - index
            return if (p < Int.MIN_VALUE || p > Int.MAX_VALUE) -1 else p.toInt()
        }

        /* the name of a signal as written in the code */
        private fun display(n: String) = n.substringBefore('#')

        private val STD_LOGIC = Type(BITS, 1, scalar = true)
        private val INTEGER = intType(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
        private val NATURAL = intType(0, Int.MAX_VALUE.toLong())
        private val POSITIVE = intType(1, Int.MAX_VALUE.toLong())

        private val BUILTIN_TYPES: Map<String, Sym> = HashMap<String, Sym>().also { m ->
            for (n in listOf("std_logic", "std_ulogic", "bit", "boolean", "character")) m[n] = Sym.TypeDef(STD_LOGIC)
            for (n in listOf("std_logic_vector", "std_ulogic_vector", "bit_vector", "unsigned", "string"))
                m[n] = Sym.TypeDef(Type(BITS, -1))
            m["signed"] = Sym.TypeDef(Type(SIGNED, -1))
            for (n in listOf("integer", "time", "real")) m[n] = Sym.TypeDef(INTEGER)
            m["natural"] = Sym.TypeDef(NATURAL)
            m["positive"] = Sym.TypeDef(POSITIVE)
        }

        private val FUNCTIONS = setOf("rising_edge", "falling_edge", "conv_std_logic_vector", "to_unsigned", "to_signed",
            "conv_unsigned", "conv_signed", "resize", "shift_left", "shift_right", "rotate_left", "rotate_right", "shl", "shr",
            "conv_integer", "to_integer", "ext", "sxt", "to_stdlogicvector", "to_bitvector", "to_stdulogicvector", "to_01",
            "to_x01", "to_ux01", "to_bit", "to_stdulogic", "and_reduce", "or_reduce", "xor_reduce", "nand_reduce",
            "nor_reduce", "xnor_reduce", "minimum", "maximum")

        private val ATTRIBUTES = setOf("event", "length", "range", "reverse_range", "high", "low", "left", "right", "ascending")

        /* creates the flattened design of the given entity */
        private fun parse(entity: String, code: String): Shared {
            val sh = Shared(tokenize(code), code.contains("std_logic_signed", true))
            Parser(sh, "").entity(entity.lowercase(), null, emptyList(), null)
            return sh
        }

        /**
         * Creates the simulation of the given entity.
         *
         * @param entity  the name of the entity
         * @param code    the VHDL code, it has to contain all entities instantiated by the entity
         * @param inputs  the inputs in the order Digital uses
         * @param outputs the outputs in the order Digital uses
         * @throws IOException if the code is not supported
         */
        @JvmStatic
        @Throws(IOException::class)
        fun start(entity: String, code: String, inputs: PortDefinition, outputs: PortDefinition): ProcessInterface {
            val sh = parse(entity, code)
            val sim = VhdlInterpreter(entity, sh.signals, sh.processes, sh.signedPkg)
            fun ports(pd: PortDefinition) = pd.map { p ->
                sh.signals[p.name.lowercase()]?.takeIf { it.name in sh.ports }
                    ?: throw IOException("port '${p.name}' not found in entity '$entity'")
            }
            return sim.Interface(ports(inputs), ports(outputs))
        }

        /** returns null if the given entity can be simulated or the reason why not */
        @JvmStatic
        fun check(entity: String, code: String): String? = try {
            parse(entity, code)
            null
        } catch (e: IOException) {
            e.message
        }

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
                    c == '/' && i + 1 < n && code[i + 1] == '*' -> {
                        val e = code.indexOf("*/", i + 2)
                        val end = if (e < 0) n else e + 2
                        for (k in i until end) if (code[k] == '\n') line++
                        i = end
                    }
                    c.isLetter() -> {
                        val s = i
                        while (i < n && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                        val w = code.substring(s, i).lowercase()
                        // based bit strings like x"0F" or b"0101"
                        if ((w == "x" || w == "b" || w == "o") && i < n && code[i] == '"') {
                            val e = code.indexOf('"', i + 1)
                            if (e < 0) throw IOException("VHDL line $line: unterminated string")
                            val digits = code.substring(i + 1, e).replace("_", "")
                            fun conv(radix: Int, len: Int) = digits.map {
                                Integer.toBinaryString(maxOf(0, Character.digit(it, radix))).padStart(len, '0')
                            }.joinToString("")
                            val bits = when (w) {
                                "x" -> conv(16, 4)
                                "o" -> conv(8, 3)
                                else -> digits.lowercase().map { if (it == '1' || it == 'h') '1' else '0' }.joinToString("")
                            }
                            l.add(Tok('b', bits, line))
                            i = e + 1
                        } else l.add(Tok('w', w, line))
                    }
                    c == '\\' -> {
                        // an extended identifier
                        val e = code.indexOf('\\', i + 1)
                        if (e < 0) throw IOException("VHDL line $line: unterminated identifier")
                        l.add(Tok('w', code.substring(i, e + 1).lowercase(), line))
                        i = e + 1
                    }
                    c.isDigit() -> {
                        val s = i
                        while (i < n && (code[i].isDigit() || code[i] == '_')) i++
                        var text = code.substring(s, i).replace("_", "")
                        if (i < n && code[i] == '#') {
                            // a based literal like 16#FF#
                            val e = code.indexOf('#', i + 1)
                            if (e < 0) throw IOException("VHDL line $line: unterminated based literal")
                            text = java.lang.Long.parseLong(code.substring(i + 1, e).replace("_", ""), text.toInt()).toString()
                            i = e + 1
                        } else if (i + 1 < n && code[i] == '.' && code[i + 1].isDigit()) {
                            // the fraction of a real number is ignored
                            i++
                            while (i < n && (code[i].isDigit() || code[i] == '_')) i++
                        }
                        if (i + 1 < n && (code[i] == 'e' || code[i] == 'E') && (code[i + 1].isDigit() || code[i + 1] == '+')) {
                            // an exponent
                            i++
                            if (code[i] == '+') i++
                            val es = i
                            while (i < n && code[i].isDigit()) i++
                            text = (text.toLong() * pow(10, code.substring(es, i).toLong())).toString()
                        }
                        l.add(Tok('n', text, line))
                    }
                    c == '"' -> {
                        val e = code.indexOf('"', i + 1)
                        if (e < 0) throw IOException("VHDL line $line: unterminated string")
                        l.add(Tok('b', code.substring(i + 1, e).replace("_", "").lowercase()
                            .map { if (it == '1' || it == 'h') '1' else '0' }.joinToString(""), line))
                        i = e + 1
                    }
                    // a character literal, but not an attribute like clk'event or a qualified expression like t'('0')
                    c == '\'' && i + 2 < n && code[i + 2] == '\'' &&
                        !(code[i + 1] == '(' && l.lastOrNull()?.kind == 'w') -> {
                        l.add(Tok('c', code[i + 1].lowercase(), line))
                        i += 3
                    }
                    else -> {
                        val two = if (i + 1 < n) code.substring(i, i + 2) else ""
                        if (two in setOf("<=", ":=", "=>", "/=", ">=", "**", "<>")) {
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

    // ---------------------------------------------------------------------------------
    // parser
    // ---------------------------------------------------------------------------------

    private class Tok(val kind: Char, val text: String, val line: Int)

    /** a name declared in the code */
    private sealed class Sym {
        /** a signal, a variable or a constant array, the name used by the simulation */
        class Signal(val global: String) : Sym()
        class Constant(val e: Expr) : Sym()
        class TypeDef(val t: Type) : Sym()
        class Function(val f: Func) : Sym()
        class Alias(val e: Expr) : Sym()
    }

    /** an association of a generic or port map, the actual is null if the port is open */
    private class Assoc(val formal: String?, val actual: Expr?)

    /** the flattened design */
    private class Shared(val toks: List<Tok>, val signedPkg: Boolean) {
        val signals = LinkedHashMap<String, Sig>()
        val ports = HashSet<String>()
        val processes = ArrayList<Process>()

        /** the constant arrays, visible while constant expressions are evaluated */
        val constSigs = HashMap<String, Sig>()
        val engine = VhdlInterpreter("", constSigs, emptyList(), signedPkg)
        var packages: HashMap<String, Sym>? = null
        var unique = 0
        var depth = 0
    }

    /**
     * Parses an entity and its architecture. The signals and processes are added to the shared design,
     * the signals of instances get the prefix "label.".
     */
    private class Parser(private val sh: Shared, private var prefix: String) {
        private val toks = sh.toks
        private var pos = 0
        private val scopes = ArrayList<HashMap<String, Sym>>()

        /** the variables of the current process, null outside of processes */
        private var procLocals: HashMap<String, Sig>? = null

        /** the current function, null outside of functions */
        private var func: Func? = null

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
            if (t.kind != 'w') {
                pos--
                err("name expected but found '${t.text}'")
            }
            return t.text
        }

        private fun identList(): List<String> {
            val names = arrayListOf(ident())
            while (isSym(",")) {
                pos++
                names.add(ident())
            }
            return names
        }

        private fun skipTo(s: String) {
            while (!isSym(s)) next()
            pos++
        }

        private fun optIdent() {
            if (peek()?.kind == 'w') pos++
        }

        private fun lookup(n: String): Sym? {
            for (i in scopes.indices.reversed()) scopes[i][n]?.let { return it }
            return BUILTIN_TYPES[n]
        }

        private fun declare(n: String, s: Sym) {
            scopes.last()[n] = s
        }

        private fun pushScope() = scopes.add(HashMap())
        private fun popScope() = scopes.removeAt(scopes.size - 1)
        private fun unique(n: String) = "$prefix$n#${sh.unique++}"

        /* evaluates a constant expression */
        private fun const(e: Expr, t: Type? = null): V = try {
            if (t == null) sh.engine.eval(e) else sh.engine.cast(sh.engine.evalTyped(e, t), t)
        } catch (ex: IOException) {
            err(ex.message ?: "constant expression expected")
        }

        private fun resolve(ts: TypeSpec): Type = try {
            sh.engine.resolve(ts)
        } catch (ex: IOException) {
            err(ex.message ?: "constant expression expected")
        }

        private fun newSig(global: String, t: Type, init: Expr?): Sig {
            if (t.isArray && t.elem!!.isArray) err("arrays of arrays are not supported")
            if (t.width < 0) err("the range of '${display(global.substringAfterLast('.'))}' is missing")
            val s = Sig(global, t)
            try {
                sh.engine.initSig(s, init)
            } catch (ex: IOException) {
                err(ex.message ?: "constant expression expected")
            }
            return s
        }

        private fun findWords(vararg w: String?): List<Int> {
            val l = ArrayList<Int>()
            for (i in toks.indices) {
                var ok = true
                for (k in w.indices) {
                    val t = toks.getOrNull(i + k)
                    if (w[k] != null && (t == null || t.kind != 'w' || t.text != w[k])) {
                        ok = false
                        break
                    }
                }
                if (ok) l.add(i)
            }
            return l
        }

        /* the packages declared in the code: their constants, types and functions are visible everywhere */
        private fun packages(): HashMap<String, Sym> {
            sh.packages?.let { return it }
            val m = HashMap<String, Sym>()
            sh.packages = m
            val saved = ArrayList(scopes)
            scopes.clear()
            scopes.add(m)
            for (i in findWords("package", null, "is")) if (toks[i + 1].text != "body") {
                pos = i + 3
                if (isWord("new")) err("package instances are not supported")
                declarations()
            }
            for (i in findWords("package", "body", null, "is")) {
                pos = i + 4
                declarations()
            }
            scopes.clear()
            scopes.addAll(saved)
            return m
        }

        /**
         * Parses the entity and its architecture.
         * If [ports] is null, the entity is the top level entity, otherwise the ports are connected to the actuals.
         */
        fun entity(name: String, arch: String?, generics: List<Assoc>, ports: List<Assoc>?) {
            // like GHDL the last declaration is used, so a file may be preceded by the files it uses
            val start = findWords("entity", name, "is").lastOrNull() ?: throw IOException("entity '$name' not found")
            scopes.add(packages())
            pushScope()
            pos = start + 3
            if (isWord("generic")) genericClause(generics)
            if (isWord("port")) portClause(ports)
            // the architecture: the given one or the last one in the code
            val archs = findWords("architecture", null, "of", name, "is").filter { arch == null || toks[it + 1].text == arch }
            if (archs.isEmpty()) throw IOException("no architecture of '$name' found")
            pos = archs.last() + 5
            declarations()
            word("begin")
            while (!isWord("end")) concurrent()
        }

        private fun assocFor(formal: String, index: Int, assocs: List<Assoc>): Assoc? =
            assocs.firstOrNull { it.formal == formal } ?: assocs.getOrNull(index)?.takeIf { it.formal == null }

        private fun genericClause(assocs: List<Assoc>) {
            word("generic")
            sym("(")
            var index = 0
            while (!isSym(")")) {
                if (isWord("constant")) pos++
                val names = identList()
                sym(":")
                val ts = typeSpec()
                var def: Expr? = null
                if (isSym(":=")) {
                    pos++
                    def = expr()
                }
                val t = resolve(ts)
                for (n in names) {
                    val a = assocFor(n, index++, assocs)
                    val e = a?.actual ?: def ?: err("the generic '$n' has no value")
                    declare(n, Sym.Constant(Expr.Const(const(e, t))))
                }
                if (isSym(";")) pos++
            }
            sym(")")
            sym(";")
        }

        private fun portClause(assocs: List<Assoc>?) {
            word("port")
            sym("(")
            var index = 0
            while (!isSym(")")) {
                if (isWord("signal")) pos++
                val names = identList()
                sym(":")
                val mode = if (peek()?.text in setOf("in", "out", "inout", "buffer", "linkage")) ident() else "in"
                val t = resolve(typeSpec())
                if (t.isArray) err("array ports are not supported")
                var def: Expr? = null
                if (isSym(":=")) {
                    pos++
                    def = expr()
                }
                for (n in names) {
                    if (assocs == null) {
                        sh.signals[prefix + n] = newSig(prefix + n, t, def)
                        sh.ports.add(prefix + n)
                        declare(n, Sym.Signal(prefix + n))
                    } else connectPort(n, mode, t, def, assocFor(n, index, assocs))
                    index++
                }
                if (isSym(";")) pos++
            }
            sym(")")
            sym(";")
        }

        /*
         * Connects a port of an instance. If the actual is a signal of the same type, the port is
         * this signal. Otherwise a signal is created and connected by an assignment.
         */
        private fun connectPort(n: String, mode: String, t: Type, def: Expr?, a: Assoc?) {
            val actual = a?.actual
            if (actual is Expr.Ref) {
                val s = sh.signals[actual.name]
                if (s != null && !s.type.isArray && s.type.kind == t.kind && s.type.width == t.width
                    && (t.width == 1 || (s.type.low == t.low && s.type.asc == t.asc))) {
                    declare(n, Sym.Signal(s.name))
                    return
                }
            }
            val g = prefix + n
            sh.signals[g] = newSig(g, t, def)
            declare(n, Sym.Signal(g))
            if (actual == null) return
            if (mode == "in") addImplicit(listOf(Stmt.Assign(Target(g, emptyList()), actual, false)))
            else addImplicit(listOf(Stmt.Assign(targetOf(actual), Expr.Ref(g), false)))
        }

        // ---- declarations -------------------------------------------------------------

        /* the declarations of an architecture, a package, a process, a function or a generate statement */
        private fun declarations() {
            while (!isWord("begin") && !isWord("end")) {
                when {
                    isWord("signal") -> signalDecl()
                    isWord("variable") || isWord("shared") -> variableDecl()
                    isWord("constant") -> constantDecl()
                    isWord("type") -> typeDecl()
                    isWord("subtype") -> {
                        pos++
                        val n = ident()
                        word("is")
                        declare(n, Sym.TypeDef(resolve(typeSpec())))
                        sym(";")
                    }
                    isWord("alias") -> {
                        pos++
                        val n = ident()
                        if (isSym(":")) {
                            pos++
                            typeSpec()
                        }
                        word("is")
                        declare(n, Sym.Alias(expr()))
                        sym(";")
                    }
                    isWord("component") -> {
                        while (!(isWord("end") && isWord("component", 1))) next()
                        pos += 2
                        skipTo(";")
                    }
                    isWord("attribute") || isWord("use") || isWord("library") -> skipTo(";")
                    isWord("function") || isWord("procedure") || isWord("pure") || isWord("impure") -> subprogram()
                    isWord("file") -> err("files are not supported")
                    else -> err("unexpected '${peek()?.text}'")
                }
            }
        }

        private fun signalDecl() {
            if (procLocals != null || func != null) err("signals cannot be declared here")
            pos++
            val names = identList()
            sym(":")
            val t = resolve(typeSpec())
            if (isWord("register") || isWord("bus")) pos++
            val init = optInit()
            sym(";")
            for (n in names) {
                val g = prefix + n
                sh.signals[g] = newSig(g, t, init)
                declare(n, Sym.Signal(g))
            }
        }

        private fun optInit(): Expr? {
            if (!isSym(":=")) return null
            pos++
            return expr()
        }

        private fun variableDecl() {
            if (isWord("shared")) pos++
            word("variable")
            val names = identList()
            sym(":")
            val ts = typeSpec()
            val init = optInit()
            sym(";")
            for (n in names) local(n, ts, init)
        }

        /* declares a variable of the current process or function, or a shared variable */
        private fun local(n: String, ts: TypeSpec, init: Expr?) {
            val f = func
            val pl = procLocals
            val g = unique(n)
            when {
                f != null -> f.vars.add(Triple(g, ts, init))
                pl != null -> pl[g] = newSig(g, resolve(ts), init)
                else -> sh.signals[g] = newSig(g, resolve(ts), init)
            }
            declare(n, Sym.Signal(g))
        }

        private fun constantDecl() {
            pos++
            val names = identList()
            sym(":")
            val ts = typeSpec()
            if (isSym(";")) {
                // a deferred constant, the value is given in the package body
                pos++
                return
            }
            sym(":=")
            val e = expr()
            sym(";")
            for (n in names) {
                if (func != null) {
                    // a constant of a function may depend on the parameters
                    local(n, ts, e)
                    continue
                }
                val t = resolve(ts)
                if (t.isArray) {
                    val g = unique(n)
                    val s = newSig(g, t, e)
                    sh.signals[g] = s
                    sh.constSigs[g] = s
                    declare(n, Sym.Signal(g))
                } else declare(n, Sym.Constant(Expr.Const(const(e, t))))
            }
        }

        private fun typeDecl() {
            pos++
            val n = ident()
            if (isSym(";")) {
                // an incomplete type declaration
                pos++
                return
            }
            word("is")
            when {
                isSym("(") -> {
                    // an enumeration type
                    pos++
                    val lits = ArrayList<String>()
                    while (!isSym(")")) {
                        if (peek()?.kind != 'w') err("enumerations of characters are not supported")
                        lits.add(ident())
                        if (isSym(",")) pos++
                    }
                    pos++
                    val w = maxOf(1, bits(lits.size - 1L))
                    val t = Type(BITS, w, scalar = true, enumSize = lits.size)
                    declare(n, Sym.TypeDef(t))
                    lits.forEachIndexed { i, l -> declare(l, Sym.Constant(Expr.Const(V(i.toLong(), w)))) }
                }
                isWord("array") -> {
                    pos++
                    sym("(")
                    var rng: Rng? = null
                    if (peek()?.kind == 'w' && isWord("range", 1)) {
                        pos += 2
                        if (isSym("<>")) pos++ else rng = range()
                    } else rng = range()
                    if (isSym(",")) err("multi dimensional arrays are not supported")
                    sym(")")
                    word("of")
                    val elem = resolve(typeSpec())
                    if (elem.width < 0) err("the element type of '$n' needs a range")
                    if (elem.isArray) err("arrays of arrays are not supported")
                    var t = Type(elem.kind, -1, elem = elem)
                    if (rng != null) t = resolve(TypeSpec(t, rng))
                    declare(n, Sym.TypeDef(t))
                }
                isWord("range") -> {
                    pos++
                    declare(n, Sym.TypeDef(resolve(TypeSpec(INTEGER, range()))))
                }
                isWord("record") -> err("record types are not supported")
                else -> err("the type declaration of '$n' is not supported")
            }
            sym(";")
        }

        /* a type mark with an optional constraint */
        private fun typeSpec(): TypeSpec {
            val n = ident()
            val base = (lookup(n) as? Sym.TypeDef)?.t ?: err("the type '$n' is not supported")
            if (isSym("(")) {
                pos++
                val r = range()
                if (isSym(",")) err("multi dimensional arrays are not supported")
                sym(")")
                return TypeSpec(base, r)
            }
            if (isWord("range")) {
                pos++
                return TypeSpec(base, range())
            }
            return TypeSpec(base, null)
        }

        /* a range: a to b, a downto b, x'range or a type */
        private fun range(): Rng {
            val a = expr()
            if (a is Expr.Attr && (a.attr == "range" || a.attr == "reverse_range")) return rangeOf(a)
            if (a is Expr.TypeE) return rangeOf(Expr.Attr(a, "range"))
            val asc = when {
                isWord("to") -> true
                isWord("downto") -> false
                else -> err("'to' or 'downto' expected")
            }
            pos++
            return Rng(a, expr(), Expr.Const(V(if (asc) 1 else 0, 1)))
        }

        private fun rangeOf(a: Expr.Attr): Rng {
            val l = Expr.Attr(a.base, "left")
            val r = Expr.Attr(a.base, "right")
            val asc = Expr.Attr(a.base, "ascending")
            return if (a.attr == "range") Rng(l, r, asc) else Rng(r, l, Expr.Unary("not", asc))
        }

        /* a function or a procedure, the procedures are parsed but not supported */
        private fun subprogram() {
            if (isWord("pure") || isWord("impure")) pos++
            val procedure = isWord("procedure")
            pos++
            if (peek()?.kind != 'w') err("operator overloading is not supported")
            val n = ident()
            val f = (lookup(n) as? Sym.Function)?.f?.takeIf { it.body == null && it.procedure == procedure } ?: Func(n, procedure)
            val first = f.params.isEmpty()
            pushScope()
            val params = ArrayList<Param>()
            if (isSym("(")) {
                pos++
                while (!isSym(")")) {
                    if (isWord("constant") || isWord("signal") || isWord("variable") || isWord("file")) pos++
                    val names = identList()
                    sym(":")
                    if (isWord("in") || isWord("out") || isWord("inout")) pos++
                    val ts = typeSpec()
                    val def = optInit()
                    for (p in names) {
                        val g = unique(p)
                        params.add(Param(p, g, ts, def))
                        declare(p, Sym.Signal(g))
                    }
                    if (isSym(";")) pos++
                }
                pos++
            }
            if (first) f.params.addAll(params)
            else {
                // the declaration in the package and the body use the same names
                for ((i, p) in params.withIndex()) f.params.getOrNull(i)?.let { declare(p.name, Sym.Signal(it.local)) }
            }
            if (!procedure) {
                word("return")
                f.ret = typeSpec()
            }
            scopes[scopes.size - 2][n] = Sym.Function(f)
            if (isSym(";")) {
                // only the declaration
                pos++
                popScope()
                return
            }
            word("is")
            val savedFunc = func
            val savedLocals = procLocals
            func = f
            procLocals = null
            declarations()
            word("begin")
            val body = statements(setOf("end"))
            word("end")
            if (isWord("function") || isWord("procedure")) pos++
            optIdent()
            sym(";")
            func = savedFunc
            procLocals = savedLocals
            popScope()
            f.body = body
        }

        // ---- concurrent statements ----------------------------------------------------

        private fun concurrent() {
            var label: String? = null
            if (peek()?.kind == 'w' && isSym(":", 1)) {
                label = ident()
                pos++
            }
            if (isWord("postponed")) pos++
            when {
                isWord("process") -> process()
                isWord("assert") -> skipTo(";")
                isWord("with") -> selected()
                isWord("for") && label != null -> forGenerate(label)
                isWord("if") && label != null -> ifGenerate(label)
                isWord("block") -> err("block statements are not supported")
                isWord("entity") || isWord("component") || isWord("configuration") ||
                    (peek()?.kind == 'w' && (isWord("port", 1) || isWord("generic", 1))) ->
                    instance(label ?: err("instances need a label"))
                else -> conditional()
            }
        }

        private fun process() {
            word("process")
            val sens = LinkedHashSet<String>()
            var all = false
            if (isSym("(")) {
                pos++
                while (!isSym(")")) {
                    if (isWord("all")) {
                        pos++
                        all = true
                    } else collect(name(ident()), sens)
                    if (isSym(",")) pos++
                }
                pos++
            }
            if (isWord("is")) pos++
            val locals = HashMap<String, Sig>()
            pushScope()
            procLocals = locals
            declarations()
            word("begin")
            var wait: Expr? = null
            if (sens.isEmpty() && !all && isWord("wait")) {
                pos++
                word("until")
                wait = expr()
                sym(";")
                collect(wait, sens)
            }
            val body = statements(setOf("end", "wait"))
            var once = false
            if (isWord("wait")) {
                // a WAIT at the end of the process
                pos++
                when {
                    isSym(";") -> once = true
                    isWord("on") && wait == null -> {
                        pos++
                        while (!isSym(";")) {
                            collect(name(ident()), sens)
                            if (isSym(",")) pos++
                        }
                    }
                    else -> err("only one WAIT at the beginning or at the end of a process is supported")
                }
                sym(";")
                if (!isWord("end")) err("only one WAIT at the beginning or at the end of a process is supported")
            }
            word("end")
            if (isWord("postponed")) pos++
            word("process")
            optIdent()
            sym(";")
            popScope()
            procLocals = null
            if (all) body.forEach { collect(it, sens) }
            if (sens.isEmpty() && wait == null && !once) err("a process needs a sensitivity list or a WAIT")
            sh.processes.add(Process(sens, wait, body, locals, once))
        }

        /* target <= a WHEN c ELSE b; */
        private fun conditional() {
            val t = target()
            sym("<=")
            if (isWord("guarded")) err("guarded assignments are not supported")
            addImplicit(listOf(conditionalValue(t, false)))
        }

        /* the value of a signal assignment, also with WHEN ELSE */
        private fun conditionalValue(t: Target, variable: Boolean): Stmt {
            if (isWord("transport")) pos++
            if (isWord("inertial")) pos++
            val values = ArrayList<Expr>()
            val conds = ArrayList<Expr>()
            values.add(waveform())
            while (isWord("when")) {
                pos++
                conds.add(expr())
                if (!isWord("else")) break
                pos++
                values.add(waveform())
            }
            sym(";")
            if (conds.isEmpty()) return Stmt.Assign(t, values[0], variable)
            return Stmt.If(conds, conds.indices.map { listOf(Stmt.Assign(t, values[it], variable)) },
                if (values.size > conds.size) listOf(Stmt.Assign(t, values.last(), variable)) else null)
        }

        /* a value with an optional delay, the delay is ignored */
        private fun waveform(): Expr {
            val e = expr()
            if (isWord("after")) {
                pos++
                expr()
            }
            if (isSym(",")) err("waveforms with several elements are not supported")
            return e
        }

        /* WITH sel SELECT target <= a WHEN c1, b WHEN OTHERS; */
        private fun selected() {
            addImplicit(listOf(selectedStmt(false)))
        }

        private fun selectedStmt(inProcess: Boolean): Stmt {
            word("with")
            val sel = expr()
            word("select")
            if (isSym("?")) err("matching selected assignments are not supported")
            val t = target()
            val variable = inProcess && isSym(":=")
            if (variable) pos++ else sym("<=")
            val choices = ArrayList<Pair<List<Choice>, List<Stmt>>>()
            while (true) {
                val v = waveform()
                word("when")
                choices.add(choices() to listOf(Stmt.Assign(t, v, variable)))
                if (isSym(",")) pos++ else break
            }
            sym(";")
            return Stmt.Case(sel, choices)
        }

        /* a concurrent statement is a process sensitive to all signals it reads */
        private fun addImplicit(body: List<Stmt>) {
            val sens = LinkedHashSet<String>()
            body.forEach { collect(it, sens) }
            sh.processes.add(Process(sens, null, body, HashMap(), false))
        }

        private fun choices(): List<Choice> {
            val l = ArrayList<Choice>()
            while (true) {
                if (isWord("others")) {
                    pos++
                    l.add(Choice(null, null))
                } else {
                    val a = expr()
                    when {
                        isWord("to") || isWord("downto") -> {
                            val asc = isWord("to")
                            pos++
                            l.add(Choice(null, Rng(a, expr(), Expr.Const(V(if (asc) 1 else 0, 1)))))
                        }
                        a is Expr.TypeE || (a is Expr.Attr && a.attr.endsWith("range")) ->
                            l.add(Choice(null, rangeOf(if (a is Expr.Attr) a else Expr.Attr(a, "range"))))
                        else -> l.add(Choice(a, null))
                    }
                }
                if (isSym("|")) pos++ else return l
            }
        }

        /* an entity or a component instance: the entity is parsed with the generics and the ports connected */
        private fun instance(label: String) {
            var arch: String? = null
            val entity: String
            when {
                isWord("entity") -> {
                    pos++
                    if (isSym(".", 1)) pos += 2
                    entity = ident()
                    if (isSym("(")) {
                        pos++
                        arch = ident()
                        sym(")")
                    }
                }
                isWord("configuration") -> err("configurations are not supported")
                else -> {
                    if (isWord("component")) pos++
                    entity = ident()
                }
            }
            val generics = if (isWord("generic")) {
                pos++
                word("map")
                assocList()
            } else emptyList()
            val ports = if (isWord("port")) {
                pos++
                word("map")
                assocList()
            } else emptyList()
            sym(";")
            if (findWords("entity", entity, "is").isEmpty())
                err("the entity '$entity' of the instance '$label' is not found, it has to be in the same file")
            if (++sh.depth > MAX_INSTANCE_DEPTH) err("too many nested instances")
            try {
                Parser(sh, "$prefix$label.").entity(entity, arch, generics, ports)
            } catch (e: IOException) {
                throw IOException("${e.message} (instance $prefix$label of $entity)", e)
            }
            sh.depth--
        }

        private fun assocList(): List<Assoc> {
            sym("(")
            val l = ArrayList<Assoc>()
            while (!isSym(")")) {
                var formal: String? = null
                if (peek()?.kind == 'w' && isSym("=>", 1)) {
                    formal = ident()
                    pos++
                }
                val actual = if (isWord("open")) {
                    pos++
                    null
                } else expr()
                if (isSym("=>")) err("partial associations are not supported")
                l.add(Assoc(formal, actual))
                if (isSym(",")) pos++
            }
            pos++
            return l
        }

        private fun forGenerate(label: String) {
            word("for")
            val n = ident()
            word("in")
            val r = range()
            word("generate")
            val a = const(r.left).num
            val b = const(r.right).num
            val asc = const(r.asc).bool
            val start = pos
            val saved = prefix
            val values = if (asc) (a..b).toList() else (a downTo b).toList()
            if (values.isEmpty()) skipGenerate(false)
            for (v in values) {
                pos = start
                prefix = "$saved$label($v)."
                pushScope()
                declare(n, Sym.Constant(Expr.Const(V(v, 32, INT))))
                generateBody()
                popScope()
            }
            prefix = saved
            endGenerate()
        }

        private fun ifGenerate(label: String) {
            word("if")
            val c = const(expr()).bool
            word("generate")
            val saved = prefix
            prefix = "$saved$label."
            if (c) {
                generateBody()
                if (isWord("else")) {
                    pos += 2
                    skipGenerate(false)
                }
            } else {
                skipGenerate(true)
                if (isWord("else")) {
                    pos += 2
                    generateBody()
                }
            }
            prefix = saved
            endGenerate()
        }

        private fun generateBody() {
            pushScope()
            if (peek()?.text in setOf("signal", "constant", "type", "subtype", "component", "attribute", "function", "shared", "alias")) {
                declarations()
                word("begin")
            } else if (isWord("begin")) pos++
            while (!isWord("end") && !isWord("else") && !isWord("elsif")) concurrent()
            if (isWord("end") && isSym(";", 1)) pos += 2
            if (isWord("elsif")) err("elsif generate is not supported")
            popScope()
        }

        /* skips the body of a generate statement, stops at END GENERATE or at ELSE GENERATE if [toElse] is set */
        private fun skipGenerate(toElse: Boolean) {
            var d = 0
            while (true) {
                if (isWord("generate")) {
                    val prev = toks[pos - 1].text
                    if (prev == "end") {
                        if (d == 0) {
                            pos--
                            return
                        }
                        d--
                    } else if (prev != "else") d++
                } else if (toElse && d == 0 && isWord("else") && isWord("generate", 1)) return
                next()
            }
        }

        private fun endGenerate() {
            word("end")
            word("generate")
            optIdent()
            sym(";")
        }

        private fun target(): Target {
            val n = ident()
            val base = when (val s = lookup(n)) {
                is Sym.Signal -> Target(s.global, emptyList())
                is Sym.Alias -> targetOf(s.e)
                else -> err(if (s == null) "unknown name '$n'" else "'$n' cannot be assigned")
            }
            val suf = ArrayList(base.suffixes)
            while (isSym("(")) {
                pos++
                val a = expr()
                when {
                    isWord("to") || isWord("downto") -> {
                        val asc = isWord("to")
                        pos++
                        suf.add(Rng(a, expr(), Expr.Const(V(if (asc) 1 else 0, 1))))
                    }
                    a is Expr.Attr && a.attr.endsWith("range") -> suf.add(rangeOf(a))
                    else -> suf.add(a)
                }
                sym(")")
            }
            return Target(base.name, suf)
        }

        private fun targetOf(e: Expr): Target = when (e) {
            is Expr.Ref -> Target(e.name, emptyList())
            is Expr.Index -> targetOf(e.base).let { Target(it.name, it.suffixes + e.index) }
            is Expr.Slice -> targetOf(e.base).let { Target(it.name, it.suffixes + e.r) }
            else -> err("the expression cannot be assigned")
        }

        // ---- sequential statements ----------------------------------------------------

        private fun statements(end: Set<String>): List<Stmt> {
            val l = ArrayList<Stmt>()
            while (peek()?.let { it.kind == 'w' && it.text in end } != true) {
                if (peek() == null) err("unexpected end")
                statement()?.let { l.add(it) }
            }
            return l
        }

        private fun statement(): Stmt? {
            var label: String? = null
            if (peek()?.kind == 'w' && isSym(":", 1)) {
                label = ident()
                pos++
            }
            when {
                isWord("null") -> {
                    pos++
                    sym(";")
                    return null
                }
                isWord("if") -> return ifStmt()
                isWord("case") -> {
                    pos++
                    val sel = expr()
                    word("is")
                    val choices = ArrayList<Pair<List<Choice>, List<Stmt>>>()
                    while (isWord("when")) {
                        pos++
                        val c = choices()
                        sym("=>")
                        choices.add(c to statements(setOf("when", "end")))
                    }
                    word("end")
                    word("case")
                    optIdent()
                    sym(";")
                    return Stmt.Case(sel, choices)
                }
                isWord("for") -> {
                    pos++
                    val n = ident()
                    word("in")
                    val r = range()
                    word("loop")
                    val g = unique(n)
                    val f = func
                    if (f != null) f.loopVars.add(g)
                    else (procLocals ?: err("loops are only supported in processes and functions"))[g] = Sig(g, INTEGER)
                    pushScope()
                    declare(n, Sym.Signal(g))
                    val body = loopEnd()
                    popScope()
                    return Stmt.For(g, r, body, label)
                }
                isWord("while") -> {
                    pos++
                    val c = expr()
                    word("loop")
                    return Stmt.While(c, loopEnd(), label)
                }
                isWord("loop") -> {
                    pos++
                    return Stmt.While(null, loopEnd(), label)
                }
                isWord("exit") || isWord("next") -> {
                    val next = isWord("next")
                    pos++
                    var l: String? = null
                    if (peek()?.kind == 'w' && !isWord("when")) l = ident()
                    var c: Expr? = null
                    if (isWord("when")) {
                        pos++
                        c = expr()
                    }
                    sym(";")
                    return Stmt.Exit(next, l, c)
                }
                isWord("return") -> {
                    val f = func ?: err("RETURN outside of a function")
                    pos++
                    val e = if (isSym(";")) null else expr()
                    sym(";")
                    return Stmt.Return(e, f.ret)
                }
                isWord("assert") || isWord("report") -> {
                    skipTo(";")
                    return null
                }
                isWord("wait") -> err("only one WAIT at the beginning or at the end of a process is supported")
                isWord("with") -> return selectedStmt(true)
                else -> {
                    if (peek()?.kind == 'w' && (lookup(peek()!!.text) as? Sym.Function)?.f?.procedure == true)
                        err("procedure calls are not supported")
                    val t = target()
                    val variable = isSym(":=")
                    if (!variable) sym("<=") else pos++
                    return conditionalValue(t, variable)
                }
            }
        }

        private fun ifStmt(): Stmt {
            word("if")
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
            optIdent()
            sym(";")
            return Stmt.If(conds, bodies, otherwise)
        }

        private fun loopEnd(): List<Stmt> {
            val body = statements(setOf("end"))
            word("end")
            word("loop")
            optIdent()
            sym(";")
            return body
        }

        // ---- expressions --------------------------------------------------------------

        private val logical = setOf("and", "or", "xor", "nand", "nor", "xnor")
        private val relational = setOf("=", "/=", "<", "<=", ">", ">=")
        private val shifts = setOf("sll", "srl", "sla", "sra", "rol", "ror")

        fun expr(): Expr {
            var e = relation()
            while (peek()?.let { it.kind == 'w' && it.text in logical } == true) {
                val op = next().text
                e = Expr.Bin(op, e, relation())
            }
            return e
        }

        private fun relation(): Expr {
            val a = shiftExpr()
            if (peek()?.let { it.kind == 's' && it.text in relational } == true) {
                val op = next().text
                return Expr.Bin(op, a, shiftExpr())
            }
            return a
        }

        private fun shiftExpr(): Expr {
            val a = simple()
            if (peek()?.let { it.kind == 'w' && it.text in shifts } == true) {
                val op = next().text
                return Expr.Bin(op, a, simple())
            }
            return a
        }

        private fun simple(): Expr {
            var e = when {
                isSym("-") -> {
                    pos++
                    Expr.Unary("-", term())
                }
                isSym("+") -> {
                    pos++
                    term()
                }
                else -> term()
            }
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
            when {
                isWord("not") -> {
                    pos++
                    return Expr.Unary("not", factor())
                }
                isWord("abs") -> {
                    pos++
                    return Expr.Unary("abs", primary())
                }
                peek()?.let { it.kind == 'w' && it.text in logical } == true -> {
                    // the reduction operators of VHDL-2008
                    val op = next().text
                    return Expr.Unary(op + "_r", primary())
                }
            }
            val p = primary()
            if (isSym("**")) {
                pos++
                return Expr.Bin("**", p, primary())
            }
            return p
        }

        private fun primary(): Expr {
            val t = next()
            return when (t.kind) {
                'n' -> {
                    // physical literals like 10 ns are only used for delays
                    if (peek()?.text in setOf("fs", "ps", "ns", "us", "ms", "sec", "min", "hr")) {
                        pos++
                        Expr.Const(V(0, 32, INT))
                    } else Expr.Const(V(t.text.toLongOrNull() ?: err("number too large: ${t.text}"), 32, INT))
                }
                'c' -> Expr.Const(V(if (t.text == "1" || t.text == "h") 1 else 0, 1))
                'b' -> {
                    if (t.text.length > 64) err("vectors wider than 64 bits are not supported")
                    Expr.Const(V(if (t.text.isEmpty()) 0 else java.lang.Long.parseUnsignedLong(t.text, 2), t.text.length))
                }
                's' -> if (t.text == "(") aggregateOrParen() else {
                    pos--
                    err("unexpected '${t.text}'")
                }
                'w' -> when (t.text) {
                    "true" -> Expr.Const(V(1, 1))
                    "false" -> Expr.Const(V(0, 1))
                    else -> postfix(name(t.text))
                }
                else -> err("unexpected '${t.text}'")
            }
        }

        /* the expression a name stands for */
        private fun name(n: String): Expr = when (val s = lookup(n)) {
            is Sym.Signal -> Expr.Ref(s.global)
            is Sym.Constant -> s.e
            is Sym.Alias -> s.e
            is Sym.Function -> {
                if (s.f.procedure) err("procedure calls are not supported")
                Expr.User(s.f, userArgs(s.f))
            }
            is Sym.TypeDef -> typeExpr(s.t)
            null -> if (n in FUNCTIONS) Expr.Call(n, args().map { it.second }) else err("unknown name '$n'")
        }

        private fun args(): List<Pair<String?, Expr>> {
            if (!isSym("(")) return emptyList()
            pos++
            val l = ArrayList<Pair<String?, Expr>>()
            while (!isSym(")")) {
                var n: String? = null
                if (peek()?.kind == 'w' && isSym("=>", 1)) {
                    n = ident()
                    pos++
                }
                l.add(n to expr())
                if (isSym(",")) pos++ else break
            }
            sym(")")
            return l
        }

        /* the arguments of a function call in the order of the parameters */
        private fun userArgs(f: Func): List<Expr> {
            val a = args()
            if (a.none { it.first != null }) return a.map { it.second }
            val r = ArrayList<Expr>()
            for ((i, p) in f.params.withIndex()) {
                val e = a.firstOrNull { it.first == p.name }?.second ?: a.getOrNull(i)?.takeIf { it.first == null }?.second
                ?: p.default ?: err("${f.name}: the argument '${p.name}' is missing")
                r.add(e)
            }
            return r
        }

        /* a type name: a conversion, a qualified expression, an attribute or a range */
        private fun typeExpr(t: Type): Expr {
            if (isSym("'")) {
                if (isSym("(", 1)) {
                    pos += 2
                    return Expr.Convert(t, aggregateOrParen())
                }
                val a = peek(1)?.text
                if (a == "pos" || a == "val") {
                    pos += 2
                    sym("(")
                    val e = expr()
                    sym(")")
                    return Expr.Convert(if (a == "pos") INTEGER else t, e)
                }
                if (a in ATTRIBUTES && a != "event") {
                    pos += 2
                    return Expr.Attr(Expr.TypeE(t), a!!)
                }
                err("the attribute '$a' is not supported")
            }
            if (isSym("(")) {
                pos++
                val e = expr()
                sym(")")
                return Expr.Convert(t, e)
            }
            return Expr.TypeE(t)
        }

        /* indices, slices and attributes following a name */
        private fun postfix(start: Expr): Expr {
            var e = start
            while (true) {
                if (isSym("(") && (e is Expr.Ref || e is Expr.Index || e is Expr.User || e is Expr.Call || e is Expr.Convert || e is Expr.Const)) {
                    pos++
                    val a = expr()
                    e = when {
                        isWord("to") || isWord("downto") -> {
                            val asc = isWord("to")
                            pos++
                            Expr.Slice(e, Rng(a, expr(), Expr.Const(V(if (asc) 1 else 0, 1))))
                        }
                        a is Expr.Attr && a.attr.endsWith("range") -> Expr.Slice(e, rangeOf(a))
                        else -> Expr.Index(e, a)
                    }
                    if (isSym(",")) err("multi dimensional arrays are not supported")
                    sym(")")
                } else if (isSym("'") && peek(1)?.kind == 'w') {
                    val a = peek(1)!!.text
                    if (a !in ATTRIBUTES) err("the attribute '$a' is not supported")
                    pos += 2
                    e = Expr.Attr(e, a)
                } else return e
            }
        }

        /* an aggregate like (others => '0') or an expression in parentheses */
        private fun aggregateOrParen(): Expr {
            val items = ArrayList<Pair<List<Choice>?, Expr>>()
            while (true) {
                val save = pos
                var choices: List<Choice>? = null
                if (isWord("others")) choices = choices()
                else {
                    val a = expr()
                    if (isSym("=>") || isSym("|") || isWord("to") || isWord("downto")) {
                        pos = save
                        choices = choices()
                    } else items.add(null to a)
                }
                if (choices != null) {
                    sym("=>")
                    items.add(choices to expr())
                }
                if (isSym(",")) pos++ else break
            }
            sym(")")
            if (items.size == 1 && items[0].first == null) return items[0].second
            return Expr.Aggregate(items)
        }

        private fun collect(s: Stmt, into: MutableSet<String>) {
            when (s) {
                is Stmt.Assign -> {
                    collect(s.value, into)
                    for (x in s.target.suffixes) if (x is Expr) collect(x, into) else collect(x as Rng, into)
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
                is Stmt.For -> {
                    collect(s.r, into)
                    s.body.forEach { collect(it, into) }
                }
                is Stmt.While -> {
                    s.cond?.let { collect(it, into) }
                    s.body.forEach { collect(it, into) }
                }
                is Stmt.Exit -> s.cond?.let { collect(it, into) }
                is Stmt.Return -> s.e?.let { collect(it, into) }
            }
        }

        private fun collect(r: Rng, into: MutableSet<String>) {
            collect(r.left, into)
            collect(r.right, into)
        }

        private fun collect(e: Expr, into: MutableSet<String>) {
            when (e) {
                is Expr.Ref -> if (procLocals?.containsKey(e.name) != true) into.add(e.name)
                is Expr.Index -> {
                    collect(e.base, into)
                    collect(e.index, into)
                }
                is Expr.Slice -> {
                    collect(e.base, into)
                    collect(e.r, into)
                }
                is Expr.Attr -> if (e.attr == "event") collect(e.base, into)
                is Expr.Unary -> collect(e.e, into)
                is Expr.Bin -> {
                    collect(e.a, into)
                    collect(e.b, into)
                }
                is Expr.Call -> e.args.forEach { collect(it, into) }
                is Expr.User -> e.args.forEach { collect(it, into) }
                is Expr.Convert -> collect(e.e, into)
                is Expr.Aggregate -> e.items.forEach { (c, x) ->
                    collect(x, into)
                    c?.forEach { ch ->
                        ch.e?.let { collect(it, into) }
                        ch.r?.let { collect(it, into) }
                    }
                }
                else -> {}
            }
        }
    }
}
