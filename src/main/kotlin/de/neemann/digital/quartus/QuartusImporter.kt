/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus

import de.neemann.digital.core.element.ElementAttributes
import de.neemann.digital.core.element.Keys
import de.neemann.digital.core.extern.Application
import de.neemann.digital.core.extern.ExternalFile
import de.neemann.digital.core.io.Const
import de.neemann.digital.core.io.In
import de.neemann.digital.core.io.Out
import de.neemann.digital.draw.elements.Circuit
import de.neemann.digital.draw.elements.Wire
import de.neemann.digital.draw.graphics.Orientation
import de.neemann.digital.draw.graphics.Vector
import de.neemann.digital.draw.elements.Tunnel
import de.neemann.digital.draw.elements.VisualElement
import de.neemann.digital.core.wiring.Splitter
import de.neemann.digital.draw.shapes.CustomCircuitShapeType
import de.neemann.digital.draw.shapes.custom.CustomShapeDescription
import de.neemann.digital.quartus.lib.Params
import de.neemann.digital.quartus.lib.QuartusPart
import de.neemann.digital.quartus.lib.QuartusParts
import de.neemann.digital.testing.TestCaseDescription
import de.neemann.digital.testing.TestCaseElement
import java.io.File
import java.io.IOException
import java.util.Properties
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Imports a Quartus project or a single Quartus design file.
 * Every design unit (block design file, VHDL entity, Verilog module, ...) is converted
 * to a Digital circuit stored in the output folder. Block design files are converted to
 * schematics which look like the Quartus schematics. VHDL and Verilog designs are simulated
 * by GHDL or Icarus Verilog, or by the built-in simulators [VhdlInterpreter] and [VerilogInterpreter]
 * if these are not installed. Wrappers of megafunctions created by the MegaWizard plug-in
 * manager are simulated natively. Waveform files (vwf) are converted to test cases.
 *
 * @param project the project
 * @param outDir  the folder to store the Digital circuits in
 */
class QuartusImporter(val project: QuartusProject, private val outDir: File) {
    /** the warnings */
    val warnings = ArrayList<String>()

    /** the created files */
    val createdFiles = ArrayList<File>()

    /** if true, input pins driving clock inputs are converted to clocks */
    val convertClocks = true

    private val interfaces = HashMap<String, DesignInterface>()
    private val inProgress = HashSet<String>()
    private val fileIndex: MutableMap<String, File> by lazy { buildIndex() }
    private val entityIndex: Map<String, HdlEntity> by lazy { buildEntityIndex() }
    private val importInfo: Properties by lazy {
        Properties().also { p -> File(outDir, IMPORT_INFO).takeIf { it.exists() }?.inputStream()?.use { p.load(it) } }
    }

    /** the circuit file of the top level design */
    val topFile: File get() = File(outDir, getElementName(interfaces[key(project.topLevelEntity)]!!))

    companion object {
        private val EXTENSIONS = listOf(".bdf", ".vhd", ".vhdl", ".v", ".sv", ".tdf", ".bsf", ".gdf")
        private val QUARTUS_EXTENSIONS = arrayOf("qpf", "qsf", "bdf", "bsf", "vhd", "vhdl", "v", "sv", "tdf")
        private val AHDL_SUBDESIGN = Regex("(?is)\\bSUBDESIGN\\s+(\\w+)\\s*\\((.*?)\\)\\s*(VARIABLE|BEGIN)")
        private const val SOURCE_NOT_FOUND = "source not found"
        private const val IMPORT_INFO = "quartus-import.properties"

        /** true if the given file is a Quartus file which can be imported */
        @JvmStatic
        fun isQuartusFile(f: File): Boolean = QUARTUS_EXTENSIONS.any { f.name.lowercase().endsWith(".$it") }

        /** creates a file filter for the Quartus files */
        @JvmStatic
        fun createFileFilter(description: String) = FileNameExtensionFilter(description, *QUARTUS_EXTENSIONS)

        /**
         * Imports the given file: a project file (qpf, qsf), a block design file (bdf),
         * a block symbol file (bsf) or a HDL file (vhd, v).
         * The circuits are stored in a folder "digital" next to the given file.
         */
        @JvmStatic
        @Throws(IOException::class)
        fun importFile(file: File): QuartusImporter = importFile(file, File(file.absoluteFile.parentFile, "digital"))

        /** imports the given file and stores the circuits in the given folder */
        @JvmStatic
        @Throws(IOException::class)
        fun importFile(file: File, outDir: File): QuartusImporter {
            val f = file.absoluteFile
            val n = f.name.lowercase()
            val isProject = n.endsWith(".qpf") || n.endsWith(".qsf")
            val p = if (isProject) QuartusProject.open(f) else findProject(f).also { it.topLevelEntity = QuartusProject.stem(f.name) }
            val imp = QuartusImporter(p, outDir)
            imp.importTop(if (isProject) null else f)
            return imp
        }

        /* looks for a project in the folder of the given file, so that pin assignments and search paths are known */
        private fun findProject(f: File): QuartusProject {
            val qsf = f.parentFile.listFiles { _, name -> name.lowercase().endsWith(".qsf") } ?: emptyArray()
            if (qsf.isEmpty()) return QuartusProject(f.parentFile)
            return qsf.map { QuartusProject.open(it) }.firstOrNull { it.topLevelEntity.equals(QuartusProject.stem(f.name), true) }
                ?: QuartusProject.open(qsf[0])
        }

        private fun key(name: String) = name.trim().lowercase()

        private fun isHdl(f: File) = f.name.lowercase().let { it.endsWith(".vhd") || it.endsWith(".vhdl") || it.endsWith(".v") || it.endsWith(".sv") }
    }

