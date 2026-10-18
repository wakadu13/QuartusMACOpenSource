/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Describes a VHDL entity or a Verilog module found in a HDL file.
 * Only the interface is extracted. If the design unit is a wrapper of a
 * Quartus megafunction (as created by the MegaWizard plug-in manager)
 * the megafunction and its parameters are also extracted.
 */
public final class HdlEntity {
    private static final Pattern VHDL_ENTITY = Pattern.compile(
            "\\bentity\\s+(\\w+)\\s+is\\b(.*?)\\bend\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern VERILOG_MODULE = Pattern.compile(
            "\\bmodule\\s+(\\w+)\\s*(#\\s*\\((.*?)\\)\\s*)?\\((.*?)\\)\\s*;(.*?)\\bendmodule\\b",
            Pattern.DOTALL);
    private static final Pattern VHDL_COMPONENT_INST = Pattern.compile(
            "(\\w+)\\s*:\\s*(?:component\\s+)?(?:entity\\s+\\w+\\.)?(\\w+)\\s*(generic\\s+map\\s*\\((.*?)\\)\\s*)?port\\s+map\\s*\\((.*?)\\)\\s*;",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern VERILOG_INST = Pattern.compile(
            "\\b(\\w+)\\s+(?:#\\s*\\((.*?)\\)\\s*)?(\\w+)\\s*\\((\\s*\\..*?)\\)\\s*;", Pattern.DOTALL);
    private static final Pattern VERILOG_DEFPARAM = Pattern.compile(
            "\\bdefparam\\s+(\\w+)\\.(\\w+)\\s*=\\s*(.*?);", Pattern.DOTALL);

    /**
     * The language of the file
     */
    public enum Language {
        /**
         * VHDL
         */
        VHDL,
        /**
         * Verilog
         */
        VERILOG
    }

    /**
     * A port of an entity
     */
    public static final class Port {
        /**
         * the name
         */
        public final String name;
        /**
         * the direction
         */
        public final BdfFile.Direction direction;
        /**
         * the number of bits
         */
        public final int bits;
        /**
         * true if the port is a vector, also if bits==1
         */
        public final boolean vector;
        /**
         * true if the default value is one, false otherwise
         */
        public final boolean defaultHigh;

        private Port(String name, BdfFile.Direction direction, int bits, boolean vector, boolean defaultHigh) {
            this.name = name;
            this.direction = direction;
            this.bits = bits;
            this.vector = vector;
            this.defaultHigh = defaultHigh;
        }

        @Override
        public String toString() {
            return name + ":" + bits + " " + direction;
        }
    }

    private final String name;
    private final Language language;
    private final File file;
    private final List<Port> ports;
    private final Map<String, String> generics;
    private String megafunction;
    private final Map<String, String> megafunctionParams = new LinkedHashMap<>();
    private final Map<String, String> megafunctionPortMap = new LinkedHashMap<>();

    private HdlEntity(String name, Language language, File file, List<Port> ports, Map<String, String> generics) {
        this.name = name;
        this.language = language;
        this.file = file;
        this.ports = ports;
        this.generics = generics;
    }

    /**
     * @return the name of the entity or module
     */
    public String getName() {
        return name;
    }

    /**
     * @return the language
     */
    public Language getLanguage() {
        return language;
    }

    /**
     * @return the file containing the entity
     */
    public File getFile() {
        return file;
    }

    /**
     * @return the ports
     */
    public List<Port> getPorts() {
        return ports;
    }

    /**
     * @return the generics or parameters with their default values
     */
    public Map<String, String> getGenerics() {
        return generics;
    }

    /**
     * @return the name of the wrapped megafunction in lower case or null
     */
    public String getMegafunction() {
        return megafunction;
    }

    /**
     * @return the parameters of the megafunction, keys in upper case
     */
    public Map<String, String> getMegafunctionParams() {
        return megafunctionParams;
    }

    /**
     * @return the port map of the megafunction: megafunction port (lower case) to entity signal
     */
    public Map<String, String> getMegafunctionPortMap() {
        return megafunctionPortMap;
    }

    @Override
    public String toString() {
        return name + ports;
    }

    /**
     * Reads all entities or modules found in the given file
     *
     * @param f the file
     * @return the entities
     * @throws IOException IOException
     */
    public static List<HdlEntity> read(File f) throws IOException {
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.ISO_8859_1);
        String n = f.getName().toLowerCase(Locale.ROOT);
        if (n.endsWith(".v") || n.endsWith(".sv") || n.endsWith(".vh") || n.endsWith(".svh"))
            return parseVerilog(text, f);
        return parseVHDL(text, f);
    }

