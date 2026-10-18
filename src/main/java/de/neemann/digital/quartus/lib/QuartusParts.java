/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus.lib;

import de.neemann.digital.core.Bits;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The registry of all Quartus components which are simulated natively.
 * This contains the Quartus flip-flop primitives, some LPM megafunctions
 * and some of the 74xx macrofunctions of the Quartus "others/maxplus2" library.
 */
public final class QuartusParts {
    private static final LinkedHashMap<String, QuartusPart> PARTS = new LinkedHashMap<>();
    private static final String LPM_WIDTH = "LPM_WIDTH";

    private static final int[] SEVEN_SEG = {
            0b1111110, 0b0110000, 0b1101101, 0b1111001, 0b0110011, 0b1011011, 0b0011111, 0b1110000,
            0b1111111, 0b1110011, 0b0001101, 0b0011001, 0b0100011, 0b1001011, 0b0001111, 0b0000000};

    static {
        createFlipFlops();
        createLPM();
        createLPMCombinatorial();
        create74xx();
        create74xxCombinatorial();
        create74xxFlipFlops();
    }

    private QuartusParts() {
    }

    /**
     * Returns the part with the given Quartus name
     *
     * @param quartusName the name, case insensitive
     * @return the part or null
     */
    public static QuartusPart get(String quartusName) {
        return PARTS.get(quartusName.toLowerCase(Locale.ROOT));
    }

    /**
     * @return all parts
     */
    public static Collection<QuartusPart> getAll() {
        return Collections.unmodifiableCollection(PARTS.values());
    }

    /**
     * @return all parts which are flip-flop primitives
     */
    public static List<QuartusPart> getPrimitives() {
        return filter(p -> !p.getQuartusName().toLowerCase(Locale.ROOT).startsWith("lpm")
                && !p.getQuartusName().equalsIgnoreCase("busmux")
                && !Character.isDigit(p.getQuartusName().charAt(0)));
    }

    /**
     * @return all megafunctions
     */
    public static List<QuartusPart> getMegafunctions() {
        return filter(p -> p.getQuartusName().toLowerCase(Locale.ROOT).startsWith("lpm")
                || p.getQuartusName().equalsIgnoreCase("busmux"));
    }

    /**
     * @return all 74xx macrofunctions
     */
    public static List<QuartusPart> getMacrofunctions() {
        return filter(p -> Character.isDigit(p.getQuartusName().charAt(0)));
    }

    private static List<QuartusPart> filter(Function<QuartusPart, Boolean> f) {
        ArrayList<QuartusPart> l = new ArrayList<>();
        for (QuartusPart p : PARTS.values())
            if (f.apply(p))
                l.add(p);
        return l;
    }

    private static QuartusPart add(QuartusPart p) {
        PARTS.put(p.getQuartusName().toLowerCase(Locale.ROOT), p);
        return p;
    }

    private static boolean isSet(long v) {
        return (v & 1) != 0;
    }

    private static long b(boolean v) {
        return v ? 1 : 0;
    }

    /**
     * Detects rising and falling edges
     */
    private static final class Edge {
        private boolean last;

        private boolean rising(long v) {
            boolean n = isSet(v);
            boolean r = n && !last;
            last = n;
            return r;
        }

        private boolean falling(long v) {
            boolean n = isSet(v);
            boolean r = !n && last;
            last = n;
            return r;
        }
    }

    // ----------------------------------------------------------------------------------------
    // flip-flops
    // ----------------------------------------------------------------------------------------

    private enum FFKind {D, T, JK, SR}

