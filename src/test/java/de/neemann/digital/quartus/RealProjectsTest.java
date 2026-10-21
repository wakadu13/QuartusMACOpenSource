/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus;

import de.neemann.digital.integration.Resources;
import junit.framework.TestCase;

import java.io.File;

/**
 * Test bench: every project of the folder "projets" and every block of the EA library
 * must import and simulate, the tests created from waveform files must pass.
 */
public class RealProjectsTest extends TestCase {
    private static final File ROOT = new File(Resources.getRoot(), "../../..");

    private static void checkFolder(String name) {
        File dir = new File(ROOT, name);
        ProjectChecker checker = new ProjectChecker().check(dir);
        String report = checker.createReport(dir);
        System.out.println(report);
        assertTrue(report, checker.getResults().size() > 0);
        assertEquals(report, 0, checker.count(ProjectChecker.Status.ERROR));
    }

    public void testProjects() {
        checkFolder("projets");
    }

    public void testEaLibrary() {
        checkFolder("bibliotheque-EA");
    }
}
