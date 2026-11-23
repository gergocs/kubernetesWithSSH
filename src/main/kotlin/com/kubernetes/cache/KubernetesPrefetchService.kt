package com.kubernetes.cache

import com.intellij.openapi.application.ApplicationManager
import com.kubernetes.model.ResourceType
import com.kubernetes.model.SshProfile
import com.kubernetes.ssh.KubernetesSshClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Intelligent background prefetching service that warms resource caches ahead of time,
 * eliminating user wait times when switching between resource types, tabs, or dialogs.
 */
object KubernetesPrefetchService {

    private val inFlightPrefetches = ConcurrentHashMap.newKeySet<String>()

    /**
     * Prefetches non-active resource types for a given namespace in the background so that
     * when the user switches tabs or combo box items (e.g., from Pods to Services or Deployments),
     * data appears instantly with 0ms latency.
     */
    fun prefetchAllResourcesForNamespace(profile: SshProfile, namespace: String, excludeType: ResourceType? = null) {
        if (profile.host.isBlank() || namespace.isBlank()) return

        val targetTypes = ResourceType.values().filter { it != excludeType }
        val prefetchKey = "ns-resources:${profile.id}:$namespace"

        if (!inFlightPrefetches.add(prefetchKey)) return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = KubernetesSshClient(profile)
                for (type in targetTypes) {
                    when (type) {
                        ResourceType.PODS -> {
                            if (!KubernetesDataCache.isPodsFresh(profile.id, namespace)) {
                                try {
                                    val pods = client.getPods(namespace)
                                    KubernetesDataCache.putPods(profile.id, namespace, pods)
                                } catch (_: Exception) {}
                            }
                        }
                        ResourceType.SERVICES -> {
                            if (!KubernetesDataCache.isServicesFresh(profile.id, namespace)) {
                                try {
                                    val services = client.getServices(namespace)
                                    KubernetesDataCache.putServices(profile.id, namespace, services)
                                } catch (_: Exception) {}
                            }
                        }
                        ResourceType.INGRESSES -> {
                            if (!KubernetesDataCache.isIngressesFresh(profile.id, namespace)) {
                                try {
                                    val ingresses = client.getIngresses(namespace)
                                    KubernetesDataCache.putIngresses(profile.id, namespace, ingresses)
                                } catch (_: Exception) {}
                            }
                        }
                        ResourceType.CONFIG_MAPS -> {
                            if (!KubernetesDataCache.isConfigMapsFresh(profile.id, namespace)) {
                                try {
                                    val configMaps = client.getConfigMaps(namespace)
                                    KubernetesDataCache.putConfigMaps(profile.id, namespace, configMaps)
                                } catch (_: Exception) {}
                            }
                        }
                        ResourceType.SECRETS -> {
                            if (!KubernetesDataCache.isSecretsFresh(profile.id, namespace)) {
                                try {
                                    val secrets = client.getSecrets(namespace)
                                    KubernetesDataCache.putSecrets(profile.id, namespace, secrets)
                                } catch (_: Exception) {}
                            }
                        }
                        ResourceType.DEPLOYMENTS -> {
                            if (!KubernetesDataCache.isDeploymentsFresh(profile.id, namespace)) {
                                try {
                                    val deployments = client.getDeployments(namespace)
                                    KubernetesDataCache.putDeployments(profile.id, namespace, deployments)
                                } catch (_: Exception) {}
                            }
                        }
                        ResourceType.STATEFUL_SETS -> {
                            if (!KubernetesDataCache.isStatefulSetsFresh(profile.id, namespace)) {
                                try {
                                    val statefulSets = client.getStatefulSets(namespace)
                                    KubernetesDataCache.putStatefulSets(profile.id, namespace, statefulSets)
                                } catch (_: Exception) {}
                            }
                        }
                        ResourceType.DAEMON_SETS -> {
                            if (!KubernetesDataCache.isDaemonSetsFresh(profile.id, namespace)) {
                                try {
                                    val daemonSets = client.getDaemonSets(namespace)
                                    KubernetesDataCache.putDaemonSets(profile.id, namespace, daemonSets)
                                } catch (_: Exception) {}
                            }
                        }
                        ResourceType.JOBS -> {
                            if (!KubernetesDataCache.isJobsFresh(profile.id, namespace)) {
                                try {
                                    val jobs = client.getJobs(namespace)
                                    KubernetesDataCache.putJobs(profile.id, namespace, jobs)
                                } catch (_: Exception) {}
                            }
                        }
                        ResourceType.CRON_JOBS -> {
                            if (!KubernetesDataCache.isCronJobsFresh(profile.id, namespace)) {
                                try {
                                    val cronJobs = client.getCronJobs(namespace)
                                    KubernetesDataCache.putCronJobs(profile.id, namespace, cronJobs)
                                } catch (_: Exception) {}
                            }
                        }
                        ResourceType.PERSISTENT_VOLUME_CLAIMS -> {
                            if (!KubernetesDataCache.isPersistentVolumeClaimsFresh(profile.id, namespace)) {
                                try {
                                    val pvcs = client.getPersistentVolumeClaims(namespace)
                                    KubernetesDataCache.putPersistentVolumeClaims(profile.id, namespace, pvcs)
                                } catch (_: Exception) {}
                            }
                        }
                        ResourceType.EVENTS -> {
                            if (!KubernetesDataCache.isEventsFresh(profile.id, namespace)) {
                                try {
                                    val events = client.getEvents(namespace)
                                    KubernetesDataCache.putEvents(profile.id, namespace, events)
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
            } finally {
                inFlightPrefetches.remove(prefetchKey)
            }
        }
    }

    /**
     * Prefetches cluster namespaces and warms cache.
     */
    fun prefetchNamespaces(profile: SshProfile, onComplete: ((List<String>) -> Unit)? = null) {
        if (profile.host.isBlank()) return
        val prefetchKey = "namespaces:${profile.id}"
        if (!inFlightPrefetches.add(prefetchKey)) return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = KubernetesSshClient(profile)
                val namespaces = client.getNamespaces()
                KubernetesDataCache.putNamespaces(profile.id, namespaces)
                onComplete?.invoke(namespaces)
            } catch (_: Exception) {
            } finally {
                inFlightPrefetches.remove(prefetchKey)
            }
        }
    }

    /**
     * Prefetches Docker images in the background.
     */
    fun prefetchDockerImages(profile: SshProfile, onComplete: (() -> Unit)? = null) {
        if (profile.host.isBlank()) return
        val prefetchKey = "docker-images:${profile.id}"
        if (!inFlightPrefetches.add(prefetchKey)) return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = KubernetesSshClient(profile)
                val images = client.getDockerImages()
                KubernetesDataCache.putDockerImages(profile.id, images)
                onComplete?.invoke()
            } catch (_: Exception) {
            } finally {
                inFlightPrefetches.remove(prefetchKey)
            }
        }
    }

    /**
     * Proactively warms YAML cache for a specific resource so YAML dialog opens instantaneously.
     */
    fun prefetchResourceYaml(profile: SshProfile, resourceType: String, namespace: String, resourceName: String) {
        if (profile.host.isBlank() || resourceName.isBlank()) return
        val prefetchKey = "yaml:${profile.id}:$namespace:$resourceType:$resourceName"
        if (!inFlightPrefetches.add(prefetchKey)) return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = KubernetesSshClient(profile)
                val res = client.getResourceYaml(resourceType, namespace, resourceName)
                if (res.isSuccess && res.output.isNotBlank()) {
                    KubernetesDataCache.putYaml(profile.id, namespace, resourceType, resourceName, res.output)
                }
            } catch (_: Exception) {
            } finally {
                inFlightPrefetches.remove(prefetchKey)
            }
        }
    }

    /**
     * Proactively warms describe cache for a specific resource.
     */
    fun prefetchResourceDescribe(profile: SshProfile, resourceType: String, namespace: String, resourceName: String) {
        if (profile.host.isBlank() || resourceName.isBlank()) return
        val prefetchKey = "describe:${profile.id}:$namespace:$resourceType:$resourceName"
        if (!inFlightPrefetches.add(prefetchKey)) return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = KubernetesSshClient(profile)
                val res = client.describeResource(resourceType, namespace, resourceName)
                if (res.isSuccess && res.output.isNotBlank()) {
                    KubernetesDataCache.putDescribe(profile.id, namespace, resourceType, resourceName, res.output)
                }
            } catch (_: Exception) {
            } finally {
                inFlightPrefetches.remove(prefetchKey)
            }
        }
    }
}
