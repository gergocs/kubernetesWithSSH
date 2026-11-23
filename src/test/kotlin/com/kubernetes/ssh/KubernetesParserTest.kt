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

    @Test
    fun testParseServicesJson() {
        val json = """
            {
              "apiVersion": "v1",
              "items": [
                {
                  "metadata": {
                    "name": "kubernetes",
                    "namespace": "default",
                    "creationTimestamp": "2026-11-01T10:00:00Z"
                  },
                  "spec": {
                    "type": "ClusterIP",
                    "clusterIP": "10.96.0.1",
                    "ports": [
                      { "name": "https", "port": 443, "protocol": "TCP", "targetPort": 6443 }
                    ]
                  }
                },
                {
                  "metadata": {
                    "name": "frontend-service",
                    "namespace": "production",
                    "creationTimestamp": "2026-11-05T12:00:00Z"
                  },
                  "spec": {
                    "type": "LoadBalancer",
                    "clusterIP": "10.96.10.50",
                    "ports": [
                      { "name": "http", "port": 80, "nodePort": 30080, "protocol": "TCP" },
                      { "name": "https", "port": 443, "nodePort": 30443, "protocol": "TCP" }
                    ],
                    "selector": {
                      "app": "frontend",
                      "tier": "web"
                    }
                  },
                  "status": {
                    "loadBalancer": {
                      "ingress": [
                        { "ip": "203.0.113.10" }
                      ]
                    }
                  }
                },
                {
                  "metadata": {
                    "name": "external-db",
                    "namespace": "production"
                  },
                  "spec": {
                    "type": "ExternalName",
                    "externalName": "db.example.com",
                    "clusterIP": ""
                  }
                }
              ]
            }
        """.trimIndent()

        val services = KubernetesParser.parseServicesJson(json, "default")
        assertEquals(3, services.size)

        val svc1 = services[0]
        assertEquals("kubernetes", svc1.name)
        assertEquals("default", svc1.namespace)
        assertEquals("ClusterIP", svc1.type)
        assertEquals("10.96.0.1", svc1.clusterIP)
        assertEquals("<none>", svc1.externalIP)
        assertEquals("443/TCP", svc1.ports)

        val svc2 = services[1]
        assertEquals("frontend-service", svc2.name)
        assertEquals("production", svc2.namespace)
        assertEquals("LoadBalancer", svc2.type)
        assertEquals("10.96.10.50", svc2.clusterIP)
        assertEquals("203.0.113.10", svc2.externalIP)
        assertEquals("80:30080/TCP,443:30443/TCP", svc2.ports)
        assertTrue(svc2.selector.contains("app=frontend"))
        assertTrue(svc2.selector.contains("tier=web"))

        val svc3 = services[2]
        assertEquals("external-db", svc3.name)
        assertEquals("production", svc3.namespace)
        assertEquals("ExternalName", svc3.type)
        assertEquals("db.example.com", svc3.externalIP)
    }

    @Test
    fun testParseServicesTable() {
        val table = """
            NAME               TYPE           CLUSTER-IP     EXTERNAL-IP    PORT(S)                  AGE   SELECTOR
            kubernetes         ClusterIP      10.96.0.1      <none>         443/TCP                  10d   <none>
            frontend-service   LoadBalancer   10.96.10.50    203.0.113.10   80:30080/TCP,443/TCP     2d    app=frontend
        """.trimIndent()

        val services = KubernetesParser.parseServicesTable(table, "default")
        assertEquals(2, services.size)

        val svc1 = services[0]
        assertEquals("kubernetes", svc1.name)
        assertEquals("ClusterIP", svc1.type)
        assertEquals("10.96.0.1", svc1.clusterIP)
        assertEquals("<none>", svc1.externalIP)
        assertEquals("443/TCP", svc1.ports)
        assertEquals("10d", svc1.age)

        val svc2 = services[1]
        assertEquals("frontend-service", svc2.name)
        assertEquals("LoadBalancer", svc2.type)
        assertEquals("10.96.10.50", svc2.clusterIP)
        assertEquals("203.0.113.10", svc2.externalIP)
        assertEquals("80:30080/TCP,443/TCP", svc2.ports)
        assertEquals("2d", svc2.age)
    }

    @Test
    fun testParseConfigMapsJson() {
        val json = """
            {
              "apiVersion": "v1",
              "items": [
                {
                  "metadata": {
                    "name": "app-config",
                    "namespace": "production",
                    "creationTimestamp": "2026-11-01T10:00:00Z"
                  },
                  "data": {
                    "application.yml": "server:\n  port: 8080",
                    "database.url": "jdbc:postgresql://postgres:5432/db"
                  }
                },
                {
                  "metadata": {
                    "name": "kube-root-ca.crt",
                    "namespace": "kube-system",
                    "creationTimestamp": "2026-10-01T10:00:00Z"
                  },
                  "data": {
                    "ca.crt": "-----BEGIN CERTIFICATE-----\nMIIC...\n-----END CERTIFICATE-----"
                  }
                }
              ]
            }
        """.trimIndent()

        val cms = KubernetesParser.parseConfigMapsJson(json, "default")
        assertEquals(2, cms.size)

        val cm1 = cms[0]
        assertEquals("app-config", cm1.name)
        assertEquals("production", cm1.namespace)
        assertEquals(2, cm1.dataCount)
        assertTrue(cm1.keys.contains("application.yml"))
        assertTrue(cm1.keys.contains("database.url"))
        assertEquals("server:\n  port: 8080", cm1.data["application.yml"])

        val cm2 = cms[1]
        assertEquals("kube-root-ca.crt", cm2.name)
        assertEquals("kube-system", cm2.namespace)
        assertEquals(1, cm2.dataCount)
        assertEquals(listOf("ca.crt"), cm2.keys)
    }

    @Test
    fun testParseConfigMapsTable() {
        val table = """
            NAME               DATA   AGE
            app-config         2      5d
            kube-root-ca.crt   1      30d
        """.trimIndent()

        val cms = KubernetesParser.parseConfigMapsTable(table, "default")
        assertEquals(2, cms.size)

        val cm1 = cms[0]
        assertEquals("app-config", cm1.name)
        assertEquals(2, cm1.dataCount)
        assertEquals("5d", cm1.age)

        val cm2 = cms[1]
        assertEquals("kube-root-ca.crt", cm2.name)
        assertEquals(1, cm2.dataCount)
        assertEquals("30d", cm2.age)
    }

    // MARK: - Secrets Tests
    @Test
    fun testParseSecretsJson() {
        val json = """
            {
              "apiVersion": "v1",
              "items": [
                {
                  "metadata": {
                    "name": "db-secret",
                    "namespace": "production",
                    "creationTimestamp": "2026-11-01T10:00:00Z"
                  },
                  "type": "Opaque",
                  "data": {
                    "password": "c3VwZXJzZWNyZXQ=",
                    "username": "cG9zdGdyZXM="
                  }
                },
                {
                  "metadata": {
                    "name": "tls-cert",
                    "namespace": "production",
                    "creationTimestamp": "2026-10-01T10:00:00Z"
                  },
                  "type": "kubernetes.io/tls",
                  "data": {
                    "tls.crt": "LS0tLS1CRUdJTi...",
                    "tls.key": "LS0tLS1CRUdJTi..."
                  }
                }
              ]
            }
        """.trimIndent()

        val secrets = KubernetesParser.parseSecretsJson(json, "default")
        assertEquals(2, secrets.size)

        val s1 = secrets[0]
        assertEquals("db-secret", s1.name)
        assertEquals("production", s1.namespace)
        assertEquals("Opaque", s1.type)
        assertEquals(2, s1.dataCount)
        assertTrue(s1.keys.contains("password"))
        assertTrue(s1.keys.contains("username"))
        assertEquals("c3VwZXJzZWNyZXQ=", s1.data["password"])

        val s2 = secrets[1]
        assertEquals("tls-cert", s2.name)
        assertEquals("kubernetes.io/tls", s2.type)
        assertEquals(2, s2.dataCount)
    }

    @Test
    fun testParseSecretsTable() {
        val table = """
            NAME        TYPE               DATA   AGE
            db-secret   Opaque             2      5d
            tls-cert    kubernetes.io/tls  2      30d
        """.trimIndent()

        val secrets = KubernetesParser.parseSecretsTable(table, "production")
        assertEquals(2, secrets.size)
        assertEquals("db-secret", secrets[0].name)
        assertEquals("Opaque", secrets[0].type)
        assertEquals(2, secrets[0].dataCount)
        assertEquals("5d", secrets[0].age)
    }

    // MARK: - Ingresses Tests
    @Test
    fun testParseIngressesJson() {
        val json = """
            {
              "apiVersion": "networking.k8s.io/v1",
              "items": [
                {
                  "metadata": {
                    "name": "app-ingress",
                    "namespace": "production",
                    "creationTimestamp": "2026-11-01T10:00:00Z"
                  },
                  "spec": {
                    "ingressClassName": "nginx",
                    "rules": [
                      {
                        "host": "api.example.com",
                        "http": {
                          "paths": [
                            {
                              "path": "/v1",
                              "pathType": "Prefix",
                              "backend": {
                                "service": {
                                  "name": "api-service",
                                  "port": { "number": 8080 }
                                }
                              }
                            }
                          ]
                        }
                      },
                      {
                        "host": "app.example.com",
                        "http": {
                          "paths": [
                            {
                              "path": "/",
                              "pathType": "Prefix",
                              "backend": {
                                "service": {
                                  "name": "web-service",
                                  "port": { "number": 80 }
                                }
                              }
                            }
                          ]
                        }
                      }
                    ],
                    "tls": [
                      { "hosts": ["api.example.com", "app.example.com"] }
                    ]
                  },
                  "status": {
                    "loadBalancer": {
                      "ingress": [
                        { "ip": "198.51.100.1" }
                      ]
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        val ingresses = KubernetesParser.parseIngressesJson(json, "default")
        assertEquals(1, ingresses.size)

        val ing = ingresses[0]
        assertEquals("app-ingress", ing.name)
        assertEquals("production", ing.namespace)
        assertEquals("nginx", ing.className)
        assertEquals("api.example.com, app.example.com", ing.hosts)
        assertEquals("198.51.100.1", ing.address)
        assertEquals("80, 443", ing.ports)
        assertEquals(2, ing.rules.size)
        assertEquals("api-service", ing.rules[0].backendService)
        assertEquals("8080", ing.rules[0].backendPort)
    }

    @Test
    fun testParseIngressesTable() {
        val table = """
            NAME          CLASS   HOSTS                           ADDRESS        PORTS     AGE
            app-ingress   nginx   api.example.com,app.example.com 198.51.100.1   80, 443   10d
        """.trimIndent()

        val ingresses = KubernetesParser.parseIngressesTable(table, "default")
        assertEquals(1, ingresses.size)
        assertEquals("app-ingress", ingresses[0].name)
        assertEquals("nginx", ingresses[0].className)
        assertEquals("198.51.100.1", ingresses[0].address)
        assertEquals("80, 443", ingresses[0].ports)
        assertEquals("10d", ingresses[0].age)
    }

    // MARK: - Jobs Tests
    @Test
    fun testParseJobsJson() {
        val json = """
            {
              "apiVersion": "batch/v1",
              "items": [
                {
                  "metadata": {
                    "name": "db-migration-job",
                    "namespace": "production",
                    "creationTimestamp": "2026-11-10T10:00:00Z"
                  },
                  "spec": {
                    "completions": 1,
                    "template": {
                      "spec": {
                        "containers": [
                          { "name": "migrator", "image": "flyway/flyway:latest" }
                        ]
                      }
                    }
                  },
                  "status": {
                    "succeeded": 1,
                    "startTime": "2026-11-10T10:00:00Z",
                    "completionTime": "2026-11-10T10:01:15Z"
                  }
                }
              ]
            }
        """.trimIndent()

        val jobs = KubernetesParser.parseJobsJson(json, "default")
        assertEquals(1, jobs.size)

        val j = jobs[0]
        assertEquals("db-migration-job", j.name)
        assertEquals("1/1", j.completions)
        assertEquals("1m15s", j.duration)
        assertEquals(listOf("migrator"), j.containers)
        assertEquals(listOf("flyway/flyway:latest"), j.images)
        assertEquals("Complete", j.condition)
    }

    @Test
    fun testParseJobsTable() {
        val table = """
            NAME               COMPLETIONS   DURATION   AGE
            db-migration-job   1/1           1m15s      2d
        """.trimIndent()

        val jobs = KubernetesParser.parseJobsTable(table, "default")
        assertEquals(1, jobs.size)
        assertEquals("db-migration-job", jobs[0].name)
        assertEquals("1/1", jobs[0].completions)
        assertEquals("1m15s", jobs[0].duration)
        assertEquals("2d", jobs[0].age)
    }

    // MARK: - CronJobs Tests
    @Test
    fun testParseCronJobsJson() {
        val json = """
            {
              "apiVersion": "batch/v1",
              "items": [
                {
                  "metadata": {
                    "name": "nightly-cleanup",
                    "namespace": "production",
                    "creationTimestamp": "2026-11-01T10:00:00Z"
                  },
                  "spec": {
                    "schedule": "0 2 * * *",
                    "suspend": false,
                    "jobTemplate": {
                      "spec": {
                        "template": {
                          "spec": {
                            "containers": [
                              { "name": "cleaner", "image": "cleaner:1.0" }
                            ]
                          }
                        }
                      }
                    }
                  },
                  "status": {
                    "active": []
                  }
                }
              ]
            }
        """.trimIndent()

        val cronJobs = KubernetesParser.parseCronJobsJson(json, "default")
        assertEquals(1, cronJobs.size)

        val cj = cronJobs[0]
        assertEquals("nightly-cleanup", cj.name)
        assertEquals("0 2 * * *", cj.schedule)
        assertEquals(false, cj.suspend)
        assertEquals(0, cj.active)
        assertEquals(listOf("cleaner"), cj.containers)
    }

    @Test
    fun testParseCronJobsTable() {
        val table = """
            NAME              SCHEDULE    SUSPEND   ACTIVE   LAST SCHEDULE   AGE
            nightly-cleanup   0 2 * * *   False     0        5h              10d
        """.trimIndent()

        val cronJobs = KubernetesParser.parseCronJobsTable(table, "default")
        assertEquals(1, cronJobs.size)
        assertEquals("nightly-cleanup", cronJobs[0].name)
        assertEquals("0 2 * * *", cronJobs[0].schedule)
        assertEquals(false, cronJobs[0].suspend)
        assertEquals(0, cronJobs[0].active)
        assertEquals("5h", cronJobs[0].lastSchedule)
        assertEquals("10d", cronJobs[0].age)
    }

    // MARK: - Events Tests
    @Test
    fun testParseEventsJson() {
        val json = """
            {
              "apiVersion": "v1",
              "items": [
                {
                  "metadata": {
                    "name": "pod-event-1",
                    "namespace": "production",
                    "creationTimestamp": "2026-11-12T10:00:00Z"
                  },
                  "type": "Warning",
                  "reason": "FailedScheduling",
                  "message": "0/3 nodes are available: 3 Insufficient memory.",
                  "involvedObject": {
                    "kind": "Pod",
                    "name": "heavy-backend-xyz"
                  },
                  "source": {
                    "component": "default-scheduler"
                  },
                  "count": 4
                }
              ]
            }
        """.trimIndent()

        val events = KubernetesParser.parseEventsJson(json, "default")
        assertEquals(1, events.size)

        val ev = events[0]
        assertEquals("Warning", ev.type)
        assertEquals("FailedScheduling", ev.reason)
        assertEquals("Pod/heavy-backend-xyz", ev.involvedObject)
        assertEquals("0/3 nodes are available: 3 Insufficient memory.", ev.message)
        assertEquals("default-scheduler", ev.source)
        assertEquals(4, ev.count)
    }

    @Test
    fun testParseEventsJsonWithNullFields() {
        val jsonWithNulls = """
            {
              "apiVersion": "v1",
              "items": [
                {
                  "metadata": {
                    "name": "demo-backend-1.180...",
                    "namespace": "demo-app",
                    "creationTimestamp": "2026-11-21T15:40:00Z"
                  },
                  "type": "Normal",
                  "reason": "Started",
                  "message": "Started container demo-backend",
                  "involvedObject": {
                    "kind": "Pod",
                    "name": "demo-backend-1"
                  },
                  "eventTime": null,
                  "firstTimestamp": "2026-11-21T15:40:00Z",
                  "lastTimestamp": "2026-11-21T15:40:00Z",
                  "reportingComponent": null,
                  "reportingInstance": null,
                  "source": {
                    "component": "kubelet",
                    "host": "minikube"
                  },
                  "count": 1
                }
              ]
            }
        """.trimIndent()

        val events = KubernetesParser.parseEventsJson(jsonWithNulls, "demo-app")
        assertEquals(1, events.size)

        val ev = events[0]
        assertEquals("Normal", ev.type)
        assertEquals("Started", ev.reason)
        assertEquals("Pod/demo-backend-1", ev.involvedObject)
        assertEquals("Started container demo-backend", ev.message)
        assertEquals("kubelet", ev.source)
        assertEquals(1, ev.count)
    }

    @Test
    fun testParseEventsJsonEventsK8sIoFormat() {
        val json = """
            {
              "apiVersion": "events.k8s.io/v1",
              "items": [
                {
                  "metadata": {
                    "name": "ingress-ev-1",
                    "namespace": "ingress-nginx",
                    "creationTimestamp": "2026-11-21T12:00:00Z"
                  },
                  "type": "Normal",
                  "reason": "RELOAD",
                  "note": "NGINX reload triggered due to config update",
                  "regarding": {
                    "kind": "Ingress",
                    "name": "main-ingress"
                  },
                  "reportingController": "nginx-ingress-controller",
                  "series": {
                    "count": 3,
                    "lastObservedTime": "2026-11-21T12:30:00Z"
                  }
                }
              ]
            }
        """.trimIndent()

        val events = KubernetesParser.parseEventsJson(json, "ingress-nginx")
        assertEquals(1, events.size)

        val ev = events[0]
        assertEquals("Normal", ev.type)
        assertEquals("RELOAD", ev.reason)
        assertEquals("Ingress/main-ingress", ev.involvedObject)
        assertEquals("NGINX reload triggered due to config update", ev.message)
        assertEquals("nginx-ingress-controller", ev.source)
        assertEquals(3, ev.count)
    }

    @Test
    fun testParseEventsJsonCorruptedDoesNotFallbackToTableGarbage() {
        val corruptedJson = """
            {
              "apiVersion": "v1",
              "invalidStructure": true
            }
        """.trimIndent()

        val events = KubernetesParser.parseEventsJson(corruptedJson, "default")
        assertEquals(0, events.size)
    }

    @Test
    fun testParseEventsTable() {
        val table = """
            LAST SEEN   TYPE      REASON             OBJECT                  MESSAGE
            5m          Warning   FailedScheduling   Pod/heavy-backend-xyz   0/3 nodes are available: 3 Insufficient memory.
        """.trimIndent()

        val events = KubernetesParser.parseEventsTable(table, "default")
        assertEquals(1, events.size)
        assertEquals("Warning", events[0].type)
        assertEquals("FailedScheduling", events[0].reason)
        assertEquals("Pod/heavy-backend-xyz", events[0].involvedObject)
        assertEquals("0/3 nodes are available: 3 Insufficient memory.", events[0].message)
        assertEquals("5m", events[0].age)
    }

    @Test
    fun testParseEventsTableWithNamespaceColumn() {
        val table = """
            NAMESPACE   LAST SEEN   TYPE      REASON      OBJECT       MESSAGE
            demo-app    12m         Normal    Scheduled   pod/web-1    Successfully assigned demo-app/web-1 to node-a
        """.trimIndent()

        val events = KubernetesParser.parseEventsTable(table, "default")
        assertEquals(1, events.size)
        assertEquals("demo-app", events[0].namespace)
        assertEquals("Normal", events[0].type)
        assertEquals("Scheduled", events[0].reason)
        assertEquals("pod/web-1", events[0].involvedObject)
        assertEquals("Successfully assigned demo-app/web-1 to node-a", events[0].message)
        assertEquals("12m", events[0].age)
    }

    // MARK: - StatefulSets Tests
    @Test
    fun testParseStatefulSetsJson() {
        val json = """
            {
              "apiVersion": "apps/v1",
              "items": [
                {
                  "metadata": {
                    "name": "redis-cluster",
                    "namespace": "production",
                    "creationTimestamp": "2026-11-01T10:00:00Z"
                  },
                  "spec": {
                    "replicas": 3,
                    "template": {
                      "spec": {
                        "containers": [
                          { "name": "redis", "image": "redis:7.0-alpine" }
                        ]
                      }
                    }
                  },
                  "status": {
                    "readyReplicas": 3
                  }
                }
              ]
            }
        """.trimIndent()

        val stsList = KubernetesParser.parseStatefulSetsJson(json, "default")
        assertEquals(1, stsList.size)

        val sts = stsList[0]
        assertEquals("redis-cluster", sts.name)
        assertEquals(3, sts.readyReplicas)
        assertEquals(3, sts.replicas)
        assertEquals(listOf("redis"), sts.containers)
        assertEquals(listOf("redis:7.0-alpine"), sts.images)
    }

    @Test
    fun testParseStatefulSetsTable() {
        val table = """
            NAME            READY   AGE
            redis-cluster   3/3     15d
        """.trimIndent()

        val stsList = KubernetesParser.parseStatefulSetsTable(table, "default")
        assertEquals(1, stsList.size)
        assertEquals("redis-cluster", stsList[0].name)
        assertEquals(3, stsList[0].readyReplicas)
        assertEquals(3, stsList[0].replicas)
        assertEquals("15d", stsList[0].age)
    }

    // MARK: - DaemonSets Tests
    @Test
    fun testParseDaemonSetsJson() {
        val json = """
            {
              "apiVersion": "apps/v1",
              "items": [
                {
                  "metadata": {
                    "name": "fluentd-logger",
                    "namespace": "kube-system",
                    "creationTimestamp": "2026-11-01T10:00:00Z"
                  },
                  "spec": {
                    "template": {
                      "spec": {
                        "nodeSelector": { "beta.kubernetes.io/os": "linux" },
                        "containers": [
                          { "name": "fluentd", "image": "fluent/fluentd-kubernetes-daemonset:v1.14" }
                        ]
                      }
                    }
                  },
                  "status": {
                    "desiredNumberScheduled": 5,
                    "currentNumberScheduled": 5,
                    "numberReady": 5,
                    "updatedNumberScheduled": 5,
                    "numberAvailable": 5
                  }
                }
              ]
            }
        """.trimIndent()

        val dsList = KubernetesParser.parseDaemonSetsJson(json, "default")
        assertEquals(1, dsList.size)

        val ds = dsList[0]
        assertEquals("fluentd-logger", ds.name)
        assertEquals(5, ds.desired)
        assertEquals(5, ds.current)
        assertEquals(5, ds.ready)
        assertEquals(5, ds.upToDate)
        assertEquals(5, ds.available)
        assertEquals("beta.kubernetes.io/os=linux", ds.nodeSelector)
    }

    @Test
    fun testParseDaemonSetsTable() {
        val table = """
            NAME             DESIRED   CURRENT   READY   UP-TO-DATE   AVAILABLE   NODE SELECTOR   AGE
            fluentd-logger   5         5         5       5            5           <none>          15d
        """.trimIndent()

        val dsList = KubernetesParser.parseDaemonSetsTable(table, "default")
        assertEquals(1, dsList.size)
        assertEquals("fluentd-logger", dsList[0].name)
        assertEquals(5, dsList[0].desired)
        assertEquals(5, dsList[0].ready)
        assertEquals("<none>", dsList[0].nodeSelector)
        assertEquals("15d", dsList[0].age)
    }

    // MARK: - PersistentVolumeClaims Tests
    @Test
    fun testParsePersistentVolumeClaimsJson() {
        val json = """
            {
              "apiVersion": "v1",
              "items": [
                {
                  "metadata": {
                    "name": "postgres-data-pvc",
                    "namespace": "production",
                    "creationTimestamp": "2026-11-01T10:00:00Z"
                  },
                  "spec": {
                    "volumeName": "pvc-volume-7f8a9b",
                    "storageClassName": "standard-ssd",
                    "accessModes": [
                      "ReadWriteOnce"
                    ],
                    "resources": {
                      "requests": { "storage": "50Gi" }
                    }
                  },
                  "status": {
                    "phase": "Bound",
                    "capacity": { "storage": "50Gi" }
                  }
                }
              ]
            }
        """.trimIndent()

        val pvcs = KubernetesParser.parsePersistentVolumeClaimsJson(json, "default")
        assertEquals(1, pvcs.size)

        val pvc = pvcs[0]
        assertEquals("postgres-data-pvc", pvc.name)
        assertEquals("Bound", pvc.status)
        assertEquals("pvc-volume-7f8a9b", pvc.volume)
        assertEquals("50Gi", pvc.capacity)
        assertEquals("RWO", pvc.accessModes)
        assertEquals("standard-ssd", pvc.storageClass)
    }

    @Test
    fun testParsePersistentVolumeClaimsTable() {
        val table = """
            NAME                STATUS   VOLUME              CAPACITY   ACCESS MODES   STORAGECLASS   AGE
            postgres-data-pvc   Bound    pvc-volume-7f8a9b   50Gi       RWO            standard-ssd   10d
        """.trimIndent()

        val pvcs = KubernetesParser.parsePersistentVolumeClaimsTable(table, "default")
        assertEquals(1, pvcs.size)
        assertEquals("postgres-data-pvc", pvcs[0].name)
        assertEquals("Bound", pvcs[0].status)
        assertEquals("pvc-volume-7f8a9b", pvcs[0].volume)
        assertEquals("50Gi", pvcs[0].capacity)
        assertEquals("RWO", pvcs[0].accessModes)
        assertEquals("standard-ssd", pvcs[0].storageClass)
        assertEquals("10d", pvcs[0].age)
    }
}
