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
}
