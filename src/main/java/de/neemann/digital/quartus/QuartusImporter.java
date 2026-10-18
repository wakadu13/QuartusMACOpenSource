/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus;

import de.neemann.digital.core.element.ElementAttributes;
import de.neemann.digital.core.element.Keys;
import de.neemann.digital.core.extern.Application;
import de.neemann.digital.core.extern.ExternalFile;
import de.neemann.digital.core.io.Const;
import de.neemann.digital.core.io.In;
import de.neemann.digital.core.io.Out;
import de.neemann.digital.draw.elements.Circuit;
import de.neemann.digital.draw.elements.VisualElement;
import de.neemann.digital.draw.elements.Wire;
import de.neemann.digital.draw.graphics.Vector;
import de.neemann.digital.draw.shapes.CustomCircuitShapeType;
import de.neemann.digital.draw.shapes.custom.CustomShapeDescription;
import de.neemann.digital.quartus.lib.Params;
import de.neemann.digital.quartus.lib.QuartusPart;
import de.neemann.digital.quartus.lib.QuartusParts;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Imports a Quartus project or a single Quartus design file.
 * Every design unit (block design file, VHDL entity, Verilog module, ...) is converted
 * to a Digital circuit which is stored in the output folder. The block design files
 * are converted to schematics which look like the Quartus schematics.
 * VHDL and Verilog files are simulated using GHDL or Icarus Verilog, wrappers of
 * megafunctions created by the MegaWizard plug-in manager are simulated natively.
 */
public final class QuartusImporter {
    private static final String[] EXTENSIONS = {".bdf", ".vhd", ".vhdl", ".v", ".sv", ".tdf", ".bsf", ".gdf"};
    private static final Pattern AHDL_SUBDESIGN = Pattern.compile(
            "(?is)\\bSUBDESIGN\\s+(\\w+)\\s*\\((.*?)\\)\\s*(VARIABLE|BEGIN)");

    private final QuartusProject project;
    private final File outDir;
    private final ArrayList<String> warnings = new ArrayList<>();
    private final HashMap<String, DesignInterface> interfaces = new HashMap<>();
    private final HashSet<String> inProgress = new HashSet<>();
    private final ArrayList<File> created = new ArrayList<>();
    private HashMap<String, File> fileIndex;
    private HashMap<String, HdlEntity> entityIndex;

    /**
     * Creates a new importer
     *
     * @param project the project
     * @param outDir  the folder to store the Digital circuits in
     */
    public QuartusImporter(QuartusProject project, File outDir) {
        this.project = project;
        this.outDir = outDir;
    }

    /**
     * Imports the given file. This can be a project file (qpf, qsf), a block design file (bdf),
     * a block symbol file (bsf) or a HDL file (vhd, v).
     * The circuits are stored in a folder "digital" next to the given file.
     *
     * @param file the file to import
     * @return the importer containing the warnings and the created files
     * @throws IOException IOException
     */
    public static QuartusImporter importFile(File file) throws IOException {
        File f = file.getAbsoluteFile();
        return importFile(f, new File(f.getParentFile(), "digital"));
    }

    /**
     * Imports the given file.
     *
     * @param file   the file to import
     * @param outDir the folder to store the circuits in
     * @return the importer containing the warnings and the created files
     * @throws IOException IOException
     */
    public static QuartusImporter importFile(File file, File outDir) throws IOException {
        File f = file.getAbsoluteFile();
        String n = f.getName().toLowerCase(Locale.ROOT);
        QuartusProject p;
        if (n.endsWith(".qpf") || n.endsWith(".qsf"))
            p = QuartusProject.open(f);
        else {
            p = findProject(f);
            p.setTopLevelEntity(QuartusProject.stem(f.getName()));
        }
        QuartusImporter imp = new QuartusImporter(p, outDir);
        imp.importTop(n.endsWith(".qpf") || n.endsWith(".qsf") ? null : f);
        return imp;
    }

