/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus;

import de.neemann.digital.core.Model;
import de.neemann.digital.core.ObservableValue;
import de.neemann.digital.core.Signal;
import de.neemann.digital.core.element.Keys;
import de.neemann.digital.draw.elements.Circuit;
import de.neemann.digital.draw.elements.VisualElement;
import de.neemann.digital.draw.library.ElementLibrary;
import de.neemann.digital.draw.model.ModelCreator;
import de.neemann.digital.draw.shapes.ShapeFactory;
import de.neemann.digital.integration.Resources;
import de.neemann.digital.testing.TestCaseDescription;
import de.neemann.digital.testing.TestExecutor;
import junit.framework.TestCase;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Imports Quartus designs and simulates them.
 */
public class QuartusImportTest extends TestCase {

    static File tempDir() throws IOException {
        return Files.createTempDirectory("quartus").toFile();
    }

    static Model createModel(File dig) throws Exception {
        ElementLibrary library = new ElementLibrary();
        library.setRootFilePath(dig.getParentFile());
        ShapeFactory shapeFactory = new ShapeFactory(library);
        Circuit circuit = Circuit.loadCircuit(dig, shapeFactory);
        // all elements need a valid shape
        for (VisualElement ve : circuit.getElements())
            assertNotNull(ve.getPins());
        Model model = new ModelCreator(circuit, library).createModel(false);
        model.init(true);
        return model;
    }

    static ObservableValue input(Model m, String name) {
        for (Signal s : m.getInputs())
            if (s.getName().equals(name))
                return s.getValue();
        fail("input " + name + " not found");
        return null;
    }

    static ObservableValue output(Model m, String name) {
        for (Signal s : m.getOutputs())
            if (s.getName().equals(name))
                return s.getValue();
        fail("output " + name + " not found");
        return null;
    }

    static void clock(Model m, ObservableValue clk) {
        clk.setValue(1);
        m.doStep();
        clk.setValue(0);
        m.doStep();
    }

    public void testCounter() throws Exception {
        File bdf = new File(Resources.getRoot(), "quartus/counter/counter.bdf");
        QuartusImporter imp = QuartusImporter.importFile(bdf, tempDir());
        assertEquals(imp.getWarnings().toString(), 0, imp.getWarnings().size());

        Model m = createModel(imp.getTopFile());
        ObservableValue en = input(m, "en");
        // the pin clk drives flip-flops, so it is imported as a clock
        assertEquals(1, m.getClocks().size());
        ObservableValue clk = m.getClocks().get(0).getClockOutput();
        ObservableValue q = output(m, "q");
        assertEquals(2, q.getBits());

        en.setValue(1);
        m.doStep();
        assertEquals(0, q.getValue());
        for (int i = 1; i <= 6; i++) {
            clock(m, clk);
            assertEquals(i & 3, q.getValue());
        }
        en.setValue(0);
        m.doStep();
        clock(m, clk);
        assertEquals(2, q.getValue());
        m.close();
    }

    public void testUserProject() throws Exception {
        File qpf = new File(Resources.getRoot(), "quartus/afficheur8d/Afficheur8D.qpf");
        QuartusImporter imp = QuartusImporter.importFile(qpf, tempDir());
        assertEquals("Afficheur8D.dig", imp.getTopFile().getName());
        // the sources of the sub designs are missing
        assertEquals(imp.getWarnings().toString(), 5, imp.getWarnings().size());

        Model m = createModel(imp.getTopFile());
        assertEquals(7, output(m, "c").getBits());
        m.close();
    }

    public void testMegaWizardCounter() throws Exception {
        File qpf = new File(Resources.getRoot(), "quartus/megawizard/top.qpf");
        QuartusImporter imp = QuartusImporter.importFile(qpf, tempDir());
        assertEquals(imp.getWarnings().toString(), 0, imp.getWarnings().size());
        assertEquals("N2", imp.getProject().getPinLocation("clk"));

        Model m = createModel(imp.getTopFile());
        ObservableValue en = input(m, "en");
        ObservableValue clk = m.getClocks().get(0).getClockOutput();
        ObservableValue count = output(m, "count");
        ObservableValue carry = output(m, "carry");
        ObservableValue hi = output(m, "hi");
        assertEquals(4, count.getBits());

        en.setValue(1);
        m.doStep();
        for (int i = 1; i <= 25; i++) {
            clock(m, clk);
            long v = i % 10;
            assertEquals(v, count.getValue());
            assertEquals(v == 9 ? 1 : 0, carry.getValue());
            assertEquals(v == 9 ? 1 : 0, hi.getValue());
        }
        m.close();
    }

