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
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.kubernetes.model.PodInfo
import com.kubernetes.model.SshProfile
import com.kubernetes.settings.KubernetesSshConfigurable
import com.kubernetes.settings.KubernetesSshSettings
import com.kubernetes.ssh.KubernetesSshClient
import com.kubernetes.ui.dialogs.EditDeploymentDialog
import com.kubernetes.ui.dialogs.PodDetailsDialog
import com.kubernetes.ui.dialogs.PodTerminalDialog
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ListSelectionModel
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.table.TableRowSorter

class KubernetesToolWindowPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    var onNavigateToDockerImage: ((String) -> Unit)? = null
    var onNavigateToKubectl: ((String) -> Unit)? = null
    var onProfileChanged: ((SshProfile) -> Unit)? = null

    private val tableModel = PodTableModel()
    private val table = JBTable(tableModel)

    private val profileComboBox = ComboBox<SshProfile>()
    private val namespaceComboBox = ComboBox<String>()
    private val searchField = SearchTextField()
    private val statusLabel = JBLabel("Ready")

    private var isUpdatingProfiles = false
    private var isUpdatingNamespaces = false
    private val isFetching = AtomicBoolean(false)
    private var isDisposed = false
    private var refreshTimer: Timer? = null

    init {
        setupUI()
        refreshProfiles()
        loadInitialData()
        startAutoRefresh()
    }

    private fun setupUI() {
        border = JBUI.Borders.empty(4)

        // Top Controls Panel
        val topPanel = JPanel(BorderLayout())

        // Profile and Namespace selector panel
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
                        loadInitialData()
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
                        KubernetesSshSettings.getInstance(project).state.getActiveProfile().defaultNamespace = selectedNs
                        loadPods(selectedNs)
                    }
                }
            }
            add(namespaceComboBox)
        }

        // Search field listener
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                tableModel.setFilter(searchField.text)
                updateStatusLabel()
            }
        })

        // Action Toolbar
        val actionGroup = DefaultActionGroup().apply {
            add(object : AnAction("Refresh", "Refresh pods and namespaces", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    val ns = namespaceComboBox.selectedItem as? String
                    if (!ns.isNullOrBlank()) {
                        loadPods(ns)
                    } else {
                        loadInitialData()
                    }
                }
            })
            addSeparator()
            add(object : AnAction("Edit Deployment", "Edit Kubernetes deployment YAML, scale replicas, update images, and restart rollouts", AllIcons.Actions.Edit) {
                override fun actionPerformed(e: AnActionEvent) {
                    val pod = getSelectedPod()
                    val ns = namespaceComboBox.selectedItem as? String ?: pod?.namespace ?: "default"
                    openEditDeploymentDialog(deploymentName = pod?.deploymentName?.ifBlank { null }, namespace = ns)
                }
            })
            addSeparator()
            add(object : AnAction("Show in Docker Images", "Navigate to Docker Images tab and search for this pod's image", AllIcons.Actions.Forward) {
                override fun actionPerformed(e: AnActionEvent) {
                    navigateToDockerImageForSelectedPod()
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = table.selectedRow >= 0
                }
            })
            addSeparator()
            add(object : AnAction("Delete Pod", "Delete the selected pod", AllIcons.Actions.Cancel) {
                override fun actionPerformed(e: AnActionEvent) {
                    deleteSelectedPod(force = false)
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = table.selectedRow >= 0
                }
            })
            add(object : AnAction("Execute Terminal", "Execute interactive terminal inside the selected pod", AllIcons.Nodes.Console) {
                override fun actionPerformed(e: AnActionEvent) {
                    openSelectedPodTerminal()
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = table.selectedRow >= 0
                }
            })
            add(object : AnAction("View Pod Logs", "View logs of the selected pod", AllIcons.FileTypes.Text) {
                override fun actionPerformed(e: AnActionEvent) {
                    showSelectedPodLogs()
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = table.selectedRow >= 0
                }
            })
            add(object : AnAction("Describe Pod", "Describe the selected pod", AllIcons.Actions.PreviewDetails) {
                override fun actionPerformed(e: AnActionEvent) {
                    showSelectedPodDescribe()
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = table.selectedRow >= 0
                }
            })
            addSeparator()
            add(object : AnAction("Settings", "Open Kubernetes SSH Settings", AllIcons.General.Settings) {
                override fun actionPerformed(e: AnActionEvent) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, KubernetesSshConfigurable::class.java)
                    refreshProfiles()
                    val active = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
                    onProfileChanged?.invoke(active)
                    loadInitialData()
                }
            })
        }

        val toolbar: ActionToolbar = ActionManager.getInstance().createActionToolbar(
            ActionPlaces.TOOLBAR,
            actionGroup,
            true
        )
        toolbar.targetComponent = this

        val controlsAndSearch = JPanel(BorderLayout()).apply {
            add(selectorsPanel, BorderLayout.WEST)
            add(searchField, BorderLayout.CENTER)
            add(toolbar.component, BorderLayout.EAST)
        }

        topPanel.add(controlsAndSearch, BorderLayout.CENTER)
        add(topPanel, BorderLayout.NORTH)

        // Center Table Setup
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.rowSorter = TableRowSorter(tableModel)
        table.setDefaultRenderer(Any::class.java, PodStatusCellRenderer())
        table.tableHeader.reorderingAllowed = false

        // Column widths
        table.columnModel.getColumn(0).preferredWidth = 200 // Name
        table.columnModel.getColumn(1).preferredWidth = 100 // Status
        table.columnModel.getColumn(2).preferredWidth = 60  // Ready
        table.columnModel.getColumn(3).preferredWidth = 70  // Restarts
        table.columnModel.getColumn(4).preferredWidth = 70  // Age
        table.columnModel.getColumn(5).preferredWidth = 220 // Image
        table.columnModel.getColumn(6).preferredWidth = 120 // Image ID
        table.columnModel.getColumn(7).preferredWidth = 120 // Node
        table.columnModel.getColumn(8).preferredWidth = 110 // IP

        // Double-click listener for pod details
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && table.selectedRow >= 0) {
                    showSelectedPodDetails()
                }
            }

            override fun mousePressed(e: MouseEvent) {
                maybeShowPopup(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                maybeShowPopup(e)
            }
        })

        val scrollPane = JBScrollPane(table)
        add(scrollPane, BorderLayout.CENTER)

        // Bottom Status Bar
        val bottomPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 6)
            add(statusLabel, BorderLayout.WEST)
        }
        add(bottomPanel, BorderLayout.SOUTH)
    }

    private fun maybeShowPopup(e: MouseEvent) {
        if (e.isPopupTrigger) {
            val row = table.rowAtPoint(e.point)
            if (row >= 0 && !table.isRowSelected(row)) {
                table.setRowSelectionInterval(row, row)
            }
            if (table.selectedRow >= 0) {
                val menu = JPopupMenu()
                menu.add(JMenuItem("Show in Docker Images").apply {
                    icon = AllIcons.Actions.Forward
                    addActionListener { navigateToDockerImageForSelectedPod() }
                })
                menu.addSeparator()
                menu.add(JMenuItem("Delete Pod").apply {
                    icon = AllIcons.Actions.Cancel
                    addActionListener { deleteSelectedPod(force = false) }
                })
                menu.add(JMenuItem("Force Delete Pod").apply {
                    icon = AllIcons.Actions.ForceRefresh
                    addActionListener { deleteSelectedPod(force = true) }
                })
                menu.addSeparator()
                menu.add(JMenuItem("Execute Terminal...").apply {
                    icon = AllIcons.Nodes.Console
                    addActionListener { openSelectedPodTerminal() }
                })
                menu.add(JMenuItem("Edit Deployment...").apply {
                    icon = AllIcons.Actions.Edit
                    addActionListener {
                        val pod = getSelectedPod()
                        val ns = namespaceComboBox.selectedItem as? String ?: pod?.namespace ?: "default"
                        openEditDeploymentDialog(deploymentName = pod?.deploymentName?.ifBlank { null }, namespace = ns)
                    }
                })
                menu.add(JMenuItem("View Logs").apply {
                    icon = AllIcons.FileTypes.Text
                    addActionListener { showSelectedPodLogs() }
                })
                menu.add(JMenuItem("Describe Pod").apply {
                    icon = AllIcons.Actions.PreviewDetails
                    addActionListener { showSelectedPodDescribe() }
                })
                menu.add(JMenuItem("Pod Details...").apply {
                    icon = AllIcons.Actions.Show
                    addActionListener { showSelectedPodDetails() }
                })
                menu.add(JMenuItem("Open in Kubectl Console").apply {
                    icon = AllIcons.Nodes.Console
                    addActionListener {
                        getSelectedPod()?.let { pod ->
                            onNavigateToKubectl?.invoke("describe pod ${pod.name} -n ${pod.namespace}")
                        }
                    }
                })
                menu.addSeparator()
                menu.add(JMenuItem("Copy Pod Name").apply {
                    icon = AllIcons.Actions.Copy
                    addActionListener {
                        getSelectedPod()?.let { pod ->
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(pod.name), null)
                        }
                    }
                })
                menu.add(JMenuItem("Copy Image").apply {
                    icon = AllIcons.Actions.Copy
                    addActionListener {
                        getSelectedPod()?.let { pod ->
                            val textToCopy = if (pod.image.isNotBlank()) pod.image else pod.images.joinToString(",")
                            if (textToCopy.isNotBlank()) {
                                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(textToCopy), null)
                            }
                        }
                    }
                })
                menu.add(JMenuItem("Copy Image ID").apply {
                    icon = AllIcons.Actions.Copy
                    addActionListener {
                        getSelectedPod()?.let { pod ->
                            val textToCopy = if (pod.imageId.isNotBlank()) pod.imageId else pod.imageIds.joinToString(",")
                            if (textToCopy.isNotBlank()) {
                                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(textToCopy), null)
                            }
                        }
                    }
                })
                menu.add(JMenuItem("Copy Pod IP").apply {
                    icon = AllIcons.Actions.Copy
                    addActionListener {
                        getSelectedPod()?.let { pod ->
                            if (pod.ip.isNotBlank()) {
                                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(pod.ip), null)
                            }
                        }
                    }
                })
                menu.show(e.component, e.x, e.y)
            }
        }
    }

    private fun getSelectedPod(): PodInfo? {
        val selectedRow = table.selectedRow
        if (selectedRow < 0) return null
        val modelRow = table.convertRowIndexToModel(selectedRow)
        return tableModel.getPodAt(modelRow)
    }

    fun navigateToDockerImageForSelectedPod() {
        val pod = getSelectedPod() ?: return
        val query = if (pod.imageId.isNotBlank()) {
            pod.imageId
        } else if (pod.image.isNotBlank()) {
            pod.image
        } else {
            ""
        }
        if (query.isNotBlank()) {
            onNavigateToDockerImage?.invoke(query)
        } else {
            Messages.showInfoMessage(project, "No Docker image information available for pod '${pod.name}'.", "No Image Information")
        }
    }

    fun syncProfile(profile: SshProfile) {
        refreshProfiles(selectedProfileId = profile.id)
        loadInitialData()
    }

    fun refreshProfiles(selectedProfileId: String? = null) {
        isUpdatingProfiles = true
        try {
            val state = KubernetesSshSettings.getInstance(project).state
            state.ensureProfilesInitialized()
            profileComboBox.removeAllItems()
            state.profiles.forEach { profileComboBox.addItem(it) }

            val targetId = selectedProfileId ?: state.activeProfileId
            val targetProfile = state.profiles.find { it.id == targetId } ?: state.profiles.firstOrNull()
            if (targetProfile != null) {
                profileComboBox.selectedItem = targetProfile
                state.selectProfile(targetProfile.id)
            }
        } finally {
            isUpdatingProfiles = false
        }
    }

    fun loadInitialData() {
        val state = KubernetesSshSettings.getInstance(project).state
        val profile = state.getActiveProfile()
        if (profile.host.isBlank()) {
            tableModel.setPods(emptyList())
            statusLabel.text = "SSH connection is not configured for profile '${profile.name}'. Click Settings to configure."
            return
        }
        loadNamespaces()
    }

    fun loadNamespaces() {
        val state = KubernetesSshSettings.getInstance(project).state
        val profile = state.getActiveProfile()
        if (profile.host.isBlank()) {
            tableModel.setPods(emptyList())
            statusLabel.text = "SSH Host is not configured in profile '${profile.name}'."
            return
        }

        statusLabel.text = "[${profile.name}] Connecting to ${profile.host}..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Fetching Kubernetes Namespaces", false) {
            private var namespaces: List<String> = emptyList()
            private var errorMessage: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Connecting via SSH to ${profile.host}..."
                try {
                    val client = KubernetesSshClient(profile)
                    namespaces = client.getNamespaces()
                } catch (e: Exception) {
                    errorMessage = e.message ?: "Unknown error"
                }
            }

            override fun onSuccess() {
                if (errorMessage != null) {
                    statusLabel.text = "Error fetching namespaces: $errorMessage"
                } else {
                    isUpdatingNamespaces = true
                    namespaceComboBox.removeAllItems()
                    for (ns in namespaces) {
                        namespaceComboBox.addItem(ns)
                    }

                    val targetNs = if (namespaces.contains(profile.defaultNamespace)) {
                        profile.defaultNamespace
                    } else if (namespaces.contains("default")) {
                        "default"
                    } else {
                        namespaces.firstOrNull() ?: "default"
                    }

                    namespaceComboBox.selectedItem = targetNs
                    isUpdatingNamespaces = false

                    loadPods(targetNs)
                }
            }
        })
    }

    private fun startAutoRefresh() {
        refreshTimer = Timer(5000) {
            if (isDisposed || project.isDisposed) {
                refreshTimer?.stop()
                return@Timer
            }
            val ns = namespaceComboBox.selectedItem as? String
            if (!ns.isNullOrBlank() && !isUpdatingNamespaces && !isUpdatingProfiles) {
                autoRefreshPods(ns)
            }
        }.apply {
            isRepeats = true
            start()
        }
    }

    fun autoRefreshPods(namespace: String) {
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        if (profile.host.isBlank() || !isFetching.compareAndSet(false, true)) {
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = KubernetesSshClient(profile)
                val pods = client.getPods(namespace)
                ApplicationManager.getApplication().invokeLater {
                    if (!isDisposed && !project.isDisposed) {
                        val selectedPodName = getSelectedPod()?.name
                        tableModel.setPods(pods)
                        restorePodSelection(selectedPodName)
                        updateStatusLabel()
                    }
                }
            } catch (_: Exception) {
                // Background refresh silent on transient connection glitches
            } finally {
                isFetching.set(false)
            }
        }
    }

    private fun restorePodSelection(podName: String?) {
        if (podName == null) return
        for (i in 0 until tableModel.rowCount) {
            val pod = tableModel.getPodAt(i)
            if (pod?.name == podName) {
                val viewRow = table.convertRowIndexToView(i)
                if (viewRow >= 0 && viewRow < table.rowCount) {
                    table.setRowSelectionInterval(viewRow, viewRow)
                }
                break
            }
        }
    }

    fun loadPods(namespace: String) {
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        if (profile.host.isBlank()) {
            statusLabel.text = "SSH Host is not configured in profile '${profile.name}'."
            return
        }

        statusLabel.text = "[${profile.name}] Loading pods in namespace '$namespace'..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading Pods in $namespace", false) {
            private var pods: List<PodInfo> = emptyList()
            private var errorMessage: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Running kubectl get pods -n $namespace..."
                try {
                    val client = KubernetesSshClient(profile)
                    pods = client.getPods(namespace)
                } catch (e: Exception) {
                    errorMessage = e.message ?: "Unknown error"
                }
            }

            override fun onSuccess() {
                if (errorMessage != null) {
                    statusLabel.text = "Error fetching pods: $errorMessage"
                    tableModel.setPods(emptyList())
                } else {
                    val selectedPodName = getSelectedPod()?.name
                    tableModel.setPods(pods)
                    restorePodSelection(selectedPodName)
                    updateStatusLabel()
                }
            }
        })
    }

    private fun updateStatusLabel() {
        val count = tableModel.rowCount
        val total = tableModel.getTotalCount()
        val ns = namespaceComboBox.selectedItem as? String ?: ""
        val profileName = (profileComboBox.selectedItem as? SshProfile)?.name ?: "Default"
        val filter = searchField.text.trim()
        val filterInfo = if (filter.isNotBlank()) " (filtered from $total)" else ""
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        statusLabel.text = "Profile: $profileName | Namespace: $ns | $count pods$filterInfo | Last updated: $time"
    }

    private fun deleteSelectedPod(force: Boolean) {
        val pod = getSelectedPod() ?: return
        val currentNamespace = namespaceComboBox.selectedItem as? String ?: pod.namespace
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()

        val warningMsg = if (force) {
            "Are you sure you want to FORCE delete pod '${pod.name}' in namespace '$currentNamespace'?\nThis immediately terminates the pod without graceful shutdown."
        } else {
            "Are you sure you want to delete pod '${pod.name}' in namespace '$currentNamespace'?"
        }

        val title = if (force) "Force Delete Pod" else "Delete Pod"
        val confirm = Messages.showYesNoDialog(
            project,
            warningMsg,
            title,
            Messages.getWarningIcon()
        )

        if (confirm != Messages.YES) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Deleting Pod ${pod.name}", false) {
            private var deleteSuccess = false
            private var errorOutput = ""

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Executing kubectl delete pod ${pod.name}..."
                val client = KubernetesSshClient(profile)
                val cmdResult = client.deletePod(currentNamespace, pod.name, force)
                deleteSuccess = cmdResult.isSuccess
                errorOutput = cmdResult.error.ifBlank { cmdResult.output }
            }

            override fun onSuccess() {
                if (deleteSuccess) {
                    statusLabel.text = "Pod '${pod.name}' deleted successfully."
                    loadPods(currentNamespace)
                } else {
                    Messages.showErrorDialog(
                        project,
                        "Failed to delete pod '${pod.name}':\n$errorOutput",
                        "Delete Pod Failed"
                    )
                }
            }
        })
    }

    private fun openSelectedPodTerminal() {
        val pod = getSelectedPod() ?: return
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        val dialog = PodTerminalDialog(project, profile, pod)
        dialog.show()
    }

    private fun showSelectedPodLogs() {
        val pod = getSelectedPod() ?: return
        val currentNamespace = namespaceComboBox.selectedItem as? String ?: pod.namespace
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Fetching Pod Logs", false) {
            private var logs = ""
            private var isSuccess = false

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Fetching logs for ${pod.name}..."
                val client = KubernetesSshClient(profile)
                val cmdResult = client.getPodLogs(currentNamespace, pod.name)
                isSuccess = cmdResult.isSuccess
                logs = if (isSuccess) cmdResult.output else cmdResult.error.ifBlank { cmdResult.output }
            }

            override fun onSuccess() {
                val dialog = PodDetailsDialog(
                    project,
                    titleText = "Logs for Pod: ${pod.name} ($currentNamespace)",
                    contentText = logs.ifBlank { "No logs available or empty output." }
                )
                dialog.show()
            }
        })
    }

    private fun showSelectedPodDescribe() {
        val pod = getSelectedPod() ?: return
        val currentNamespace = namespaceComboBox.selectedItem as? String ?: pod.namespace
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Describing Pod", false) {
            private var description = ""
            private var isSuccess = false

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Describing pod ${pod.name}..."
                val client = KubernetesSshClient(profile)
                val cmdResult = client.describePod(currentNamespace, pod.name)
                isSuccess = cmdResult.isSuccess
                description = if (isSuccess) cmdResult.output else cmdResult.error.ifBlank { cmdResult.output }
            }

            override fun onSuccess() {
                val dialog = PodDetailsDialog(
                    project,
                    titleText = "Describe Pod: ${pod.name} ($currentNamespace)",
                    contentText = description.ifBlank { "No describe output returned." },
                    onEditDeployment = {
                        openEditDeploymentDialog(deploymentName = pod.deploymentName.ifBlank { null }, namespace = currentNamespace)
                    }
                )
                dialog.show()
            }
        })
    }

    private fun showSelectedPodDetails() {
        val pod = getSelectedPod() ?: return
        val details = buildString {
            appendLine("Name:          ${pod.name}")
            appendLine("Namespace:     ${pod.namespace}")
            if (pod.deploymentName.isNotBlank()) {
                appendLine("Deployment:    ${pod.deploymentName}")
            }
            if (pod.ownerKind.isNotBlank() && pod.ownerName.isNotBlank()) {
                appendLine("Controlled By: ${pod.ownerKind}/${pod.ownerName}")
            }
            appendLine("Status:        ${pod.status}")
            appendLine("Ready:         ${pod.ready}")
            appendLine("Restarts:      ${pod.restarts}")
            appendLine("Age:           ${pod.age}")
            appendLine("Image:         ${pod.image}")
            if (pod.images.size > 1) {
                appendLine("All Images:    ${pod.images.joinToString(", ")}")
            }
            appendLine("Image ID:      ${pod.imageId}")
            if (pod.imageIds.size > 1) {
                appendLine("All Image IDs: ${pod.imageIds.joinToString(", ")}")
            }
            appendLine("Node:          ${pod.node}")
            appendLine("IP:            ${pod.ip}")
            appendLine("Raw Phase:     ${pod.rawPhase}")
        }
        val dialog = PodDetailsDialog(
            project,
            titleText = "Pod Info: ${pod.name}",
            contentText = details,
            onEditDeployment = {
                openEditDeploymentDialog(deploymentName = pod.deploymentName.ifBlank { null }, namespace = pod.namespace)
            }
        )
        dialog.show()
    }

    fun openEditDeploymentDialog(deploymentName: String? = null, namespace: String? = null) {
        val targetNs = if (!namespace.isNullOrBlank() && namespace != "All Namespaces") {
            namespace
        } else {
            val selected = namespaceComboBox.selectedItem as? String
            if (!selected.isNullOrBlank() && selected != "All Namespaces") selected else "default"
        }
        val dialog = EditDeploymentDialog(project, targetNs, deploymentName) {
            val currentNs = namespaceComboBox.selectedItem as? String
            if (!currentNs.isNullOrBlank()) {
                loadPods(currentNs)
            }
        }
        dialog.show()
    }

    override fun dispose() {
        isDisposed = true
        refreshTimer?.stop()
        refreshTimer = null
    }
}
