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
import java.util.concurrent.ConcurrentHashMap

/**
 * Intelligent in-memory cache for Kubernetes cluster resources and Docker images.
 * Provides instant sub-millisecond retrieval for immediate UI responsiveness with Stale-While-Revalidate support.
 */
object KubernetesDataCache {

    private data class CacheEntry<T>(
        val data: T,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isFresh(ttlMillis: Long = 30_000L): Boolean {
            return (System.currentTimeMillis() - timestamp) <= ttlMillis
        }
    }

    private data class ResourceKey(
        val profileId: String,
        val namespace: String,
        val resourceType: ResourceType
    )

    private data class DetailKey(
        val profileId: String,
        val namespace: String,
        val resourceType: String,
        val resourceName: String
    )

    // Resource collection caches
    private val podsCache = ConcurrentHashMap<ResourceKey, CacheEntry<List<PodInfo>>>()
    private val servicesCache = ConcurrentHashMap<ResourceKey, CacheEntry<List<ServiceInfo>>>()
    private val ingressesCache = ConcurrentHashMap<ResourceKey, CacheEntry<List<IngressInfo>>>()
    private val configMapsCache = ConcurrentHashMap<ResourceKey, CacheEntry<List<ConfigMapInfo>>>()
    private val secretsCache = ConcurrentHashMap<ResourceKey, CacheEntry<List<SecretInfo>>>()
    private val deploymentsCache = ConcurrentHashMap<ResourceKey, CacheEntry<List<DeploymentInfo>>>()
    private val statefulSetsCache = ConcurrentHashMap<ResourceKey, CacheEntry<List<StatefulSetInfo>>>()
    private val daemonSetsCache = ConcurrentHashMap<ResourceKey, CacheEntry<List<DaemonSetInfo>>>()
    private val jobsCache = ConcurrentHashMap<ResourceKey, CacheEntry<List<JobInfo>>>()
    private val cronJobsCache = ConcurrentHashMap<ResourceKey, CacheEntry<List<CronJobInfo>>>()
    private val pvcCache = ConcurrentHashMap<ResourceKey, CacheEntry<List<PersistentVolumeClaimInfo>>>()
    private val eventsCache = ConcurrentHashMap<ResourceKey, CacheEntry<List<EventInfo>>>()

    // Global / per-profile caches
    private val namespacesCache = ConcurrentHashMap<String, CacheEntry<List<String>>>()
    private val dockerImagesCache = ConcurrentHashMap<String, CacheEntry<List<DockerImageInfo>>>()

    // Details caches (YAML, Describe)
    private val yamlCache = ConcurrentHashMap<DetailKey, CacheEntry<String>>()
    private val describeCache = ConcurrentHashMap<DetailKey, CacheEntry<String>>()

    // MARK: - Pods
    fun getPods(profileId: String, namespace: String): List<PodInfo>? {
        return podsCache[ResourceKey(profileId, namespace, ResourceType.PODS)]?.data
    }

    fun isPodsFresh(profileId: String, namespace: String, ttlMillis: Long = 30_000L): Boolean {
        return podsCache[ResourceKey(profileId, namespace, ResourceType.PODS)]?.isFresh(ttlMillis) == true
    }

    fun putPods(profileId: String, namespace: String, pods: List<PodInfo>) {
        podsCache[ResourceKey(profileId, namespace, ResourceType.PODS)] = CacheEntry(pods)
    }

    // MARK: - Services
    fun getServices(profileId: String, namespace: String): List<ServiceInfo>? {
        return servicesCache[ResourceKey(profileId, namespace, ResourceType.SERVICES)]?.data
    }

    fun isServicesFresh(profileId: String, namespace: String, ttlMillis: Long = 30_000L): Boolean {
        return servicesCache[ResourceKey(profileId, namespace, ResourceType.SERVICES)]?.isFresh(ttlMillis) == true
    }

    fun putServices(profileId: String, namespace: String, services: List<ServiceInfo>) {
        servicesCache[ResourceKey(profileId, namespace, ResourceType.SERVICES)] = CacheEntry(services)
    }

    // MARK: - Ingresses
    fun getIngresses(profileId: String, namespace: String): List<IngressInfo>? {
        return ingressesCache[ResourceKey(profileId, namespace, ResourceType.INGRESSES)]?.data
    }