    private static void write(File dir, String name, String text) throws IOException {
        Files.write(new File(dir, name).toPath(), text.getBytes(StandardCharsets.ISO_8859_1));
    }

    public void testVerilogProject() throws Exception {
        // the top level module instantiates a module of another file, simulated by Icarus Verilog or the built-in simulator
        File dir = tempDir();
        write(dir, "vproj.qsf", "set_global_assignment -name TOP_LEVEL_ENTITY top\n"
                + "set_global_assignment -name VERILOG_FILE top.v\n"
                + "set_global_assignment -name VERILOG_FILE cnt.v\n");
        write(dir, "top.v", "module top(input clk, input rst_n, output [3:0] q, output tick);\n"
                + "  counter #(.MAX(9)) u0 (.clk(clk), .rst_n(rst_n), .q(q));\n"
                + "  assign tick = (q == 4'd9);\n"
                + "endmodule\n");
        write(dir, "cnt.v", "// a counter with an asynchronous reset\n"
                + "module counter #(parameter MAX = 15) (input clk, rst_n, output reg [3:0] q);\n"
                + "  always @(posedge clk or negedge rst_n)\n"
                + "    if (!rst_n) q <= 4'd0;\n"
                + "    else if (q == MAX) q <= 4'd0;\n"
                + "    else q <= q + 4'd1;\n"
                + "endmodule\n");
        QuartusImporter imp = QuartusImporter.importFile(new File(dir, "vproj.qsf"), new File(dir, "digital"));
        assertEquals(imp.getWarnings().toString(), 0, imp.getWarnings().size());

        Model m = createModel(imp.getTopFile());
        ObservableValue clk = input(m, "clk");
        ObservableValue rst = input(m, "rst_n");
        ObservableValue q = output(m, "q");
        ObservableValue tick = output(m, "tick");
        rst.setValue(0);
        m.doStep();
        assertEquals(0, q.getValue());
        rst.setValue(1);
        m.doStep();
        for (int i = 1; i <= 25; i++) {
            clock(m, clk);
            assertEquals(i % 10, q.getValue());
            assertEquals(i % 10 == 9 ? 1 : 0, tick.getValue());
        }
        m.close();
    }

