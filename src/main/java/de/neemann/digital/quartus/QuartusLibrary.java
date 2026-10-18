/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus;

import de.neemann.digital.core.basic.And;
import de.neemann.digital.core.basic.NAnd;
import de.neemann.digital.core.basic.NOr;
import de.neemann.digital.core.basic.Not;
import de.neemann.digital.core.basic.Or;
import de.neemann.digital.core.basic.XNOr;
import de.neemann.digital.core.basic.XOr;
import de.neemann.digital.core.element.ElementAttributes;
import de.neemann.digital.core.element.Keys;
import de.neemann.digital.core.io.Const;
import de.neemann.digital.core.wiring.Driver;
import de.neemann.digital.core.wiring.Splitter;
import de.neemann.digital.quartus.lib.QuartusPart;
import de.neemann.digital.quartus.lib.QuartusParts;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Knows the Quartus primitives and how they are mapped to Digital elements.
 */
public final class QuartusLibrary {
    private static final Pattern GATE = Pattern.compile("(B?)(AND|NAND|OR|NOR)(\\d+)");
    private static final String[] BUFFERS = {"WIRE", "LCELL", "GLOBAL", "SOFT", "CARRY", "CASCADE",
            "ROW_GLOBAL", "ALT_INBUF", "ALT_OUTBUF", "OPNDRN", "ALT_IOBUF", "CLKLOCK"};

    private QuartusLibrary() {
    }

    /**
     * The mapping of a Quartus primitive to a Digital element
     */
    public static final class Mapping {
        private final String element;
        private final ElementAttributes attributes;
        private final HashMap<String, String> ports = new HashMap<>();
        private final QuartusPart part;
        private boolean constant;

        private Mapping(String element, QuartusPart part) {
            this.element = element;
            this.part = part;
            attributes = new ElementAttributes();
        }

        private Mapping port(String quartus, String key) {
            ports.put(quartus.toUpperCase(Locale.ROOT), key);
            return this;
        }

        /**
         * @return the name of the Digital element
         */
        public String getElement() {
            return element;
        }

        /**
         * @return the attributes of the Digital element
         */
        public ElementAttributes getAttributes() {
            return attributes;
        }

        /**
         * Returns the Digital pin key (including the direction prefix) of the given Quartus port.
         *
         * @param quartusPort the Quartus port
         * @return the key or null if not found
         */
        public String getPinKey(String quartusPort) {
            return ports.get(quartusPort.trim().toUpperCase(Locale.ROOT));
        }

        /**
         * @return the native Quartus part or null if mapped to a Digital element
         */
        public QuartusPart getPart() {
            return part;
        }

        /**
         * @return true if this is a constant (VCC or GND)
         */
        public boolean isConstant() {
            return constant;
        }

        /**
         * @return the port map
         */
        public Map<String, String> getPorts() {
            return ports;
        }
    }

    /**
     * Returns true if the given name is a megafunction which is simulated natively
     *
     * @param name the name
     * @return true if it is a supported megafunction
     */
    public static boolean isMegafunction(String name) {
        QuartusPart p = QuartusParts.get(name);
        return p != null && QuartusParts.getMegafunctions().contains(p);
    }

    /**
     * Returns the mapping of a Quartus primitive or a natively simulated component.
     *
     * @param symbolName the name of the symbol
     * @return the mapping or null if this is not a primitive
     */
    public static Mapping map(String symbolName) {
        String n = symbolName.trim().toUpperCase(Locale.ROOT);
        Matcher m = GATE.matcher(n);
        if (m.matches()) {
            int inputs = Integer.parseInt(m.group(3));
            if (inputs < 2 || inputs > 16)
                return null;
            boolean inverted = !m.group(1).isEmpty();
            String elem;
            switch (m.group(2)) {
                case "AND":
                    elem = inverted ? NOr.DESCRIPTION.getName() : And.DESCRIPTION.getName();
                    break;
                case "NAND":
                    elem = inverted ? Or.DESCRIPTION.getName() : NAnd.DESCRIPTION.getName();
                    break;
                case "OR":
                    elem = inverted ? NAnd.DESCRIPTION.getName() : Or.DESCRIPTION.getName();
                    break;
                default:
                    elem = inverted ? And.DESCRIPTION.getName() : NOr.DESCRIPTION.getName();
                    break;
            }
            return gate(elem, inputs);
        }
        switch (n) {
            case "XOR":
                return gate(XOr.DESCRIPTION.getName(), 2);
            case "XNOR":
                return gate(XNOr.DESCRIPTION.getName(), 2);
            case "NOT":
            case "EXP":
                return new Mapping(Not.DESCRIPTION.getName(), null)
                        .port("IN", ShapeBuilder.IN + "in")
                        .port("OUT", ShapeBuilder.OUT + "out");
            case "TRI":
                return new Mapping(Driver.DESCRIPTION.getName(), null)
                        .port("IN", ShapeBuilder.IN + "in")
                        .port("OE", ShapeBuilder.IN + "sel")
                        .port("OUT", ShapeBuilder.OUT + "out");
            case "VCC":
            case "GND":
                Mapping c = new Mapping(Const.DESCRIPTION.getName(), null);
                c.constant = true;
                c.attributes.set(Keys.VALUE, n.equals("VCC") ? 1L : 0L);
                return c;
            default:
                break;
        }
        for (String b : BUFFERS)
            if (b.equals(n)) {
                Mapping s = new Mapping(Splitter.DESCRIPTION.getName(), null)
                        .port("IN", ShapeBuilder.IN + "0")
                        .port("OUT", ShapeBuilder.OUT + "0");
                s.attributes.set(Keys.INPUT_SPLIT, "1").set(Keys.OUTPUT_SPLIT, "1");
                return s;
            }

        QuartusPart part = QuartusParts.get(n);
        if (part != null) {
            Mapping p = new Mapping(part.getDigitalName(), part);
            for (QuartusPart.PinSpec s : part.getInputs())
                p.port(s.getName(), ShapeBuilder.IN + s.getName());
            for (QuartusPart.PinSpec s : part.getOutputs())
                p.port(s.getName(), ShapeBuilder.OUT + s.getName());
            return p;
        }
        return null;
    }

    private static Mapping gate(String elem, int inputs) {
        Mapping m = new Mapping(elem, null);
        m.attributes.set(Keys.INPUT_COUNT, inputs);
        for (int i = 1; i <= inputs; i++)
            m.port("IN" + i, ShapeBuilder.IN + "In_" + i);
        m.port("OUT", ShapeBuilder.OUT + "out");
        return m;
    }
}
