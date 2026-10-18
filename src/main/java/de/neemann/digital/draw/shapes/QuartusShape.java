/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.draw.shapes;

import de.neemann.digital.core.Value;
import de.neemann.digital.core.element.PinDescription;
import de.neemann.digital.core.element.PinDescriptions;
import de.neemann.digital.draw.elements.IOState;
import de.neemann.digital.draw.elements.Pin;
import de.neemann.digital.draw.elements.PinException;
import de.neemann.digital.draw.elements.Pins;
import de.neemann.digital.draw.graphics.Graphic;
import de.neemann.digital.draw.graphics.GraphicMinMax;
import de.neemann.digital.draw.graphics.Orientation;
import de.neemann.digital.draw.graphics.Style;
import de.neemann.digital.draw.graphics.Vector;
import de.neemann.digital.draw.shapes.custom.CustomShapeDescription;
import de.neemann.digital.lang.Lang;
import de.neemann.digital.quartus.ShapeBuilder;

/**
 * A shape which is defined by a drawing imported from Quartus.
 * The drawing is stored in the attributes of the visual element.
 * If an other shape is given, the interaction with the element is
 * delegated to this shape, so that inputs can be clicked.
 */
public class QuartusShape implements Shape {
    private static final int VALUE_RAD = 9;
    private static final Style INVISIBLE = Style.NORMAL.deriveStyle(0, false, new java.awt.Color(0, 0, 0, 0));
    private final CustomShapeDescription description;
    private final String label;
    private final Shape inner;
    private final Pins pins;
    private final boolean showInputValue;
    private final boolean showOutputValue;
    private IOState ioState;
    private Value value;

    /**
     * Creates a new instance
     *
     * @param description the drawing
     * @param inner       the original shape, used for interaction, maybe null
     * @param inputs      the inputs
     * @param outputs     the outputs
     * @throws PinException PinException
     */
    public QuartusShape(CustomShapeDescription description, Shape inner, PinDescriptions inputs, PinDescriptions outputs) throws PinException {
        this(description, inner, inputs, outputs, null);
    }

    /**
     * Creates a new instance
     *
     * @param description the drawing
     * @param inner       the original shape, used for interaction, maybe null
     * @param inputs      the inputs
     * @param outputs     the outputs
     * @param label       the label to draw at the label position of the drawing, maybe null
     * @throws PinException PinException
     */
    public QuartusShape(CustomShapeDescription description, Shape inner, PinDescriptions inputs, PinDescriptions outputs, String label) throws PinException {
        this.description = description;
        this.label = label;
        this.inner = inner;
        pins = new Pins();
        for (PinDescription p : outputs)
            pins.add(new Pin(find(ShapeBuilder.OUT, p.getName()), p));
        for (PinDescription p : inputs)
            pins.add(new Pin(find(ShapeBuilder.IN, p.getName()), p));
        boolean hasValuePos = description.getPinOrNull(ShapeBuilder.VALUE_POS) != null;
        showInputValue = hasValuePos && inputs.size() == 1 && outputs.size() == 0;
        showOutputValue = hasValuePos && inputs.size() == 0 && outputs.size() == 1;
    }

    private Vector find(String prefix, String name) throws PinException {
        CustomShapeDescription.Pin p = description.getPinOrNull(prefix + name);
        if (p == null)
            p = description.getPinOrNull(name);
        if (p == null)
            throw new PinException(Lang.get("err_customShapeHasNoPin_N", name));
        return p.getPos();
    }

    @Override
    public Pins getPins() {
        return pins;
    }

    @Override
    public InteractorInterface applyStateMonitor(IOState ioState) {
        this.ioState = ioState;
        if (inner != null)
            return inner.applyStateMonitor(ioState);
        return null;
    }

    @Override
    public void readObservableValues() {
        if (inner != null)
            inner.readObservableValues();
        if (ioState != null) {
            if (showInputValue)
                value = ioState.getInput(0).getCopy();
            else if (showOutputValue)
                value = ioState.getOutput(0).getCopy();
        }
    }

    @Override
    public boolean showPinMarkers() {
        return false;
    }

    @Override
    public void drawTo(Graphic graphic, Style highLight) {
        boolean empty = true;
        for (CustomShapeDescription.Holder d : description) {
            d.drawTo(graphic, highLight);
            empty = false;
        }
        if (empty && graphic instanceof GraphicMinMax) {
            // invisible helper element: draw an invisible point to give the element a bounding box
            Vector p = new Vector(0, 0);
            if (pins.size() > 0)
                p = pins.iterator().next().getPos();
            graphic.drawLine(p, p.add(1, 0), INVISIBLE);
        }

        CustomShapeDescription.TextHolder l = description.getLabel();
        if (l != null && label != null && !label.isEmpty())
            l.drawText(graphic, label);

        if (value != null) {
            CustomShapeDescription.Pin vp = description.getPinOrNull(ShapeBuilder.VALUE_POS);
            if (vp != null) {
                Vector c = vp.getPos();
                if (value.getBits() == 1) {
                    Vector r = new Vector(VALUE_RAD, VALUE_RAD);
                    graphic.drawCircle(c.sub(r), c.add(r), Style.getWireStyle(value));
                } else
                    graphic.drawText(c.add(0, -VALUE_RAD * 2), value.toString(), Orientation.CENTERBOTTOM, Style.NORMAL);
            }
        }
    }
}