    /**
     * Parses VHDL code
     *
     * @param text the code
     * @param f    the file, maybe null
     * @return the entities
     */
    public static List<HdlEntity> parseVHDL(String text, File f) {
        String code = text.replaceAll("--[^\n]*", " ");
        ArrayList<HdlEntity> list = new ArrayList<>();
        Matcher m = VHDL_ENTITY.matcher(code);
        while (m.find()) {
            String name = m.group(1);
            // the entity ends at "end [entity] name;" but the regex stops at the first "end"
            int start = m.start(2);
            int end = findVhdlEntityEnd(code, start, name);
            String body = code.substring(start, end);
            LinkedHashMap<String, String> generics = new LinkedHashMap<>();
            String gen = extractParenBlock(body, "generic");
            if (gen != null)
                for (String decl : splitTop(gen, ';')) {
                    int c = decl.indexOf(':');
                    if (c < 0) continue;
                    String names = decl.substring(0, c);
                    String rest = decl.substring(c + 1);
                    int d = rest.indexOf(":=");
                    String def = d < 0 ? "" : rest.substring(d + 2).trim();
                    for (String gn : names.split(","))
                        generics.put(gn.trim().toLowerCase(Locale.ROOT), def);
                }
            ArrayList<Port> ports = new ArrayList<>();
            String portBlock = extractParenBlock(body, "port");
            if (portBlock != null)
                for (String decl : splitTop(portBlock, ';')) {
                    int c = decl.indexOf(':');
                    if (c < 0) continue;
                    String names = decl.substring(0, c);
                    String rest = decl.substring(c + 1).trim();
                    String[] w = rest.split("\\s+", 2);
                    BdfFile.Direction dir;
                    switch (w[0].toLowerCase(Locale.ROOT)) {
                        case "out":
                        case "buffer":
                            dir = BdfFile.Direction.OUTPUT;
                            break;
                        case "inout":
                            dir = BdfFile.Direction.BIDIR;
                            break;
                        default:
                            dir = BdfFile.Direction.INPUT;
                    }
                    String type = w.length > 1 ? w[1] : "";
                    String def = "";
                    int d = type.indexOf(":=");
                    if (d >= 0) {
                        def = type.substring(d + 2).trim();
                        type = type.substring(0, d);
                    }
                    int bits = 1;
                    boolean vector = false;
                    int p = type.indexOf('(');
                    if (p >= 0) {
                        int q = type.lastIndexOf(')');
                        if (q > p) {
                            bits = vhdlRangeWidth(type.substring(p + 1, q), generics);
                            vector = true;
                        }
                    } else if (type.toLowerCase(Locale.ROOT).contains("integer")) {
                        bits = integerRangeWidth(type, generics);
                        vector = true;
                    }
                    boolean defHigh = def.contains("'1'") || def.toLowerCase(Locale.ROOT).contains("others=>'1'")
                            || def.toLowerCase(Locale.ROOT).replace(" ", "").contains("others=>'1'");
                    for (String pn : names.split(","))
                        if (!pn.trim().isEmpty())
                            ports.add(new Port(pn.trim(), dir, bits, vector, defHigh));
                }
            HdlEntity e = new HdlEntity(name, Language.VHDL, f, ports, generics);
            findVhdlMegafunction(code, e);
            list.add(e);
        }
        return list;
    }

    private static int findVhdlEntityEnd(String code, int start, String name) {
        Pattern p = Pattern.compile("\\bend\\s*(entity)?\\s*(" + Pattern.quote(name) + ")?\\s*;", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(code);
        int pos = start;
        while (m.find(pos)) {
            // skip an 'end' which belongs to a nested parenthesis, e.g. a comment
            if (balanced(code.substring(start, m.start())))
                return m.start();
            pos = m.end();
        }
        return code.length();
    }

    private static boolean balanced(String s) {
        int d = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') d++;
            else if (c == ')') d--;
        }
        return d == 0;
    }

    private static void findVhdlMegafunction(String code, HdlEntity e) {
        Matcher m = VHDL_COMPONENT_INST.matcher(code);
        while (m.find()) {
            String comp = m.group(2).toLowerCase(Locale.ROOT);
            if (QuartusLibrary.isMegafunction(comp)) {
                e.megafunction = comp;
                if (m.group(4) != null)
                    for (String a : splitTop(m.group(4), ','))
                        addAssoc(a, e.megafunctionParams, true);
                for (String a : splitTop(m.group(5), ','))
                    addAssoc(a, e.megafunctionPortMap, false);
                resolveSubWires(code, e.megafunctionPortMap, true);
                return;
            }
        }
    }

