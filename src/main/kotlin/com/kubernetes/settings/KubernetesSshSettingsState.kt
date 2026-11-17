package com.kubernetes.settings

import com.kubernetes.model.AuthType
import com.kubernetes.model.SshProfile

data class KubernetesSshSettingsState(
    var activeProfileId: String = "",
    var profiles: MutableList<SshProfile> = mutableListOf(),
    // Legacy fields for backward compatibility with older configurations
    var host: String = "",
    var port: Int = 22,
    var username: String = "",
    var authType: AuthType = AuthType.PASSWORD,
    var password: String = "",
    var privateKeyPath: String = "",
    var passphrase: String = "",
    var kubectlPath: String = "kubectl",
    var dockerPath: String = "docker",
    var connectionTimeoutSeconds: Int = 10,
    var defaultNamespace: String = "default"
) {
    fun getActiveProfile(): SshProfile {
        ensureProfilesInitialized()
        return profiles.find { it.id == activeProfileId } ?: profiles.first()
    }

    fun selectProfile(id: String) {
        ensureProfilesInitialized()
        if (profiles.any { it.id == id }) {
            activeProfileId = id
        }
    }

    fun ensureProfilesInitialized() {
        if (profiles.isEmpty()) {
            val initialProfile = SshProfile(
                name = "Default",
                host = host,
                port = if (port > 0) port else 22,
                username = username,
                authType = authType,
                password = password,
                privateKeyPath = privateKeyPath,
                passphrase = passphrase,
                kubectlPath = kubectlPath.ifBlank { "kubectl" },
                dockerPath = dockerPath.ifBlank { "docker" },
                connectionTimeoutSeconds = if (connectionTimeoutSeconds > 0) connectionTimeoutSeconds else 10,
                defaultNamespace = defaultNamespace.ifBlank { "default" }
            )
            profiles.add(initialProfile)
            activeProfileId = initialProfile.id
        } else {
            if (activeProfileId.isBlank() || profiles.none { it.id == activeProfileId }) {
                activeProfileId = profiles.first().id
            }
        }
    }
}
