/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus;

import de.neemann.digital.core.Model;
import de.neemann.digital.core.ObservableValue;
import de.neemann.digital.core.Signal;
import de.neemann.digital.draw.elements.Circuit;
import de.neemann.digital.draw.elements.VisualElement;
import de.neemann.digital.draw.library.ElementLibrary;
import de.neemann.digital.draw.model.ModelCreator;
import de.neemann.digital.draw.shapes.ShapeFactory;
import de.neemann.digital.integration.Resources;
import junit.framework.TestCase;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

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
}
