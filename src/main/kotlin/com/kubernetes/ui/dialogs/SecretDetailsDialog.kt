package com.kubernetes.ui.dialogs

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.kubernetes.cache.KubernetesDataCache
import com.kubernetes.model.ResourceType
import com.kubernetes.model.SecretInfo
import com.kubernetes.model.SshProfile
import com.kubernetes.ssh.KubernetesSshClient
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.Base64
import javax.swing.Action
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

class SecretDetailsDialog(
    private val project: Project,
    private val profile: SshProfile,
    private val secret: SecretInfo,
    private val onSecretUpdated: (() -> Unit)? = null
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

    private val decodeCheckBox = JBCheckBox("Decode Base64 values", true)
    private val statusLabel = JBLabel("Ready")
    private val listModel = DefaultListModel<String>()
    private val keyList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    init {
        title = "Secret: ${secret.name} [${secret.type}] (${secret.namespace})"
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

    private fun getSelectedValue(): String {
        val selected = keyList.selectedValue ?: return "<No key selected>"
        val rawBase64 = secret.data[selected]
        val stringDataVal = secret.stringData[selected]

        if (stringDataVal != null) {
            return stringDataVal
        }

        if (rawBase64 == null) {
            return "<Empty Value>"
        }

        return if (decodeCheckBox.isSelected) {
            try {
                val clean = rawBase64.trim().replace("\n", "").replace("\r", "")
                val decoded = Base64.getDecoder().decode(clean)
                String(decoded, Charsets.UTF_8)
            } catch (e: Exception) {
                "<Failed to decode base64: ${e.message}>\nRaw: $rawBase64"
            }
        } else {
            rawBase64
        }
    }

    private fun updateDataView() {
        if (listModel.isEmpty) {
            dataTextArea.text = "<No data keys in this Secret>"
            return
        }
        val text = getSelectedValue()
        dataTextArea.text = text
        dataTextArea.caretPosition = 0
    }

    private fun loadDataKeys() {
        listModel.clear()
        secret.keys.forEach { listModel.addElement(it) }
        if (listModel.isEmpty) {
            secret.data.keys.forEach { listModel.addElement(it) }
            secret.stringData.keys.forEach { if (!listModel.contains(it)) listModel.addElement(it) }
        }

        if (!listModel.isEmpty) {
            keyList.selectedIndex = 0
        }
        updateDataView()

        keyList.addListSelectionListener {
            updateDataView()
        }

        decodeCheckBox.addActionListener {
            updateDataView()
        }
    }

    private fun loadYaml() {
        val cached = KubernetesDataCache.getYaml(profile.id, secret.namespace, "secret", secret.name)
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
                val res = client.getSecretYaml(secret.namespace, secret.name)
                runOnEdt {
                    if (res.isSuccess && res.output.isNotBlank()) {
                        yamlTextArea.text = res.output
                        yamlTextArea.caretPosition = 0
                        statusLabel.text = "YAML loaded successfully."
                        KubernetesDataCache.putYaml(profile.id, secret.namespace, "secret", secret.name, res.output)
                    } else {
                        yamlTextArea.text = "# Error retrieving YAML:\n${res.error.ifBlank { res.output }}"
                        statusLabel.text = "Failed to load YAML."
                    }
                }
            } catch (e: Exception) {
                runOnEdt {
                    yamlTextArea.text = "# Failed to execute command: ${e.message}"
                    statusLabel.text = "Error: ${e.message}"
                }
            }
        }
    }

    override fun createCenterPanel(): JComponent {
        val tabbedPane = JBTabbedPane()

        // Tab 1: Data Keys Explorer
        val dataPanel = JPanel(BorderLayout(8, 8))
        val splitter = JBSplitter(false, 0.3f)

        val leftPanel = JPanel(BorderLayout(4, 4)).apply {
            border = JBUI.Borders.empty(4)
            add(JBLabel("Keys (${secret.dataCount}):"), BorderLayout.NORTH)
            add(JBScrollPane(keyList), BorderLayout.CENTER)
        }

        val rightPanel = JPanel(BorderLayout(4, 4)).apply {
            border = JBUI.Borders.empty(4)
            val topBar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
                add(decodeCheckBox)
                val copyValBtn = JButton("Copy Value", AllIcons.Actions.Copy).apply {
                    addActionListener {
                        val text = getSelectedValue()
                        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                        clipboard.setContents(StringSelection(text), null)
                        statusLabel.text = "Copied value of '${keyList.selectedValue}' to clipboard."
                    }
                }
                add(copyValBtn)
            }
            add(topBar, BorderLayout.NORTH)
            add(JBScrollPane(dataTextArea), BorderLayout.CENTER)
        }

        splitter.firstComponent = leftPanel
        splitter.secondComponent = rightPanel
        dataPanel.add(splitter, BorderLayout.CENTER)
        tabbedPane.addTab("Data Keys", AllIcons.Nodes.Padlock, dataPanel)

        // Tab 2: YAML Editor
        val yamlPanel = JPanel(BorderLayout(4, 4)).apply {
            border = JBUI.Borders.empty(8)
            add(JBScrollPane(yamlTextArea), BorderLayout.CENTER)

            val yamlActionsBar = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 2)).apply {
                val copyYamlBtn = JButton("Copy YAML", AllIcons.Actions.Copy).apply {
                    addActionListener {
                        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                        clipboard.setContents(StringSelection(yamlTextArea.text), null)
                        statusLabel.text = "YAML copied to clipboard."
                    }
                }
                val dryRunBtn = JButton("Dry Run Apply", AllIcons.Actions.CheckOut).apply {
                    addActionListener {
                        applyYaml(dryRun = true)
                    }
                }
                val applyBtn = JButton("Apply YAML", AllIcons.Actions.Commit).apply {
                    addActionListener {
                        applyYaml(dryRun = false)
                    }
                }
                add(copyYamlBtn)
                add(dryRunBtn)
                add(applyBtn)
            }
            add(yamlActionsBar, BorderLayout.SOUTH)
        }
        tabbedPane.addTab("YAML", AllIcons.FileTypes.Yaml, yamlPanel)

        val mainPanel = JPanel(BorderLayout(4, 4)).apply {
            preferredSize = Dimension(780, 520)
            add(tabbedPane, BorderLayout.CENTER)
            add(statusLabel.apply { border = JBUI.Borders.empty(4, 8) }, BorderLayout.SOUTH)
        }

        return mainPanel
    }

    private fun applyYaml(dryRun: Boolean) {
        val newYaml = yamlTextArea.text
        if (newYaml.isBlank()) {
            Messages.showWarningDialog(project, "YAML content is empty.", "Invalid Input")
            return
        }

        val modeText = if (dryRun) "Dry Run" else "Apply"
        statusLabel.text = "Executing $modeText..."

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = KubernetesSshClient(profile)
                val res = client.applyResourceYaml(secret.namespace, newYaml, dryRun = dryRun)
                runOnEdt {
                    if (res.isSuccess) {
                        statusLabel.text = if (dryRun) "Dry run check passed successfully." else "Secret applied successfully."
                        Messages.showInfoMessage(
                            project,
                            "${if (dryRun) "Dry run completed successfully:\n" else "Applied successfully:\n"}${res.output.trim()}",
                            "Secret $modeText"
                        )
                        if (!dryRun) {
                            KubernetesDataCache.invalidateResourceType(profile.id, secret.namespace, ResourceType.SECRETS)
                            KubernetesDataCache.putYaml(profile.id, secret.namespace, "secret", secret.name, newYaml)
                            onSecretUpdated?.invoke()
                        }
                    } else {
                        statusLabel.text = "$modeText failed."
                        Messages.showErrorDialog(
                            project,
                            "Failed to $modeText secret:\n${res.error.ifBlank { res.output }}",
                            "Error"
                        )
                    }
                }
            } catch (e: Exception) {
                runOnEdt {
                    statusLabel.text = "Error during $modeText."
                    Messages.showErrorDialog(project, "Exception during $modeText: ${e.message}", "Error")
                }
            }
        }
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }
}
