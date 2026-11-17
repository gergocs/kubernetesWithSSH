package com.kubernetes.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.kubernetes.model.CommandResult
import com.kubernetes.model.SshProfile
import com.kubernetes.settings.KubernetesSshConfigurable
import com.kubernetes.settings.KubernetesSshSettings
import com.kubernetes.ssh.KubernetesSshClient
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.text.DefaultHighlighter

class KubectlConsolePanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    var onProfileChanged: ((SshProfile) -> Unit)? = null
    var onNamespaceChanged: ((String) -> Unit)? = null

    private val profileComboBox = ComboBox<SshProfile>()
    private val namespaceComboBox = ComboBox<String>()
    private val presetsComboBox = ComboBox<String>()

    private val commandInputField = JBTextField()
    private val executeButton = JButton("Execute", AllIcons.Actions.Execute)
    private val autoNamespaceCheckBox = JBCheckBox("Auto -n", true)
    private val appendOutputCheckBox = JBCheckBox("Append", false)

    private val consoleTextArea = JBTextArea()
    private val searchField = SearchTextField()

    private val statusIconLabel = JBLabel(AllIcons.General.Information)
    private val statusTextLabel = JBLabel("Ready to execute kubectl commands")
    private val statusTimeLabel = JBLabel("")

    private var isUpdatingProfiles = false
    private var isUpdatingNamespaces = false
    private var isDisposed = false
    private val isExecuting = AtomicBoolean(false)

    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1

    private var fullOutputBuffer = StringBuilder()

    private val presets = listOf(
        "-- Presets / Quick Snippets --",
        "get nodes -o wide",
        "get pods -A -o wide",
        "get pods -o wide",
        "get services -A",
        "get deployments -A",
        "get events --sort-by=.metadata.creationTimestamp",
        "get ingress -A",
        "get configmaps,secrets",
        "get namespaces",
        "cluster-info",
        "top nodes",
        "top pods -A",
        "api-resources",
        "version"
    )

    init {
        setupUI()
        refreshProfiles()
        loadNamespaces()
    }

    private fun setupUI() {
        border = JBUI.Borders.empty(4)

        // 1. Top Panel: Profile, Namespace, Presets, and Main Action Toolbar
        val topPanel = JPanel(BorderLayout())

        val selectorsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            profileComboBox.toolTipText = "Select SSH Profile"
            profileComboBox.prototypeDisplayValue = SshProfile(name = "production-server-profile")
            profileComboBox.addActionListener {
                if (!isUpdatingProfiles) {
                    val selectedProf = profileComboBox.selectedItem as? SshProfile
                    if (selectedProf != null) {
                        val settings = KubernetesSshSettings.getInstance(project)
                        settings.state.selectProfile(selectedProf.id)
                        onProfileChanged?.invoke(selectedProf)
                        loadNamespaces()
                    }
                }
            }
            add(profileComboBox)

            namespaceComboBox.toolTipText = "Select Kubernetes Namespace"
            namespaceComboBox.prototypeDisplayValue = "kube-system-long-namespace"
            namespaceComboBox.addActionListener {
                if (!isUpdatingNamespaces) {
                    val selectedNs = namespaceComboBox.selectedItem as? String
                    if (!selectedNs.isNullOrBlank()) {
                        onNamespaceChanged?.invoke(selectedNs)
                    }
                }
            }
            add(namespaceComboBox)

            presets.forEach { presetsComboBox.addItem(it) }
            presetsComboBox.toolTipText = "Select a quick command preset"
            presetsComboBox.addActionListener {
                val selected = presetsComboBox.selectedItem as? String
                if (!selected.isNullOrBlank() && !selected.startsWith("--")) {
                    commandInputField.text = selected
                    commandInputField.requestFocusInWindow()
                }
            }
            add(presetsComboBox)
        }

        val actionGroup = DefaultActionGroup().apply {
            add(object : AnAction("Execute", "Execute current command (Enter)", AllIcons.Actions.Execute) {
                override fun actionPerformed(e: AnActionEvent) {
                    executeCurrentCommand()
                }
            })
            add(object : AnAction("Clear Output", "Clear console output", AllIcons.Actions.GC) {
                override fun actionPerformed(e: AnActionEvent) {
                    clearConsole()
                }
            })
            add(object : AnAction("Copy Output", "Copy console output to clipboard", AllIcons.Actions.Copy) {
                override fun actionPerformed(e: AnActionEvent) {
                    copyConsoleOutput()
                }
            })
            addSeparator()
            add(object : AnAction("Refresh Namespaces", "Fetch namespaces from remote cluster", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    loadNamespaces()
                }
            })
            addSeparator()
            add(object : AnAction("Settings", "Open Kubernetes SSH Settings", AllIcons.General.Settings) {
                override fun actionPerformed(e: AnActionEvent) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, KubernetesSshConfigurable::class.java)
                    refreshProfiles()
                    val active = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
                    onProfileChanged?.invoke(active)
                    loadNamespaces()
                }
            })
        }

        val toolbar: ActionToolbar = ActionManager.getInstance().createActionToolbar(
            ActionPlaces.TOOLBAR,
            actionGroup,
            true
        )
        toolbar.targetComponent = this

        topPanel.add(selectorsPanel, BorderLayout.WEST)
        topPanel.add(toolbar.component, BorderLayout.EAST)

        // 2. Command Input Box Panel
        val inputCardPanel = JPanel(BorderLayout(4, 2)).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 1, 0),
                JBUI.Borders.empty(4, 2)
            )

            val prefixLabel = JBLabel("kubectl ").apply {
                font = Font(Font.MONOSPACED, Font.BOLD, 12)
                foreground = JBColor.namedColor("Label.infoForeground", Color(0x38, 0x8E, 0x3C))
            }

            commandInputField.emptyText.text = "e.g. get nodes -o wide, describe pod <name>, cluster-info..."
            commandInputField.font = Font(Font.MONOSPACED, Font.PLAIN, 12)

            // History navigation with Up and Down arrows
            commandInputField.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ENTER) {
                        executeCurrentCommand()
                    } else if (e.keyCode == KeyEvent.VK_UP) {
                        navigateHistory(up = true)
                    } else if (e.keyCode == KeyEvent.VK_DOWN) {
                        navigateHistory(up = false)
                    }
                }
            })

            executeButton.addActionListener { executeCurrentCommand() }
            autoNamespaceCheckBox.toolTipText = "Automatically append '-n <namespace>' if not already present in the command"
            appendOutputCheckBox.toolTipText = "Append new command output to existing output instead of replacing it"

            val rightOptionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                add(autoNamespaceCheckBox)
                add(appendOutputCheckBox)
                add(executeButton)
            }

            add(prefixLabel, BorderLayout.WEST)
            add(commandInputField, BorderLayout.CENTER)
            add(rightOptionsPanel, BorderLayout.EAST)
        }

        val northContainer = JPanel(BorderLayout()).apply {
            add(topPanel, BorderLayout.NORTH)
            add(inputCardPanel, BorderLayout.SOUTH)
        }
        add(northContainer, BorderLayout.NORTH)

        // 3. Output Center Area (Console + Search)
        consoleTextArea.apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            margin = JBUI.insets(6)
            text = "# Kubernetes Remote SSH Kubectl Console\n# Type a kubectl command above and press Enter or click Execute.\n"
        }
        fullOutputBuffer.append(consoleTextArea.text)

        val scrollPane = JBScrollPane(consoleTextArea).apply {
            border = JBUI.Borders.empty()
        }

        // Search Bar for Output
        val searchPanel = JPanel(BorderLayout(4, 2)).apply {
            border = JBUI.Borders.empty(2, 4)
            val searchLabel = JBLabel("Find in output:")
            searchField.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    filterOutput(searchField.text)
                }
            })
            add(searchLabel, BorderLayout.WEST)
            add(searchField, BorderLayout.CENTER)
        }

        val centerPanel = JPanel(BorderLayout()).apply {
            add(searchPanel, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }
        add(centerPanel, BorderLayout.CENTER)

        // 4. Status Bar at Bottom
        val bottomStatusPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                JBUI.Borders.empty(2, 4)
            )

            val leftStatusPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(statusIconLabel)
                add(statusTextLabel)
            }

            add(leftStatusPanel, BorderLayout.WEST)
            add(statusTimeLabel, BorderLayout.EAST)
        }
        add(bottomStatusPanel, BorderLayout.SOUTH)
    }

    fun syncProfile(profile: SshProfile) {
        isUpdatingProfiles = true
        try {
            refreshProfiles()
            for (i in 0 until profileComboBox.itemCount) {
                val item = profileComboBox.getItemAt(i)
                if (item.id == profile.id) {
                    profileComboBox.selectedIndex = i
                    break
                }
            }
        } finally {
            isUpdatingProfiles = false
        }
        loadNamespaces()
    }

    fun syncNamespace(namespace: String) {
        if (namespace.isBlank()) return
        isUpdatingNamespaces = true
        try {
            for (i in 0 until namespaceComboBox.itemCount) {
                if (namespaceComboBox.getItemAt(i) == namespace) {
                    namespaceComboBox.selectedIndex = i
                    return
                }
            }
            namespaceComboBox.addItem(namespace)
            namespaceComboBox.selectedItem = namespace
        } finally {
            isUpdatingNamespaces = false
        }
    }

    fun refreshProfiles() {
        isUpdatingProfiles = true
        try {
            profileComboBox.removeAllItems()
            val settings = KubernetesSshSettings.getInstance(project).state
            val profiles = settings.profiles
            val active = settings.getActiveProfile()

            profiles.forEach { profileComboBox.addItem(it) }
            val activeIndex = profiles.indexOfFirst { it.id == active.id }
            if (activeIndex >= 0) {
                profileComboBox.selectedIndex = activeIndex
            }
        } finally {
            isUpdatingProfiles = false
        }
    }

    fun loadNamespaces() {
        val selectedProfile = profileComboBox.selectedItem as? SshProfile
            ?: KubernetesSshSettings.getInstance(project).state.getActiveProfile()

        if (selectedProfile.host.isBlank()) {
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = KubernetesSshClient(selectedProfile)
                val namespaces = client.getNamespaces()

                ApplicationManager.getApplication().invokeLater {
                    if (isDisposed) return@invokeLater
                    isUpdatingNamespaces = true
                    try {
                        val currentSelected = namespaceComboBox.selectedItem as? String
                        namespaceComboBox.removeAllItems()
                        namespaceComboBox.addItem("[All Namespaces]")
                        namespaces.forEach { namespaceComboBox.addItem(it) }

                        val defaultNs = selectedProfile.defaultNamespace.ifBlank { "default" }
                        val toSelect = currentSelected ?: defaultNs
                        var foundIndex = -1
                        for (i in 0 until namespaceComboBox.itemCount) {
                            if (namespaceComboBox.getItemAt(i) == toSelect) {
                                foundIndex = i
                                break
                            }
                        }
                        if (foundIndex >= 0) {
                            namespaceComboBox.selectedIndex = foundIndex
                        } else if (namespaceComboBox.itemCount > 1) {
                            namespaceComboBox.selectedIndex = 1
                        }
                    } finally {
                        isUpdatingNamespaces = false
                    }
                }
            } catch (_: Exception) {
                // Ignore silent namespace load failures in background
            }
        }
    }

    fun executeCommand(command: String) {
        commandInputField.text = command
        executeCurrentCommand()
    }

    private fun executeCurrentCommand() {
        val rawCommand = commandInputField.text.trim()
        if (rawCommand.isBlank()) {
            Messages.showWarningDialog(project, "Please enter a kubectl command to execute.", "Empty Command")
            return
        }

        if (isExecuting.getAndSet(true)) {
            return
        }

        // Record history
        if (commandHistory.isEmpty() || commandHistory.last() != rawCommand) {
            commandHistory.add(rawCommand)
        }
        historyIndex = commandHistory.size

        val selectedProfile = profileComboBox.selectedItem as? SshProfile
            ?: KubernetesSshSettings.getInstance(project).state.getActiveProfile()

        if (selectedProfile.host.isBlank()) {
            isExecuting.set(false)
            Messages.showErrorDialog(project, "SSH Host is not configured. Please open Plugin Settings.", "SSH Configuration Missing")
            return
        }

        val selectedNamespace = namespaceComboBox.selectedItem as? String
        val targetNs = if (selectedNamespace == null || selectedNamespace == "[All Namespaces]") "" else selectedNamespace
        val autoNs = autoNamespaceCheckBox.isSelected && targetNs.isNotBlank()

        val client = KubernetesSshClient(selectedProfile)
        val resolvedCommand = client.resolveKubectlCommand(rawCommand, targetNs, autoNs)

        executeButton.isEnabled = false
        statusIconLabel.icon = AllIcons.Process.Step_1
        statusTextLabel.text = "Executing: $resolvedCommand..."
        statusTimeLabel.text = ""

        val startTime = System.currentTimeMillis()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Executing Kubectl Command", true) {
            var result: CommandResult? = null
            var errorException: Exception? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Executing '$resolvedCommand' on ${selectedProfile.host}..."
                try {
                    result = client.executeCustomKubectlCommand(rawCommand, targetNs, autoNs)
                } catch (e: Exception) {
                    errorException = e
                }
            }

            override fun onSuccess() {
                if (isDisposed) return
                val durationMs = System.currentTimeMillis() - startTime
                val timeStr = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))

                val header = "\n=================================================================\n" +
                        "[$timeStr] $resolvedCommand (Duration: ${durationMs}ms)\n" +
                        "-----------------------------------------------------------------\n"

                val cmdResult = result
                val outputText: String
                val isSuccess: Boolean

                if (errorException != null) {
                    isSuccess = false
                    outputText = "$header[ERROR] Failed to execute command: ${errorException?.message}\n${errorException?.stackTraceToString()}\n"
                    statusIconLabel.icon = AllIcons.General.Error
                    statusTextLabel.text = "Execution Failed (${errorException?.message ?: "Unknown error"})"
                } else if (cmdResult != null) {
                    isSuccess = cmdResult.isSuccess
                    val combined = if (cmdResult.output.isNotBlank() && cmdResult.error.isNotBlank()) {
                        "${cmdResult.output}\n[STDERR]\n${cmdResult.error}"
                    } else if (cmdResult.output.isNotBlank()) {
                        cmdResult.output
                    } else if (cmdResult.error.isNotBlank()) {
                        cmdResult.error
                    } else {
                        "(No output returned. Exit code: ${cmdResult.exitCode})"
                    }
                    outputText = "$header$combined\n"

                    if (isSuccess) {
                        statusIconLabel.icon = AllIcons.General.InspectionsOK
                        statusTextLabel.text = "Success (Exit code: ${cmdResult.exitCode})"
                    } else {
                        statusIconLabel.icon = AllIcons.General.Error
                        statusTextLabel.text = "Failed (Exit code: ${cmdResult.exitCode})"
                    }
                } else {
                    isSuccess = false
                    outputText = "$header[ERROR] No result returned.\n"
                    statusIconLabel.icon = AllIcons.General.Error
                    statusTextLabel.text = "No result"
                }

                statusTimeLabel.text = "$timeStr (${durationMs}ms)"

                if (appendOutputCheckBox.isSelected) {
                    fullOutputBuffer.append(outputText)
                } else {
                    fullOutputBuffer.setLength(0)
                    fullOutputBuffer.append(outputText)
                }

                applyCurrentOutput()
            }

            override fun onFinished() {
                isExecuting.set(false)
                executeButton.isEnabled = true
            }
        })
    }

    private fun applyCurrentOutput() {
        val filter = searchField.text.trim()
        if (filter.isBlank()) {
            consoleTextArea.text = fullOutputBuffer.toString()
            consoleTextArea.caretPosition = consoleTextArea.document.length
        } else {
            filterOutput(filter)
        }
    }

    private fun filterOutput(query: String) {
        val fullText = fullOutputBuffer.toString()
        if (query.isBlank()) {
            consoleTextArea.text = fullText
            consoleTextArea.highlighter.removeAllHighlights()
            return
        }

        consoleTextArea.text = fullText
        val highlighter = consoleTextArea.highlighter
        highlighter.removeAllHighlights()

        val painter = DefaultHighlighter.DefaultHighlightPainter(JBColor(Color(0xFF, 0xEB, 0x3B, 120), Color(0x7F, 0x6E, 0x00, 150)))
        var pos = 0
        var matchCount = 0
        val lowerText = fullText.lowercase()
        val lowerQuery = query.lowercase()

        while (pos < lowerText.length) {
            val idx = lowerText.indexOf(lowerQuery, pos)
            if (idx < 0) break
            try {
                highlighter.addHighlight(idx, idx + query.length, painter)
                if (matchCount == 0) {
                    consoleTextArea.caretPosition = idx
                }
                matchCount++
            } catch (_: Exception) {}
            pos = idx + query.length
        }

        statusTimeLabel.text = "Matches: $matchCount"
    }

    private fun navigateHistory(up: Boolean) {
        if (commandHistory.isEmpty()) return

        if (up) {
            if (historyIndex > 0) {
                historyIndex--
                commandInputField.text = commandHistory[historyIndex]
            }
        } else {
            if (historyIndex < commandHistory.size - 1) {
                historyIndex++
                commandInputField.text = commandHistory[historyIndex]
            } else {
                historyIndex = commandHistory.size
                commandInputField.text = ""
            }
        }
    }

    private fun clearConsole() {
        fullOutputBuffer.setLength(0)
        consoleTextArea.text = ""
        statusTextLabel.text = "Console cleared"
        statusIconLabel.icon = AllIcons.General.Information
        statusTimeLabel.text = ""
    }

    private fun copyConsoleOutput() {
        val text = consoleTextArea.text
        if (text.isNotBlank()) {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
            statusTextLabel.text = "Output copied to clipboard (${text.length} chars)"
        }
    }

    override fun dispose() {
        isDisposed = true
    }
}