    private fun importTop(topFile: File?) {
        if (!outDir.exists() && !outDir.mkdirs()) throw IOException("could not create folder $outDir")
        val top = project.topLevelEntity
        if (topFile != null && top.equals(QuartusProject.stem(topFile.name), true)) fileIndex[key(top)] = topFile
        val ifc = getInterface(top, null, true)
        if (ifc.kind == DesignInterface.Kind.BLACKBOX && ifc.info == SOURCE_NOT_FOUND)
            throw IOException("the top level design '$top' was not found")
        createBoard(ifc)
    }

    /** the circuit showing the top level design on the development board, null if there is none */
    var boardFile: File? = null
        private set

    /*
     * If the pins are assigned to the displays, LEDs, switches or buttons of a known board,
     * a circuit showing the design on this board is created.
     */
    private fun createBoard(ifc: DesignInterface) {
        val board = Board.find(project.device, project.allPinLocations) ?: return
        val ports = ifc.ports.map { p ->
            val bn = BusName.parse(p.quartusName)
            val pins = if (p.bits == 1) listOf(project.getPinLocation(p.quartusName) ?: project.getPinLocation(bn.base))
            else bn.atoms.reversed().map { project.getPinLocation(it) }
            BoardCircuitBuilder.Port(p.label, !p.isOutput, p.bits, pins)
        }
        if (ports.none { p -> p.pins.any { it != null && board.getResource(it)?.isInput == p.input } }) return
        val f = File(outDir, ifc.name + "_" + board.name + ".dig")
        try {
            val c = BoardCircuitBuilder(board, outDir, getElementName(ifc)).build(ports)
            c.save(f)
            createdFiles.add(f)
            boardFile = f
        } catch (e: Exception) {
            warn("the ${board.name} board circuit could not be created: ${e.message}")
        }
    }

    internal fun warn(w: String) {
        if (w !in warnings) warnings.add(w)
    }

    /** returns the name of the Digital element representing the given design unit */
    fun getElementName(ifc: DesignInterface) = ifc.name + ".dig"

    // ------------------------------------------------------------------------------------
    // search the sources
    // ------------------------------------------------------------------------------------

    private fun searchDirs(): List<File> {
        val dirs = LinkedHashSet<File>()
        val pd = project.projectDir
        dirs.add(pd)
        dirs.addAll(project.searchPaths)
        project.designFiles.mapNotNullTo(dirs) { it.parentFile }
        addSubDirs(pd, dirs)
        // libraries are often stored in sibling folders, or next to the folder containing the projects
        pd.parentFile?.let { addSubDirs(it, dirs) }
        pd.parentFile?.parentFile?.let { addSubDirs(it, dirs) }
        return dirs.toList()
    }

