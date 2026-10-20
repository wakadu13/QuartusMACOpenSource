/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus;

import de.neemann.digital.core.Model;
import de.neemann.digital.core.ObservableValue;
import de.neemann.digital.integration.Resources;
import junit.framework.TestCase;

import java.io.File;

import static de.neemann.digital.quartus.QuartusImportTest.*;

/**
 * Simulates the blocks of the EA library: schematics, AHDL megafunctions and VHDL files
 * which are simulated by the built-in VHDL simulator.
 */
public class EaLibraryTest extends TestCase {
    private static final File LIB = new File(Resources.getRoot(), "../../../bibliotheque-EA");

    private static Model importBlock(String file) throws Exception {
        QuartusImporter imp = QuartusImporter.importFile(new File(LIB, file), tempDir());
        assertEquals(imp.getWarnings().toString(), 0, imp.getWarnings().size());
        return createModel(imp.getTopFile());
    }

    private static ObservableValue clockOrInput(Model m, String name) {
        return m.getClocks().isEmpty() ? input(m, name) : m.getClocks().get(0).getClockOutput();
    }

    /* the period of the output in clock cycles */
    private static int period(Model m, ObservableValue clk, ObservableValue out, int cycles) {
        int first = -1;
        long last = out.getValue();
        for (int i = 0; i < cycles; i++) {
            clock(m, clk);
            long v = out.getValue();
            if (v == 1 && last == 0) {
                if (first >= 0)
                    return i - first;
                first = i;
            }
            last = v;
        }
        return -1;
    }

    public void testDividers() throws Exception {
        int[][] dividers = {{2}, {3}, {5}};
        String[] names = {"div2.bdf", "div3.bdf", "div5.bdf"};
        for (int i = 0; i < names.length; i++) {
            Model m = importBlock(names[i]);
            assertEquals(names[i], dividers[i][0], period(m, clockOrInput(m, "E"), output(m, "S"), 40));
            m.close();
        }
    }

    public void testClockDivWithVhdl() throws Exception {
        // Clock_Div uses the VHDL file CLK_DIV.VHD which is simulated without GHDL
        Model m = importBlock("Clock_Div.bdf");
        ObservableValue clk = input(m, "50MHz");
        assertEquals(50, period(m, clk, output(m, "1MHz"), 200));
        assertEquals(500, period(m, clk, output(m, "100KHz"), 2000));
        m.close();
    }

    public void testAhdlMegafunctions() throws Exception {
        Model m = importBlock("compteur3b.tdf");
        ObservableValue clk = input(m, "clock");
        ObservableValue q = output(m, "q");
        for (int i = 1; i <= 10; i++) {
            clock(m, clk);
            assertEquals(i & 7, q.getValue());
        }
        m.close();

        m = importBlock("decode1parmi8.tdf");
        ObservableValue data = input(m, "data");
        for (int v = 0; v < 8; v++) {
            data.setValue(v);
            m.doStep();
            for (int k = 0; k < 8; k++)
                assertEquals(k == v ? 1 : 0, output(m, "eq" + k).getValue());
        }
        m.close();

        m = importBlock("mux8par7.tdf");
        for (int k = 0; k < 8; k++)
            input(m, "data" + k + "x").setValue(10 * k + 1);
        for (int s = 0; s < 8; s++) {
            input(m, "sel").setValue(s);
            m.doStep();
            assertEquals(10 * s + 1, output(m, "result").getValue());
        }
        m.close();
    }

    public void testSevenSegmentDecoder() throws Exception {
        Model m = importBlock("DEC_7SEG.VHD");
        // the segments are active low
        String[] expected = {"0000001", "1001111", "0010010", "0000110", "1001100", "0100100", "0100000", "0001111", "0000000", "0000100"};
        for (int v = 0; v < 10; v++) {
            input(m, "hex_digit").setValue(v);
            m.doStep();
            StringBuilder sb = new StringBuilder();
            for (String s : new String[]{"a", "b", "c", "d", "e", "f", "g"})
                sb.append(output(m, "segment_" + s).getValue());
            assertEquals(expected[v], sb.toString());
        }
        m.close();
    }

    public void testShiftRegister32() throws Exception {
        // two ShiftRight16 written in VHDL
        Model m = importBlock("ShiftRight32.bdf");
        ObservableValue h = input(m, "h");
        ObservableValue load = input(m, "load");
        ObservableValue out = output(m, "SerialOut");
        long value = 0x8000A5C3L;
        input(m, "data").setValue(value);
        load.setValue(1);
        m.doStep();
        load.setValue(0);
        m.doStep();
        for (int i = 0; i < 32; i++) {
            assertEquals("bit " + i, (value >> i) & 1, out.getValue());
            clock(m, h);
        }
        m.close();
    }

    public void testReconstructedBlocks() throws Exception {
        // XOR8 and XOR4 compute the parity of their inputs
        for (int n : new int[]{4, 8}) {
            Model m = importBlock("XOR" + n + ".bdf");
            for (int v = 0; v < (1 << n); v++) {
                for (int k = 0; k < n; k++)
                    input(m, "IN" + (k + 1)).setValue((v >> k) & 1);
                m.doStep();
                assertEquals(Integer.bitCount(v) & 1, output(m, "OUT").getValue());
            }
            m.close();
        }

        // Divideur7 counts modulo 7
        Model m = importBlock("Divideur7.tdf");
        ObservableValue clk = input(m, "clock");
        for (int i = 1; i <= 20; i++) {
            clock(m, clk);
            assertEquals(i % 7, output(m, "q").getValue());
            assertEquals(i % 7 == 6 ? 1 : 0, output(m, "cout").getValue());
        }
        m.close();

        // the sub designs of GenerateurArinc are complete now
        importBlock("GenerateurArinc.bdf").close();
    }
}
