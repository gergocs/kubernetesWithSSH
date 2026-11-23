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
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import com.kubernetes.cache.KubernetesDataCache
import com.kubernetes.model.PodInfo
import com.kubernetes.model.ResourceType
import com.kubernetes.model.SshProfile
import com.kubernetes.settings.KubernetesSshSettings
import com.kubernetes.ssh.KubernetesSshClient
import com.kubernetes.ssh.SshSessionPool
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JToggleButton
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.text.DefaultHighlighter

/**
 * Dedicated, non-blocking tool window panel for streaming and tailing Kubernetes pod logs.
 * Supports concurrent multi-pod log streams, container selection, live filtering, pause/resume,
 * auto-scroll, timestamps, and tabbed session management.
 */
class TailLogsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    var onProfileChanged: ((SshProfile) -> Unit)? = null
    var onNamespaceChanged: ((String) -> Unit)? = null

    private val profileComboBox = ComboBox<SshProfile>()
    private val namespaceComboBox = ComboBox<String>()
    private val podComboBox = ComboBox<String>()
    private val containerComboBox = ComboBox<String>()
    private val tailLinesComboBox = ComboBox(arrayOf("50", "100", "200", "500", "1000", "All"))

    private val followCheckBox = JBCheckBox("Follow (-f)", true)
    private val timestampsCheckBox = JBCheckBox("Timestamps", false)
    private val previousCheckBox = JBCheckBox("Previous (-p)", false)

    private val startTailButton = JButton("Start Tail", AllIcons.Actions.Execute)
    private val multiPodButton = JButton("Multi-Pod...", AllIcons.Actions.ShowAsTree)
    private val stopAllButton = JButton("Stop All", AllIcons.Actions.Suspend)
    private val closeAllButton = JButton("Close All Tabs", AllIcons.Actions.Close)

    private val tabbedPane = JBTabbedPane()
    private val emptyPlaceholderPanel = JPanel(BorderLayout())

    private val activeSessions = mutableListOf<TailLogSession>()
    private var availablePods: List<PodInfo> = emptyList()

    private var isUpdatingProfiles = false
    private var isUpdatingNamespaces = false
    private var isUpdatingPods = false
    private var isDisposed = false

    init {
        setupUI()
        refreshProfiles()
        loadNamespaces()
    }

    private fun setupUI() {
        border = JBUI.Borders.empty(2)

        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        // Tier 1: Action Toolbar / Options
        val toolbarPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(4, 6)
            )

            val leftActions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                startTailButton.toolTipText = "Start streaming logs for the selected pod and options"
                startTailButton.addActionListener {
                    startTailFromControls()
                }
                add(startTailButton)

                multiPodButton.toolTipText = "Select and tail multiple pods concurrently in one aggregated view or separate tabs"
                multiPodButton.addActionListener {
                    showMultiPodSelectionDialog()
                }
                add(multiPodButton)

                stopAllButton.toolTipText = "Stop streaming in all active tabs"
                stopAllButton.addActionListener {
                    stopAllSessions()
                }
                add(stopAllButton)

                closeAllButton.toolTipText = "Close and stop all log tail tabs"
                closeAllButton.addActionListener {
                    closeAllSessions()
                }
                add(closeAllButton)
            }
            add(leftActions, BorderLayout.WEST)

            val rightOptions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
                add(followCheckBox)
                add(timestampsCheckBox)
                add(previousCheckBox)
                add(JBLabel("Tail Lines:"))
                tailLinesComboBox.selectedItem = "200"
                tailLinesComboBox.toolTipText = "Initial number of lines to fetch before following"
                add(tailLinesComboBox)
            }
            add(rightOptions, BorderLayout.EAST)
        }

        // Tier 2: Selectors (Profile, Namespace, Pod, Container)
        val selectorsPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(4, 6)
            )

            val leftSelectors = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                add(JBLabel("Profile:").apply { font = font.deriveFont(Font.BOLD) })
                profileComboBox.toolTipText = "Active SSH Connection Profile"
                profileComboBox.renderer = object : SimpleListCellRenderer<SshProfile>() {
                    override fun customize(list: javax.swing.JList<out SshProfile>, value: SshProfile?, index: Int, selected: Boolean, hasFocus: Boolean) {
                        if (value != null) {
                            text = value.name.ifBlank { value.host }
                            icon = AllIcons.General.Web
                        }
                    }
                }
                profileComboBox.addActionListener {
                    if (!isUpdatingProfiles) {
                        val selectedProf = profileComboBox.selectedItem as? SshProfile
                        if (selectedProf != null) {
                            KubernetesSshSettings.getInstance(project).state.selectProfile(selectedProf.id)
                            onProfileChanged?.invoke(selectedProf)
                            loadNamespaces()
                        }
                    }
                }
                add(profileComboBox)

                add(Box.createHorizontalStrut(JBUI.scale(6)))
                add(JBLabel("Namespace:").apply { font = font.deriveFont(Font.BOLD) })
                namespaceComboBox.toolTipText = "Target Kubernetes Namespace"
                namespaceComboBox.renderer = object : SimpleListCellRenderer<String>() {
                    override fun customize(list: javax.swing.JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
                        if (value != null) {
                            text = value
                            icon = AllIcons.Nodes.Folder
                        }
                    }
                }
                namespaceComboBox.addActionListener {
                    if (!isUpdatingNamespaces) {
                        val ns = namespaceComboBox.selectedItem as? String
                        if (!ns.isNullOrBlank()) {
                            onNamespaceChanged?.invoke(ns)
                            loadPodsForNamespace(ns)
                        }
                    }
                }
                add(namespaceComboBox)

                add(Box.createHorizontalStrut(JBUI.scale(6)))
                add(JBLabel("Pod:").apply { font = font.deriveFont(Font.BOLD) })
                podComboBox.toolTipText = "Target Pod to tail"
                podComboBox.renderer = object : SimpleListCellRenderer<String>() {
                    override fun customize(list: javax.swing.JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
                        if (value != null) {
                            text = value
                            icon = AllIcons.Nodes.Deploy
                        }
                    }
                }
                podComboBox.addActionListener {
                    if (!isUpdatingPods) {
                        updateContainersForSelectedPod()
                    }
                }
                add(podComboBox)

                add(Box.createHorizontalStrut(JBUI.scale(6)))
                add(JBLabel("Container:").apply { font = font.deriveFont(Font.BOLD) })
                containerComboBox.toolTipText = "Specific container inside the selected pod (or all containers)"
                containerComboBox.renderer = object : SimpleListCellRenderer<String>() {
                    override fun customize(list: javax.swing.JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
                        if (value != null) {
                            text = value
                            icon = AllIcons.Nodes.PpLibFolder
                        }
                    }
                }
                add(containerComboBox)
            }
            add(leftSelectors, BorderLayout.WEST)
        }

        topPanel.add(toolbarPanel)
        topPanel.add(selectorsPanel)
        add(topPanel, BorderLayout.NORTH)

        // Center Area: Empty Placeholder or Tabbed Sessions
        emptyPlaceholderPanel.apply {
            val emptyMsg = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(40)
                val title = JBLabel("No Active Log Tail Sessions", AllIcons.Debugger.Console, SwingConstants.CENTER).apply {
                    font = font.deriveFont(Font.BOLD, JBUI.scale(14f))
                    alignmentX = Component.CENTER_ALIGNMENT
                }
                val hint = JBLabel("Select a pod above and click 'Start Tail', use 'Multi-Pod...' or right-click any pod in the Resources explorer.", SwingConstants.CENTER).apply {
                    foreground = JBColor.GRAY
                    alignmentX = Component.CENTER_ALIGNMENT
                }
                add(title)
                add(Box.createVerticalStrut(JBUI.scale(8)))
                add(hint)
            }
            add(emptyMsg, BorderLayout.CENTER)
        }

        add(emptyPlaceholderPanel, BorderLayout.CENTER)
    }

    fun syncProfile(profile: SshProfile) {
        isUpdatingProfiles = true
        for (i in 0 until profileComboBox.itemCount) {
            val item = profileComboBox.getItemAt(i)
            if (item?.id == profile.id) {
                profileComboBox.selectedIndex = i
                break
            }
        }
        isUpdatingProfiles = false
        loadNamespaces()
    }

    fun refreshProfiles() {
        val settings = KubernetesSshSettings.getInstance(project)
        val profiles = settings.state.profiles
        val active = settings.state.getActiveProfile()

        isUpdatingProfiles = true
        profileComboBox.removeAllItems()
        profiles.forEach { profileComboBox.addItem(it) }
        profileComboBox.selectedItem = active
        isUpdatingProfiles = false
    }

    fun loadNamespaces() {
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        if (profile.host.isBlank()) return

        val cachedNs = KubernetesDataCache.getNamespaces(profile.id)
        if (cachedNs != null) {
            updateNamespacesUi(cachedNs)
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = KubernetesSshClient(profile)
                val nsList = client.getNamespaces()
                KubernetesDataCache.putNamespaces(profile.id, nsList)
                ApplicationManager.getApplication().invokeLater({
                    if (!isDisposed) {
                        updateNamespacesUi(nsList)
                    }
                }, ModalityState.any())
            } catch (_: Exception) {}
        }
    }

    private fun updateNamespacesUi(namespaces: List<String>) {
        isUpdatingNamespaces = true
        val prevSelected = namespaceComboBox.selectedItem as? String
        namespaceComboBox.removeAllItems()
        val defaultNs = KubernetesSshSettings.getInstance(project).state.getActiveProfile().defaultNamespace.ifBlank { "default" }

        for (ns in namespaces) {
            namespaceComboBox.addItem(ns)
        }

        if (prevSelected != null && namespaces.contains(prevSelected)) {
            namespaceComboBox.selectedItem = prevSelected
        } else if (namespaces.contains(defaultNs)) {
            namespaceComboBox.selectedItem = defaultNs
        } else if (namespaces.isNotEmpty()) {
            namespaceComboBox.selectedIndex = 0
        }
        isUpdatingNamespaces = false

        val activeNs = namespaceComboBox.selectedItem as? String ?: "default"
        loadPodsForNamespace(activeNs)
    }

    private fun loadPodsForNamespace(namespace: String) {
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        if (profile.host.isBlank() || namespace.isBlank()) return

        val cachedPods = KubernetesDataCache.getPods(profile.id, namespace)
        if (cachedPods != null) {
            updatePodsUi(cachedPods)
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = KubernetesSshClient(profile)
                val pods = client.getPods(namespace)
                KubernetesDataCache.putPods(profile.id, namespace, pods)
                ApplicationManager.getApplication().invokeLater({
                    if (!isDisposed) {
                        updatePodsUi(pods)
                    }
                }, ModalityState.any())
            } catch (_: Exception) {}
        }
    }

    private fun updatePodsUi(pods: List<PodInfo>) {
        availablePods = pods
        isUpdatingPods = true
        val prevSelected = podComboBox.selectedItem as? String
        podComboBox.removeAllItems()

        for (pod in pods) {
            podComboBox.addItem(pod.name)
        }

        if (prevSelected != null && pods.any { it.name == prevSelected }) {
            podComboBox.selectedItem = prevSelected
        } else if (pods.isNotEmpty()) {
            podComboBox.selectedIndex = 0
        }
        isUpdatingPods = false
        updateContainersForSelectedPod()
    }

    private fun updateContainersForSelectedPod() {
        val selectedPodName = podComboBox.selectedItem as? String ?: return
        val pod = availablePods.find { it.name == selectedPodName }

        containerComboBox.removeAllItems()
        containerComboBox.addItem("(All Containers)")

        if (pod != null && pod.containers.isNotEmpty()) {
            for (c in pod.containers) {
                containerComboBox.addItem(c)
            }
        }
        containerComboBox.selectedIndex = 0
    }

    private fun startTailFromControls() {
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        val namespace = namespaceComboBox.selectedItem as? String ?: "default"
        val podName = podComboBox.selectedItem as? String

        if (podName.isNullOrBlank()) {
            Messages.showWarningDialog(project, "Please select a pod to tail.", "No Pod Selected")
            return
        }

        val containerSelection = containerComboBox.selectedItem as? String
        val targetContainer = if (containerSelection != null && !containerSelection.startsWith("(")) containerSelection else null
        val follow = followCheckBox.isSelected
        val timestamps = timestampsCheckBox.isSelected
        val previous = previousCheckBox.isSelected
        val tailLinesStr = tailLinesComboBox.selectedItem as? String ?: "200"
        val tailLines = if (tailLinesStr.equals("All", ignoreCase = true)) -1 else tailLinesStr.toIntOrNull() ?: 200

        tailPod(
            profile = profile,
            namespace = namespace,
            podName = podName,
            container = targetContainer,
            follow = follow,
            tailLines = tailLines,
            timestamps = timestamps,
            previous = previous
        )
    }

    /**
     * Starts a tail log session for a single pod.
     */
    fun tailPod(
        profile: SshProfile,
        namespace: String,
        podName: String,
        container: String? = null,
        follow: Boolean = true,
        tailLines: Int = 200,
        timestamps: Boolean = false,
        previous: Boolean = false
    ) {
        tailMultiplePods(
            profile = profile,
            namespace = namespace,
            podNames = listOf(podName),
            container = container,
            follow = follow,
            tailLines = tailLines,
            timestamps = timestamps,
            previous = previous
        )
    }

    /**
     * Starts a tail log session for one or multiple pods.
     * When multiple pods are specified, creates an aggregated multi-pod tail session with colored prefixes.
     */
    fun tailMultiplePods(
        profile: SshProfile,
        namespace: String,
        podNames: List<String>,
        container: String? = null,
        follow: Boolean = true,
        tailLines: Int = 200,
        timestamps: Boolean = false,
        previous: Boolean = false
    ) {
        if (podNames.isEmpty()) return

        val title = if (podNames.size == 1) {
            val singlePod = podNames.first()
            if (!container.isNullOrBlank()) "$singlePod [$container]" else singlePod
        } else {
            "Aggregated (${podNames.size} Pods)"
        }

        val session = TailLogSession(
            project = project,
            profile = profile,
            namespace = namespace,
            podNames = podNames,
            container = container,
            follow = follow,
            tailLines = tailLines,
            timestamps = timestamps,
            previous = previous,
            title = title,
            onClose = { s -> closeSession(s) }
        )

        activeSessions.add(session)
        ensureTabbedPaneVisible()

        val tabIndex = tabbedPane.tabCount
        tabbedPane.addTab(title, session.component)
        tabbedPane.setTabComponentAt(tabIndex, createTabHeader(session))
        tabbedPane.selectedIndex = tabIndex

        session.start()
    }

    private fun createTabHeader(session: TailLogSession): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            val iconLabel = JBLabel(session.title, if (session.podNames.size > 1) AllIcons.Actions.ShowAsTree else AllIcons.Nodes.Deploy, SwingConstants.LEFT).apply {
                font = font.deriveFont(Font.PLAIN, JBUI.scale(11f))
            }
            add(iconLabel)

            val closeButton = JButton(AllIcons.Actions.Close).apply {
                toolTipText = "Close Tail Session"
                border = BorderFactory.createEmptyBorder()
                isContentAreaFilled = false
                isFocusable = false
                preferredSize = Dimension(JBUI.scale(16), JBUI.scale(16))
                addActionListener {
                    closeSession(session)
                }
            }
            add(closeButton)
        }
    }

    private fun ensureTabbedPaneVisible() {
        if (tabbedPane.parent == null) {
            remove(emptyPlaceholderPanel)
            add(tabbedPane, BorderLayout.CENTER)
            revalidate()
            repaint()
        }
    }

    private fun checkEmptyState() {
        if (activeSessions.isEmpty()) {
            remove(tabbedPane)
            add(emptyPlaceholderPanel, BorderLayout.CENTER)
            revalidate()
            repaint()
        }
    }

    private fun closeSession(session: TailLogSession) {
        val index = activeSessions.indexOf(session)
        if (index >= 0) {
            session.dispose()
            activeSessions.removeAt(index)
            if (index < tabbedPane.tabCount) {
                tabbedPane.removeTabAt(index)
            }
            checkEmptyState()
        }
    }

    fun stopAllSessions() {
        for (session in activeSessions) {
            session.stop()
        }
    }

    fun closeAllSessions() {
        for (session in activeSessions.toList()) {
            closeSession(session)
        }
    }

    private fun showMultiPodSelectionDialog() {
        val ns = namespaceComboBox.selectedItem as? String ?: "default"
        if (availablePods.isEmpty()) {
            Messages.showInfoMessage(project, "No pods found in namespace '$ns' to tail.", "No Pods Available")
            return
        }

        val dialog = MultiPodSelectDialog(project, ns, availablePods)
        if (dialog.showAndGet()) {
            val selected = dialog.getSelectedPods()
            if (selected.isNotEmpty()) {
                val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
                val follow = followCheckBox.isSelected
                val timestamps = timestampsCheckBox.isSelected
                val previous = previousCheckBox.isSelected
                val tailLinesStr = tailLinesComboBox.selectedItem as? String ?: "200"
                val tailLines = if (tailLinesStr.equals("All", ignoreCase = true)) -1 else tailLinesStr.toIntOrNull() ?: 200

                if (dialog.isOpenAsSeparateTabs()) {
                    for (podName in selected) {
                        tailPod(profile, ns, podName, null, follow, tailLines, timestamps, previous)
                    }
                } else {
                    tailMultiplePods(profile, ns, selected, null, follow, tailLines, timestamps, previous)
                }
            }
        }
    }

    override fun dispose() {
        isDisposed = true
        for (session in activeSessions) {
            session.dispose()
        }
        activeSessions.clear()
    }
}