    fun isIngressesFresh(profileId: String, namespace: String, ttlMillis: Long = 30_000L): Boolean {
        return ingressesCache[ResourceKey(profileId, namespace, ResourceType.INGRESSES)]?.isFresh(ttlMillis) == true
    }

    fun putIngresses(profileId: String, namespace: String, ingresses: List<IngressInfo>) {
        ingressesCache[ResourceKey(profileId, namespace, ResourceType.INGRESSES)] = CacheEntry(ingresses)
    }

    // MARK: - ConfigMaps
    fun getConfigMaps(profileId: String, namespace: String): List<ConfigMapInfo>? {
        return configMapsCache[ResourceKey(profileId, namespace, ResourceType.CONFIG_MAPS)]?.data
    }

    fun isConfigMapsFresh(profileId: String, namespace: String, ttlMillis: Long = 30_000L): Boolean {
        return configMapsCache[ResourceKey(profileId, namespace, ResourceType.CONFIG_MAPS)]?.isFresh(ttlMillis) == true
    }

    fun putConfigMaps(profileId: String, namespace: String, configMaps: List<ConfigMapInfo>) {
        configMapsCache[ResourceKey(profileId, namespace, ResourceType.CONFIG_MAPS)] = CacheEntry(configMaps)
    }

    // MARK: - Secrets
    fun getSecrets(profileId: String, namespace: String): List<SecretInfo>? {
        return secretsCache[ResourceKey(profileId, namespace, ResourceType.SECRETS)]?.data
    }

    fun isSecretsFresh(profileId: String, namespace: String, ttlMillis: Long = 30_000L): Boolean {
        return secretsCache[ResourceKey(profileId, namespace, ResourceType.SECRETS)]?.isFresh(ttlMillis) == true
    }

    fun putSecrets(profileId: String, namespace: String, secrets: List<SecretInfo>) {
        secretsCache[ResourceKey(profileId, namespace, ResourceType.SECRETS)] = CacheEntry(secrets)
    }

    // MARK: - Deployments
    fun getDeployments(profileId: String, namespace: String): List<DeploymentInfo>? {
        return deploymentsCache[ResourceKey(profileId, namespace, ResourceType.DEPLOYMENTS)]?.data
    }

    fun isDeploymentsFresh(profileId: String, namespace: String, ttlMillis: Long = 30_000L): Boolean {
        return deploymentsCache[ResourceKey(profileId, namespace, ResourceType.DEPLOYMENTS)]?.isFresh(ttlMillis) == true
    }

    fun putDeployments(profileId: String, namespace: String, deployments: List<DeploymentInfo>) {
        deploymentsCache[ResourceKey(profileId, namespace, ResourceType.DEPLOYMENTS)] = CacheEntry(deployments)
    }

    // MARK: - StatefulSets
    fun getStatefulSets(profileId: String, namespace: String): List<StatefulSetInfo>? {
        return statefulSetsCache[ResourceKey(profileId, namespace, ResourceType.STATEFUL_SETS)]?.data
    }

    fun isStatefulSetsFresh(profileId: String, namespace: String, ttlMillis: Long = 30_000L): Boolean {
        return statefulSetsCache[ResourceKey(profileId, namespace, ResourceType.STATEFUL_SETS)]?.isFresh(ttlMillis) == true
    }

    fun putStatefulSets(profileId: String, namespace: String, statefulSets: List<StatefulSetInfo>) {
        statefulSetsCache[ResourceKey(profileId, namespace, ResourceType.STATEFUL_SETS)] = CacheEntry(statefulSets)
    }

    // MARK: - DaemonSets
    fun getDaemonSets(profileId: String, namespace: String): List<DaemonSetInfo>? {
        return daemonSetsCache[ResourceKey(profileId, namespace, ResourceType.DAEMON_SETS)]?.data
    }

    fun isDaemonSetsFresh(profileId: String, namespace: String, ttlMillis: Long = 30_000L): Boolean {
        return daemonSetsCache[ResourceKey(profileId, namespace, ResourceType.DAEMON_SETS)]?.isFresh(ttlMillis) == true
    }

    fun putDaemonSets(profileId: String, namespace: String, daemonSets: List<DaemonSetInfo>) {
        daemonSetsCache[ResourceKey(profileId, namespace, ResourceType.DAEMON_SETS)] = CacheEntry(daemonSets)
    }