    private static void createFlipFlops() {
        createFF("DFF", "D flip-flop with asynchronous clear and preset", FFKind.D, false, false);
        createFF("DFFE", "D flip-flop with clock enable, asynchronous clear and preset", FFKind.D, true, false);
        createFF("DFFEA", "D flip-flop with clock enable and asynchronous load", FFKind.D, true, true);
        createFF("TFF", "T flip-flop with asynchronous clear and preset", FFKind.T, false, false);
        createFF("TFFE", "T flip-flop with clock enable, asynchronous clear and preset", FFKind.T, true, false);
        createFF("JKFF", "JK flip-flop with asynchronous clear and preset", FFKind.JK, false, false);
        createFF("JKFFE", "JK flip-flop with clock enable, asynchronous clear and preset", FFKind.JK, true, false);
        createFF("SRFF", "SR flip-flop with asynchronous clear and preset", FFKind.SR, false, false);
        createFF("SRFFE", "SR flip-flop with clock enable, asynchronous clear and preset", FFKind.SR, true, false);

        add(new QuartusPart("LATCH", "Level sensitive D latch")
                .in("D").in("ENA", 1)
                .out("Q")
                .behavior(p -> new QuartusPart.Behavior() {
                    private long q;

                    @Override
                    public void eval(long[] in, long[] out) {
                        if (isSet(in[1]))
                            q = in[0] & 1;
                        out[0] = q;
                    }
                }));
    }

    private static void createFF(String name, String descr, FFKind kind, boolean ena, boolean aload) {
        QuartusPart p = new QuartusPart(name, descr);
        switch (kind) {
            case T:
                p.in("T");
                break;
            case JK:
                p.in("J").in("K");
                break;
            case SR:
                p.in("S").in("R");
                break;
            default:
                p.in("D");
        }
        int dataInputs = kind == FFKind.JK || kind == FFKind.SR ? 2 : 1;
        p.clock("CLK").in("CLRN", 1).in("PRN", 1);
        if (ena)
            p.in("ENA", 1);
        if (aload)
            p.in("ADATA").in("ALOAD");
        p.out("Q");
        final int clk = dataInputs;
        final int clrn = clk + 1;
        final int prn = clk + 2;
        final int en = ena ? clk + 3 : -1;
        final int adata = aload ? clk + 4 : -1;
        p.behavior(params -> new QuartusPart.Behavior() {
            private final Edge edge = new Edge();
            private long q;

            @Override
            public void eval(long[] in, long[] out) {
                boolean rising = edge.rising(in[clk]);
                if (!isSet(in[clrn]))
                    q = 0;
                else if (!isSet(in[prn]))
                    q = 1;
                else if (adata >= 0 && isSet(in[adata + 1]))
                    q = in[adata] & 1;
                else if (rising && (en < 0 || isSet(in[en]))) {
                    switch (kind) {
                        case T:
                            if (isSet(in[0])) q ^= 1;
                            break;
                        case JK:
                            if (isSet(in[0]) && isSet(in[1])) q ^= 1;
                            else if (isSet(in[0])) q = 1;
                            else if (isSet(in[1])) q = 0;
                            break;
                        case SR:
                            if (isSet(in[0])) q = 1;
                            else if (isSet(in[1])) q = 0;
                            break;
                        default:
                            q = in[0] & 1;
                    }
                }
                out[0] = q;
            }
        });
        add(p);
    }

    // ----------------------------------------------------------------------------------------
    // LPM megafunctions
    // ----------------------------------------------------------------------------------------

    private static Function<Params, Integer> w(String param, int def) {
        return p -> p.getInt(param, def);
    }

