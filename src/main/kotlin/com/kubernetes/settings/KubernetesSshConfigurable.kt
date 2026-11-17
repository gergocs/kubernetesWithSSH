package com.kubernetes.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.kubernetes.model.AuthType
import com.kubernetes.model.SshProfile
import com.kubernetes.ssh.KubernetesSshClient
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

class KubernetesSshConfigurable(private val project: Project) : Configurable {

    private val workingProfiles = mutableListOf<SshProfile>()
    private var selectedProfile: SshProfile? = null
    private var isSwitchingProfile = false

    private val profileComboBox = ComboBox<SshProfile>()
    private val addProfileButton = JButton(AllIcons.General.Add).apply {
        toolTipText = "Add new profile"
    }
    private val duplicateProfileButton = JButton(AllIcons.Actions.Copy).apply {
        toolTipText = "Duplicate current profile"
    }
    private val removeProfileButton = JButton(AllIcons.General.Remove).apply {
        toolTipText = "Delete current profile"
    }

    private val profileNameField = JBTextField()
    private val hostField = JBTextField()
    private val portField = JBTextField("22")
    private val usernameField = JBTextField()

    private val passwordRadio = JBRadioButton("Password authentication", true)
    private val privateKeyRadio = JBRadioButton("Private key authentication", false)

    private val passwordField = JBPasswordField()
    private val privateKeyPathField = TextFieldWithBrowseButton()
    private val passphraseField = JBPasswordField()

    private val kubectlPathField = JBTextField("kubectl")
    private val dockerPathField = JBTextField("docker")
    private val defaultNamespaceField = JBTextField("default")
    private val timeoutField = JBTextField("10")

    private val testConnectionButton = JButton("Test SSH Connection")
    private val testResultLabel = JBLabel("")

    private var mainPanel: JPanel? = null

    override fun getDisplayName(): String = "Kubernetes over SSH"

    override fun createComponent(): JComponent {
        val authGroup = ButtonGroup()
        authGroup.add(passwordRadio)
        authGroup.add(privateKeyRadio)

        passwordRadio.addActionListener { updateAuthFieldsVisibility() }
        privateKeyRadio.addActionListener { updateAuthFieldsVisibility() }

        privateKeyPathField.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor()
                .withTitle("Select Private Key File")
                .withDescription("Select your SSH private key (e.g. id_rsa, id_ed25519)")
        )

        profileComboBox.addActionListener {
            if (!isSwitchingProfile) {
                val newSelection = profileComboBox.selectedItem as? SshProfile
                if (newSelection != null && newSelection != selectedProfile) {
                    saveCurrentFieldsToProfile()
                    selectedProfile = newSelection
                    loadProfileToUI(newSelection)
                }
            }
        }

