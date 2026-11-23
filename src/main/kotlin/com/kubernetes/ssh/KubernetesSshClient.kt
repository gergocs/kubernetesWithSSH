package com.kubernetes.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.kubernetes.model.AuthType
import com.kubernetes.model.CommandResult
import com.kubernetes.model.ConfigMapInfo
import com.kubernetes.model.ConnectionTestResult
import com.kubernetes.model.CronJobInfo
import com.kubernetes.model.DaemonSetInfo
import com.kubernetes.model.DeploymentInfo
import com.kubernetes.model.DockerImageInfo
import com.kubernetes.model.EventInfo
import com.kubernetes.model.IngressInfo
import com.kubernetes.model.JobInfo
import com.kubernetes.model.PersistentVolumeClaimInfo
import com.kubernetes.model.PodInfo
import com.kubernetes.model.SecretInfo
import com.kubernetes.model.ServiceInfo
import com.kubernetes.model.StatefulSetInfo
import com.kubernetes.model.SshProfile
import com.kubernetes.settings.KubernetesSshSettingsState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties

class KubernetesSshClient(private val settings: SshProfile) {

    constructor(state: KubernetesSshSettingsState) : this(state.getActiveProfile())

    fun testConnection(): ConnectionTestResult {
        if (settings.host.isBlank()) {
            return ConnectionTestResult(false, "SSH Host is not configured", "Please enter a valid host in settings.")
        }
        if (settings.username.isBlank()) {
            return ConnectionTestResult(false, "SSH Username is not configured", "Please enter a valid username in settings.")
        }

        var session: Session? = null
        return try {
            session = createSession()
            session.connect(settings.connectionTimeoutSeconds * 1000)

            // Test if kubectl exists on remote machine (execute clean command without shell-specific operators like '||')
            var kubectlCheck = executeCommand(session, "${settings.kubectlPath} version --client")
            if (!kubectlCheck.isSuccess && kubectlCheck.output.isBlank()) {
                // Fallback to basic 'kubectl version'
                val fallback = executeCommand(session, "${settings.kubectlPath} version")
                if (fallback.isSuccess || fallback.output.isNotBlank()) {
                    kubectlCheck = fallback
                }
            }

            if (!kubectlCheck.isSuccess && kubectlCheck.output.isBlank()) {
                ConnectionTestResult(
                    success = false,
                    message = "Connected to SSH, but '${settings.kubectlPath}' command was not found or failed.",
                    details = "Exit code: ${kubectlCheck.exitCode}\nOutput: ${kubectlCheck.output}\nError: ${kubectlCheck.error}"
                )
            } else {
                ConnectionTestResult(
                    success = true,
                    message = "SSH Connection & kubectl verification successful!",
                    details = "Host: ${settings.host}:${settings.port}\nKubectl output:\n${(kubectlCheck.output.ifBlank { kubectlCheck.error }).trim()}"
                )
            }
        } catch (e: Exception) {
            ConnectionTestResult(
                success = false,
                message = "Failed to connect to SSH server: ${e.message}",
                details = e.stackTraceToString()
            )
        } finally {
            session?.disconnect()
        }
    }

    fun getNamespaces(): List<String> {
        return withSession { session ->
            val result = executeCommand(session, "${settings.kubectlPath} get namespaces -o json")
            if (result.isSuccess) {
                KubernetesParser.parseNamespacesJson(result.output)
            } else {
                // Try fallback command if json is not supported or failed
                val fallback = executeCommand(session, "${settings.kubectlPath} get namespaces --no-headers -o custom-columns=\":metadata.name\"")
                if (fallback.isSuccess) {
                    KubernetesParser.parseNamespacesJson(fallback.output)
                } else {
                    throw RuntimeException("Failed to get namespaces: ${result.error.ifBlank { result.output }}")
                }
            }
        }
    }