    private static void createLPM() {
        final Function<Params, Integer> width = w(LPM_WIDTH, 8);

        add(new QuartusPart("LPM_COUNTER", "Binary counter")
                .in("data", width, 0).clock("clock").in("clk_en", 1).in("cnt_en", 1).in("updown", 1)
                .in("cin", 1).in("aclr").in("aset").in("aload").in("sclr").in("sset").in("sload")
                .out("q", width).out("cout")
                .defaults("LPM_WIDTH=8")
                .behavior(p -> new QuartusPart.Behavior() {
                    private final int bits = width.apply(p);
                    private final long mask = Bits.mask(bits);
                    private final long modulus = p.getLong("LPM_MODULUS", 0);
                    private final long max = modulus > 0 ? modulus - 1 : mask;
                    private final String dir = p.get("LPM_DIRECTION", "UNUSED").toUpperCase(Locale.ROOT);
                    private final long avalue = p.getLong("LPM_AVALUE", mask);
                    private final long svalue = p.getLong("LPM_SVALUE", mask);
                    private final Edge edge = new Edge();
                    private long q;

                    @Override
                    public void eval(long[] in, long[] out) {
                        boolean up = dir.equals("UP") || (!dir.equals("DOWN") && isSet(in[4]));
                        boolean rising = edge.rising(in[1]);
                        if (isSet(in[6]))
                            q = 0;
                        else if (isSet(in[7]))
                            q = avalue & mask;
                        else if (isSet(in[8]))
                            q = in[0] & mask;
                        else if (rising && isSet(in[2])) {
                            if (isSet(in[9]))
                                q = 0;
                            else if (isSet(in[10]))
                                q = svalue & mask;
                            else if (isSet(in[11]))
                                q = in[0] & mask;
                            else if (isSet(in[3]) && isSet(in[5])) {
                                if (up)
                                    q = q >= max ? 0 : q + 1;
                                else
                                    q = q == 0 ? max : q - 1;
                            }
                        }
                        out[0] = q;
                        out[1] = b(isSet(in[5]) && (up ? q == max : q == 0));
                    }
                }));

        add(new QuartusPart("LPM_SHIFTREG", "Shift register")
                .in("data", width, 0).clock("clock").in("enable", 1).in("shiftin", 1).in("load")
                .in("aclr").in("aset").in("sclr").in("sset")
                .out("q", width).out("shiftout")
                .defaults("LPM_WIDTH=8")
                .behavior(p -> new QuartusPart.Behavior() {
                    private final int bits = width.apply(p);
                    private final long mask = Bits.mask(bits);
                    private final boolean right = p.get("LPM_DIRECTION", "LEFT").equalsIgnoreCase("RIGHT");
                    private final long avalue = p.getLong("LPM_AVALUE", mask);
                    private final long svalue = p.getLong("LPM_SVALUE", mask);
                    private final Edge edge = new Edge();
                    private long q;

                    @Override
                    public void eval(long[] in, long[] out) {
                        boolean rising = edge.rising(in[1]);
                        if (isSet(in[5]))
                            q = 0;
                        else if (isSet(in[6]))
                            q = avalue & mask;
                        else if (rising && isSet(in[2])) {
                            if (isSet(in[7]))
                                q = 0;
                            else if (isSet(in[8]))
                                q = svalue & mask;
                            else if (isSet(in[4]))
                                q = in[0] & mask;
                            else if (right)
                                q = (q >>> 1) | ((in[3] & 1) << (bits - 1));
                            else
                                q = ((q << 1) | (in[3] & 1)) & mask;
                        }
                        out[0] = q;
                        out[1] = right ? q & 1 : (q >>> (bits - 1)) & 1;
                    }
                }));

        add(new QuartusPart("LPM_FF", "D or T flip-flop register")
                .in("data", width, 0).clock("clock").in("enable", 1).in("aclr").in("aset").in("aload")
                .in("sclr").in("sset").in("sload")
                .out("q", width)
                .defaults("LPM_WIDTH=8")
                .behavior(p -> new QuartusPart.Behavior() {
                    private final long mask = Bits.mask(width.apply(p));
                    private final boolean tff = p.get("LPM_FFTYPE", "DFF").equalsIgnoreCase("TFF");
                    private final long avalue = p.getLong("LPM_AVALUE", mask);
                    private final long svalue = p.getLong("LPM_SVALUE", mask);
                    private final Edge edge = new Edge();
                    private long q;

                    @Override
                    public void eval(long[] in, long[] out) {
                        boolean rising = edge.rising(in[1]);
                        if (isSet(in[3]))
                            q = 0;
                        else if (isSet(in[4]))
                            q = avalue & mask;
                        else if (isSet(in[5]))
                            q = in[0] & mask;
                        else if (rising && isSet(in[2])) {
                            if (isSet(in[6]))
                                q = 0;
                            else if (isSet(in[7]))
                                q = svalue & mask;
                            else if (isSet(in[8]))
                                q = in[0] & mask;
                            else if (tff)
                                q = (q ^ in[0]) & mask;
                            else
                                q = in[0] & mask;
                        }
                        out[0] = q;
                    }
                }));

        add(new QuartusPart("LPM_LATCH", "Latch")
                .in("data", width, 0).in("gate").in("aclr").in("aset")
                .out("q", width)
                .defaults("LPM_WIDTH=8")
                .behavior(p -> new QuartusPart.Behavior() {
                    private final long mask = Bits.mask(width.apply(p));
                    private long q;

                    @Override
                    public void eval(long[] in, long[] out) {
                        if (isSet(in[2]))
                            q = 0;
                        else if (isSet(in[3]))
                            q = mask;
                        else if (isSet(in[1]))
                            q = in[0] & mask;
                        out[0] = q;
                    }
                }));

    }

