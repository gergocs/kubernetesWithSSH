package com.kubernetes.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Font
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer

class PodStatusCellRenderer : DefaultTableCellRenderer() {

    companion object {
        private val GREEN_COLOR = JBColor(Color(46, 125, 50), Color(129, 199, 132))
        private val RED_COLOR = JBColor(Color(198, 40, 40), Color(239, 83, 80))
        private val ORANGE_COLOR = JBColor(Color(230, 81, 0), Color(255, 183, 77))
        private val GRAY_COLOR = JBColor(Color(97, 97, 97), Color(189, 189, 189))
        private val BLUE_COLOR = JBColor(Color(21, 101, 192), Color(100, 181, 246))
    }

    override fun getTableCellRendererComponent(
        table: JTable?,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val label = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
        label.border = JBUI.Borders.empty(2, 6)

        val text = value?.toString() ?: ""
        label.text = text

        val isStatusColumn = (column == 1 || table == null)
        if (!isSelected && isStatusColumn) {
            val status = text
            when {
                status.equals("Running", ignoreCase = true) -> {
                    label.foreground = GREEN_COLOR
                    label.font = label.font.deriveFont(Font.BOLD)
                }
                status.equals("Completed", ignoreCase = true) || status.equals("Succeeded", ignoreCase = true) -> {
                    label.foreground = BLUE_COLOR
                    label.font = label.font.deriveFont(Font.PLAIN)
                }
                status.contains("CrashLoopBackOff", ignoreCase = true) ||
                status.contains("Error", ignoreCase = true) ||
                status.contains("Failed", ignoreCase = true) ||
                status.contains("ImagePullBackOff", ignoreCase = true) ||
                status.contains("ErrImagePull", ignoreCase = true) ||
                status.contains("Evicted", ignoreCase = true) -> {
                    label.foreground = RED_COLOR
                    label.font = label.font.deriveFont(Font.BOLD)
                }
                status.equals("Pending", ignoreCase = true) ||
                status.contains("Creating", ignoreCase = true) ||
                status.contains("Waiting", ignoreCase = true) ||
                status.startsWith("Init:", ignoreCase = true) -> {
                    label.foreground = ORANGE_COLOR
                    label.font = label.font.deriveFont(Font.BOLD)
                }
                status.equals("Terminating", ignoreCase = true) -> {
                    label.foreground = GRAY_COLOR
                    label.font = label.font.deriveFont(Font.ITALIC)
                }
                else -> {
                    label.foreground = table?.foreground ?: JBColor.foreground()
                    label.font = label.font.deriveFont(Font.PLAIN)
                }
            }
        } else if (!isSelected) {
            label.foreground = table?.foreground ?: JBColor.foreground()
            label.font = label.font.deriveFont(Font.PLAIN)
        }
        return label
    }
}
