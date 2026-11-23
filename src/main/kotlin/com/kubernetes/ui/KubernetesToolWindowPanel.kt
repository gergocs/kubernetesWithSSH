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
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.kubernetes.cache.KubernetesDataCache
import com.kubernetes.cache.KubernetesPrefetchService
import com.kubernetes.model.ConfigMapInfo
import com.kubernetes.model.CronJobInfo
import com.kubernetes.model.DaemonSetInfo
import com.kubernetes.model.DeploymentInfo
import com.kubernetes.model.EventInfo
import com.kubernetes.model.IngressInfo
import com.kubernetes.model.JobInfo
import com.kubernetes.model.PersistentVolumeClaimInfo
import com.kubernetes.model.PodInfo
import com.kubernetes.model.ResourceType
import com.kubernetes.model.SecretInfo
import com.kubernetes.model.ServiceInfo
import com.kubernetes.model.StatefulSetInfo
import com.kubernetes.model.SshProfile
import com.kubernetes.settings.KubernetesSshConfigurable
import com.kubernetes.settings.KubernetesSshSettings
import com.kubernetes.ssh.KubernetesSshClient
import com.kubernetes.terminal.TerminalLauncher
import com.kubernetes.ui.dialogs.ConfigMapDetailsDialog
import com.kubernetes.ui.dialogs.EditDeploymentDialog
import com.kubernetes.ui.dialogs.PodDetailsDialog
import com.kubernetes.ui.dialogs.ResourceYamlDialog
import com.kubernetes.ui.dialogs.SecretDetailsDialog
import com.kubernetes.ui.dialogs.SelectContainerDialog
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JMenu
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
    var onNavigateToTailLogs: ((SshProfile, String, List<String>, String?, Boolean, Int, Boolean, Boolean) -> Unit)? = null
    var onProfileChanged: ((SshProfile) -> Unit)? = null

    private val tableModel = PodTableModel()
    private val table = JBTable(tableModel)

    private val profileComboBox = ComboBox<SshProfile>()
    private val namespaceComboBox = ComboBox<String>()
    private val resourceComboBox = ComboBox<ResourceType>()
    private val searchField = SearchTextField()
    private val statusLabel = JBLabel("Ready")
    private val summaryLabel = JBLabel("")

    private var isUpdatingProfiles = false
    private var isUpdatingNamespaces = false
    private var isUpdatingResources = false
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
        border = JBUI.Borders.empty(2)

        // Top Controls Panel (Two-tier header for clean organization)
        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        // Action Toolbar
        val actionGroup = DefaultActionGroup().apply {
            add(object : AnAction("Refresh", "Refresh resources and namespaces", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    val ns = namespaceComboBox.selectedItem as? String
                    val resType = resourceComboBox.selectedItem as? ResourceType ?: ResourceType.PODS
                    if (!ns.isNullOrBlank()) {
                        loadResources(resType, ns)
                    } else {
                        loadInitialData()
                    }
                }
            })
            addSeparator()
            add(object : AnAction("Describe Resource", "Describe the selected resource in detail", AllIcons.Actions.PreviewDetails) {
                override fun actionPerformed(e: AnActionEvent) {
                    describeSelectedResource()
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = table.selectedRow >= 0
                }
            })
            add(object : AnAction("View / Edit YAML", "View or edit YAML of the selected resource with apply/dry-run", AllIcons.FileTypes.Yaml) {
                override fun actionPerformed(e: AnActionEvent) {
                    showSelectedResourceYamlOrDetails()
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = table.selectedRow >= 0
                }
            })
            add(object : AnAction("Edit Deployment", "Edit Kubernetes deployment YAML, scale replicas, update images, and restart rollouts", AllIcons.Actions.Edit) {
                override fun actionPerformed(e: AnActionEvent) {
                    val resType = tableModel.getResourceType()
                    val ns = namespaceComboBox.selectedItem as? String ?: "default"
                    val depName = if (resType == ResourceType.DEPLOYMENTS) {
                        getSelectedDeployment()?.name
                    } else {
                        getSelectedPod()?.deploymentName?.ifBlank { null }
                    }
                    openEditDeploymentDialog(deploymentName = depName, namespace = ns)
                }
            })
            addSeparator()
            add(object : AnAction("Execute Terminal", "Execute interactive terminal inside the selected pod in built-in terminal", AllIcons.Debugger.Console) {
                override fun actionPerformed(e: AnActionEvent) {
                    openSelectedPodTerminal()
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = tableModel.getResourceType() == ResourceType.PODS && table.selectedRow >= 0
                }
            })
            add(object : AnAction("Tail Logs", "Tail live logs of the selected pod(s) or workload in the Tail Logs tab", AllIcons.Debugger.Console) {
                override fun actionPerformed(e: AnActionEvent) {
                    tailSelectedLogs()
                }

                override fun update(e: AnActionEvent) {
                    val resType = tableModel.getResourceType()
                    e.presentation.isEnabled = (resType == ResourceType.PODS || resType == ResourceType.DEPLOYMENTS || resType == ResourceType.JOBS || resType == ResourceType.STATEFUL_SETS || resType == ResourceType.DAEMON_SETS) && table.selectedRow >= 0
                }
            })
            add(object : AnAction("View Logs (Snapshot)", "View snapshot logs of the selected pod or deployment in a dialog", AllIcons.FileTypes.Text) {
                override fun actionPerformed(e: AnActionEvent) {
                    showSelectedLogs()
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = (tableModel.getResourceType() == ResourceType.PODS || tableModel.getResourceType() == ResourceType.DEPLOYMENTS) && table.selectedRow >= 0
                }
            })
            add(object : AnAction("Show in Docker Images", "Navigate to Docker Images tab and search for this resource's image", AllIcons.Actions.Forward) {
                override fun actionPerformed(e: AnActionEvent) {
                    navigateToDockerImageForSelectedResource()
                }

                override fun update(e: AnActionEvent) {
                    val resType = tableModel.getResourceType()
                    e.presentation.isEnabled = (resType == ResourceType.PODS || resType == ResourceType.DEPLOYMENTS) && table.selectedRow >= 0
                }
            })
            addSeparator()
            add(object : AnAction("Delete Resource", "Delete the selected Kubernetes resource", AllIcons.Actions.Cancel) {
                override fun actionPerformed(e: AnActionEvent) {
                    deleteSelectedResource(force = false)
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

        // Row 1: Action Toolbar on the left, Search field with proper size on the right
        searchField.textEditor.emptyText.text = "Search resources..."
        searchField.preferredSize = Dimension(JBUI.scale(220), searchField.preferredSize.height)
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                tableModel.setFilter(searchField.text)
                updateStatusLabel()
            }
        })

        val toolbarAndSearchPanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(2, 4)
            add(toolbar.component, BorderLayout.WEST)
            val searchWrapper = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                add(searchField)
            }
            add(searchWrapper, BorderLayout.EAST)
        }

        // Row 2: Selectors (Resource, Profile, Namespace) with custom icons and styled labels
        resourceComboBox.toolTipText = "Select Kubernetes Resource Type"
        resourceComboBox.renderer = object : SimpleListCellRenderer<ResourceType>() {
            override fun customize(list: javax.swing.JList<out ResourceType>, value: ResourceType?, index: Int, selected: Boolean, hasFocus: Boolean) {
                if (value != null) {
                    text = value.displayName
                    icon = when (value) {
                        ResourceType.PODS -> AllIcons.Nodes.Deploy
                        ResourceType.SERVICES -> AllIcons.Nodes.Services
                        ResourceType.INGRESSES -> AllIcons.Nodes.WebFolder
                        ResourceType.CONFIG_MAPS -> AllIcons.Nodes.ConfigFolder
                        ResourceType.SECRETS -> AllIcons.Nodes.Padlock
                        ResourceType.DEPLOYMENTS -> AllIcons.Nodes.Favorite
                        ResourceType.STATEFUL_SETS -> AllIcons.Nodes.Deploy
                        ResourceType.DAEMON_SETS -> AllIcons.Nodes.Deploy
                        ResourceType.JOBS -> AllIcons.Actions.Execute
                        ResourceType.CRON_JOBS -> AllIcons.Vcs.History
                        ResourceType.PERSISTENT_VOLUME_CLAIMS -> AllIcons.Nodes.Folder
                        ResourceType.EVENTS -> AllIcons.General.Warning
                    }
                }
            }
        }
        ResourceType.values().forEach { resourceComboBox.addItem(it) }
        resourceComboBox.selectedItem = ResourceType.PODS
        resourceComboBox.addActionListener {
            if (!isUpdatingResources) {
                val selectedType = resourceComboBox.selectedItem as? ResourceType ?: ResourceType.PODS
                tableModel.setResourceType(selectedType)
                updateColumnWidths()
                val ns = namespaceComboBox.selectedItem as? String ?: "default"
                loadResources(selectedType, ns)
            }
        }

        profileComboBox.toolTipText = "Select SSH Profile"
        profileComboBox.renderer = object : SimpleListCellRenderer<SshProfile>() {
            override fun customize(list: javax.swing.JList<out SshProfile>, value: SshProfile?, index: Int, selected: Boolean, hasFocus: Boolean) {
                if (value != null) {
                    text = value.name.ifBlank { value.host }
                    icon = AllIcons.General.Web
                }
            }
        }
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

        namespaceComboBox.toolTipText = "Select Kubernetes Namespace"
        namespaceComboBox.renderer = object : SimpleListCellRenderer<String>() {
            override fun customize(list: javax.swing.JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
                if (value != null) {
                    text = value
                    icon = AllIcons.Nodes.Folder
                }
            }
        }
        namespaceComboBox.prototypeDisplayValue = "kube-system-long-namespace"
        namespaceComboBox.addActionListener {
            if (!isUpdatingNamespaces) {
                val selectedNs = namespaceComboBox.selectedItem as? String
                if (!selectedNs.isNullOrBlank()) {
                    KubernetesSshSettings.getInstance(project).state.getActiveProfile().defaultNamespace = selectedNs
                    val resType = resourceComboBox.selectedItem as? ResourceType ?: ResourceType.PODS
                    loadResources(resType, selectedNs)
                }
            }
        }

        val selectorsPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(3, 4, 5, 4)
            )

            val leftSelectors = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                add(JBLabel("Type:").apply { font = font.deriveFont(Font.BOLD) })
                add(resourceComboBox)
                add(Box.createHorizontalStrut(JBUI.scale(4)))
                add(JBLabel("Profile:").apply { font = font.deriveFont(Font.BOLD) })
                add(profileComboBox)
                add(Box.createHorizontalStrut(JBUI.scale(4)))
                add(JBLabel("Namespace:").apply { font = font.deriveFont(Font.BOLD) })
                add(namespaceComboBox)
            }
            add(leftSelectors, BorderLayout.WEST)

            val rightSummary = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                summaryLabel.foreground = JBColor.GRAY
                add(summaryLabel)
            }
            add(rightSummary, BorderLayout.EAST)
        }

        topPanel.add(toolbarAndSearchPanel)
        topPanel.add(selectorsPanel)
        add(topPanel, BorderLayout.NORTH)

        // Center Table Setup
        table.apply {
            setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
            rowSorter = TableRowSorter(tableModel)
            setDefaultRenderer(Any::class.java, PodStatusCellRenderer())
            tableHeader.reorderingAllowed = false
            rowHeight = JBUI.scale(24)
            setShowGrid(false)
            setShowHorizontalLines(true)
            gridColor = JBColor(Color(235, 235, 235), Color(50, 50, 50))
            emptyText.text = "No Kubernetes resources found in namespace."
        }

        updateColumnWidths()

        // Double-click listener for resource details
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && table.selectedRow >= 0) {
                    showSelectedResourceDetails()
                }
            }

            override fun mousePressed(e: MouseEvent) {
                maybeShowPopup(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                maybeShowPopup(e)
            }
        })

        // Proactive background prefetch on resource selection
        table.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting && table.selectedRow >= 0) {
                val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
                val ns = namespaceComboBox.selectedItem as? String ?: "default"
                val resType = tableModel.getResourceType()
                val resName = getSelectedResourceName()
                if (resName != null) {
                    KubernetesPrefetchService.prefetchResourceYaml(profile, resType.singularName.lowercase(), ns, resName)
                    KubernetesPrefetchService.prefetchResourceDescribe(profile, resType.singularName.lowercase(), ns, resName)
                }
            }
        }

        val scrollPane = JBScrollPane(table)
        add(scrollPane, BorderLayout.CENTER)

        // Bottom Status Bar
        val bottomPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                JBUI.Borders.empty(4, 8)
            )
            add(statusLabel, BorderLayout.WEST)
        }
        add(bottomPanel, BorderLayout.SOUTH)
    }

    private fun updateColumnWidths() {
        when (tableModel.getResourceType()) {
            ResourceType.PODS -> {
                if (table.columnModel.columnCount >= 9) {
                    table.columnModel.getColumn(0).preferredWidth = 200 // Name
                    table.columnModel.getColumn(1).preferredWidth = 100 // Status
                    table.columnModel.getColumn(2).preferredWidth = 60  // Ready
                    table.columnModel.getColumn(3).preferredWidth = 70  // Restarts
                    table.columnModel.getColumn(4).preferredWidth = 70  // Age
                    table.columnModel.getColumn(5).preferredWidth = 220 // Image
                    table.columnModel.getColumn(6).preferredWidth = 120 // Image ID
                    table.columnModel.getColumn(7).preferredWidth = 120 // Node
                    table.columnModel.getColumn(8).preferredWidth = 110 // IP
                }
            }
            ResourceType.SERVICES -> {
                if (table.columnModel.columnCount >= 7) {
                    table.columnModel.getColumn(0).preferredWidth = 200 // Name
                    table.columnModel.getColumn(1).preferredWidth = 110 // Type
                    table.columnModel.getColumn(2).preferredWidth = 130 // Cluster IP
                    table.columnModel.getColumn(3).preferredWidth = 130 // External IP
                    table.columnModel.getColumn(4).preferredWidth = 160 // Ports
                    table.columnModel.getColumn(5).preferredWidth = 70  // Age
                    table.columnModel.getColumn(6).preferredWidth = 180 // Selector
                }
            }
            ResourceType.INGRESSES -> {
                if (table.columnModel.columnCount >= 6) {
                    table.columnModel.getColumn(0).preferredWidth = 200 // Name
                    table.columnModel.getColumn(1).preferredWidth = 110 // Class
                    table.columnModel.getColumn(2).preferredWidth = 220 // Hosts
                    table.columnModel.getColumn(3).preferredWidth = 150 // Address
                    table.columnModel.getColumn(4).preferredWidth = 100 // Ports
                    table.columnModel.getColumn(5).preferredWidth = 70  // Age
                }
            }
            ResourceType.CONFIG_MAPS -> {
                if (table.columnModel.columnCount >= 4) {
                    table.columnModel.getColumn(0).preferredWidth = 240 // Name
                    table.columnModel.getColumn(1).preferredWidth = 360 // Data Keys
                    table.columnModel.getColumn(2).preferredWidth = 80  // Count
                    table.columnModel.getColumn(3).preferredWidth = 80  // Age
                }
            }
            ResourceType.SECRETS -> {
                if (table.columnModel.columnCount >= 5) {
                    table.columnModel.getColumn(0).preferredWidth = 220 // Name
                    table.columnModel.getColumn(1).preferredWidth = 140 // Type
                    table.columnModel.getColumn(2).preferredWidth = 300 // Data Keys
                    table.columnModel.getColumn(3).preferredWidth = 80  // Count
                    table.columnModel.getColumn(4).preferredWidth = 80  // Age
                }
            }
            ResourceType.DEPLOYMENTS -> {
                if (table.columnModel.columnCount >= 7) {
                    table.columnModel.getColumn(0).preferredWidth = 200 // Name
                    table.columnModel.getColumn(1).preferredWidth = 80  // Ready
                    table.columnModel.getColumn(2).preferredWidth = 90  // Up-to-date
                    table.columnModel.getColumn(3).preferredWidth = 90  // Available
                    table.columnModel.getColumn(4).preferredWidth = 70  // Age
                    table.columnModel.getColumn(5).preferredWidth = 240 // Images
                    table.columnModel.getColumn(6).preferredWidth = 160 // Containers
                }
            }
            ResourceType.STATEFUL_SETS -> {
                if (table.columnModel.columnCount >= 5) {
                    table.columnModel.getColumn(0).preferredWidth = 220 // Name
                    table.columnModel.getColumn(1).preferredWidth = 90  // Ready
                    table.columnModel.getColumn(2).preferredWidth = 70  // Age
                    table.columnModel.getColumn(3).preferredWidth = 180 // Containers
                    table.columnModel.getColumn(4).preferredWidth = 240 // Images
                }
            }
            ResourceType.DAEMON_SETS -> {
                if (table.columnModel.columnCount >= 8) {
                    table.columnModel.getColumn(0).preferredWidth = 200 // Name
                    table.columnModel.getColumn(1).preferredWidth = 70  // Desired
                    table.columnModel.getColumn(2).preferredWidth = 70  // Current
                    table.columnModel.getColumn(3).preferredWidth = 70  // Ready
                    table.columnModel.getColumn(4).preferredWidth = 90  // Up-to-date
                    table.columnModel.getColumn(5).preferredWidth = 80  // Available
                    table.columnModel.getColumn(6).preferredWidth = 150 // Node Selector
                    table.columnModel.getColumn(7).preferredWidth = 70  // Age
                }
            }
            ResourceType.JOBS -> {
                if (table.columnModel.columnCount >= 6) {
                    table.columnModel.getColumn(0).preferredWidth = 220 // Name
                    table.columnModel.getColumn(1).preferredWidth = 110 // Completions
                    table.columnModel.getColumn(2).preferredWidth = 100 // Duration
                    table.columnModel.getColumn(3).preferredWidth = 70  // Age
                    table.columnModel.getColumn(4).preferredWidth = 180 // Containers
                    table.columnModel.getColumn(5).preferredWidth = 220 // Images
                }
            }
            ResourceType.CRON_JOBS -> {
                if (table.columnModel.columnCount >= 7) {
                    table.columnModel.getColumn(0).preferredWidth = 200 // Name
                    table.columnModel.getColumn(1).preferredWidth = 120 // Schedule
                    table.columnModel.getColumn(2).preferredWidth = 80  // Suspend
                    table.columnModel.getColumn(3).preferredWidth = 70  // Active
                    table.columnModel.getColumn(4).preferredWidth = 110 // Last Schedule
                    table.columnModel.getColumn(5).preferredWidth = 70  // Age
                    table.columnModel.getColumn(6).preferredWidth = 220 // Images
                }
            }
            ResourceType.PERSISTENT_VOLUME_CLAIMS -> {
                if (table.columnModel.columnCount >= 7) {
                    table.columnModel.getColumn(0).preferredWidth = 200 // Name
                    table.columnModel.getColumn(1).preferredWidth = 90  // Status
                    table.columnModel.getColumn(2).preferredWidth = 180 // Volume
                    table.columnModel.getColumn(3).preferredWidth = 90  // Capacity
                    table.columnModel.getColumn(4).preferredWidth = 110 // Access Modes
                    table.columnModel.getColumn(5).preferredWidth = 140 // StorageClass
                    table.columnModel.getColumn(6).preferredWidth = 70  // Age
                }
            }
            ResourceType.EVENTS -> {
                if (table.columnModel.columnCount >= 7) {
                    table.columnModel.getColumn(0).preferredWidth = 90  // Type
                    table.columnModel.getColumn(1).preferredWidth = 140 // Reason
                    table.columnModel.getColumn(2).preferredWidth = 180 // Object
                    table.columnModel.getColumn(3).preferredWidth = 350 // Message
                    table.columnModel.getColumn(4).preferredWidth = 60  // Count
                    table.columnModel.getColumn(5).preferredWidth = 70  // Age
                    table.columnModel.getColumn(6).preferredWidth = 130 // Source
                }
            }
        }
    }

    private fun maybeShowPopup(e: MouseEvent) {
        if (e.isPopupTrigger) {
            val row = table.rowAtPoint(e.point)
            if (row >= 0 && !table.isRowSelected(row)) {
                table.setRowSelectionInterval(row, row)
            }
            if (table.selectedRow >= 0) {
                val menu = JPopupMenu()
                val resType = tableModel.getResourceType()

                when (resType) {
                    ResourceType.PODS -> buildPodPopupMenu(menu)
                    ResourceType.SERVICES -> buildServicePopupMenu(menu)
                    ResourceType.INGRESSES -> buildIngressPopupMenu(menu)
                    ResourceType.CONFIG_MAPS -> buildConfigMapPopupMenu(menu)
                    ResourceType.SECRETS -> buildSecretPopupMenu(menu)
                    ResourceType.DEPLOYMENTS -> buildDeploymentPopupMenu(menu)
                    ResourceType.STATEFUL_SETS -> buildStatefulSetPopupMenu(menu)
                    ResourceType.DAEMON_SETS -> buildDaemonSetPopupMenu(menu)
                    ResourceType.JOBS -> buildJobPopupMenu(menu)
                    ResourceType.CRON_JOBS -> buildCronJobPopupMenu(menu)
                    ResourceType.PERSISTENT_VOLUME_CLAIMS -> buildPersistentVolumeClaimPopupMenu(menu)
                    ResourceType.EVENTS -> buildEventPopupMenu(menu)
                }

                menu.show(e.component, e.x, e.y)
            }
        }
    }

    private fun copyToClipboard(text: String, statusMessage: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        statusLabel.text = statusMessage
    }

    private fun quickScaleDeployment(dep: DeploymentInfo) {
        val initialReplicas = if (dep.replicas > 0) dep.replicas else 1
        val input = Messages.showInputDialog(
            project,
            "Enter desired replica count for deployment '${dep.name}':",
            "Quick Scale Deployment",
            AllIcons.Nodes.Deploy,
            initialReplicas.toString(),
            null
        ) ?: return

        val newReplicas = input.trim().toIntOrNull()
        if (newReplicas == null || newReplicas < 0) {
            Messages.showErrorDialog(project, "Please enter a valid non-negative integer for replica count.", "Invalid Replica Count")
            return
        }

        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Scaling ${dep.name} to $newReplicas Replicas", false) {
            private var isSuccess = false
            private var outputMsg = ""

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Executing kubectl scale deployment/${dep.name} --replicas=$newReplicas..."
                val client = KubernetesSshClient(profile)
                val result = client.scaleDeployment(dep.namespace, dep.name, newReplicas)
                isSuccess = result.isSuccess
                outputMsg = if (result.isSuccess) result.output else result.error.ifBlank { result.output }
            }

            override fun onSuccess() {
                if (isSuccess) {
                    statusLabel.text = "Scaled deployment '${dep.name}' to $newReplicas replicas."
                    Messages.showInfoMessage(project, "Deployment '${dep.name}' scaled to $newReplicas replicas successfully:\n$outputMsg", "Scale Deployment")
                    KubernetesDataCache.invalidateResourceType(profile.id, dep.namespace, ResourceType.DEPLOYMENTS)
                    KubernetesDataCache.invalidateResourceType(profile.id, dep.namespace, ResourceType.PODS)
                    loadResources(ResourceType.DEPLOYMENTS, dep.namespace)
                } else {
                    Messages.showErrorDialog(project, "Failed to scale deployment:\n$outputMsg", "Scale Failed")
                }
            }
        })
    }

    private fun buildPodPopupMenu(menu: JPopupMenu) {
        val selectedPods = getSelectedPods()
        if (selectedPods.isEmpty()) return
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()

        if (selectedPods.size > 1) {
            // Multi-pod Selection Actions
            menu.add(JMenuItem("Tail Logs (${selectedPods.size} Pods)...").apply {
                icon = AllIcons.Debugger.Console
                font = font.deriveFont(Font.BOLD)
                addActionListener {
                    onNavigateToTailLogs?.invoke(profile, selectedPods.first().namespace, selectedPods.map { it.name }, null, true, 200, false, false)
                }
            })
            menu.add(JMenuItem("Delete (${selectedPods.size} Pods)...").apply {
                icon = AllIcons.Actions.Cancel
                addActionListener {
                    deleteMultiplePods(selectedPods, force = false)
                }
            })
            menu.add(JMenuItem("Force Delete (${selectedPods.size} Pods)...").apply {
                icon = AllIcons.Actions.ForceRefresh
                addActionListener {
                    deleteMultiplePods(selectedPods, force = true)
                }
            })
            menu.addSeparator()

            val copyMenu = JMenu("Copy").apply {
                icon = AllIcons.Actions.Copy
            }
            copyMenu.add(JMenuItem("Copy Pod Names (${selectedPods.size})").apply {
                icon = AllIcons.Actions.Copy
                addActionListener {
                    val names = selectedPods.joinToString("\n") { it.name }
                    copyToClipboard(names, "Copied ${selectedPods.size} pod names to clipboard.")
                }
            })
            copyMenu.add(JMenuItem("Copy 'kubectl logs' Commands").apply {
                icon = AllIcons.Actions.Copy
                addActionListener {
                    val cmds = selectedPods.joinToString(" && ") { "kubectl logs ${it.name} -n ${it.namespace}" }
                    copyToClipboard(cmds, "Copied kubectl logs commands to clipboard.")
                }
            })
            menu.add(copyMenu)
            return
        }

        val pod = selectedPods.first()

        // 1. Primary Inspect / Diagnose Actions
        menu.add(JMenuItem("Pod Details & YAML...").apply {
            icon = AllIcons.Actions.Show
            font = font.deriveFont(Font.BOLD)
            addActionListener { showSelectedResourceDetails() }
        })
        menu.add(JMenuItem("Tail Logs in Tail Logs Tab").apply {
            icon = AllIcons.Debugger.Console
            font = font.deriveFont(Font.BOLD)
            addActionListener {
                onNavigateToTailLogs?.invoke(profile, pod.namespace, listOf(pod.name), null, true, 200, false, false)
            }
        })
        if (pod.containers.size > 1) {
            val tailContainerMenu = JMenu("Tail Logs for Container").apply {
                icon = AllIcons.Debugger.Console
            }
            for (cName in pod.containers) {
                tailContainerMenu.add(JMenuItem("Container: $cName").apply {
                    icon = AllIcons.Nodes.Deploy
                    addActionListener {
                        onNavigateToTailLogs?.invoke(profile, pod.namespace, listOf(pod.name), cName, true, 200, false, false)
                    }
                })
            }
            menu.add(tailContainerMenu)
        }
        menu.add(JMenuItem("View Logs (Snapshot)...").apply {
            icon = AllIcons.FileTypes.Text
            addActionListener { showSelectedLogs() }
        })
        menu.add(JMenuItem("Describe Pod...").apply {
            icon = AllIcons.Actions.PreviewDetails
            addActionListener { describeSelectedResource() }
        })

        menu.addSeparator()

        // 2. Interactive & Navigation Actions
        if (pod.containers.size > 1) {
            menu.add(JMenuItem("Execute Terminal (Select Container)...").apply {
                icon = AllIcons.Debugger.Console
                font = font.deriveFont(Font.BOLD)
                addActionListener { openSelectedPodTerminal() }
            })

            val execContainerMenu = JMenu("Execute Terminal in Container").apply {
                icon = AllIcons.Debugger.Console
            }
            for (cName in pod.containers) {
                execContainerMenu.add(JMenuItem("Container: $cName").apply {
                    icon = AllIcons.Nodes.Deploy
                    addActionListener { openSelectedPodTerminal(targetContainer = cName) }
                })
            }
            menu.add(execContainerMenu)
        } else {
            menu.add(JMenuItem("Execute Terminal (Built-in Terminal)").apply {
                icon = AllIcons.Debugger.Console
                font = font.deriveFont(Font.BOLD)
                addActionListener { openSelectedPodTerminal() }
            })
        }
        menu.add(JMenuItem("Show in Docker Images").apply {
            icon = AllIcons.Actions.Forward
            addActionListener { navigateToDockerImageForSelectedResource() }
        })
        menu.add(JMenuItem("Open in Kubectl Console").apply {
            icon = AllIcons.Nodes.Console
            addActionListener {
                onNavigateToKubectl?.invoke("describe pod ${pod.name} -n ${pod.namespace}")
            }
        })
        if (pod.deploymentName.isNotBlank()) {
            menu.add(JMenuItem("Edit Parent Deployment (${pod.deploymentName})...").apply {
                icon = AllIcons.Actions.Edit
                addActionListener {
                    openEditDeploymentDialog(deploymentName = pod.deploymentName, namespace = pod.namespace)
                }
            })
        }

        menu.addSeparator()

        // 3. Copy Submenu
        val copyMenu = JMenu("Copy").apply {
            icon = AllIcons.Actions.Copy
        }
        copyMenu.add(JMenuItem("Copy Pod Name (${pod.name})").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                copyToClipboard(pod.name, "Copied pod name '${pod.name}' to clipboard.")
            }
        })
        if (pod.ip.isNotBlank()) {
            copyMenu.add(JMenuItem("Copy Pod IP (${pod.ip})").apply {
                icon = AllIcons.Actions.Copy
                addActionListener {
                    copyToClipboard(pod.ip, "Copied pod IP '${pod.ip}' to clipboard.")
                }
            })
        }
        if (pod.image.isNotBlank()) {
            copyMenu.add(JMenuItem("Copy Container Image (${pod.image})").apply {
                icon = AllIcons.Actions.Copy
                addActionListener {
                    copyToClipboard(pod.image, "Copied image '${pod.image}' to clipboard.")
                }
            })
        }
        if (pod.imageId.isNotBlank()) {
            copyMenu.add(JMenuItem("Copy Image ID (${pod.imageId})").apply {
                icon = AllIcons.Actions.Copy
                addActionListener {
                    copyToClipboard(pod.imageId, "Copied image ID '${pod.imageId}' to clipboard.")
                }
            })
        }
        if (pod.node.isNotBlank()) {
            copyMenu.add(JMenuItem("Copy Node Name (${pod.node})").apply {
                icon = AllIcons.Actions.Copy
                addActionListener {
                    copyToClipboard(pod.node, "Copied node name '${pod.node}' to clipboard.")
                }
            })
        }
        copyMenu.addSeparator()
        copyMenu.add(JMenuItem("Copy 'kubectl get pod' Command").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                val cmd = "kubectl get pod ${pod.name} -n ${pod.namespace} -o wide"
                copyToClipboard(cmd, "Copied command: $cmd")
            }
        })
        copyMenu.add(JMenuItem("Copy 'kubectl describe pod' Command").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                val cmd = "kubectl describe pod ${pod.name} -n ${pod.namespace}"
                copyToClipboard(cmd, "Copied command: $cmd")
            }
        })
        copyMenu.add(JMenuItem("Copy 'kubectl logs' Command").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                val cmd = "kubectl logs ${pod.name} -n ${pod.namespace}"
                copyToClipboard(cmd, "Copied command: $cmd")
            }
        })
        if (pod.containers.size > 1) {
            val copyExecMenu = JMenu("Copy 'kubectl exec' Command").apply {
                icon = AllIcons.Actions.Copy
            }
            copyExecMenu.add(JMenuItem("Default (Auto Container)").apply {
                icon = AllIcons.Actions.Copy
                addActionListener {
                    val cmd = "kubectl exec -it ${pod.name} -n ${pod.namespace} -- /bin/sh"
                    copyToClipboard(cmd, "Copied command: $cmd")
                }
            })
            for (cName in pod.containers) {
                copyExecMenu.add(JMenuItem("Container: $cName").apply {
                    icon = AllIcons.Actions.Copy
                    addActionListener {
                        val cmd = "kubectl exec -it ${pod.name} -n ${pod.namespace} -c $cName -- /bin/sh"
                        copyToClipboard(cmd, "Copied command: $cmd")
                    }
                })
            }
            copyMenu.add(copyExecMenu)
        } else {
            val containerFlag = if (pod.containers.isNotEmpty()) " -c ${pod.containers.first()}" else ""
            copyMenu.add(JMenuItem("Copy 'kubectl exec' Command").apply {
                icon = AllIcons.Actions.Copy
                addActionListener {
                    val cmd = "kubectl exec -it ${pod.name} -n ${pod.namespace}$containerFlag -- /bin/sh"
                    copyToClipboard(cmd, "Copied command: $cmd")
                }
            })
        }
        copyMenu.add(JMenuItem("Copy 'kubectl port-forward' Command").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                val cmd = "kubectl port-forward pod/${pod.name} 8080:8080 -n ${pod.namespace}"
                copyToClipboard(cmd, "Copied command: $cmd")
            }
        })
        menu.add(copyMenu)

        menu.addSeparator()

        // 4. Delete Submenu
        val deleteMenu = JMenu("Delete Pod").apply {
            icon = AllIcons.Actions.Cancel
        }
        deleteMenu.add(JMenuItem("Delete Pod (Graceful)...").apply {
            icon = AllIcons.Actions.Cancel
            addActionListener { deleteSelectedResource(force = false) }
        })
        deleteMenu.add(JMenuItem("Force Delete Pod (Grace Period 0)...").apply {
            icon = AllIcons.Actions.ForceRefresh
            addActionListener { deleteSelectedResource(force = true) }
        })
        menu.add(deleteMenu)
    }

    private fun buildServicePopupMenu(menu: JPopupMenu) {
        val svc = getSelectedService() ?: return

        menu.add(JMenuItem("View / Edit Service YAML...").apply {
            icon = AllIcons.FileTypes.Yaml
            font = font.deriveFont(Font.BOLD)
            addActionListener { showSelectedResourceYamlOrDetails() }
        })
        menu.add(JMenuItem("Describe Service...").apply {
            icon = AllIcons.Actions.PreviewDetails
            addActionListener { describeSelectedResource() }
        })
        menu.add(JMenuItem("Open in Kubectl Console").apply {
            icon = AllIcons.Nodes.Console
            addActionListener {
                onNavigateToKubectl?.invoke("describe service ${svc.name} -n ${svc.namespace}")
            }
        })
        menu.add(JMenuItem("Port Forward in Console").apply {
            icon = AllIcons.General.Web
            addActionListener {
                val port = svc.ports.split(",").firstOrNull()?.trim()?.split(":")?.firstOrNull()?.split("/")?.firstOrNull()?.toIntOrNull() ?: 8080
                onNavigateToKubectl?.invoke("port-forward svc/${svc.name} $port:$port -n ${svc.namespace}")
            }
        })

        menu.addSeparator()

        val copyMenu = JMenu("Copy").apply {
            icon = AllIcons.Actions.Copy
        }
        copyMenu.add(JMenuItem("Copy Service Name (${svc.name})").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                copyToClipboard(svc.name, "Copied service name '${svc.name}' to clipboard.")
            }
        })
        if (svc.clusterIP.isNotBlank() && svc.clusterIP != "None") {
            copyMenu.add(JMenuItem("Copy Cluster IP (${svc.clusterIP})").apply {
                icon = AllIcons.Actions.Copy
                addActionListener {
                    copyToClipboard(svc.clusterIP, "Copied Cluster IP '${svc.clusterIP}' to clipboard.")
                }
            })
        }
        if (svc.externalIP.isNotBlank() && svc.externalIP != "<none>") {
            copyMenu.add(JMenuItem("Copy External IP (${svc.externalIP})").apply {
                icon = AllIcons.Actions.Copy
                addActionListener {
                    copyToClipboard(svc.externalIP, "Copied External IP '${svc.externalIP}' to clipboard.")
                }
            })
        }
        if (svc.ports.isNotBlank() && svc.ports != "<none>") {
            copyMenu.add(JMenuItem("Copy Ports (${svc.ports})").apply {
                icon = AllIcons.Actions.Copy
                addActionListener {
                    copyToClipboard(svc.ports, "Copied ports '${svc.ports}' to clipboard.")
                }
            })
        }
        if (svc.selector.isNotBlank() && svc.selector != "<none>") {
            copyMenu.add(JMenuItem("Copy Selector (${svc.selector})").apply {
                icon = AllIcons.Actions.Copy
                addActionListener {
                    copyToClipboard(svc.selector, "Copied selector '${svc.selector}' to clipboard.")
                }
            })
        }
        copyMenu.addSeparator()
        copyMenu.add(JMenuItem("Copy 'kubectl get svc' Command").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                val cmd = "kubectl get svc ${svc.name} -n ${svc.namespace} -o yaml"
                copyToClipboard(cmd, "Copied command: $cmd")
            }
        })
        copyMenu.add(JMenuItem("Copy 'kubectl describe svc' Command").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                val cmd = "kubectl describe service ${svc.name} -n ${svc.namespace}"
                copyToClipboard(cmd, "Copied command: $cmd")
            }
        })
        copyMenu.add(JMenuItem("Copy 'kubectl port-forward' Command").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                val port = svc.ports.split(",").firstOrNull()?.trim()?.split(":")?.firstOrNull()?.split("/")?.firstOrNull()?.toIntOrNull() ?: 8080
                val cmd = "kubectl port-forward svc/${svc.name} $port:$port -n ${svc.namespace}"
                copyToClipboard(cmd, "Copied command: $cmd")
            }
        })
        menu.add(copyMenu)

        menu.addSeparator()

        val deleteMenu = JMenu("Delete Service").apply {
            icon = AllIcons.Actions.Cancel
        }
        deleteMenu.add(JMenuItem("Delete Service (Graceful)...").apply {
            icon = AllIcons.Actions.Cancel
            addActionListener { deleteSelectedResource(force = false) }
        })
        deleteMenu.add(JMenuItem("Force Delete Service...").apply {
            icon = AllIcons.Actions.ForceRefresh
            addActionListener { deleteSelectedResource(force = true) }
        })
        menu.add(deleteMenu)
    }

    private fun buildIngressPopupMenu(menu: JPopupMenu) {
        val ing = getSelectedIngress() ?: return

        menu.add(JMenuItem("View / Edit Ingress YAML...").apply {
            icon = AllIcons.FileTypes.Yaml
            font = font.deriveFont(Font.BOLD)
            addActionListener { showSelectedResourceYamlOrDetails() }
        })
        menu.add(JMenuItem("Describe Ingress...").apply {
            icon = AllIcons.Actions.PreviewDetails
            addActionListener { describeSelectedResource() }
        })
        menu.add(JMenuItem("Open in Kubectl Console").apply {
            icon = AllIcons.Nodes.Console
            addActionListener {
                onNavigateToKubectl?.invoke("describe ingress ${ing.name} -n ${ing.namespace}")
            }
        })

        menu.addSeparator()

        val copyMenu = JMenu("Copy").apply {
            icon = AllIcons.Actions.Copy
        }
        copyMenu.add(JMenuItem("Copy Ingress Name (${ing.name})").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                copyToClipboard(ing.name, "Copied Ingress name '${ing.name}' to clipboard.")
            }
        })
        if (ing.hosts.isNotBlank() && ing.hosts != "*") {
            copyMenu.add(JMenuItem("Copy Hosts (${ing.hosts})").apply {
                icon = AllIcons.Actions.Copy
                addActionListener {
                    copyToClipboard(ing.hosts, "Copied hosts to clipboard.")
                }
            })
        }
        if (ing.address.isNotBlank() && ing.address != "<none>") {
            copyMenu.add(JMenuItem("Copy Address (${ing.address})").apply {
                icon = AllIcons.Actions.Copy
                addActionListener {
                    copyToClipboard(ing.address, "Copied address to clipboard.")
                }
            })
        }
        copyMenu.addSeparator()
        copyMenu.add(JMenuItem("Copy 'kubectl get ingress' Command").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                val cmd = "kubectl get ingress ${ing.name} -n ${ing.namespace} -o yaml"
                copyToClipboard(cmd, "Copied command: $cmd")
            }
        })
        copyMenu.add(JMenuItem("Copy 'kubectl describe ingress' Command").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                val cmd = "kubectl describe ingress ${ing.name} -n ${ing.namespace}"
                copyToClipboard(cmd, "Copied command: $cmd")
            }
        })
        menu.add(copyMenu)

        menu.addSeparator()

        val deleteMenu = JMenu("Delete Ingress").apply {
            icon = AllIcons.Actions.Cancel
        }
        deleteMenu.add(JMenuItem("Delete Ingress (Graceful)...").apply {
            icon = AllIcons.Actions.Cancel
            addActionListener { deleteSelectedResource(force = false) }
        })
        deleteMenu.add(JMenuItem("Force Delete Ingress...").apply {
            icon = AllIcons.Actions.ForceRefresh
            addActionListener { deleteSelectedResource(force = true) }
        })
        menu.add(deleteMenu)
    }

    private fun buildSecretPopupMenu(menu: JPopupMenu) {
        val sec = getSelectedSecret() ?: return

        menu.add(JMenuItem("View Decoded Data & YAML...").apply {
            icon = AllIcons.Nodes.Padlock
            font = font.deriveFont(Font.BOLD)
            addActionListener { showSelectedSecretDetails() }
        })
        menu.add(JMenuItem("Describe Secret...").apply {
            icon = AllIcons.Actions.PreviewDetails
            addActionListener { describeSelectedResource() }
        })
        menu.add(JMenuItem("Open in Kubectl Console").apply {
            icon = AllIcons.Nodes.Console
            addActionListener {
                onNavigateToKubectl?.invoke("describe secret ${sec.name} -n ${sec.namespace}")
            }
        })

        menu.addSeparator()

        val copyMenu = JMenu("Copy").apply {
            icon = AllIcons.Actions.Copy
        }
        copyMenu.add(JMenuItem("Copy Secret Name (${sec.name})").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                copyToClipboard(sec.name, "Copied Secret name '${sec.name}' to clipboard.")
            }
        })
        if (sec.keys.isNotEmpty()) {
            val keysStr = sec.keys.joinToString(", ")
            copyMenu.add(JMenuItem("Copy Data Keys ($keysStr)").apply {
                icon = AllIcons.Actions.Copy
                addActionListener {
                    copyToClipboard(keysStr, "Copied secret data keys to clipboard.")
                }
            })
        }
        copyMenu.addSeparator()
        copyMenu.add(JMenuItem("Copy 'kubectl get secret' Command").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                val cmd = "kubectl get secret ${sec.name} -n ${sec.namespace} -o yaml"
                copyToClipboard(cmd, "Copied command: $cmd")
            }
        })
        copyMenu.add(JMenuItem("Copy 'kubectl describe secret' Command").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                val cmd = "kubectl describe secret ${sec.name} -n ${sec.namespace}"
                copyToClipboard(cmd, "Copied command: $cmd")
            }
        })
        menu.add(copyMenu)

        menu.addSeparator()

        val deleteMenu = JMenu("Delete Secret").apply {
            icon = AllIcons.Actions.Cancel
        }
        deleteMenu.add(JMenuItem("Delete Secret (Graceful)...").apply {
            icon = AllIcons.Actions.Cancel
            addActionListener { deleteSelectedResource(force = false) }
        })
        deleteMenu.add(JMenuItem("Force Delete Secret...").apply {
            icon = AllIcons.Actions.ForceRefresh
            addActionListener { deleteSelectedResource(force = true) }
        })
        menu.add(deleteMenu)
    }

    private fun buildStatefulSetPopupMenu(menu: JPopupMenu) {
        val sts = getSelectedStatefulSet() ?: return
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()

        menu.add(JMenuItem("Tail Logs (StatefulSet Pods)...").apply {
            icon = AllIcons.Debugger.Console
            font = font.deriveFont(Font.BOLD)
            addActionListener {
                val cachedPods = KubernetesDataCache.getPods(profile.id, sts.namespace)
                val stsPods = cachedPods?.filter { it.ownerName == sts.name || it.name.startsWith(sts.name) }?.map { it.name }
                val targetPods = if (!stsPods.isNullOrEmpty()) stsPods else listOf("statefulset/${sts.name}")
                onNavigateToTailLogs?.invoke(profile, sts.namespace, targetPods, null, true, 200, false, false)
            }
        })
        menu.add(JMenuItem("Quick Scale Replicas...").apply {
            icon = AllIcons.Nodes.Deploy
            addActionListener { quickScaleStatefulSet(sts) }
        })
        menu.add(JMenuItem("Restart Rollout...").apply {
            icon = AllIcons.Actions.Restart
            addActionListener { restartStatefulSetRollout(sts) }
        })
        menu.add(JMenuItem("View / Edit StatefulSet YAML...").apply {
            icon = AllIcons.FileTypes.Yaml
            addActionListener { showSelectedResourceYamlOrDetails() }
        })
        menu.add(JMenuItem("Describe StatefulSet...").apply {
            icon = AllIcons.Actions.PreviewDetails
            addActionListener { describeSelectedResource() }
        })

        menu.addSeparator()

        val copyMenu = JMenu("Copy").apply {
            icon = AllIcons.Actions.Copy
        }
        copyMenu.add(JMenuItem("Copy StatefulSet Name (${sts.name})").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                copyToClipboard(sts.name, "Copied StatefulSet name '${sts.name}' to clipboard.")
            }
        })
        copyMenu.add(JMenuItem("Copy 'kubectl rollout restart' Command").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                val cmd = "kubectl rollout restart statefulset/${sts.name} -n ${sts.namespace}"
                copyToClipboard(cmd, "Copied command: $cmd")
            }
        })
        copyMenu.add(JMenuItem("Copy 'kubectl scale' Command").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                val cmd = "kubectl scale statefulset/${sts.name} --replicas=${sts.replicas} -n ${sts.namespace}"
                copyToClipboard(cmd, "Copied command: $cmd")
            }
        })
        menu.add(copyMenu)

        menu.addSeparator()

        val deleteMenu = JMenu("Delete StatefulSet").apply {
            icon = AllIcons.Actions.Cancel
        }
        deleteMenu.add(JMenuItem("Delete StatefulSet (Graceful)...").apply {
            icon = AllIcons.Actions.Cancel
            addActionListener { deleteSelectedResource(force = false) }
        })
        deleteMenu.add(JMenuItem("Force Delete StatefulSet...").apply {
            icon = AllIcons.Actions.ForceRefresh
            addActionListener { deleteSelectedResource(force = true) }
        })
        menu.add(deleteMenu)
    }

    private fun buildDaemonSetPopupMenu(menu: JPopupMenu) {
        val ds = getSelectedDaemonSet() ?: return
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()

        menu.add(JMenuItem("Tail Logs (DaemonSet Pods)...").apply {
            icon = AllIcons.Debugger.Console
            font = font.deriveFont(Font.BOLD)
            addActionListener {
                val cachedPods = KubernetesDataCache.getPods(profile.id, ds.namespace)
                val dsPods = cachedPods?.filter { it.ownerName == ds.name || it.name.startsWith(ds.name) }?.map { it.name }
                val targetPods = if (!dsPods.isNullOrEmpty()) dsPods else listOf("daemonset/${ds.name}")
                onNavigateToTailLogs?.invoke(profile, ds.namespace, targetPods, null, true, 200, false, false)
            }
        })
        menu.add(JMenuItem("Restart Rollout...").apply {
            icon = AllIcons.Actions.Restart
            addActionListener { restartDaemonSetRollout(ds) }
        })
        menu.add(JMenuItem("View / Edit DaemonSet YAML...").apply {
            icon = AllIcons.FileTypes.Yaml
            addActionListener { showSelectedResourceYamlOrDetails() }
        })
        menu.add(JMenuItem("Describe DaemonSet...").apply {
            icon = AllIcons.Actions.PreviewDetails
            addActionListener { describeSelectedResource() }
        })

        menu.addSeparator()

        val copyMenu = JMenu("Copy").apply {
            icon = AllIcons.Actions.Copy
        }
        copyMenu.add(JMenuItem("Copy DaemonSet Name (${ds.name})").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                copyToClipboard(ds.name, "Copied DaemonSet name '${ds.name}' to clipboard.")
            }
        })
        copyMenu.add(JMenuItem("Copy 'kubectl rollout restart' Command").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                val cmd = "kubectl rollout restart daemonset/${ds.name} -n ${ds.namespace}"
                copyToClipboard(cmd, "Copied command: $cmd")
            }
        })
        menu.add(copyMenu)

        menu.addSeparator()

        val deleteMenu = JMenu("Delete DaemonSet").apply {
            icon = AllIcons.Actions.Cancel
        }
        deleteMenu.add(JMenuItem("Delete DaemonSet (Graceful)...").apply {
            icon = AllIcons.Actions.Cancel
            addActionListener { deleteSelectedResource(force = false) }
        })
        deleteMenu.add(JMenuItem("Force Delete DaemonSet...").apply {
            icon = AllIcons.Actions.ForceRefresh
            addActionListener { deleteSelectedResource(force = true) }
        })
        menu.add(deleteMenu)
    }

    private fun buildJobPopupMenu(menu: JPopupMenu) {
        val job = getSelectedJob() ?: return
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()

        menu.add(JMenuItem("Tail Logs in Tail Logs Tab").apply {
            icon = AllIcons.Debugger.Console
            font = font.deriveFont(Font.BOLD)
            addActionListener {
                onNavigateToTailLogs?.invoke(profile, job.namespace, listOf("job/${job.name}"), null, true, 200, false, false)
            }
        })
        menu.add(JMenuItem("Job Logs (Snapshot)...").apply {
            icon = AllIcons.FileTypes.Text
            addActionListener { showSelectedJobLogs() }
        })
        menu.add(JMenuItem("Describe Job...").apply {
            icon = AllIcons.Actions.PreviewDetails
            addActionListener { describeSelectedResource() }
        })
        menu.add(JMenuItem("View / Edit Job YAML...").apply {
            icon = AllIcons.FileTypes.Yaml
            addActionListener { showSelectedResourceYamlOrDetails() }
        })

        menu.addSeparator()

        val copyMenu = JMenu("Copy").apply {
            icon = AllIcons.Actions.Copy
        }
        copyMenu.add(JMenuItem("Copy Job Name (${job.name})").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                copyToClipboard(job.name, "Copied Job name '${job.name}' to clipboard.")
            }
        })
        copyMenu.add(JMenuItem("Copy 'kubectl logs' Command").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                val cmd = "kubectl logs job/${job.name} -n ${job.namespace}"
                copyToClipboard(cmd, "Copied command: $cmd")
            }
        })
        menu.add(copyMenu)

        menu.addSeparator()

        val deleteMenu = JMenu("Delete Job").apply {
            icon = AllIcons.Actions.Cancel
        }
        deleteMenu.add(JMenuItem("Delete Job (Graceful)...").apply {
            icon = AllIcons.Actions.Cancel
            addActionListener { deleteSelectedResource(force = false) }
        })
        deleteMenu.add(JMenuItem("Force Delete Job...").apply {
            icon = AllIcons.Actions.ForceRefresh
            addActionListener { deleteSelectedResource(force = true) }
        })
        menu.add(deleteMenu)
    }

    private fun buildCronJobPopupMenu(menu: JPopupMenu) {
        val cj = getSelectedCronJob() ?: return

        menu.add(JMenuItem("Run Now (Trigger Job)...").apply {
            icon = AllIcons.Actions.Execute
            font = font.deriveFont(Font.BOLD)
            addActionListener { triggerCronJobNow(cj) }
        })
        menu.add(JMenuItem("Describe CronJob...").apply {
            icon = AllIcons.Actions.PreviewDetails
            addActionListener { describeSelectedResource() }
        })
        menu.add(JMenuItem("View / Edit CronJob YAML...").apply {
            icon = AllIcons.FileTypes.Yaml
            addActionListener { showSelectedResourceYamlOrDetails() }
        })

        menu.addSeparator()

        val copyMenu = JMenu("Copy").apply {
            icon = AllIcons.Actions.Copy
        }
        copyMenu.add(JMenuItem("Copy CronJob Name (${cj.name})").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                copyToClipboard(cj.name, "Copied CronJob name '${cj.name}' to clipboard.")
            }
        })
        copyMenu.add(JMenuItem("Copy 'kubectl create job --from' Command").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                val cmd = "kubectl create job ${cj.name}-manual -n ${cj.namespace} --from=cronjob/${cj.name}"
                copyToClipboard(cmd, "Copied command: $cmd")
            }
        })
        menu.add(copyMenu)

        menu.addSeparator()

        val deleteMenu = JMenu("Delete CronJob").apply {
            icon = AllIcons.Actions.Cancel
        }
        deleteMenu.add(JMenuItem("Delete CronJob (Graceful)...").apply {
            icon = AllIcons.Actions.Cancel
            addActionListener { deleteSelectedResource(force = false) }
        })
        deleteMenu.add(JMenuItem("Force Delete CronJob...").apply {
            icon = AllIcons.Actions.ForceRefresh
            addActionListener { deleteSelectedResource(force = true) }
        })
        menu.add(deleteMenu)
    }

    private fun buildPersistentVolumeClaimPopupMenu(menu: JPopupMenu) {
        val pvc = getSelectedPersistentVolumeClaim() ?: return

        menu.add(JMenuItem("Describe PVC...").apply {
            icon = AllIcons.Actions.PreviewDetails
            font = font.deriveFont(Font.BOLD)
            addActionListener { describeSelectedResource() }
        })
        menu.add(JMenuItem("View / Edit PVC YAML...").apply {
            icon = AllIcons.FileTypes.Yaml
            addActionListener { showSelectedResourceYamlOrDetails() }
        })

        menu.addSeparator()

        val copyMenu = JMenu("Copy").apply {
            icon = AllIcons.Actions.Copy
        }
        copyMenu.add(JMenuItem("Copy PVC Name (${pvc.name})").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                copyToClipboard(pvc.name, "Copied PVC name '${pvc.name}' to clipboard.")
            }
        })
        if (pvc.volume.isNotBlank() && pvc.volume != "<none>") {
            copyMenu.add(JMenuItem("Copy Bound Volume (${pvc.volume})").apply {
                icon = AllIcons.Actions.Copy
                addActionListener {
                    copyToClipboard(pvc.volume, "Copied bound volume to clipboard.")
                }
            })
        }
        copyMenu.addSeparator()
        copyMenu.add(JMenuItem("Copy 'kubectl describe pvc' Command").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                val cmd = "kubectl describe pvc ${pvc.name} -n ${pvc.namespace}"
                copyToClipboard(cmd, "Copied command: $cmd")
            }
        })
        menu.add(copyMenu)

        menu.addSeparator()

        val deleteMenu = JMenu("Delete PVC").apply {
            icon = AllIcons.Actions.Cancel
        }
        deleteMenu.add(JMenuItem("Delete PVC (Graceful)...").apply {
            icon = AllIcons.Actions.Cancel
            addActionListener { deleteSelectedResource(force = false) }
        })
        deleteMenu.add(JMenuItem("Force Delete PVC...").apply {
            icon = AllIcons.Actions.ForceRefresh
            addActionListener { deleteSelectedResource(force = true) }
        })
        menu.add(deleteMenu)
    }

    private fun buildEventPopupMenu(menu: JPopupMenu) {
        val ev = getSelectedEvent() ?: return

        if (ev.involvedObject.isNotBlank()) {
            menu.add(JMenuItem("Describe Involved Object (${ev.involvedObject})...").apply {
                icon = AllIcons.Actions.PreviewDetails
                font = font.deriveFont(Font.BOLD)
                addActionListener {
                    val parts = ev.involvedObject.split("/")
                    val kind = parts.getOrNull(0)?.lowercase() ?: "pod"
                    val name = parts.getOrNull(1) ?: parts[0]
                    describeNamedResource(kind, ev.namespace, name)
                }
            })
            menu.addSeparator()
        }

        val copyMenu = JMenu("Copy").apply {
            icon = AllIcons.Actions.Copy
        }
        copyMenu.add(JMenuItem("Copy Event Message").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                copyToClipboard(ev.message, "Copied event message to clipboard.")
            }
        })
        if (ev.involvedObject.isNotBlank()) {
            copyMenu.add(JMenuItem("Copy Involved Object (${ev.involvedObject})").apply {
                icon = AllIcons.Actions.Copy
                addActionListener {
                    copyToClipboard(ev.involvedObject, "Copied involved object to clipboard.")
                }
            })
        }
        if (ev.reason.isNotBlank()) {
            copyMenu.add(JMenuItem("Copy Reason (${ev.reason})").apply {
                icon = AllIcons.Actions.Copy
                addActionListener {
                    copyToClipboard(ev.reason, "Copied event reason to clipboard.")
                }
            })
        }
        menu.add(copyMenu)
    }

    private fun buildConfigMapPopupMenu(menu: JPopupMenu) {
        val cm = getSelectedConfigMap() ?: return

        menu.add(JMenuItem("View Data & Edit YAML...").apply {
            icon = AllIcons.Nodes.Property
            font = font.deriveFont(Font.BOLD)
            addActionListener { showSelectedResourceDetails() }
        })
        menu.add(JMenuItem("Describe ConfigMap...").apply {
            icon = AllIcons.Actions.PreviewDetails
            addActionListener { describeSelectedResource() }
        })
        menu.add(JMenuItem("Open in Kubectl Console").apply {
            icon = AllIcons.Nodes.Console
            addActionListener {
                onNavigateToKubectl?.invoke("describe configmap ${cm.name} -n ${cm.namespace}")
            }
        })

        menu.addSeparator()

        val copyMenu = JMenu("Copy").apply {
            icon = AllIcons.Actions.Copy
        }
        copyMenu.add(JMenuItem("Copy ConfigMap Name (${cm.name})").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                copyToClipboard(cm.name, "Copied ConfigMap name '${cm.name}' to clipboard.")
            }
        })
        if (cm.keys.isNotEmpty()) {
            val keysStr = cm.keys.joinToString(", ")
            copyMenu.add(JMenuItem("Copy Data Keys ($keysStr)").apply {
                icon = AllIcons.Actions.Copy
                addActionListener {
                    copyToClipboard(keysStr, "Copied data keys to clipboard.")
                }
            })
        }
        copyMenu.addSeparator()
        copyMenu.add(JMenuItem("Copy 'kubectl get configmap' Command").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                val cmd = "kubectl get configmap ${cm.name} -n ${cm.namespace} -o yaml"
                copyToClipboard(cmd, "Copied command: $cmd")
            }
        })
        copyMenu.add(JMenuItem("Copy 'kubectl describe configmap' Command").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                val cmd = "kubectl describe configmap ${cm.name} -n ${cm.namespace}"
                copyToClipboard(cmd, "Copied command: $cmd")
            }
        })
        menu.add(copyMenu)

        menu.addSeparator()

        val deleteMenu = JMenu("Delete ConfigMap").apply {
            icon = AllIcons.Actions.Cancel
        }
        deleteMenu.add(JMenuItem("Delete ConfigMap (Graceful)...").apply {
            icon = AllIcons.Actions.Cancel
            addActionListener { deleteSelectedResource(force = false) }
        })
        deleteMenu.add(JMenuItem("Force Delete ConfigMap...").apply {
            icon = AllIcons.Actions.ForceRefresh
            addActionListener { deleteSelectedResource(force = true) }
        })
        menu.add(deleteMenu)
    }

    private fun buildDeploymentPopupMenu(menu: JPopupMenu) {
        val dep = getSelectedDeployment() ?: return
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()

        menu.add(JMenuItem("Tail Logs (Deployment Pods)...").apply {
            icon = AllIcons.Debugger.Console
            font = font.deriveFont(Font.BOLD)
            addActionListener {
                val cachedPods = KubernetesDataCache.getPods(profile.id, dep.namespace)
                val depPods = cachedPods?.filter { it.deploymentName == dep.name || it.name.startsWith(dep.name) }?.map { it.name }
                val targetPods = if (!depPods.isNullOrEmpty()) depPods else listOf("deployment/${dep.name}")
                onNavigateToTailLogs?.invoke(profile, dep.namespace, targetPods, null, true, 200, false, false)
            }
        })
        menu.add(JMenuItem("Edit Deployment (Scale, Images, Rollout)...").apply {
            icon = AllIcons.Actions.Edit
            addActionListener {
                openEditDeploymentDialog(deploymentName = dep.name, namespace = dep.namespace)
            }
        })
        menu.add(JMenuItem("View / Edit Deployment YAML...").apply {
            icon = AllIcons.FileTypes.Yaml
            addActionListener { showSelectedResourceYamlOrDetails() }
        })
        menu.add(JMenuItem("Describe Deployment...").apply {
            icon = AllIcons.Actions.PreviewDetails
            addActionListener { describeSelectedResource() }
        })

        menu.addSeparator()

        menu.add(JMenuItem("Quick Scale Replicas...").apply {
            icon = AllIcons.Nodes.Deploy
            addActionListener { quickScaleDeployment(dep) }
        })
        menu.add(JMenuItem("Restart Rollout...").apply {
            icon = AllIcons.Actions.Restart
            addActionListener { restartDeploymentRollout(dep) }
        })
        menu.add(JMenuItem("Show in Docker Images").apply {
            icon = AllIcons.Actions.Forward
            addActionListener { navigateToDockerImageForSelectedResource() }
        })
        menu.add(JMenuItem("Open in Kubectl Console").apply {
            icon = AllIcons.Nodes.Console
            addActionListener {
                onNavigateToKubectl?.invoke("describe deployment ${dep.name} -n ${dep.namespace}")
            }
        })

        menu.addSeparator()

        val copyMenu = JMenu("Copy").apply {
            icon = AllIcons.Actions.Copy
        }
        copyMenu.add(JMenuItem("Copy Deployment Name (${dep.name})").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                copyToClipboard(dep.name, "Copied deployment name '${dep.name}' to clipboard.")
            }
        })
        if (dep.images.isNotEmpty()) {
            copyMenu.add(JMenuItem("Copy Container Images").apply {
                icon = AllIcons.Actions.Copy
                addActionListener {
                    val imagesStr = dep.images.joinToString(", ")
                    copyToClipboard(imagesStr, "Copied deployment images to clipboard.")
                }
            })
        }
        copyMenu.addSeparator()
        copyMenu.add(JMenuItem("Copy 'kubectl rollout restart' Command").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                val cmd = "kubectl rollout restart deployment/${dep.name} -n ${dep.namespace}"
                copyToClipboard(cmd, "Copied command: $cmd")
            }
        })
        copyMenu.add(JMenuItem("Copy 'kubectl rollout status' Command").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                val cmd = "kubectl rollout status deployment/${dep.name} -n ${dep.namespace}"
                copyToClipboard(cmd, "Copied command: $cmd")
            }
        })
        copyMenu.add(JMenuItem("Copy 'kubectl scale' Command").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                val cmd = "kubectl scale deployment/${dep.name} --replicas=3 -n ${dep.namespace}"
                copyToClipboard(cmd, "Copied command: $cmd")
            }
        })
        copyMenu.add(JMenuItem("Copy 'kubectl get deployment' Command").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                val cmd = "kubectl get deployment ${dep.name} -n ${dep.namespace} -o yaml"
                copyToClipboard(cmd, "Copied command: $cmd")
            }
        })
        copyMenu.add(JMenuItem("Copy 'kubectl describe deployment' Command").apply {
            icon = AllIcons.Actions.Copy
            addActionListener {
                val cmd = "kubectl describe deployment ${dep.name} -n ${dep.namespace}"
                copyToClipboard(cmd, "Copied command: $cmd")
            }
        })
        menu.add(copyMenu)

        menu.addSeparator()

        val deleteMenu = JMenu("Delete Deployment").apply {
            icon = AllIcons.Actions.Cancel
        }
        deleteMenu.add(JMenuItem("Delete Deployment (Graceful)...").apply {
            icon = AllIcons.Actions.Cancel
            addActionListener { deleteSelectedResource(force = false) }
        })
        deleteMenu.add(JMenuItem("Force Delete Deployment...").apply {
            icon = AllIcons.Actions.ForceRefresh
            addActionListener { deleteSelectedResource(force = true) }
        })
        menu.add(deleteMenu)
    }

    fun syncProfile(profile: SshProfile) {
        refreshProfiles(profile.id)
        loadInitialData()
    }

    fun getSelectedPod(): PodInfo? {
        val row = table.selectedRow
        if (row >= 0 && row < table.rowCount) {
            val modelRow = table.convertRowIndexToModel(row)
            return tableModel.getPodAt(modelRow)
        }
        return null
    }

    fun getSelectedPods(): List<PodInfo> {
        val selectedRows = table.selectedRows
        if (selectedRows.isEmpty()) {
            val single = getSelectedPod()
            return if (single != null) listOf(single) else emptyList()
        }
        val list = mutableListOf<PodInfo>()
        for (row in selectedRows) {
            if (row >= 0 && row < table.rowCount) {
                val modelRow = table.convertRowIndexToModel(row)
                val pod = tableModel.getPodAt(modelRow)
                if (pod != null) {
                    list.add(pod)
                }
            }
        }
        return list
    }

    fun tailSelectedLogs() {
        val resType = tableModel.getResourceType()
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        val currentNamespace = namespaceComboBox.selectedItem as? String ?: getSelectedResourceNamespace() ?: "default"

        when (resType) {
            ResourceType.PODS -> {
                val selectedPods = getSelectedPods()
                if (selectedPods.isNotEmpty()) {
                    val podNames = selectedPods.map { it.name }
                    onNavigateToTailLogs?.invoke(profile, currentNamespace, podNames, null, true, 200, false, false)
                }
            }
            ResourceType.DEPLOYMENTS -> {
                val dep = getSelectedDeployment() ?: return
                val cachedPods = KubernetesDataCache.getPods(profile.id, dep.namespace)
                val depPods = cachedPods?.filter { it.deploymentName == dep.name || it.name.startsWith(dep.name) }?.map { it.name }
                val targetPods = if (!depPods.isNullOrEmpty()) depPods else listOf("deployment/${dep.name}")
                onNavigateToTailLogs?.invoke(profile, dep.namespace, targetPods, null, true, 200, false, false)
            }
            ResourceType.JOBS -> {
                val job = getSelectedJob() ?: return
                onNavigateToTailLogs?.invoke(profile, job.namespace, listOf("job/${job.name}"), null, true, 200, false, false)
            }
            ResourceType.STATEFUL_SETS -> {
                val sts = getSelectedStatefulSet() ?: return
                val cachedPods = KubernetesDataCache.getPods(profile.id, sts.namespace)
                val stsPods = cachedPods?.filter { it.ownerName == sts.name || it.name.startsWith(sts.name) }?.map { it.name }
                val targetPods = if (!stsPods.isNullOrEmpty()) stsPods else listOf("statefulset/${sts.name}")
                onNavigateToTailLogs?.invoke(profile, sts.namespace, targetPods, null, true, 200, false, false)
            }
            ResourceType.DAEMON_SETS -> {
                val ds = getSelectedDaemonSet() ?: return
                val cachedPods = KubernetesDataCache.getPods(profile.id, ds.namespace)
                val dsPods = cachedPods?.filter { it.ownerName == ds.name || it.name.startsWith(ds.name) }?.map { it.name }
                val targetPods = if (!dsPods.isNullOrEmpty()) dsPods else listOf("daemonset/${ds.name}")
                onNavigateToTailLogs?.invoke(profile, ds.namespace, targetPods, null, true, 200, false, false)
            }
            else -> {}
        }
    }

    private fun deleteMultiplePods(pods: List<PodInfo>, force: Boolean) {
        val currentNamespace = namespaceComboBox.selectedItem as? String ?: pods.firstOrNull()?.namespace ?: "default"
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        val confirm = Messages.showYesNoDialog(
            project,
            "Are you sure you want to ${if (force) "FORCE " else ""}delete ${pods.size} selected pods in namespace '$currentNamespace'?",
            if (force) "Force Delete ${pods.size} Pods" else "Delete ${pods.size} Pods",
            Messages.getWarningIcon()
        )
        if (confirm != Messages.YES) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Deleting ${pods.size} Pods", false) {
            private var successCount = 0
            private var failCount = 0

            override fun run(indicator: ProgressIndicator) {
                val client = KubernetesSshClient(profile)
                for ((index, pod) in pods.withIndex()) {
                    indicator.text = "Deleting pod ${pod.name} (${index + 1}/${pods.size})..."
                    val res = client.deleteResource("pod", pod.namespace, pod.name, force)
                    if (res.isSuccess) successCount++ else failCount++
                }
            }

            override fun onSuccess() {
                statusLabel.text = "Deleted $successCount pods${if (failCount > 0) " ($failCount failed)" else ""}."
                KubernetesDataCache.invalidateResourceType(profile.id, currentNamespace, ResourceType.PODS)
                loadResources(ResourceType.PODS, currentNamespace)
            }
        })
    }

    fun getSelectedService(): ServiceInfo? {
        val row = table.selectedRow
        if (row >= 0 && row < table.rowCount) {
            val modelRow = table.convertRowIndexToModel(row)
            return tableModel.getServiceAt(modelRow)
        }
        return null
    }

    fun getSelectedIngress(): IngressInfo? {
        val row = table.selectedRow
        if (row >= 0 && row < table.rowCount) {
            val modelRow = table.convertRowIndexToModel(row)
            return tableModel.getIngressAt(modelRow)
        }
        return null
    }

    fun getSelectedConfigMap(): ConfigMapInfo? {
        val row = table.selectedRow
        if (row >= 0 && row < table.rowCount) {
            val modelRow = table.convertRowIndexToModel(row)
            return tableModel.getConfigMapAt(modelRow)
        }
        return null
    }

    fun getSelectedSecret(): SecretInfo? {
        val row = table.selectedRow
        if (row >= 0 && row < table.rowCount) {
            val modelRow = table.convertRowIndexToModel(row)
            return tableModel.getSecretAt(modelRow)
        }
        return null
    }

    fun getSelectedDeployment(): DeploymentInfo? {
        val row = table.selectedRow
        if (row >= 0 && row < table.rowCount) {
            val modelRow = table.convertRowIndexToModel(row)
            return tableModel.getDeploymentAt(modelRow)
        }
        return null
    }

    fun getSelectedStatefulSet(): StatefulSetInfo? {
        val row = table.selectedRow
        if (row >= 0 && row < table.rowCount) {
            val modelRow = table.convertRowIndexToModel(row)
            return tableModel.getStatefulSetAt(modelRow)
        }
        return null
    }

    fun getSelectedDaemonSet(): DaemonSetInfo? {
        val row = table.selectedRow
        if (row >= 0 && row < table.rowCount) {
            val modelRow = table.convertRowIndexToModel(row)
            return tableModel.getDaemonSetAt(modelRow)
        }
        return null
    }

    fun getSelectedJob(): JobInfo? {
        val row = table.selectedRow
        if (row >= 0 && row < table.rowCount) {
            val modelRow = table.convertRowIndexToModel(row)
            return tableModel.getJobAt(modelRow)
        }
        return null
    }

    fun getSelectedCronJob(): CronJobInfo? {
        val row = table.selectedRow
        if (row >= 0 && row < table.rowCount) {
            val modelRow = table.convertRowIndexToModel(row)
            return tableModel.getCronJobAt(modelRow)
        }
        return null
    }

    fun getSelectedPersistentVolumeClaim(): PersistentVolumeClaimInfo? {
        val row = table.selectedRow
        if (row >= 0 && row < table.rowCount) {
            val modelRow = table.convertRowIndexToModel(row)
            return tableModel.getPersistentVolumeClaimAt(modelRow)
        }
        return null
    }

    fun getSelectedEvent(): EventInfo? {
        val row = table.selectedRow
        if (row >= 0 && row < table.rowCount) {
            val modelRow = table.convertRowIndexToModel(row)
            return tableModel.getEventAt(modelRow)
        }
        return null
    }

    fun getSelectedResourceName(): String? {
        val row = table.selectedRow
        if (row >= 0 && row < table.rowCount) {
            val modelRow = table.convertRowIndexToModel(row)
            return tableModel.getResourceNameAt(modelRow)
        }
        return null
    }

    fun getSelectedResourceNamespace(): String? {
        val row = table.selectedRow
        if (row >= 0 && row < table.rowCount) {
            val modelRow = table.convertRowIndexToModel(row)
            return tableModel.getResourceNamespaceAt(modelRow)
        }
        return null
    }

    private fun navigateToDockerImageForSelectedResource() {
        val resType = tableModel.getResourceType()
        if (resType == ResourceType.PODS) {
            val pod = getSelectedPod() ?: return
            val imageTarget = pod.imageId.ifBlank { pod.image }
            if (imageTarget.isNotBlank()) {
                onNavigateToDockerImage?.invoke(imageTarget)
            } else {
                Messages.showInfoMessage(project, "No image information available for pod '${pod.name}'.", "Image Navigation")
            }
        } else if (resType == ResourceType.DEPLOYMENTS) {
            val dep = getSelectedDeployment() ?: return
            val img = dep.images.firstOrNull() ?: ""
            if (img.isNotBlank()) {
                onNavigateToDockerImage?.invoke(img)
            } else {
                Messages.showInfoMessage(project, "No image information available for deployment '${dep.name}'.", "Image Navigation")
            }
        }
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

        val cachedNs = KubernetesDataCache.getNamespaces(profile.id)
        if (!cachedNs.isNullOrEmpty()) {
            applyNamespaces(cachedNs, profile)
            // Revalidate in background asynchronously
            KubernetesPrefetchService.prefetchNamespaces(profile) { freshNamespaces ->
                ApplicationManager.getApplication().invokeLater {
                    if (!isDisposed && !project.isDisposed && freshNamespaces != cachedNs) {
                        applyNamespaces(freshNamespaces, profile)
                    }
                }
            }
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
                    KubernetesDataCache.putNamespaces(profile.id, namespaces)
                } catch (e: Exception) {
                    errorMessage = e.message ?: "Unknown error"
                }
            }

            override fun onSuccess() {
                if (errorMessage != null) {
                    statusLabel.text = "Error fetching namespaces: $errorMessage"
                } else {
                    applyNamespaces(namespaces, profile)
                }
            }
        })
    }

    private fun applyNamespaces(namespaces: List<String>, profile: SshProfile) {
        val currentSelected = namespaceComboBox.selectedItem as? String
        isUpdatingNamespaces = true
        try {
            namespaceComboBox.removeAllItems()
            for (ns in namespaces) {
                namespaceComboBox.addItem(ns)
            }

            val targetNs = if (currentSelected != null && namespaces.contains(currentSelected)) {
                currentSelected
            } else if (namespaces.contains(profile.defaultNamespace)) {
                profile.defaultNamespace
            } else if (namespaces.contains("default")) {
                "default"
            } else {
                namespaces.firstOrNull() ?: "default"
            }

            namespaceComboBox.selectedItem = targetNs
        } finally {
            isUpdatingNamespaces = false
        }

        val currentType = resourceComboBox.selectedItem as? ResourceType ?: ResourceType.PODS
        val selectedNs = namespaceComboBox.selectedItem as? String ?: "default"
        loadResources(currentType, selectedNs)
    }

    private fun startAutoRefresh() {
        refreshTimer = Timer(5000) {
            if (isDisposed || project.isDisposed) {
                refreshTimer?.stop()
                return@Timer
            }
            val ns = namespaceComboBox.selectedItem as? String
            val resType = resourceComboBox.selectedItem as? ResourceType ?: ResourceType.PODS
            if (!ns.isNullOrBlank() && !isUpdatingNamespaces && !isUpdatingProfiles && !isUpdatingResources) {
                autoRefreshResources(resType, ns)
            }
        }.apply {
            isRepeats = true
            start()
        }
    }

    fun autoRefreshResources(resourceType: ResourceType, namespace: String) {
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        if (profile.host.isBlank() || !isFetching.compareAndSet(false, true)) {
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = KubernetesSshClient(profile)
                when (resourceType) {
                    ResourceType.PODS -> {
                        val pods = client.getPods(namespace)
                        KubernetesDataCache.putPods(profile.id, namespace, pods)
                        ApplicationManager.getApplication().invokeLater {
                            if (!isDisposed && !project.isDisposed && tableModel.getResourceType() == ResourceType.PODS) {
                                val selectedName = getSelectedResourceName()
                                tableModel.setPods(pods)
                                restoreResourceSelection(selectedName)
                                updateStatusLabel()
                            }
                        }
                    }
                    ResourceType.SERVICES -> {
                        val services = client.getServices(namespace)
                        KubernetesDataCache.putServices(profile.id, namespace, services)
                        ApplicationManager.getApplication().invokeLater {
                            if (!isDisposed && !project.isDisposed && tableModel.getResourceType() == ResourceType.SERVICES) {
                                val selectedName = getSelectedResourceName()
                                tableModel.setServices(services)
                                restoreResourceSelection(selectedName)
                                updateStatusLabel()
                            }
                        }
                    }
                    ResourceType.INGRESSES -> {
                        val ingresses = client.getIngresses(namespace)
                        KubernetesDataCache.putIngresses(profile.id, namespace, ingresses)
                        ApplicationManager.getApplication().invokeLater {
                            if (!isDisposed && !project.isDisposed && tableModel.getResourceType() == ResourceType.INGRESSES) {
                                val selectedName = getSelectedResourceName()
                                tableModel.setIngresses(ingresses)
                                restoreResourceSelection(selectedName)
                                updateStatusLabel()
                            }
                        }
                    }
                    ResourceType.CONFIG_MAPS -> {
                        val configMaps = client.getConfigMaps(namespace)
                        KubernetesDataCache.putConfigMaps(profile.id, namespace, configMaps)
                        ApplicationManager.getApplication().invokeLater {
                            if (!isDisposed && !project.isDisposed && tableModel.getResourceType() == ResourceType.CONFIG_MAPS) {
                                val selectedName = getSelectedResourceName()
                                tableModel.setConfigMaps(configMaps)
                                restoreResourceSelection(selectedName)
                                updateStatusLabel()
                            }
                        }
                    }
                    ResourceType.SECRETS -> {
                        val secrets = client.getSecrets(namespace)
                        KubernetesDataCache.putSecrets(profile.id, namespace, secrets)
                        ApplicationManager.getApplication().invokeLater {
                            if (!isDisposed && !project.isDisposed && tableModel.getResourceType() == ResourceType.SECRETS) {
                                val selectedName = getSelectedResourceName()
                                tableModel.setSecrets(secrets)
                                restoreResourceSelection(selectedName)
                                updateStatusLabel()
                            }
                        }
                    }
                    ResourceType.DEPLOYMENTS -> {
                        val deployments = client.getDeployments(namespace)
                        KubernetesDataCache.putDeployments(profile.id, namespace, deployments)
                        ApplicationManager.getApplication().invokeLater {
                            if (!isDisposed && !project.isDisposed && tableModel.getResourceType() == ResourceType.DEPLOYMENTS) {
                                val selectedName = getSelectedResourceName()
                                tableModel.setDeployments(deployments)
                                restoreResourceSelection(selectedName)
                                updateStatusLabel()
                            }
                        }
                    }
                    ResourceType.STATEFUL_SETS -> {
                        val statefulSets = client.getStatefulSets(namespace)
                        KubernetesDataCache.putStatefulSets(profile.id, namespace, statefulSets)
                        ApplicationManager.getApplication().invokeLater {
                            if (!isDisposed && !project.isDisposed && tableModel.getResourceType() == ResourceType.STATEFUL_SETS) {
                                val selectedName = getSelectedResourceName()
                                tableModel.setStatefulSets(statefulSets)
                                restoreResourceSelection(selectedName)
                                updateStatusLabel()
                            }
                        }
                    }
                    ResourceType.DAEMON_SETS -> {
                        val daemonSets = client.getDaemonSets(namespace)
                        KubernetesDataCache.putDaemonSets(profile.id, namespace, daemonSets)
                        ApplicationManager.getApplication().invokeLater {
                            if (!isDisposed && !project.isDisposed && tableModel.getResourceType() == ResourceType.DAEMON_SETS) {
                                val selectedName = getSelectedResourceName()
                                tableModel.setDaemonSets(daemonSets)
                                restoreResourceSelection(selectedName)
                                updateStatusLabel()
                            }
                        }
                    }
                    ResourceType.JOBS -> {
                        val jobs = client.getJobs(namespace)
                        KubernetesDataCache.putJobs(profile.id, namespace, jobs)
                        ApplicationManager.getApplication().invokeLater {
                            if (!isDisposed && !project.isDisposed && tableModel.getResourceType() == ResourceType.JOBS) {
                                val selectedName = getSelectedResourceName()
                                tableModel.setJobs(jobs)
                                restoreResourceSelection(selectedName)
                                updateStatusLabel()
                            }
                        }
                    }
                    ResourceType.CRON_JOBS -> {
                        val cronJobs = client.getCronJobs(namespace)
                        KubernetesDataCache.putCronJobs(profile.id, namespace, cronJobs)
                        ApplicationManager.getApplication().invokeLater {
                            if (!isDisposed && !project.isDisposed && tableModel.getResourceType() == ResourceType.CRON_JOBS) {
                                val selectedName = getSelectedResourceName()
                                tableModel.setCronJobs(cronJobs)
                                restoreResourceSelection(selectedName)
                                updateStatusLabel()
                            }
                        }
                    }
                    ResourceType.PERSISTENT_VOLUME_CLAIMS -> {
                        val pvcs = client.getPersistentVolumeClaims(namespace)
                        KubernetesDataCache.putPersistentVolumeClaims(profile.id, namespace, pvcs)
                        ApplicationManager.getApplication().invokeLater {
                            if (!isDisposed && !project.isDisposed && tableModel.getResourceType() == ResourceType.PERSISTENT_VOLUME_CLAIMS) {
                                val selectedName = getSelectedResourceName()
                                tableModel.setPersistentVolumeClaims(pvcs)
                                restoreResourceSelection(selectedName)
                                updateStatusLabel()
                            }
                        }
                    }
                    ResourceType.EVENTS -> {
                        val events = client.getEvents(namespace)
                        KubernetesDataCache.putEvents(profile.id, namespace, events)
                        ApplicationManager.getApplication().invokeLater {
                            if (!isDisposed && !project.isDisposed && tableModel.getResourceType() == ResourceType.EVENTS) {
                                val selectedName = getSelectedResourceName()
                                tableModel.setEvents(events)
                                restoreResourceSelection(selectedName)
                                updateStatusLabel()
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Background refresh silent on transient connection glitches
            } finally {
                isFetching.set(false)
            }
        }
    }

    private fun restoreResourceSelection(resourceName: String?) {
        if (resourceName == null) return
        for (i in 0 until tableModel.rowCount) {
            val name = tableModel.getResourceNameAt(i)
            if (name == resourceName) {
                val viewRow = table.convertRowIndexToView(i)
                if (viewRow >= 0 && viewRow < table.rowCount) {
                    table.setRowSelectionInterval(viewRow, viewRow)
                }
                break
            }
        }
    }

    fun loadPods(namespace: String) {
        loadResources(ResourceType.PODS, namespace)
    }

    fun loadResources(resourceType: ResourceType, namespace: String) {
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        if (profile.host.isBlank()) {
            statusLabel.text = "SSH Host is not configured in profile '${profile.name}'."
            return
        }

        val typeName = resourceType.displayName.lowercase()

        // 1. Check in-memory cache for instant rendering (0ms user wait)
        val cachedPods = if (resourceType == ResourceType.PODS) KubernetesDataCache.getPods(profile.id, namespace) else null
        val cachedServices = if (resourceType == ResourceType.SERVICES) KubernetesDataCache.getServices(profile.id, namespace) else null
        val cachedIngresses = if (resourceType == ResourceType.INGRESSES) KubernetesDataCache.getIngresses(profile.id, namespace) else null
        val cachedConfigMaps = if (resourceType == ResourceType.CONFIG_MAPS) KubernetesDataCache.getConfigMaps(profile.id, namespace) else null
        val cachedSecrets = if (resourceType == ResourceType.SECRETS) KubernetesDataCache.getSecrets(profile.id, namespace) else null
        val cachedDeployments = if (resourceType == ResourceType.DEPLOYMENTS) KubernetesDataCache.getDeployments(profile.id, namespace) else null
        val cachedStatefulSets = if (resourceType == ResourceType.STATEFUL_SETS) KubernetesDataCache.getStatefulSets(profile.id, namespace) else null
        val cachedDaemonSets = if (resourceType == ResourceType.DAEMON_SETS) KubernetesDataCache.getDaemonSets(profile.id, namespace) else null
        val cachedJobs = if (resourceType == ResourceType.JOBS) KubernetesDataCache.getJobs(profile.id, namespace) else null
        val cachedCronJobs = if (resourceType == ResourceType.CRON_JOBS) KubernetesDataCache.getCronJobs(profile.id, namespace) else null
        val cachedPVCs = if (resourceType == ResourceType.PERSISTENT_VOLUME_CLAIMS) KubernetesDataCache.getPersistentVolumeClaims(profile.id, namespace) else null
        val cachedEvents = if (resourceType == ResourceType.EVENTS) KubernetesDataCache.getEvents(profile.id, namespace) else null

        val hasCache = cachedPods != null || cachedServices != null || cachedIngresses != null ||
                cachedConfigMaps != null || cachedSecrets != null || cachedDeployments != null ||
                cachedStatefulSets != null || cachedDaemonSets != null || cachedJobs != null ||
                cachedCronJobs != null || cachedPVCs != null || cachedEvents != null

        if (hasCache) {
            val selectedName = getSelectedResourceName()
            when (resourceType) {
                ResourceType.PODS -> cachedPods?.let { tableModel.setPods(it) }
                ResourceType.SERVICES -> cachedServices?.let { tableModel.setServices(it) }
                ResourceType.INGRESSES -> cachedIngresses?.let { tableModel.setIngresses(it) }
                ResourceType.CONFIG_MAPS -> cachedConfigMaps?.let { tableModel.setConfigMaps(it) }
                ResourceType.SECRETS -> cachedSecrets?.let { tableModel.setSecrets(it) }
                ResourceType.DEPLOYMENTS -> cachedDeployments?.let { tableModel.setDeployments(it) }
                ResourceType.STATEFUL_SETS -> cachedStatefulSets?.let { tableModel.setStatefulSets(it) }
                ResourceType.DAEMON_SETS -> cachedDaemonSets?.let { tableModel.setDaemonSets(it) }
                ResourceType.JOBS -> cachedJobs?.let { tableModel.setJobs(it) }
                ResourceType.CRON_JOBS -> cachedCronJobs?.let { tableModel.setCronJobs(it) }
                ResourceType.PERSISTENT_VOLUME_CLAIMS -> cachedPVCs?.let { tableModel.setPersistentVolumeClaims(it) }
                ResourceType.EVENTS -> cachedEvents?.let { tableModel.setEvents(it) }
            }
            updateColumnWidths()
            restoreResourceSelection(selectedName)
            updateStatusLabel()

            // Prefetch other resources in background so switching types is instant
            KubernetesPrefetchService.prefetchAllResourcesForNamespace(profile, namespace, excludeType = resourceType)

            // Silent background revalidation
            autoRefreshResources(resourceType, namespace)
            return
        }

        // 2. If no cache, perform background load
        statusLabel.text = "[${profile.name}] Loading $typeName in namespace '$namespace'..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading ${resourceType.displayName} in $namespace", false) {
            private var pods: List<PodInfo> = emptyList()
            private var services: List<ServiceInfo> = emptyList()
            private var ingresses: List<IngressInfo> = emptyList()
            private var configMaps: List<ConfigMapInfo> = emptyList()
            private var secrets: List<SecretInfo> = emptyList()
            private var deployments: List<DeploymentInfo> = emptyList()
            private var statefulSets: List<StatefulSetInfo> = emptyList()
            private var daemonSets: List<DaemonSetInfo> = emptyList()
            private var jobs: List<JobInfo> = emptyList()
            private var cronJobs: List<CronJobInfo> = emptyList()
            private var pvcs: List<PersistentVolumeClaimInfo> = emptyList()
            private var events: List<EventInfo> = emptyList()
            private var errorMessage: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Running kubectl get ${resourceType.pluralName} -n $namespace..."
                try {
                    val client = KubernetesSshClient(profile)
                    when (resourceType) {
                        ResourceType.PODS -> {
                            pods = client.getPods(namespace)
                            KubernetesDataCache.putPods(profile.id, namespace, pods)
                        }
                        ResourceType.SERVICES -> {
                            services = client.getServices(namespace)
                            KubernetesDataCache.putServices(profile.id, namespace, services)
                        }
                        ResourceType.INGRESSES -> {
                            ingresses = client.getIngresses(namespace)
                            KubernetesDataCache.putIngresses(profile.id, namespace, ingresses)
                        }
                        ResourceType.CONFIG_MAPS -> {
                            configMaps = client.getConfigMaps(namespace)
                            KubernetesDataCache.putConfigMaps(profile.id, namespace, configMaps)
                        }
                        ResourceType.SECRETS -> {
                            secrets = client.getSecrets(namespace)
                            KubernetesDataCache.putSecrets(profile.id, namespace, secrets)
                        }
                        ResourceType.DEPLOYMENTS -> {
                            deployments = client.getDeployments(namespace)
                            KubernetesDataCache.putDeployments(profile.id, namespace, deployments)
                        }
                        ResourceType.STATEFUL_SETS -> {
                            statefulSets = client.getStatefulSets(namespace)
                            KubernetesDataCache.putStatefulSets(profile.id, namespace, statefulSets)
                        }
                        ResourceType.DAEMON_SETS -> {
                            daemonSets = client.getDaemonSets(namespace)
                            KubernetesDataCache.putDaemonSets(profile.id, namespace, daemonSets)
                        }
                        ResourceType.JOBS -> {
                            jobs = client.getJobs(namespace)
                            KubernetesDataCache.putJobs(profile.id, namespace, jobs)
                        }
                        ResourceType.CRON_JOBS -> {
                            cronJobs = client.getCronJobs(namespace)
                            KubernetesDataCache.putCronJobs(profile.id, namespace, cronJobs)
                        }
                        ResourceType.PERSISTENT_VOLUME_CLAIMS -> {
                            pvcs = client.getPersistentVolumeClaims(namespace)
                            KubernetesDataCache.putPersistentVolumeClaims(profile.id, namespace, pvcs)
                        }
                        ResourceType.EVENTS -> {
                            events = client.getEvents(namespace)
                            KubernetesDataCache.putEvents(profile.id, namespace, events)
                        }
                    }
                } catch (e: Exception) {
                    errorMessage = e.message ?: "Unknown error"
                }
            }

            override fun onSuccess() {
                if (errorMessage != null) {
                    statusLabel.text = "Error fetching $typeName: $errorMessage"
                } else {
                    val selectedName = getSelectedResourceName()
                    when (resourceType) {
                        ResourceType.PODS -> tableModel.setPods(pods)
                        ResourceType.SERVICES -> tableModel.setServices(services)
                        ResourceType.INGRESSES -> tableModel.setIngresses(ingresses)
                        ResourceType.CONFIG_MAPS -> tableModel.setConfigMaps(configMaps)
                        ResourceType.SECRETS -> tableModel.setSecrets(secrets)
                        ResourceType.DEPLOYMENTS -> tableModel.setDeployments(deployments)
                        ResourceType.STATEFUL_SETS -> tableModel.setStatefulSets(statefulSets)
                        ResourceType.DAEMON_SETS -> tableModel.setDaemonSets(daemonSets)
                        ResourceType.JOBS -> tableModel.setJobs(jobs)
                        ResourceType.CRON_JOBS -> tableModel.setCronJobs(cronJobs)
                        ResourceType.PERSISTENT_VOLUME_CLAIMS -> tableModel.setPersistentVolumeClaims(pvcs)
                        ResourceType.EVENTS -> tableModel.setEvents(events)
                    }
                    updateColumnWidths()
                    restoreResourceSelection(selectedName)
                    updateStatusLabel()

                    // Prefetch other resources in background
                    KubernetesPrefetchService.prefetchAllResourcesForNamespace(profile, namespace, excludeType = resourceType)
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
        val resourceLabel = tableModel.getResourceType().pluralName.lowercase()
        statusLabel.text = "Profile: $profileName | Namespace: $ns | $count $resourceLabel$filterInfo | Last updated: $time"
        summaryLabel.text = "$count $resourceLabel$filterInfo"
    }

    private fun deleteSelectedResource(force: Boolean) {
        val resType = tableModel.getResourceType()
        val resName = getSelectedResourceName() ?: return
        val currentNamespace = namespaceComboBox.selectedItem as? String ?: getSelectedResourceNamespace() ?: "default"
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()

        val warningMsg = if (force) {
            "Are you sure you want to FORCE delete ${resType.singularName} '$resName' in namespace '$currentNamespace'?\nThis immediately terminates the resource."
        } else {
            "Are you sure you want to delete ${resType.singularName} '$resName' in namespace '$currentNamespace'?"
        }

        val title = if (force) "Force Delete ${resType.singularName.replaceFirstChar { it.uppercase() }}" else "Delete ${resType.singularName.replaceFirstChar { it.uppercase() }}"
        val confirm = Messages.showYesNoDialog(
            project,
            warningMsg,
            title,
            Messages.getWarningIcon()
        )

        if (confirm != Messages.YES) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Deleting ${resType.singularName} $resName", false) {
            private var deleteSuccess = false
            private var errorOutput = ""

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Executing kubectl delete ${resType.singularName} $resName..."
                val client = KubernetesSshClient(profile)
                val cmdResult = client.deleteResource(resType.singularName, currentNamespace, resName, force)
                deleteSuccess = cmdResult.isSuccess
                errorOutput = cmdResult.error.ifBlank { cmdResult.output }
            }

            override fun onSuccess() {
                if (deleteSuccess) {
                    statusLabel.text = "${resType.singularName.replaceFirstChar { it.uppercase() }} '$resName' deleted successfully."
                    KubernetesDataCache.invalidateResourceType(profile.id, currentNamespace, resType)
                    if (resType == ResourceType.DEPLOYMENTS) {
                        KubernetesDataCache.invalidateResourceType(profile.id, currentNamespace, ResourceType.PODS)
                    }
                    loadResources(resType, currentNamespace)
                } else {
                    Messages.showErrorDialog(
                        project,
                        "Failed to delete ${resType.singularName} '$resName':\n$errorOutput",
                        "Delete Failed"
                    )
                }
            }
        })
    }

    private fun openSelectedPodTerminal(targetContainer: String? = null, targetShell: String = "/bin/sh") {
        val pod = getSelectedPod() ?: return
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()

        val (containerToExec, shellToExec) = if (targetContainer != null) {
            targetContainer to targetShell
        } else if (pod.containers.size > 1) {
            val dialog = SelectContainerDialog(project, pod, initialShell = targetShell)
            if (!dialog.showAndGet()) {
                return
            }
            dialog.getSelectedContainer() to dialog.getSelectedShell()
        } else {
            pod.containers.firstOrNull().orEmpty() to targetShell
        }

        val opened = TerminalLauncher.openInBuiltinTerminal(
            project = project,
            profile = profile,
            pod = pod,
            container = containerToExec.ifBlank { null },
            shell = shellToExec
        )
        if (opened) {
            val containerMsg = if (containerToExec.isNotBlank()) " (container: $containerToExec)" else ""
            statusLabel.text = "Opened terminal for pod '${pod.name}'$containerMsg in built-in IntelliJ Terminal."
        } else {
            Messages.showWarningDialog(
                project,
                "Unable to open terminal for pod '${pod.name}'. Please ensure the Terminal plugin is enabled and configured.",
                "Terminal Unavailable"
            )
        }
    }

    private fun showSelectedLogs() {
        val resType = tableModel.getResourceType()
        val currentNamespace = namespaceComboBox.selectedItem as? String ?: getSelectedResourceNamespace() ?: "default"
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()

        val resourceName = when (resType) {
            ResourceType.PODS -> getSelectedPod()?.name
            ResourceType.DEPLOYMENTS -> getSelectedDeployment()?.name?.let { "deployment/$it" }
            else -> null
        } ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Fetching Logs", false) {
            private var logs = ""
            private var isSuccess = false

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Fetching logs for $resourceName..."
                val client = KubernetesSshClient(profile)
                val cmdResult = client.getPodLogs(currentNamespace, resourceName)
                isSuccess = cmdResult.isSuccess
                logs = if (isSuccess) cmdResult.output else cmdResult.error.ifBlank { cmdResult.output }
            }

            override fun onSuccess() {
                val dialog = PodDetailsDialog(
                    project,
                    titleText = "Logs: $resourceName ($currentNamespace)",
                    contentText = logs.ifBlank { "No logs available or empty output." }
                )
                dialog.show()
            }
        })
    }

    private fun describeSelectedResource() {
        val resType = tableModel.getResourceType()
        val resName = getSelectedResourceName() ?: return
        val currentNamespace = namespaceComboBox.selectedItem as? String ?: getSelectedResourceNamespace() ?: "default"
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()

        val cachedDescribe = KubernetesDataCache.getDescribe(profile.id, currentNamespace, resType.singularName.lowercase(), resName)
        if (cachedDescribe != null) {
            val pod = getSelectedPod()
            val dialog = PodDetailsDialog(
                project,
                titleText = "Describe ${resType.singularName.replaceFirstChar { it.uppercase() }}: $resName ($currentNamespace)",
                contentText = cachedDescribe,
                onEditDeployment = if (resType == ResourceType.PODS && pod != null && pod.deploymentName.isNotBlank()) {
                    { openEditDeploymentDialog(deploymentName = pod.deploymentName, namespace = currentNamespace) }
                } else if (resType == ResourceType.DEPLOYMENTS) {
                    { openEditDeploymentDialog(deploymentName = resName, namespace = currentNamespace) }
                } else null
            )
            dialog.show()
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Describing ${resType.singularName}", false) {
            private var description = ""
            private var isSuccess = false

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Describing ${resType.singularName} $resName..."
                val client = KubernetesSshClient(profile)
                val cmdResult = client.describeResource(resType.singularName, currentNamespace, resName)
                isSuccess = cmdResult.isSuccess
                description = if (isSuccess) cmdResult.output else cmdResult.error.ifBlank { cmdResult.output }
                if (isSuccess) {
                    KubernetesDataCache.putDescribe(profile.id, currentNamespace, resType.singularName.lowercase(), resName, description)
                }
            }

            override fun onSuccess() {
                val pod = getSelectedPod()
                val dialog = PodDetailsDialog(
                    project,
                    titleText = "Describe ${resType.singularName.replaceFirstChar { it.uppercase() }}: $resName ($currentNamespace)",
                    contentText = description.ifBlank { "No describe output returned." },
                    onEditDeployment = if (resType == ResourceType.PODS && pod != null && pod.deploymentName.isNotBlank()) {
                        { openEditDeploymentDialog(deploymentName = pod.deploymentName, namespace = currentNamespace) }
                    } else if (resType == ResourceType.DEPLOYMENTS) {
                        { openEditDeploymentDialog(deploymentName = resName, namespace = currentNamespace) }
                    } else null
                )
                dialog.show()
            }
        })
    }

    private fun showSelectedResourceYamlOrDetails() {
        val resType = tableModel.getResourceType()
        val currentNs = namespaceComboBox.selectedItem as? String ?: getSelectedResourceNamespace() ?: "default"
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        val resName = getSelectedResourceName() ?: return

        when (resType) {
            ResourceType.CONFIG_MAPS -> showSelectedConfigMapDetails()
            ResourceType.SECRETS -> showSelectedSecretDetails()
            else -> {
                val dialog = ResourceYamlDialog(
                    project,
                    profile,
                    resType.singularName,
                    currentNs,
                    resName
                ) {
                    loadResources(resType, currentNs)
                }
                dialog.show()
            }
        }
    }

    private fun showSelectedResourceDetails() {
        val resType = tableModel.getResourceType()
        when (resType) {
            ResourceType.PODS -> showSelectedPodDetails()
            ResourceType.SERVICES -> showSelectedServiceDetails()
            ResourceType.CONFIG_MAPS -> showSelectedConfigMapDetails()
            ResourceType.SECRETS -> showSelectedSecretDetails()
            ResourceType.DEPLOYMENTS -> {
                val dep = getSelectedDeployment()
                if (dep != null) {
                    openEditDeploymentDialog(deploymentName = dep.name, namespace = dep.namespace)
                }
            }
            ResourceType.INGRESSES,
            ResourceType.STATEFUL_SETS,
            ResourceType.DAEMON_SETS,
            ResourceType.JOBS,
            ResourceType.CRON_JOBS,
            ResourceType.PERSISTENT_VOLUME_CLAIMS,
            ResourceType.EVENTS -> showSelectedResourceYamlOrDetails()
        }
    }

    private fun showSelectedSecretDetails() {
        val sec = getSelectedSecret() ?: return
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        val dialog = SecretDetailsDialog(
            project,
            profile,
            sec
        ) {
            loadResources(ResourceType.SECRETS, sec.namespace)
        }
        dialog.show()
    }

    private fun showSelectedJobLogs() {
        val job = getSelectedJob() ?: return
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Fetching Job Logs", false) {
            private var logs = ""
            private var isSuccess = false

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Fetching logs for job/${job.name}..."
                val client = KubernetesSshClient(profile)
                val cmdResult = client.executeCustomKubectlCommand("logs job/${job.name}", job.namespace, autoNamespace = true)
                isSuccess = cmdResult.isSuccess
                logs = if (isSuccess) cmdResult.output else cmdResult.error.ifBlank { cmdResult.output }
            }

            override fun onSuccess() {
                val dialog = PodDetailsDialog(
                    project,
                    titleText = "Job Logs: ${job.name} (${job.namespace})",
                    contentText = logs.ifBlank { "No logs available or empty output." }
                )
                dialog.show()
            }
        })
    }

    private fun triggerCronJobNow(cj: CronJobInfo) {
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        val confirm = Messages.showYesNoDialog(
            project,
            "Trigger manual execution of CronJob '${cj.name}' now in namespace '${cj.namespace}'?",
            "Run CronJob Now",
            Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Triggering CronJob ${cj.name}", false) {
            private var isSuccess = false
            private var outputMsg = ""

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Executing kubectl create job from cronjob/${cj.name}..."
                val client = KubernetesSshClient(profile)
                val cmdResult = client.triggerCronJob(cj.namespace, cj.name)
                isSuccess = cmdResult.isSuccess
                outputMsg = if (cmdResult.isSuccess) cmdResult.output else cmdResult.error.ifBlank { cmdResult.output }
            }

            override fun onSuccess() {
                if (isSuccess) {
                    statusLabel.text = "Triggered job for '${cj.name}'."
                    Messages.showInfoMessage(project, "Job triggered successfully:\n$outputMsg", "Job Triggered")
                    KubernetesDataCache.invalidateResourceType(profile.id, cj.namespace, ResourceType.JOBS)
                    KubernetesDataCache.invalidateResourceType(profile.id, cj.namespace, ResourceType.CRON_JOBS)
                } else {
                    Messages.showErrorDialog(project, "Failed to trigger job:\n$outputMsg", "Trigger Failed")
                }
            }
        })
    }

    private fun quickScaleStatefulSet(sts: StatefulSetInfo) {
        val initialReplicas = if (sts.replicas > 0) sts.replicas else 1
        val input = Messages.showInputDialog(
            project,
            "Enter desired replica count for StatefulSet '${sts.name}':",
            "Quick Scale StatefulSet",
            AllIcons.Nodes.Deploy,
            initialReplicas.toString(),
            null
        ) ?: return

        val newReplicas = input.trim().toIntOrNull()
        if (newReplicas == null || newReplicas < 0) {
            Messages.showErrorDialog(project, "Please enter a valid non-negative integer for replica count.", "Invalid Replica Count")
            return
        }

        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Scaling ${sts.name} to $newReplicas Replicas", false) {
            private var isSuccess = false
            private var outputMsg = ""

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Executing kubectl scale statefulset/${sts.name} --replicas=$newReplicas..."
                val client = KubernetesSshClient(profile)
                val result = client.scaleStatefulSet(sts.namespace, sts.name, newReplicas)
                isSuccess = result.isSuccess
                outputMsg = if (result.isSuccess) result.output else result.error.ifBlank { result.output }
            }

            override fun onSuccess() {
                if (isSuccess) {
                    statusLabel.text = "Scaled StatefulSet '${sts.name}' to $newReplicas replicas."
                    Messages.showInfoMessage(project, "StatefulSet '${sts.name}' scaled to $newReplicas replicas successfully:\n$outputMsg", "Scale StatefulSet")
                    KubernetesDataCache.invalidateResourceType(profile.id, sts.namespace, ResourceType.STATEFUL_SETS)
                    KubernetesDataCache.invalidateResourceType(profile.id, sts.namespace, ResourceType.PODS)
                    loadResources(ResourceType.STATEFUL_SETS, sts.namespace)
                } else {
                    Messages.showErrorDialog(project, "Failed to scale StatefulSet:\n$outputMsg", "Scale Failed")
                }
            }
        })
    }

    private fun restartStatefulSetRollout(sts: StatefulSetInfo) {
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        val confirm = Messages.showYesNoDialog(
            project,
            "Restart rollout for StatefulSet '${sts.name}' in namespace '${sts.namespace}'?",
            "Restart Rollout",
            Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Restarting Rollout for ${sts.name}", false) {
            private var isSuccess = false
            private var outputMsg = ""

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Executing kubectl rollout restart statefulset/${sts.name}..."
                val client = KubernetesSshClient(profile)
                val cmdResult = client.restartStatefulSetRollout(sts.namespace, sts.name)
                isSuccess = cmdResult.isSuccess
                outputMsg = if (cmdResult.isSuccess) cmdResult.output else cmdResult.error.ifBlank { cmdResult.output }
            }

            override fun onSuccess() {
                if (isSuccess) {
                    statusLabel.text = "Rollout restart triggered for StatefulSet '${sts.name}'."
                    Messages.showInfoMessage(project, "Rollout restarted successfully:\n$outputMsg", "Rollout Restart")
                    KubernetesDataCache.invalidateResourceType(profile.id, sts.namespace, ResourceType.STATEFUL_SETS)
                    KubernetesDataCache.invalidateResourceType(profile.id, sts.namespace, ResourceType.PODS)
                    loadResources(ResourceType.STATEFUL_SETS, sts.namespace)
                } else {
                    Messages.showErrorDialog(project, "Failed to restart rollout:\n$outputMsg", "Restart Failed")
                }
            }
        })
    }

    private fun restartDaemonSetRollout(ds: DaemonSetInfo) {
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        val confirm = Messages.showYesNoDialog(
            project,
            "Restart rollout for DaemonSet '${ds.name}' in namespace '${ds.namespace}'?",
            "Restart Rollout",
            Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Restarting Rollout for ${ds.name}", false) {
            private var isSuccess = false
            private var outputMsg = ""

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Executing kubectl rollout restart daemonset/${ds.name}..."
                val client = KubernetesSshClient(profile)
                val cmdResult = client.restartDaemonSetRollout(ds.namespace, ds.name)
                isSuccess = cmdResult.isSuccess
                outputMsg = if (cmdResult.isSuccess) cmdResult.output else cmdResult.error.ifBlank { cmdResult.output }
            }

            override fun onSuccess() {
                if (isSuccess) {
                    statusLabel.text = "Rollout restart triggered for DaemonSet '${ds.name}'."
                    Messages.showInfoMessage(project, "Rollout restarted successfully:\n$outputMsg", "Rollout Restart")
                    KubernetesDataCache.invalidateResourceType(profile.id, ds.namespace, ResourceType.DAEMON_SETS)
                    KubernetesDataCache.invalidateResourceType(profile.id, ds.namespace, ResourceType.PODS)
                    loadResources(ResourceType.DAEMON_SETS, ds.namespace)
                } else {
                    Messages.showErrorDialog(project, "Failed to restart rollout:\n$outputMsg", "Restart Failed")
                }
            }
        })
    }

    private fun describeNamedResource(kind: String, namespace: String, name: String) {
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Describing $kind $name", false) {
            private var description = ""
            private var isSuccess = false

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Describing $kind $name..."
                val client = KubernetesSshClient(profile)
                val cmdResult = client.describeResource(kind, namespace, name)
                isSuccess = cmdResult.isSuccess
                description = if (isSuccess) cmdResult.output else cmdResult.error.ifBlank { cmdResult.output }
            }

            override fun onSuccess() {
                val dialog = PodDetailsDialog(
                    project,
                    titleText = "Describe ${kind.replaceFirstChar { it.uppercase() }}: $name ($namespace)",
                    contentText = description.ifBlank { "No describe output returned." }
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

    private fun showSelectedServiceDetails() {
        val svc = getSelectedService() ?: return
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        val dialog = ResourceYamlDialog(
            project,
            profile,
            "service",
            svc.namespace,
            svc.name
        ) {
            loadResources(ResourceType.SERVICES, svc.namespace)
        }
        dialog.show()
    }

    private fun showSelectedConfigMapDetails() {
        val cm = getSelectedConfigMap() ?: return
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        val dialog = ConfigMapDetailsDialog(
            project,
            profile,
            cm
        ) {
            loadResources(ResourceType.CONFIG_MAPS, cm.namespace)
        }
        dialog.show()
    }

    private fun restartDeploymentRollout(dep: DeploymentInfo) {
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        val confirm = Messages.showYesNoDialog(
            project,
            "Restart rollout for deployment '${dep.name}' in namespace '${dep.namespace}'?",
            "Restart Rollout",
            Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Restarting Rollout for ${dep.name}", false) {
            private var isSuccess = false
            private var outputMsg = ""

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Executing kubectl rollout restart deployment/${dep.name}..."
                val client = KubernetesSshClient(profile)
                val cmdResult = client.restartDeploymentRollout(dep.namespace, dep.name)
                isSuccess = cmdResult.isSuccess
                outputMsg = if (cmdResult.isSuccess) cmdResult.output else cmdResult.error.ifBlank { cmdResult.output }
            }

            override fun onSuccess() {
                if (isSuccess) {
                    statusLabel.text = "Rollout restart triggered for '${dep.name}'."
                    Messages.showInfoMessage(project, "Rollout restarted successfully:\n$outputMsg", "Rollout Restart")
                    KubernetesDataCache.invalidateResourceType(profile.id, dep.namespace, ResourceType.DEPLOYMENTS)
                    KubernetesDataCache.invalidateResourceType(profile.id, dep.namespace, ResourceType.PODS)
                    loadResources(ResourceType.DEPLOYMENTS, dep.namespace)
                } else {
                    Messages.showErrorDialog(project, "Failed to restart rollout:\n$outputMsg", "Restart Failed")
                }
            }
        })
    }

    fun openEditDeploymentDialog(deploymentName: String? = null, namespace: String? = null) {
        val targetNs = if (!namespace.isNullOrBlank() && namespace != "All Namespaces") {
            namespace
        } else {
            val selected = namespaceComboBox.selectedItem as? String
            if (!selected.isNullOrBlank() && selected != "All Namespaces") selected else "default"
        }
        val dialog = EditDeploymentDialog(project, targetNs, deploymentName) {
            val currentNs = namespaceComboBox.selectedItem as? String ?: "default"
            val currentType = resourceComboBox.selectedItem as? ResourceType ?: ResourceType.PODS
            loadResources(currentType, currentNs)
        }
        dialog.show()
    }

    override fun dispose() {
        isDisposed = true
        refreshTimer?.stop()
        refreshTimer = null
    }
}