    private static void createLPMCombinatorial() {
        final Function<Params, Integer> width = w(LPM_WIDTH, 8);

        add(new QuartusPart("LPM_CONSTANT", "Constant value")
                .out("result", width)
                .defaults("LPM_WIDTH=8;LPM_CVALUE=0")
                .behavior(p -> {
                    final long v = p.getLong("LPM_CVALUE", 0) & Bits.mask(width.apply(p));
                    return (in, out) -> out[0] = v;
                }));

        add(new QuartusPart("LPM_INV", "Inverter")
                .in("data", width, 0)
                .out("result", width)
                .defaults("LPM_WIDTH=8")
                .behavior(p -> {
                    final long mask = Bits.mask(width.apply(p));
                    return (in, out) -> out[0] = ~in[0] & mask;
                }));

        add(new QuartusPart("LPM_COMPARE", "Comparator")
                .in("dataa", width, 0).in("datab", width, 0)
                .out("alb").out("aeb").out("agb").out("ageb").out("aneb").out("aleb")
                .defaults("LPM_WIDTH=8")
                .behavior(p -> {
                    final int bits = width.apply(p);
                    final boolean signed = p.get("LPM_REPRESENTATION", "UNSIGNED").equalsIgnoreCase("SIGNED");
                    return (in, out) -> {
                        int c;
                        if (signed)
                            c = Long.compare(Bits.signExtend(in[0], bits), Bits.signExtend(in[1], bits));
                        else
                            c = Long.compareUnsigned(in[0], in[1]);
                        out[0] = b(c < 0);
                        out[1] = b(c == 0);
                        out[2] = b(c > 0);
                        out[3] = b(c >= 0);
                        out[4] = b(c != 0);
                        out[5] = b(c <= 0);
                    };
                }));

        add(new QuartusPart("LPM_ADD_SUB", "Adder/Subtractor")
                .in("dataa", width, 0).in("datab", width, 0).in("cin", 2).in("add_sub", 1)
                .out("result", width).out("cout").out("overflow")
                .defaults("LPM_WIDTH=8")
                .behavior(p -> {
                    final int bits = width.apply(p);
                    final long mask = Bits.mask(bits);
                    final String dir = p.get("LPM_DIRECTION", "UNUSED").toUpperCase(Locale.ROOT);
                    return (in, out) -> {
                        boolean add = dir.equals("ADD") || (!dir.equals("SUB") && isSet(in[3]));
                        long a = in[0] & mask;
                        long bb = in[1] & mask;
                        long cin = in[2] == 2 ? (add ? 0 : 1) : in[2] & 1;
                        long r;
                        if (add)
                            r = a + bb + cin;
                        else
                            r = a + (~bb & mask) + cin;
                        out[0] = r & mask;
                        out[1] = (r >>> bits) & 1;
                        long sa = Bits.signExtend(a, bits);
                        long sb = Bits.signExtend(bb, bits);
                        long sr = add ? sa + sb + cin : sa - sb - (1 - cin);
                        long min = -(1L << (bits - 1));
                        long max = (1L << (bits - 1)) - 1;
                        out[2] = b(sr < min || sr > max);
                    };
                }));

        add(new QuartusPart("LPM_DECODE", "Decoder")
                .in("data", width, 0).in("enable", 1)
                .out("eq", w("LPM_DECODES", 4))
                .defaults("LPM_WIDTH=2;LPM_DECODES=4")
                .behavior(p -> (in, out) -> {
                    if (isSet(in[1]) && in[0] < 64)
                        out[0] = 1L << in[0];
                    else
                        out[0] = 0;
                }));

        add(new QuartusPart("LPM_MULT", "Multiplier")
                .in("dataa", w("LPM_WIDTHA", 8), 0).in("datab", w("LPM_WIDTHB", 8), 0)
                .out("result", w("LPM_WIDTHP", 16))
                .defaults("LPM_WIDTHA=8;LPM_WIDTHB=8;LPM_WIDTHP=16")
                .behavior(p -> {
                    final long mask = Bits.mask(p.getInt("LPM_WIDTHP", 16));
                    return (in, out) -> out[0] = (in[0] * in[1]) & mask;
                }));

        add(new QuartusPart("BUSMUX", "Bus multiplexer")
                .in("dataa", w("WIDTH", 8), 0).in("datab", w("WIDTH", 8), 0).in("sel")
                .out("result", w("WIDTH", 8))
                .defaults("WIDTH=8")
                .behavior(p -> (in, out) -> out[0] = isSet(in[2]) ? in[1] : in[0]));
    }