    private fun addSubDirs(dir: File, dirs: MutableSet<File>) {
        dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "db" && it.name != "incremental_db" && it != outDir }
            ?.forEach { dirs.add(it) }
    }

    private fun buildIndex(): MutableMap<String, File> {
        val index = HashMap<String, File>()
        fun add(f: File) {
            val prio = EXTENSIONS.indexOfFirst { f.name.lowercase().endsWith(it) }
            if (prio < 0) return
            val k = key(QuartusProject.stem(f.name))
            val present = index[k]
            if (present == null || prio < EXTENSIONS.indexOfFirst { present.name.lowercase().endsWith(it) }) index[k] = f
        }
        project.designFiles.forEach { add(it) }
        for (d in searchDirs()) d.listFiles()?.filter { it.isFile }?.forEach { add(it) }
        return index
    }

    private fun buildEntityIndex(): Map<String, HdlEntity> {
        val index = HashMap<String, HdlEntity>()
        for (f in (project.designFiles + fileIndex.values).toSet().filter { it.exists() && isHdl(it) }) {
            try {
                HdlEntity.read(f).forEach { index.putIfAbsent(key(it.name), it) }
            } catch (e: IOException) {
                warn("could not read $f: ${e.message}")
            }
        }
        return index
    }

    /** resolves a data file (mif, hex) given relative to the source folder */
    internal fun resolveDataFile(name: String, sourceDir: File?): File? {
        val f = File(name.replace('\\', '/'))
        if (f.isAbsolute) return f.takeIf { it.exists() }
        val candidates = listOfNotNull(sourceDir, project.projectDir) + searchDirs()
        val found = candidates.map { File(it, f.path) }.firstOrNull { it.exists() }
        if (found == null) warn("the memory initialization file '$name' was not found")
        return found
    }

    // ------------------------------------------------------------------------------------
    // convert the design units
    // ------------------------------------------------------------------------------------

    /** returns the interface of a design unit, if necessary the unit is converted */
    internal fun getInterface(name: String, symbol: BdfFile.Symbol?): DesignInterface = getInterface(name, symbol, false)

    private fun getInterface(name: String, symbol: BdfFile.Symbol?, top: Boolean): DesignInterface {
        val k = key(name)
        interfaces[k]?.let { return it }
        if (!inProgress.add(k)) {
            warn("recursive instantiation of '$name'")
            return blackBox(name, symbol, "recursive instantiation")
        }
        val ifc = try {
            convert(name, symbol, top)
        } catch (e: Exception) {
            warn("error importing '$name': ${e.message}")
            blackBox(name, symbol, e.message ?: e.toString())
        } finally {
            inProgress.remove(k)
        }
        interfaces[k] = ifc
        return ifc
    }

    private fun convert(name: String, symbol: BdfFile.Symbol?, top: Boolean): DesignInterface {
        var src = fileIndex[key(name)]
        var entity: HdlEntity? = null
        if (src == null || !isHdl(src)) {
            val e = entityIndex[key(name)]
            if (e != null && (src == null || src.name.lowercase().endsWith(".bsf"))) {
                entity = e
                src = e.file
            }
        }
        if (src == null) {
            warn("source of '$name' not found, it is imported as an empty block")
            return blackBox(name, symbol, SOURCE_NOT_FOUND)
        }
        val n = src.name.lowercase()
        return when {
            n.endsWith(".bdf") -> convertBdf(name, src, symbol, top)
            isHdl(src) -> {
                val all = HdlEntity.read(src)
                val e = entity ?: all.firstOrNull { key(it.name) == key(name) } ?: all.firstOrNull()
                ?: throw IOException("no entity or module found in ${src.name}")
                convertHdl(e, symbol)
            }
            n.endsWith(".tdf") -> convertAhdl(name, src, symbol)
            n.endsWith(".bsf") -> {
                val sym = BdfFile.read(src).symbols.firstOrNull()
                warn("only the symbol of '$name' was found (${src.name}), it is imported as an empty block")
                blackBox(name, sym ?: symbol, "only the symbol file was found")
            }
            else -> {
                warn("the file type of ${src.name} is not supported, '$name' is imported as an empty block")
                blackBox(name, symbol, "unsupported file type: ${src.name}")
            }
        }
    }

    private fun convertBdf(name: String, src: File, symbol: BdfFile.Symbol?, top: Boolean): DesignInterface {
        val bdf = BdfFile.read(src)
        val ports = bdf.pins.map { DesignInterface.Port(it.getName(), it.direction, BusName.parse(it.getName()).bits, it.isDefaultVCC) }
        val ifc = DesignInterface(unitName(name, src), DesignInterface.Kind.BDF, ports)
        // register the interface before the conversion to allow the lookup of the element name
        interfaces[key(name)] = ifc
        val c = BdfConverter(this, bdf, ifc.name, top, ifc, src.parentFile).convert()
        c.attributes.set(Keys.DESCRIPTION, "Imported from Quartus: ${src.name}")
        if (top) addWaveforms(c, ifc, src)
        setSymbolShape(c, ifc, symbol)
        save(c, ifc)
        return ifc
    }

    private fun unitName(name: String, src: File): String {
        val stem = QuartusProject.stem(src.name)
        return if (stem.equals(name, true)) stem else name
    }

    /* the shape used if the user places a new instance of this circuit: the drawing used by Quartus */
    private fun setSymbolShape(c: Circuit, ifc: DesignInterface, symbol: BdfFile.Symbol?) {
        var shape: CustomShapeDescription? = null
        if (symbol != null) {
            val sb = ShapeBuilder(ShapeBuilder.scale(BdfFile.Point(symbol.rect.x1, symbol.rect.y1)))
            sb.add(symbol.drawing, symbol.rect.x1, symbol.rect.y1).addText(symbol.type, symbol.rect.x1, symbol.rect.y1)
            val found = HashSet<String>()
            for (port in symbol.ports) {
                val ip = ifc.find(port.getName()) ?: continue
                if (!found.add(ip.label)) continue
                port.line?.let { sb.add(it, symbol.rect.x1, symbol.rect.y1) }
                if (port.label != null && !port.label.invisible) sb.addText(port.label, symbol.rect.x1, symbol.rect.y1)
                val p = symbol.getAbsolutePos(port)
                sb.addPin(ip.label, p.x, p.y)
            }
            if (found.size == ifc.ports.size) shape = sb.build()
        }
        if (shape == null) {
            val s = SymbolFactory.block(ifc.name,
                ifc.ports.filter { !it.isOutput }.map { SymbolFactory.Port(it.label, it.quartusName, it.bits > 1) },
                ifc.ports.filter { it.isOutput }.map { SymbolFactory.Port(it.label, it.quartusName, it.bits > 1) })
            val sb = ShapeBuilder(Vector(0, 0))
            s.typeText?.let { sb.addText(it, 0, 0) }
            shape = sb.addSymbol(s, 0, 0) { it.name }.build()
        }
        c.attributes.set(Keys.SHAPE_TYPE, CustomCircuitShapeType.CUSTOM)
        c.attributes.set(Keys.CUSTOM_SHAPE, shape)
    }

    /*
     * Saves the circuit. A circuit which was modified by the user after the last import is not overwritten.
     * The time stamps of the created files are stored in the file IMPORT_INFO.
     */
    private fun save(c: Circuit, ifc: DesignInterface) {
        val f = File(outDir, getElementName(ifc))
        if (f.exists()) {
            val stamp = importInfo.getProperty(f.name)
            if (stamp == null || stamp.toLong() != f.lastModified()) {
                warn("${f.name} was modified in Digital and is not overwritten. Delete it to import it again.")
                createdFiles.add(f)
                return
            }
        }
        c.save(f)
        createdFiles.add(f)
        importInfo.setProperty(f.name, f.lastModified().toString())
        File(outDir, IMPORT_INFO).outputStream().use { importInfo.store(it, "Files created by the Quartus import") }
    }

    // ------------------------------------------------------------------------------------
    // waveforms
    // ------------------------------------------------------------------------------------

    private fun addWaveforms(c: Circuit, ifc: DesignInterface, src: File) {
        val files = LinkedHashSet(project.waveformFiles)
        project.projectDir.listFiles { _, n -> n.lowercase().endsWith(".vwf") }?.sorted()?.let { files.addAll(it) }
        src.parentFile.listFiles { _, n -> n.lowercase().endsWith(".vwf") }?.sorted()?.let { files.addAll(it) }
        val inputs = ifc.ports.filter { !it.isOutput }
            .associate { it.quartusName.lowercase().substringBefore('[') to VwfFile.Pin(it.label, it.quartusName, it.bits) }
        val outputs = ifc.ports.filter { it.isOutput }
            .associate { it.quartusName.lowercase().substringBefore('[') to VwfFile.Pin(it.label, it.quartusName, it.bits) }
        var y = c.elements.maxOfOrNull { it.pos.y } ?: 0
        for (f in files.filter { it.exists() }) {
            try {
                val test = VwfFile.read(f).createTest(inputs, outputs) { warn("${f.name}: $it") } ?: continue
                y += 100
                val ve = ShapeBuilder.createElement(TestCaseElement.DESCRIPTION.name, Vector(0, y))
                ve.elementAttributes.set(Keys.LABEL, QuartusProject.stem(f.name))
                    .set(Keys.TESTDATA, TestCaseDescription(test))
                c.add(ve)
            } catch (e: Exception) {
                warn("could not import the waveform ${f.name}: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // design units without a schematic
    // ------------------------------------------------------------------------------------

    private fun blackBox(name: String, symbol: BdfFile.Symbol?, reason: String): DesignInterface {
        val ports = symbol?.ports?.map { DesignInterface.Port(it.getName(), it.direction, BusName.parse(it.getName()).bits, false) } ?: emptyList()
        val ifc = DesignInterface(name, DesignInterface.Kind.BLACKBOX, ports).setInfo(reason)
        try {
            val c = wrapperCircuit(ifc, null)
            c.attributes.set(Keys.DESCRIPTION, "Empty block, the outputs are zero: $reason")
            setSymbolShape(c, ifc, symbol)
            save(c, ifc)
        } catch (e: IOException) {
            warn("could not create the block '$name': ${e.message}")
        }
        return ifc
    }

    private fun convertAhdl(name: String, src: File, symbol: BdfFile.Symbol?): DesignInterface {
        val raw = String(src.readBytes(), Charsets.ISO_8859_1)
        // a megafunction created by the MegaWizard in AHDL is simulated natively
        HdlEntity.parseAHDL(raw, src)?.takeIf { it.megafunction != null }?.let { return convertHdl(it, symbol) }
        val text = raw.replace(Regex("(?s)%.*?%"), " ").replace(Regex("--[^\n]*"), " ")
        val m = AHDL_SUBDESIGN.find(text) ?: return blackBox(name, symbol, "AHDL file without SUBDESIGN")
        val ports = ArrayList<DesignInterface.Port>()
        for (decl in HdlEntity.splitTop(m.groupValues[2], ';')) {
            val c = decl.indexOf(':')
            if (c < 0) continue
            val type = decl.substring(c + 1).trim().uppercase()
            val dir = when {
                type.startsWith("OUTPUT") -> BdfFile.Direction.OUTPUT
                type.startsWith("BIDIR") -> BdfFile.Direction.BIDIR
                else -> BdfFile.Direction.INPUT
            }
            HdlEntity.splitTop(decl.substring(0, c), ',').map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { ports.add(DesignInterface.Port(it, dir, BusName.parse(it).bits, type.contains("VCC"))) }
        }
        warn("AHDL is not supported, '$name' (${src.name}) is imported as an empty block")
        val ifc = DesignInterface(unitName(name, src), DesignInterface.Kind.BLACKBOX, ports).setInfo("AHDL is not supported")
        val c = wrapperCircuit(ifc, null)
        c.attributes.set(Keys.DESCRIPTION, "AHDL design ${src.name} is not supported, the outputs are zero")
        setSymbolShape(c, ifc, symbol)
        save(c, ifc)
        return ifc
    }

    private fun convertHdl(entity: HdlEntity, symbol: BdfFile.Symbol?): DesignInterface {
        val ports = entity.ports.map { DesignInterface.Port(it.name, it.direction, it.bits, it.defaultHigh) }
        val mf = entity.megafunction
        val c: Circuit
        val ifc: DesignInterface
        if (mf != null) {
            ifc = DesignInterface(entity.name, DesignInterface.Kind.MEGAFUNCTION, ports)
            val part = QuartusParts.get(mf)!!
            val params = Params(part.defaultParams)
            for ((k, v) in entity.megafunctionParams) params.set(k, v)
            for (k in listOf("LPM_FILE", "INIT_FILE")) {
                val f = params.get(k, "")
                if (f.isNotEmpty() && !f.equals("UNUSED", true))
                    resolveDataFile(f, entity.file?.parentFile)?.let { params.set(k, it.absolutePath) }
            }
            // map the entity ports to the megafunction ports
            val portMap = LinkedHashMap<String, String>()
            for ((mport, signal) in entity.megafunctionPortMap) {
                val ip = ifc.find(signal.replace(Regex("[(\\[].*"), "").trim()) ?: continue
                val key = pinKey(part, mport) ?: continue
                portMap[ip.label] = key
            }
            // parts of megafunction ports, e.g. the single outputs of a decoder
            val slices = HashMap<String, IntRange>()
            for ((signal, sl) in entity.megafunctionSlices) {
                val ip = ifc.find(signal.replace(Regex("[(\\[].*"), "").trim()) ?: continue
                val key = pinKey(part, sl.port) ?: continue
                portMap[ip.label] = key
                val spec = if (ip.isOutput) part.getOutput(sl.port) else part.getInput(sl.port)
                val width = spec?.getWidth(params) ?: continue
                if (sl.lo != 0 || sl.hi != width - 1) {
                    if (ip.isOutput) slices[ip.label] = sl.lo..sl.hi
                    else warn("${entity.name}: the input '$signal' is connected to a part of the port '${sl.port}', this is not supported")
                }
            }
            // ports which are not found in the port map are connected by name (e.g. altpll, lpm_mux)
            for (ip in ifc.ports) if (ip.label !in portMap) pinKey(part, ip.quartusName)?.let { portMap[ip.label] = it }
            params.set(Params.PORTS, portMap.values.map { it.substring(2) }.distinct().joinToString(","))
            if (mf == "altpll") warn("${entity.name}: ALTPLL is simulated as a clock buffer, the frequency is not changed")
            c = wrapperCircuit(ifc, Inner.Part(part, params, portMap, slices))
            c.attributes.set(Keys.DESCRIPTION, "Megafunction ${mf.uppercase()} imported from ${entity.file?.name}\n$params")
        } else {
            ifc = DesignInterface(entity.name, DesignInterface.Kind.HDL, ports)
            val codeFile = hdlCodeFile(entity)
            c = wrapperCircuit(ifc, Inner.Hdl(entity, codeFile))
            c.attributes.set(Keys.DESCRIPTION, "Simulated by " +
                (if (entity.language == HdlEntity.Language.VHDL) "GHDL" else "Icarus Verilog") +
                " or, if it is not installed, by the built-in simulator: " + codeFile?.absolutePath)
        }
        setSymbolShape(c, ifc, symbol)
        save(c, ifc)
        return ifc
    }

    private fun pinKey(part: QuartusPart, name: String): String? =
        part.getInput(name)?.let { ShapeBuilder.IN + it.name } ?: part.getOutput(name)?.let { ShapeBuilder.OUT + it.name }

    /** the element inside a wrapper circuit */
    private sealed class Inner {
        class Part(val part: QuartusPart, val params: Params, val portMap: Map<String, String>,
                   val slices: Map<String, IntRange> = emptyMap()) : Inner()
        class Hdl(val entity: HdlEntity, val codeFile: File?) : Inner()
    }

    /*
     * The file with the code of the HDL entity. If the entity uses entities, modules or packages
     * declared in other files of the project, a file containing all of them is created next to the
     * circuits, so that GHDL, Icarus Verilog or the built-in simulators find them.
     */
    private fun hdlCodeFile(e: HdlEntity): File? {
        val f = e.file?.absoluteFile ?: return null
        val vhdl = e.language == HdlEntity.Language.VHDL
        fun isVhdl(x: File) = x.name.lowercase().let { it.endsWith(".vhd") || it.endsWith(".vhdl") }
        fun read(x: File) = try {
            String(x.readBytes(), Charsets.ISO_8859_1)
        } catch (ex: IOException) {
            ""
        }
        fun strip(t: String) = if (vhdl) t.replace(Regex("--[^\n]*"), " ")
        else t.replace(Regex("(?s)/\\*.*?\\*/"), " ").replace(Regex("//[^\n]*"), " ")
        val unitRegex = if (vhdl) Regex("""(?i)\b(?:entity|package)\s+(\w+)\s+is\b""") else Regex("""\bmodule\s+(\w+)""")
        // only the files of the project, not the files of other projects in neighbouring folders
        val dirs = (listOf(f.parentFile, project.projectDir) + project.searchPaths).map { it.absoluteFile }.toSet()
        val candidates = ArrayList<File>(project.designFiles)
        for (d in dirs) d.listFiles()?.sortedBy { it.name }?.let { candidates.addAll(it) }
        val files = candidates.map { it.absoluteFile }.distinct().filter { it.isFile && isHdl(it) && isVhdl(it) == vhdl }
        val texts = HashMap<File, String>()
        val declared = HashMap<File, Set<String>>()
        val units = LinkedHashMap<String, File>()
        fun unitsOf(t: String) = unitRegex.findAll(t).map { m -> m.groupValues[1].let { if (vhdl) it.lowercase() else it } }
            .filter { it != "body" }.toSet()
        for (x in files) {
            val t = strip(read(x))
            texts[x] = t
            declared[x] = unitsOf(t)
            for (u in declared[x]!!) units.putIfAbsent(u, x)
        }
        val order = ArrayList<File>()
        val visited = HashSet<File>()
        fun visit(x: File) {
            if (!visited.add(x)) return
            val t = texts.getOrPut(x) { strip(read(x)) }
            val own = declared.getOrPut(x) { unitsOf(t) }
            for ((u, uf) in units)
                if (uf != x && u !in own && Regex("\\b${Regex.escape(u)}\\b", if (vhdl) setOf(RegexOption.IGNORE_CASE) else emptySet()).containsMatchIn(t))
                    visit(uf)
            // the used units are declared first
            order.add(x)
        }
        visit(f)
        if (order.size == 1) return f
        val out = File(outDir, e.name + if (vhdl) "_all.vhd" else "_all.v")
        out.writeBytes(order.joinToString("\n") { read(it) }.toByteArray(Charsets.ISO_8859_1))
        if (out !in createdFiles) createdFiles.add(out)
        return out
    }

    /*
     * Creates a circuit which contains an inner element connected to the input and output pins.
     * The inner element is a native part, an external HDL element or nothing (black box).
     */
    private fun wrapperCircuit(ifc: DesignInterface, inner: Inner?): Circuit {
        val c = Circuit()
        val blockX = 24 * 8
        val innerKey = HashMap<String, String>() // interface label to key of the inner pin
        val innerPins = HashMap<String, BdfFile.Point>()
        var innerWidth = 0
        if (inner != null) {
            val attr = ElementAttributes()
            val name: String
            val symbol: QSymbol
            when (inner) {
                is Inner.Part -> {
                    name = inner.part.digitalName
                    attr.set(Keys.QUARTUS_PARAMS, inner.params.toString()).set(Keys.LABEL, ifc.name)
                    symbol = inner.part.getSymbol(inner.params)
                    innerKey.putAll(inner.portMap)
                }
                is Inner.Hdl -> {
                    name = ExternalFile.DESCRIPTION.name
                    val e = inner.entity
                    fun spec(dirOut: Boolean) = e.ports.filter { (it.direction == BdfFile.Direction.OUTPUT) == dirOut }
                        .joinToString(",") { if (it.bits > 1) "${it.name}:${it.bits}" else it.name }
                    for (p in e.ports) ifc.find(p.name)?.let {
                        innerKey[it.label] = (if (p.direction == BdfFile.Direction.OUTPUT) ShapeBuilder.OUT else ShapeBuilder.IN) + p.name
                    }
                    attr.set(Keys.LABEL, e.name).set(Keys.EXTERNAL_INPUTS, spec(false)).set(Keys.EXTERNAL_OUTPUTS, spec(true))
                        .set(Keys.EXTERNAL_CODE_FILE, inner.codeFile)
                        .set(Keys.APPLICATION_TYPE, if (e.language == HdlEntity.Language.VHDL) Application.Type.GHDL else Application.Type.IVERILOG)
                    symbol = SymbolFactory.block(e.name,
                        e.ports.filter { it.direction != BdfFile.Direction.OUTPUT }.map { SymbolFactory.Port(it.name, it.name, it.bits > 1) },
                        e.ports.filter { it.direction == BdfFile.Direction.OUTPUT }.map { SymbolFactory.Port(it.name, it.name, it.bits > 1) })
                }
            }
            val pos = ShapeBuilder.scale(BdfFile.Point(blockX, 0))
            val sb = ShapeBuilder(pos)
            symbol.typeText?.let { sb.addText(it, blockX, 0) }
            sb.addSymbol(symbol, blockX, 0) { p ->
                val k = (if (p.direction == BdfFile.Direction.OUTPUT) ShapeBuilder.OUT else ShapeBuilder.IN) + p.name
                innerPins[k] = BdfFile.Point(blockX + p.pt.x, p.pt.y)
                k
            }
            innerWidth = symbol.width
            attr.set(Keys.CUSTOM_SHAPE, sb.build())
            c.add(ShapeBuilder.createElement(name, pos, attr))
        }

        val outX = blockX + maxOf(innerWidth, 64) + 24 * 8
        var row = 0
        val ins = ifc.ports.filter { !it.isOutput }
        val outs = ifc.ports.filter { it.isOutput }
        val nextFree = { 32 + SymbolFactory.STEP * (maxOf(ins.size, outs.size) + 2 + row++) }
        for (p in ins) {
            val target = innerKey[p.label]?.let { innerPins[it] }
            val y = target?.y ?: nextFree()
            addPin(c, true, p, 0, y)
            if (target != null) c.wires.add(Wire(ShapeBuilder.scale(BdfFile.Point(0, y)), ShapeBuilder.scale(target)))
        }
        val slices = (inner as? Inner.Part)?.slices ?: emptyMap()
        for (p in outs.filter { it.label in slices }) {
            // a part of an output: connected via a splitter and tunnels
            val key = innerKey[p.label] ?: continue
            val source = innerPins[key] ?: continue
            val y = nextFree()
            addPin(c, false, p, outX, y)
            val r = slices[p.label]!!
            c.add(tunnel("$key[${r.first}..${r.last}]", ShapeBuilder.scale(BdfFile.Point(outX, y))))
        }
        for (key in slices.keys.mapNotNull { innerKey[it] }.distinct()) {
            val source = innerPins[key] ?: continue
            val ranges = ifc.ports.filter { innerKey[it.label] == key }.mapNotNull { slices[it.label] }.distinct()
            val width = (inner as Inner.Part).part.getOutput(key.substring(2))?.getWidth(inner.params) ?: continue
            c.add(tunnel(key, ShapeBuilder.scale(source)))
            val pos = Vector(0, ShapeBuilder.scale(BdfFile.Point(0, nextFree() + SymbolFactory.STEP)).y / 20 * 20)
            val sp = ShapeBuilder.createElement(Splitter.DESCRIPTION.name, pos)
            sp.elementAttributes.set(Keys.INPUT_SPLIT, "$width").set(Keys.OUTPUT_SPLIT, ranges.joinToString(",") { "${it.first}-${it.last}" })
            c.add(sp)
            c.add(tunnel(key, pos))
            ranges.forEachIndexed { i, rg -> c.add(tunnel("$key[${rg.first}..${rg.last}]", pos.add(20, i * 20))) }
            repeat(ranges.size) { nextFree() }
        }
        for (p in outs.filter { it.label !in slices }) {
            val source = innerKey[p.label]?.let { innerPins[it] }
            val y = source?.y ?: nextFree()
            addPin(c, false, p, outX, y)
            val outPos = ShapeBuilder.scale(BdfFile.Point(outX, y))
            if (source != null) c.wires.add(Wire(ShapeBuilder.scale(source), outPos))
            else {
                // undriven output: constant zero
                val cp = BdfFile.Point(outX - 48, y)
                val v = ShapeBuilder.scale(cp)
                val ve = ShapeBuilder.createElement(Const.DESCRIPTION.name, v)
                ve.elementAttributes.setBits(p.bits).set(Keys.VALUE, 0L).set(Keys.CUSTOM_SHAPE,
                    ShapeBuilder(v).addPinDigital(ShapeBuilder.OUT + "out", v).text("GND", cp.x - 2, cp.y, 6, Orientation.RIGHTCENTER).build())
                c.add(ve)
                c.wires.add(Wire(v, outPos))
            }
        }
        return c
    }

    private fun tunnel(name: String, pos: Vector): VisualElement {
        val t = ShapeBuilder.createElement(Tunnel.DESCRIPTION.name, pos)
        t.elementAttributes.set(Keys.NETNAME, name)
        return t
    }

    private fun addPin(c: Circuit, input: Boolean, p: DesignInterface.Port, x: Int, y: Int) {
        val pos = ShapeBuilder.scale(BdfFile.Point(x, y))
        val shape = if (input) SymbolFactory.inputPinShape(p.quartusName, ShapeBuilder.OUT + "out")
        else SymbolFactory.outputPinShape(p.quartusName, ShapeBuilder.IN + "in")
        val ve = ShapeBuilder.createElement(if (input) In.DESCRIPTION.name else Out.DESCRIPTION.name, pos)
        ve.elementAttributes.set(Keys.LABEL, p.label).setBits(p.bits).set(Keys.CUSTOM_SHAPE, shape)
        c.add(ve)
    }
}
