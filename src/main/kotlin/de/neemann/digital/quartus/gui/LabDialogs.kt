/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus.gui

import de.neemann.digital.draw.shapes.ShapeFactory
import de.neemann.digital.gui.Main
import de.neemann.digital.lang.Lang
import de.neemann.digital.quartus.Lab
import de.neemann.gui.ErrorMessage
import de.neemann.gui.MyFileChooser
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import java.io.IOException
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * The dialogs of the labs which grade themselves: the teacher creates a lab from a circuit with tests,
 * the student checks the circuit with the hidden tests.
 */
object LabDialogs {

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * Creates a lab from the circuit of the main window.
     *
     * @param main the main window
     */
    @JvmStatic
    fun create(main: Main) {
        val circuit = main.circuitComponent.circuit
        val tests = Lab.testsOf(circuit)
        if (tests.isEmpty()) {
            JOptionPane.showMessageDialog(main, Lang.get("msg_labNoTests"), Lang.get("menu_labCreate"), JOptionPane.WARNING_MESSAGE)
            return
        }
        // the solution of the teacher should pass the tests
        val failed = Lab("", "", false, tests).check(circuit, main.library).filter { !it.passed }
        if (failed.isNotEmpty() && JOptionPane.showConfirmDialog(main,
                Lang.get("msg_labSolutionFails_N", failed.joinToString(", ") { it.name }), Lang.get("menu_labCreate"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return

        val name = main.baseFileName?.nameWithoutExtension ?: "TP"
        val title = JTextField(name, 30)
        val text = JTextArea(Lang.get("msg_labDefaultText"), 6, 30)
        text.lineWrap = true
        text.wrapStyleWord = true
        val remove = JCheckBox(Lang.get("msg_labRemoveSolution"), true)
        val hints = JCheckBox(Lang.get("msg_labHints"), true)
        val p = JPanel(GridBagLayout())
        val c = GridBagConstraints()
        c.insets = Insets(3, 3, 3, 3)
        c.anchor = GridBagConstraints.WEST
        c.fill = GridBagConstraints.HORIZONTAL
        c.gridx = 0
        c.gridy = 0
        p.add(JLabel(Lang.get("msg_labTitle")), c)
        c.gridx = 1
        p.add(title, c)
        c.gridx = 0
        c.gridy++
        p.add(JLabel(Lang.get("msg_labText")), c)
        c.gridx = 1
        p.add(JScrollPane(text), c)
        c.gridx = 1
        c.gridy++
        p.add(remove, c)
        c.gridy++
        p.add(hints, c)
        c.gridy++
        p.add(JLabel(Lang.get("msg_labTestCount_N", tests.size)), c)
        if (JOptionPane.showConfirmDialog(main, p, Lang.get("menu_labCreate"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return

        val fc = MyFileChooser()
        fc.fileFilter = FileNameExtensionFilter("Digital", "dig")
        val base = main.baseFileName
        fc.selectedFile = if (base != null) File(base.parentFile, base.nameWithoutExtension + "_TP.dig") else File("TP.dig")
        if (fc.showSaveDialog(main) != JFileChooser.APPROVE_OPTION) return
        var f = fc.selectedFile
        if (!f.name.endsWith(".dig")) f = File(f.parentFile, f.name + ".dig")
        if (base != null && f.absoluteFile == base.absoluteFile) {
            JOptionPane.showMessageDialog(main, Lang.get("msg_labSameFile"))
            return
        }
        try {
            val lab = Lab(title.text.trim(), text.text.trim(), hints.isSelected, tests)
            Lab.createLabCircuit(circuit, lab, remove.isSelected, ShapeFactory(main.library)).save(f)
            JOptionPane.showMessageDialog(main, Lang.get("msg_labCreated_N", f.path), Lang.get("menu_labCreate"),
                JOptionPane.INFORMATION_MESSAGE)
        } catch (e: IOException) {
            ErrorMessage().addCause(e).show(main)
        }
    }

    /**
     * Checks the circuit of the main window with the hidden tests of the lab.
     *
     * @param main the main window
     */
    @JvmStatic
    fun check(main: Main) {
        val lab = Lab.of(main.circuitComponent.circuit)
        if (lab == null) {
            JOptionPane.showMessageDialog(main, Lang.get("msg_labNoLab"), Lang.get("menu_labCheck"), JOptionPane.INFORMATION_MESSAGE)
            return
        }
        main.ensureModelIsStopped()
        val results = lab.check(main.circuitComponent.circuit, main.library)
        val passed = results.count { it.passed }
        val sb = StringBuilder("<html><body>")
        if (lab.title.isNotEmpty()) sb.append("<h2>").append(esc(lab.title)).append("</h2>")
        val color = if (passed == results.size) "#2e7d32" else if (passed > 0) "#b26a00" else "#c62828"
        sb.append("<h1><font color='").append(color).append("'>")
            .append(Lang.get("msg_labScore_NN", passed, results.size)).append("</font></h1>")
        if (passed == results.size) sb.append("<p><b>").append(Lang.get("msg_labAllPassed")).append("</b></p>")
        sb.append("<ul>")
        for (r in results) {
            sb.append("<li>").append(if (r.passed) "<font color='#2e7d32'><b>✔</b></font> " else "<font color='#c62828'><b>✘</b></font> ")
                .append(esc(r.name))
            if (r.error != null) sb.append("<br><i>").append(esc(r.error)).append("</i>")
            else if (r.hint != null) sb.append("<br><i>").append(Lang.get("msg_labHintRow")).append("</i> ")
                .append(esc(r.hint))
            sb.append("</li>")
        }
        sb.append("</ul></body></html>")
        val pane = JEditorPane("text/html", sb.toString())
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        pane.isEditable = false
        val scroll = JScrollPane(pane)
        scroll.preferredSize = Dimension(560, minOf(500, 180 + results.size * 50))
        val p = JPanel(BorderLayout())
        p.add(scroll)
        JOptionPane.showMessageDialog(main, p, Lang.get("menu_labCheck"), JOptionPane.PLAIN_MESSAGE)
    }
}
