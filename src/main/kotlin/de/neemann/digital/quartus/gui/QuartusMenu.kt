/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus.gui

import de.neemann.digital.gui.Main
import de.neemann.digital.lang.Lang
import de.neemann.gui.ToolTipAction
import java.awt.event.ActionEvent
import javax.swing.JMenu

/**
 * The menu with the tools which replace the ones of Quartus: waveform editor, pin planner, labs and the
 * test bench for projects.
 */
object QuartusMenu {

    private fun action(name: String, tip: String, run: () -> Unit) = object : ToolTipAction(name) {
        override fun actionPerformed(e: ActionEvent?) = run()
    }.setToolTip(tip)

    /**
     * Creates the menu.
     *
     * @param main the main window
     * @return the menu
     */
    @JvmStatic
    fun create(main: Main): JMenu {
        val menu = JMenu(Lang.get("menu_quartus"))
        menu.add(action(Lang.get("menu_waveformEditor"), Lang.get("menu_waveformEditor_tt")) {
            WaveformEditor.show(main)
        }.createJMenuItem())
        menu.add(action(Lang.get("menu_pinPlanner"), Lang.get("menu_pinPlanner_tt")) {
            PinPlannerDialog.show(main)
        }.createJMenuItem())
        menu.addSeparator()
        menu.add(action(Lang.get("menu_labCheck"), Lang.get("menu_labCheck_tt")) {
            LabDialogs.check(main)
        }.createJMenuItem())
        menu.add(action(Lang.get("menu_labCreate"), Lang.get("menu_labCreate_tt")) {
            LabDialogs.create(main)
        }.createJMenuItem())
        menu.addSeparator()
        menu.add(action(Lang.get("menu_checkProjects"), Lang.get("menu_checkProjects_tt")) {
            CheckProjectsDialog.chooseAndCheck(main, main.baseFileName?.parentFile)
        }.createJMenuItem())
        return menu
    }
}