    public void testVhdlProject() throws Exception {
        // a package, an entity using an enumeration type and generics, a top level entity with component instances
        File labs = tempDir();
        // another project in a neighbouring folder declares an entity with the same name, it must not be used
        File other = new File(labs, "lab1");
        assertTrue(other.mkdirs());
        write(other, "blink.vhd", "library ieee; use ieee.std_logic_1164.all;\n"
                + "entity blink is port (clk, rst : in std_logic; led : out std_logic); end blink;\n"
                + "architecture wrong of blink is begin led <= '1'; end wrong;\n");
        File dir = new File(labs, "lab2");
        assertTrue(dir.mkdirs());
        write(dir, "vhdlproj.qsf", "set_global_assignment -name TOP_LEVEL_ENTITY vtop\n"
                + "set_global_assignment -name VHDL_FILE defs.vhd\n"
                + "set_global_assignment -name VHDL_FILE blink.vhd\n"
                + "set_global_assignment -name VHDL_FILE vtop.vhd\n");
        write(dir, "defs.vhd", "library ieee; use ieee.std_logic_1164.all;\n"
                + "package defs is\n"
                + "  constant LIMIT : integer := 3;\n"
                + "  type mode_t is (OFF, ON_STATE);\n"
                + "end package defs;\n");
        write(dir, "blink.vhd", "library ieee; use ieee.std_logic_1164.all;\n"
                + "use work.defs.all;\n"
                + "entity blink is\n"
                + "  generic (N : integer := LIMIT);\n"
                + "  port (clk, rst : in std_logic; led : out std_logic);\n"
                + "end blink;\n"
                + "architecture rtl of blink is\n"
                + "  signal mode : mode_t := OFF;\n"
                + "  signal cnt : integer range 0 to 15 := 0;\n"
                + "begin\n"
                + "  process (clk, rst)\n"
                + "  begin\n"
                + "    if rst = '1' then\n"
                + "      mode <= OFF;\n"
                + "      cnt <= 0;\n"
                + "    elsif rising_edge(clk) then\n"
                + "      if cnt = N - 1 then\n"
                + "        cnt <= 0;\n"
                + "        case mode is\n"
                + "          when OFF => mode <= ON_STATE;\n"
                + "          when ON_STATE => mode <= OFF;\n"
                + "        end case;\n"
                + "      else\n"
                + "        cnt <= cnt + 1;\n"
                + "      end if;\n"
                + "    end if;\n"
                + "  end process;\n"
                + "  led <= '1' when mode = ON_STATE else '0';\n"
                + "end rtl;\n");
        write(dir, "vtop.vhd", "library ieee; use ieee.std_logic_1164.all;\n"
                + "entity vtop is\n"
                + "  port (clk, rst : in std_logic; led : out std_logic_vector(1 downto 0));\n"
                + "end vtop;\n"
                + "architecture s of vtop is\n"
                + "  component blink\n"
                + "    generic (N : integer := 3);\n"
                + "    port (clk, rst : in std_logic; led : out std_logic);\n"
                + "  end component;\n"
                + "begin\n"
                + "  b0 : blink port map (clk => clk, rst => rst, led => led(0));\n"
                + "  b1 : blink generic map (N => 2) port map (clk, rst, led(1));\n"
                + "end s;\n");
        QuartusImporter imp = QuartusImporter.importFile(new File(dir, "vhdlproj.qsf"), new File(dir, "digital"));
        assertEquals(imp.getWarnings().toString(), 0, imp.getWarnings().size());

        Model m = createModel(imp.getTopFile());
        ObservableValue clk = input(m, "clk");
        ObservableValue rst = input(m, "rst");
        ObservableValue led = output(m, "led");
        rst.setValue(1);
        m.doStep();
        rst.setValue(0);
        m.doStep();
        assertEquals(0, led.getValue());
        for (int i = 1; i <= 24; i++) {
            clock(m, clk);
            long expected = ((i / 3) % 2) | (((i / 2) % 2) << 1);
            assertEquals("clock " + i, expected, led.getValue());
        }
        m.close();
    }

    public void testModifiedFilesAreNotOverwritten() throws Exception {
        File bdf = new File(Resources.getRoot(), "quartus/counter/counter.bdf");
        File out = tempDir();
        QuartusImporter imp = QuartusImporter.importFile(bdf, out);
        assertEquals(0, imp.getWarnings().size());
        // second import overwrites the unmodified file
        imp = QuartusImporter.importFile(bdf, out);
        assertEquals(0, imp.getWarnings().size());
        // a modified file is kept
        File dig = imp.getTopFile();
        assertTrue(dig.setLastModified(dig.lastModified() + 10000));
        imp = QuartusImporter.importFile(bdf, out);
        assertEquals(1, imp.getWarnings().size());
    }

    public void testMacrofunctionAndMemoryFile() throws Exception {
        File bdf = new File(Resources.getRoot(), "quartus/parts/parts.bdf");
        QuartusImporter imp = QuartusImporter.importFile(bdf, tempDir());
        assertEquals(imp.getWarnings().toString(), 0, imp.getWarnings().size());

        Model m = createModel(imp.getTopFile());
        // the ROM is initialized by the mif file
        ObservableValue addr = input(m, "addr");
        ObservableValue data = output(m, "data");
        for (int a = 0; a < 8; a++) {
            addr.setValue(a);
            m.doStep();
            assertEquals(3 * a + 1, data.getValue());
        }

        // the 74161 counts
        ObservableValue clk = m.getClocks().isEmpty() ? input(m, "clk") : m.getClocks().get(0).getClockOutput();
        ObservableValue[] q = {output(m, "qa"), output(m, "qb"), output(m, "qc"), output(m, "qd")};
        ObservableValue rco = output(m, "rco");
        for (int i = 1; i <= 20; i++) {
            clock(m, clk);
            long v = 0;
            for (int b = 0; b < 4; b++)
                v |= q[b].getValue() << b;
            assertEquals(i & 15, v);
            assertEquals((i & 15) == 15 ? 1 : 0, rco.getValue());
        }
        m.close();
    }

