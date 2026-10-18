/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A node of a s-expression as used in the Quartus block design files (bdf)
 * and block symbol files (bsf).
 * A node has a name (the first atom) and a list of arguments. An argument is either
 * a {@link String} (a quoted string or an atom) or a nested {@link SExpr}.
 */
public final class SExpr {
    private final String name;
    private final ArrayList<Object> args;
    private final int line;

    private SExpr(String name, int line) {
        this.name = name;
        this.line = line;
        args = new ArrayList<>();
    }

    /**
     * @return the name of this node
     */
    public String getName() {
        return name;
    }

    /**
     * @return the line number of this node in the source file
     */
    public int getLine() {
        return line;
    }

    /**
     * @return all arguments
     */
    public List<Object> getArgs() {
        return Collections.unmodifiableList(args);
    }

    /**
     * @return all children which are nodes
     */
    public List<SExpr> getChildren() {
        ArrayList<SExpr> l = new ArrayList<>();
        for (Object o : args)
            if (o instanceof SExpr)
                l.add((SExpr) o);
        return l;
    }

    /**
     * Returns all children with the given name
     *
     * @param n the name
     * @return the children
     */
    public List<SExpr> getChildren(String n) {
        ArrayList<SExpr> l = new ArrayList<>();
        for (Object o : args)
            if (o instanceof SExpr && ((SExpr) o).name.equalsIgnoreCase(n))
                l.add((SExpr) o);
        return l;
    }

    /**
     * Returns the first child with the given name
     *
     * @param n the name
     * @return the child or null if not present
     */
    public SExpr get(String n) {
        for (Object o : args)
            if (o instanceof SExpr && ((SExpr) o).name.equalsIgnoreCase(n))
                return (SExpr) o;
        return null;
    }

    /**
     * Returns true if a child with the given name is present.
     * Flags like "(input)" or "(bus)" are stored as children without arguments.
     *
     * @param n the name
     * @return true if present
     */
    public boolean has(String n) {
        return get(n) != null;
    }

    /**
     * @return the string arguments of this node
     */
    public List<String> getStrings() {
        ArrayList<String> l = new ArrayList<>();
        for (Object o : args)
            if (o instanceof String)
                l.add((String) o);
        return l;
    }

    /**
     * Returns the n-th string argument
     *
     * @param n the index
     * @return the string or null if not present
     */
    public String getString(int n) {
        List<String> s = getStrings();
        if (n < s.size())
            return s.get(n);
        return null;
    }

    /**
     * Returns the n-th string argument as an integer
     *
     * @param n   the index
     * @param def the default value
     * @return the integer value
     */
    public int getInt(int n, int def) {
        String s = getString(n);
        if (s == null)
            return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            try {
                return (int) Math.round(Double.parseDouble(s.trim()));
            } catch (NumberFormatException e2) {
                return def;
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("(").append(name);
        for (Object o : args) {
            sb.append(' ');
            if (o instanceof String)
                sb.append('"').append(o).append('"');
            else
                sb.append(o);
        }
        return sb.append(')').toString();
    }

    /**
     * Parses the given text.
     * C-style comments are skipped.
     *
     * @param text the text to parse
     * @return the top level nodes
     * @throws IOException IOException
     */
    public static List<SExpr> parse(String text) throws IOException {
        return new Parser(text).parseAll();
    }

    private static final class Parser {
        private final String text;
        private int pos;
        private int line = 1;

        private Parser(String text) {
            this.text = text;
        }

        private List<SExpr> parseAll() throws IOException {
            ArrayList<SExpr> list = new ArrayList<>();
            while (true) {
                skipWhite();
                if (pos >= text.length())
                    return list;
                char c = text.charAt(pos);
                if (c == '(')
                    list.add(parseNode());
                else
                    throw new IOException("unexpected character '" + c + "' in line " + line);
            }
        }

        private SExpr parseNode() throws IOException {
            pos++; // skip '('
            skipWhite();
            int startLine = line;
            String n = readAtom();
            if (n.isEmpty())
                throw new IOException("node name expected in line " + line);
            SExpr node = new SExpr(n, startLine);
            while (true) {
                skipWhite();
                if (pos >= text.length())
                    throw new IOException("unexpected end of file in node '" + n + "' starting in line " + startLine);
                char c = text.charAt(pos);
                if (c == ')') {
                    pos++;
                    return node;
                } else if (c == '(')
                    node.args.add(parseNode());
                else if (c == '"')
                    node.args.add(readString());
                else {
                    String a = readAtom();
                    if (a.isEmpty())
                        throw new IOException("unexpected character '" + c + "' in line " + line);
                    node.args.add(a);
                }
            }
        }

        private String readString() throws IOException {
            pos++; // skip quote
            StringBuilder sb = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    // Quartus doubles a quote inside a string
                    if (pos < text.length() && text.charAt(pos) == '"') {
                        sb.append('"');
                        pos++;
                    } else
                        return sb.toString();
                } else if (c == '\\' && pos < text.length() && text.charAt(pos) == '"') {
                    sb.append('"');
                    pos++;
                } else {
                    if (c == '\n')
                        line++;
                    sb.append(c);
                }
            }
            throw new IOException("unterminated string in line " + line);
        }

        private String readAtom() {
            int start = pos;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (Character.isWhitespace(c) || c == '(' || c == ')' || c == '"')
                    break;
                pos++;
            }
            return text.substring(start, pos);
        }

        private void skipWhite() throws IOException {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == '\n') {
                    line++;
                    pos++;
                } else if (Character.isWhitespace(c) || c == '﻿')
                    pos++;
                else if (c == '/' && pos + 1 < text.length() && text.charAt(pos + 1) == '*') {
                    int e = text.indexOf("*/", pos + 2);
                    if (e < 0)
                        throw new IOException("unterminated comment in line " + line);
                    countLines(pos, e + 2);
                    pos = e + 2;
                } else if (c == '/' && pos + 1 < text.length() && text.charAt(pos + 1) == '/') {
                    while (pos < text.length() && text.charAt(pos) != '\n')
                        pos++;
                } else
                    return;
            }
        }

        private void countLines(int from, int to) {
            for (int i = from; i < to; i++)
                if (text.charAt(i) == '\n')
                    line++;
        }
    }
}
