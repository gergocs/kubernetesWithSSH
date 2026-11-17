package com.kubernetes.model

import java.util.UUID

data class SshProfile(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "Default",
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
    fun copyProfile(newName: String = "$name (Copy)"): SshProfile {
        return this.copy(
            id = UUID.randomUUID().toString(),
            name = newName
        )
    }

    override fun toString(): String = name
}
