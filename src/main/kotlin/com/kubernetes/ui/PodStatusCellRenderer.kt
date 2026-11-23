package com.kubernetes.ui

import com.intellij.icons.AllIcons
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
        private val PURPLE_COLOR = JBColor(Color(106, 27, 154), Color(186, 104, 200))
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
        label.border = JBUI.Borders.empty(3, 8)

        val text = value?.toString() ?: ""
        label.text = text
        label.toolTipText = text.ifBlank { null }
        label.icon = null

        val columnName = if (table != null && column < table.columnCount) table.getColumnName(column) else ""

        // Icon for the Name / First column
        if (column == 0 && table != null) {
            val statusColIndex = (0 until table.columnCount).firstOrNull {
                table.getColumnName(it).equals("Status", ignoreCase = true)
            }
            val statusVal = if (statusColIndex != null && row < table.rowCount) {
                table.getValueAt(row, statusColIndex)?.toString() ?: ""
            } else ""

            val typeColIndex = (0 until table.columnCount).firstOrNull {
                table.getColumnName(it).equals("Type", ignoreCase = true)
            }
            val reasonColIndex = (0 until table.columnCount).firstOrNull {
                table.getColumnName(it).equals("Reason", ignoreCase = true)
            }
            val scheduleColIndex = (0 until table.columnCount).firstOrNull {
                table.getColumnName(it).equals("Schedule", ignoreCase = true)
            }
            val completionsColIndex = (0 until table.columnCount).firstOrNull {
                table.getColumnName(it).equals("Completions", ignoreCase = true)
            }
            val hostsColIndex = (0 until table.columnCount).firstOrNull {
                table.getColumnName(it).equals("Hosts", ignoreCase = true)
            }
            val volumeColIndex = (0 until table.columnCount).firstOrNull {
                table.getColumnName(it).equals("Volume", ignoreCase = true)
            }
            val desiredColIndex = (0 until table.columnCount).firstOrNull {
                table.getColumnName(it).equals("Desired", ignoreCase = true)
            }
            val upToDateColIndex = (0 until table.columnCount).firstOrNull {
                table.getColumnName(it).equals("Up-to-date", ignoreCase = true)
            }
            val dataKeysColIndex = (0 until table.columnCount).firstOrNull {
                table.getColumnName(it).equals("Data Keys", ignoreCase = true)
            }

            label.icon = when {
                reasonColIndex != null -> {
                    // Event table (first column is Type)
                    if (text.equals("Warning", ignoreCase = true)) AllIcons.General.Warning else AllIcons.General.Information
                }
                volumeColIndex != null -> {
                    // PVC table
                    when {
                        statusVal.equals("Bound", ignoreCase = true) -> AllIcons.RunConfigurations.TestPassed
                        statusVal.equals("Lost", ignoreCase = true) -> AllIcons.General.Error
                        else -> AllIcons.General.Warning
                    }
                }
                hostsColIndex != null -> AllIcons.Nodes.WebFolder
                scheduleColIndex != null -> AllIcons.Vcs.History
                completionsColIndex != null -> AllIcons.Actions.Execute
                desiredColIndex != null -> AllIcons.Nodes.Deploy
                dataKeysColIndex != null && typeColIndex != null -> AllIcons.Nodes.Padlock // Secrets
                dataKeysColIndex != null -> AllIcons.Nodes.ConfigFolder // ConfigMaps
                upToDateColIndex != null -> AllIcons.Nodes.Favorite // Deployments
                statusColIndex != null -> {
                    // Pod status icon
                    when {
                        statusVal.equals("Running", ignoreCase = true) -> AllIcons.RunConfigurations.TestPassed
                        statusVal.contains("CrashLoopBackOff", ignoreCase = true) ||
                        statusVal.contains("Error", ignoreCase = true) ||
                        statusVal.contains("Failed", ignoreCase = true) ||
                        statusVal.contains("ImagePullBackOff", ignoreCase = true) ||
                        statusVal.contains("ErrImagePull", ignoreCase = true) ||
                        statusVal.contains("Evicted", ignoreCase = true) -> AllIcons.General.Error
                        statusVal.equals("Pending", ignoreCase = true) ||
                        statusVal.contains("Creating", ignoreCase = true) ||
                        statusVal.contains("Waiting", ignoreCase = true) ||
                        statusVal.startsWith("Init:", ignoreCase = true) -> AllIcons.General.Warning
                        statusVal.equals("Completed", ignoreCase = true) ||
                        statusVal.equals("Succeeded", ignoreCase = true) -> AllIcons.Actions.CheckOut
                        statusVal.equals("Terminating", ignoreCase = true) -> AllIcons.Process.ProgressPause
                        else -> AllIcons.Nodes.Deploy
                    }
                }
                typeColIndex != null -> AllIcons.Nodes.Services
                else -> null
            }
        }

        val isSpecialColumn = columnName.equals("Status", ignoreCase = true) ||
                columnName.equals("Type", ignoreCase = true) ||
                columnName.equals("Suspend", ignoreCase = true) ||
                (columnName.equals("Ready", ignoreCase = true) && text.contains('/')) ||
                (columnName.equals("Completions", ignoreCase = true) && text.contains('/')) ||
                (table == null && column == 1)

        if (!isSelected && isSpecialColumn) {
            when {
                // Event Type
                columnName.equals("Type", ignoreCase = true) && text.equals("Warning", ignoreCase = true) -> {
                    label.foreground = RED_COLOR
                    label.font = label.font.deriveFont(Font.BOLD)
                }
                columnName.equals("Type", ignoreCase = true) && text.equals("Normal", ignoreCase = true) -> {
                    label.foreground = BLUE_COLOR
                    label.font = label.font.deriveFont(Font.PLAIN)
                }

                // PVC Status
                text.equals("Bound", ignoreCase = true) -> {
                    label.foreground = GREEN_COLOR
                    label.font = label.font.deriveFont(Font.BOLD)
                }
                text.equals("Lost", ignoreCase = true) -> {
                    label.foreground = RED_COLOR
                    label.font = label.font.deriveFont(Font.BOLD)
                }

                // CronJob Suspend
                columnName.equals("Suspend", ignoreCase = true) && text.equals("True", ignoreCase = true) -> {
                    label.foreground = ORANGE_COLOR
                    label.font = label.font.deriveFont(Font.BOLD)
                }
                columnName.equals("Suspend", ignoreCase = true) && text.equals("False", ignoreCase = true) -> {
                    label.foreground = GREEN_COLOR
                    label.font = label.font.deriveFont(Font.PLAIN)
                }

                // Service Type
                text.equals("LoadBalancer", ignoreCase = true) -> {
                    label.foreground = GREEN_COLOR
                    label.font = label.font.deriveFont(Font.BOLD)
                }
                text.equals("NodePort", ignoreCase = true) -> {
                    label.foreground = ORANGE_COLOR
                    label.font = label.font.deriveFont(Font.BOLD)
                }
                text.equals("ClusterIP", ignoreCase = true) -> {
                    label.foreground = BLUE_COLOR
                    label.font = label.font.deriveFont(Font.PLAIN)
                }
                text.equals("ExternalName", ignoreCase = true) -> {
                    label.foreground = PURPLE_COLOR
                    label.font = label.font.deriveFont(Font.PLAIN)
                }

                // Deployment, StatefulSet, Job, or Pod Ready/Completions ratio (e.g. 1/1, 0/1)
                (columnName.equals("Ready", ignoreCase = true) || columnName.equals("Completions", ignoreCase = true)) && text.contains('/') -> {
                    val parts = text.split('/')
                    val ready = parts.getOrNull(0)?.toIntOrNull() ?: 0
                    val total = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    if (total > 0 && ready >= total) {
                        label.foreground = GREEN_COLOR
                        label.font = label.font.deriveFont(Font.BOLD)
                    } else if (ready > 0) {
                        label.foreground = ORANGE_COLOR
                        label.font = label.font.deriveFont(Font.BOLD)
                    } else {
                        label.foreground = RED_COLOR
                        label.font = label.font.deriveFont(Font.BOLD)
                    }
                }

                // Pod Status
                text.equals("Running", ignoreCase = true) -> {
                    label.foreground = GREEN_COLOR
                    label.font = label.font.deriveFont(Font.BOLD)
                }
                text.equals("Completed", ignoreCase = true) || text.equals("Succeeded", ignoreCase = true) -> {
                    label.foreground = BLUE_COLOR
                    label.font = label.font.deriveFont(Font.PLAIN)
                }
                text.contains("CrashLoopBackOff", ignoreCase = true) ||
                text.contains("Error", ignoreCase = true) ||
                text.contains("Failed", ignoreCase = true) ||
                text.contains("ImagePullBackOff", ignoreCase = true) ||
                text.contains("ErrImagePull", ignoreCase = true) ||
                text.contains("Evicted", ignoreCase = true) -> {
                    label.foreground = RED_COLOR
                    label.font = label.font.deriveFont(Font.BOLD)
                }
                text.equals("Pending", ignoreCase = true) ||
                text.contains("Creating", ignoreCase = true) ||
                text.contains("Waiting", ignoreCase = true) ||
                text.startsWith("Init:", ignoreCase = true) -> {
                    label.foreground = ORANGE_COLOR
                    label.font = label.font.deriveFont(Font.BOLD)
                }
                text.equals("Terminating", ignoreCase = true) -> {
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
