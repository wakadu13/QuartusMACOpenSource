/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.release;

import junit.framework.TestCase;

public class ReleaseInfoTest extends TestCase {

    public void testIsNewer() {
        assertTrue(ReleaseInfo.isNewer("v1.0.1", "v1.0.0"));
        assertTrue(ReleaseInfo.isNewer("v1.1", "v1.0.9"));
        assertTrue(ReleaseInfo.isNewer("v2.0.0", "v1.9"));
        assertFalse(ReleaseInfo.isNewer("v1.0.0", "v1.0.0"));
        assertFalse(ReleaseInfo.isNewer("v1.0", "v1.0.0"));
        assertFalse(ReleaseInfo.isNewer("v1.0.0", "v1.2.0"));
    }

    public void testProjectIsThisFork() {
        assertEquals("wakadu13/QuartusMACOpenSource", ReleaseInfo.GITHUB_PROJECT);
    }
}
