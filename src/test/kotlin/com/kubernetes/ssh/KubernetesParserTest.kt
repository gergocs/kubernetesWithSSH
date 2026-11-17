package com.kubernetes.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class KubernetesParserTest {

    @Test
    fun testParseNamespacesJson() {
        val json = """
            {
              "apiVersion": "v1",
              "items": [
                {
                  "metadata": {
                    "name": "default"
                  }
                },
                {
                  "metadata": {
                    "name": "kube-system"
                  }
                },
                {
                  "metadata": {
                    "name": "production"
                  }
                }
              ]
            }
        """.trimIndent()

        val namespaces = KubernetesParser.parseNamespacesJson(json)
        assertEquals(3, namespaces.size)
        assertEquals(listOf("default", "kube-system", "production"), namespaces)
    }

    @Test
    fun testParseNamespacesFallback() {
        val output = """
            NAME
            default
            kube-system
            monitoring
        """.trimIndent()

        val namespaces = KubernetesParser.parseNamespacesJson(output)
        assertEquals(3, namespaces.size)
        assertEquals(listOf("default", "kube-system", "monitoring"), namespaces)
    }

    @Test
    fun testParsePodsJson() {
        val now = Instant.parse("2026-11-12T18:00:00Z")
        val json = """
            {
              "items": [
                {
                  "metadata": {
                    "name": "frontend-app-7b998cf-abc",
                    "namespace": "production",
                    "creationTimestamp": "2026-11-12T17:00:00Z"
                  },
                  "spec": {
                    "nodeName": "node-worker-1",
                    "containers": [
                      { "name": "web", "image": "nginx:1.25-alpine" }
                    ]
                  },
                  "status": {
                    "phase": "Running",
                    "podIP": "10.244.1.15",
                    "containerStatuses": [
                      {
                        "name": "web",
                        "ready": true,
                        "restartCount": 2,
                        "image": "nginx:1.25-alpine",
                        "imageID": "docker-pullable://nginx@sha256:4c0fdaa8b63410d93b1026eeacc165c766021a6ee52e1a3fa305336b33017187",
                        "state": {
                          "running": { "startedAt": "2026-11-12T17:05:00Z" }
                        }
                      }
                    ]
                  }
                },
                {
                  "metadata": {
                    "name": "backend-api-crashing-xyz",
                    "namespace": "production",
                    "creationTimestamp": "2026-11-12T17:50:00Z"
                  },
                  "spec": {
                    "nodeName": "node-worker-2",
                    "containers": [
                      { "name": "api", "image": "myregistry.io/api/backend:v2.1" }
                    ]
                  },
                  "status": {
                    "phase": "Running",
                    "podIP": "10.244.2.20",
                    "containerStatuses": [
                      {
                        "name": "api",
                        "ready": false,
                        "restartCount": 5,
                        "image": "myregistry.io/api/backend:v2.1",
                        "imageID": "containerd://sha256:7f9a12b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1",
                        "state": {
                          "waiting": {
                            "reason": "CrashLoopBackOff",
                            "message": "Back-off 5m0s restarting failed container"
                          }
                        }
                      }
                    ]
                  }
                },
                {
                  "metadata": {
                    "name": "terminating-pod-123",
                    "namespace": "production",
                    "deletionTimestamp": "2026-11-12T17:59:00Z",
                    "creationTimestamp": "2026-11-12T16:00:00Z"
                  },
                  "spec": {
                    "nodeName": "node-worker-1",
                    "containers": [
                      { "name": "worker", "image": "redis:7.0-alpine" },
                      { "name": "sidecar", "image": "envoyproxy/envoy:v1.28.0" }
                    ]
                  },
                  "status": {
                    "phase": "Running",
                    "podIP": "10.244.1.30",
                    "containerStatuses": [
                      {
                        "name": "worker",
                        "ready": false,
                        "restartCount": 0,
                        "image": "redis:7.0-alpine",
                        "imageID": "docker-pullable://redis@sha256:12345",
                        "state": {
                          "running": {}
                        }
                      },
                      {
                        "name": "sidecar",
                        "ready": false,
                        "restartCount": 0,
                        "image": "envoyproxy/envoy:v1.28.0",
                        "imageID": "docker-pullable://envoy@sha256:67890",
                        "state": {
                          "running": {}
                        }
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val pods = KubernetesParser.parsePodsJson(json, "production")
        assertEquals(3, pods.size)

        val runningPod = pods[0]
        assertEquals("frontend-app-7b998cf-abc", runningPod.name)
        assertEquals("production", runningPod.namespace)
        assertEquals("Running", runningPod.status)
        assertEquals("1/1", runningPod.ready)
        assertEquals(2, runningPod.restarts)
        assertEquals("node-worker-1", runningPod.node)
        assertEquals("10.244.1.15", runningPod.ip)
        assertEquals("nginx:1.25-alpine", runningPod.image)
        assertEquals(listOf("nginx:1.25-alpine"), runningPod.images)
        assertEquals(listOf("web"), runningPod.containers)
        assertEquals("4c0fdaa8b634", runningPod.imageId)
        assertEquals(listOf("4c0fdaa8b634"), runningPod.imageIds)
        assertEquals("frontend-app", runningPod.deploymentName)

        val crashingPod = pods[1]
        assertEquals("backend-api-crashing-xyz", crashingPod.name)
        assertEquals("CrashLoopBackOff", crashingPod.status)
        assertEquals("0/1", crashingPod.ready)
        assertEquals(5, crashingPod.restarts)
        assertEquals("myregistry.io/api/backend:v2.1", crashingPod.image)
        assertEquals(listOf("myregistry.io/api/backend:v2.1"), crashingPod.images)
        assertEquals(listOf("api"), crashingPod.containers)
        assertEquals("7f9a12b3c4d5", crashingPod.imageId)
        assertEquals(listOf("7f9a12b3c4d5"), crashingPod.imageIds)

        val terminatingPod = pods[2]
        assertEquals("terminating-pod-123", terminatingPod.name)
        assertEquals("Terminating", terminatingPod.status)
        assertEquals("redis:7.0-alpine, envoyproxy/envoy:v1.28.0", terminatingPod.image)
        assertEquals(listOf("redis:7.0-alpine", "envoyproxy/envoy:v1.28.0"), terminatingPod.images)
        assertEquals(listOf("worker", "sidecar"), terminatingPod.containers)
        assertEquals("12345, 67890", terminatingPod.imageId)
        assertEquals(listOf("12345", "67890"), terminatingPod.imageIds)
    }

    @Test
    fun testShortenImageId() {
        assertEquals("8251a1f0ba5d", KubernetesParser.shortenImageId("sha256:8251a1f0ba5d8251a1f0ba5d"))
        assertEquals("8251a1f0ba5d", KubernetesParser.shortenImageId("ghcr.io/maintainerr/maintainerr@sha256:8251a1f0ba5d8251a1f0ba5d"))
        assertEquals("4c0fdaa8b634", KubernetesParser.shortenImageId("docker-pullable://nginx@sha256:4c0fdaa8b63410d93b1026eeacc165c766021a6ee52e1a3fa305336b33017187"))
        assertEquals("7f9a12b3c4d5", KubernetesParser.shortenImageId("containerd://sha256:7f9a12b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1"))
        assertEquals("8251a1f0ba5d", KubernetesParser.shortenImageId("docker://8251a1f0ba5daaaaaabbbbbb"))
        assertEquals("8251a1f0ba5d", KubernetesParser.shortenImageId("8251a1f0ba5d"))
        assertEquals("", KubernetesParser.shortenImageId(""))
    }

    @Test
    fun testFormatAge() {
        val now = Instant.parse("2026-11-12T18:00:00Z")

        val ageSecs = KubernetesParser.formatAge("2026-11-12T17:59:45Z", now)
        assertEquals("15s", ageSecs)

        val ageMins = KubernetesParser.formatAge("2026-11-12T17:35:00Z", now)
        assertEquals("25m", ageMins)

        val ageHours = KubernetesParser.formatAge("2026-11-12T15:00:00Z", now)
        assertEquals("3h", ageHours)

        val ageDays = KubernetesParser.formatAge("2026-11-10T18:00:00Z", now)
        assertEquals("2d", ageDays)
    }

    @Test
    fun testParseDockerImagesTable() {
        val tableOutput = """
            REPOSITORY                                TAG                                                                           IMAGE ID       CREATED         SIZE
            ghcr.io/maintainerr/maintainerr           latest                                                                        8251a1f0ba5d   5 weeks ago     742MB
            nginx                                     1.25-alpine                                                                   4c0fdaa8b634   2 weeks ago     42.6MB
            <none>                                    <none>                                                                        d5e321a91234   3 months ago    1.2GB
        """.trimIndent()

        val images = KubernetesParser.parseDockerImagesTable(tableOutput)
        assertEquals(3, images.size)

        val first = images[0]
        assertEquals("ghcr.io/maintainerr/maintainerr", first.repository)
        assertEquals("latest", first.tag)
        assertEquals("8251a1f0ba5d", first.imageId)
        assertEquals("5 weeks ago", first.created)
        assertEquals("742MB", first.size)

        val second = images[1]
        assertEquals("nginx", second.repository)
        assertEquals("1.25-alpine", second.tag)
        assertEquals("4c0fdaa8b634", second.imageId)
        assertEquals("2 weeks ago", second.created)
        assertEquals("42.6MB", second.size)

        val third = images[2]
        assertEquals("<none>", third.repository)
        assertEquals("<none>", third.tag)
        assertEquals("d5e321a91234", third.imageId)
        assertEquals("3 months ago", third.created)
        assertEquals("1.2GB", third.size)
    }

    @Test
    fun testParseDockerImagesJson() {
        val jsonOutput = """
            {"Repository":"ghcr.io/maintainerr/maintainerr","Tag":"latest","ID":"8251a1f0ba5d","CreatedSince":"5 weeks ago","Size":"742MB"}
            {"Repository":"nginx","Tag":"1.25-alpine","ID":"4c0fdaa8b63410d93b1026eeacc165c766021a6ee52e1a3fa305336b33017187","CreatedSince":"2 weeks ago","Size":"42.6MB"}
        """.trimIndent()

        val images = KubernetesParser.parseDockerImagesJson(jsonOutput)
        assertEquals(2, images.size)

        assertEquals("ghcr.io/maintainerr/maintainerr", images[0].repository)
        assertEquals("latest", images[0].tag)
        assertEquals("8251a1f0ba5d", images[0].imageId)
        assertEquals("5 weeks ago", images[0].created)
        assertEquals("742MB", images[0].size)

        assertEquals("nginx", images[1].repository)
        assertEquals("1.25-alpine", images[1].tag)
        assertEquals("4c0fdaa8b634", images[1].imageId)
        assertEquals("2 weeks ago", images[1].created)
        assertEquals("42.6MB", images[1].size)
    }

    @Test
    fun testParseDeploymentsJson() {
        val json = """
            {
              "apiVersion": "apps/v1",
              "items": [
                {
                  "metadata": {
                    "name": "web-frontend",
                    "namespace": "production",
                    "creationTimestamp": "2026-11-12T17:00:00Z"
                  },
                  "spec": {
                    "replicas": 3,
                    "template": {
                      "spec": {
                        "containers": [
                          { "name": "nginx", "image": "nginx:1.25-alpine" }
                        ]
                      }
                    }
                  },
                  "status": {
                    "replicas": 3,
                    "readyReplicas": 3,
                    "updatedReplicas": 3,
                    "availableReplicas": 3
                  }
                },
                {
                  "metadata": {
                    "name": "api-service",
                    "namespace": "production",
                    "creationTimestamp": "2026-11-12T17:30:00Z"
                  },
                  "spec": {
                    "replicas": 2,
                    "template": {
                      "spec": {
                        "containers": [
                          { "name": "api", "image": "myregistry.io/api:v1.0" },
                          { "name": "sidecar", "image": "envoyproxy/envoy:v1.28" }
                        ]
                      }
                    }
                  },
                  "status": {
                    "replicas": 2,
                    "readyReplicas": 1,
                    "updatedReplicas": 2,
                    "availableReplicas": 1
                  }
                }
              ]
            }
        """.trimIndent()

        val deployments = KubernetesParser.parseDeploymentsJson(json, "production")
        assertEquals(2, deployments.size)

        val dep1 = deployments[0]
        assertEquals("web-frontend", dep1.name)
        assertEquals("production", dep1.namespace)
        assertEquals(3, dep1.replicas)
        assertEquals(3, dep1.readyReplicas)
        assertEquals(listOf("nginx"), dep1.containers)
        assertEquals(listOf("nginx:1.25-alpine"), dep1.images)

        val dep2 = deployments[1]
        assertEquals("api-service", dep2.name)
        assertEquals("production", dep2.namespace)
        assertEquals(2, dep2.replicas)
        assertEquals(1, dep2.readyReplicas)
        assertEquals(listOf("api", "sidecar"), dep2.containers)
        assertEquals(listOf("myregistry.io/api:v1.0", "envoyproxy/envoy:v1.28"), dep2.images)
    }

    @Test
    fun testParseDeploymentsTable() {
        val table = """
            NAME           READY   UP-TO-DATE   AVAILABLE   AGE   CONTAINERS     IMAGES
            web-frontend   3/3     3            3           2d    nginx          nginx:1.25-alpine
            api-service    1/2     2            1           1h    api,sidecar    myregistry.io/api:v1.0,envoyproxy/envoy:v1.28
        """.trimIndent()

        val deployments = KubernetesParser.parseDeploymentsTable(table, "default")
        assertEquals(2, deployments.size)

        val dep1 = deployments[0]
        assertEquals("web-frontend", dep1.name)
        assertEquals(3, dep1.readyReplicas)
        assertEquals(3, dep1.replicas)
        assertEquals(3, dep1.updatedReplicas)
        assertEquals(3, dep1.availableReplicas)
        assertEquals("2d", dep1.age)
        assertEquals(listOf("nginx"), dep1.containers)
        assertEquals(listOf("nginx:1.25-alpine"), dep1.images)

        val dep2 = deployments[1]
        assertEquals("api-service", dep2.name)
        assertEquals(1, dep2.readyReplicas)
        assertEquals(2, dep2.replicas)
        assertEquals(2, dep2.updatedReplicas)
        assertEquals(1, dep2.availableReplicas)
        assertEquals("1h", dep2.age)
        assertEquals(listOf("api", "sidecar"), dep2.containers)
        assertEquals(listOf("myregistry.io/api:v1.0", "envoyproxy/envoy:v1.28"), dep2.images)
    }

    @Test
    fun testInferDeploymentName() {
        assertEquals(
            "frontend-app",
            KubernetesParser.inferDeploymentName(
                podName = "frontend-app-7b998cf-abc12",
                ownerKind = "ReplicaSet",
                ownerName = "frontend-app-7b998cf"
            )
        )

        assertEquals(
            "direct-deployment",
            KubernetesParser.inferDeploymentName(
                podName = "direct-deployment-xyz",
                ownerKind = "Deployment",
                ownerName = "direct-deployment"
            )
        )

        assertEquals(
            "app-from-label",
            KubernetesParser.inferDeploymentName(
                podName = "custom-pod-name",
                labels = mapOf("app.kubernetes.io/name" to "app-from-label")
            )
        )

        assertEquals(
            "my-service",
            KubernetesParser.inferDeploymentName(
                podName = "my-service-6789abcd-12345"
            )
        )
    }
}
