package com.kubernetes.terminal

import com.kubernetes.model.SshProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class PodTerminalSessionTest {

    @Test
    fun testPodTerminalSessionInitialization() {
        val profile = SshProfile(
            name = "Test-Profile",
            host = "192.168.1.50",
            port = 2222,
            username = "admin",
            kubectlPath = "/usr/local/bin/kubectl",
            defaultNamespace = "kube-system"
        )

        var lastOutput = ""
        var lastStatus = ""
        var closedExitCode = -999

        val session = PodTerminalSession(
            profile = profile,
            namespace = "production",
            podName = "nginx-deployment-abc-123",
            container = "nginx-web",
            shell = "/bin/bash",
            onOutput = { lastOutput = it },
            onStatusChange = { lastStatus = it },
            onClosed = { closedExitCode = it }
        )

        assertEquals("production", session.namespace)
        assertEquals("nginx-deployment-abc-123", session.podName)
        assertEquals("nginx-web", session.container)
        assertEquals("/bin/bash", session.shell)
        assertFalse(session.isSessionRunning)

        // Close should be safe even before start
        session.close()
        assertFalse(session.isSessionRunning)
    }

    @Test
    fun testPodTerminalSessionDefaultValues() {
        val profile = SshProfile()

        val session = PodTerminalSession(
            profile = profile,
            namespace = "",
            podName = "simple-pod",
            onOutput = {},
            onStatusChange = {},
            onClosed = {}
        )

        assertEquals("", session.namespace)
        assertEquals("simple-pod", session.podName)
        assertEquals(null, session.container)
        assertEquals("/bin/sh", session.shell)
        assertFalse(session.isSessionRunning)
    }
}
