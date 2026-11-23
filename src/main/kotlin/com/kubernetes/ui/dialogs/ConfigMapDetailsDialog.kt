package com.kubernetes.ui.dialogs

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.kubernetes.cache.KubernetesDataCache
import com.kubernetes.model.ConfigMapInfo
import com.kubernetes.model.SshProfile
import com.kubernetes.ssh.KubernetesSshClient
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.Action
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

class ConfigMapDetailsDialog(
    private val project: Project,
    private val profile: SshProfile,
    private val configMap: ConfigMapInfo,
    private val onConfigMapUpdated: (() -> Unit)? = null
) : DialogWrapper(project, true) {

    private val dataTextArea = JBTextArea().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        isEditable = false
        caretPosition = 0
    }

    private val yamlTextArea = JBTextArea().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        tabSize = 2
        isEditable = true
    }

    private val statusLabel = JBLabel("Ready")
    private val listModel = DefaultListModel<String>()
    private val keyList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    init {
        title = "ConfigMap: ${configMap.name} (${configMap.namespace})"
        init()
        loadDataKeys()
        loadYaml()
    }

    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            ApplicationManager.getApplication().invokeLater(action, ModalityState.any())
        }
    }

    private fun loadDataKeys() {
        listModel.clear()
        configMap.keys.forEach { listModel.addElement(it) }
        if (listModel.isEmpty) {
            configMap.data.keys.forEach { listModel.addElement(it) }
        }
        if (!listModel.isEmpty) {
            keyList.selectedIndex = 0
            val key = listModel.get(0)
            dataTextArea.text = configMap.data[key] ?: "<Binary or Empty Content>"
            dataTextArea.caretPosition = 0
        } else {
            dataTextArea.text = "<No data keys in this ConfigMap>"
        }

        keyList.addListSelectionListener {
            val selected = keyList.selectedValue
            if (selected != null) {
                dataTextArea.text = configMap.data[selected] ?: "<Binary or Empty Content>"
                dataTextArea.caretPosition = 0
            }
        }
    }

    private fun loadYaml() {
        val cached = KubernetesDataCache.getYaml(profile.id, configMap.namespace, "configmap", configMap.name)
        if (cached != null) {
            yamlTextArea.text = cached
            yamlTextArea.caretPosition = 0
            statusLabel.text = "YAML loaded from cache."
            return
        }

        statusLabel.text = "Loading YAML from cluster..."
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = KubernetesSshClient(profile)
                val res = client.getConfigMapYaml(configMap.namespace, configMap.name)
                runOnEdt {
                    if (res.isSuccess) {
                        KubernetesDataCache.putYaml(profile.id, configMap.namespace, "configmap", configMap.name, res.output)
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
        panel.preferredSize = Dimension(820, 560)
        panel.border = JBUI.Borders.empty(4)

        val tabbedPane = JBTabbedPane()

        // Tab 1: Key-Value Data Viewer
        val dataViewerPanel = JPanel(BorderLayout(6, 6)).apply {
            val keyPanel = JPanel(BorderLayout()).apply {
                add(JBLabel("Keys (${configMap.dataCount}):").apply { border = JBUI.Borders.empty(2, 4) }, BorderLayout.NORTH)
                add(JBScrollPane(keyList), BorderLayout.CENTER)
                preferredSize = Dimension(220, 400)
            }

            val contentPanel = JPanel(BorderLayout()).apply {
                add(JBLabel("Data Content:").apply { border = JBUI.Borders.empty(2, 4) }, BorderLayout.NORTH)
                add(JBScrollPane(dataTextArea), BorderLayout.CENTER)
            }

            val splitter = JBSplitter(false, 0.3f).apply {
                firstComponent = keyPanel
                secondComponent = contentPanel
            }
            add(splitter, BorderLayout.CENTER)
        }

        tabbedPane.addTab("Data Entries", AllIcons.Nodes.Property, dataViewerPanel)

        // Tab 2: Full YAML Viewer & Editor
        val yamlPanel = JPanel(BorderLayout(0, 6)).apply {
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
            add(toolbar, BorderLayout.NORTH)
            add(JBScrollPane(yamlTextArea), BorderLayout.CENTER)
        }

        tabbedPane.addTab("YAML Editor", AllIcons.FileTypes.Yaml, yamlPanel)

        panel.add(tabbedPane, BorderLayout.CENTER)
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
        statusLabel.text = "Executing $actionName for ConfigMap ${configMap.name}..."

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = KubernetesSshClient(profile)
                val res = client.applyResourceYaml(configMap.namespace, content, dryRun)
                val isSuccess = res.isSuccess
                val resultOutput = if (res.isSuccess) res.output else res.error.ifBlank { res.output }

                runOnEdt {
                    if (isSuccess) {
                        statusLabel.text = "$actionName successful!"
                        if (!dryRun) {
                            KubernetesDataCache.putYaml(profile.id, configMap.namespace, "configmap", configMap.name, content)
                            KubernetesDataCache.invalidateNamespace(profile.id, configMap.namespace)
                        }
                        Messages.showInfoMessage(
                            project,
                            "$actionName succeeded:\n$resultOutput",
                            "$actionName Success"
                        )
                        if (!dryRun) {
                            onConfigMapUpdated?.invoke()
                        }
                    } else {
                        statusLabel.text = "$actionName failed: $resultOutput"
                        Messages.showErrorDialog(
                            project,
                            "Failed to $actionName ConfigMap:\n$resultOutput",
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
                        "Exception during $actionName ConfigMap:\n$msg",
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
