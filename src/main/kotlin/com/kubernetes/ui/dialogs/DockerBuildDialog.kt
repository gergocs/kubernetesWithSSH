package com.kubernetes.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.kubernetes.model.DockerImageInfo
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Dialog for building a Docker image from a source folder on the SSH server.
 * Allows the user to specify a build context path and optionally change the target tag.
 */
class DockerBuildDialog(
    project: Project,
    private val imageInfo: DockerImageInfo,
    private val existingSourceFolder: String? = null
) : DialogWrapper(project, true) {

    private lateinit var sourceFolderField: JBTextField
    private lateinit var targetTagField: JBTextField
    private lateinit var dockerFilePathField: JBTextField

    init {
        title = "Build Docker Image"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val fullImageName = if (imageInfo.repository.isNotBlank() && imageInfo.tag.isNotBlank() && imageInfo.tag != "<none>") {
            "${imageInfo.repository}:${imageInfo.tag}"
        } else {
            imageInfo.imageId.ifBlank { imageInfo.repository }
        }

        val sourceImageLabel = JBLabel("<html><b>$fullImageName</b></html>")

        sourceFolderField = JBTextField(existingSourceFolder ?: "")
        sourceFolderField.emptyText.text = "/path/to/context or C:\\path\\to\\context"

        // Default target tag is the same as the current image tag
        val defaultTag = if (imageInfo.tag.isNotBlank() && imageInfo.tag != "<none>") {
            imageInfo.tag
        } else {
            "latest"
        }
        targetTagField = JBTextField(defaultTag)

        dockerFilePathField = JBTextField("")
        dockerFilePathField.emptyText.text = "Optional: custom Dockerfile path"

        val formBuilder = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Source Image:"), sourceImageLabel, 1, false)
            .addSeparator()
            .addLabeledComponent(JBLabel("Build Context Folder:"), sourceFolderField, 1, false)
            .addLabeledComponent(JBLabel("  (on SSH server)"), JBLabel(""), 1, false)
            .addLabeledComponent(JBLabel("Target Tag:"), targetTagField, 1, false)
            .addLabeledComponent(JBLabel("  (repository stays the same)"), JBLabel(""), 1, false)
            .addLabeledComponent(JBLabel("Dockerfile Path:"), dockerFilePathField, 1, false)
            .addLabeledComponent(JBLabel("  (optional, relative to context)"), JBLabel(""), 1, false)

        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(500, 240)
        panel.add(formBuilder.panel, BorderLayout.CENTER)
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = sourceFolderField

    override fun doValidate(): ValidationInfo? {
        val folder = sourceFolderField.text.trim()
        val tag = targetTagField.text.trim()

        if (folder.isEmpty()) {
            return ValidationInfo("Build context folder cannot be empty", sourceFolderField)
        }
        if (!isAbsolutePath(folder)) {
            return ValidationInfo("Build context folder must be an absolute path (e.g., /path/to/context or C:\\path\\to\\context)", sourceFolderField)
        }
        if (tag.isEmpty()) {
            return ValidationInfo("Target tag cannot be empty", targetTagField)
        }
        if (tag.contains(" ")) {
            return ValidationInfo("Tag cannot contain spaces", targetTagField)
        }
        val dockerfilePath = dockerFilePathField.text.trim()
        if (dockerfilePath.isNotEmpty() && dockerfilePath.contains(" ")) {
            return ValidationInfo("Dockerfile path cannot contain spaces", dockerFilePathField)
        }
        return null
    }

    /** Returns the absolute path to the build context on the SSH server */
    fun getSourceFolder(): String = sourceFolderField.text.trim()

    /** Returns the target tag for the built image */
    fun getTargetTag(): String = targetTagField.text.trim()

    /** Returns an optional custom Dockerfile path, or null if using the default */
    fun getDockerfilePath(): String? {
        val path = dockerFilePathField.text.trim()
        return if (path.isNotEmpty()) path else null
    }

    /** Returns the full target image name (repository:tag) */
    fun getTargetImageName(): String {
        val repo = if (imageInfo.repository.isNotBlank() && imageInfo.repository != "<none>") {
            imageInfo.repository
        } else {
            "untagged-image"
        }
        return "$repo:${getTargetTag()}"
    }

    private fun isAbsolutePath(input: String): Boolean {
        // Unix absolute path
        if (input.startsWith("/")) return true
        // Windows absolute path: C:\ or C:/
        if (input.length >= 3 && input[1] == ':' && (input[2] == '\\' || input[2] == '/')) return true
        return false
    }
}