    // ----------------------------------------------------------------------------------------
    // 74xx macrofunctions
    // ----------------------------------------------------------------------------------------

    private static long nibble(long[] in, int a) {
        return (in[a] & 1) | ((in[a + 1] & 1) << 1) | ((in[a + 2] & 1) << 2) | ((in[a + 3] & 1) << 3);
    }

    private static void setNibble(long[] out, int a, long v) {
        for (int i = 0; i < 4; i++)
            out[a + i] = (v >>> i) & 1;
    }

    private static void create74xx() {
        create7416x("74160", false, 10);
        create7416x("74161", false, 16);
        create7416x("74162", true, 10);
        create7416x("74163", true, 16);
        create7419x("74190", 10);
        create7419x("74191", 16);
        create7419x2("74192", 10);
        create7419x2("74193", 16);

        add(new QuartusPart("74194", "4-bit bidirectional universal shift register")
                .in("CLRN", 1).in("S1").in("S0").in("SRSI").in("SLSI").clock("CLK")
                .in("A").in("B").in("C").in("D")
                .out("QA").out("QB").out("QC").out("QD")
                .behavior(p -> new QuartusPart.Behavior() {
                    private final Edge edge = new Edge();
                    private long q;

                    @Override
                    public void eval(long[] in, long[] out) {
                        boolean rising = edge.rising(in[5]);
                        if (!isSet(in[0]))
                            q = 0;
                        else if (rising) {
                            int mode = (int) (((in[1] & 1) << 1) | (in[2] & 1));
                            switch (mode) {
                                case 1: // shift right: QA <- SRSI
                                    q = ((q << 1) | (in[3] & 1)) & 0xF;
                                    break;
                                case 2: // shift left: QD <- SLSI
                                    q = (q >>> 1) | ((in[4] & 1) << 3);
                                    break;
                                case 3:
                                    q = nibble(in, 6);
                                    break;
                                default:
                                    break;
                            }
                        }
                        setNibble(out, 0, q);
                    }
                }));

        add(new QuartusPart("74164", "8-bit serial in, parallel out shift register")
                .in("A").in("B").in("CLRN", 1).clock("CLK")
                .out("QA").out("QB").out("QC").out("QD").out("QE").out("QF").out("QG").out("QH")
                .behavior(p -> new QuartusPart.Behavior() {
                    private final Edge edge = new Edge();
                    private long q;

                    @Override
                    public void eval(long[] in, long[] out) {
                        boolean rising = edge.rising(in[3]);
                        if (!isSet(in[2]))
                            q = 0;
                        else if (rising)
                            q = ((q << 1) | (in[0] & in[1] & 1)) & 0xFF;
                        for (int i = 0; i < 8; i++)
                            out[i] = (q >>> i) & 1;
                    }
                }));

    }