    /*
     * Looks for a project in the directory of the given file, so that pin assignments and search paths are known.
     */
    private static QuartusProject findProject(File f) throws IOException {
        File dir = f.getParentFile();
        File[] qsf = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".qsf"));
        if (qsf != null && qsf.length > 0) {
            for (File q : qsf) {
                QuartusProject p = QuartusProject.open(q);
                if (p.getTopLevelEntity().equalsIgnoreCase(QuartusProject.stem(f.getName())))
                    return p;
            }
            return QuartusProject.open(qsf[0]);
        }
        return new QuartusProject(dir);
    }

    private void importTop(File topFile) throws IOException {
        if (!outDir.exists() && !outDir.mkdirs())
            throw new IOException("could not create folder " + outDir);
        String top = project.getTopLevelEntity();
        if (topFile != null && top.equalsIgnoreCase(QuartusProject.stem(topFile.getName()))) {
            getIndex().put(key(top), topFile);
        }
        DesignInterface ifc = getInterface(top, null, true);
        if (ifc.getKind() == DesignInterface.Kind.BLACKBOX)
            throw new IOException("the top level design '" + top + "' was not found");
    }

    /**
     * @return the circuit file of the top level design
     */
    public File getTopFile() {
        return new File(outDir, getElementName(interfaces.get(key(project.getTopLevelEntity()))));
    }

    /**
     * @return the created files
     */
    public List<File> getCreatedFiles() {
        return created;
    }

    /**
     * @return the warnings
     */
    public List<String> getWarnings() {
        return warnings;
    }

    /**
     * @return the project
     */
    public QuartusProject getProject() {
        return project;
    }

    void warn(String w) {
        if (!warnings.contains(w))
            warnings.add(w);
    }

    /**
     * Returns the name of the Digital element representing the given design unit
     *
     * @param ifc the interface of the design unit
     * @return the name of the element
     */
    public String getElementName(DesignInterface ifc) {
        return ifc.getName() + ".dig";
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------------------------
    // search the sources
    // ------------------------------------------------------------------------------------

    private HashMap<String, File> getIndex() {
        if (fileIndex == null) {
            fileIndex = new HashMap<>();
            for (File f : project.getDesignFiles())
                addToIndex(f);
            ArrayList<File> dirs = new ArrayList<>();
            File pd = project.getProjectDir();
            dirs.add(pd);
            dirs.addAll(project.getSearchPaths());
            for (File f : project.getDesignFiles())
                if (f.getParentFile() != null)
                    dirs.add(f.getParentFile());
            addSubDirs(pd, dirs);
            // libraries are often stored in sibling folders
            File parentDir = pd.getParentFile();
            if (parentDir != null)
                addSubDirs(parentDir, dirs);
            for (File d : dirs) {
                File[] list = d.listFiles();
                if (list != null)
                    for (File f : list)
                        if (f.isFile())
                            addToIndex(f);
            }
        }
        return fileIndex;
    }

    private void addSubDirs(File dir, List<File> dirs) {
        File[] list = dir.listFiles();
        if (list != null)
            for (File f : list)
                if (f.isDirectory() && !f.getName().startsWith(".") && !f.getName().equals("db")
                        && !f.getName().equals("incremental_db") && !f.equals(outDir) && !dirs.contains(f))
                    dirs.add(f);
    }

    private void addToIndex(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        for (int prio = 0; prio < EXTENSIONS.length; prio++) {
            if (n.endsWith(EXTENSIONS[prio])) {
                String k = key(QuartusProject.stem(f.getName()));
                File present = fileIndex.get(k);
                if (present == null || prio < priority(present))
                    fileIndex.put(k, f);
                return;
            }
        }
    }

    private static int priority(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        for (int i = 0; i < EXTENSIONS.length; i++)
            if (n.endsWith(EXTENSIONS[i]))
                return i;
        return EXTENSIONS.length;
    }

    private HdlEntity findEntity(String name) {
        if (entityIndex == null) {
            entityIndex = new HashMap<>();
            HashSet<File> files = new HashSet<>(project.getDesignFiles());
            files.addAll(getIndex().values());
            for (File f : files) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (f.exists() && (n.endsWith(".vhd") || n.endsWith(".vhdl") || n.endsWith(".v") || n.endsWith(".sv"))) {
                    try {
                        for (HdlEntity e : HdlEntity.read(f))
                            entityIndex.putIfAbsent(key(e.getName()), e);
                    } catch (IOException e) {
                        warn("could not read " + f + ": " + e.getMessage());
                    }
                }
            }
        }
        return entityIndex.get(key(name));
    }

    // ------------------------------------------------------------------------------------
    // convert the design units
    // ------------------------------------------------------------------------------------

    /**
     * Returns the interface of a design unit. If necessary the unit is converted.
     *
     * @param name   the name of the design unit
     * @param symbol a symbol which instantiates this unit, maybe null
     * @return the interface
     */
    DesignInterface getInterface(String name, BdfFile.Symbol symbol) {
        return getInterface(name, symbol, false);
    }

    private DesignInterface getInterface(String name, BdfFile.Symbol symbol, boolean top) {
        String k = key(name);
        DesignInterface ifc = interfaces.get(k);
        if (ifc != null)
            return ifc;
        if (inProgress.contains(k)) {
            warn("recursive instantiation of '" + name + "'");
            return blackBox(name, symbol, "recursive instantiation");
        }
        inProgress.add(k);
        try {
            ifc = convert(name, symbol, top);
        } catch (IOException | RuntimeException e) {
            warn("error importing '" + name + "': " + e.getMessage());
            ifc = blackBox(name, symbol, e.getMessage());
        } finally {
            inProgress.remove(k);
        }
        interfaces.put(k, ifc);
        return ifc;
    }

    private DesignInterface convert(String name, BdfFile.Symbol symbol, boolean top) throws IOException {
        File src = getIndex().get(key(name));
        HdlEntity entity = null;
        if (src == null || !isHdl(src)) {
            HdlEntity e = findEntity(name);
            if (e != null && (src == null || src.getName().toLowerCase(Locale.ROOT).endsWith(".bsf"))) {
                entity = e;
                src = e.getFile();
            }
        }
        if (src == null) {
            warn("source of '" + name + "' not found, it is imported as an empty block");
            return blackBox(name, symbol, "source not found");
        }
        String n = src.getName().toLowerCase(Locale.ROOT);
        if (n.endsWith(".bdf"))
            return convertBdf(name, src, symbol, top);
        if (isHdl(src)) {
            if (entity == null) {
                for (HdlEntity e : HdlEntity.read(src))
                    if (key(e.getName()).equals(key(name)))
                        entity = e;
                if (entity == null) {
                    List<HdlEntity> all = HdlEntity.read(src);
                    if (all.isEmpty())
                        throw new IOException("no entity or module found in " + src.getName());
                    entity = all.get(0);
                }
            }
            return convertHdl(name, entity, symbol);
        }
        if (n.endsWith(".tdf"))
            return convertAhdl(name, src, symbol);
        if (n.endsWith(".bsf")) {
            BdfFile bsf = BdfFile.read(src);
            if (!bsf.getSymbols().isEmpty()) {
                warn("only the symbol of '" + name + "' was found (" + src.getName() + "), it is imported as an empty block");
                return blackBox(name, bsf.getSymbols().get(0), "only the symbol file was found");
            }
        }
        warn("the file type of " + src.getName() + " is not supported, '" + name + "' is imported as an empty block");
        return blackBox(name, symbol, "unsupported file type: " + src.getName());
    }

    private static boolean isHdl(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        return n.endsWith(".vhd") || n.endsWith(".vhdl") || n.endsWith(".v") || n.endsWith(".sv");
    }

    private DesignInterface convertBdf(String name, File src, BdfFile.Symbol symbol, boolean top) throws IOException {
        BdfFile bdf = BdfFile.read(src);
        ArrayList<DesignInterface.Port> ports = new ArrayList<>();
        for (BdfFile.Pin p : bdf.getPins()) {
            BusName bn = BusName.parse(p.getName());
            ports.add(new DesignInterface.Port(p.getName(), p.direction, bn.getBits(), p.isDefaultVCC()));
        }
        DesignInterface ifc = new DesignInterface(unitName(name, src), DesignInterface.Kind.BDF, ports);
        // register the interface before the conversion to allow the lookup of the element name
        interfaces.put(key(name), ifc);
        Circuit c = new BdfConverter(this, bdf, ifc.getName(), top, ifc).convert();
        c.getAttributes().set(Keys.DESCRIPTION, "Imported from Quartus: " + src.getName());
        setSymbolShape(c, ifc, symbol);
        save(c, ifc);
        return ifc;
    }

    private static String unitName(String name, File src) {
        String stem = QuartusProject.stem(src.getName());
        if (stem.equalsIgnoreCase(name))
            return stem;
        return name;
    }

    /*
     * The shape used if the user places a new instance of this circuit: the drawing used by Quartus.
     */
    private void setSymbolShape(Circuit c, DesignInterface ifc, BdfFile.Symbol symbol) {
        CustomShapeDescription shape = null;
        if (symbol != null) {
            Vector pos = ShapeBuilder.scale(new BdfFile.Point(symbol.rect.x1, symbol.rect.y1));
            ShapeBuilder sb = new ShapeBuilder(pos);
            sb.add(symbol.drawing, symbol.rect.x1, symbol.rect.y1);
            sb.addText(symbol.type, symbol.rect.x1, symbol.rect.y1);
            HashSet<String> found = new HashSet<>();
            for (BdfFile.Port port : symbol.ports) {
                DesignInterface.Port ip = ifc.find(port.getName());
                if (ip != null && found.add(ip.getLabel())) {
                    if (port.line != null)
                        sb.add(port.line, symbol.rect.x1, symbol.rect.y1);
                    if (port.label != null && !port.label.invisible)
                        sb.addText(port.label, symbol.rect.x1, symbol.rect.y1);
                    BdfFile.Point p = symbol.getAbsolutePos(port);
                    sb.addPin(ip.getLabel(), p.x, p.y);
                }
            }
            if (found.size() == ifc.getPorts().size())
                shape = sb.build();
        }
        if (shape == null)
            shape = blockShape(ifc, false);
        c.getAttributes().set(Keys.SHAPE_TYPE, CustomCircuitShapeType.CUSTOM);
        c.getAttributes().set(Keys.CUSTOM_SHAPE, shape);
    }

    private CustomShapeDescription blockShape(DesignInterface ifc, boolean prefixed) {
        ShapeBuilder sb = new ShapeBuilder(new Vector(0, 0));
        ArrayList<SymbolFactory.Port> in = new ArrayList<>();
        ArrayList<SymbolFactory.Port> out = new ArrayList<>();
        for (DesignInterface.Port p : ifc.getPorts()) {
            String label = p.getQuartusName();
            if (p.isOutput())
                out.add(new SymbolFactory.Port((prefixed ? ShapeBuilder.OUT : "") + p.getLabel(), label, p.getBits() > 1));
            else
                in.add(new SymbolFactory.Port((prefixed ? ShapeBuilder.IN : "") + p.getLabel(), label, p.getBits() > 1));
        }
        SymbolFactory.block(sb, 0, 0, ifc.getName(), in, out);
        return sb.build();
    }

    private void save(Circuit c, DesignInterface ifc) throws IOException {
        File f = new File(outDir, getElementName(ifc));
        c.save(f);
        created.add(f);
    }

    // ------------------------------------------------------------------------------------
    // design units without a schematic
    // ------------------------------------------------------------------------------------

    private DesignInterface blackBox(String name, BdfFile.Symbol symbol, String reason) {
        ArrayList<DesignInterface.Port> ports = new ArrayList<>();
        if (symbol != null)
            for (BdfFile.Port p : symbol.ports)
                ports.add(new DesignInterface.Port(p.getName(), p.direction, BusName.parse(p.getName()).getBits(), false));
        DesignInterface ifc = new DesignInterface(name, DesignInterface.Kind.BLACKBOX, ports).setInfo(reason);
        try {
            Circuit c = wrapperCircuit(ifc, null, null, null);
            c.getAttributes().set(Keys.DESCRIPTION, "Empty block, the outputs are zero: " + reason);
            setSymbolShape(c, ifc, symbol);
            save(c, ifc);
        } catch (IOException e) {
            warn("could not create the block '" + name + "': " + e.getMessage());
        }
        return ifc;
    }

    private DesignInterface convertAhdl(String name, File src, BdfFile.Symbol symbol) throws IOException {
        String text = new String(Files.readAllBytes(src.toPath()), StandardCharsets.ISO_8859_1)
                .replaceAll("(?s)%.*?%", " ").replaceAll("--[^\n]*", " ");
        Matcher m = AHDL_SUBDESIGN.matcher(text);
        if (!m.find())
            return blackBox(name, symbol, "AHDL file without SUBDESIGN");
        ArrayList<DesignInterface.Port> ports = new ArrayList<>();
        for (String decl : HdlEntity.splitTop(m.group(2), ';')) {
            int c = decl.indexOf(':');
            if (c < 0) continue;
            String type = decl.substring(c + 1).trim().toUpperCase(Locale.ROOT);
            BdfFile.Direction dir = type.startsWith("OUTPUT") ? BdfFile.Direction.OUTPUT
                    : type.startsWith("BIDIR") ? BdfFile.Direction.BIDIR : BdfFile.Direction.INPUT;
            boolean defHigh = type.contains("VCC");
            for (String pn : HdlEntity.splitTop(decl.substring(0, c), ','))
                if (!pn.trim().isEmpty())
                    ports.add(new DesignInterface.Port(pn.trim(), dir, BusName.parse(pn).getBits(), defHigh));
        }
        warn("AHDL is not supported, '" + name + "' (" + src.getName() + ") is imported as an empty block");
        DesignInterface ifc = new DesignInterface(unitName(name, src), DesignInterface.Kind.BLACKBOX, ports)
                .setInfo("AHDL is not supported");
        Circuit c = wrapperCircuit(ifc, null, null, null);
        c.getAttributes().set(Keys.DESCRIPTION, "AHDL design " + src.getName() + " is not supported, the outputs are zero");
        setSymbolShape(c, ifc, symbol);
        save(c, ifc);
        return ifc;
    }

    private DesignInterface convertHdl(String name, HdlEntity entity, BdfFile.Symbol symbol) throws IOException {
        ArrayList<DesignInterface.Port> ports = new ArrayList<>();
        for (HdlEntity.Port p : entity.getPorts())
            ports.add(new DesignInterface.Port(p.name, p.direction, p.bits, p.defaultHigh));

        String mf = entity.getMegafunction();
        if (mf != null) {
            DesignInterface ifc = new DesignInterface(entity.getName(), DesignInterface.Kind.MEGAFUNCTION, ports);
            QuartusPart part = QuartusParts.get(mf);
            Params params = new Params(part.getDefaultParams());
            for (Map.Entry<String, String> e : entity.getMegafunctionParams().entrySet())
                params.set(e.getKey(), e.getValue());
            // map the entity ports to the megafunction ports
            LinkedHashMap<String, String> portMap = new LinkedHashMap<>();
            StringBuilder used = new StringBuilder();
            for (Map.Entry<String, String> e : entity.getMegafunctionPortMap().entrySet()) {
                String sig = e.getValue().replaceAll("[\\(\\[].*", "").trim();
                DesignInterface.Port ip = ifc.find(sig);
                QuartusPart.PinSpec in = part.getInput(e.getKey());
                QuartusPart.PinSpec out = part.getOutput(e.getKey());
                if (ip != null && (in != null || out != null)) {
                    portMap.put(ip.getLabel(), (in != null ? ShapeBuilder.IN : ShapeBuilder.OUT) + (in != null ? in.getName() : out.getName()));
                    if (used.length() > 0)
                        used.append(',');
                    used.append(in != null ? in.getName() : out.getName());
                }
            }
            params.set(Params.PORTS, used.toString());
            Circuit c = wrapperCircuit(ifc, part, params, portMap);
            c.getAttributes().set(Keys.DESCRIPTION, "Megafunction " + mf.toUpperCase(Locale.ROOT)
                    + " imported from " + entity.getFile().getName() + "\n" + params);
            setSymbolShape(c, ifc, symbol);
            save(c, ifc);
            return ifc;
        }

        DesignInterface ifc = new DesignInterface(entity.getName(), DesignInterface.Kind.HDL, ports);
        Circuit c = wrapperCircuit(ifc, null, null, null, entity);
        c.getAttributes().set(Keys.DESCRIPTION, "Simulated by "
                + (entity.getLanguage() == HdlEntity.Language.VHDL ? "GHDL" : "Icarus Verilog")
                + ": " + entity.getFile().getAbsolutePath());
        setSymbolShape(c, ifc, symbol);
        save(c, ifc);
        return ifc;
    }

    private Circuit wrapperCircuit(DesignInterface ifc, QuartusPart part, Params params, Map<String, String> portMap) {
        return wrapperCircuit(ifc, part, params, portMap, null);
    }

    /*
     * Creates a circuit which contains an inner element and the input and output pins.
     * The inner element is a native part, an external HDL element or nothing (black box).
     */
    private Circuit wrapperCircuit(DesignInterface ifc, QuartusPart part, Params params,
                                   Map<String, String> portMap, HdlEntity entity) {
        Circuit c = new Circuit();
        ArrayList<DesignInterface.Port> ins = new ArrayList<>();
        ArrayList<DesignInterface.Port> outs = new ArrayList<>();
        for (DesignInterface.Port p : ifc.getPorts())
            if (p.isOutput())
                outs.add(p);
            else
                ins.add(p);

        int pinsX = 0;
        int blockX = 24 * 8;
        String innerName = null;
        ElementAttributes innerAttr = new ElementAttributes();
        ArrayList<SymbolFactory.Port> blockIn = new ArrayList<>();
        ArrayList<SymbolFactory.Port> blockOut = new ArrayList<>();
        // maps the interface label to the key of the inner element
        HashMap<String, String> innerKey = new HashMap<>();
        if (part != null) {
            innerName = part.getDigitalName();
            innerAttr.set(Keys.QUARTUS_PARAMS, params.toString()).set(Keys.LABEL, ifc.getName());
            for (QuartusPart.PinSpec s : part.getInputs())
                if (params.isPortUsed(s.getName()))
                    blockIn.add(new SymbolFactory.Port(ShapeBuilder.IN + s.getName(), s.getName(), s.getWidth(params) > 1));
            for (QuartusPart.PinSpec s : part.getOutputs())
                if (params.isPortUsed(s.getName()))
                    blockOut.add(new SymbolFactory.Port(ShapeBuilder.OUT + s.getName(), s.getName(), s.getWidth(params) > 1));
            innerKey.putAll(portMap);
        } else if (entity != null) {
            innerName = ExternalFile.DESCRIPTION.getName();
            StringBuilder inSpec = new StringBuilder();
            StringBuilder outSpec = new StringBuilder();
            for (HdlEntity.Port p : entity.getPorts()) {
                StringBuilder sb = p.direction == BdfFile.Direction.OUTPUT ? outSpec : inSpec;
                if (sb.length() > 0)
                    sb.append(',');
                sb.append(p.name);
                if (p.bits > 1)
                    sb.append(':').append(p.bits);
                DesignInterface.Port ip = ifc.find(p.name);
                if (p.direction == BdfFile.Direction.OUTPUT) {
                    blockOut.add(new SymbolFactory.Port(ShapeBuilder.OUT + p.name, p.name, p.bits > 1));
                    innerKey.put(ip.getLabel(), ShapeBuilder.OUT + p.name);
                } else {
                    blockIn.add(new SymbolFactory.Port(ShapeBuilder.IN + p.name, p.name, p.bits > 1));
                    innerKey.put(ip.getLabel(), ShapeBuilder.IN + p.name);
                }
            }
            innerAttr.set(Keys.LABEL, entity.getName())
                    .set(Keys.EXTERNAL_INPUTS, inSpec.toString())
                    .set(Keys.EXTERNAL_OUTPUTS, outSpec.toString())
                    .set(Keys.EXTERNAL_CODE_FILE, entity.getFile().getAbsoluteFile())
                    .set(Keys.APPLICATION_TYPE, entity.getLanguage() == HdlEntity.Language.VHDL
                            ? Application.Type.GHDL : Application.Type.IVERILOG);
        }

        // the positions of the pins of the inner element in quartus coordinates
        HashMap<String, BdfFile.Point> innerPins = new HashMap<>();
        int innerWidth = 0;
        if (innerName != null) {
            ShapeBuilder sb = new ShapeBuilder(ShapeBuilder.scale(new BdfFile.Point(blockX, 0)));
            SymbolFactory.block(sb, blockX, 0, part != null ? part.getQuartusName() : ifc.getName(), blockIn, blockOut);
            innerWidth = SymbolFactory.bodyWidth(part != null ? part.getQuartusName() : ifc.getName(), blockIn, blockOut) + 32;
            for (int i = 0; i < blockIn.size(); i++)
                innerPins.put(blockIn.get(i).getKey(), new BdfFile.Point(blockX, 32 + SymbolFactory.STEP * i));
            for (int i = 0; i < blockOut.size(); i++)
                innerPins.put(blockOut.get(i).getKey(), new BdfFile.Point(blockX + innerWidth, 32 + SymbolFactory.STEP * i));
            innerAttr.set(Keys.CUSTOM_SHAPE, sb.build());
            VisualElement ve = ShapeBuilder.createElement(innerName, ShapeBuilder.scale(new BdfFile.Point(blockX, 0)), innerAttr);
            c.add(ve);
        }

        int outX = blockX + Math.max(innerWidth, 64) + 24 * 8;
        int row = 0;
        for (DesignInterface.Port p : ins) {
            String k = innerKey.get(p.getLabel());
            BdfFile.Point target = k == null ? null : innerPins.get(k);
            int y = target != null ? target.y : 32 + SymbolFactory.STEP * (blockIn.size() + 2 + row++);
            addPin(c, true, p, pinsX, y);
            if (target != null)
                c.getWires().add(new Wire(ShapeBuilder.scale(new BdfFile.Point(pinsX, y)), ShapeBuilder.scale(target)));
        }
        row = 0;
        for (DesignInterface.Port p : outs) {
            String k = innerKey.get(p.getLabel());
            BdfFile.Point source = k == null ? null : innerPins.get(k);
            int y = source != null ? source.y : 32 + SymbolFactory.STEP * (blockOut.size() + 2 + row++);
            addPin(c, false, p, outX, y);
            if (source != null)
                c.getWires().add(new Wire(ShapeBuilder.scale(source), ShapeBuilder.scale(new BdfFile.Point(outX, y))));
            else {
                // undriven output: constant zero
                BdfFile.Point cp = new BdfFile.Point(outX - 48, y);
                Vector v = ShapeBuilder.scale(cp);
                ShapeBuilder sb = new ShapeBuilder(v);
                sb.addPinDigital(ShapeBuilder.OUT + "out", v);
                sb.text("GND", cp.x - 2, cp.y, 6, de.neemann.digital.draw.graphics.Orientation.RIGHTCENTER);
                VisualElement ve = ShapeBuilder.createElement(Const.DESCRIPTION.getName(), v);
                ve.getElementAttributes().setBits(p.getBits()).set(Keys.VALUE, 0L).set(Keys.CUSTOM_SHAPE, sb.build());
                c.add(ve);
                c.getWires().add(new Wire(v, ShapeBuilder.scale(new BdfFile.Point(outX, y))));
            }
        }
        return c;
    }

    private static void addPin(Circuit c, boolean input, DesignInterface.Port p, int x, int y) {
        Vector pos = ShapeBuilder.scale(new BdfFile.Point(x, y));
        ShapeBuilder sb = new ShapeBuilder(pos);
        if (input)
            SymbolFactory.inputPin(sb, x, y, p.getQuartusName(), ShapeBuilder.OUT + "out");
        else
            SymbolFactory.outputPin(sb, x, y, p.getQuartusName(), ShapeBuilder.IN + "in");
        VisualElement ve = ShapeBuilder.createElement(input ? In.DESCRIPTION.getName() : Out.DESCRIPTION.getName(), pos);
        ve.getElementAttributes()
                .set(Keys.LABEL, p.getLabel())
                .setBits(p.getBits())
                .set(Keys.CUSTOM_SHAPE, sb.build());
        c.add(ve);
    }
}
