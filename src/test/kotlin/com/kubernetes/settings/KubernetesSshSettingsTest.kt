package com.kubernetes.settings

import com.kubernetes.model.AuthType
import com.kubernetes.model.SshProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KubernetesSshSettingsTest {

    @Test
    fun testDefaultSettings() {
        val state = KubernetesSshSettingsState()
        assertEquals("", state.host)
        assertEquals(22, state.port)
        assertEquals("", state.username)
        assertEquals(AuthType.PASSWORD, state.authType)
        assertEquals("", state.password)
        assertEquals("kubectl", state.kubectlPath)
        assertEquals("docker", state.dockerPath)
        assertEquals(10, state.connectionTimeoutSeconds)
        assertEquals("default", state.defaultNamespace)

        val activeProfile = state.getActiveProfile()
        assertNotNull(activeProfile)
        assertEquals("Default", activeProfile.name)
        assertEquals("", activeProfile.host)
        assertEquals(22, activeProfile.port)
    }

    @Test
    fun testSettingsStateMutationAndEquality() {
        val profile1 = SshProfile(
            name = "Cluster A",
            host = "192.168.1.100",
            port = 2222,
            username = "admin",
            authType = AuthType.PRIVATE_KEY,
            privateKeyPath = "/home/user/.ssh/id_rsa",
            passphrase = "secret_passphrase",
            kubectlPath = "/usr/local/bin/kubectl",
            connectionTimeoutSeconds = 15,
            defaultNamespace = "production"
        )

        val state1 = KubernetesSshSettingsState(
            activeProfileId = profile1.id,
            profiles = mutableListOf(profile1)
        )

        val state2 = state1.copy(profiles = mutableListOf(profile1.copy()))
        assertEquals(state1, state2)

        state2.profiles[0].defaultNamespace = "staging"
        assertNotEquals(state1, state2)
    }

    @Test
    fun testMultipleProfilesAndSwitching() {
        val profile1 = SshProfile(name = "Production", host = "prod.k8s.example.com", username = "prod-user")
        val profile2 = SshProfile(name = "Staging", host = "staging.k8s.example.com", username = "stage-user")
        val profile3 = SshProfile(name = "Dev", host = "dev.k8s.example.com", username = "dev-user")

        val state = KubernetesSshSettingsState(
            activeProfileId = profile1.id,
            profiles = mutableListOf(profile1, profile2, profile3)
        )

        assertEquals(3, state.profiles.size)
        assertEquals("Production", state.getActiveProfile().name)
        assertEquals("prod.k8s.example.com", state.getActiveProfile().host)

        state.selectProfile(profile2.id)
        assertEquals("Staging", state.getActiveProfile().name)
        assertEquals("staging.k8s.example.com", state.getActiveProfile().host)

        state.selectProfile(profile3.id)
        assertEquals("Dev", state.getActiveProfile().name)
        assertEquals("dev.k8s.example.com", state.getActiveProfile().host)
    }

    @Test
    fun testProfileDuplication() {
        val original = SshProfile(name = "Production", host = "prod.k8s.example.com", username = "root", port = 2222)
        val copy = original.copyProfile("Production (Backup)")

        assertNotEquals(original.id, copy.id)
        assertEquals("Production (Backup)", copy.name)
        assertEquals(original.host, copy.host)
        assertEquals(original.username, copy.username)
        assertEquals(original.port, copy.port)
    }

    @Test
    fun testLegacySettingsMigration() {
        val legacyState = KubernetesSshSettingsState(
            host = "legacy.k8s.example.com",
            port = 2200,
            username = "legacy-user",
            authType = AuthType.PASSWORD,
            password = "secret-password",
            kubectlPath = "/custom/kubectl",
            dockerPath = "/custom/docker",
            defaultNamespace = "custom-ns"
        )

        val active = legacyState.getActiveProfile()
        assertEquals("Default", active.name)
        assertEquals("legacy.k8s.example.com", active.host)
        assertEquals(2200, active.port)
        assertEquals("legacy-user", active.username)
        assertEquals("secret-password", active.password)
        assertEquals("/custom/kubectl", active.kubectlPath)
        assertEquals("/custom/docker", active.dockerPath)
        assertEquals("custom-ns", active.defaultNamespace)
        assertTrue(legacyState.profiles.size == 1)
        assertEquals(active.id, legacyState.activeProfileId)
    }
}
