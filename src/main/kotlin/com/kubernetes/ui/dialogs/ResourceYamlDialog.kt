package com.kubernetes.ui.dialogs

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.kubernetes.cache.KubernetesDataCache
import com.kubernetes.model.SshProfile
import com.kubernetes.ssh.KubernetesSshClient
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

class ResourceYamlDialog(
    private val project: Project,
    private val profile: SshProfile,
    private val resourceType: String,
    private val namespace: String,
    private val resourceName: String,
    private val onResourceUpdated: (() -> Unit)? = null
) : DialogWrapper(project, true) {

    private val yamlTextArea = JBTextArea().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        tabSize = 2
        isEditable = true
    }

    private val statusLabel = JBLabel("Loading $resourceType YAML...")

    init {
        title = "${resourceType.replaceFirstChar { it.uppercase() }}: $resourceName ($namespace) - YAML"
        init()
        loadYaml()
    }

    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            ApplicationManager.getApplication().invokeLater(action, ModalityState.any())
        }
    }

    private fun loadYaml() {
        val cached = KubernetesDataCache.getYaml(profile.id, namespace, resourceType, resourceName)
        if (cached != null) {
            yamlTextArea.text = cached
            yamlTextArea.caretPosition = 0
            statusLabel.text = "YAML loaded from cache."
            return
        }

        statusLabel.text = "Loading $resourceType YAML..."
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = KubernetesSshClient(profile)
                val res = client.getResourceYaml(resourceType.lowercase(), namespace, resourceName)
                runOnEdt {
                    if (res.isSuccess) {
                        KubernetesDataCache.putYaml(profile.id, namespace, resourceType, resourceName, res.output)
                        yamlTextArea.text = res.output
                        yamlTextArea.caretPosition = 0
                        statusLabel.text = "YAML loaded successfully."
                    } else {
                        val error = res.error.ifBlank { res.output }
                        yamlTextArea.text = "# Error fetching YAML:\n# $error"
                        statusLabel.text = "Error loading YAML: $error"
                    }
                }
            } catch (e: Exception) {
                runOnEdt {
                    val msg = e.message ?: "Unknown error"
                    yamlTextArea.text = "# Error fetching YAML:\n# $msg"
                    statusLabel.text = "Error loading YAML: $msg"
                }
            }
        }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 6))
        panel.preferredSize = Dimension(820, 580)
        panel.border = JBUI.Borders.empty(4)

        val toolbar = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            add(JButton("Copy YAML", AllIcons.Actions.Copy).apply {
                addActionListener {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(
                        StringSelection(yamlTextArea.text),
                        null
                    )
                    statusLabel.text = "YAML copied to clipboard."
                }
            })
            add(JButton("Dry Run", AllIcons.Actions.CheckOut).apply {
                addActionListener { applyYaml(dryRun = true) }
            })
            add(JButton("Apply Changes", AllIcons.Actions.Commit).apply {
                addActionListener { applyYaml(dryRun = false) }
            })
        }

        panel.add(toolbar, BorderLayout.NORTH)
        panel.add(JBScrollPane(yamlTextArea), BorderLayout.CENTER)
        panel.add(statusLabel, BorderLayout.SOUTH)

        return panel
    }

    private fun applyYaml(dryRun: Boolean) {
        val content = yamlTextArea.text.trim()
        if (content.isBlank()) {
            Messages.showWarningDialog(project, "YAML content cannot be empty.", "Empty YAML")
            return
        }

        val actionName = if (dryRun) "Dry Run" else "Apply"
        statusLabel.text = "Executing $actionName for $resourceType $resourceName..."

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = KubernetesSshClient(profile)
                val res = client.applyResourceYaml(namespace, content, dryRun)
                val isSuccess = res.isSuccess
                val resultOutput = if (res.isSuccess) res.output else res.error.ifBlank { res.output }

                runOnEdt {
                    if (isSuccess) {
                        statusLabel.text = "$actionName successful!"
                        if (!dryRun) {
                            KubernetesDataCache.putYaml(profile.id, namespace, resourceType, resourceName, content)
                            KubernetesDataCache.invalidateNamespace(profile.id, namespace)
                        }
                        Messages.showInfoMessage(
                            project,
                            "$actionName succeeded:\n$resultOutput",
                            "$actionName Success"
                        )
                        if (!dryRun) {
                            onResourceUpdated?.invoke()
                        }
                    } else {
                        statusLabel.text = "$actionName failed: $resultOutput"
                        Messages.showErrorDialog(
                            project,
                            "Failed to $actionName $resourceType:\n$resultOutput",
                            "$actionName Failed"
                        )
                    }
                }
            } catch (e: Exception) {
                runOnEdt {
                    val msg = e.message ?: "Unknown error"
                    statusLabel.text = "$actionName failed: $msg"
                    Messages.showErrorDialog(
                        project,
                        "Exception during $actionName $resourceType:\n$msg",
                        "$actionName Error"
                    )
                }
            }
        }
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }
}
