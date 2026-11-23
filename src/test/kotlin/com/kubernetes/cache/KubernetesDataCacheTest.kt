package com.kubernetes.cache

import com.kubernetes.model.ConfigMapInfo
import com.kubernetes.model.CronJobInfo
import com.kubernetes.model.DaemonSetInfo
import com.kubernetes.model.DeploymentInfo
import com.kubernetes.model.DockerImageInfo
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KubernetesDataCacheTest {

    private val profileId = "profile-1"
    private val namespace = "kube-system"

    @Before
    fun setUp() {
        KubernetesDataCache.clearAll()
    }

    @Test
    fun testPodsCacheAndInvalidate() {
        assertNull(KubernetesDataCache.getPods(profileId, namespace))
        assertFalse(KubernetesDataCache.isPodsFresh(profileId, namespace))

        val pods = listOf(
            PodInfo(name = "coredns-1", namespace = namespace, status = "Running", ready = "1/1", restarts = 0, age = "10d", ip = "10.0.0.1", node = "node-1")
        )
        KubernetesDataCache.putPods(profileId, namespace, pods)

        val cached = KubernetesDataCache.getPods(profileId, namespace)
        assertNotNull(cached)
        assertEquals(1, cached?.size)
        assertEquals("coredns-1", cached?.first()?.name)
        assertTrue(KubernetesDataCache.isPodsFresh(profileId, namespace))

        // Invalidate specific resource type
        KubernetesDataCache.invalidateResourceType(profileId, namespace, ResourceType.PODS)
        assertNull(KubernetesDataCache.getPods(profileId, namespace))
    }

    @Test
    fun testServicesCacheAndNamespaceInvalidate() {
        val services = listOf(
            ServiceInfo(name = "kube-dns", namespace = namespace, type = "ClusterIP", clusterIP = "10.96.0.10", ports = "53/UDP")
        )
        KubernetesDataCache.putServices(profileId, namespace, services)
        assertEquals(1, KubernetesDataCache.getServices(profileId, namespace)?.size)

        // Invalidate whole namespace
        KubernetesDataCache.invalidateNamespace(profileId, namespace)
        assertNull(KubernetesDataCache.getServices(profileId, namespace))
    }

    @Test
    fun testConfigMapsAndDeploymentsCache() {
        val configMaps = listOf(
            ConfigMapInfo(name = "coredns", namespace = namespace, dataCount = 1, age = "10d", keys = listOf("Corefile"))
        )
        val deployments = listOf(
            DeploymentInfo(name = "coredns", namespace = namespace, readyReplicas = 2, replicas = 2, age = "10d")
        )

        KubernetesDataCache.putConfigMaps(profileId, namespace, configMaps)
        KubernetesDataCache.putDeployments(profileId, namespace, deployments)

        assertEquals("coredns", KubernetesDataCache.getConfigMaps(profileId, namespace)?.first()?.name)
        assertEquals(2, KubernetesDataCache.getDeployments(profileId, namespace)?.first()?.replicas)

        KubernetesDataCache.invalidateProfile(profileId)
        assertNull(KubernetesDataCache.getConfigMaps(profileId, namespace))
        assertNull(KubernetesDataCache.getDeployments(profileId, namespace))
    }

    @Test
    fun testNamespacesAndDockerImagesCache() {
        val nsList = listOf("default", "kube-system", "production")
        KubernetesDataCache.putNamespaces(profileId, nsList)
        assertEquals(3, KubernetesDataCache.getNamespaces(profileId)?.size)

        val dockerImages = listOf(
            DockerImageInfo(repository = "nginx", tag = "latest", imageId = "abc12345", created = "2 days ago", size = "140MB")
        )
        KubernetesDataCache.putDockerImages(profileId, dockerImages)
        assertEquals(1, KubernetesDataCache.getDockerImages(profileId)?.size)

        KubernetesDataCache.invalidateDockerImages(profileId)
        assertNull(KubernetesDataCache.getDockerImages(profileId))
        assertEquals(3, KubernetesDataCache.getNamespaces(profileId)?.size)
    }

    @Test
    fun testYamlAndDescribeCache() {
        val yaml = "apiVersion: v1\nkind: Pod\nmetadata:\n  name: test-pod\n"
        KubernetesDataCache.putYaml(profileId, namespace, "pod", "test-pod", yaml)
        assertEquals(yaml, KubernetesDataCache.getYaml(profileId, namespace, "pod", "test-pod"))

        val describe = "Name: test-pod\nNamespace: kube-system\nStatus: Running\n"
        KubernetesDataCache.putDescribe(profileId, namespace, "pod", "test-pod", describe)
        assertEquals(describe, KubernetesDataCache.getDescribe(profileId, namespace, "pod", "test-pod"))

        KubernetesDataCache.invalidateResourceType(profileId, namespace, ResourceType.PODS)
        assertNull(KubernetesDataCache.getYaml(profileId, namespace, "pod", "test-pod"))
        assertNull(KubernetesDataCache.getDescribe(profileId, namespace, "pod", "test-pod"))
    }

    @Test
    fun testAllNewResourcesCache() {
        // Secrets
        val secrets = listOf(SecretInfo(name = "sec-1", namespace = namespace, type = "Opaque", dataCount = 2))
        KubernetesDataCache.putSecrets(profileId, namespace, secrets)
        assertEquals(1, KubernetesDataCache.getSecrets(profileId, namespace)?.size)
        assertTrue(KubernetesDataCache.isSecretsFresh(profileId, namespace))

        // Ingresses
        val ingresses = listOf(IngressInfo(name = "ing-1", namespace = namespace, className = "nginx", hosts = "api.example.com"))
        KubernetesDataCache.putIngresses(profileId, namespace, ingresses)
        assertEquals(1, KubernetesDataCache.getIngresses(profileId, namespace)?.size)
        assertTrue(KubernetesDataCache.isIngressesFresh(profileId, namespace))

        // Jobs
        val jobs = listOf(JobInfo(name = "job-1", namespace = namespace, completions = "1/1"))
        KubernetesDataCache.putJobs(profileId, namespace, jobs)
        assertEquals(1, KubernetesDataCache.getJobs(profileId, namespace)?.size)

        // CronJobs
        val cronJobs = listOf(CronJobInfo(name = "cron-1", namespace = namespace, schedule = "0 0 * * *"))
        KubernetesDataCache.putCronJobs(profileId, namespace, cronJobs)
        assertEquals(1, KubernetesDataCache.getCronJobs(profileId, namespace)?.size)

        // StatefulSets
        val statefulSets = listOf(StatefulSetInfo(name = "sts-1", namespace = namespace, replicas = 3, readyReplicas = 3))
        KubernetesDataCache.putStatefulSets(profileId, namespace, statefulSets)
        assertEquals(1, KubernetesDataCache.getStatefulSets(profileId, namespace)?.size)

        // DaemonSets
        val daemonSets = listOf(DaemonSetInfo(name = "ds-1", namespace = namespace, desired = 4, ready = 4))
        KubernetesDataCache.putDaemonSets(profileId, namespace, daemonSets)
        assertEquals(1, KubernetesDataCache.getDaemonSets(profileId, namespace)?.size)

        // PVCs
        val pvcs = listOf(PersistentVolumeClaimInfo(name = "pvc-1", namespace = namespace, status = "Bound", volume = "vol-1"))
        KubernetesDataCache.putPersistentVolumeClaims(profileId, namespace, pvcs)
        assertEquals(1, KubernetesDataCache.getPersistentVolumeClaims(profileId, namespace)?.size)

        // Events
        val events = listOf(EventInfo(name = "ev-1", namespace = namespace, type = "Warning", reason = "BackOff"))
        KubernetesDataCache.putEvents(profileId, namespace, events)
        assertEquals(1, KubernetesDataCache.getEvents(profileId, namespace)?.size)

        // Invalidate specific resource
        KubernetesDataCache.invalidateResourceType(profileId, namespace, ResourceType.SECRETS)
        assertNull(KubernetesDataCache.getSecrets(profileId, namespace))
        assertNotNull(KubernetesDataCache.getIngresses(profileId, namespace))

        // Invalidate whole profile
        KubernetesDataCache.invalidateProfile(profileId)
        assertNull(KubernetesDataCache.getIngresses(profileId, namespace))
        assertNull(KubernetesDataCache.getJobs(profileId, namespace))
        assertNull(KubernetesDataCache.getEvents(profileId, namespace))
    }
}
