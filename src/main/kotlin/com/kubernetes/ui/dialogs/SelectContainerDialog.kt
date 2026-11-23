package com.kubernetes.ui.dialogs

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.kubernetes.model.PodInfo
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Dialog enabling container selection when executing a terminal session inside a multi-container pod.
 */
class SelectContainerDialog(
    project: Project,
    private val pod: PodInfo,
    private val initialContainer: String? = null,
    private val initialShell: String = "/bin/sh"
) : DialogWrapper(project, true) {

    private val containerComboBox = ComboBox<String>()
    private val shellComboBox = ComboBox<String>()
    private val imageHintLabel = JBLabel()

    init {
        title = "Execute Terminal: Select Container"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val rootPanel = JPanel(BorderLayout(0, 10)).apply {
            border = JBUI.Borders.empty(12)
            preferredSize = Dimension(480, 180)
        }

        val headerPanel = JPanel(BorderLayout(8, 0)).apply {
            val iconLabel = JBLabel(AllIcons.Debugger.Console)
            val textPanel = JPanel(BorderLayout(0, 2)).apply {
                add(JBLabel("Pod: ${pod.name}").apply { font = font.deriveFont(Font.BOLD, 13f) }, BorderLayout.NORTH)
                add(JBLabel("Namespace: ${pod.namespace}  |  Containers: ${pod.containers.size}"), BorderLayout.SOUTH)
            }
            add(iconLabel, BorderLayout.WEST)
            add(textPanel, BorderLayout.CENTER)
        }
        rootPanel.add(headerPanel, BorderLayout.NORTH)

        val formPanel = JPanel(GridBagLayout()).apply {
            val gbc = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = JBUI.insets(4, 0)
                anchor = GridBagConstraints.WEST
            }

            // Container row
            gbc.gridx = 0
            gbc.gridy = 0
            gbc.weightx = 0.0
            add(JBLabel("Container:").apply { font = font.deriveFont(Font.BOLD) }, gbc)

            gbc.gridx = 1
            gbc.weightx = 1.0
            pod.containers.forEach { containerComboBox.addItem(it) }
            if (initialContainer != null && pod.containers.contains(initialContainer)) {
                containerComboBox.selectedItem = initialContainer
            } else if (pod.containers.isNotEmpty()) {
                containerComboBox.selectedIndex = 0
            }
            add(containerComboBox, gbc)

            // Shell row
            gbc.gridx = 0
            gbc.gridy = 1
            gbc.weightx = 0.0
            add(JBLabel("Shell:").apply { font = font.deriveFont(Font.BOLD) }, gbc)

            gbc.gridx = 1
            gbc.weightx = 1.0
            listOf("/bin/sh", "/bin/bash", "sh", "bash", "/bin/zsh").forEach { shellComboBox.addItem(it) }
            shellComboBox.selectedItem = initialShell
            add(shellComboBox, gbc)

            // Image hint row
            gbc.gridx = 1
            gbc.gridy = 2
            gbc.weightx = 1.0
            updateImageHint()
            add(imageHintLabel, gbc)
        }

        containerComboBox.addActionListener {
            updateImageHint()
        }

        rootPanel.add(formPanel, BorderLayout.CENTER)
        return rootPanel
    }

    private fun updateImageHint() {
        val selectedIndex = containerComboBox.selectedIndex
        val img = if (selectedIndex in pod.images.indices) pod.images[selectedIndex] else ""
        if (img.isNotBlank()) {
            imageHintLabel.text = "Image: $img"
            imageHintLabel.icon = AllIcons.Nodes.Deploy
            imageHintLabel.isVisible = true
        } else {
            imageHintLabel.text = ""
            imageHintLabel.icon = null
            imageHintLabel.isVisible = false
        }
    }

    fun getSelectedContainer(): String {
        return (containerComboBox.selectedItem as? String)?.trim().orEmpty()
    }

    fun getSelectedShell(): String {
        return (shellComboBox.selectedItem as? String)?.trim()?.ifBlank { "/bin/sh" } ?: "/bin/sh"
    }

    override fun createActions(): Array<Action> {
        okAction.putValue(Action.NAME, "Execute Terminal")
        return arrayOf(okAction, cancelAction)
    }
}