    // MARK: - Jobs
    fun getJobs(profileId: String, namespace: String): List<JobInfo>? {
        return jobsCache[ResourceKey(profileId, namespace, ResourceType.JOBS)]?.data
    }

    fun isJobsFresh(profileId: String, namespace: String, ttlMillis: Long = 30_000L): Boolean {
        return jobsCache[ResourceKey(profileId, namespace, ResourceType.JOBS)]?.isFresh(ttlMillis) == true
    }

    fun putJobs(profileId: String, namespace: String, jobs: List<JobInfo>) {
        jobsCache[ResourceKey(profileId, namespace, ResourceType.JOBS)] = CacheEntry(jobs)
    }

    // MARK: - CronJobs
    fun getCronJobs(profileId: String, namespace: String): List<CronJobInfo>? {
        return cronJobsCache[ResourceKey(profileId, namespace, ResourceType.CRON_JOBS)]?.data
    }

    fun isCronJobsFresh(profileId: String, namespace: String, ttlMillis: Long = 30_000L): Boolean {
        return cronJobsCache[ResourceKey(profileId, namespace, ResourceType.CRON_JOBS)]?.isFresh(ttlMillis) == true
    }

    fun putCronJobs(profileId: String, namespace: String, cronJobs: List<CronJobInfo>) {
        cronJobsCache[ResourceKey(profileId, namespace, ResourceType.CRON_JOBS)] = CacheEntry(cronJobs)
    }

    // MARK: - PVCs
    fun getPersistentVolumeClaims(profileId: String, namespace: String): List<PersistentVolumeClaimInfo>? {
        return pvcCache[ResourceKey(profileId, namespace, ResourceType.PERSISTENT_VOLUME_CLAIMS)]?.data
    }

    fun isPersistentVolumeClaimsFresh(profileId: String, namespace: String, ttlMillis: Long = 30_000L): Boolean {
        return pvcCache[ResourceKey(profileId, namespace, ResourceType.PERSISTENT_VOLUME_CLAIMS)]?.isFresh(ttlMillis) == true
    }

    fun putPersistentVolumeClaims(profileId: String, namespace: String, pvcs: List<PersistentVolumeClaimInfo>) {
        pvcCache[ResourceKey(profileId, namespace, ResourceType.PERSISTENT_VOLUME_CLAIMS)] = CacheEntry(pvcs)
    }

    // MARK: - Events
    fun getEvents(profileId: String, namespace: String): List<EventInfo>? {
        return eventsCache[ResourceKey(profileId, namespace, ResourceType.EVENTS)]?.data
    }

    fun isEventsFresh(profileId: String, namespace: String, ttlMillis: Long = 10_000L): Boolean {
        return eventsCache[ResourceKey(profileId, namespace, ResourceType.EVENTS)]?.isFresh(ttlMillis) == true
    }

    fun putEvents(profileId: String, namespace: String, events: List<EventInfo>) {
        eventsCache[ResourceKey(profileId, namespace, ResourceType.EVENTS)] = CacheEntry(events)
    }

    // MARK: - Namespaces
    fun getNamespaces(profileId: String): List<String>? {
        return namespacesCache[profileId]?.data
    }

    fun isNamespacesFresh(profileId: String, ttlMillis: Long = 60_000L): Boolean {
        return namespacesCache[profileId]?.isFresh(ttlMillis) == true
    }

    fun putNamespaces(profileId: String, namespaces: List<String>) {
        namespacesCache[profileId] = CacheEntry(namespaces)
    }

    // MARK: - Docker Images
    fun getDockerImages(profileId: String): List<DockerImageInfo>? {
        return dockerImagesCache[profileId]?.data
    }

    fun isDockerImagesFresh(profileId: String, ttlMillis: Long = 30_000L): Boolean {
        return dockerImagesCache[profileId]?.isFresh(ttlMillis) == true
    }

    fun putDockerImages(profileId: String, images: List<DockerImageInfo>) {
        dockerImagesCache[profileId] = CacheEntry(images)
    }

    // MARK: - YAML
    fun getYaml(profileId: String, namespace: String, resourceType: String, resourceName: String): String? {
        return yamlCache[DetailKey(profileId, namespace, resourceType.lowercase(), resourceName)]?.data
    }

    fun putYaml(profileId: String, namespace: String, resourceType: String, resourceName: String, yaml: String) {
        yamlCache[DetailKey(profileId, namespace, resourceType.lowercase(), resourceName)] = CacheEntry(yaml)
    }

