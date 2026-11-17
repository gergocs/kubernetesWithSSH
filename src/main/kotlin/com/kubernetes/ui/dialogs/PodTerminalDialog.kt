package com.kubernetes.ui.dialogs

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.kubernetes.model.PodInfo
import com.kubernetes.model.SshProfile
import com.kubernetes.terminal.AnsiTerminalPane
import com.kubernetes.terminal.PodTerminalSession
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

class PodTerminalDialog(
    project: Project,
    private val profile: SshProfile,
    private val pod: PodInfo
) : DialogWrapper(project, true) {

    private val terminalPane = AnsiTerminalPane()
    private var session: PodTerminalSession? = null

    private val containerComboBox = ComboBox<String>()
    private val shellComboBox = ComboBox<String>()
    private val statusLabel = JBLabel("Initializing...")
    private val reconnectButton = JButton("Reconnect", AllIcons.Actions.Restart)
    private val ctrlCButton = JButton("Ctrl+C", AllIcons.Actions.Suspend)
    private val clearButton = JButton("Clear", AllIcons.Actions.GC)

    private val commandInputField = JBTextField()
    private val sendButton = JButton("Send", AllIcons.Actions.Execute)

    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1

    init {
        title = "Terminal: ${pod.name} (${pod.namespace}) - ${profile.name}"
        setOKButtonText("Close")
        init()
        startTerminalSession()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout(0, 6)).apply {
            preferredSize = Dimension(900, 600)
            border = JBUI.Borders.empty(4)
        }

        // Top Control Panel
        val topPanel = JPanel(BorderLayout(6, 0))

        val leftControls = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(JBLabel("Container:").apply { font = font.deriveFont(Font.BOLD) })

            // Populate containers
            val containers = if (pod.containers.isNotEmpty()) {
                pod.containers
            } else if (pod.images.isNotEmpty()) {
                pod.images.map { it.substringAfterLast("/").substringBefore(":") }
            } else {
                listOf(pod.name)
            }

            containers.forEach { containerComboBox.addItem(it) }
            add(containerComboBox)

            add(JBLabel("Shell:").apply { font = font.deriveFont(Font.BOLD) })
            shellComboBox.isEditable = true
            shellComboBox.addItem("/bin/sh")
            shellComboBox.addItem("/bin/bash")
            shellComboBox.addItem("/bin/zsh")
            shellComboBox.addItem("sh")
            shellComboBox.addItem("bash")
            shellComboBox.selectedItem = "/bin/sh"
            add(shellComboBox)
        }

        val rightControls = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 2)).apply {
            add(statusLabel)
            add(reconnectButton)
            add(ctrlCButton)
            add(clearButton)
        }

        topPanel.add(leftControls, BorderLayout.WEST)
        topPanel.add(rightControls, BorderLayout.EAST)
        mainPanel.add(topPanel, BorderLayout.NORTH)

        // Center Terminal Screen
        val scrollPane = JBScrollPane(terminalPane).apply {
            border = JBUI.Borders.customLine(Color(60, 60, 60))
        }
        mainPanel.add(scrollPane, BorderLayout.CENTER)

        // Bottom Command Bar
        val bottomPanel = JPanel(BorderLayout(6, 4))

        val inputPanel = JPanel(BorderLayout(4, 0)).apply {
            commandInputField.emptyText.text = "Type command or click inside terminal to type interactively..."
            add(commandInputField, BorderLayout.CENTER)
            add(sendButton, BorderLayout.EAST)
        }

        val hintLabel = JBLabel("Tip: Click inside terminal for interactive commands (top, vi, etc.) | Ctrl+C to interrupt").apply {
            foreground = Color.GRAY
            font = font.deriveFont(11f)
            border = JBUI.Borders.emptyLeft(2)
        }

        bottomPanel.add(inputPanel, BorderLayout.CENTER)
        bottomPanel.add(hintLabel, BorderLayout.SOUTH)
        mainPanel.add(bottomPanel, BorderLayout.SOUTH)

        // Setup event handlers
        setupEventHandlers(scrollPane)

        return mainPanel
    }

    private fun setupEventHandlers(scrollPane: JBScrollPane) {
        terminalPane.onInput = { input ->
            session?.sendInput(input)
        }

        terminalPane.onCtrlC = {
            session?.sendCtrlC()
        }

        terminalPane.onCtrlD = {
            session?.sendCtrlD()
        }

        terminalPane.onCtrlZ = {
            session?.sendCtrlZ()
        }

        terminalPane.onCtrlL = {
            terminalPane.clearTerminal()
            session?.sendCtrlL()
        }

        reconnectButton.addActionListener {
            startTerminalSession()
        }

        ctrlCButton.addActionListener {
            session?.sendCtrlC()
            terminalPane.requestFocusInWindow()
        }

        clearButton.addActionListener {
            terminalPane.clearTerminal()
            terminalPane.requestFocusInWindow()
        }

        sendButton.addActionListener {
            sendCommandFromInput()
        }

        commandInputField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> {
                        sendCommandFromInput()
                        e.consume()
                    }
                    KeyEvent.VK_UP -> {
                        if (commandHistory.isNotEmpty()) {
                            if (historyIndex == -1) {
                                historyIndex = commandHistory.size - 1
                            } else if (historyIndex > 0) {
                                historyIndex--
                            }
                            commandInputField.text = commandHistory[historyIndex]
                            commandInputField.selectAll()
                        }
                        e.consume()
                    }
                    KeyEvent.VK_DOWN -> {
                        if (commandHistory.isNotEmpty() && historyIndex != -1) {
                            if (historyIndex < commandHistory.size - 1) {
                                historyIndex++
                                commandInputField.text = commandHistory[historyIndex]
                            } else {
                                historyIndex = -1
                                commandInputField.text = ""
                            }
                        }
                        e.consume()
                    }
                }
            }
        })

        containerComboBox.addActionListener {
            if (session?.isSessionRunning == true) {
                startTerminalSession()
            }
        }

        shellComboBox.addActionListener {
            if (session?.isSessionRunning == true) {
                startTerminalSession()
            }
        }

        scrollPane.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val fontMetrics = terminalPane.getFontMetrics(terminalPane.font)
                val charWidth = fontMetrics.charWidth('W').coerceAtLeast(1)
                val charHeight = fontMetrics.height.coerceAtLeast(1)

                val cols = (scrollPane.viewport.width / charWidth).coerceIn(40, 300)
                val rows = (scrollPane.viewport.height / charHeight).coerceIn(10, 100)

                session?.resize(cols, rows)
            }
        })
    }

    private fun sendCommandFromInput() {
        val text = commandInputField.text
        if (text.isNotBlank()) {
            commandHistory.add(text)
            historyIndex = -1
            session?.sendInput("$text\r")
            commandInputField.text = ""
        } else {
            session?.sendInput("\r")
        }
        terminalPane.requestFocusInWindow()
    }

    private fun startTerminalSession() {
        session?.close()

        val selectedContainer = containerComboBox.selectedItem as? String
        val selectedShell = (shellComboBox.selectedItem as? String)?.trim().let {
            if (it.isNullOrBlank()) "/bin/sh" else it
        }

        updateStatusBadge("Connecting...", Color(242, 201, 76))
        reconnectButton.isEnabled = false

        val newSession = PodTerminalSession(
            profile = profile,
            namespace = pod.namespace,
            podName = pod.name,
            container = selectedContainer,
            shell = selectedShell,
            onOutput = { text ->
                terminalPane.appendOutput(text)
            },
            onStatusChange = { status ->
                SwingUtilities.invokeLater {
                    when {
                        status.startsWith("Connected", ignoreCase = true) -> {
                            updateStatusBadge("Connected", Color(80, 205, 137))
                            reconnectButton.isEnabled = true
                        }
                        status.startsWith("Disconnected", ignoreCase = true) -> {
                            updateStatusBadge(status, Color(235, 87, 87))
                            reconnectButton.isEnabled = true
                        }
                        status.startsWith("Failed", ignoreCase = true) -> {
                            updateStatusBadge("Connection Failed", Color(235, 87, 87))
                            reconnectButton.isEnabled = true
                        }
                        else -> {
                            updateStatusBadge(status, Color(242, 201, 76))
                        }
                    }
                }
            },
            onClosed = {
                SwingUtilities.invokeLater {
                    reconnectButton.isEnabled = true
                }
            }
        )

        session = newSession
        terminalPane.appendOutput("\n--- Connecting to Pod '${pod.name}' (${pod.namespace}) ---\n")
        newSession.start(cols = 120, rows = 30)

        SwingUtilities.invokeLater {
            terminalPane.requestFocusInWindow()
        }
    }

    private fun updateStatusBadge(text: String, color: Color) {
        statusLabel.text = "● $text"
        statusLabel.foreground = color
    }

    override fun createActions(): Array<Action> {
        return arrayOf(cancelAction.apply {
            putValue(Action.NAME, "Close")
        })
    }

    override fun dispose() {
        session?.close()
        session = null
        super.dispose()
    }
}
