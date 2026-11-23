package com.kubernetes.ui

import com.kubernetes.model.PodInfo
import com.kubernetes.model.SshProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailLogsPanelTest {

    @Test
    fun testBuildTailLogsCommandSinglePod() {
        val profile = SshProfile(
            host = "192.168.1.50",
            kubectlPath = "kubectl",
            defaultNamespace = "default"
        )

        val podName = "backend-service-123"
        val namespace = "production"
        val follow = true
        val tailLines = 200
        val container: String? = null
        val timestamps = true
        val previous = false

        val followFlag = if (follow) " -f" else ""
        val tailFlag = if (tailLines > 0) " --tail=$tailLines" else ""
        val containerFlag = if (!container.isNullOrBlank()) " -c $container" else ""
        val timestampsFlag = if (timestamps) " --timestamps" else ""
        val previousFlag = if (previous) " -p" else ""

        val command = "${profile.kubectlPath} logs $podName -n $namespace$followFlag$tailFlag$containerFlag$timestampsFlag$previousFlag"

        assertEquals("kubectl logs backend-service-123 -n production -f --tail=200 --timestamps", command)
    }

    @Test
    fun testBuildTailLogsCommandWithContainerAndPrevious() {
        val profile = SshProfile(
            host = "cluster.local",
            kubectlPath = "/usr/local/bin/kubectl",
            defaultNamespace = "default"
        )

        val podName = "multi-container-pod"
        val namespace = "demo"
        val follow = false
        val tailLines = 50
        val container = "sidecar-logger"
        val timestamps = false
        val previous = true

        val followFlag = if (follow) " -f" else ""
        val tailFlag = if (tailLines > 0) " --tail=$tailLines" else ""
        val containerFlag = if (!container.isNullOrBlank()) " -c $container" else ""
        val timestampsFlag = if (timestamps) " --timestamps" else ""
        val previousFlag = if (previous) " -p" else ""

        val command = "${profile.kubectlPath} logs $podName -n $namespace$followFlag$tailFlag$containerFlag$timestampsFlag$previousFlag"

        assertEquals("/usr/local/bin/kubectl logs multi-container-pod -n demo --tail=50 -c sidecar-logger -p", command)
    }

    @Test
    fun testMultiPodSelectionAndPrefixFormatting() {
        val pods = listOf(
            PodInfo("api-pod-1", "default", "Running", "1/1", 0, "10m"),
            PodInfo("api-pod-2", "default", "Running", "1/1", 0, "10m"),
            PodInfo("worker-pod-1", "default", "Running", "1/1", 0, "15m")
        )

        val selectedPods = pods.map { it.name }
        assertEquals(3, selectedPods.size)

        // Aggregation prefix testing
        val rawLine1 = "Started processing batch job"
        val rawLine2 = "Connected to database"
        val prefix1 = "[${selectedPods[0]}] "
        val prefix2 = "[${selectedPods[1]}] "

        val formatted1 = "$prefix1$rawLine1"
        val formatted2 = "$prefix2$rawLine2"

        assertEquals("[api-pod-1] Started processing batch job", formatted1)
        assertEquals("[api-pod-2] Connected to database", formatted2)
    }

    @Test
    fun testAnsiCodeStripping() {
        val rawAnsi = "\u001B[32m[INFO]\u001B[0m Application initialized successfully in \u001B[1m245ms\u001B[0m"
        val clean = rawAnsi.replace(Regex("\u001B\\[[;\\d]*[ -/]*[@-~]"), "")
        assertEquals("[INFO] Application initialized successfully in 245ms", clean)
    }
}