    // MARK: - Describe
    fun getDescribe(profileId: String, namespace: String, resourceType: String, resourceName: String): String? {
        return describeCache[DetailKey(profileId, namespace, resourceType.lowercase(), resourceName)]?.data
    }

    fun putDescribe(profileId: String, namespace: String, resourceType: String, resourceName: String, describe: String) {
        describeCache[DetailKey(profileId, namespace, resourceType.lowercase(), resourceName)] = CacheEntry(describe)
    }

    // MARK: - Invalidation
    fun invalidateNamespace(profileId: String, namespace: String) {
        ResourceType.values().forEach { type ->
            val key = ResourceKey(profileId, namespace, type)
            podsCache.remove(key)
            servicesCache.remove(key)
            ingressesCache.remove(key)
            configMapsCache.remove(key)
            secretsCache.remove(key)
            deploymentsCache.remove(key)
            statefulSetsCache.remove(key)
            daemonSetsCache.remove(key)
            jobsCache.remove(key)
            cronJobsCache.remove(key)
            pvcCache.remove(key)
            eventsCache.remove(key)
        }
        yamlCache.keys.removeIf { it.profileId == profileId && it.namespace == namespace }
        describeCache.keys.removeIf { it.profileId == profileId && it.namespace == namespace }
    }

    fun invalidateResourceType(profileId: String, namespace: String, resourceType: ResourceType) {
        val key = ResourceKey(profileId, namespace, resourceType)
        when (resourceType) {
            ResourceType.PODS -> podsCache.remove(key)
            ResourceType.SERVICES -> servicesCache.remove(key)
            ResourceType.INGRESSES -> ingressesCache.remove(key)
            ResourceType.CONFIG_MAPS -> configMapsCache.remove(key)
            ResourceType.SECRETS -> secretsCache.remove(key)
            ResourceType.DEPLOYMENTS -> deploymentsCache.remove(key)
            ResourceType.STATEFUL_SETS -> statefulSetsCache.remove(key)
            ResourceType.DAEMON_SETS -> daemonSetsCache.remove(key)
            ResourceType.JOBS -> jobsCache.remove(key)
            ResourceType.CRON_JOBS -> cronJobsCache.remove(key)
            ResourceType.PERSISTENT_VOLUME_CLAIMS -> pvcCache.remove(key)
            ResourceType.EVENTS -> eventsCache.remove(key)
        }
        yamlCache.keys.removeIf { it.profileId == profileId && it.namespace == namespace && it.resourceType == resourceType.singularName.lowercase() }
        describeCache.keys.removeIf { it.profileId == profileId && it.namespace == namespace && it.resourceType == resourceType.singularName.lowercase() }
    }

    fun invalidateDockerImages(profileId: String) {
        dockerImagesCache.remove(profileId)
    }

    fun invalidateProfile(profileId: String) {
        podsCache.keys.removeIf { it.profileId == profileId }
        servicesCache.keys.removeIf { it.profileId == profileId }
        ingressesCache.keys.removeIf { it.profileId == profileId }
        configMapsCache.keys.removeIf { it.profileId == profileId }
        secretsCache.keys.removeIf { it.profileId == profileId }
        deploymentsCache.keys.removeIf { it.profileId == profileId }
        statefulSetsCache.keys.removeIf { it.profileId == profileId }
        daemonSetsCache.keys.removeIf { it.profileId == profileId }
        jobsCache.keys.removeIf { it.profileId == profileId }
        cronJobsCache.keys.removeIf { it.profileId == profileId }
        pvcCache.keys.removeIf { it.profileId == profileId }
        eventsCache.keys.removeIf { it.profileId == profileId }
        namespacesCache.remove(profileId)
        dockerImagesCache.remove(profileId)
        yamlCache.keys.removeIf { it.profileId == profileId }
        describeCache.keys.removeIf { it.profileId == profileId }
    }

    fun clearAll() {
        podsCache.clear()
        servicesCache.clear()
        ingressesCache.clear()
        configMapsCache.clear()
        secretsCache.clear()
        deploymentsCache.clear()
        statefulSetsCache.clear()
        daemonSetsCache.clear()
        jobsCache.clear()
        cronJobsCache.clear()
        pvcCache.clear()
        eventsCache.clear()
        namespacesCache.clear()
        dockerImagesCache.clear()
        yamlCache.clear()
        describeCache.clear()
    }
}
