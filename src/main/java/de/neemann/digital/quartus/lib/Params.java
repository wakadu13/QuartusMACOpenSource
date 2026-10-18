/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus.lib;

import de.neemann.digital.core.element.ElementAttributes;
import de.neemann.digital.core.element.Keys;
import de.neemann.digital.quartus.Expression;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The parameters of a Quartus component.
 * Stored as a string like "LPM_WIDTH=8;LPM_DIRECTION=UP" in the
 * attribute {@link Keys#QUARTUS_PARAMS}.
 * The special parameter PORTS contains the list of the used ports.
 */
public final class Params {
    /**
     * Name of the parameter which holds the list of used ports
     */
    public static final String PORTS = "PORTS";

    private final LinkedHashMap<String, String> map = new LinkedHashMap<>();

    /**
     * Creates empty parameters
     */
    public Params() {
    }

    /**
     * Creates parameters from the attributes
     *
     * @param attr the attributes
     */
    public Params(ElementAttributes attr) {
        this(attr.get(Keys.QUARTUS_PARAMS));
    }

    /**
     * Creates parameters from a string
     *
     * @param s the string
     */
    public Params(String s) {
        if (s != null)
            for (String p : s.split(";")) {
                int e = p.indexOf('=');
                if (e > 0)
                    map.put(p.substring(0, e).trim().toUpperCase(Locale.ROOT), p.substring(e + 1).trim());
            }
    }

    /**
     * Sets a parameter
     *
     * @param key   the key
     * @param value the value
     * @return this for chained calls
     */
    public Params set(String key, String value) {
        map.put(key.toUpperCase(Locale.ROOT), value);
        return this;
    }

    /**
     * Returns a parameter
     *
     * @param key the key
     * @param def the default value
     * @return the value
     */
    public String get(String key, String def) {
        String v = map.get(key.toUpperCase(Locale.ROOT));
        if (v == null || v.isEmpty())
            return def;
        return v;
    }

    /**
     * Returns an integer parameter
     *
     * @param key the key
     * @param def the default value
     * @return the value
     */
    public long getLong(String key, long def) {
        String v = map.get(key.toUpperCase(Locale.ROOT));
        if (v == null || v.isEmpty())
            return def;
        String t = v.trim();
        try {
            if (t.matches("(?i)[0-9a-f]+h"))
                return Long.parseLong(t.substring(0, t.length() - 1), 16);
            if (t.matches("(?i)[hH]\"[0-9a-f]+\""))
                return Long.parseLong(t.substring(2, t.length() - 1), 16);
            if (t.matches("(?i)[bB]\"[01]+\""))
                return Long.parseLong(t.substring(2, t.length() - 1), 2);
        } catch (NumberFormatException e) {
            return def;
        }
        return Expression.eval(t, null, def);
    }

    /**
     * Returns an integer parameter
     *
     * @param key the key
     * @param def the default value
     * @return the value
     */
    public int getInt(String key, int def) {
        return (int) getLong(key, def);
    }

    /**
     * Returns true if the given port is used.
     * If no port list is given, all ports are used.
     *
     * @param port the ports name
     * @return true if used
     */
    public boolean isPortUsed(String port) {
        String ports = map.get(PORTS);
        if (ports == null)
            return true;
        HashSet<String> set = new HashSet<>();
        for (String p : ports.split(","))
            set.add(p.trim().toUpperCase(Locale.ROOT));
        return set.contains(port.toUpperCase(Locale.ROOT));
    }

    /**
     * @return the parameter map
     */
    public Map<String, String> getMap() {
        return map;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 0)
                sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
