/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus.gui

import de.neemann.digital.lang.Lang
import de.neemann.digital.quartus.ProjectChecker
import de.neemann.gui.ErrorMessage
import de.neemann.gui.MyFileChooser
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Frame
import java.io.File
import java.io.IOException
import javax.swing.*

/**
 * Shows the report of the test bench: every Quartus project and block diagram of a folder is imported and simulated.
 * Used by a teacher to check the projects of the students or a library.
 */
class CheckProjectsDialog(parent: Frame, private val dir: File) : JDialog(parent, Lang.get("menu_checkProjects"), false) {
    private val pane = JEditorPane("text/html", "<html><body><p>" + Lang.get("msg_checkRunning_N", dir.path) + "</p></body></html>")
    private val save = JButton(Lang.get("btn_saveReport"))
    private var checker: ProjectChecker? = null

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        pane.isEditable = false
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        contentPane.add(JScrollPane(pane), BorderLayout.CENTER)
        save.isEnabled = false
        save.addActionListener { saveReport() }
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT))
        buttons.add(save)
        buttons.add(JButton(Lang.get("ok")).also { it.addActionListener { dispose() } })
        contentPane.add(buttons, BorderLayout.SOUTH)
        preferredSize = Dimension(900, 600)
        pack()
        setLocationRelativeTo(parent)

        object : SwingWorker<ProjectChecker, Unit>() {
            override fun doInBackground(): ProjectChecker = ProjectChecker().check(dir)

            override fun done() {
                try {
                    val c = get()
                    checker = c
                    pane.text = c.createHtmlReport(dir)
                    pane.caretPosition = 0
                    save.isEnabled = true
                } catch (e: Exception) {
                    ErrorMessage(Lang.get("menu_checkProjects")).addCause(e).show(this@CheckProjectsDialog)
                }
            }
        }.execute()
    }

    private fun saveReport() {
        val c = checker ?: return
        val fc = MyFileChooser()
        fc.selectedFile = File(dir, "rapport.md")
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                fc.selectedFile.writeText(c.createReport(dir))
            } catch (e: IOException) {
                ErrorMessage().addCause(e).show(this)
            }
        }
    }

    companion object {
        /**
         * Asks for a folder and shows the report.
         *
         * @param parent the parent frame
         * @param start  the folder to start the selection in
         */
        @JvmStatic
        fun chooseAndCheck(parent: Frame, start: File?) {
            val fc = MyFileChooser()
            fc.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            fc.dialogTitle = Lang.get("menu_checkProjects")
            if (start != null) fc.currentDirectory = start
            if (fc.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION)
                CheckProjectsDialog(parent, fc.selectedFile).isVisible = true
        }
    }
}
