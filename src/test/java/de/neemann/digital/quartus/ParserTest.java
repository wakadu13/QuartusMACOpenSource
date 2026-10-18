/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus;

import de.neemann.digital.integration.Resources;
import junit.framework.TestCase;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Tests the parsers of the Quartus files
 */
public class ParserTest extends TestCase {

    public void testBusName() {
        assertEquals(Arrays.asList("a[3]", "a[2]", "a[1]", "a[0]"), BusName.parse("a[3..0]").getAtoms());
        assertEquals(Arrays.asList("c[1]", "c[2]", "c[3]"), BusName.parse("C[1..3]").getAtoms());
        assertEquals(Arrays.asList("x", "b[1]", "b[0]", "gnd"), BusName.parse("X, b[1..0], GND").getAtoms());
        assertEquals("d7", BusName.parse("d7[7..1]").getBase());
        assertEquals(7, BusName.parse("d7[7..1]").getBits());
        assertTrue(BusName.parse("VCC").isConstant());
        assertEquals("a[3]", BusName.parse("a[3]").getKey());
    }

    public void testExpression() {
        HashMap<String, String> v = new HashMap<>();
        v.put("n", "8");
        v.put("w", "n*2");
        assertEquals(7, Expression.eval("N-1", v, -1));
        assertEquals(15, Expression.eval("w-1", v, -1));
        assertEquals(256, Expression.eval("2**n", v, -1));
        assertEquals(10, Expression.eval("4'hA", v, -1));
        assertEquals(-1, Expression.eval("foo", v, -1));
    }

    public void testBdf() throws Exception {
        BdfFile f = BdfFile.read(new File(Resources.getRoot(), "quartus/afficheur8d/Afficheur8D.bdf"));
        assertEquals(11, f.getPins().size());
        assertEquals(7, f.getSymbols().size());
        assertEquals(58, f.getConnectors().size());
        assertEquals(9, f.getJunctions().size());
        BdfFile.Symbol s = f.getSymbols().get(0);
        assertEquals("Clock_Div", s.getTypeName());
        assertEquals("inst3", s.getInstanceName());
        assertEquals(9, s.ports.size());
        assertEquals(new BdfFile.Point(272, 384), s.getAbsolutePos(s.ports.get(0)));
        assertTrue(f.getPins().get(0).isDefaultVCC());
    }

    public void testVhdl() {
        List<HdlEntity> l = HdlEntity.parseVHDL("library ieee; -- comment\n"
                + "entity Foo is generic (N : integer := 4);\n"
                + " port ( a, b : in std_logic_vector(N-1 downto 0); -- comment\n"
                + "        c : out std_logic;\n"
                + "        en : in std_logic := '1');\n"
                + "end entity Foo;\narchitecture x of Foo is begin end x;", null);
        assertEquals(1, l.size());
        HdlEntity e = l.get(0);
        assertEquals("Foo", e.getName());
        assertEquals(4, e.getPorts().size());
        assertEquals(4, e.getPorts().get(0).bits);
        assertEquals("b", e.getPorts().get(1).name);
        assertEquals(BdfFile.Direction.OUTPUT, e.getPorts().get(2).direction);
        assertTrue(e.getPorts().get(3).defaultHigh);
    }

    public void testVerilog() {
        List<HdlEntity> l = HdlEntity.parseVerilog("module m #(parameter W = 8) (input clk, input [W-1:0] d, output reg [W-1:0] q);\n"
                + "endmodule\n"
                + "module old(a, y); input [3:0] a; output y; endmodule", null);
        assertEquals(2, l.size());
        assertEquals(3, l.get(0).getPorts().size());
        assertEquals(8, l.get(0).getPorts().get(2).bits);
        assertEquals(BdfFile.Direction.OUTPUT, l.get(0).getPorts().get(2).direction);
        assertEquals(4, l.get(1).getPorts().get(0).bits);
        assertEquals(BdfFile.Direction.OUTPUT, l.get(1).getPorts().get(1).direction);
    }

    public void testProject() throws Exception {
        QuartusProject p = QuartusProject.open(new File(Resources.getRoot(), "quartus/afficheur8d/Afficheur8D.qpf"));
        assertEquals("Afficheur8D", p.getTopLevelEntity());
        assertEquals("Cyclone III", p.getFamily());
        assertEquals("EP3C16F484C6", p.getDevice());
        assertTrue(p.getDesignFiles().get(0).getName().equals("Afficheur8D.bdf"));
    }

    public void testMegaWizardEntity() throws Exception {
        List<HdlEntity> l = HdlEntity.read(new File(Resources.getRoot(), "quartus/megawizard/cnt4.vhd"));
        assertEquals(1, l.size());
        HdlEntity e = l.get(0);
        assertEquals("lpm_counter", e.getMegafunction());
        assertEquals("10", e.getMegafunctionParams().get("LPM_MODULUS"));
        assertEquals("q", e.getMegafunctionPortMap().get("q"));
        assertEquals("cout", e.getMegafunctionPortMap().get("cout"));
    }
}
