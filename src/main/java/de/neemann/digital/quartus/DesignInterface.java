/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * The interface of a design unit: the ports seen from the outside.
 * Every Quartus port gets a Digital pin name. Usually this is the base name
 * of the port, so "c[1..7]" becomes "c".
 */
public final class DesignInterface {

    /**
     * The kind of the design unit
     */
    public enum Kind {
        /**
         * a block design file
         */
        BDF,
        /**
         * a VHDL or Verilog design simulated by an external simulator
         */
        HDL,
        /**
         * a wrapper of a natively supported megafunction
         */
        MEGAFUNCTION,
        /**
         * the source is missing or not supported
         */
        BLACKBOX
    }

    /**
     * A port of the design unit
     */
    public static final class Port {
        private final String quartusName;
        private final BdfFile.Direction direction;
        private final int bits;
        private final boolean defaultHigh;
        private String label;

        /**
         * Creates a new port
         *
         * @param quartusName the name used by Quartus
         * @param direction   the direction
         * @param bits        the number of bits
         * @param defaultHigh true if the default value of an unconnected input is one
         */
        public Port(String quartusName, BdfFile.Direction direction, int bits, boolean defaultHigh) {
            this.quartusName = quartusName;
            this.direction = direction;
            this.bits = bits;
            this.defaultHigh = defaultHigh;
        }

        /**
         * @return the name used by Quartus
         */
        public String getQuartusName() {
            return quartusName;
        }

        /**
         * @return the name of the Digital pin
         */
        public String getLabel() {
            return label;
        }

        /**
         * @return the direction
         */
        public BdfFile.Direction getDirection() {
            return direction;
        }

        /**
         * @return true if this port is an output
         */
        public boolean isOutput() {
            return direction == BdfFile.Direction.OUTPUT;
        }

        /**
         * @return the number of bits
         */
        public int getBits() {
            return bits;
        }

        /**
         * @return true if the default value of an unconnected input is one
         */
        public boolean isDefaultHigh() {
            return defaultHigh;
        }

        @Override
        public String toString() {
            return quartusName + "->" + label + ":" + bits;
        }
    }

    private final String name;
    private final Kind kind;
    private final ArrayList<Port> ports = new ArrayList<>();
    private final HashMap<String, Port> byKey = new HashMap<>();
    private String info = "";

    /**
     * Creates a new interface
     *
     * @param name  the name of the design unit
     * @param kind  the kind of the unit
     * @param ports the ports
     */
    public DesignInterface(String name, Kind kind, List<Port> ports) {
        this.name = name;
        this.kind = kind;
        HashMap<String, Integer> baseCount = new HashMap<>();
        for (Port p : ports)
            baseCount.merge(key(BusName.parse(p.quartusName).getBase()), 1, Integer::sum);
        ArrayList<String> used = new ArrayList<>();
        for (Port p : ports) {
            String base = BusName.parse(p.quartusName).getBase();
            String l;
            if (baseCount.get(key(base)) == 1)
                l = sanitize(base);
            else
                l = sanitize(p.quartusName);
            String unique = l;
            int n = 1;
            while (used.contains(key(unique)))
                unique = l + "_" + (n++);
            used.add(key(unique));
            p.label = unique;
            this.ports.add(p);
            byKey.put(key(p.quartusName), p);
            byKey.putIfAbsent(key(BusName.parse(p.quartusName).getBase()), p);
            byKey.putIfAbsent(key(unique), p);
        }
    }

    private static String key(String s) {
        return s.trim().toLowerCase(Locale.ROOT).replace(" ", "");
    }

    /**
     * Creates a valid Digital pin name
     *
     * @param n the name
     * @return the sanitized name
     */
    public static String sanitize(String n) {
        String s = n.trim().replace("..", "_").replace('[', '_').replace(']', ' ').trim().replace(',', '_');
        s = s.replaceAll("[^A-Za-z0-9_~/\\-+]", "_");
        if (s.isEmpty())
            s = "p";
        return s;
    }

    /**
     * @return the name of the design unit
     */
    public String getName() {
        return name;
    }

    /**
     * @return the kind of the unit
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * @return the ports
     */
    public List<Port> getPorts() {
        return ports;
    }

    /**
     * Finds the port matching the given name of a symbol port
     *
     * @param quartusName the name used in the symbol
     * @return the port or null
     */
    public Port find(String quartusName) {
        Port p = byKey.get(key(quartusName));
        if (p == null)
            p = byKey.get(key(BusName.parse(quartusName).getBase()));
        return p;
    }

    /**
     * @return additional information shown to the user
     */
    public String getInfo() {
        return info;
    }

    /**
     * Sets additional information
     *
     * @param info the information
     * @return this for chained calls
     */
    public DesignInterface setInfo(String info) {
        this.info = info;
        return this;
    }

    @Override
    public String toString() {
        return name + "(" + kind + ")" + ports;
    }
}
