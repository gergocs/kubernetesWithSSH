package com.kubernetes.ui.dialogs

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.kubernetes.cache.KubernetesDataCache
import com.kubernetes.model.DeploymentInfo
import com.kubernetes.model.ResourceType
import com.kubernetes.settings.KubernetesSshSettings
import com.kubernetes.ssh.KubernetesParser
import com.kubernetes.ssh.KubernetesSshClient
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities

class EditDeploymentDialog(
    private val project: Project,
    private val namespace: String,
    private val initialDeploymentName: String? = null,
    private val onDeploymentUpdated: (() -> Unit)? = null
) : DialogWrapper(project, true) {

    private val deploymentComboBox = ComboBox<String>()
    private val replicasSpinner = JSpinner(SpinnerNumberModel(1, 0, 1000, 1))
    private val scaleButton = JButton("Scale", AllIcons.Actions.MoveUp)

    private val containerComboBox = ComboBox<String>()
    private val imageTextField = JBTextField(24)
    private val updateImageButton = JButton("Set Image", AllIcons.Actions.SetDefault)

    private val restartRolloutButton = JButton("Restart Rollout", AllIcons.Actions.Restart)
    private val rolloutStatusButton = JButton("Rollout Status", AllIcons.Actions.CheckOut)

    private val yamlTextArea = JBTextArea().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        tabSize = 2
        isEditable = true
    }

    private val statusLabel = JBLabel("Loading deployment details...")
    private val isBusy = AtomicBoolean(false)

    private var availableDeployments: List<DeploymentInfo> = emptyList()
    private var isUpdatingCombo = false

    init {
        title = if (!initialDeploymentName.isNullOrBlank()) {
            "Edit Deployment: $initialDeploymentName ($namespace)"
        } else {
            "Edit Deployment ($namespace)"
        }
        init()
        loadDeploymentsAndSelect(initialDeploymentName)
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout(0, 8))
        mainPanel.preferredSize = Dimension(880, 620)
        mainPanel.border = JBUI.Borders.empty(4)

        // Top Control Area
        val topControlsPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(2, 4)
            gridx = 0
            gridy = 0
            weightx = 1.0
        }

        // Row 1: Deployment selector + quick status
        val selectorPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(JBLabel("Deployment:"))
            deploymentComboBox.prototypeDisplayValue = "my-sample-deployment-name-very-long"
            deploymentComboBox.addActionListener {
                if (!isUpdatingCombo) {
                    val selected = deploymentComboBox.selectedItem as? String
                    if (!selected.isNullOrBlank()) {
                        loadDeploymentYaml(selected)
                    }
                }
            }
            add(deploymentComboBox)

            val reloadListBtn = JButton(AllIcons.Actions.Refresh).apply {
                toolTipText = "Refresh deployments list"
                addActionListener {
                    val current = deploymentComboBox.selectedItem as? String
                    loadDeploymentsAndSelect(current)
                }
            }
            add(reloadListBtn)

            add(JBLabel("  |  Namespace: <b>$namespace</b>"))
        }
        topControlsPanel.add(selectorPanel, gbc)

        // Row 2: Quick actions (Scale, Set Image, Rollout)
        gbc.gridy = 1
        val quickActionsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            border = JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1, 0, 1, 0)

            // Scale
            add(JBLabel("Scale Replicas:"))
            replicasSpinner.preferredSize = Dimension(60, replicasSpinner.preferredSize.height)
            add(replicasSpinner)
            scaleButton.addActionListener { scaleSelectedDeployment() }
            add(scaleButton)

            add(JBLabel("  |  "))

            // Container Image
            add(JBLabel("Container:"))
            containerComboBox.prototypeDisplayValue = "main-container"
            containerComboBox.addActionListener {
                val selectedContainer = containerComboBox.selectedItem as? String
                val currentDep = getSelectedDeploymentInfo()
                if (currentDep != null && selectedContainer != null) {
                    val index = currentDep.containers.indexOf(selectedContainer)
                    if (index >= 0 && index < currentDep.images.size) {
                        imageTextField.text = currentDep.images[index]
                    }
                }
            }
            add(containerComboBox)

            imageTextField.emptyText.text = "e.g. nginx:1.25-alpine or /path:myapp:latest or C:\\path:myapp:latest"
            add(imageTextField)

            updateImageButton.addActionListener { updateContainerImage() }
            add(updateImageButton)

            add(JBLabel("  |  "))

            // Rollout actions
            restartRolloutButton.addActionListener { restartRollout() }
            add(restartRolloutButton)

            rolloutStatusButton.addActionListener { showRolloutStatus() }
            add(rolloutStatusButton)
        }
        topControlsPanel.add(quickActionsPanel, gbc)

        mainPanel.add(topControlsPanel, BorderLayout.NORTH)

        // Center Area: Editor with toolbar
        val editorPanel = JPanel(BorderLayout(0, 4))
        val editorToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(JBLabel("Deployment YAML Manifest (Edit and Apply):"))

            val reloadYamlBtn = JButton("Reload YAML", AllIcons.Actions.Refresh).apply {
                toolTipText = "Discard changes and reload latest YAML from cluster"
                addActionListener {
                    val selected = deploymentComboBox.selectedItem as? String
                    if (!selected.isNullOrBlank()) {
                        loadDeploymentYaml(selected)
                    }
                }
            }
            add(reloadYamlBtn)

            val dryRunBtn = JButton("Dry-Run Validate", AllIcons.Actions.Checked).apply {
                toolTipText = "Validate YAML syntax and schema against Kubernetes cluster without applying"
                addActionListener {
                    dryRunValidateYaml()
                }
            }
            add(dryRunBtn)

            val copyBtn = JButton("Copy YAML", AllIcons.Actions.Copy).apply {
                toolTipText = "Copy entire YAML manifest to clipboard"
                addActionListener {
                    val text = yamlTextArea.text
                    if (text.isNotBlank()) {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                        statusLabel.text = "YAML copied to clipboard."
                    }
                }
            }
            add(copyBtn)
        }
        editorPanel.add(editorToolbar, BorderLayout.NORTH)

        val scrollPane = JBScrollPane(yamlTextArea)
        editorPanel.add(scrollPane, BorderLayout.CENTER)

        mainPanel.add(editorPanel, BorderLayout.CENTER)

        // Bottom status panel
        val bottomPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 2)
            add(statusLabel, BorderLayout.WEST)
        }
        mainPanel.add(bottomPanel, BorderLayout.SOUTH)

        return mainPanel
    }

    override fun createActions(): Array<Action> {
        val applyAction = object : DialogWrapperAction("Apply Changes") {
            init {
                putValue(Action.SMALL_ICON, AllIcons.Actions.Commit)
            }
            override fun doAction(e: java.awt.event.ActionEvent?) {
                applyEditedYaml()
            }
        }
        return arrayOf(applyAction, cancelAction)
    }

    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            ApplicationManager.getApplication().invokeLater(action, ModalityState.any())
        }
    }

    private fun getSelectedDeploymentInfo(): DeploymentInfo? {
        val name = deploymentComboBox.selectedItem as? String ?: return null
        return availableDeployments.find { it.name == name }
    }

    private fun loadDeploymentsAndSelect(toSelect: String?) {
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        val cached = KubernetesDataCache.getDeployments(profile.id, namespace)

        if (!cached.isNullOrEmpty()) {
            availableDeployments = cached
            isUpdatingCombo = true
            deploymentComboBox.removeAllItems()
            for (dep in cached) {
                deploymentComboBox.addItem(dep.name)
            }

            val targetName = when {
                !toSelect.isNullOrBlank() && cached.any { it.name == toSelect } -> toSelect
                !toSelect.isNullOrBlank() -> {
                    deploymentComboBox.addItem(toSelect)
                    toSelect
                }
                cached.isNotEmpty() -> cached.first().name
                else -> null
            }

            if (targetName != null) {
                deploymentComboBox.selectedItem = targetName
            }
            isUpdatingCombo = false

            if (targetName != null) {
                loadDeploymentYaml(targetName)
            }
            return
        }

        if (!isBusy.compareAndSet(false, true)) return
        statusLabel.text = "Fetching deployments in namespace '$namespace'..."

        val client = KubernetesSshClient(profile)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val deployments = client.getDeployments(namespace)
                KubernetesDataCache.putDeployments(profile.id, namespace, deployments)
                runOnEdt {
                    availableDeployments = deployments
                    isUpdatingCombo = true
                    deploymentComboBox.removeAllItems()
                    for (dep in deployments) {
                        deploymentComboBox.addItem(dep.name)
                    }

                    val targetName = when {
                        !toSelect.isNullOrBlank() && deployments.any { it.name == toSelect } -> toSelect
                        !toSelect.isNullOrBlank() -> {
                            // Add initial name if not present in the fetched list
                            deploymentComboBox.addItem(toSelect)
                            toSelect
                        }
                        deployments.isNotEmpty() -> deployments.first().name
                        else -> null
                    }

                    if (targetName != null) {
                        deploymentComboBox.selectedItem = targetName
                    }
                    isUpdatingCombo = false
                    isBusy.set(false)

                    if (targetName != null) {
                        loadDeploymentYaml(targetName)
                    } else {
                        statusLabel.text = "No deployments found in namespace '$namespace'."
                        yamlTextArea.text = "# No deployments found in namespace '$namespace'."
                    }
                }
            } catch (e: Exception) {
                runOnEdt {
                    isBusy.set(false)
                    statusLabel.text = "Error fetching deployments: ${e.message}"
                    if (!toSelect.isNullOrBlank()) {
                        isUpdatingCombo = true
                        deploymentComboBox.removeAllItems()
                        deploymentComboBox.addItem(toSelect)
                        deploymentComboBox.selectedItem = toSelect
                        isUpdatingCombo = false
                        loadDeploymentYaml(toSelect)
                    }
                }
            }
        }
    }

    private fun loadDeploymentYaml(deploymentName: String) {
        if (deploymentName.isBlank()) return
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()

        title = "Edit Deployment: $deploymentName ($namespace)"
        val cachedYaml = KubernetesDataCache.getYaml(profile.id, namespace, "deployment", deploymentName)
        if (cachedYaml != null) {
            yamlTextArea.text = cachedYaml
            yamlTextArea.caretPosition = 0
            updateQuickControlsFromYaml(deploymentName, cachedYaml)
            statusLabel.text = "Loaded deployment '$deploymentName' from cache."
            return
        }

        if (!isBusy.compareAndSet(false, true)) return

        statusLabel.text = "Loading YAML for deployment '$deploymentName'..."
        val client = KubernetesSshClient(profile)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = client.getDeploymentYaml(namespace, deploymentName)
                runOnEdt {
                    isBusy.set(false)
                    if (result.isSuccess && result.output.isNotBlank()) {
                        KubernetesDataCache.putYaml(profile.id, namespace, "deployment", deploymentName, result.output)
                        yamlTextArea.text = result.output
                        yamlTextArea.caretPosition = 0
                        updateQuickControlsFromYaml(deploymentName, result.output)
                        statusLabel.text = "Loaded deployment '$deploymentName' successfully."
                    } else {
                        val errorMsg = result.error.ifBlank { result.output }
                        statusLabel.text = "Failed to load deployment: $errorMsg"
                        yamlTextArea.text = "# Error loading deployment $deploymentName:\n# $errorMsg"
                    }
                }
            } catch (e: Exception) {
                runOnEdt {
                    val msg = e.message ?: "Unknown error"
                    isBusy.set(false)
                    statusLabel.text = "Error: $msg"
                    yamlTextArea.text = "# Error loading deployment $deploymentName:\n# $msg"
                }
            }
        }
    }

    private fun updateQuickControlsFromYaml(deploymentName: String, yamlText: String) {
        val depInfo = availableDeployments.find { it.name == deploymentName }
        if (depInfo != null) {
            replicasSpinner.value = depInfo.replicas
            containerComboBox.removeAllItems()
            for (c in depInfo.containers) {
                containerComboBox.addItem(c)
            }
            if (depInfo.images.isNotEmpty()) {
                imageTextField.text = depInfo.images.first()
            }
        } else {
            // Parse quick info from YAML/JSON if available
            containerComboBox.removeAllItems()
            val containers = mutableListOf<String>()
            val images = mutableListOf<String>()

            // Extract container names and images simply from lines
            val lines = yamlText.lines()
            var inContainers = false
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("replicas:") && !inContainers) {
                    val count = trimmed.substringAfter("replicas:").trim().toIntOrNull()
                    if (count != null) replicasSpinner.value = count
                }
                if (trimmed == "containers:") {
                    inContainers = true
                }
                if (inContainers && trimmed.startsWith("- name:")) {
                    containers.add(trimmed.substringAfter("- name:").trim().removeSurrounding("\"").removeSurrounding("'"))
                }
                if (inContainers && trimmed.startsWith("image:")) {
                    images.add(trimmed.substringAfter("image:").trim().removeSurrounding("\"").removeSurrounding("'"))
                }
            }
            for (c in containers) {
                containerComboBox.addItem(c)
            }
            if (images.isNotEmpty()) {
                imageTextField.text = images.first()
            }
        }
    }

    private fun scaleSelectedDeployment() {
        val deploymentName = deploymentComboBox.selectedItem as? String ?: return
        val newReplicas = replicasSpinner.value as? Int ?: return
        if (!isBusy.compareAndSet(false, true)) return

        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        statusLabel.text = "Scaling deployment '$deploymentName' to $newReplicas replicas..."
        val client = KubernetesSshClient(profile)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = client.scaleDeployment(namespace, deploymentName, newReplicas)
                runOnEdt {
                    isBusy.set(false)
                    if (result.isSuccess) {
                        statusLabel.text = "Deployment '$deploymentName' scaled to $newReplicas replicas."
                        Messages.showInfoMessage(
                            project,
                            "Successfully scaled deployment '$deploymentName' to $newReplicas replicas.\n${result.output.trim()}",
                            "Scale Deployment"
                        )
                        KubernetesDataCache.invalidateResourceType(profile.id, namespace, ResourceType.DEPLOYMENTS)
                        KubernetesDataCache.invalidateResourceType(profile.id, namespace, ResourceType.PODS)
                        onDeploymentUpdated?.invoke()
                        loadDeploymentYaml(deploymentName)
                    } else {
                        val errorMsg = result.error.ifBlank { result.output }
                        statusLabel.text = "Failed to scale deployment: $errorMsg"
                        Messages.showErrorDialog(project, "Error scaling deployment:\n$errorMsg", "Scale Error")
                    }
                }
            } catch (e: Exception) {
                runOnEdt {
                    isBusy.set(false)
                    statusLabel.text = "Error: ${e.message}"
                    Messages.showErrorDialog(project, "Error: ${e.message}", "Scale Error")
                }
            }
        }
    }

    private fun updateContainerImage() {
        val deploymentName = deploymentComboBox.selectedItem as? String ?: return
        val containerName = containerComboBox.selectedItem as? String ?: return
        val imageInput = imageTextField.text.trim()
        if (imageInput.isBlank()) {
            Messages.showWarningDialog(project, "Please enter a valid container image name/tag.", "Invalid Image")
            return
        }
        if (!isBusy.compareAndSet(false, true)) return

        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        val client = KubernetesSshClient(profile)

        // Check if the input is an absolute path (docker build context on SSH server)
        // Format: /path/to/context:image:tag or /path/to/context:image
        val parsed = parseImageInput(imageInput)

        if (parsed.buildContext != null) {
            // Validate that an image name was provided
            if (parsed.buildImageName.isNullOrBlank()) {
                isBusy.set(false)
                Messages.showWarningDialog(
                    project,
                    "When using an absolute path, you must specify the image name and optional tag.\n\n" +
                            "Unix format: /path/to/context:imagename:tag\n" +
                            "Windows format: C:\\path\\to\\context:imagename:tag\n\n" +
                            "Example: /home/user/myapp:myapp:latest\n" +
                            "Example: C:\\Users\\myapp:myapp:latest",
                    "Invalid Path Format"
                )
                return
            }

            val buildContext = parsed.buildContext
            val buildImageName = parsed.buildImageName
            val buildTag = parsed.buildTag ?: "latest"
            val finalImage = parsed.finalImage

            // Absolute path provided – build the image first, then deploy
            statusLabel.text = "Building docker image from '$buildContext' on remote server..."
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val buildResult = client.buildDockerImage(buildContext, buildImageName, buildTag)
                    if (!buildResult.isSuccess) {
                        val errorMsg = buildResult.error.ifBlank { buildResult.output }
                        runOnEdt {
                            isBusy.set(false)
                            statusLabel.text = "Docker build failed: $errorMsg"
                            Messages.showErrorDialog(project, "Failed to build docker image:\n$errorMsg", "Docker Build Error")
                        }
                        return@executeOnPooledThread
                    }

                    statusLabel.text = "Image built successfully. Updating container '$containerName' in deployment '$deploymentName' to image '$finalImage'..."
                    val deployResult = client.updateDeploymentImage(namespace, deploymentName, containerName, finalImage)
                    runOnEdt {
                        isBusy.set(false)
                        if (deployResult.isSuccess) {
                            statusLabel.text = "Built image and updated container '$containerName' to image '$finalImage'."
                            Messages.showInfoMessage(
                                project,
                                "Successfully built docker image and updated container image for '$deploymentName':\n${deployResult.output.trim()}",
                                "Build & Update Image"
                            )
                            KubernetesDataCache.invalidateResourceType(profile.id, namespace, ResourceType.DEPLOYMENTS)
                            KubernetesDataCache.invalidateResourceType(profile.id, namespace, ResourceType.PODS)
                            KubernetesDataCache.invalidateDockerImages(profile.id)
                            onDeploymentUpdated?.invoke()
                            loadDeploymentYaml(deploymentName)
                        } else {
                            val errorMsg = deployResult.error.ifBlank { deployResult.output }
                            statusLabel.text = "Failed to update image: $errorMsg"
                            Messages.showErrorDialog(project, "Error updating image:\n$errorMsg", "Update Image Error")
                        }
                    }
                } catch (e: Exception) {
                    runOnEdt {
                        isBusy.set(false)
                        statusLabel.text = "Error: ${e.message}"
                        Messages.showErrorDialog(project, "Error: ${e.message}", "Build & Update Error")
                    }
                }
            }
        } else {
            // Regular image name – just update the deployment
            val regularImage = parsed.finalImage
            statusLabel.text = "Updating container '$containerName' in deployment '$deploymentName' to image '$regularImage'..."
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val result = client.updateDeploymentImage(namespace, deploymentName, containerName, regularImage)
                    runOnEdt {
                        isBusy.set(false)
                        if (result.isSuccess) {
                            statusLabel.text = "Updated container '$containerName' to image '$regularImage'."
                            Messages.showInfoMessage(
                                project,
                                "Successfully updated container image for '$deploymentName':\n${result.output.trim()}",
                                "Update Image"
                            )
                            KubernetesDataCache.invalidateResourceType(profile.id, namespace, ResourceType.DEPLOYMENTS)
                            KubernetesDataCache.invalidateResourceType(profile.id, namespace, ResourceType.PODS)
                            onDeploymentUpdated?.invoke()
                            loadDeploymentYaml(deploymentName)
                        } else {
                            val errorMsg = result.error.ifBlank { result.output }
                            statusLabel.text = "Failed to update image: $errorMsg"
                            Messages.showErrorDialog(project, "Error updating image:\n$errorMsg", "Update Image Error")
                        }
                    }
                } catch (e: Exception) {
                    runOnEdt {
                        isBusy.set(false)
                        statusLabel.text = "Error: ${e.message}"
                        Messages.showErrorDialog(project, "Error: ${e.message}", "Update Image Error")
                    }
                }
            }
        }
    }

    private data class ParsedImageInput(
        val finalImage: String,
        val buildContext: String?,
        val buildImageName: String?,
        val buildTag: String?
    )

    private fun parseImageInput(input: String): ParsedImageInput {
        // Check if input is an absolute path (Unix or Windows)
        // Unix: /path/to/context:image:tag
        // Windows: C:\path\to\context:image:tag
        if (isAbsolutePath(input)) {
            // For absolute paths, find the first ':' that separates the path from the image name.
            // For Windows paths like C:\path\to\context:image:tag, the drive letter colon is part of the path.
            val colonIndex = findImageNameSeparator(input)
            if (colonIndex == -1) {
                // No image name separator found – just a path, invalid
                return ParsedImageInput(input, input, null, null)
            }
            val buildContext = input.substring(0, colonIndex)
            val rest = input.substring(colonIndex + 1)
            // rest is now "image:tag" or just "image"
            val restParts = rest.split(":")
            return when (restParts.size) {
                1 -> ParsedImageInput("${restParts[0]}:latest", buildContext, restParts[0], "latest")
                else -> ParsedImageInput(rest, buildContext, restParts[0], restParts.drop(1).joinToString(":"))
            }
        }
        // Regular image name
        return ParsedImageInput(input, null, null, null)
    }

    private fun isAbsolutePath(input: String): Boolean {
        // Unix absolute path
        if (input.startsWith("/")) return true
        // Windows absolute path: C:\ or C:/
        if (input.length >= 3 && input[1] == ':' && (input[2] == '\\' || input[2] == '/')) return true
        return false
    }

    private fun findImageNameSeparator(input: String): Int {
        // Find the first ':' that separates the build context path from the image name.
        // For Windows paths like C:\path\to\context:image:tag, skip the drive letter colon.
        // For Unix paths like /path/to/context:image:tag, find the first colon.
        val startIndex = if (isWindowsPath(input)) 2 else 0
        return input.indexOf(':', startIndex)
    }

    private fun isWindowsPath(input: String): Boolean {
        return input.length >= 3 && input[1] == ':' && (input[2] == '\\' || input[2] == '/')
    }

    private fun restartRollout() {
        val deploymentName = deploymentComboBox.selectedItem as? String ?: return
        val confirm = Messages.showYesNoDialog(
            project,
            "Are you sure you want to restart the rollout for deployment '$deploymentName' in namespace '$namespace'?",
            "Restart Rollout",
            Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) return

        if (!isBusy.compareAndSet(false, true)) return
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        statusLabel.text = "Restarting rollout for deployment '$deploymentName'..."
        val client = KubernetesSshClient(profile)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = client.restartDeploymentRollout(namespace, deploymentName)
                runOnEdt {
                    isBusy.set(false)
                    if (result.isSuccess) {
                        statusLabel.text = "Rollout restarted for deployment '$deploymentName'."
                        Messages.showInfoMessage(
                            project,
                            "Rollout restarted successfully:\n${result.output.trim()}",
                            "Restart Rollout"
                        )
                        KubernetesDataCache.invalidateResourceType(profile.id, namespace, ResourceType.DEPLOYMENTS)
                        KubernetesDataCache.invalidateResourceType(profile.id, namespace, ResourceType.PODS)
                        onDeploymentUpdated?.invoke()
                    } else {
                        val errorMsg = result.error.ifBlank { result.output }
                        statusLabel.text = "Failed to restart rollout: $errorMsg"
                        Messages.showErrorDialog(project, "Error restarting rollout:\n$errorMsg", "Rollout Error")
                    }
                }
            } catch (e: Exception) {
                runOnEdt {
                    isBusy.set(false)
                    statusLabel.text = "Error: ${e.message}"
                    Messages.showErrorDialog(project, "Error: ${e.message}", "Rollout Error")
                }
            }
        }
    }

    private fun showRolloutStatus() {
        val deploymentName = deploymentComboBox.selectedItem as? String ?: return
        if (!isBusy.compareAndSet(false, true)) return

        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        statusLabel.text = "Checking rollout status for deployment '$deploymentName'..."
        val client = KubernetesSshClient(profile)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = client.getDeploymentRolloutStatus(namespace, deploymentName)
                runOnEdt {
                    isBusy.set(false)
                    val output = (result.output.ifBlank { result.error }).trim()
                    statusLabel.text = "Rollout status retrieved."
                    Messages.showInfoMessage(
                        project,
                        "Deployment Rollout Status ($deploymentName):\n\n$output",
                        "Rollout Status"
                    )
                }
            } catch (e: Exception) {
                runOnEdt {
                    isBusy.set(false)
                    statusLabel.text = "Error: ${e.message}"
                    Messages.showErrorDialog(project, "Error: ${e.message}", "Rollout Status Error")
                }
            }
        }
    }

    private fun dryRunValidateYaml() {
        val yamlText = yamlTextArea.text
        if (yamlText.isBlank()) {
            Messages.showWarningDialog(project, "YAML manifest cannot be empty.", "Validation")
            return
        }

        if (!isBusy.compareAndSet(false, true)) return
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        statusLabel.text = "Validating YAML manifest with cluster (server dry-run)..."
        val client = KubernetesSshClient(profile)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = client.applyDeploymentYaml(namespace, yamlText, dryRun = true)
                runOnEdt {
                    isBusy.set(false)
                    if (result.isSuccess) {
                        statusLabel.text = "✓ Dry-run validation passed successfully!"
                        Messages.showInfoMessage(
                            project,
                            "YAML manifest is valid and can be applied without errors.\n\n${result.output.trim()}",
                            "Dry-Run Validation Succeeded"
                        )
                    } else {
                        val errorMsg = result.error.ifBlank { result.output }
                        statusLabel.text = "✗ Dry-run validation failed: $errorMsg"
                        Messages.showErrorDialog(
                            project,
                            "Dry-run validation reported errors:\n\n$errorMsg",
                            "Dry-Run Validation Failed"
                        )
                    }
                }
            } catch (e: Exception) {
                runOnEdt {
                    isBusy.set(false)
                    statusLabel.text = "Error: ${e.message}"
                    Messages.showErrorDialog(project, "Error: ${e.message}", "Validation Error")
                }
            }
        }
    }

    private fun applyEditedYaml() {
        val yamlText = yamlTextArea.text
        if (yamlText.isBlank()) {
            Messages.showWarningDialog(project, "YAML manifest cannot be empty.", "Apply Changes")
            return
        }

        val deploymentName = deploymentComboBox.selectedItem as? String ?: "deployment"
        val confirm = Messages.showYesNoDialog(
            project,
            "Apply the modified YAML manifest for '$deploymentName' in namespace '$namespace' to the remote Kubernetes cluster?",
            "Confirm Apply Changes",
            Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) return

        if (!isBusy.compareAndSet(false, true)) return
        val profile = KubernetesSshSettings.getInstance(project).state.getActiveProfile()
        statusLabel.text = "Applying YAML manifest to cluster..."
        val client = KubernetesSshClient(profile)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = client.applyDeploymentYaml(namespace, yamlText, dryRun = false)
                runOnEdt {
                    isBusy.set(false)
                    if (result.isSuccess) {
                        statusLabel.text = "✓ Deployment applied successfully!"
                        KubernetesDataCache.putYaml(profile.id, namespace, "deployment", deploymentName, yamlText)
                        KubernetesDataCache.invalidateResourceType(profile.id, namespace, ResourceType.DEPLOYMENTS)
                        KubernetesDataCache.invalidateResourceType(profile.id, namespace, ResourceType.PODS)
                        Messages.showInfoMessage(
                            project,
                            "Deployment applied successfully:\n\n${result.output.trim()}",
                            "Deployment Applied"
                        )
                        onDeploymentUpdated?.invoke()
                        close(OK_EXIT_CODE)
                    } else {
                        val errorMsg = result.error.ifBlank { result.output }
                        statusLabel.text = "✗ Failed to apply deployment: $errorMsg"
                        Messages.showErrorDialog(
                            project,
                            "Failed to apply deployment manifest:\n\n$errorMsg",
                            "Apply Error"
                        )
                    }
                }
            } catch (e: Exception) {
                runOnEdt {
                    isBusy.set(false)
                    statusLabel.text = "Error: ${e.message}"
                    Messages.showErrorDialog(project, "Error applying deployment: ${e.message}", "Apply Error")
                }
            }
        }
    }
}
