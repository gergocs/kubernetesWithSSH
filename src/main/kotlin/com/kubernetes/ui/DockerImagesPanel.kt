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
import com.kubernetes.model.DockerImageInfo
import com.kubernetes.model.SshProfile
import com.kubernetes.settings.KubernetesSshConfigurable
import com.kubernetes.settings.KubernetesSshSettings
import com.kubernetes.ssh.KubernetesSshClient
import com.kubernetes.ui.dialogs.DockerImageDetailsDialog
import com.kubernetes.ui.dialogs.DockerTagDialog
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

class DockerImagesPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    var onProfileChanged: ((SshProfile) -> Unit)? = null

    private val tableModel = DockerImageTableModel()
    private val table = JBTable(tableModel)

    private val profileComboBox = ComboBox<SshProfile>()
    private val searchField = SearchTextField()
    private val statusLabel = JBLabel("Ready")

    private var isUpdatingProfiles = false
    private val isFetching = AtomicBoolean(false)
    private var isDisposed = false
    private var refreshTimer: Timer? = null

    init {
        setupUI()
        refreshProfiles()
        loadDockerImages()
        startAutoRefresh()
    }

    private fun setupUI() {
        border = JBUI.Borders.empty(4)

        // Top Controls Panel
        val topPanel = JPanel(BorderLayout())

        val profilePanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            profileComboBox.toolTipText = "Select SSH Profile"
            profileComboBox.prototypeDisplayValue = SshProfile(name = "production-server-profile")
            profileComboBox.addActionListener {
                if (!isUpdatingProfiles) {
                    val selectedProf = profileComboBox.selectedItem as? SshProfile
                    if (selectedProf != null) {
                        val settings = KubernetesSshSettings.getInstance(project)
                        settings.state.selectProfile(selectedProf.id)
                        onProfileChanged?.invoke(selectedProf)
                        loadDockerImages()
                    }
                }
            }
            add(profileComboBox)
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
            add(object : AnAction("Refresh Docker Images", "Refresh docker image list", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    loadDockerImages()
                }
            })
            addSeparator()
            add(object : AnAction("Create Tag...", "Create a new tag for the selected image (docker tag)", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) {
                    createTagSelectedImage()
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = table.selectedRow >= 0
                }
            })
            add(object : AnAction("Change Tag...", "Change tag for the selected image", AllIcons.Actions.Edit) {
                override fun actionPerformed(e: AnActionEvent) {
                    changeTagSelectedImage()
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = table.selectedRow >= 0
                }
            })
            addSeparator()
            add(object : AnAction("Inspect Image", "Inspect selected docker image", AllIcons.Actions.Show) {
                override fun actionPerformed(e: AnActionEvent) {
                    inspectSelectedImage()
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = table.selectedRow >= 0
                }
            })
            add(object : AnAction("Delete Image", "Remove the selected docker image (docker rmi)", AllIcons.Actions.Cancel) {
                override fun actionPerformed(e: AnActionEvent) {
                    deleteSelectedImage(force = false)
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = table.selectedRow >= 0
                }
            })
            addSeparator()
            add(object : AnAction("SSH Settings", "Configure remote SSH connection", AllIcons.General.Settings) {
                override fun actionPerformed(e: AnActionEvent) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, KubernetesSshConfigurable::class.java)
                    refreshProfiles()
                    val active = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
                    onProfileChanged?.invoke(active)
                    loadDockerImages()
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
            add(profilePanel, BorderLayout.WEST)
            add(searchField, BorderLayout.CENTER)
            add(toolbar.component, BorderLayout.EAST)
        }

        topPanel.add(controlsAndSearch, BorderLayout.CENTER)
        add(topPanel, BorderLayout.NORTH)

        // Table Setup
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.rowSorter = TableRowSorter(tableModel)
        table.fillsViewportHeight = true
        table.autoResizeMode = JBTable.AUTO_RESIZE_ALL_COLUMNS

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && table.selectedRow >= 0) {
                    inspectSelectedImage()
                }
            }

            override fun mousePressed(e: MouseEvent) {
                showContextMenu(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                showContextMenu(e)
            }

            private fun showContextMenu(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val row = table.rowAtPoint(e.point)
                if (row >= 0 && !table.isRowSelected(row)) {
                    table.setRowSelectionInterval(row, row)
                }
                if (table.selectedRow < 0) return

                val menu = JPopupMenu()
                menu.add(JMenuItem("Create Tag...").apply {
                    icon = AllIcons.General.Add
                    addActionListener { createTagSelectedImage() }
                })
                menu.add(JMenuItem("Change Tag...").apply {
                    icon = AllIcons.Actions.Edit
                    addActionListener { changeTagSelectedImage() }
                })
                menu.addSeparator()
                menu.add(JMenuItem("Inspect Image").apply {
                    icon = AllIcons.Actions.Show
                    addActionListener { inspectSelectedImage() }
                })
                menu.add(JMenuItem("Delete Image (rmi)").apply {
                    icon = AllIcons.Actions.Cancel
                    addActionListener { deleteSelectedImage(force = false) }
                })
                menu.add(JMenuItem("Force Delete Image (rmi -f)").apply {
                    icon = AllIcons.Actions.Cancel
                    addActionListener { deleteSelectedImage(force = true) }
                })
                menu.addSeparator()
                menu.add(JMenuItem("Copy Full Name (Repository:Tag)").apply {
                    icon = AllIcons.Actions.Copy
                    addActionListener {
                        getSelectedImage()?.let { img ->
                            val full = if (img.tag.isNotBlank() && img.tag != "<none>") "${img.repository}:${img.tag}" else img.repository
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(full), null)
                        }
                    }
                })
                menu.add(JMenuItem("Copy Image ID").apply {
                    icon = AllIcons.Actions.Copy
                    addActionListener {
                        getSelectedImage()?.let { img ->
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(img.imageId), null)
                        }
                    }
                })
                menu.add(JMenuItem("Copy Repository").apply {
                    icon = AllIcons.Actions.Copy
                    addActionListener {
                        getSelectedImage()?.let { img ->
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(img.repository), null)
                        }
                    }
                })
                menu.add(JMenuItem("Copy Tag").apply {
                    icon = AllIcons.Actions.Copy
                    addActionListener {
                        getSelectedImage()?.let { img ->
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(img.tag), null)
                        }
                    }
                })
                menu.show(e.component, e.x, e.y)
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

    private fun getSelectedImage(): DockerImageInfo? {
        val selectedRow = table.selectedRow
        if (selectedRow < 0) return null
        val modelRow = table.convertRowIndexToModel(selectedRow)
        return tableModel.getImageAt(modelRow)
    }

    fun filterByImage(query: String) {
        searchField.text = query
        tableModel.setFilter(query)
        updateStatusLabel()
        if (table.rowCount > 0) {
            table.setRowSelectionInterval(0, 0)
        }
    }

    fun syncProfile(profile: SshProfile) {
        refreshProfiles(selectedProfileId = profile.id)
        loadDockerImages()
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

    fun autoRefreshDockerImages() {
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        if (profile.host.isBlank() || !isFetching.compareAndSet(false, true)) {
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = KubernetesSshClient(profile)
                val images = client.getDockerImages()
                ApplicationManager.getApplication().invokeLater {
                    if (!isDisposed && !project.isDisposed) {
                        val selectedImageId = getSelectedImage()?.imageId
                        val selectedRepoTag = getSelectedImage()?.let { "${it.repository}:${it.tag}" }
                        tableModel.setImages(images)
                        restoreImageSelection(selectedImageId, selectedRepoTag)
                        updateStatusLabel()
                    }
                }
            } catch (_: Exception) {
                // Ignore transient errors on background auto-refresh
            } finally {
                isFetching.set(false)
            }
        }
    }

    private fun restoreImageSelection(imageId: String?, repoTag: String?) {
        if (imageId == null && repoTag == null) return
        for (i in 0 until tableModel.rowCount) {
            val img = tableModel.getImageAt(i)
            if (img != null && (img.imageId == imageId || "${img.repository}:${img.tag}" == repoTag)) {
                val viewRow = table.convertRowIndexToView(i)
                if (viewRow >= 0 && viewRow < table.rowCount) {
                    table.setRowSelectionInterval(viewRow, viewRow)
                }
                break
            }
        }
    }

    private fun startAutoRefresh() {
        refreshTimer = Timer(5000) {
            if (isDisposed || project.isDisposed) {
                refreshTimer?.stop()
                return@Timer
            }
            if (!isUpdatingProfiles) {
                autoRefreshDockerImages()
            }
        }.apply {
            isRepeats = true
            start()
        }
    }

    fun loadDockerImages() {
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        if (profile.host.isBlank()) {
            tableModel.setImages(emptyList())
            statusLabel.text = "SSH not configured for profile '${profile.name}'. Click Settings icon to configure."
            return
        }

        statusLabel.text = "[${profile.name}] Loading docker images from ${profile.host}..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading Docker Images", false) {
            private var images: List<DockerImageInfo> = emptyList()
            private var errorMessage: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Connecting to ${profile.host} and querying docker images..."
                val client = KubernetesSshClient(profile)
                try {
                    images = client.getDockerImages()
                } catch (e: Exception) {
                    errorMessage = e.message ?: "Unknown error"
                }
            }

            override fun onSuccess() {
                if (errorMessage != null) {
                    statusLabel.text = "Error fetching docker images: $errorMessage"
                    tableModel.setImages(emptyList())
                } else {
                    val selectedImageId = getSelectedImage()?.imageId
                    val selectedRepoTag = getSelectedImage()?.let { "${it.repository}:${it.tag}" }
                    tableModel.setImages(images)
                    restoreImageSelection(selectedImageId, selectedRepoTag)
                    updateStatusLabel()
                }
            }
        })
    }

    private fun updateStatusLabel() {
        val count = tableModel.rowCount
        val total = tableModel.getTotalCount()
        val profileName = (profileComboBox.selectedItem as? SshProfile)?.name ?: "Default"
        val filter = searchField.text.trim()
        val filterInfo = if (filter.isNotBlank()) " (filtered from $total)" else ""
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        statusLabel.text = "Profile: $profileName | $count images$filterInfo | Last updated: $time"
    }

    private fun createTagSelectedImage() {
        val image = getSelectedImage() ?: return
        val dialog = DockerTagDialog(project, image, isChangeTag = false)
        if (!dialog.showAndGet()) return

        val target = dialog.getTargetFullImageName()
        val source = dialog.getSourceIdentifier()
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Creating Docker Tag", false) {
            private var isSuccess = false
            private var errorOutput = ""

            override fun run(indicator: ProgressIndicator) {
                val client = KubernetesSshClient(profile)
                try {
                    val result = client.tagDockerImage(source, target)
                    isSuccess = result.isSuccess
                    errorOutput = result.error.ifBlank { result.output }
                } catch (e: Exception) {
                    isSuccess = false
                    errorOutput = e.message ?: "Execution failed"
                }
            }

            override fun onSuccess() {
                if (isSuccess) {
                    Messages.showInfoMessage(project, "Successfully tagged '$source' as '$target'", "Docker Tag Created")
                    loadDockerImages()
                } else {
                    Messages.showErrorDialog(project, "Failed to create tag '$target':\n$errorOutput", "Docker Tag Failed")
                }
            }
        })
    }

    private fun changeTagSelectedImage() {
        val image = getSelectedImage() ?: return
        val dialog = DockerTagDialog(project, image, isChangeTag = true)
        if (!dialog.showAndGet()) return

        val target = dialog.getTargetFullImageName()
        val source = dialog.getSourceIdentifier()
        val oldTagToRemove = dialog.getOldTagToRemove()

        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Changing Docker Tag", false) {
            private var isSuccess = false
            private var errorOutput = ""

            override fun run(indicator: ProgressIndicator) {
                val client = KubernetesSshClient(profile)
                try {
                    val result = client.changeDockerImageTag(source, target, oldTagToRemove)
                    isSuccess = result.isSuccess
                    errorOutput = result.error.ifBlank { result.output }
                } catch (e: Exception) {
                    isSuccess = false
                    errorOutput = e.message ?: "Execution failed"
                }
            }

            override fun onSuccess() {
                if (isSuccess) {
                    val msg = if (oldTagToRemove != null) {
                        "Successfully retagged to '$target' and removed '$oldTagToRemove'."
                    } else {
                        "Successfully tagged as '$target'."
                    }
                    Messages.showInfoMessage(project, msg, "Docker Tag Changed")
                    loadDockerImages()
                } else {
                    Messages.showErrorDialog(project, "Failed to change tag to '$target':\n$errorOutput", "Change Tag Failed")
                }
            }
        })
    }

    private fun inspectSelectedImage() {
        val image = getSelectedImage() ?: return
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        val target = if (image.repository.isNotBlank() && image.tag.isNotBlank() && image.tag != "<none>") {
            "${image.repository}:${image.tag}"
        } else {
            image.imageId
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Inspecting Docker Image", false) {
            private var inspectOutput = ""
            private var isSuccess = false

            override fun run(indicator: ProgressIndicator) {
                val client = KubernetesSshClient(profile)
                try {
                    val result = client.inspectDockerImage(target)
                    isSuccess = result.isSuccess
                    inspectOutput = if (isSuccess) result.output else result.error.ifBlank { result.output }
                } catch (e: Exception) {
                    isSuccess = false
                    inspectOutput = e.message ?: "Inspect failed"
                }
            }

            override fun onSuccess() {
                val dialog = DockerImageDetailsDialog(
                    project,
                    titleText = "Docker Image: $target",
                    contentText = inspectOutput.ifBlank { "No inspect details available." }
                )
                dialog.show()
            }
        })
    }

    private fun deleteSelectedImage(force: Boolean) {
        val image = getSelectedImage() ?: return
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        val targetDisplay = if (image.repository.isNotBlank() && image.tag.isNotBlank() && image.tag != "<none>") {
            "${image.repository}:${image.tag}"
        } else {
            image.imageId
        }

        val targetIdentifier = if (image.repository.isNotBlank() && image.tag.isNotBlank() && image.tag != "<none>") {
            "${image.repository}:${image.tag}"
        } else {
            image.imageId
        }

        val forceMsg = if (force) " (force removal -f)" else ""
        val confirm = Messages.showYesNoDialog(
            project,
            "Are you sure you want to remove docker image '$targetDisplay'$forceMsg?",
            if (force) "Force Delete Docker Image" else "Delete Docker Image",
            Messages.getWarningIcon()
        )
        if (confirm != Messages.YES) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Deleting Docker Image", false) {
            private var deleteSuccess = false
            private var errorOutput = ""

            override fun run(indicator: ProgressIndicator) {
                val client = KubernetesSshClient(profile)
                try {
                    val result = client.deleteDockerImage(targetIdentifier, force)
                    deleteSuccess = result.isSuccess
                    errorOutput = result.error.ifBlank { result.output }
                } catch (e: Exception) {
                    deleteSuccess = false
                    errorOutput = e.message ?: "Delete failed"
                }
            }

            override fun onSuccess() {
                if (deleteSuccess) {
                    statusLabel.text = "Image '$targetDisplay' deleted successfully."
                    loadDockerImages()
                } else {
                    Messages.showErrorDialog(
                        project,
                        "Failed to delete docker image '$targetDisplay':\n$errorOutput",
                        "Delete Docker Image Failed"
                    )
                }
            }
        })
    }

    override fun dispose() {
        isDisposed = true
        refreshTimer?.stop()
        refreshTimer = null
    }
}
