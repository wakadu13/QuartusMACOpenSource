/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus.lib;

import de.neemann.digital.core.element.ElementAttributes;
import de.neemann.digital.core.element.ElementTypeDescription;
import de.neemann.digital.core.element.Keys;
import de.neemann.digital.core.element.PinDescription;
import de.neemann.digital.core.element.PinDescriptions;
import de.neemann.digital.core.element.PinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The specification of a Quartus component: its ports and its behaviour.
 * From this specification a Digital element type is created.
 */
public final class QuartusPart {
    /**
     * Prefix of the Digital element names
     */
    public static final String PREFIX = "Quartus_";

    /**
     * The behaviour of a component
     */
    public interface Behavior {
        /**
         * Calculates the new outputs.
         * Called every time an input changes.
         *
         * @param in  the input values in the order of the specification
         * @param out the outputs to set in the order of the specification
         */
        void eval(long[] in, long[] out);
    }

    /**
     * Specification of a port
     */
    public static final class PinSpec {
        private final String name;
        private final Function<Params, Integer> width;
        private final long def;
        private final boolean clock;

        private PinSpec(String name, Function<Params, Integer> width, long def, boolean clock) {
            this.name = name;
            this.width = width;
            this.def = def;
            this.clock = clock;
        }

        /**
         * @return the name
         */
        public String getName() {
            return name;
        }

        /**
         * Returns the width of this pin
         *
         * @param p the parameters
         * @return the width
         */
        public int getWidth(Params p) {
            return Math.max(1, Math.min(64, width.apply(p)));
        }

        /**
         * @return the default value used if the pin is not connected
         */
        public long getDefault() {
            return def;
        }
    }

    private final String quartusName;
    private final String description;
    private final ArrayList<PinSpec> inputs = new ArrayList<>();
    private final ArrayList<PinSpec> outputs = new ArrayList<>();
    private Function<Params, Behavior> behavior;
    private String defaultParams = "";
    private ElementTypeDescription typeDescription;

    /**
     * Creates a new part
     *
     * @param quartusName the name used by Quartus
     * @param description a short description
     */
    public QuartusPart(String quartusName, String description) {
        this.quartusName = quartusName;
        this.description = description;
    }

    /**
     * Adds a single bit input
     *
     * @param name the name
     * @return this for chained calls
     */
    public QuartusPart in(String name) {
        return in(name, 0);
    }

    /**
     * Adds a single bit input
     *
     * @param name the name
     * @param def  the default value used if the input is not connected
     * @return this for chained calls
     */
    public QuartusPart in(String name, long def) {
        inputs.add(new PinSpec(name, p -> 1, def, false));
        return this;
    }

    /**
     * Adds a multi bit input
     *
     * @param name  the name
     * @param width the width depending on the parameters
     * @param def   the default value
     * @return this for chained calls
     */
    public QuartusPart in(String name, Function<Params, Integer> width, long def) {
        inputs.add(new PinSpec(name, width, def, false));
        return this;
    }

    /**
     * Adds a clock input
     *
     * @param name the name
     * @return this for chained calls
     */
    public QuartusPart clock(String name) {
        inputs.add(new PinSpec(name, p -> 1, 0, true));
        return this;
    }

    /**
     * Adds a single bit output
     *
     * @param name the name
     * @return this for chained calls
     */
    public QuartusPart out(String name) {
        outputs.add(new PinSpec(name, p -> 1, 0, false));
        return this;
    }

    /**
     * Adds a multi bit output
     *
     * @param name  the name
     * @param width the width depending on the parameters
     * @return this for chained calls
     */
    public QuartusPart out(String name, Function<Params, Integer> width) {
        outputs.add(new PinSpec(name, width, 0, false));
        return this;
    }

    /**
     * Sets the behaviour
     *
     * @param behavior creates the behaviour
     * @return this for chained calls
     */
    public QuartusPart behavior(Function<Params, Behavior> behavior) {
        this.behavior = behavior;
        return this;
    }

    /**
     * Sets the default parameters
     *
     * @param defaultParams the default parameters
     * @return this for chained calls
     */
    public QuartusPart defaults(String defaultParams) {
        this.defaultParams = defaultParams;
        return this;
    }

    /**
     * @return the name used by Quartus
     */
    public String getQuartusName() {
        return quartusName;
    }

    /**
     * @return the name of the Digital element
     */
    public String getDigitalName() {
        return PREFIX + quartusName.toUpperCase(Locale.ROOT);
    }

    /**
     * @return the description
     */
    public String getDescription() {
        return description;
    }

    /**
     * @return the default parameters
     */
    public String getDefaultParams() {
        return defaultParams;
    }

    /**
     * @return the input specifications
     */
    public List<PinSpec> getInputs() {
        return inputs;
    }

    /**
     * @return the output specifications
     */
    public List<PinSpec> getOutputs() {
        return outputs;
    }

    /**
     * Returns the input spec with the given name
     *
     * @param name the name, case insensitive
     * @return the spec or null
     */
    public PinSpec getInput(String name) {
        for (PinSpec p : inputs)
            if (p.name.equalsIgnoreCase(name))
                return p;
        return null;
    }

    /**
     * Returns the output spec with the given name
     *
     * @param name the name, case insensitive
     * @return the spec or null
     */
    public PinSpec getOutput(String name) {
        for (PinSpec p : outputs)
            if (p.name.equalsIgnoreCase(name))
                return p;
        return null;
    }

    Behavior createBehavior(Params p) {
        return behavior.apply(p);
    }

    /**
     * Creates the params of an element
     *
     * @param attr the elements attributes
     * @return the params including the defaults
     */
    public Params getParams(ElementAttributes attr) {
        Params p = new Params(defaultParams);
        Params a = new Params(attr);
        p.getMap().putAll(a.getMap());
        return p;
    }

    /**
     * @return the Digital element type description
     */
    public ElementTypeDescription getTypeDescription() {
        if (typeDescription == null) {
            final QuartusPart part = this;
            typeDescription = new ElementTypeDescription(getDigitalName(), attr -> new QuartusNode(part, attr)) {
                @Override
                public PinDescriptions getInputDescription(ElementAttributes elementAttributes) {
                    Params p = getParams(elementAttributes);
                    ArrayList<PinDescription> l = new ArrayList<>();
                    for (PinSpec s : inputs)
                        if (p.isPortUsed(s.name)) {
                            PinInfo pi = PinInfo.input(s.name, s.name);
                            if (s.clock)
                                pi.setClock();
                            l.add(pi);
                        }
                    return new PinDescriptions(l.toArray(new PinDescription[0]));
                }
            };
            typeDescription.addAttribute(Keys.LABEL);
            typeDescription.addAttribute(Keys.QUARTUS_PARAMS);
            typeDescription.setShortName(quartusName);
        }
        return typeDescription;
    }
}
