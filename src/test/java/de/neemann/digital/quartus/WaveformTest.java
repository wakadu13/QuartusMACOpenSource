/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus;

import de.neemann.digital.draw.elements.Circuit;
import de.neemann.digital.draw.library.ElementLibrary;
import de.neemann.digital.draw.shapes.ShapeFactory;
import de.neemann.digital.integration.Resources;
import junit.framework.TestCase;

import java.io.File;

/**
 * Tests the waveforms of the waveform editor: functional and timing simulation, vwf files.
 */
public class WaveformTest extends TestCase {
    private ElementLibrary library;
    private Circuit circuit;

    @Override
    protected void setUp() throws Exception {
        library = new ElementLibrary();
        circuit = Circuit.loadCircuit(new File(Resources.getRoot(), "../../main/dig/combinatorial/Xor1.dig"), new ShapeFactory(library));
    }

    // (A or B) and (A nand B): A=0,1,0,1 B=0,0,1,1
    private Waveform createXor() throws Exception {
        Waveform w = Waveform.create(circuit, library, 400, 100);
        assertEquals(4, w.getStepCount());
        assertEquals(3, w.getTraces().size());
        Waveform.Trace a = w.get("A");
        Waveform.Trace b = w.get("B");
        assertTrue(a.getInput());
        assertFalse(w.get("Y").getInput());
        a.set(1, 2, 1);
        a.set(3, 4, 1);
        b.set(2, 4, 1);
        return w;
    }

    public void testFunctional() throws Exception {
        Waveform w = createXor();
        w.simulate(circuit, library, 0);
        assertTrue(w.getMessages().isEmpty());
        Waveform.Trace y = w.get("Y");
        assertEquals(0, y.resultAt(50));
        assertEquals(1, y.resultAt(100));
        assertEquals(1, y.resultAt(250));
        assertEquals(0, y.resultAt(300));
        assertEquals("A B Y\n0 0 0\n1 0 1\n0 1 1\n1 1 0\n", w.createTestData());
    }

    public void testTiming() throws Exception {
        Waveform w = createXor();
        w.simulate(circuit, library, 10);
        Waveform.Trace y = w.get("Y");
        // two gates between A and Y: the output changes 20ns after the input
        assertEquals(0, y.resultAt(105));
        assertEquals(0, y.resultAt(119));
        assertEquals(1, y.resultAt(120));
        assertEquals(1, y.resultAt(310));
        assertEquals(0, y.resultAt(320));
    }

    public void testLongDelay() throws Exception {
        Waveform w = createXor();
        w.simulate(circuit, library, 80);
        assertTrue(w.getMessages().contains(Waveform.LONG_DELAY));
    }

    public void testVwfRoundTrip() throws Exception {
        Waveform w = createXor();
        w.simulate(circuit, library, 0);
        String vwf = w.toVwf();
        assertTrue(vwf.contains("GRID_PERIOD = 100.0;"));
        assertTrue(vwf.contains("DATA_DURATION = 400.0;"));

        VwfFile f = VwfFile.parse(vwf);
        assertEquals(400.0, f.getDuration());
        assertEquals(100.0, f.getGridPeriod());

        Waveform w2 = Waveform.create(circuit, library, 1000, 10);
        assertTrue(w2.readInputs(f).isEmpty());
        assertEquals(4, w2.getStepCount());
        assertEquals(1, w2.get("A").step(1));
        assertEquals(1, w2.get("B").step(3));
        assertEquals(0, w2.get("B").step(1));
        w2.simulate(circuit, library, 0);
        assertEquals(w.createTestData(), w2.createTestData());
    }

    public void testBus() throws Exception {
        Waveform w = new Waveform(100, 10);
        Waveform.Trace t = w.add(new Waveform.Trace("Q", 4, true, false));
        t.count(0, 10, 1, 14);
        assertEquals(14, t.step(0));
        assertEquals(15, t.step(1));
        assertEquals(0, t.step(2));
        assertEquals("1110", w.text(t, 14, Waveform.Radix.BINARY));
        assertEquals("E", w.text(t, 14, Waveform.Radix.HEX));
        assertEquals("-2", w.text(t, 14, Waveform.Radix.SIGNED));
        assertEquals("X", w.text(t, -1, Waveform.Radix.HEX));

        String vwf = w.toVwf();
        assertTrue(vwf.contains("SIGNAL(\"Q[3]\")"));
        VwfFile f = VwfFile.parse(vwf);
        Waveform w2 = new Waveform(100, 10);
        Waveform.Trace t2 = w2.add(new Waveform.Trace("Q", 4, true, false));
        w2.readInputs(f);
        for (int i = 0; i < 10; i++)
            assertEquals(t.step(i), t2.step(i));
    }

    public void testFormat() {
        assertEquals("10.0", Waveform.format(10));
        assertEquals("2.5", Waveform.format(2.5));
        assertEquals("0.125", Waveform.format(0.125));
    }

    public void testTimes() {
        assertEquals(10.0, Waveform.parseTime("10"));
        assertEquals(2500.0, Waveform.parseTime("2.5 us"));
        assertEquals(2500.0, Waveform.parseTime("2,5µs"));
        assertEquals(1e6, Waveform.parseTime("1 ms"));
        assertNull(Waveform.parseTime("abc"));
        assertEquals("1 ms", Waveform.formatTime(1e6));
        assertEquals("2.5 µs", Waveform.formatTime(2500));
        assertEquals("10 ns", Waveform.formatTime(10));
        assertEquals("500 ms", Waveform.formatTime(5e8));
    }
}
