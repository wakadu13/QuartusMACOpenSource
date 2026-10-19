/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus;

import de.neemann.digital.core.ObservableValue;
import de.neemann.digital.core.ObservableValues;
import de.neemann.digital.core.extern.PortDefinition;
import de.neemann.digital.core.extern.handler.ProcessInterface;
import junit.framework.TestCase;

public class VhdlInterpreterTest extends TestCase {
    private static final String COUNTER = "library ieee;\n"
            + "use ieee.std_logic_1164.all;\n"
            + "use ieee.numeric_std.all;\n"
            + "entity counter is\n"
            + "  port (clk, en : in std_logic; q : out std_logic_vector(3 downto 0); carry : out std_logic);\n"
            + "end counter;\n"
            + "architecture rtl of counter is\n"
            + "  signal c : unsigned(3 downto 0) := (others => '0');\n"
            + "begin\n"
            + "  process (clk)\n"
            + "  begin\n"
            + "    if rising_edge(clk) then\n"
            + "      if en = '1' then\n"
            + "        if c = 9 then c <= (others => '0'); else c <= c + 1; end if;\n"
            + "      end if;\n"
            + "    end if;\n"
            + "  end process;\n"
            + "  q <= std_logic_vector(c);\n"
            + "  carry <= '1' when c = 9 else '0';\n"
            + "end rtl;\n";

    public void testCounter() throws Exception {
        assertNull(VhdlInterpreter.check("counter", COUNTER));
        PortDefinition in = new PortDefinition("clk,en");
        PortDefinition out = new PortDefinition("q:4,carry");
        ProcessInterface p = VhdlInterpreter.start("counter", COUNTER, in, out);
        ObservableValue clk = new ObservableValue("clk", 1);
        ObservableValue en = new ObservableValue("en", 1).setValue(1);
        ObservableValues ins = new ObservableValues(clk, en);
        ObservableValues outs = out.createOutputs();
        for (int i = 1; i <= 25; i++) {
            clk.setValue(1);
            p.writeValues(ins);
            clk.setValue(0);
            p.writeValues(ins);
            p.readValues(outs);
            assertEquals(i % 10, outs.get(0).getValue());
            assertEquals(i % 10 == 9 ? 1 : 0, outs.get(1).getValue());
        }
    }

    public void testUnsupported() {
        String code = "entity top is port (a : in std_logic; y : out std_logic); end top;\n"
                + "architecture s of top is\n"
                + "  component inv port (i : in std_logic; o : out std_logic); end component;\n"
                + "begin\n"
                + "  u1 : inv port map (a, y);\n"
                + "end s;\n";
        assertNotNull(VhdlInterpreter.check("top", code));
    }
}
