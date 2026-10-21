/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.quartus.gui

import de.neemann.digital.lang.Lang
import de.neemann.gui.ErrorMessage
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Window
import java.io.IOException
import javax.swing.JEditorPane
import javax.swing.JFrame
import javax.swing.JScrollPane
import javax.swing.event.HyperlinkEvent

/**
 * The built-in guide: the steps of the Quartus simulation booklet done in this application,
 * with an animation for every step. The pages and the animations are in the resources folder "help".
 */
class GuideFrame private constructor() : JFrame(Lang.get("menu_guide")) {
    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        val page = if (Lang.currentLanguage().name == "fr") "guide_fr.html" else "guide_en.html"
        val pane = JEditorPane()
        pane.isEditable = false
        pane.page = GuideFrame::class.java.getResource("/help/$page")
        pane.addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                val d = e.description
                if (d != null && d.startsWith("#")) pane.scrollToReference(d.substring(1))
                else if (e.url != null && Desktop.isDesktopSupported()) Desktop.getDesktop().browse(e.url.toURI())
            }
        }
        val scroll = JScrollPane(pane)
        scroll.verticalScrollBar.unitIncrement = 24
        contentPane.add(scroll)
        preferredSize = Dimension(1000, 800)
        pack()
    }

    companion object {
        private var instance: GuideFrame? = null

        /**
         * Shows the guide.
         *
         * @param parent the window to show the guide over
         */
        @JvmStatic
        fun showGuide(parent: Window?) {
            try {
                val g = instance?.takeIf { it.isDisplayable } ?: GuideFrame().also { instance = it }
                if (!g.isVisible) g.setLocationRelativeTo(parent)
                g.isVisible = true
                g.toFront()
            } catch (e: IOException) {
                ErrorMessage(Lang.get("menu_guide")).addCause(e).show(parent)
            }
        }
    }
}
