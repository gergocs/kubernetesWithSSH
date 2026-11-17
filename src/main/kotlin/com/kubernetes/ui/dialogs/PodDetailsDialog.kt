package com.kubernetes.ui.dialogs

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

class PodDetailsDialog(
    project: Project,
    private val titleText: String,
    private val contentText: String,
    private val onEditDeployment: (() -> Unit)? = null
) : DialogWrapper(project, true) {

    init {
        title = titleText
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(750, 500)

        val textArea = JBTextArea(contentText).apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            caretPosition = 0
        }

        val scrollPane = JBScrollPane(textArea)
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    override fun createActions(): Array<Action> {
        val actions = mutableListOf<Action>(okAction)
        if (onEditDeployment != null) {
            val editAction = object : DialogWrapperAction("Edit Deployment...") {
                init {
                    putValue(Action.SMALL_ICON, AllIcons.Actions.Edit)
                }
                override fun doAction(e: java.awt.event.ActionEvent?) {
                    close(OK_EXIT_CODE)
                    onEditDeployment.invoke()
                }
            }
            actions.add(0, editAction)
        }
        return actions.toTypedArray()
    }
}