/**
 * Individual tailing session for one or multiple pods.
 */
class TailLogSession(
    private val project: Project,
    val profile: SshProfile,
    val namespace: String,
    val podNames: List<String>,
    val container: String?,
    val follow: Boolean,
    val tailLines: Int,
    val timestamps: Boolean,
    val previous: Boolean,
    val title: String,
    private val onClose: (TailLogSession) -> Unit
) : Disposable {

    val component: JComponent
    private val textArea = JBTextArea()
    private val scrollPane = JBScrollPane(textArea)
    private val searchField = SearchTextField()

    private val statusIconLabel = JBLabel(AllIcons.Debugger.Console)
    private val statusTextLabel = JBLabel("Connecting...")
    private val lineCountLabel = JBLabel("0 lines")

    private val pauseResumeButton = JButton("Pause", AllIcons.Actions.Pause)
    private val restartButton = JButton("Restart", AllIcons.Actions.Restart)
    private val clearButton = JButton("Clear", AllIcons.Actions.GC)
    private val autoScrollCheckBox = JBCheckBox("Auto-scroll", true)
    private val softWrapCheckBox = JBCheckBox("Soft Wrap", false)
    private val copyAllButton = JButton("Copy All", AllIcons.Actions.Copy)

    private val isStreaming = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private var isDisposed = false

    private val activeChannels = mutableListOf<ChannelExec>()
    private val activeSessions = mutableListOf<Session>()

    private val logBuffer = ConcurrentLinkedQueue<String>()
    private val lineCounter = AtomicInteger(0)
    private var fullTextCache = StringBuilder()

    private val maxBufferLines = 8000

    init {
        component = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2)

            // Top Toolbar for Session Controls
            val sessionToolbar = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.compound(
                    JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                    JBUI.Borders.empty(3, 4)
                )

                val leftControls = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                    pauseResumeButton.toolTipText = "Pause or resume real-time log stream"
                    pauseResumeButton.addActionListener {
                        togglePauseResume()
                    }
                    add(pauseResumeButton)

                    restartButton.toolTipText = "Restart log streaming from the beginning"
                    restartButton.addActionListener {
                        restart()
                    }
                    add(restartButton)

                    clearButton.toolTipText = "Clear current log output"
                    clearButton.addActionListener {
                        clearOutput()
                    }
                    add(clearButton)

                    add(autoScrollCheckBox)

                    softWrapCheckBox.addActionListener {
                        textArea.lineWrap = softWrapCheckBox.isSelected
                        textArea.wrapStyleWord = softWrapCheckBox.isSelected
                    }
                    add(softWrapCheckBox)

                    copyAllButton.toolTipText = "Copy all displayed logs to clipboard"
                    copyAllButton.addActionListener {
                        copyLogsToClipboard()
                    }
                    add(copyAllButton)
                }
                add(leftControls, BorderLayout.WEST)

                val rightSearch = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                    searchField.textEditor.emptyText.text = "Search logs..."
                    searchField.addDocumentListener(object : DocumentAdapter() {
                        override fun textChanged(e: DocumentEvent) {
                            applyFilterOrHighlight(searchField.text)
                        }
                    })
                    add(searchField)
                }
                add(rightSearch, BorderLayout.EAST)
            }
            add(sessionToolbar, BorderLayout.NORTH)

            // Center Log Console Area
            textArea.apply {
                isEditable = false
                font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(12)).let {
                    if (it.family == "JetBrains Mono") it else Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12))
                }
                background = JBColor(Color(250, 250, 250), Color(30, 30, 30))
                margin = JBUI.insets(6)
                lineWrap = false
            }

            scrollPane.verticalScrollBar.addAdjustmentListener {
                if (autoScrollCheckBox.isSelected && !scrollPane.verticalScrollBar.valueIsAdjusting) {
                    scrollPane.verticalScrollBar.value = scrollPane.verticalScrollBar.maximum
                }
            }

            add(scrollPane, BorderLayout.CENTER)

            // Bottom Status Strip
            val statusStrip = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.compound(
                    JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                    JBUI.Borders.empty(2, 6)
                )

                val statusLeft = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                    add(statusIconLabel)
                    add(statusTextLabel)
                }
                add(statusLeft, BorderLayout.WEST)

                val statusRight = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                    lineCountLabel.foreground = JBColor.GRAY
                    add(lineCountLabel)
                }
                add(statusRight, BorderLayout.EAST)
            }
            add(statusStrip, BorderLayout.SOUTH)
        }
    }

    fun start() {
        if (isStreaming.getAndSet(true)) return

        statusTextLabel.text = "Connecting SSH session for ${podNames.joinToString(", ")}..."
        statusIconLabel.icon = AllIcons.Process.Step_1

        for (podName in podNames) {
            ApplicationManager.getApplication().executeOnPooledThread {
                streamPodLogs(podName)
            }
        }

        // Start UI batch flusher
        scheduleBatchFlusher()
    }

    private fun streamPodLogs(podName: String) {
        var session: Session? = null
        var channel: ChannelExec? = null

        try {
            session = SshSessionPool.createNewSession(profile)
            session.connect(profile.connectionTimeoutSeconds * 1000)
            synchronized(activeSessions) {
                if (isDisposed) {
                    session.disconnect()
                    return
                }
                activeSessions.add(session)
            }

            val followFlag = if (follow) " -f" else ""
            val tailFlag = if (tailLines > 0) " --tail=$tailLines" else ""
            val containerFlag = if (!container.isNullOrBlank()) " -c $container" else ""
            val timestampsFlag = if (timestamps) " --timestamps" else ""
            val previousFlag = if (previous) " -p" else ""

            val command = "${profile.kubectlPath} logs $podName -n $namespace$followFlag$tailFlag$containerFlag$timestampsFlag$previousFlag"

            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)

            val inputStream = channel.inputStream
            val errStream = channel.errStream

            channel.connect(profile.connectionTimeoutSeconds * 1000)
            synchronized(activeChannels) {
                if (isDisposed) {
                    channel.disconnect()
                    return
                }
                activeChannels.add(channel)
            }

            SwingUtilities.invokeLater {
                statusTextLabel.text = "Tailing logs for $title (Streaming...)"
                statusIconLabel.icon = AllIcons.Debugger.ThreadRunning
            }

            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
            val errReader = BufferedReader(InputStreamReader(errStream, Charsets.UTF_8))

            val prefix = if (podNames.size > 1) "[$podName] " else ""

            // Read output
            while (!isDisposed && isStreaming.get()) {
                var readAny = false

                while (reader.ready()) {
                    val line = reader.readLine() ?: break
                    readAny = true
                    if (!isPaused.get()) {
                        val cleanLine = stripAnsiCodes(line)
                        logBuffer.add("$prefix$cleanLine\n")
                    }
                }

                while (errReader.ready()) {
                    val line = errReader.readLine() ?: break
                    readAny = true
                    if (!isPaused.get()) {
                        val cleanLine = stripAnsiCodes(line)
                        logBuffer.add("$prefix[ERR] $cleanLine\n")
                    }
                }

                if (channel.isClosed) {
                    // Read remaining trailing lines
                    while (reader.ready()) {
                        val line = reader.readLine() ?: break
                        logBuffer.add("$prefix${stripAnsiCodes(line)}\n")
                    }
                    while (errReader.ready()) {
                        val line = errReader.readLine() ?: break
                        logBuffer.add("$prefix[ERR] ${stripAnsiCodes(line)}\n")
                    }
                    break
                }

                if (!readAny) {
                    Thread.sleep(50)
                }
            }

            if (!follow || channel.isClosed) {
                SwingUtilities.invokeLater {
                    if (isStreaming.get() && !isDisposed) {
                        statusTextLabel.text = "Stream completed for $podName."
                        statusIconLabel.icon = AllIcons.General.Information
                    }
                }
            }
        } catch (e: Exception) {
            if (!isDisposed) {
                logBuffer.add("[SSH ERROR] ${e.message}\n")
                SwingUtilities.invokeLater {
                    statusTextLabel.text = "Error: ${e.message}"
                    statusIconLabel.icon = AllIcons.General.Error
                }
            }
        } finally {
            try {
                channel?.disconnect()
            } catch (_: Exception) {}
            try {
                session?.disconnect()
            } catch (_: Exception) {}
            synchronized(activeChannels) { activeChannels.remove(channel) }
            synchronized(activeSessions) { activeSessions.remove(session) }
        }
    }

    private fun scheduleBatchFlusher() {
        ApplicationManager.getApplication().executeOnPooledThread {
            while (!isDisposed && isStreaming.get()) {
                val batch = StringBuilder()
                var count = 0
                while (count < 200) {
                    val line = logBuffer.poll() ?: break
                    batch.append(line)
                    count++
                }

                if (batch.isNotEmpty()) {
                    val linesAdded = count
                    val newText = batch.toString()
                    ApplicationManager.getApplication().invokeLater({
                        if (!isDisposed) {
                            fullTextCache.append(newText)
                            textArea.append(newText)
                            val total = lineCounter.addAndGet(linesAdded)
                            lineCountLabel.text = "$total lines"

                            // Prune buffer if too long
                            if (total > maxBufferLines + 1000) {
                                val currentText = textArea.text
                                val lines = currentText.lines()
                                if (lines.size > maxBufferLines) {
                                    val retained = lines.takeLast(maxBufferLines).joinToString("\n")
                                    textArea.text = retained
                                    fullTextCache = StringBuilder(retained)
                                    lineCounter.set(maxBufferLines)
                                }
                            }

                            if (autoScrollCheckBox.isSelected) {
                                textArea.caretPosition = textArea.document.length
                            }
                        }
                    }, ModalityState.any())
                }

                Thread.sleep(60)
            }
        }
    }

    private fun stripAnsiCodes(text: String): String {
        return text.replace(Regex("\u001B\\[[;\\d]*[ -/]*[@-~]"), "")
    }

    private fun togglePauseResume() {
        if (isPaused.get()) {
            isPaused.set(false)
            pauseResumeButton.text = "Pause"
            pauseResumeButton.icon = AllIcons.Actions.Pause
            statusTextLabel.text = "Tailing logs for $title (Streaming...)"
            statusIconLabel.icon = AllIcons.Debugger.ThreadRunning
        } else {
            isPaused.set(true)
            pauseResumeButton.text = "Resume"
            pauseResumeButton.icon = AllIcons.Actions.Resume
            statusTextLabel.text = "Log stream paused."
            statusIconLabel.icon = AllIcons.Process.Step_passive
        }
    }

    fun restart() {
        stop()
        clearOutput()
        start()
    }

    fun stop() {
        isStreaming.set(false)
        synchronized(activeChannels) {
            for (ch in activeChannels) {
                try { ch.disconnect() } catch (_: Exception) {}
            }
            activeChannels.clear()
        }
        synchronized(activeSessions) {
            for (sess in activeSessions) {
                try { sess.disconnect() } catch (_: Exception) {}
            }
            activeSessions.clear()
        }

        SwingUtilities.invokeLater {
            statusTextLabel.text = "Stream stopped."
            statusIconLabel.icon = AllIcons.Actions.Suspend
        }
    }

    fun clearOutput() {
        logBuffer.clear()
        fullTextCache.setLength(0)
        lineCounter.set(0)
        textArea.text = ""
        lineCountLabel.text = "0 lines"
    }

    private fun copyLogsToClipboard() {
        val text = textArea.text
        if (text.isNotBlank()) {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
            statusTextLabel.text = "Copied ${lineCounter.get()} lines to clipboard."
        }
    }

    private fun applyFilterOrHighlight(query: String) {
        val highlighter = textArea.highlighter
        highlighter.removeAllHighlights()

        if (query.isBlank()) return

        val text = textArea.text
        var index = 0
        val painter = DefaultHighlighter.DefaultHighlightPainter(JBColor(Color(255, 230, 100, 180), Color(100, 90, 20, 180)))

        while (index < text.length) {
            val found = text.indexOf(query, index, ignoreCase = true)
            if (found < 0) break
            highlighter.addHighlight(found, found + query.length, painter)
            index = found + query.length
        }
    }

    override fun dispose() {
        isDisposed = true
        stop()
    }
}