    fun getPods(namespace: String): List<PodInfo> {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        return withSession { session ->
            val result = executeCommand(session, "${settings.kubectlPath} get pods -n $targetNs -o json")
            if (result.isSuccess) {
                KubernetesParser.parsePodsJson(result.output, targetNs)
            } else {
                // Try fallback to wide table
                val fallback = executeCommand(session, "${settings.kubectlPath} get pods -n $targetNs -o wide")
                if (fallback.isSuccess) {
                    KubernetesParser.parsePodsJson(fallback.output, targetNs)
                } else {
                    throw RuntimeException("Failed to get pods for namespace '$targetNs': ${result.error.ifBlank { result.output }}")
                }
            }
        }
    }

    fun deletePod(namespace: String, podName: String, force: Boolean = false): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val forceFlag = if (force) " --force --grace-period=0" else ""
        val command = "${settings.kubectlPath} delete pod $podName -n $targetNs$forceFlag"

        return withSession { session ->
            executeCommand(session, command)
        }
    }

    fun getPodLogs(namespace: String, podName: String, tailLines: Int = 200): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val command = "${settings.kubectlPath} logs $podName -n $targetNs --tail=$tailLines"

        return withSession { session ->
            executeCommand(session, command)
        }
    }

    fun describePod(namespace: String, podName: String): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val command = "${settings.kubectlPath} describe pod $podName -n $targetNs"

        return withSession { session ->
            executeCommand(session, command)
        }
    }

    fun getDeployments(namespace: String): List<DeploymentInfo> {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        return withSession { session ->
            val result = executeCommand(session, "${settings.kubectlPath} get deployments -n $targetNs -o json")
            if (result.isSuccess && result.output.isNotBlank()) {
                val trimmed = result.output.trim()
                if (trimmed.startsWith("{")) {
                    return@withSession KubernetesParser.parseDeploymentsJson(trimmed, targetNs)
                }
            }
            // Fallback to wide table
            val fallback = executeCommand(session, "${settings.kubectlPath} get deployments -n $targetNs -o wide")
            if (fallback.isSuccess && fallback.output.isNotBlank()) {
                val parsed = KubernetesParser.parseDeploymentsTable(fallback.output, targetNs)
                if (parsed.isNotEmpty()) return@withSession parsed
            }
            val tableFallback = executeCommand(session, "${settings.kubectlPath} get deployments -n $targetNs")
            if (tableFallback.isSuccess && tableFallback.output.isNotBlank()) {
                KubernetesParser.parseDeploymentsTable(tableFallback.output, targetNs)
            } else {
                emptyList()
            }
        }
    }

    fun getDeploymentYaml(namespace: String, deploymentName: String): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val command = "${settings.kubectlPath} get deployment $deploymentName -n $targetNs -o yaml"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    fun applyDeploymentYaml(namespace: String, yamlContent: String, dryRun: Boolean = false): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val dryRunFlag = if (dryRun) " --dry-run=server" else ""
        val command = "${settings.kubectlPath} apply -f - -n $targetNs$dryRunFlag"
        val inputBytes = yamlContent.toByteArray(Charsets.UTF_8)
        return withSession { session ->
            executeCommand(session, command, inputBytes)
        }
    }

    fun scaleDeployment(namespace: String, deploymentName: String, replicas: Int): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val command = "${settings.kubectlPath} scale deployment $deploymentName --replicas=$replicas -n $targetNs"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    fun updateDeploymentImage(namespace: String, deploymentName: String, containerName: String, newImage: String): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val command = "${settings.kubectlPath} set image deployment/$deploymentName $containerName=$newImage -n $targetNs"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    fun restartDeploymentRollout(namespace: String, deploymentName: String): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val command = "${settings.kubectlPath} rollout restart deployment/$deploymentName -n $targetNs"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    fun getDeploymentRolloutStatus(namespace: String, deploymentName: String): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val command = "${settings.kubectlPath} rollout status deployment/$deploymentName -n $targetNs --timeout=15s"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    fun getServices(namespace: String): List<ServiceInfo> {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        return withSession { session ->
            val result = executeCommand(session, "${settings.kubectlPath} get services -n $targetNs -o json")
            if (result.isSuccess && result.output.isNotBlank()) {
                val trimmed = result.output.trim()
                if (trimmed.startsWith("{")) {
                    return@withSession KubernetesParser.parseServicesJson(trimmed, targetNs)
                }
            }
            // Fallback to wide table
            val fallback = executeCommand(session, "${settings.kubectlPath} get services -n $targetNs -o wide")
            if (fallback.isSuccess && fallback.output.isNotBlank()) {
                val parsed = KubernetesParser.parseServicesTable(fallback.output, targetNs)
                if (parsed.isNotEmpty()) return@withSession parsed
            }
            val tableFallback = executeCommand(session, "${settings.kubectlPath} get services -n $targetNs")
            if (tableFallback.isSuccess && tableFallback.output.isNotBlank()) {
                KubernetesParser.parseServicesTable(tableFallback.output, targetNs)
            } else {
                emptyList()
            }
        }
    }

    fun deleteService(namespace: String, serviceName: String, force: Boolean = false): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val forceFlag = if (force) " --force --grace-period=0" else ""
        val command = "${settings.kubectlPath} delete service $serviceName -n $targetNs$forceFlag"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    fun describeService(namespace: String, serviceName: String): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val command = "${settings.kubectlPath} describe service $serviceName -n $targetNs"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    fun getServiceYaml(namespace: String, serviceName: String): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val command = "${settings.kubectlPath} get service $serviceName -n $targetNs -o yaml"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    fun getConfigMaps(namespace: String): List<ConfigMapInfo> {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        return withSession { session ->
            val result = executeCommand(session, "${settings.kubectlPath} get configmaps -n $targetNs -o json")
            if (result.isSuccess && result.output.isNotBlank()) {
                val trimmed = result.output.trim()
                if (trimmed.startsWith("{")) {
                    return@withSession KubernetesParser.parseConfigMapsJson(trimmed, targetNs)
                }
            }
            val fallback = executeCommand(session, "${settings.kubectlPath} get configmaps -n $targetNs")
            if (fallback.isSuccess && fallback.output.isNotBlank()) {
                KubernetesParser.parseConfigMapsTable(fallback.output, targetNs)
            } else {
                emptyList()
            }
        }
    }

    fun deleteConfigMap(namespace: String, configMapName: String, force: Boolean = false): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val forceFlag = if (force) " --force --grace-period=0" else ""
        val command = "${settings.kubectlPath} delete configmap $configMapName -n $targetNs$forceFlag"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    fun describeConfigMap(namespace: String, configMapName: String): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val command = "${settings.kubectlPath} describe configmap $configMapName -n $targetNs"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    fun getConfigMapYaml(namespace: String, configMapName: String): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val command = "${settings.kubectlPath} get configmap $configMapName -n $targetNs -o yaml"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    // MARK: - Secrets
    fun getSecrets(namespace: String): List<SecretInfo> {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        return withSession { session ->
            val result = executeCommand(session, "${settings.kubectlPath} get secrets -n $targetNs -o json")
            if (result.isSuccess && result.output.isNotBlank()) {
                val trimmed = result.output.trim()
                if (trimmed.startsWith("{")) {
                    return@withSession KubernetesParser.parseSecretsJson(trimmed, targetNs)
                }
            }
            val fallback = executeCommand(session, "${settings.kubectlPath} get secrets -n $targetNs")
            if (fallback.isSuccess && fallback.output.isNotBlank()) {
                KubernetesParser.parseSecretsTable(fallback.output, targetNs)
            } else {
                emptyList()
            }
        }
    }

    fun getSecretYaml(namespace: String, secretName: String): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val command = "${settings.kubectlPath} get secret $secretName -n $targetNs -o yaml"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    fun deleteSecret(namespace: String, secretName: String, force: Boolean = false): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val forceFlag = if (force) " --force --grace-period=0" else ""
        val command = "${settings.kubectlPath} delete secret $secretName -n $targetNs$forceFlag"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    fun describeSecret(namespace: String, secretName: String): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val command = "${settings.kubectlPath} describe secret $secretName -n $targetNs"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    // MARK: - Ingresses
    fun getIngresses(namespace: String): List<IngressInfo> {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        return withSession { session ->
            val result = executeCommand(session, "${settings.kubectlPath} get ingresses -n $targetNs -o json")
            if (result.isSuccess && result.output.isNotBlank()) {
                val trimmed = result.output.trim()
                if (trimmed.startsWith("{")) {
                    return@withSession KubernetesParser.parseIngressesJson(trimmed, targetNs)
                }
            }
            val fallback = executeCommand(session, "${settings.kubectlPath} get ingresses -n $targetNs")
            if (fallback.isSuccess && fallback.output.isNotBlank()) {
                KubernetesParser.parseIngressesTable(fallback.output, targetNs)
            } else {
                emptyList()
            }
        }
    }

    fun getIngressYaml(namespace: String, ingressName: String): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val command = "${settings.kubectlPath} get ingress $ingressName -n $targetNs -o yaml"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    fun deleteIngress(namespace: String, ingressName: String, force: Boolean = false): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val forceFlag = if (force) " --force --grace-period=0" else ""
        val command = "${settings.kubectlPath} delete ingress $ingressName -n $targetNs$forceFlag"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    fun describeIngress(namespace: String, ingressName: String): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val command = "${settings.kubectlPath} describe ingress $ingressName -n $targetNs"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    // MARK: - Jobs
    fun getJobs(namespace: String): List<JobInfo> {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        return withSession { session ->
            val result = executeCommand(session, "${settings.kubectlPath} get jobs -n $targetNs -o json")
            if (result.isSuccess && result.output.isNotBlank()) {
                val trimmed = result.output.trim()
                if (trimmed.startsWith("{")) {
                    return@withSession KubernetesParser.parseJobsJson(trimmed, targetNs)
                }
            }
            val fallback = executeCommand(session, "${settings.kubectlPath} get jobs -n $targetNs")
            if (fallback.isSuccess && fallback.output.isNotBlank()) {
                KubernetesParser.parseJobsTable(fallback.output, targetNs)
            } else {
                emptyList()
            }
        }
    }

    // MARK: - CronJobs
    fun getCronJobs(namespace: String): List<CronJobInfo> {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        return withSession { session ->
            val result = executeCommand(session, "${settings.kubectlPath} get cronjobs -n $targetNs -o json")
            if (result.isSuccess && result.output.isNotBlank()) {
                val trimmed = result.output.trim()
                if (trimmed.startsWith("{")) {
                    return@withSession KubernetesParser.parseCronJobsJson(trimmed, targetNs)
                }
            }
            val fallback = executeCommand(session, "${settings.kubectlPath} get cronjobs -n $targetNs")
            if (fallback.isSuccess && fallback.output.isNotBlank()) {
                KubernetesParser.parseCronJobsTable(fallback.output, targetNs)
            } else {
                emptyList()
            }
        }
    }

    fun triggerCronJob(namespace: String, cronJobName: String, jobName: String = ""): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val targetJobName = if (jobName.isNotBlank()) jobName else "$cronJobName-manual-${System.currentTimeMillis() / 1000}"
        val command = "${settings.kubectlPath} create job $targetJobName --from=cronjob/$cronJobName -n $targetNs"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    // MARK: - Events
    fun getEvents(namespace: String): List<EventInfo> {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        return withSession { session ->
            val result = executeCommand(session, "${settings.kubectlPath} get events -n $targetNs -o json")
            if (result.isSuccess && result.output.isNotBlank()) {
                val trimmed = result.output.trim()
                if (trimmed.startsWith("{")) {
                    return@withSession KubernetesParser.parseEventsJson(trimmed, targetNs)
                }
            }
            val fallback = executeCommand(session, "${settings.kubectlPath} get events -n $targetNs")
            if (fallback.isSuccess && fallback.output.isNotBlank()) {
                KubernetesParser.parseEventsTable(fallback.output, targetNs)
            } else {
                emptyList()
            }
        }
    }

    // MARK: - StatefulSets
    fun getStatefulSets(namespace: String): List<StatefulSetInfo> {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        return withSession { session ->
            val result = executeCommand(session, "${settings.kubectlPath} get statefulsets -n $targetNs -o json")
            if (result.isSuccess && result.output.isNotBlank()) {
                val trimmed = result.output.trim()
                if (trimmed.startsWith("{")) {
                    return@withSession KubernetesParser.parseStatefulSetsJson(trimmed, targetNs)
                }
            }
            val fallback = executeCommand(session, "${settings.kubectlPath} get statefulsets -n $targetNs")
            if (fallback.isSuccess && fallback.output.isNotBlank()) {
                KubernetesParser.parseStatefulSetsTable(fallback.output, targetNs)
            } else {
                emptyList()
            }
        }
    }

    fun scaleStatefulSet(namespace: String, statefulSetName: String, replicas: Int): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val command = "${settings.kubectlPath} scale statefulset $statefulSetName --replicas=$replicas -n $targetNs"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    fun restartStatefulSetRollout(namespace: String, statefulSetName: String): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val command = "${settings.kubectlPath} rollout restart statefulset/$statefulSetName -n $targetNs"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    // MARK: - DaemonSets
    fun getDaemonSets(namespace: String): List<DaemonSetInfo> {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        return withSession { session ->
            val result = executeCommand(session, "${settings.kubectlPath} get daemonsets -n $targetNs -o json")
            if (result.isSuccess && result.output.isNotBlank()) {
                val trimmed = result.output.trim()
                if (trimmed.startsWith("{")) {
                    return@withSession KubernetesParser.parseDaemonSetsJson(trimmed, targetNs)
                }
            }
            val fallback = executeCommand(session, "${settings.kubectlPath} get daemonsets -n $targetNs")
            if (fallback.isSuccess && fallback.output.isNotBlank()) {
                KubernetesParser.parseDaemonSetsTable(fallback.output, targetNs)
            } else {
                emptyList()
            }
        }
    }

    fun restartDaemonSetRollout(namespace: String, daemonSetName: String): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val command = "${settings.kubectlPath} rollout restart daemonset/$daemonSetName -n $targetNs"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    // MARK: - PersistentVolumeClaims
    fun getPersistentVolumeClaims(namespace: String): List<PersistentVolumeClaimInfo> {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        return withSession { session ->
            val result = executeCommand(session, "${settings.kubectlPath} get pvc -n $targetNs -o json")
            if (result.isSuccess && result.output.isNotBlank()) {
                val trimmed = result.output.trim()
                if (trimmed.startsWith("{")) {
                    return@withSession KubernetesParser.parsePersistentVolumeClaimsJson(trimmed, targetNs)
                }
            }
            val fallback = executeCommand(session, "${settings.kubectlPath} get pvc -n $targetNs")
            if (fallback.isSuccess && fallback.output.isNotBlank()) {
                KubernetesParser.parsePersistentVolumeClaimsTable(fallback.output, targetNs)
            } else {
                emptyList()
            }
        }
    }

    fun getResourceYaml(resourceType: String, namespace: String, name: String): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val command = "${settings.kubectlPath} get $resourceType $name -n $targetNs -o yaml"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    fun deleteResource(resourceType: String, namespace: String, name: String, force: Boolean = false): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val forceFlag = if (force) " --force --grace-period=0" else ""
        val command = "${settings.kubectlPath} delete $resourceType $name -n $targetNs$forceFlag"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    fun describeResource(resourceType: String, namespace: String, name: String): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val command = "${settings.kubectlPath} describe $resourceType $name -n $targetNs"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    fun applyResourceYaml(namespace: String, yamlContent: String, dryRun: Boolean = false): CommandResult {
        val targetNs = if (namespace.isBlank()) settings.defaultNamespace.ifBlank { "default" } else namespace
        val dryRunFlag = if (dryRun) " --dry-run=server" else ""
        val command = "${settings.kubectlPath} apply -f - -n $targetNs$dryRunFlag"
        val inputBytes = yamlContent.toByteArray(Charsets.UTF_8)
        return withSession { session ->
            executeCommand(session, command, inputBytes)
        }
    }

    fun resolveKubectlCommand(rawCommand: String, namespace: String? = null, autoNamespace: Boolean = false): String {
        val trimmed = rawCommand.trim()
        if (trimmed.isBlank()) return settings.kubectlPath

        val baseCommand = when {
            trimmed.startsWith("kubectl ", ignoreCase = true) -> {
                "${settings.kubectlPath} ${trimmed.substring("kubectl ".length).trim()}"
            }
            trimmed.equals("kubectl", ignoreCase = true) -> {
                settings.kubectlPath
            }
            trimmed.startsWith(settings.kubectlPath, ignoreCase = true) -> {
                trimmed
            }
            else -> {
                "${settings.kubectlPath} $trimmed"
            }
        }

        return if (autoNamespace && !namespace.isNullOrBlank() && namespace != "All Namespaces" &&
            !baseCommand.contains("-n ") &&
            !baseCommand.contains("--namespace") &&
            !baseCommand.contains("-A") &&
            !baseCommand.contains("--all-namespaces")
        ) {
            "$baseCommand -n $namespace"
        } else {
            baseCommand
        }
    }

    fun executeCustomKubectlCommand(
        rawCommand: String,
        namespace: String? = null,
        autoNamespace: Boolean = false
    ): CommandResult {
        val command = resolveKubectlCommand(rawCommand, namespace, autoNamespace)
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    fun executeRawCommand(command: String): CommandResult {
        return withSession { session ->
            executeCommand(session, command.trim())
        }
    }

    fun getDockerImages(): List<DockerImageInfo> {
        return withSession { session ->
            // Try docker images with json format first
            val jsonResult = executeCommand(session, "${settings.dockerPath} images --format \"{{json .}}\"")
            if (jsonResult.isSuccess && jsonResult.output.isNotBlank()) {
                val parsed = KubernetesParser.parseDockerImagesJson(jsonResult.output)
                if (parsed.isNotEmpty()) {
                    return@withSession parsed
                }
            }

            // Try docker image ls with json format
            val lsJsonResult = executeCommand(session, "${settings.dockerPath} image ls --format \"{{json .}}\"")
            if (lsJsonResult.isSuccess && lsJsonResult.output.isNotBlank()) {
                val parsed = KubernetesParser.parseDockerImagesJson(lsJsonResult.output)
                if (parsed.isNotEmpty()) {
                    return@withSession parsed
                }
            }

            // Try standard docker image list / docker images
            val imageListResult = executeCommand(session, "${settings.dockerPath} image list")
            if (imageListResult.isSuccess && imageListResult.output.isNotBlank()) {
                val parsed = KubernetesParser.parseDockerImagesTable(imageListResult.output)
                if (parsed.isNotEmpty()) {
                    return@withSession parsed
                }
            }

            val imagesResult = executeCommand(session, "${settings.dockerPath} images")
            if (imagesResult.isSuccess && imagesResult.output.isNotBlank()) {
                val parsed = KubernetesParser.parseDockerImagesTable(imagesResult.output)
                if (parsed.isNotEmpty()) {
                    return@withSession parsed
                }
            }

            // Fallback for crictl if used in Kubernetes environments
            val crictlResult = executeCommand(session, "crictl images")
            if (crictlResult.isSuccess && crictlResult.output.isNotBlank()) {
                val parsed = KubernetesParser.parseDockerImagesTable(crictlResult.output)
                if (parsed.isNotEmpty()) {
                    return@withSession parsed
                }
            }

            val combinedError = jsonResult.error.ifBlank {
                jsonResult.output.ifBlank {
                    imageListResult.error.ifBlank {
                        imageListResult.output.ifBlank {
                            imagesResult.error.ifBlank { imagesResult.output }
                        }
                    }
                }
            }
            if (combinedError.isNotBlank()) {
                throw RuntimeException("Failed to get docker images: $combinedError")
            }
            emptyList()
        }
    }

    fun deleteDockerImage(imageIdOrName: String, force: Boolean = false): CommandResult {
        val forceFlag = if (force) " -f" else ""
        val command = "${settings.dockerPath} rmi$forceFlag $imageIdOrName"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    fun inspectDockerImage(imageIdOrName: String): CommandResult {
        val command = "${settings.dockerPath} image inspect $imageIdOrName"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    fun tagDockerImage(source: String, target: String): CommandResult {
        val command = "${settings.dockerPath} tag $source $target"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    fun changeDockerImageTag(source: String, target: String, oldTagToRemove: String? = null): CommandResult {
        return withSession { session ->
            val tagResult = executeCommand(session, "${settings.dockerPath} tag $source $target")
            if (!tagResult.isSuccess) {
                return@withSession tagResult
            }

            if (!oldTagToRemove.isNullOrBlank() && oldTagToRemove != target && !oldTagToRemove.contains("<none>")) {
                val rmiResult = executeCommand(session, "${settings.dockerPath} rmi $oldTagToRemove")
                if (!rmiResult.isSuccess) {
                    val note = "Tagged '$target' successfully, but could not remove old tag '$oldTagToRemove': ${rmiResult.error.ifBlank { rmiResult.output }}"
                    return@withSession CommandResult(
                        exitCode = 0,
                        output = if (tagResult.output.isNotBlank()) "${tagResult.output}\n$note" else note,
                        error = ""
                    )
                }
            }

            tagResult
        }
    }

    fun buildDockerImage(contextPath: String, imageName: String, tag: String = "latest", dockerfilePath: String? = null): CommandResult {
        val fullImageName = if (tag.isNotBlank() && tag != "latest") "$imageName:$tag" else imageName
        val dockerfileFlag = dockerfilePath?.let { " -f $it" } ?: ""
        val command = "${settings.dockerPath} build$dockerfileFlag -t $fullImageName $contextPath"
        return withSession { session ->
            executeCommand(session, command)
        }
    }

    fun executeCommand(command: String, inputBytes: ByteArray? = null): CommandResult {
        return SshSessionPool.execute(settings, command, inputBytes)
    }

    private fun <T> withSession(block: (Session) -> T): T {
        if (settings.host.isBlank()) {
            throw IllegalStateException("SSH Host is not configured. Please open Plugin Settings.")
        }
        if (settings.username.isBlank()) {
            throw IllegalStateException("SSH Username is not configured. Please open Plugin Settings.")
        }

        // Return block evaluation; individual executeCommand calls will use the connection pool
        val dummySession = SshSessionPool.createNewSession(settings)
        return block(dummySession)
    }

    fun createSession(): Session {
        return SshSessionPool.createNewSession(settings)
    }

    private fun executeCommand(session: Session, command: String, inputBytes: ByteArray? = null): CommandResult {
        return if (session.isConnected) {
            SshSessionPool.runCommandOnSession(session, command, settings.connectionTimeoutSeconds, inputBytes)
        } else {
            SshSessionPool.execute(settings, command, inputBytes)
        }
    }
}
