/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Parses Quartus node and bus names like "a", "a[7..0]", "a[3]", "a[3..0],b,GND".
 * A name is converted to a list of bit atoms. The first atom is the most significant bit.
 * Quartus names are case insensitive, so all atoms are lower case.
 */
public final class BusName {
    /**
     * Atom representing a constant one
     */
    public static final String VCC = "vcc";
    /**
     * Atom representing a constant zero
     */
    public static final String GND = "gnd";

    private final String name;
    private final List<String> atoms;
    private final String base;
    private final boolean simpleBus;

    private BusName(String name, List<String> atoms, String base, boolean simpleBus) {
        this.name = name;
        this.atoms = atoms;
        this.base = base;
        this.simpleBus = simpleBus;
    }

    /**
     * Parses the given name
     *
     * @param name the name
     * @return the bus name, never null. If the name is not parsable it is used as a single atom.
     */
    public static BusName parse(String name) {
        String n = name.trim();
        ArrayList<String> atoms = new ArrayList<>();
        List<String> parts = splitTopLevel(n);
        String base = null;
        boolean simpleBus = false;
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty())
                continue;
            int b = p.indexOf('[');
            if (b > 0 && p.endsWith("]")) {
                String bn = p.substring(0, b).trim();
                String range = p.substring(b + 1, p.length() - 1).trim();
                int[] r = parseRange(range);
                if (r == null) {
                    atoms.add(p.toLowerCase(Locale.ROOT));
                } else {
                    String lb = bn.toLowerCase(Locale.ROOT);
                    if (r[0] >= r[1])
                        for (int i = r[0]; i >= r[1]; i--)
                            atoms.add(lb + "[" + i + "]");
                    else
                        for (int i = r[0]; i <= r[1]; i++)
                            atoms.add(lb + "[" + i + "]");
                    if (parts.size() == 1) {
                        base = bn;
                        simpleBus = range.contains("..");
                    }
                }
            } else {
                atoms.add(p.toLowerCase(Locale.ROOT));
                if (parts.size() == 1)
                    base = p;
            }
        }
        if (base == null)
            base = n;
        return new BusName(n, Collections.unmodifiableList(atoms), base, simpleBus);
    }

    private static List<String> splitTopLevel(String n) {
        ArrayList<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(n.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(n.substring(start));
        return parts;
    }

    private static int[] parseRange(String range) {
        try {
            int p = range.indexOf("..");
            if (p < 0) {
                int v = Integer.parseInt(range.trim());
                return new int[]{v, v};
            }
            int a = Integer.parseInt(range.substring(0, p).trim());
            int b = Integer.parseInt(range.substring(p + 2).trim());
            return new int[]{a, b};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * @return the original name
     */
    public String getName() {
        return name;
    }

    /**
     * @return the bit atoms, the first atom is the msb
     */
    public List<String> getAtoms() {
        return atoms;
    }

    /**
     * @return the number of bits
     */
    public int getBits() {
        return Math.max(1, atoms.size());
    }

    /**
     * @return the base name, e.g. "a" for "a[7..0]"
     */
    public String getBase() {
        return base;
    }

    /**
     * @return true if this is a simple bus like a[7..0]
     */
    public boolean isSimpleBus() {
        return simpleBus;
    }

    /**
     * @return true if all atoms are constants
     */
    public boolean isConstant() {
        if (atoms.isEmpty())
            return false;
        for (String a : atoms)
            if (!a.equals(VCC) && !a.equals(GND))
                return false;
        return true;
    }

    /**
     * @return a canonical key representing the bits of this name
     */
    public String getKey() {
        return String.join(",", atoms);
    }

    @Override
    public String toString() {
        return name;
    }
}
