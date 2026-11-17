package com.kubernetes.ssh

import com.kubernetes.model.AuthType
import com.kubernetes.model.SshProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class KubectlCommandResolverTest {

    private val profile = SshProfile(
        id = "test-prof",
        name = "Test Server",
        host = "192.168.1.100",
        port = 22,
        username = "root",
        authType = AuthType.PASSWORD,
        password = "secret",
        kubectlPath = "/usr/local/bin/kubectl",
        defaultNamespace = "default"
    )

    private val client = KubernetesSshClient(profile)

    @Test
    fun `test resolve command with simple get nodes`() {
        val resolved = client.resolveKubectlCommand("get nodes")
        assertEquals("/usr/local/bin/kubectl get nodes", resolved)
    }

    @Test
    fun `test resolve command when kubectl prefix is already present`() {
        val resolved = client.resolveKubectlCommand("kubectl get pods -A")
        assertEquals("/usr/local/bin/kubectl get pods -A", resolved)
    }

    @Test
    fun `test resolve command when full kubectl path is already present`() {
        val resolved = client.resolveKubectlCommand("/usr/local/bin/kubectl cluster-info")
        assertEquals("/usr/local/bin/kubectl cluster-info", resolved)
    }

    @Test
    fun `test resolve command with auto namespace enabled and target namespace provided`() {
        val resolved = client.resolveKubectlCommand("get pods", namespace = "kube-system", autoNamespace = true)
        assertEquals("/usr/local/bin/kubectl get pods -n kube-system", resolved)
    }

    @Test
    fun `test resolve command with auto namespace does not duplicate namespace if already specified`() {
        val resolvedWithN = client.resolveKubectlCommand("get pods -n custom-ns", namespace = "kube-system", autoNamespace = true)
        assertEquals("/usr/local/bin/kubectl get pods -n custom-ns", resolvedWithN)

        val resolvedWithNamespace = client.resolveKubectlCommand("get pods --namespace=custom-ns", namespace = "kube-system", autoNamespace = true)
        assertEquals("/usr/local/bin/kubectl get pods --namespace=custom-ns", resolvedWithNamespace)

        val resolvedWithAll = client.resolveKubectlCommand("get pods -A", namespace = "kube-system", autoNamespace = true)
        assertEquals("/usr/local/bin/kubectl get pods -A", resolvedWithAll)
    }

    @Test
    fun `test resolve command with blank or empty input`() {
        val resolved = client.resolveKubectlCommand("")
        assertEquals("/usr/local/bin/kubectl", resolved)
    }

    @Test
    fun `test resolve command when namespace is All Namespaces`() {
        val resolved = client.resolveKubectlCommand("get services", namespace = "All Namespaces", autoNamespace = true)
        assertEquals("/usr/local/bin/kubectl get services", resolved)
    }
}