    private static void addAssoc(String a, Map<String, String> map, boolean upperKey) {
        int p = a.indexOf("=>");
        if (p < 0)
            return;
        String k = a.substring(0, p).trim().toLowerCase(Locale.ROOT);
        if (upperKey)
            k = k.toUpperCase(Locale.ROOT);
        String v = a.substring(p + 2).trim();
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2)
            v = v.substring(1, v.length() - 1);
        map.put(k, v);
    }

    /*
     * The MegaWizard connects the megafunction ports via signals called sub_wireN.
     * Here the sub wires are replaced by the entity ports they are connected to.
     */
    private static void resolveSubWires(String code, Map<String, String> portMap, boolean vhdl) {
        HashMap<String, String> assign = new HashMap<>();
        Pattern ap = vhdl
                ? Pattern.compile("(\\w+)\\s*(\\([^;]*?\\))?\\s*<=\\s*(\\w+)\\s*(\\([^;]*?\\))?\\s*;")
                : Pattern.compile("\\bassign\\s+(\\w+)\\s*(\\[[^;]*?\\])?\\s*=\\s*(\\w+)\\s*(\\[[^;]*?\\])?\\s*;");
        Matcher m = ap.matcher(code);
        while (m.find()) {
            String lhs = m.group(1);
            String rhs = m.group(3);
            assign.put(rhs.toLowerCase(Locale.ROOT), lhs);
            assign.put(lhs.toLowerCase(Locale.ROOT), rhs);
        }
        for (Map.Entry<String, String> en : portMap.entrySet()) {
            String v = en.getValue();
            String base = v.replaceAll("[\\(\\[].*", "").trim();
            if (base.toLowerCase(Locale.ROOT).startsWith("sub_wire")) {
                String r = assign.get(base.toLowerCase(Locale.ROOT));
                if (r != null)
                    en.setValue(r);
            }
        }
    }

    /**
     * Parses Verilog code
     *
     * @param text the code
     * @param f    the file, maybe null
     * @return the modules
     */
    public static List<HdlEntity> parseVerilog(String text, File f) {
        String code = text.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
        ArrayList<HdlEntity> list = new ArrayList<>();
        Matcher m = VERILOG_MODULE.matcher(code);
        while (m.find()) {
            String name = m.group(1);
            LinkedHashMap<String, String> params = new LinkedHashMap<>();
            if (m.group(3) != null)
                for (String p : splitTop(m.group(3), ','))
                    addVerilogParam(p, params);
            String body = m.group(5);
            Matcher pm = Pattern.compile("\\b(?:parameter|localparam)\\b([^;]*);").matcher(body);
            while (pm.find())
                for (String p : splitTop(pm.group(1), ','))
                    addVerilogParam(p, params);

            ArrayList<Port> ports = new ArrayList<>();
            String header = m.group(4);
            List<String> headerItems = splitTop(header, ',');
            boolean ansi = false;
            for (String h : headerItems)
                if (h.trim().matches("(?s)^(input|output|inout)\\b.*"))
                    ansi = true;
            if (ansi) {
                BdfFile.Direction dir = BdfFile.Direction.INPUT;
                String range = null;
                for (String h : headerItems) {
                    String s = h.trim();
                    Matcher dm = Pattern.compile("(?s)^(input|output|inout)\\b(.*)").matcher(s);
                    if (dm.matches()) {
                        dir = verilogDir(dm.group(1));
                        s = dm.group(2).trim();
                        range = null;
                    }
                    s = s.replaceAll("\\b(wire|reg|logic|signed|unsigned|tri|var)\\b", " ").trim();
                    Matcher rm = Pattern.compile("(?s)^\\[(.*?)\\]\\s*(.*)").matcher(s);
                    if (rm.matches()) {
                        range = rm.group(1);
                        s = rm.group(2).trim();
                    }
                    String pn = s.replaceAll("[=\\[].*", "").trim();
                    if (!pn.isEmpty())
                        ports.add(new Port(pn, dir, verilogWidth(range, params), range != null, false));
                }
            } else {
                HashMap<String, Port> decl = new HashMap<>();
                Matcher dm = Pattern.compile("\\b(input|output|inout)\\b([^;]*);").matcher(body);
                while (dm.find()) {
                    BdfFile.Direction dir = verilogDir(dm.group(1));
                    String s = dm.group(2).replaceAll("\\b(wire|reg|logic|signed|unsigned|tri)\\b", " ").trim();
                    String range = null;
                    Matcher rm = Pattern.compile("(?s)^\\[(.*?)\\]\\s*(.*)").matcher(s);
                    if (rm.matches()) {
                        range = rm.group(1);
                        s = rm.group(2);
                    }
                    for (String pn : s.split(",")) {
                        String n = pn.replaceAll("=.*", "").trim();
                        if (!n.isEmpty())
                            decl.put(n, new Port(n, dir, verilogWidth(range, params), range != null, false));
                    }
                }
                for (String h : headerItems) {
                    Port p = decl.get(h.trim());
                    if (p != null)
                        ports.add(p);
                }
            }
            HdlEntity e = new HdlEntity(name, Language.VERILOG, f, ports, params);
            findVerilogMegafunction(body, e);
            list.add(e);
        }
        return list;
    }

    private static void findVerilogMegafunction(String body, HdlEntity e) {
        Matcher m = VERILOG_INST.matcher(body);
        while (m.find()) {
            String comp = m.group(1).toLowerCase(Locale.ROOT);
            if (QuartusLibrary.isMegafunction(comp)) {
                e.megafunction = comp;
                String inst = m.group(3);
                if (m.group(2) != null)
                    for (String a : splitTop(m.group(2), ','))
                        addVerilogNamedAssoc(a, e.megafunctionParams, true);
                for (String a : splitTop(m.group(4), ','))
                    addVerilogNamedAssoc(a, e.megafunctionPortMap, false);
                Matcher dm = VERILOG_DEFPARAM.matcher(body);
                while (dm.find())
                    if (dm.group(1).equals(inst))
                        e.megafunctionParams.put(dm.group(2).toUpperCase(Locale.ROOT), unquote(dm.group(3).trim()));
                resolveSubWires(body, e.megafunctionPortMap, false);
                return;
            }
        }
    }

    private static void addVerilogNamedAssoc(String a, Map<String, String> map, boolean upperKey) {
        Matcher m = Pattern.compile("(?s)\\s*\\.(\\w+)\\s*\\((.*)\\)\\s*").matcher(a);
        if (m.matches()) {
            String k = m.group(1).toLowerCase(Locale.ROOT);
            if (upperKey)
                k = k.toUpperCase(Locale.ROOT);
            map.put(k, unquote(m.group(2).trim()));
        }
    }

    private static String unquote(String v) {
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2)
            return v.substring(1, v.length() - 1);
        return v;
    }

    private static void addVerilogParam(String p, Map<String, String> params) {
        String s = p.replaceAll("\\b(parameter|localparam|integer|signed)\\b", " ").replaceAll("\\[.*?\\]", " ").trim();
        int e = s.indexOf('=');
        if (e > 0)
            params.put(s.substring(0, e).trim().toLowerCase(Locale.ROOT), s.substring(e + 1).trim());
    }

    private static BdfFile.Direction verilogDir(String d) {
        switch (d) {
            case "output":
                return BdfFile.Direction.OUTPUT;
            case "inout":
                return BdfFile.Direction.BIDIR;
            default:
                return BdfFile.Direction.INPUT;
        }
    }

    private static int verilogWidth(String range, Map<String, String> params) {
        if (range == null)
            return 1;
        int c = range.indexOf(':');
        if (c < 0)
            return 1;
        long a = Expression.eval(range.substring(0, c), params, 0);
        long b = Expression.eval(range.substring(c + 1), params, 0);
        return (int) Math.max(1, Math.abs(a - b) + 1);
    }

    private static int vhdlRangeWidth(String range, Map<String, String> generics) {
        String r = range.trim();
        Matcher m = Pattern.compile("(?is)(.*)\\b(downto|to)\\b(.*)").matcher(r);
        if (!m.matches())
            return 1;
        long a = Expression.eval(m.group(1), generics, 0);
        long b = Expression.eval(m.group(3), generics, 0);
        return (int) Math.max(1, Math.abs(a - b) + 1);
    }

    private static int integerRangeWidth(String type, Map<String, String> generics) {
        Matcher m = Pattern.compile("(?is).*\\brange\\b(.*)\\b(to|downto)\\b(.*)").matcher(type);
        if (!m.matches())
            return 32;
        long a = Expression.eval(m.group(1), generics, 0);
        long b = Expression.eval(m.group(3), generics, 0);
        long max = Math.max(Math.abs(a), Math.abs(b));
        int bits = 1;
        while ((1L << bits) <= max && bits < 63)
            bits++;
        return bits;
    }

    private static String extractParenBlock(String body, String keyword) {
        Matcher m = Pattern.compile("(?i)\\b" + keyword + "\\s*\\(").matcher(body);
        if (!m.find())
            return null;
        int start = m.end();
        int depth = 1;
        for (int i = start; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0)
                    return body.substring(start, i);
            }
        }
        return null;
    }

    static List<String> splitTop(String s, char sep) {
        ArrayList<String> l = new ArrayList<>();
        int depth = 0;
        int start = 0;
        boolean inString = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') inString = !inString;
            if (inString) continue;
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == sep && depth == 0) {
                l.add(s.substring(start, i));
                start = i + 1;
            }
        }
        if (start < s.length() && !s.substring(start).trim().isEmpty())
            l.add(s.substring(start));
        return l;
    }
}