        profileNameField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (!isSwitchingProfile && selectedProfile != null) {
                    val newName = profileNameField.text.trim()
                    if (newName.isNotBlank() && selectedProfile?.name != newName) {
                        selectedProfile?.name = newName
                        profileComboBox.repaint()
                    }
                }
            }
        })

        addProfileButton.addActionListener {
            addNewProfile()
        }

        duplicateProfileButton.addActionListener {
            duplicateCurrentProfile()
        }

        removeProfileButton.addActionListener {
            removeCurrentProfile()
        }

        testConnectionButton.addActionListener {
            performTestConnection()
        }

        val profileManagementPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
            add(profileComboBox)
            add(addProfileButton)
            add(duplicateProfileButton)
            add(removeProfileButton)
        }

        val testPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
            add(testConnectionButton)
            add(testResultLabel)
        }

        val authChoicePanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(passwordRadio)
            add(JBUI.Borders.emptyLeft(15).wrap(privateKeyRadio))
        }

        val formPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("SSH Profile:"), profileManagementPanel, 1, false)
            .addLabeledComponent(JBLabel("Profile Name:"), profileNameField, 1, false)
            .addSeparator()
            .addLabeledComponent(JBLabel("SSH Host:"), hostField, 1, false)
            .addLabeledComponent(JBLabel("SSH Port:"), portField, 1, false)
            .addLabeledComponent(JBLabel("SSH Username:"), usernameField, 1, false)
            .addSeparator()
            .addLabeledComponent(JBLabel("Authentication Method:"), authChoicePanel, 1, false)
            .addLabeledComponent(JBLabel("Password:"), passwordField, 1, false)
            .addLabeledComponent(JBLabel("Private Key File:"), privateKeyPathField, 1, false)
            .addLabeledComponent(JBLabel("Key Passphrase (optional):"), passphraseField, 1, false)
            .addSeparator()
            .addLabeledComponent(JBLabel("Remote kubectl Path:"), kubectlPathField, 1, false)
            .addLabeledComponent(JBLabel("Remote Docker Path:"), dockerPathField, 1, false)
            .addLabeledComponent(JBLabel("Default Namespace:"), defaultNamespaceField, 1, false)
            .addLabeledComponent(JBLabel("Timeout (seconds):"), timeoutField, 1, false)
            .addSeparator()
            .addComponent(testPanel)
            .panel

        mainPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            add(formPanel, BorderLayout.NORTH)
        }

        reset()
        updateAuthFieldsVisibility()
        return mainPanel!!
    }

    private fun addNewProfile() {
        saveCurrentFieldsToProfile()
        val newProfileName = "Profile ${workingProfiles.size + 1}"
        val newProfile = SshProfile(name = newProfileName)
        workingProfiles.add(newProfile)

        isSwitchingProfile = true
        try {
            profileComboBox.addItem(newProfile)
            profileComboBox.selectedItem = newProfile
            selectedProfile = newProfile
        } finally {
            isSwitchingProfile = false
        }
        loadProfileToUI(newProfile)
        profileNameField.requestFocusInWindow()
        profileNameField.selectAll()
    }

    private fun duplicateCurrentProfile() {
        val current = selectedProfile ?: return
        saveCurrentFieldsToProfile()
        val copyProfile = current.copyProfile("${current.name} (Copy)")
        workingProfiles.add(copyProfile)

        isSwitchingProfile = true
        try {
            profileComboBox.addItem(copyProfile)
            profileComboBox.selectedItem = copyProfile
            selectedProfile = copyProfile
        } finally {
            isSwitchingProfile = false
        }
        loadProfileToUI(copyProfile)
        profileNameField.requestFocusInWindow()
        profileNameField.selectAll()
    }

    private fun removeCurrentProfile() {
        if (workingProfiles.size <= 1) {
            Messages.showWarningDialog(
                project,
                "At least one SSH profile must be present.",
                "Cannot Delete Profile"
            )
            return
        }

        val current = selectedProfile ?: return
        val confirm = Messages.showYesNoDialog(
            project,
            "Are you sure you want to delete profile '${current.name}'?",
            "Delete SSH Profile",
            Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) return

        val currentIndex = workingProfiles.indexOf(current)
        workingProfiles.remove(current)

        val nextIndex = if (currentIndex >= workingProfiles.size) workingProfiles.size - 1 else currentIndex
        val nextProfile = workingProfiles[nextIndex]

        isSwitchingProfile = true
        try {
            profileComboBox.removeItem(current)
            profileComboBox.selectedItem = nextProfile
            selectedProfile = nextProfile
        } finally {
            isSwitchingProfile = false
        }
        loadProfileToUI(nextProfile)
    }

    private fun updateAuthFieldsVisibility() {
        val isPassword = passwordRadio.isSelected
        passwordField.isEnabled = isPassword
        privateKeyPathField.isEnabled = !isPassword
        passphraseField.isEnabled = !isPassword
    }

    private fun saveCurrentFieldsToProfile() {
        selectedProfile?.let { prof ->
            prof.name = profileNameField.text.trim().ifBlank { prof.name }
            prof.host = hostField.text.trim()
            prof.port = portField.text.trim().toIntOrNull() ?: 22
            prof.username = usernameField.text.trim()
            prof.authType = if (passwordRadio.isSelected) AuthType.PASSWORD else AuthType.PRIVATE_KEY
            prof.password = String(passwordField.password)
            prof.privateKeyPath = privateKeyPathField.text.trim()
            prof.passphrase = String(passphraseField.password)
            prof.kubectlPath = kubectlPathField.text.trim().ifBlank { "kubectl" }
            prof.dockerPath = dockerPathField.text.trim().ifBlank { "docker" }
            prof.connectionTimeoutSeconds = timeoutField.text.trim().toIntOrNull() ?: 10
            prof.defaultNamespace = defaultNamespaceField.text.trim().ifBlank { "default" }
        }
    }

    private fun loadProfileToUI(profile: SshProfile) {
        isSwitchingProfile = true
        try {
            profileNameField.text = profile.name
            hostField.text = profile.host
            portField.text = profile.port.toString()
            usernameField.text = profile.username
            if (profile.authType == AuthType.PASSWORD) {
                passwordRadio.isSelected = true
            } else {
                privateKeyRadio.isSelected = true
            }
            passwordField.text = profile.password
            privateKeyPathField.text = profile.privateKeyPath
            passphraseField.text = profile.passphrase
            kubectlPathField.text = profile.kubectlPath
            dockerPathField.text = profile.dockerPath
            defaultNamespaceField.text = profile.defaultNamespace
            timeoutField.text = profile.connectionTimeoutSeconds.toString()
            testResultLabel.text = ""
            updateAuthFieldsVisibility()
        } finally {
            isSwitchingProfile = false
        }
    }

    private fun performTestConnection() {
        testResultLabel.text = "Testing connection..."
        testConnectionButton.isEnabled = false

        saveCurrentFieldsToProfile()
        val testProfile = selectedProfile?.copy() ?: SshProfile()

        Thread {
            val client = KubernetesSshClient(testProfile)
            val result = client.testConnection()
            SwingUtilities.invokeLater {
                testConnectionButton.isEnabled = true
                if (result.success) {
                    testResultLabel.text = "✓ Connection successful!"
                    Messages.showInfoMessage(
                        project,
                        "${result.message}\n\n${result.details}",
                        "SSH Connection Test"
                    )
                } else {
                    testResultLabel.text = "✗ Connection failed"
                    Messages.showErrorDialog(
                        project,
                        "${result.message}\n\n${result.details}",
                        "SSH Connection Test Failed"
                    )
                }
            }
        }.start()
    }

    override fun isModified(): Boolean {
        saveCurrentFieldsToProfile()
        val state = KubernetesSshSettings.getInstance(project).state
        state.ensureProfilesInitialized()
        if (workingProfiles.size != state.profiles.size) return true
        if (selectedProfile?.id != state.activeProfileId) return true
        for (i in workingProfiles.indices) {
            if (workingProfiles[i] != state.profiles[i]) return true
        }
        return false
    }

    override fun apply() {
        saveCurrentFieldsToProfile()
        val state = KubernetesSshSettings.getInstance(project).state
        state.profiles.clear()
        state.profiles.addAll(workingProfiles.map { it.copy() })
        state.activeProfileId = selectedProfile?.id ?: state.profiles.firstOrNull()?.id ?: ""
        state.ensureProfilesInitialized()

        val active = state.getActiveProfile()
        state.host = active.host
        state.port = active.port
        state.username = active.username
        state.authType = active.authType
        state.password = active.password
        state.privateKeyPath = active.privateKeyPath
        state.passphrase = active.passphrase
        state.kubectlPath = active.kubectlPath
        state.dockerPath = active.dockerPath
        state.connectionTimeoutSeconds = active.connectionTimeoutSeconds
        state.defaultNamespace = active.defaultNamespace
    }

    override fun reset() {
        val state = KubernetesSshSettings.getInstance(project).state
        state.ensureProfilesInitialized()
        workingProfiles.clear()
        state.profiles.forEach { workingProfiles.add(it.copy()) }

        isSwitchingProfile = true
        try {
            profileComboBox.removeAllItems()
            workingProfiles.forEach { profileComboBox.addItem(it) }
            val active = workingProfiles.find { it.id == state.activeProfileId } ?: workingProfiles.firstOrNull()
            selectedProfile = active
            profileComboBox.selectedItem = active
        } finally {
            isSwitchingProfile = false
        }
        selectedProfile?.let { loadProfileToUI(it) }
    }

    override fun disposeUIResources() {
        mainPanel = null
    }
}