    public void testWaveformIsConvertedToTestCase() throws Exception {
        File bdf = new File(Resources.getRoot(), "quartus/parts/parts.bdf");
        QuartusImporter imp = QuartusImporter.importFile(bdf, tempDir());
        assertEquals(imp.getWarnings().toString(), 0, imp.getWarnings().size());

        File dig = imp.getTopFile();
        ElementLibrary library = new ElementLibrary();
        library.setRootFilePath(dig.getParentFile());
        Circuit circuit = Circuit.loadCircuit(dig, new ShapeFactory(library));
        List<Circuit.TestCase> tests = circuit.getTestCases();
        assertEquals(1, tests.size());
        TestCaseDescription d = tests.get(0).getTestCaseDescription();
        // a header and eight addresses
        int rows = 0;
        for (String l : d.getDataString().split("\n"))
            if (!l.trim().isEmpty() && !l.startsWith("#"))
                rows++;
        assertEquals(9, rows);
        Model model = new ModelCreator(circuit, library).createModel(false);
        assertTrue(new TestExecutor(d, model).execute().allPassed());
    }

    public void testDE0Board() throws Exception {
        File qpf = new File(Resources.getRoot(), "quartus/de0/de0test.qpf");
        QuartusImporter imp = QuartusImporter.importFile(qpf, tempDir());
        assertEquals(imp.getWarnings().toString(), 0, imp.getWarnings().size());
        assertNotNull(imp.getBoardFile());

        ElementLibrary library = new ElementLibrary();
        library.setRootFilePath(imp.getBoardFile().getParentFile());
        Circuit circuit = Circuit.loadCircuit(imp.getBoardFile(), new ShapeFactory(library));
        int displays = 0;
        for (VisualElement ve : circuit.getElements())
            if (ve.getElementName().equals("Seven-Seg"))
                displays++;
        assertEquals(4, displays);

        Model m = new ModelCreator(circuit, library).createModel(false);
        m.init(true);
        // the pins without a board resource are the pins of the board circuit
        ObservableValue vcomp = input(m, "vcomp");
        ObservableValue v = output(m, "v");
        vcomp.setValue(1);
        m.doStep();
        assertEquals(1, v.getValue());

        // the switch SW5 is registered with the 50MHz clock and shown on LEDG0
        assertEquals(1, m.getClocks().size());
        assertEquals(50000000, m.getClocks().get(0).getFrequency());
        clock(m, m.getClocks().get(0).getClockOutput());
        assertEquals(0, output(m, "LEDG0").getValue());
        m.close();

        // the switch is turned on before the simulation starts
        for (VisualElement ve : circuit.getElements())
            if (ve.getElementName().equals("DipSwitch") && ve.getElementAttributes().getLabel().equals("SW5"))
                ve.getElementAttributes().set(Keys.DIP_DEFAULT, true);
        m = new ModelCreator(circuit, library).createModel(false);
        m.init(true);
        clock(m, m.getClocks().get(0).getClockOutput());
        assertEquals(1, output(m, "LEDG0").getValue());
        m.close();
    }

