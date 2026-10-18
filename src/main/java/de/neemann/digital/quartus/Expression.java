/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus;

import java.util.Locale;
import java.util.Map;

/**
 * A tiny evaluator for the integer expressions found in HDL ranges and
 * in Quartus parameters, like "WIDTH-1" or "2**N".
 */
public final class Expression {
    private static final int MAX_DEPTH = 20;

    private final String s;
    private final Map<String, String> vars;
    private final int depth;
    private int pos;

    private Expression(String s, Map<String, String> vars, int depth) {
        this.s = s;
        this.vars = vars;
        this.depth = depth;
    }

    /**
     * Evaluates the given expression
     *
     * @param expr the expression
     * @param vars the variables, keys are lower case. Values are expressions.
     * @param def  the value returned if the expression is not valid
     * @return the value
     */
    public static long eval(String expr, Map<String, String> vars, long def) {
        return eval(expr, vars, def, 0);
    }

    private static long eval(String expr, Map<String, String> vars, long def, int depth) {
        if (expr == null || depth > MAX_DEPTH)
            return def;
        try {
            Expression e = new Expression(expr.trim(), vars, depth);
            long v = e.parseSum();
            e.skip();
            if (e.pos != e.s.length())
                return def;
            return v;
        } catch (RuntimeException e) {
            return def;
        }
    }

    private void skip() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos)))
            pos++;
    }

    private boolean is(String t) {
        skip();
        if (s.startsWith(t, pos)) {
            pos += t.length();
            return true;
        }
        return false;
    }

    private long parseSum() {
        long v = parseProduct();
        while (true) {
            if (is("+")) v += parseProduct();
            else if (is("-")) v -= parseProduct();
            else return v;
        }
    }

    private long parseProduct() {
        long v = parsePower();
        while (true) {
            if (is("*") && !s.startsWith("*", pos)) v *= parsePower();
            else if (is("/")) v /= parsePower();
            else return v;
        }
    }

    private long parsePower() {
        long v = parseUnary();
        if (is("**")) {
            long e = parsePower();
            long r = 1;
            for (int i = 0; i < e; i++)
                r *= v;
            return r;
        }
        return v;
    }

    private long parseUnary() {
        if (is("-"))
            return -parseUnary();
        if (is("+"))
            return parseUnary();
        return parseAtom();
    }

    private long parseAtom() {
        skip();
        if (is("(")) {
            long v = parseSum();
            if (!is(")"))
                throw new RuntimeException("missing )");
            return v;
        }
        int start = pos;
        if (pos < s.length() && Character.isDigit(s.charAt(pos))) {
            while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_' || s.charAt(pos) == '\''))
                pos++;
            return parseNumber(s.substring(start, pos).replace("_", ""));
        }
        while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_' || s.charAt(pos) == '$'))
            pos++;
        String id = s.substring(start, pos).toLowerCase(Locale.ROOT);
        if (id.isEmpty())
            throw new RuntimeException("unexpected character");
        if (id.equals("$clog2") || id.equals("log2")) {
            long v = parseAtom();
            int b = 0;
            while ((1L << b) < v)
                b++;
            return b;
        }
        if (vars != null && vars.containsKey(id))
            return eval(vars.get(id), vars, 0, depth + 1);
        throw new RuntimeException("unknown identifier " + id);
    }

    private static long parseNumber(String n) {
        int q = n.indexOf('\'');
        if (q >= 0) {
            String r = n.substring(q + 1).toLowerCase(Locale.ROOT);
            if (r.startsWith("s"))
                r = r.substring(1);
            switch (r.charAt(0)) {
                case 'h':
                    return Long.parseLong(r.substring(1), 16);
                case 'b':
                    return Long.parseLong(r.substring(1), 2);
                case 'o':
                    return Long.parseLong(r.substring(1), 8);
                default:
                    return Long.parseLong(r.substring(1));
            }
        }
        return Long.parseLong(n);
    }
}