    private static void create74xxCombinatorial() {
        add(new QuartusPart("74138", "3-to-8 line decoder with active low outputs")
                .in("A").in("B").in("C").in("G1", 1).in("G2AN").in("G2BN")
                .out("Y0N").out("Y1N").out("Y2N").out("Y3N").out("Y4N").out("Y5N").out("Y6N").out("Y7N")
                .behavior(p -> (in, out) -> {
                    boolean en = isSet(in[3]) && !isSet(in[4]) && !isSet(in[5]);
                    int sel = (int) ((in[0] & 1) | ((in[1] & 1) << 1) | ((in[2] & 1) << 2));
                    for (int i = 0; i < 8; i++)
                        out[i] = b(!(en && i == sel));
                }));

        add(new QuartusPart("74151", "8-to-1 multiplexer")
                .in("A").in("B").in("C").in("GN")
                .in("D0").in("D1").in("D2").in("D3").in("D4").in("D5").in("D6").in("D7")
                .out("Y").out("WN")
                .behavior(p -> (in, out) -> {
                    int sel = (int) ((in[0] & 1) | ((in[1] & 1) << 1) | ((in[2] & 1) << 2));
                    long y = isSet(in[3]) ? 0 : in[4 + sel] & 1;
                    out[0] = y;
                    out[1] = y ^ 1;
                }));

        add(new QuartusPart("74153", "Dual 4-to-1 multiplexer")
                .in("A").in("B").in("1GN").in("1C0").in("1C1").in("1C2").in("1C3")
                .in("2GN").in("2C0").in("2C1").in("2C2").in("2C3")
                .out("1Y").out("2Y")
                .behavior(p -> (in, out) -> {
                    int sel = (int) ((in[0] & 1) | ((in[1] & 1) << 1));
                    out[0] = isSet(in[2]) ? 0 : in[3 + sel] & 1;
                    out[1] = isSet(in[7]) ? 0 : in[8 + sel] & 1;
                }));

        add(new QuartusPart("74157", "Quad 2-to-1 multiplexer")
                .in("SEL").in("GN").in("A1").in("B1").in("A2").in("B2").in("A3").in("B3").in("A4").in("B4")
                .out("Y1").out("Y2").out("Y3").out("Y4")
                .behavior(p -> (in, out) -> {
                    for (int i = 0; i < 4; i++)
                        out[i] = isSet(in[1]) ? 0 : in[2 + i * 2 + (int) (in[0] & 1)] & 1;
                }));

        create7seg("7446", true);
        create7seg("7447", true);
        create7seg("7448", false);
        create7seg("7449", false);

        add(new QuartusPart("74283", "4-bit full adder")
                .in("A1").in("A2").in("A3").in("A4").in("B1").in("B2").in("B3").in("B4").in("CIN")
                .out("SUM1").out("SUM2").out("SUM3").out("SUM4").out("COUT")
                .behavior(p -> (in, out) -> {
                    long s = nibble(in, 0) + nibble(in, 4) + (in[8] & 1);
                    setNibble(out, 0, s);
                    out[4] = (s >>> 4) & 1;
                }));

        add(new QuartusPart("7485", "4-bit magnitude comparator")
                .in("A0").in("A1").in("A2").in("A3").in("B0").in("B1").in("B2").in("B3")
                .in("ALBI").in("AEBI", 1).in("AGBI")
                .out("ALBO").out("AEBO").out("AGBO")
                .behavior(p -> (in, out) -> {
                    long a = nibble(in, 0);
                    long bb = nibble(in, 4);
                    if (a < bb) {
                        out[0] = 1;
                        out[1] = 0;
                        out[2] = 0;
                    } else if (a > bb) {
                        out[0] = 0;
                        out[1] = 0;
                        out[2] = 1;
                    } else {
                        out[0] = in[8] & 1;
                        out[1] = in[9] & 1;
                        out[2] = in[10] & 1;
                    }
                }));

    }

