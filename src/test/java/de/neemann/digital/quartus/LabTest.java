/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus;

import de.neemann.digital.core.element.Keys;
import de.neemann.digital.draw.elements.Circuit;
import de.neemann.digital.draw.elements.VisualElement;
import de.neemann.digital.draw.library.ElementLibrary;
import de.neemann.digital.draw.shapes.ShapeFactory;
import de.neemann.digital.integration.Resources;
import junit.framework.TestCase;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

public class LabTest extends TestCase {

    public void testLab() throws Exception {
        ElementLibrary library = new ElementLibrary();
        ShapeFactory shapeFactory = new ShapeFactory(library);
        Circuit solution = Circuit.loadCircuit(new File(Resources.getRoot(), "../../main/dig/combinatorial/Xor1.dig"), shapeFactory);
        assertNull(Lab.of(solution));

        List<kotlin.Pair<String, String>> tests = Lab.testsOf(solution);
        assertEquals(1, tests.size());
        Lab lab = new Lab("XOR", "Draw a XOR", true, tests);
        Lab decoded = Lab.decode(lab.encode());
        assertEquals("XOR", decoded.getTitle());
        assertEquals("Draw a XOR", decoded.getText());
        assertTrue(decoded.getHints());
        assertEquals(tests, decoded.getTests());
        // the test data is not readable in the file
        assertFalse(lab.encode().contains("A B Y"));

        // the solution passes
        List<Lab.TestResult> r = lab.check(solution, library);
        assertTrue(r.get(0).getPassed());

        // the circuit for the students has no solution and no visible test
        Circuit student = Lab.createLabCircuit(solution, lab, true, shapeFactory);
        File f = Files.createTempFile("lab", ".dig").toFile();
        student.save(f);
        student = Circuit.loadCircuit(f, shapeFactory);
        assertNotNull(Lab.of(student));
        assertTrue(student.getTestCases().isEmpty());
        assertTrue(student.getWires().isEmpty());
        for (VisualElement ve : student.getElements())
            assertTrue(ve.getElementName(), ve.getElementName().equals("In") || ve.getElementName().equals("Out")
                    || ve.getElementName().equals("Text"));
        // the solution itself is unchanged
        assertFalse(solution.getWires().isEmpty());
        assertEquals("", solution.getAttributes().get(Keys.LAB_DATA));

        // the empty circuit fails, with a hint
        r = Lab.of(student).check(student, library);
        assertFalse(r.get(0).getPassed());
        assertEquals(1, r.size());
        assertTrue(r.get(0).getHint() + " / " + r.get(0).getError() + " / " + r.get(0).getRows(), r.get(0).getHint() != null || r.get(0).getError() != null);

        // the circuit with the solution and the hidden tests passes
        Circuit withSolution = Lab.createLabCircuit(solution, lab, false, shapeFactory);
        assertTrue(withSolution.getTestCases().isEmpty());
        Lab.TestResult ok = Lab.of(withSolution).check(withSolution, library).get(0);
        assertTrue(ok.getHint() + " / " + ok.getError(), ok.getPassed());
    }
}
