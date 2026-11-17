package com.kubernetes.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.kubernetes.model.DockerImageInfo
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

class DockerTagDialog(
    project: Project,
    private val imageInfo: DockerImageInfo,
    private val isChangeTag: Boolean
) : DialogWrapper(project, true) {

    private val sourceLabel: JBLabel
    private val repositoryField: JBTextField
    private val tagField: JBTextField
    private val removeOldTagCheckBox: JBCheckBox

    init {
        title = if (isChangeTag) "Change Docker Image Tag" else "Create New Docker Image Tag"

        val hasValidOldTag = imageInfo.repository.isNotBlank() &&
                imageInfo.repository != "<none>" &&
                imageInfo.tag.isNotBlank() &&
                imageInfo.tag != "<none>"

        val sourceDisplay = if (hasValidOldTag) {
            "${imageInfo.repository}:${imageInfo.tag} (${imageInfo.imageId})"
        } else {
            imageInfo.imageId.ifBlank { imageInfo.repository }
        }

        sourceLabel = JBLabel("<html><b>$sourceDisplay</b></html>")

        val initialRepo = if (imageInfo.repository.isNotBlank() && imageInfo.repository != "<none>") {
            imageInfo.repository
        } else {
            ""
        }

        repositoryField = JBTextField(initialRepo)
        tagField = JBTextField(if (isChangeTag && hasValidOldTag) imageInfo.tag else "")

        val oldTagRef = if (hasValidOldTag) "${imageInfo.repository}:${imageInfo.tag}" else ""
        removeOldTagCheckBox = JBCheckBox(
            "Remove old tag ($oldTagRef) after creating new tag",
            isChangeTag && hasValidOldTag
        ).apply {
            isEnabled = isChangeTag && hasValidOldTag
            isVisible = isChangeTag && hasValidOldTag
        }

        init()
    }

    override fun createCenterPanel(): JComponent {
        val formBuilder = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Source Image:"), sourceLabel, 1, false)
            .addSeparator()
            .addLabeledComponent(JBLabel("Target Repository:"), repositoryField, 1, false)
            .addLabeledComponent(JBLabel("Target Tag:"), tagField, 1, false)

        if (removeOldTagCheckBox.isVisible) {
            formBuilder.addComponent(removeOldTagCheckBox, 1)
        }

        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(480, if (removeOldTagCheckBox.isVisible) 180 else 150)
        panel.add(formBuilder.panel, BorderLayout.CENTER)
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = if (repositoryField.text.isBlank()) repositoryField else tagField

    override fun doValidate(): ValidationInfo? {
        val repo = repositoryField.text.trim()
        val tag = tagField.text.trim()

        if (repo.isEmpty()) {
            return ValidationInfo("Repository name cannot be empty", repositoryField)
        }
        if (tag.isEmpty()) {
            return ValidationInfo("Tag cannot be empty", tagField)
        }
        if (repo.contains(" ") || tag.contains(" ")) {
            return ValidationInfo("Repository and tag cannot contain spaces")
        }

        val oldRepo = imageInfo.repository.trim()
        val oldTag = imageInfo.tag.trim()
        if (isChangeTag && repo == oldRepo && tag == oldTag) {
            return ValidationInfo("New tag is identical to the current tag ($oldRepo:$oldTag)", tagField)
        }

        return null
    }

    fun getTargetRepository(): String = repositoryField.text.trim()

    fun getTargetTag(): String = tagField.text.trim()

    fun getTargetFullImageName(): String = "${getTargetRepository()}:${getTargetTag()}"

    fun shouldRemoveOldTag(): Boolean = removeOldTagCheckBox.isSelected && removeOldTagCheckBox.isEnabled

    fun getOldTagToRemove(): String? {
        val hasValidOldTag = imageInfo.repository.isNotBlank() &&
                imageInfo.repository != "<none>" &&
                imageInfo.tag.isNotBlank() &&
                imageInfo.tag != "<none>"
        return if (shouldRemoveOldTag() && hasValidOldTag) {
            "${imageInfo.repository}:${imageInfo.tag}"
        } else {
            null
        }
    }

    fun getSourceIdentifier(): String {
        val hasValidOldTag = imageInfo.repository.isNotBlank() &&
                imageInfo.repository != "<none>" &&
                imageInfo.tag.isNotBlank() &&
                imageInfo.tag != "<none>"
        return if (hasValidOldTag) {
            "${imageInfo.repository}:${imageInfo.tag}"
        } else {
            imageInfo.imageId.ifBlank { imageInfo.repository }
        }
    }
}