    private static void create74xxFlipFlops() {
        add(new QuartusPart("7474", "Dual D flip-flop with preset and clear")
                .in("1CLRN", 1).in("1D").clock("1CLK").in("1PRN", 1)
                .in("2CLRN", 1).in("2D").clock("2CLK").in("2PRN", 1)
                .out("1Q").out("1QN").out("2Q").out("2QN")
                .behavior(p -> new QuartusPart.Behavior() {
                    private final Edge e1 = new Edge();
                    private final Edge e2 = new Edge();
                    private long q1;
                    private long q2;

                    @Override
                    public void eval(long[] in, long[] out) {
                        q1 = dff(in, 0, e1, q1);
                        q2 = dff(in, 4, e2, q2);
                        out[0] = q1;
                        out[1] = q1 ^ 1;
                        out[2] = q2;
                        out[3] = q2 ^ 1;
                    }

                    private long dff(long[] in, int o, Edge e, long q) {
                        boolean r = e.rising(in[o + 2]);
                        if (!isSet(in[o]))
                            return 0;
                        if (!isSet(in[o + 3]))
                            return 1;
                        if (r)
                            return in[o + 1] & 1;
                        return q;
                    }
                }));

        add(new QuartusPart("7493", "4-bit binary ripple counter")
                .clock("CLKA").clock("CLKB").in("RO1").in("RO2")
                .out("QA").out("QB").out("QC").out("QD")
                .behavior(p -> new QuartusPart.Behavior() {
                    private final Edge ea = new Edge();
                    private final Edge eb = new Edge();
                    private long qa;
                    private long qbcd;

                    @Override
                    public void eval(long[] in, long[] out) {
                        boolean fa = ea.falling(in[0]);
                        boolean fb = eb.falling(in[1]);
                        if (isSet(in[2]) && isSet(in[3])) {
                            qa = 0;
                            qbcd = 0;
                        } else {
                            if (fa) qa ^= 1;
                            if (fb) qbcd = (qbcd + 1) & 7;
                        }
                        out[0] = qa;
                        out[1] = qbcd & 1;
                        out[2] = (qbcd >>> 1) & 1;
                        out[3] = (qbcd >>> 2) & 1;
                    }
                }));

        add(new QuartusPart("7490", "Decade ripple counter")
                .clock("CLKA").clock("CLKB").in("RO1").in("RO2").in("RS1").in("RS2")
                .out("QA").out("QB").out("QC").out("QD")
                .behavior(p -> new QuartusPart.Behavior() {
                    private final Edge ea = new Edge();
                    private final Edge eb = new Edge();
                    private long qa;
                    private long qbcd;

                    @Override
                    public void eval(long[] in, long[] out) {
                        boolean fa = ea.falling(in[0]);
                        boolean fb = eb.falling(in[1]);
                        if (isSet(in[4]) && isSet(in[5])) {
                            qa = 1;
                            qbcd = 4;
                        } else if (isSet(in[2]) && isSet(in[3])) {
                            qa = 0;
                            qbcd = 0;
                        } else {
                            if (fa) qa ^= 1;
                            if (fb) qbcd = (qbcd + 1) % 5;
                        }
                        out[0] = qa;
                        out[1] = qbcd & 1;
                        out[2] = (qbcd >>> 1) & 1;
                        out[3] = (qbcd >>> 2) & 1;
                    }
                }));
    }

    private static void create7seg(String name, boolean activeLow) {
        add(new QuartusPart(name, "BCD to 7-segment decoder" + (activeLow ? " with active low outputs" : ""))
                .in("A").in("B").in("C").in("D").in("LTN", 1).in("RBIN", 1).in("BIN", 1)
                .out("OA").out("OB").out("OC").out("OD").out("OE").out("OF").out("OG").out("RBON")
                .behavior(p -> (in, out) -> {
                    int v = (int) nibble(in, 0);
                    int seg;
                    long rbo = 1;
                    if (!isSet(in[6]))
                        seg = 0;
                    else if (!isSet(in[4]))
                        seg = 0x7F;
                    else if (v == 0 && !isSet(in[5])) {
                        seg = 0;
                        rbo = 0;
                    } else
                        seg = SEVEN_SEG[v];
                    for (int i = 0; i < 7; i++) {
                        long s = (seg >>> (6 - i)) & 1;
                        out[i] = activeLow ? s ^ 1 : s;
                    }
                    out[7] = rbo;
                }));
    }