/**
 * Dialog for selecting multiple pods to tail simultaneously.
 */
class MultiPodSelectDialog(
    project: Project,
    private val namespace: String,
    private val pods: List<PodInfo>
) : DialogWrapper(project, true) {

    private val checkBoxes = mutableListOf<JCheckBox>()
    private val separateTabsCheckBox = JBCheckBox("Open in separate tabs for each pod", false)

    init {
        title = "Select Multiple Pods to Tail Logs ($namespace)"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            preferredSize = Dimension(JBUI.scale(420), JBUI.scale(320))
        }

        val topHint = JBLabel("Select the pods you want to stream logs from concurrently:")
        panel.add(topHint, BorderLayout.NORTH)

        val listPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4)
        }

        val selectAllBox = JBCheckBox("Select All / Deselect All", false).apply {
            font = font.deriveFont(Font.BOLD)
            addActionListener {
                val sel = isSelected
                checkBoxes.forEach { it.isSelected = sel }
            }
        }
        listPanel.add(selectAllBox)
        listPanel.add(Box.createVerticalStrut(JBUI.scale(4)))

        for (pod in pods) {
            val cb = JBCheckBox("${pod.name} (${pod.status})", false).apply {
                toolTipText = "Image: ${pod.image}"
            }
            checkBoxes.add(cb)
            listPanel.add(cb)
        }

        val scrollPane = JBScrollPane(listPanel).apply {
            border = JBUI.Borders.customLine(JBColor.border())
        }
        panel.add(scrollPane, BorderLayout.CENTER)

        val bottomOptions = JPanel(BorderLayout()).apply {
            separateTabsCheckBox.toolTipText = "When checked, each selected pod opens in its own tab. When unchecked, logs are combined with pod prefix tags."
            add(separateTabsCheckBox, BorderLayout.WEST)
        }
        panel.add(bottomOptions, BorderLayout.SOUTH)

        return panel
    }

    fun getSelectedPods(): List<String> {
        val selected = mutableListOf<String>()
        for (i in 0 until checkBoxes.size) {
            if (checkBoxes[i].isSelected && i < pods.size) {
                selected.add(pods[i].name)
            }
        }
        return selected
    }

    fun isOpenAsSeparateTabs(): Boolean = separateTabsCheckBox.isSelected
}
