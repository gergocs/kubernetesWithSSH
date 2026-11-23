package com.kubernetes.ui

import com.kubernetes.model.ConfigMapInfo
import com.kubernetes.model.CronJobInfo
import com.kubernetes.model.DaemonSetInfo
import com.kubernetes.model.DeploymentInfo
import com.kubernetes.model.EventInfo
import com.kubernetes.model.IngressInfo
import com.kubernetes.model.JobInfo
import com.kubernetes.model.PersistentVolumeClaimInfo
import com.kubernetes.model.PodInfo
import com.kubernetes.model.ResourceType
import com.kubernetes.model.SecretInfo
import com.kubernetes.model.ServiceInfo
import com.kubernetes.model.StatefulSetInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PodTableModelTest {

    @Test
    fun testTableModelOperationsAndFiltering() {
        val model = PodTableModel()

        val pods = listOf(
            PodInfo("auth-service-7f6d89", "default", "Running", "1/1", 0, "5m", "node1", "10.0.0.1", image = "auth-api:1.0.0", imageId = "8251a1f0ba5d"),
            PodInfo("payment-service-123a", "default", "CrashLoopBackOff", "0/1", 4, "12h", "node2", "10.0.0.2", image = "payment-gateway:2.3.1", imageId = "7f9a12b3c4d5"),
            PodInfo("notification-worker", "default", "Completed", "0/1", 0, "1d", "node1", "10.0.0.3", image = "worker-daemon:latest", imageId = "4c0fdaa8b634")
        )

        model.setPods(pods)
        assertEquals(ResourceType.PODS, model.getResourceType())
        assertEquals(3, model.rowCount)
        assertEquals(9, model.columnCount)
        assertEquals(3, model.getTotalCount())
        assertEquals(3, model.getDisplayedCount())

        // Test column values
        assertEquals("auth-service-7f6d89", model.getValueAt(0, 0))
        assertEquals("Running", model.getValueAt(0, 1))
        assertEquals("1/1", model.getValueAt(0, 2))
        assertEquals(0, model.getValueAt(0, 3))
        assertEquals("5m", model.getValueAt(0, 4))
        assertEquals("auth-api:1.0.0", model.getValueAt(0, 5))
        assertEquals("8251a1f0ba5d", model.getValueAt(0, 6))
        assertEquals("node1", model.getValueAt(0, 7))
        assertEquals("10.0.0.1", model.getValueAt(0, 8))

        // Filter by name
        model.setFilter("payment")
        assertEquals(1, model.rowCount)
        assertEquals("payment-service-123a", model.getPodAt(0)?.name)

        // Filter by image
        model.setFilter("payment-gateway")
        assertEquals(1, model.rowCount)
        assertEquals("payment-service-123a", model.getPodAt(0)?.name)

        // Filter by image ID
        model.setFilter("4c0fdaa8")
        assertEquals(1, model.rowCount)
        assertEquals("notification-worker", model.getPodAt(0)?.name)

        // Filter by status
        model.setFilter("running")
        assertEquals(1, model.rowCount)
        assertEquals("auth-service-7f6d89", model.getPodAt(0)?.name)

        // Filter by node
        model.setFilter("node1")
        assertEquals(2, model.rowCount)

        // Reset filter
        model.setFilter("")
        assertEquals(3, model.rowCount)
        assertNotNull(model.getPodAt(2))
        assertNull(model.getPodAt(10))
    }

    @Test
    fun testResourceTableModelWithServices() {
        val model = PodTableModel()

        val services = listOf(
            ServiceInfo(
                name = "kubernetes",
                namespace = "default",
                type = "ClusterIP",
                clusterIP = "10.96.0.1",
                externalIP = "<none>",
                ports = "443/TCP",
                age = "10d",
                selector = "<none>"
            ),
            ServiceInfo(
                name = "frontend-lb",
                namespace = "production",
                type = "LoadBalancer",
                clusterIP = "10.96.20.10",
                externalIP = "203.0.113.5",
                ports = "80:30080/TCP,443:30443/TCP",
                age = "2d",
                selector = "app=frontend"
            )
        )

        model.setServices(services)
        assertEquals(ResourceType.SERVICES, model.getResourceType())
        assertEquals(2, model.rowCount)
        assertEquals(7, model.columnCount)
        assertEquals("Name", model.getColumnName(0))
        assertEquals("Type", model.getColumnName(1))
        assertEquals("Cluster IP", model.getColumnName(2))
        assertEquals("External IP", model.getColumnName(3))
        assertEquals("Ports", model.getColumnName(4))
        assertEquals("Age", model.getColumnName(5))
        assertEquals("Selector", model.getColumnName(6))

        assertEquals("frontend-lb", model.getValueAt(1, 0))
        assertEquals("LoadBalancer", model.getValueAt(1, 1))
        assertEquals("10.96.20.10", model.getValueAt(1, 2))
        assertEquals("203.0.113.5", model.getValueAt(1, 3))
        assertEquals("80:30080/TCP,443:30443/TCP", model.getValueAt(1, 4))
        assertEquals("2d", model.getValueAt(1, 5))
        assertEquals("app=frontend", model.getValueAt(1, 6))

        // Filtering
        model.setFilter("loadbalancer")
        assertEquals(1, model.rowCount)
        assertEquals("frontend-lb", model.getServiceAt(0)?.name)

        model.setFilter("443")
        assertEquals(2, model.rowCount)

        model.setFilter("app=frontend")
        assertEquals(1, model.rowCount)
        assertEquals("frontend-lb", model.getResourceNameAt(0))
        assertEquals("production", model.getResourceNamespaceAt(0))
    }

    @Test
    fun testResourceTableModelWithConfigMaps() {
        val model = PodTableModel()

        val cms = listOf(
            ConfigMapInfo(
                name = "app-config",
                namespace = "production",
                dataCount = 2,
                keys = listOf("application.yml", "db.properties"),
                age = "5d",
                data = mapOf("application.yml" to "port: 8080", "db.properties" to "url=jdbc:...")
            ),
            ConfigMapInfo(
                name = "kube-root-ca",
                namespace = "kube-system",
                dataCount = 1,
                keys = listOf("ca.crt"),
                age = "30d"
            )
        )

        model.setConfigMaps(cms)
        assertEquals(ResourceType.CONFIG_MAPS, model.getResourceType())
        assertEquals(2, model.rowCount)
        assertEquals(4, model.columnCount)
        assertEquals("Name", model.getColumnName(0))
        assertEquals("Data Keys", model.getColumnName(1))
        assertEquals("Count", model.getColumnName(2))
        assertEquals("Age", model.getColumnName(3))

        assertEquals("app-config", model.getValueAt(0, 0))
        assertEquals("application.yml, db.properties", model.getValueAt(0, 1))
        assertEquals(2, model.getValueAt(0, 2))
        assertEquals("5d", model.getValueAt(0, 3))

        // Filter by key name
        model.setFilter("db.properties")
        assertEquals(1, model.rowCount)
        assertEquals("app-config", model.getConfigMapAt(0)?.name)

        // Filter by data content
        model.setFilter("8080")
        assertEquals(1, model.rowCount)
        assertEquals("app-config", model.getConfigMapAt(0)?.name)

        model.setFilter("nonexistent")
        assertEquals(0, model.rowCount)
    }

    @Test
    fun testResourceTableModelWithDeployments() {
        val model = PodTableModel()

        val deployments = listOf(
            DeploymentInfo(
                name = "web-frontend",
                namespace = "production",
                readyReplicas = 3,
                replicas = 3,
                updatedReplicas = 3,
                availableReplicas = 3,
                age = "2d",
                images = listOf("nginx:1.25"),
                containers = listOf("nginx")
            )
        )

        model.setDeployments(deployments)
        assertEquals(ResourceType.DEPLOYMENTS, model.getResourceType())
        assertEquals(1, model.rowCount)
        assertEquals(7, model.columnCount)
        assertEquals("web-frontend", model.getValueAt(0, 0))
        assertEquals("3/3", model.getValueAt(0, 1))
        assertEquals(3, model.getValueAt(0, 2))
        assertEquals(3, model.getValueAt(0, 3))
        assertEquals("2d", model.getValueAt(0, 4))
        assertEquals("nginx:1.25", model.getValueAt(0, 5))
        assertEquals("nginx", model.getValueAt(0, 6))

        assertEquals("web-frontend", model.getDeploymentAt(0)?.name)
        assertEquals("web-frontend", model.getResourceNameAt(0))
        assertEquals("production", model.getResourceNamespaceAt(0))
    }

    @Test
    fun testResourceTypeSwitching() {
        val model = PodTableModel()
        model.setPods(listOf(PodInfo("pod-1", "default", "Running", "1/1", 0, "1m")))
        assertEquals(ResourceType.PODS, model.getResourceType())
        assertEquals("pod-1", model.getResourceNameAt(0))

        model.setResourceType(ResourceType.SERVICES)
        assertEquals(ResourceType.SERVICES, model.getResourceType())
        assertEquals(0, model.rowCount)

        model.setServices(listOf(ServiceInfo("svc-1", "default", "ClusterIP", "10.0.0.1", "<none>", "80/TCP", "1m")))
        assertEquals("svc-1", model.getResourceNameAt(0))
    }

    @Test
    fun testResourceTableModelWithSecrets() {
        val model = PodTableModel()
        val secrets = listOf(
            SecretInfo(name = "db-secret", namespace = "production", type = "Opaque", dataCount = 2, keys = listOf("user", "pass"), age = "1d")
        )
        model.setSecrets(secrets)
        assertEquals(ResourceType.SECRETS, model.getResourceType())
        assertEquals(1, model.rowCount)
        assertEquals(5, model.columnCount)
        assertEquals("db-secret", model.getValueAt(0, 0))
        assertEquals("Opaque", model.getValueAt(0, 1))
        assertEquals("user, pass", model.getValueAt(0, 2))
        assertEquals(2, model.getValueAt(0, 3))
        assertEquals("1d", model.getValueAt(0, 4))
        assertEquals("db-secret", model.getSecretAt(0)?.name)
    }

    @Test
    fun testResourceTableModelWithIngresses() {
        val model = PodTableModel()
        val ingresses = listOf(
            IngressInfo(name = "app-ingress", namespace = "production", className = "nginx", hosts = "api.example.com", address = "10.0.0.50", ports = "80, 443", age = "2d")
        )
        model.setIngresses(ingresses)
        assertEquals(ResourceType.INGRESSES, model.getResourceType())
        assertEquals(1, model.rowCount)
        assertEquals(6, model.columnCount)
        assertEquals("app-ingress", model.getValueAt(0, 0))
        assertEquals("nginx", model.getValueAt(0, 1))
        assertEquals("api.example.com", model.getValueAt(0, 2))
        assertEquals("10.0.0.50", model.getValueAt(0, 3))
        assertEquals("80, 443", model.getValueAt(0, 4))
        assertEquals("2d", model.getValueAt(0, 5))
        assertEquals("app-ingress", model.getIngressAt(0)?.name)
    }

    @Test
    fun testResourceTableModelWithJobsAndCronJobs() {
        val model = PodTableModel()
        val jobs = listOf(
            JobInfo(name = "batch-job", namespace = "production", completions = "1/1", duration = "45s", age = "1h")
        )
        model.setJobs(jobs)
        assertEquals(ResourceType.JOBS, model.getResourceType())
        assertEquals(1, model.rowCount)
        assertEquals("batch-job", model.getValueAt(0, 0))
        assertEquals("1/1", model.getValueAt(0, 1))
        assertEquals("45s", model.getValueAt(0, 2))

        val cronJobs = listOf(
            CronJobInfo(name = "cron-job", namespace = "production", schedule = "0 * * * *", suspend = false, active = 1, lastSchedule = "10m", age = "5d")
        )
        model.setCronJobs(cronJobs)
        assertEquals(ResourceType.CRON_JOBS, model.getResourceType())
        assertEquals(1, model.rowCount)
        assertEquals("cron-job", model.getValueAt(0, 0))
        assertEquals("0 * * * *", model.getValueAt(0, 1))
        assertEquals("False", model.getValueAt(0, 2))
        assertEquals(1, model.getValueAt(0, 3))
    }

    @Test
    fun testResourceTableModelWithEventsStatefulSetsDaemonSetsPVCs() {
        val model = PodTableModel()

        // StatefulSets
        val sts = listOf(StatefulSetInfo(name = "redis-cluster", namespace = "production", readyReplicas = 3, replicas = 3, age = "10d"))
        model.setStatefulSets(sts)
        assertEquals(ResourceType.STATEFUL_SETS, model.getResourceType())
        assertEquals("redis-cluster", model.getValueAt(0, 0))
        assertEquals("3/3", model.getValueAt(0, 1))

        // DaemonSets
        val ds = listOf(DaemonSetInfo(name = "node-agent", namespace = "kube-system", desired = 5, current = 5, ready = 5, upToDate = 5, available = 5, nodeSelector = "os=linux", age = "20d"))
        model.setDaemonSets(ds)
        assertEquals(ResourceType.DAEMON_SETS, model.getResourceType())
        assertEquals("node-agent", model.getValueAt(0, 0))
        assertEquals(5, model.getValueAt(0, 1))

        // PVCs
        val pvcs = listOf(PersistentVolumeClaimInfo(name = "data-pvc", namespace = "production", status = "Bound", volume = "pv-123", capacity = "100Gi", accessModes = "RWO", storageClass = "fast-ssd", age = "5d"))
        model.setPersistentVolumeClaims(pvcs)
        assertEquals(ResourceType.PERSISTENT_VOLUME_CLAIMS, model.getResourceType())
        assertEquals("data-pvc", model.getValueAt(0, 0))
        assertEquals("Bound", model.getValueAt(0, 1))
        assertEquals("pv-123", model.getValueAt(0, 2))
        assertEquals("100Gi", model.getValueAt(0, 3))
        assertEquals("RWO", model.getValueAt(0, 4))
        assertEquals("fast-ssd", model.getValueAt(0, 5))

        // Events
        val events = listOf(EventInfo(name = "ev-1", namespace = "production", type = "Warning", reason = "BackOff", involvedObject = "Pod/my-pod", message = "Back-off restarting failed container", count = 3, age = "2m", source = "kubelet"))
        model.setEvents(events)
        assertEquals(ResourceType.EVENTS, model.getResourceType())
        assertEquals("Warning", model.getValueAt(0, 0))
        assertEquals("BackOff", model.getValueAt(0, 1))
        assertEquals("Pod/my-pod", model.getValueAt(0, 2))
        assertEquals("Back-off restarting failed container", model.getValueAt(0, 3))
        assertEquals(3, model.getValueAt(0, 4))
        assertEquals("2m", model.getValueAt(0, 5))
        assertEquals("kubelet", model.getValueAt(0, 6))
    }
}
