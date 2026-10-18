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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a Quartus project: the project file (qpf), the settings file (qsf) and
 * IP files (qip). It provides the top level entity, the design files and the pin assignments.
 */
public final class QuartusProject {
    private static final Pattern QIP_PATH = Pattern.compile("\\[\\s*file\\s+join\\s+\\$::quartus\\(qip_path\\)\\s+(.*?)\\]");

    private final File projectDir;
    private String name;
    private String topLevelEntity;
    private String family = "";
    private String device = "";
    private final List<File> designFiles = new ArrayList<>();
    private final List<File> searchPaths = new ArrayList<>();
    private final Map<String, String> pinLocations = new LinkedHashMap<>();
    private final HashSet<File> readFiles = new HashSet<>();

    /**
     * Creates an empty project located in the given directory
     *
     * @param projectDir the directory
     */
    public QuartusProject(File projectDir) {
        this.projectDir = projectDir;
    }

    /**
     * Opens a Quartus project.
     *
     * @param file a qpf or a qsf file
     * @return the project
     * @throws IOException IOException
     */
    public static QuartusProject open(File file) throws IOException {
        File f = file.getAbsoluteFile();
        File dir = f.getParentFile();
        QuartusProject p = new QuartusProject(dir);
        String fn = f.getName().toLowerCase(Locale.ROOT);
        String stem = stem(f.getName());
        p.name = stem;
        if (fn.endsWith(".qpf")) {
            File qsf = null;
            for (String rev : readRevisions(f)) {
                File c = new File(dir, rev + ".qsf");
                if (c.exists()) {
                    qsf = c;
                    break;
                }
            }
            if (qsf == null) {
                File c = new File(dir, stem + ".qsf");
                if (c.exists())
                    qsf = c;
            }
            if (qsf == null) {
                File[] list = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".qsf"));
                if (list != null && list.length > 0)
                    qsf = list[0];
            }
            if (qsf != null)
                p.readSettings(qsf);
        } else
            p.readSettings(f);

        if (p.topLevelEntity == null)
            p.topLevelEntity = stem;
        return p;
    }

    private static List<String> readRevisions(File qpf) throws IOException {
        ArrayList<String> revs = new ArrayList<>();
        for (String l : Files.readAllLines(qpf.toPath(), StandardCharsets.ISO_8859_1)) {
            String line = l.trim();
            if (line.startsWith("PROJECT_REVISION")) {
                int e = line.indexOf('=');
                if (e > 0)
                    revs.add(unquote(line.substring(e + 1).trim()));
            }
        }
        return revs;
    }

    /**
     * Reads a settings file (qsf) or an ip file (qip)
     *
     * @param file the file
     * @throws IOException IOException
     */
    public void readSettings(File file) throws IOException {
        File f = file.getAbsoluteFile();
        if (!readFiles.add(f))
            return;
        File base = f.getParentFile();
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.ISO_8859_1);
        for (String l : text.split("\n")) {
            String line = l.trim();
            if (line.isEmpty() || line.startsWith("#"))
                continue;
            Matcher qm = QIP_PATH.matcher(line);
            if (qm.find())
                line = line.substring(0, qm.start()) + qm.group(1).trim() + line.substring(qm.end());
            List<String> tok = tokenize(line);
            if (tok.isEmpty())
                continue;
            String cmd = tok.get(0);
            String n = option(tok, "-name");
            if (cmd.equals("set_global_assignment") && n != null) {
                String value = lastValue(tok);
                handleGlobal(n.toUpperCase(Locale.ROOT), value, base);
            } else if (cmd.equals("set_location_assignment")) {
                String to = option(tok, "-to");
                if (to != null && tok.size() > 1) {
                    String loc = tok.get(1);
                    if (loc.toUpperCase(Locale.ROOT).startsWith("PIN_"))
                        loc = loc.substring(4);
                    pinLocations.put(to.toLowerCase(Locale.ROOT), loc);
                }
            }
        }
    }

    private void handleGlobal(String n, String value, File base) throws IOException {
        if (value == null)
            return;
        switch (n) {
            case "TOP_LEVEL_ENTITY":
                topLevelEntity = value;
                break;
            case "FAMILY":
                family = value;
                break;
            case "DEVICE":
                device = value;
                break;
            case "BDF_FILE":
            case "VHDL_FILE":
            case "VERILOG_FILE":
            case "SYSTEMVERILOG_FILE":
            case "AHDL_FILE":
            case "GDF_FILE":
            case "SOURCE_FILE":
            case "MISC_FILE":
            case "EDIF_FILE":
            case "BSF_FILE":
                File df = resolve(base, value);
                if (!designFiles.contains(df))
                    designFiles.add(df);
                break;
            case "QIP_FILE":
            case "SIP_FILE":
                File qip = resolve(base, value);
                if (qip.exists())
                    readSettings(qip);
                else {
                    // the qip is missing, but the HDL file may be present
                    for (String ext : new String[]{".vhd", ".v", ".bdf"}) {
                        File h = new File(qip.getParentFile(), stem(qip.getName()) + ext);
                        if (h.exists() && !designFiles.contains(h))
                            designFiles.add(h);
                    }
                }
                break;
            case "SEARCH_PATH":
            case "USER_LIBRARIES":
                for (String p : value.split(";")) {
                    if (!p.trim().isEmpty()) {
                        File sp = resolve(base, p.trim());
                        if (!searchPaths.contains(sp))
                            searchPaths.add(sp);
                    }
                }
                break;
            default:
                break;
        }
    }

    private static File resolve(File base, String value) {
        String v = value.replace('\\', '/');
        File f = new File(v);
        if (!f.isAbsolute())
            f = new File(base, v);
        try {
            return f.getCanonicalFile();
        } catch (IOException e) {
            return f.getAbsoluteFile();
        }
    }

    private static String option(List<String> tok, String opt) {
        for (int i = 0; i < tok.size() - 1; i++)
            if (tok.get(i).equals(opt))
                return tok.get(i + 1);
        return null;
    }

    /*
     * the value of an assignment is the first token which is not an option or an option value
     */
    private static String lastValue(List<String> tok) {
        int i = 1;
        while (i < tok.size()) {
            String t = tok.get(i);
            if (t.startsWith("-") && t.length() > 1 && Character.isLetter(t.charAt(1)))
                i += 2; // skip option and its value
            else
                return t;
        }
        return null;
    }

    static List<String> tokenize(String line) {
        ArrayList<String> tok = new ArrayList<>();
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '"') {
                int e = i + 1;
                StringBuilder sb = new StringBuilder();
                while (e < line.length() && line.charAt(e) != '"') {
                    if (line.charAt(e) == '\\' && e + 1 < line.length())
                        e++;
                    sb.append(line.charAt(e));
                    e++;
                }
                tok.add(sb.toString());
                i = e + 1;
            } else if (c == '{') {
                int e = line.indexOf('}', i);
                if (e < 0) e = line.length();
                tok.add(line.substring(i + 1, e));
                i = e + 1;
            } else {
                int e = i;
                while (e < line.length() && !Character.isWhitespace(line.charAt(e)))
                    e++;
                tok.add(line.substring(i, e));
                i = e;
            }
        }
        return tok;
    }

    private static String unquote(String s) {
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2)
            return s.substring(1, s.length() - 1);
        return s;
    }

    /**
     * Returns the name of a file without its extension
     *
     * @param name the file name
     * @return the stem
     */
    public static String stem(String name) {
        int p = name.lastIndexOf('.');
        if (p > 0)
            return name.substring(0, p);
        return name;
    }

    /**
     * @return the projects directory
     */
    public File getProjectDir() {
        return projectDir;
    }

    /**
     * @return the projects name
     */
    public String getName() {
        return name;
    }

    /**
     * @return the top level entity
     */
    public String getTopLevelEntity() {
        return topLevelEntity;
    }

    /**
     * Sets the top level entity
     *
     * @param topLevelEntity the top level entity
     */
    public void setTopLevelEntity(String topLevelEntity) {
        this.topLevelEntity = topLevelEntity;
    }

    /**
     * @return the device family
     */
    public String getFamily() {
        return family;
    }

    /**
     * @return the device
     */
    public String getDevice() {
        return device;
    }

    /**
     * @return the design files listed in the project
     */
    public List<File> getDesignFiles() {
        return designFiles;
    }

    /**
     * @return the search paths
     */
    public List<File> getSearchPaths() {
        return searchPaths;
    }

    /**
     * Adds a search path
     *
     * @param dir the directory
     */
    public void addSearchPath(File dir) {
        if (!searchPaths.contains(dir))
            searchPaths.add(dir);
    }

    /**
     * Returns the pin location of the given node
     *
     * @param node the node name
     * @return the location like "G21" or null
     */
    public String getPinLocation(String node) {
        return pinLocations.get(node.toLowerCase(Locale.ROOT));
    }
}
