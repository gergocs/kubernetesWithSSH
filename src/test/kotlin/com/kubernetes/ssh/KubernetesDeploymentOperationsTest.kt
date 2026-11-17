package com.kubernetes.ssh

import com.kubernetes.model.AuthType
import com.kubernetes.model.DeploymentInfo
import com.kubernetes.model.PodInfo
import com.kubernetes.model.SshProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KubernetesDeploymentOperationsTest {

    @Test
    fun testDeploymentInfoModel() {
        val deployment = DeploymentInfo(
            name = "web-service",
            namespace = "production",
            readyReplicas = 3,
            replicas = 3,
            updatedReplicas = 3,
            availableReplicas = 3,
            age = "5d",
            images = listOf("nginx:1.25", "redis:7"),
            containers = listOf("web", "cache")
        )

        assertEquals("web-service", deployment.name)
        assertEquals("production", deployment.namespace)
        assertEquals(3, deployment.readyReplicas)
        assertEquals(3, deployment.replicas)
        assertEquals("5d", deployment.age)
        assertEquals(2, deployment.images.size)
        assertEquals(2, deployment.containers.size)
    }

    @Test
    fun testPodInfoDeploymentLinking() {
        val pod = PodInfo(
            name = "nginx-deployment-6b7f9d8c-z8k9w",
            namespace = "staging",
            status = "Running",
            ready = "1/1",
            restarts = 0,
            age = "2h",
            deploymentName = "nginx-deployment",
            ownerKind = "ReplicaSet",
            ownerName = "nginx-deployment-6b7f9d8c"
        )

        assertEquals("nginx-deployment", pod.deploymentName)
        assertEquals("ReplicaSet", pod.ownerKind)
        assertEquals("nginx-deployment-6b7f9d8c", pod.ownerName)
    }

    @Test
    fun testCommandResolutionForDeploymentOperations() {
        val profile = SshProfile(
            name = "prod",
            host = "192.168.1.100",
            username = "admin",
            authType = AuthType.PASSWORD,
            kubectlPath = "/usr/local/bin/kubectl"
        )
        val client = KubernetesSshClient(profile)

        val getCmd = client.resolveKubectlCommand("get deployment my-app -o yaml", "production", autoNamespace = true)
        assertEquals("/usr/local/bin/kubectl get deployment my-app -o yaml -n production", getCmd)

        val scaleCmd = client.resolveKubectlCommand("scale deployment my-app --replicas=5", "production", autoNamespace = true)
        assertEquals("/usr/local/bin/kubectl scale deployment my-app --replicas=5 -n production", scaleCmd)

        val rolloutCmd = client.resolveKubectlCommand("rollout restart deployment/my-app", "production", autoNamespace = true)
        assertEquals("/usr/local/bin/kubectl rollout restart deployment/my-app -n production", rolloutCmd)
    }

    @Test
    fun testParseDeploymentsWithSingleItemJson() {
        val singleJson = """
            {
              "apiVersion": "apps/v1",
              "kind": "Deployment",
              "metadata": {
                "name": "single-app",
                "namespace": "custom-ns",
                "creationTimestamp": "2026-11-12T10:00:00Z"
              },
              "spec": {
                "replicas": 4,
                "template": {
                  "spec": {
                    "containers": [
                      { "name": "app", "image": "custom/app:v3.2" }
                    ]
                  }
                }
              },
              "status": {
                "replicas": 4,
                "readyReplicas": 4,
                "updatedReplicas": 4,
                "availableReplicas": 4
              }
            }
        """.trimIndent()

        val parsed = KubernetesParser.parseDeploymentsJson(singleJson, "custom-ns")
        assertEquals(1, parsed.size)
        val dep = parsed[0]
        assertEquals("single-app", dep.name)
        assertEquals("custom-ns", dep.namespace)
        assertEquals(4, dep.replicas)
        assertEquals(4, dep.readyReplicas)
        assertEquals(listOf("custom/app:v3.2"), dep.images)
        assertEquals(listOf("app"), dep.containers)
    }
}