    private static void create7416x(String name, boolean syncClear, int modulus) {
        add(new QuartusPart(name, (modulus == 10 ? "Decade" : "4-bit binary") + " counter with "
                + (syncClear ? "synchronous" : "asynchronous") + " clear")
                .in("CLRN", 1).in("LDN", 1).in("ENP", 1).in("ENT", 1).clock("CLK")
                .in("A").in("B").in("C").in("D")
                .out("QA").out("QB").out("QC").out("QD").out("RCO")
                .behavior(p -> new QuartusPart.Behavior() {
                    private final Edge edge = new Edge();
                    private long q;

                    @Override
                    public void eval(long[] in, long[] out) {
                        boolean rising = edge.rising(in[4]);
                        if (!syncClear && !isSet(in[0]))
                            q = 0;
                        else if (rising) {
                            if (!isSet(in[0]))
                                q = 0;
                            else if (!isSet(in[1]))
                                q = nibble(in, 5);
                            else if (isSet(in[2]) && isSet(in[3]))
                                q = q >= modulus - 1 ? 0 : q + 1;
                        }
                        setNibble(out, 0, q);
                        out[4] = b(isSet(in[3]) && q == modulus - 1);
                    }
                }));
    }

    private static void create7419x(String name, int modulus) {
        add(new QuartusPart(name, (modulus == 10 ? "Decade" : "4-bit binary") + " up/down counter")
                .in("GN").in("DNUP").in("LDN", 1).clock("CLK")
                .in("A").in("B").in("C").in("D")
                .out("QA").out("QB").out("QC").out("QD").out("MXMN").out("RCON")
                .behavior(p -> new QuartusPart.Behavior() {
                    private final Edge edge = new Edge();
                    private long q;

                    @Override
                    public void eval(long[] in, long[] out) {
                        boolean rising = edge.rising(in[3]);
                        boolean down = isSet(in[1]);
                        if (!isSet(in[2]))
                            q = nibble(in, 4);
                        else if (rising && !isSet(in[0])) {
                            if (down)
                                q = q == 0 ? modulus - 1 : q - 1;
                            else
                                q = q >= modulus - 1 ? 0 : q + 1;
                        }
                        setNibble(out, 0, q);
                        boolean mxmn = down ? q == 0 : q == modulus - 1;
                        out[4] = b(mxmn);
                        out[5] = b(!(mxmn && !isSet(in[0]) && !isSet(in[3])));
                    }
                }));
    }

    private static void create7419x2(String name, int modulus) {
        add(new QuartusPart(name, (modulus == 10 ? "Decade" : "4-bit binary") + " up/down counter with dual clock")
                .clock("UP").clock("DN").in("CLR").in("LDN", 1)
                .in("A").in("B").in("C").in("D")
                .out("QA").out("QB").out("QC").out("QD").out("CON").out("BON")
                .behavior(p -> new QuartusPart.Behavior() {
                    private final Edge up = new Edge();
                    private final Edge dn = new Edge();
                    private long q;

                    @Override
                    public void eval(long[] in, long[] out) {
                        boolean ru = up.rising(in[0]);
                        boolean rd = dn.rising(in[1]);
                        if (isSet(in[2]))
                            q = 0;
                        else if (!isSet(in[3]))
                            q = nibble(in, 4);
                        else if (ru && isSet(in[1]))
                            q = q >= modulus - 1 ? 0 : q + 1;
                        else if (rd && isSet(in[0]))
                            q = q == 0 ? modulus - 1 : q - 1;
                        setNibble(out, 0, q);
                        out[4] = b(!(q == modulus - 1 && !isSet(in[0])));
                        out[5] = b(!(q == 0 && !isSet(in[1])));
                    }
                }));
    }
}
