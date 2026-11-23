package com.kubernetes.terminal

import com.kubernetes.model.AuthType
import com.kubernetes.model.PodInfo
import com.kubernetes.model.SshProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalLauncherTest {

    @Test
    fun testBuildSshCommandDefault() {
        val profile = SshProfile(
            host = "192.168.1.100",
            port = 22,
            username = "ubuntu",
            kubectlPath = "kubectl",
            defaultNamespace = "default"
        )

        val command = TerminalLauncher.buildSshCommand(
            profile = profile,
            namespace = "production",
            podName = "api-service-789",
            container = null,
            shell = "/bin/sh"
        )

        assertEquals("ssh -t ubuntu@192.168.1.100 \"kubectl exec -it -n production api-service-789 -- /bin/sh\"", command)
    }

    @Test
    fun testBuildSshCommandWithContainerAndCustomShell() {
        val profile = SshProfile(
            host = "cluster.internal",
            port = 2222,
            username = "devops",
            authType = AuthType.PASSWORD,
            kubectlPath = "/opt/k8s/bin/kubectl",
            defaultNamespace = "default"
        )

        val command = TerminalLauncher.buildSshCommand(
            profile = profile,
            namespace = "staging",
            podName = "frontend-pod-456",
            container = "nginx",
            shell = "/bin/bash"
        )

        assertEquals("ssh -p 2222 -t devops@cluster.internal \"/opt/k8s/bin/kubectl exec -it -n staging frontend-pod-456 -c nginx -- /bin/bash\"", command)
    }

    @Test
    fun testBuildSshCommandWithPrivateKey() {
        val profile = SshProfile(
            host = "k8s-node.example.com",
            port = 22,
            username = "root",
            authType = AuthType.PRIVATE_KEY,
            privateKeyPath = "C:\\Users\\admin\\.ssh\\id_ed25519",
            kubectlPath = "kubectl",
            defaultNamespace = "kube-system"
        )

        val command = TerminalLauncher.buildSshCommand(
            profile = profile,
            namespace = "",
            podName = "coredns-12345",
            container = "coredns",
            shell = "sh"
        )

        assertEquals("ssh -i \"C:/Users/admin/.ssh/id_ed25519\" -t root@k8s-node.example.com \"kubectl exec -it -n kube-system coredns-12345 -c coredns -- sh\"", command)
    }

    @Test
    fun testBuildSshCommandFallbackNamespace() {
        val profile = SshProfile(
            host = "10.0.0.1",
            port = 22,
            username = "",
            defaultNamespace = ""
        )

        val command = TerminalLauncher.buildSshCommand(
            profile = profile,
            namespace = "",
            podName = "standalone-pod",
            container = "",
            shell = ""
        )

        assertEquals("ssh -t 10.0.0.1 \"kubectl exec -it -n default standalone-pod -- /bin/sh\"", command)
    }

    @Test
    fun testTerminalPluginAvailabilityCheck() {
        // In the test runtime, TerminalView should be present on classpath
        val isAvailable = TerminalLauncher.isTerminalPluginAvailable()
        assertTrue("TerminalView class should be loadable on classpath", isAvailable)
    }

    @Test
    fun testBuildSshCommandMultipleContainersSupport() {
        val profile = SshProfile(
            host = "cluster.prod",
            port = 22,
            username = "k8sadmin",
            kubectlPath = "kubectl",
            defaultNamespace = "default"
        )

        val pod = PodInfo(
            name = "multi-container-pod",
            namespace = "analytics",
            status = "Running",
            ready = "3/3",
            restarts = 0,
            age = "5d",
            containers = listOf("main-app", "sidecar-proxy", "log-collector"),
            images = listOf("analytics/app:v2", "envoy:1.20", "fluentd:latest")
        )

        val cmdContainer1 = TerminalLauncher.buildSshCommand(profile, pod.namespace, pod.name, pod.containers[0], "/bin/bash")
        val cmdContainer2 = TerminalLauncher.buildSshCommand(profile, pod.namespace, pod.name, pod.containers[1], "/bin/sh")
        val cmdContainer3 = TerminalLauncher.buildSshCommand(profile, pod.namespace, pod.name, pod.containers[2], "sh")

        assertEquals("ssh -t k8sadmin@cluster.prod \"kubectl exec -it -n analytics multi-container-pod -c main-app -- /bin/bash\"", cmdContainer1)
        assertEquals("ssh -t k8sadmin@cluster.prod \"kubectl exec -it -n analytics multi-container-pod -c sidecar-proxy -- /bin/sh\"", cmdContainer2)
        assertEquals("ssh -t k8sadmin@cluster.prod \"kubectl exec -it -n analytics multi-container-pod -c log-collector -- sh\"", cmdContainer3)
    }
}
