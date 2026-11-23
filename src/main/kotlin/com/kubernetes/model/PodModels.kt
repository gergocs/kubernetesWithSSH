package com.kubernetes.model

enum class AuthType {
    PASSWORD,
    PRIVATE_KEY
}

enum class ResourceType(
    val displayName: String,
    val singularName: String,
    val pluralName: String,
    val shortName: String
) {
    PODS("Pods", "pod", "pods", "po"),
    SERVICES("Services", "service", "services", "svc"),
    INGRESSES("Ingresses", "ingress", "ingresses", "ing"),
    CONFIG_MAPS("ConfigMaps", "configmap", "configmaps", "cm"),
    SECRETS("Secrets", "secret", "secrets", "secret"),
    DEPLOYMENTS("Deployments", "deployment", "deployments", "deploy"),
    STATEFUL_SETS("StatefulSets", "statefulset", "statefulsets", "sts"),
    DAEMON_SETS("DaemonSets", "daemonset", "daemonsets", "ds"),
    JOBS("Jobs", "job", "jobs", "job"),
    CRON_JOBS("CronJobs", "cronjob", "cronjobs", "cj"),
    PERSISTENT_VOLUME_CLAIMS("PersistentVolumeClaims", "persistentvolumeclaim", "persistentvolumeclaims", "pvc"),
    EVENTS("Events", "event", "events", "ev");

    override fun toString(): String = displayName
}

data class PodInfo(
    val name: String,
    val namespace: String,
    val status: String,
    val ready: String,
    val restarts: Int,
    val age: String,
    val node: String = "",
    val ip: String = "",
    val rawPhase: String = "",
    val image: String = "",
    val images: List<String> = emptyList(),
    val imageId: String = "",
    val imageIds: List<String> = emptyList(),
    val containers: List<String> = emptyList(),
    val deploymentName: String = "",
    val ownerKind: String = "",
    val ownerName: String = ""
)

data class DeploymentInfo(
    val name: String,
    val namespace: String,
    val readyReplicas: Int = 0,
    val replicas: Int = 0,
    val updatedReplicas: Int = 0,
    val availableReplicas: Int = 0,
    val age: String = "",
    val images: List<String> = emptyList(),
    val containers: List<String> = emptyList()
)

data class ServiceInfo(
    val name: String,
    val namespace: String,
    val type: String = "ClusterIP",
    val clusterIP: String = "",
    val externalIP: String = "<none>",
    val ports: String = "",
    val age: String = "",
    val selector: String = "",
    val selectorMap: Map<String, String> = emptyMap()
)

data class ConfigMapInfo(
    val name: String,
    val namespace: String,
    val dataCount: Int = 0,
    val keys: List<String> = emptyList(),
    val age: String = "",
    val data: Map<String, String> = emptyMap(),
    val binaryDataKeys: List<String> = emptyList()
)

data class SecretInfo(
    val name: String,
    val namespace: String,
    val type: String = "Opaque",
    val dataCount: Int = 0,
    val keys: List<String> = emptyList(),
    val age: String = "",
    val data: Map<String, String> = emptyMap(),
    val stringData: Map<String, String> = emptyMap()
)

data class IngressInfo(
    val name: String,
    val namespace: String,
    val className: String = "",
    val hosts: String = "",
    val hostsList: List<String> = emptyList(),
    val address: String = "",
    val ports: String = "",
    val age: String = "",
    val rules: List<IngressRuleInfo> = emptyList()
)

data class IngressRuleInfo(
    val host: String,
    val path: String,
    val pathType: String = "Prefix",
    val backendService: String,
    val backendPort: String
)

data class JobInfo(
    val name: String,
    val namespace: String,
    val completions: String = "0/1",
    val duration: String = "",
    val age: String = "",
    val containers: List<String> = emptyList(),
    val images: List<String> = emptyList(),
    val condition: String = "",
    val active: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0
)

data class CronJobInfo(
    val name: String,
    val namespace: String,
    val schedule: String = "",
    val suspend: Boolean = false,
    val active: Int = 0,
    val lastSchedule: String = "",
    val age: String = "",
    val containers: List<String> = emptyList(),
    val images: List<String> = emptyList()
)

data class EventInfo(
    val name: String,
    val namespace: String,
    val type: String = "Normal",
    val reason: String = "",
    val involvedObject: String = "",
    val message: String = "",
    val source: String = "",
    val count: Int = 1,
    val age: String = "",
    val lastTimestamp: String = ""
)

data class StatefulSetInfo(
    val name: String,
    val namespace: String,
    val readyReplicas: Int = 0,
    val replicas: Int = 0,
    val age: String = "",
    val containers: List<String> = emptyList(),
    val images: List<String> = emptyList()
)

data class DaemonSetInfo(
    val name: String,
    val namespace: String,
    val desired: Int = 0,
    val current: Int = 0,
    val ready: Int = 0,
    val upToDate: Int = 0,
    val available: Int = 0,
    val nodeSelector: String = "",
    val age: String = "",
    val containers: List<String> = emptyList(),
    val images: List<String> = emptyList()
)

data class PersistentVolumeClaimInfo(
    val name: String,
    val namespace: String,
    val status: String = "Pending",
    val volume: String = "",
    val capacity: String = "",
    val accessModes: String = "",
    val storageClass: String = "",
    val age: String = "",
    val volumeMode: String = "Filesystem"
)

data class DockerImageInfo(
    val repository: String,
    val tag: String,
    val imageId: String,
    val created: String,
    val size: String
)

data class CommandResult(
    val exitCode: Int,
    val output: String,
    val error: String
) {
    val isSuccess: Boolean get() = exitCode == 0
}

data class ConnectionTestResult(
    val success: Boolean,
    val message: String,
    val details: String = ""
)