    public void testExportAndImportAgain() throws Exception {
        File dig = new File(Resources.getRoot(), "../../main/dig/combinatorial/FullAdderRC.dig");
        ElementLibrary library = new ElementLibrary();
        library.setRootFilePath(dig.getParentFile());
        Circuit circuit = Circuit.loadCircuit(dig, new ShapeFactory(library));

        File dir = tempDir();
        BdfExporter exporter = new BdfExporter(library);
        exporter.export(circuit, new File(dir, "FullAdderRC.bdf"));
        assertEquals(exporter.getWarnings().toString(), 0, exporter.getWarnings().size());

        QuartusImporter imp = QuartusImporter.importFile(new File(dir, "FullAdderRC.bdf"), new File(dir, "digital"));
        assertEquals(imp.getWarnings().toString(), 0, imp.getWarnings().size());

        // the original test cases pass with the imported circuit
        ElementLibrary library2 = new ElementLibrary();
        library2.setRootFilePath(imp.getTopFile().getParentFile());
        Circuit imported = Circuit.loadCircuit(imp.getTopFile(), new ShapeFactory(library2));
        assertFalse(circuit.getTestCases().isEmpty());
        for (Circuit.TestCase tc : circuit.getTestCases()) {
            // the signal names are converted to valid Quartus names
            String[] lines = tc.getTestCaseDescription().getDataString().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i].trim();
                if (l.isEmpty() || l.startsWith("#"))
                    continue;
                StringBuilder sb = new StringBuilder();
                for (String name : l.split("\\s+"))
                    sb.append(BdfExporter.sanitize(name)).append(' ');
                lines[i] = sb.toString();
                break;
            }
            Model model = new ModelCreator(imported, library2).createModel(false);
            TestCaseDescription d = new TestCaseDescription(String.join("\n", lines));
            assertTrue(tc.getLabel(), new TestExecutor(d, model).execute().allPassed());
        }
    }

    public void testBoardAndProjectOfDigitalCircuit() throws Exception {
        // the example of the manual: E1 on SW0 (J6), E2 on SW1 (H5), the result on LEDG0 (J1)
        File dir = tempDir();
        File dig = new File(dir, "ouex.dig");
        ElementLibrary library = new ElementLibrary();
        library.setRootFilePath(dir);
        Circuit circuit = Circuit.loadCircuit(new File(Resources.getRoot(), "../../main/dig/combinatorial/Xor1.dig"), new ShapeFactory(library));
        for (VisualElement ve : circuit.getElements()) {
            String l = ve.getElementAttributes().getLabel();
            if (l.equals("A")) ve.getElementAttributes().set(Keys.PINNUMBER, "J6");
            if (l.equals("B")) ve.getElementAttributes().set(Keys.PINNUMBER, "PIN_H5");
            if (l.equals("Y")) ve.getElementAttributes().set(Keys.PINNUMBER, "J1");
        }
        circuit.save(dig);

        File board = Board.createBoardFor(circuit, dig);
        assertNotNull(board);
        library = new ElementLibrary();
        library.setRootFilePath(dir);
        Circuit bc = Circuit.loadCircuit(board, new ShapeFactory(library));
        for (int a = 0; a < 2; a++)
            for (int b = 0; b < 2; b++) {
                for (VisualElement ve : bc.getElements())
                    if (ve.getElementName().equals("DipSwitch")) {
                        String l = ve.getElementAttributes().getLabel();
                        if (l.equals("SW0")) ve.getElementAttributes().set(Keys.DIP_DEFAULT, a == 1);
                        if (l.equals("SW1")) ve.getElementAttributes().set(Keys.DIP_DEFAULT, b == 1);
                    }
                Model m = new ModelCreator(bc, library).createModel(false);
                m.init(true);
                assertEquals(a ^ b, output(m, "LEDG0").getValue());
                m.close();
            }

        // the export creates a Quartus project with the pin assignments
        BdfExporter exporter = new BdfExporter(library);
        exporter.export(circuit, new File(dir, "ouex.bdf"));
        String qsf = new String(Files.readAllBytes(new File(dir, "ouex.qsf").toPath()));
        assertTrue(qsf, qsf.contains("set_location_assignment PIN_J6 -to A"));
        assertTrue(qsf, qsf.contains("set_location_assignment PIN_H5 -to B"));
        assertTrue(qsf, qsf.contains("set_location_assignment PIN_J1 -to Y"));
        assertTrue(qsf, qsf.contains("DEVICE EP3C16F484C6"));
        assertTrue(new File(dir, "ouex.qpf").exists());
    }
}
