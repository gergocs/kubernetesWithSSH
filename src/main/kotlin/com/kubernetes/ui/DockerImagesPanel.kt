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
import com.kubernetes.model.DockerImageInfo
import com.kubernetes.model.SshProfile
import com.kubernetes.settings.KubernetesSshConfigurable
import com.kubernetes.settings.KubernetesSshSettings
import com.kubernetes.ssh.KubernetesSshClient
import com.kubernetes.ui.dialogs.DockerBuildDialog
import com.kubernetes.ui.dialogs.DockerImageDetailsDialog
import com.kubernetes.ui.dialogs.DockerTagDialog
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
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

class DockerImagesPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    var onProfileChanged: ((SshProfile) -> Unit)? = null
    var onNavigateToConsole: ((String) -> Unit)? = null

    private val tableModel = DockerImageTableModel()
    private val table = JBTable(tableModel)

    private val profileComboBox = ComboBox<SshProfile>()
    private val searchField = SearchTextField()
    private val statusLabel = JBLabel("Ready")
    private val summaryLabel = JBLabel("")

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
        border = JBUI.Borders.empty(2)

        // Top Controls Panel (Two-tier header for clean organization)
        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

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

        // Row 1: Action Toolbar on the left, Search field on the right
        searchField.textEditor.emptyText.text = "Search images..."
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

        // Row 2: Profile Selector with custom icons
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
                    loadDockerImages()
                }
            }
        }

        val selectorsPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(3, 4, 5, 4)
            )

            val leftSelectors = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                add(JBLabel("Profile:").apply { font = font.deriveFont(Font.BOLD) })
                add(profileComboBox)
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

        // Table Setup
        table.apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            rowSorter = TableRowSorter(tableModel)
            fillsViewportHeight = true
            autoResizeMode = JBTable.AUTO_RESIZE_ALL_COLUMNS
            rowHeight = JBUI.scale(24)
            setShowGrid(false)
            setShowHorizontalLines(true)
            gridColor = JBColor(Color(235, 235, 235), Color(50, 50, 50))
            emptyText.text = "No Docker images found."
            setDefaultRenderer(Any::class.java, DockerImageCellRenderer())
        }

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
                val img = getSelectedImage() ?: return

                val menu = JPopupMenu()

                // 1. Primary Inspect & Tag Actions
                menu.add(JMenuItem("Inspect Image Details...").apply {
                    icon = AllIcons.Actions.Show
                    font = font.deriveFont(Font.BOLD)
                    addActionListener { inspectSelectedImage() }
                })
                menu.add(JMenuItem("Create New Tag...").apply {
                    icon = AllIcons.General.Add
                    addActionListener { createTagSelectedImage() }
                })
                menu.add(JMenuItem("Change / Re-tag Image...").apply {
                    icon = AllIcons.Actions.Edit
                    addActionListener { changeTagSelectedImage() }
                })

                menu.addSeparator()

                // Build from source folder action
                menu.add(JMenuItem("Build from Source...").apply {
                    icon = AllIcons.General.Gear
                    font = font.deriveFont(Font.BOLD)
                    addActionListener { buildImageFromSource(img) }
                })

                val fullImageName = if (img.tag.isNotBlank() && img.tag != "<none>") "${img.repository}:${img.tag}" else img.repository

                menu.add(JMenuItem("Open in Kubectl / Shell Console").apply {
                    icon = AllIcons.Nodes.Console
                    addActionListener {
                        onNavigateToConsole?.invoke("docker inspect ${img.imageId.ifBlank { fullImageName }}")
                    }
                })

                menu.addSeparator()

                // 2. Copy Submenu
                val copyMenu = JMenu("Copy").apply {
                    icon = AllIcons.Actions.Copy
                }
                copyMenu.add(JMenuItem("Copy Full Name ($fullImageName)").apply {
                    icon = AllIcons.Actions.Copy
                    addActionListener {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(fullImageName), null)
                        statusLabel.text = "Copied full image name '$fullImageName' to clipboard."
                    }
                })
                copyMenu.add(JMenuItem("Copy Image ID (${img.imageId})").apply {
                    icon = AllIcons.Actions.Copy
                    addActionListener {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(img.imageId), null)
                        statusLabel.text = "Copied image ID '${img.imageId}' to clipboard."
                    }
                })
                copyMenu.add(JMenuItem("Copy Repository (${img.repository})").apply {
                    icon = AllIcons.Actions.Copy
                    addActionListener {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(img.repository), null)
                        statusLabel.text = "Copied repository '${img.repository}' to clipboard."
                    }
                })
                copyMenu.add(JMenuItem("Copy Tag (${img.tag})").apply {
                    icon = AllIcons.Actions.Copy
                    addActionListener {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(img.tag), null)
                        statusLabel.text = "Copied tag '${img.tag}' to clipboard."
                    }
                })
                copyMenu.addSeparator()
                copyMenu.add(JMenuItem("Copy 'docker run' Command").apply {
                    icon = AllIcons.Actions.Copy
                    addActionListener {
                        val containerName = img.repository.substringAfterLast("/").substringBefore(":")
                        val cmd = "docker run -d --name $containerName $fullImageName"
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(cmd), null)
                        statusLabel.text = "Copied docker run command to clipboard."
                    }
                })
                copyMenu.add(JMenuItem("Copy 'docker pull' Command").apply {
                    icon = AllIcons.Actions.Copy
                    addActionListener {
                        val cmd = "docker pull $fullImageName"
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(cmd), null)
                        statusLabel.text = "Copied docker pull command to clipboard."
                    }
                })
                copyMenu.add(JMenuItem("Copy 'docker push' Command").apply {
                    icon = AllIcons.Actions.Copy
                    addActionListener {
                        val cmd = "docker push $fullImageName"
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(cmd), null)
                        statusLabel.text = "Copied docker push command to clipboard."
                    }
                })
                copyMenu.add(JMenuItem("Copy 'docker inspect' Command").apply {
                    icon = AllIcons.Actions.Copy
                    addActionListener {
                        val cmd = "docker inspect ${img.imageId.ifBlank { fullImageName }}"
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(cmd), null)
                        statusLabel.text = "Copied docker inspect command to clipboard."
                    }
                })
                copyMenu.add(JMenuItem("Copy 'docker rmi' Command").apply {
                    icon = AllIcons.Actions.Copy
                    addActionListener {
                        val cmd = "docker rmi $fullImageName"
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(cmd), null)
                        statusLabel.text = "Copied docker rmi command to clipboard."
                    }
                })
                menu.add(copyMenu)

                menu.addSeparator()

                // 3. Delete Submenu
                val deleteMenu = JMenu("Delete Image").apply {
                    icon = AllIcons.Actions.Cancel
                }
                deleteMenu.add(JMenuItem("Delete Image (docker rmi)...").apply {
                    icon = AllIcons.Actions.Cancel
                    addActionListener { deleteSelectedImage(force = false) }
                })
                deleteMenu.add(JMenuItem("Force Delete Image (docker rmi -f)...").apply {
                    icon = AllIcons.Actions.ForceRefresh
                    addActionListener { deleteSelectedImage(force = true) }
                })
                menu.add(deleteMenu)

                menu.show(e.component, e.x, e.y)
            }
        })

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
                KubernetesDataCache.putDockerImages(profile.id, images)
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

        // 1. Instant cache rendering
        val cached = KubernetesDataCache.getDockerImages(profile.id)
        if (cached != null) {
            val selectedImageId = getSelectedImage()?.imageId
            val selectedRepoTag = getSelectedImage()?.let { "${it.repository}:${it.tag}" }
            tableModel.setImages(cached)
            restoreImageSelection(selectedImageId, selectedRepoTag)
            updateStatusLabel()

            // Background revalidation
            autoRefreshDockerImages()
            return
        }

        // 2. Background load if not cached
        statusLabel.text = "[${profile.name}] Loading docker images from ${profile.host}..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading Docker Images", false) {
            private var images: List<DockerImageInfo> = emptyList()
            private var errorMessage: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Connecting to ${profile.host} and querying docker images..."
                val client = KubernetesSshClient(profile)
                try {
                    images = client.getDockerImages()
                    KubernetesDataCache.putDockerImages(profile.id, images)
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
        summaryLabel.text = "$count images$filterInfo"
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
                    KubernetesDataCache.invalidateDockerImages(profile.id)
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
                    KubernetesDataCache.invalidateDockerImages(profile.id)
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

    private fun buildImageFromSource(image: DockerImageInfo) {
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        if (profile.host.isBlank()) {
            Messages.showWarningDialog(project, "SSH not configured. Please configure the SSH profile first.", "SSH Not Configured")
            return
        }

        // Get the image key for looking up stored source folder
        val imageKey = if (image.repository.isNotBlank() && image.tag.isNotBlank() && image.tag != "<none>") {
            "${image.repository}:${image.tag}"
        } else {
            image.repository
        }

        // Check if there's already a stored source folder for this image
        val existingSourceFolder = profile.dockerImageSourceFolders[imageKey]

        val dialog = DockerBuildDialog(project, image, existingSourceFolder)
        if (!dialog.showAndGet()) return

        val sourceFolder = dialog.getSourceFolder()
        val targetTag = dialog.getTargetTag()
        val dockerfilePath = dialog.getDockerfilePath()
        val targetImageName = dialog.getTargetImageName()

        // Save the source folder mapping for future use
        profile.dockerImageSourceFolders[imageKey] = sourceFolder
        KubernetesSshSettings.getInstance(project).state.selectProfile(profile.id)

        val client = KubernetesSshClient(profile)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Building Docker Image", false) {
            private var buildSuccess = false
            private var errorOutput = ""

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Building docker image from '$sourceFolder' on ${profile.host}..."
                try {
                    val result = client.buildDockerImage(sourceFolder, image.repository.ifBlank { "untagged-image" }, targetTag, dockerfilePath)
                    buildSuccess = result.isSuccess
                    errorOutput = result.error.ifBlank { result.output }
                } catch (e: Exception) {
                    buildSuccess = false
                    errorOutput = e.message ?: "Build failed"
                }
            }

            override fun onSuccess() {
                if (buildSuccess) {
                    Messages.showInfoMessage(
                        project,
                        "Successfully built docker image '$targetImageName' from '$sourceFolder'.",
                        "Docker Build Successful"
                    )
                    statusLabel.text = "Built image '$targetImageName' from '$sourceFolder'."
                    KubernetesDataCache.invalidateDockerImages(profile.id)
                    loadDockerImages()
                } else {
                    Messages.showErrorDialog(
                        project,
                        "Failed to build docker image:\n$errorOutput",
                        "Docker Build Failed"
                    )
                }
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
                    KubernetesDataCache.invalidateDockerImages(profile.id)
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

class DockerImageCellRenderer : DefaultTableCellRenderer() {
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

        if (column == 0) {
            label.icon = AllIcons.Nodes.Artifact
            if (!isSelected) {
                label.font = label.font.deriveFont(Font.BOLD)
            }
        } else if (columnName.equals("Tag", ignoreCase = true)) {
            if (text == "<none>") {
                if (!isSelected) label.foreground = JBColor.GRAY
                label.font = label.font.deriveFont(Font.ITALIC)
            } else {
                label.font = label.font.deriveFont(Font.BOLD)
            }
        } else if (columnName.equals("Image ID", ignoreCase = true)) {
            label.font = Font(Font.MONOSPACED, Font.PLAIN, label.font.size)
        }

        return label
    }
}
