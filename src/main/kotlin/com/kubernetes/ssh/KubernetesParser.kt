package com.kubernetes.ssh

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kubernetes.model.ConfigMapInfo
import com.kubernetes.model.CronJobInfo
import com.kubernetes.model.DaemonSetInfo
import com.kubernetes.model.DeploymentInfo
import com.kubernetes.model.DockerImageInfo
import com.kubernetes.model.EventInfo
import com.kubernetes.model.IngressInfo
import com.kubernetes.model.IngressRuleInfo
import com.kubernetes.model.JobInfo
import com.kubernetes.model.PersistentVolumeClaimInfo
import com.kubernetes.model.PodInfo
import com.kubernetes.model.SecretInfo
import com.kubernetes.model.ServiceInfo
import com.kubernetes.model.StatefulSetInfo
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

object KubernetesParser {

    fun JsonElement?.asSafeString(): String? {
        if (this == null || this.isJsonNull) return null
        return if (this.isJsonPrimitive) this.asString else this.toString()
    }

    fun JsonElement?.asSafeInt(): Int? {
        if (this == null || this.isJsonNull) return null
        return if (this.isJsonPrimitive) runCatching { this.asInt }.getOrNull() else null
    }

    fun JsonElement?.asSafeLong(): Long? {
        if (this == null || this.isJsonNull) return null
        return if (this.isJsonPrimitive) runCatching { this.asLong }.getOrNull() else null
    }

    fun JsonElement?.asSafeBoolean(): Boolean? {
        if (this == null || this.isJsonNull) return null
        return if (this.isJsonPrimitive) runCatching { this.asBoolean }.getOrNull() else null
    }

    fun JsonElement?.asSafeJsonObject(): JsonObject? {
        return if (this != null && !this.isJsonNull && this.isJsonObject) this.asJsonObject else null
    }

    fun JsonElement?.asSafeJsonArray(): JsonArray? {
        return if (this != null && !this.isJsonNull && this.isJsonArray) this.asJsonArray else null
    }

    fun JsonObject.getSafeString(key: String): String? = this.get(key).asSafeString()
    fun JsonObject.getSafeInt(key: String): Int? = this.get(key).asSafeInt()
    fun JsonObject.getSafeLong(key: String): Long? = this.get(key).asSafeLong()
    fun JsonObject.getSafeBoolean(key: String): Boolean? = this.get(key).asSafeBoolean()
    fun JsonObject.getSafeObject(key: String): JsonObject? = this.get(key).asSafeJsonObject()
    fun JsonObject.getSafeArray(key: String): JsonArray? = this.get(key).asSafeJsonArray()

    fun shortenImageId(rawImageId: String): String {
        if (rawImageId.isBlank()) return ""
        val trimmed = rawImageId.trim()
        val shaIndex = trimmed.indexOf("sha256:")
        val rawHash = if (shaIndex != -1) {
            trimmed.substring(shaIndex + 7)
        } else {
            val schemeIndex = trimmed.indexOf("://")
            val withoutScheme = if (schemeIndex != -1) trimmed.substring(schemeIndex + 3) else trimmed
            val atIndex = withoutScheme.indexOf("@")
            if (atIndex != -1) withoutScheme.substring(atIndex + 1) else withoutScheme
        }
        val clean = rawHash.trim().trimStart('/')
        val lastSegment = clean.substringAfterLast('/')
        return if (lastSegment.length > 12) lastSegment.substring(0, 12) else lastSegment
    }

    fun parseNamespacesJson(jsonStr: String): List<String> {
        val list = mutableListOf<String>()
        if (jsonStr.isBlank()) return list

        try {
            val root = JsonParser.parseString(jsonStr).asJsonObject
            val items = root.getAsJsonArray("items") ?: return list
            for (element in items) {
                val item = element.asJsonObject
                val metadata = item.getAsJsonObject("metadata") ?: continue
                val name = metadata.get("name")?.asString
                if (!name.isNullOrBlank()) {
                    list.add(name)
                }
            }
        } catch (e: Exception) {
            // Fallback for plain-text or line-by-line output
            jsonStr.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("NAME")) {
                    val name = trimmed.split("\\s+".toRegex()).firstOrNull()
                    if (!name.isNullOrBlank()) {
                        list.add(name)
                    }
                }
            }
        }
        return list.sorted()
    }

    fun parsePodsJson(jsonStr: String, defaultNamespace: String = ""): List<PodInfo> {
        val list = mutableListOf<PodInfo>()
        if (jsonStr.isBlank()) return list

        try {
            val root = JsonParser.parseString(jsonStr).asJsonObject
            val items = root.getAsJsonArray("items") ?: return list
            val now = Instant.now()

            for (element in items) {
                val item = element.asJsonObject
                val metadata = item.getAsJsonObject("metadata") ?: JsonObject()
                val spec = item.getAsJsonObject("spec") ?: JsonObject()
                val status = item.getAsJsonObject("status") ?: JsonObject()

                val name = metadata.get("name")?.asString ?: "unknown"
                val namespace = metadata.get("namespace")?.asString ?: defaultNamespace

                val deletionTimestamp = metadata.get("deletionTimestamp")?.asString
                val creationTimestamp = metadata.get("creationTimestamp")?.asString
                val age = formatAge(creationTimestamp, now)

                val phase = status.get("phase")?.asString ?: "Unknown"
                val reason = status.get("reason")?.asString

                val containerStatuses = status.getAsJsonArray("containerStatuses") ?: JsonArray()
                val initContainerStatuses = status.getAsJsonArray("initContainerStatuses") ?: JsonArray()

                var readyCount = 0
                var totalContainers = 0
                var totalRestarts = 0

                val podStatus = determinePodStatus(
                    phase = phase,
                    statusReason = reason,
                    deletionTimestamp = deletionTimestamp,
                    initContainerStatuses = initContainerStatuses,
                    containerStatuses = containerStatuses
                )

                // Calculate container readiness, restarts & docker/container images and image IDs
                val specContainers = spec.getAsJsonArray("containers") ?: JsonArray()
                totalContainers = if (specContainers.size() > 0) specContainers.size() else containerStatuses.size()

                val containersList = mutableListOf<String>()
                val imagesList = mutableListOf<String>()
                for (cElem in specContainers) {
                    val cObj = cElem.asJsonObject
                    val cName = cObj.get("name")?.asString
                    if (!cName.isNullOrBlank()) {
                        containersList.add(cName)
                    }
                    val img = cObj.get("image")?.asString
                    if (!img.isNullOrBlank()) {
                        imagesList.add(img)
                    }
                }

                val imageIdsList = mutableListOf<String>()
                for (cStatusElem in containerStatuses) {
                    val cStatus = cStatusElem.asJsonObject
                    val cName = cStatus.get("name")?.asString
                    if (!cName.isNullOrBlank() && !containersList.contains(cName)) {
                        containersList.add(cName)
                    }
                    if (cStatus.get("ready")?.asBoolean == true) {
                        readyCount++
                    }
                    val restarts = cStatus.get("restartCount")?.asInt ?: 0
                    totalRestarts += restarts

                    if (imagesList.isEmpty()) {
                        val img = cStatus.get("image")?.asString
                        if (!img.isNullOrBlank()) {
                            imagesList.add(img)
                        }
                    }

                    val imgId = cStatus.get("imageID")?.asString ?: cStatus.get("imageId")?.asString
                    if (!imgId.isNullOrBlank()) {
                        val shortId = shortenImageId(imgId)
                        if (shortId.isNotBlank()) {
                            imageIdsList.add(shortId)
                        }
                    }
                }

                if (imageIdsList.isEmpty()) {
                    for (initStatusElem in initContainerStatuses) {
                        val initStatus = initStatusElem.asJsonObject
                        val imgId = initStatus.get("imageID")?.asString ?: initStatus.get("imageId")?.asString
                        if (!imgId.isNullOrBlank()) {
                            val shortId = shortenImageId(imgId)
                            if (shortId.isNotBlank()) {
                                imageIdsList.add(shortId)
                            }
                        }
                    }
                }

                val readyStr = "$readyCount/$totalContainers"
                val node = spec.get("nodeName")?.asString ?: ""
                val ip = status.get("podIP")?.asString ?: ""
                val imageStr = imagesList.joinToString(", ")
                val imageIdStr = imageIdsList.joinToString(", ")

                var ownerKind = ""
                var ownerName = ""
                val ownerRefs = metadata.getAsJsonArray("ownerReferences")
                if (ownerRefs != null && ownerRefs.size() > 0) {
                    val firstOwner = ownerRefs[0].asJsonObject
                    ownerKind = firstOwner.get("kind")?.asString ?: ""
                    ownerName = firstOwner.get("name")?.asString ?: ""
                }

                val labelsMap = mutableMapOf<String, String>()
                val labelsObj = metadata.getAsJsonObject("labels")
                if (labelsObj != null) {
                    for (key in labelsObj.keySet()) {
                        labelsMap[key] = labelsObj.get(key)?.asString ?: ""
                    }
                }

                val deploymentName = inferDeploymentName(name, ownerKind, ownerName, labelsMap)

                list.add(
                    PodInfo(
                        name = name,
                        namespace = namespace,
                        status = podStatus,
                        ready = readyStr,
                        restarts = totalRestarts,
                        age = age,
                        node = node,
                        ip = ip,
                        rawPhase = phase,
                        image = imageStr,
                        images = imagesList,
                        imageId = imageIdStr,
                        imageIds = imageIdsList,
                        containers = containersList,
                        deploymentName = deploymentName,
                        ownerKind = ownerKind,
                        ownerName = ownerName
                    )
                )
            }
        } catch (e: Exception) {
            // In case of non-json output or parsing error, fallback to line parser
            return parsePodsTableText(jsonStr, defaultNamespace)
        }
        return list
    }

    private fun determinePodStatus(
        phase: String,
        statusReason: String?,
        deletionTimestamp: String?,
        initContainerStatuses: JsonArray,
        containerStatuses: JsonArray
    ): String {
        if (!deletionTimestamp.isNullOrBlank()) {
            return "Terminating"
        }

        if (!statusReason.isNullOrBlank()) {
            return statusReason
        }

        // Check init containers
        for (i in 0 until initContainerStatuses.size()) {
            val initStatus = initContainerStatuses.get(i).asJsonObject
            val state = initStatus.getAsJsonObject("state") ?: continue
            val waiting = state.getAsJsonObject("waiting")
            if (waiting != null) {
                val waitReason = waiting.get("reason")?.asString
                return if (!waitReason.isNullOrBlank()) "Init:$waitReason" else "Init:$i/${initContainerStatuses.size()}"
            }
            val terminated = state.getAsJsonObject("terminated")
            if (terminated != null) {
                val exitCode = terminated.get("exitCode")?.asInt ?: 0
                if (exitCode != 0) {
                    val termReason = terminated.get("reason")?.asString
                    return if (!termReason.isNullOrBlank()) "Init:$termReason" else "Init:ExitCode$exitCode"
                }
            }
        }

        // Check regular containers
        var hasRunning = false
        for (i in containerStatuses.size() - 1 downTo 0) {
            val cStatus = containerStatuses.get(i).asJsonObject
            val state = cStatus.getAsJsonObject("state") ?: continue

            val waiting = state.getAsJsonObject("waiting")
            if (waiting != null) {
                val waitReason = waiting.get("reason")?.asString
                if (!waitReason.isNullOrBlank()) {
                    return waitReason
                }
                return "Waiting"
            }

            val terminated = state.getAsJsonObject("terminated")
            if (terminated != null) {
                val termReason = terminated.get("reason")?.asString
                if (!termReason.isNullOrBlank()) {
                    return termReason
                }
                val exitCode = terminated.get("exitCode")?.asInt ?: 0
                return if (exitCode == 0) "Completed" else "Error"
            }

            val running = state.getAsJsonObject("running")
            if (running != null) {
                hasRunning = true
            }
        }

        if (hasRunning && phase == "Running") {
            return "Running"
        }

        return if (phase.isNotBlank()) phase else "Unknown"
    }

    fun formatAge(creationTimestamp: String?, now: Instant = Instant.now()): String {
        if (creationTimestamp.isNullOrBlank()) return ""
        try {
            val created = Instant.parse(creationTimestamp)
            val duration = Duration.between(created, now)
            val seconds = duration.seconds.coerceAtLeast(0)

            val days = seconds / 86400
            val hours = (seconds % 86400) / 3600
            val minutes = (seconds % 3600) / 60
            val secs = seconds % 60

            return when {
                days > 0 -> "${days}d"
                hours > 0 -> "${hours}h"
                minutes > 0 -> "${minutes}m"
                else -> "${secs}s"
            }
        } catch (e: DateTimeParseException) {
            return creationTimestamp
        }
    }

    private fun parsePodsTableText(tableText: String, defaultNamespace: String): List<PodInfo> {
        val list = mutableListOf<PodInfo>()
        val trimmedText = tableText.trim()
        if (trimmedText.startsWith("{") || trimmedText.startsWith("[")) return list
        val lines = tableText.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("NAME")) continue
            val parts = trimmed.split("\\s+".toRegex())
            if (parts.size >= 4) {
                val name = parts[0]
                val ready = parts[1]
                val status = parts[2]
                val restarts = parts[3].toIntOrNull() ?: 0
                val age = if (parts.size > 4) parts[4] else ""
                val ip = if (parts.size > 5) parts[5] else ""
                val node = if (parts.size > 6) parts[6] else ""

                list.add(
                    PodInfo(
                        name = name,
                        namespace = defaultNamespace,
                        status = status,
                        ready = ready,
                        restarts = restarts,
                        age = age,
                        node = node,
                        ip = ip,
                        rawPhase = status,
                        image = "",
                        images = emptyList(),
                        imageId = "",
                        imageIds = emptyList(),
                        containers = emptyList()
                    )
                )
            }
        }
        return list
    }

    fun parseDockerImagesJson(jsonStr: String): List<DockerImageInfo> {
        val list = mutableListOf<DockerImageInfo>()
        if (jsonStr.isBlank()) return list

        for (line in jsonStr.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            try {
                val jsonElement = JsonParser.parseString(trimmed)
                if (jsonElement.isJsonObject) {
                    val obj = jsonElement.asJsonObject
                    val repo = obj.get("Repository")?.asString
                        ?: obj.get("repository")?.asString
                        ?: ""
                    val tag = obj.get("Tag")?.asString
                        ?: obj.get("tag")?.asString
                        ?: ""
                    val rawId = obj.get("ID")?.asString
                        ?: obj.get("id")?.asString
                        ?: obj.get("ImageId")?.asString
                        ?: obj.get("imageId")?.asString
                        ?: ""
                    val imageId = shortenImageId(rawId)
                    val created = obj.get("CreatedSince")?.asString
                        ?: obj.get("CreatedAt")?.asString
                        ?: obj.get("created")?.asString
                        ?: ""
                    val size = obj.get("Size")?.asString
                        ?: obj.get("size")?.asString
                        ?: obj.get("VirtualSize")?.asString
                        ?: ""

                    if (repo.isNotBlank() || imageId.isNotBlank()) {
                        list.add(
                            DockerImageInfo(
                                repository = repo,
                                tag = tag,
                                imageId = imageId,
                                created = created,
                                size = size
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                // Ignore non-json line and attempt fallback
            }
        }

        if (list.isEmpty()) {
            return parseDockerImagesTable(jsonStr)
        }

        return list
    }

    fun parseDockerImagesTable(tableStr: String): List<DockerImageInfo> {
        val list = mutableListOf<DockerImageInfo>()
        if (tableStr.isBlank()) return list

        val lines = tableStr.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            if (line.startsWith("REPOSITORY", ignoreCase = true) || line.startsWith("IMAGE ID", ignoreCase = true)) {
                continue
            }
            val tokens = line.split("\\s+".toRegex())
            if (tokens.size >= 5) {
                val repository = tokens[0]
                val tag = tokens[1]
                val imageId = shortenImageId(tokens[2])
                val size = tokens.last()
                val created = tokens.subList(3, tokens.size - 1).joinToString(" ")
                list.add(
                    DockerImageInfo(
                        repository = repository,
                        tag = tag,
                        imageId = imageId,
                        created = created,
                        size = size
                    )
                )
            } else if (tokens.size in 3..4) {
                val repository = tokens[0]
                val tag = if (tokens.size >= 4) tokens[1] else "<none>"
                val imageId = shortenImageId(if (tokens.size >= 4) tokens[2] else tokens[1])
                val size = tokens.last()
                list.add(
                    DockerImageInfo(
                        repository = repository,
                        tag = tag,
                        imageId = imageId,
                        created = "",
                        size = size
                    )
                )
            }
        }
        return list
    }
    fun inferDeploymentName(
        podName: String,
        ownerKind: String = "",
        ownerName: String = "",
        labels: Map<String, String> = emptyMap()
    ): String {
        if (ownerKind.equals("Deployment", ignoreCase = true) && ownerName.isNotBlank()) {
            return ownerName
        }
        if (ownerKind.equals("ReplicaSet", ignoreCase = true) && ownerName.isNotBlank()) {
            val stripped = ownerName.substringBeforeLast('-')
            if (stripped.isNotBlank()) {
                return stripped
            }
        }
        val appName = labels["app.kubernetes.io/name"] ?: labels["app"] ?: labels["k8s-app"] ?: labels["deployment"]
        if (!appName.isNullOrBlank()) {
            return appName
        }
        // Fallback: analyze pod name
        val segments = podName.split('-')
        if (segments.size >= 3) {
            val lastSeg = segments.last()
            val secondLastSeg = segments[segments.size - 2]
            if (lastSeg.length in 3..10 && secondLastSeg.length in 3..12) {
                return segments.dropLast(2).joinToString("-")
            }
        }
        if (segments.size >= 2) {
            val lastSeg = segments.last()
            if (lastSeg.length in 3..10) {
                return segments.dropLast(1).joinToString("-")
            }
        }
        return ""
    }

    fun parseDeploymentsJson(jsonStr: String, defaultNamespace: String = ""): List<DeploymentInfo> {
        val list = mutableListOf<DeploymentInfo>()
        if (jsonStr.isBlank()) return list

        try {
            val root = JsonParser.parseString(jsonStr).asJsonObject
            val items = root.getAsJsonArray("items") ?: run {
                if (root.has("kind") && root.get("kind")?.asString == "Deployment") {
                    val array = JsonArray()
                    array.add(root)
                    array
                } else return list
            }
            val now = Instant.now()
            for (element in items) {
                val item = element.asJsonObject
                val metadata = item.getAsJsonObject("metadata") ?: JsonObject()
                val spec = item.getAsJsonObject("spec") ?: JsonObject()
                val status = item.getAsJsonObject("status") ?: JsonObject()

                val name = metadata.get("name")?.asString ?: continue
                val namespace = metadata.get("namespace")?.asString ?: defaultNamespace

                val creationTimestamp = metadata.get("creationTimestamp")?.asString
                val age = formatAge(creationTimestamp, now)

                val replicas = spec.get("replicas")?.asInt ?: 1
                val readyReplicas = status.get("readyReplicas")?.asInt ?: 0
                val updatedReplicas = status.get("updatedReplicas")?.asInt ?: 0
                val availableReplicas = status.get("availableReplicas")?.asInt ?: 0

                val template = spec.getAsJsonObject("template") ?: JsonObject()
                val templateSpec = template.getAsJsonObject("spec") ?: JsonObject()
                val specContainers = templateSpec.getAsJsonArray("containers") ?: JsonArray()

                val containersList = mutableListOf<String>()
                val imagesList = mutableListOf<String>()
                for (cElem in specContainers) {
                    val cObj = cElem.asJsonObject
                    val cName = cObj.get("name")?.asString
                    if (!cName.isNullOrBlank()) {
                        containersList.add(cName)
                    }
                    val img = cObj.get("image")?.asString
                    if (!img.isNullOrBlank()) {
                        imagesList.add(img)
                    }
                }

                list.add(
                    DeploymentInfo(
                        name = name,
                        namespace = namespace,
                        readyReplicas = readyReplicas,
                        replicas = replicas,
                        updatedReplicas = updatedReplicas,
                        availableReplicas = availableReplicas,
                        age = age,
                        images = imagesList,
                        containers = containersList
                    )
                )
            }
        } catch (e: Exception) {
            val t = jsonStr.trim()
            if (!t.startsWith("{") && !t.startsWith("[")) {
                return parseDeploymentsTable(jsonStr, defaultNamespace)
            }
            return emptyList()
        }
        return list
    }

    fun parseDeploymentsTable(tableStr: String, defaultNamespace: String = ""): List<DeploymentInfo> {
        val list = mutableListOf<DeploymentInfo>()
        if (tableStr.isBlank()) return list

        val trimmed = tableStr.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return emptyList()

        val lines = tableStr.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            if (line.startsWith("NAME", ignoreCase = true)) continue
            val tokens = line.split("\\s+".toRegex())
            if (tokens.isEmpty()) continue

            // NAME   READY   UP-TO-DATE   AVAILABLE   AGE
            val name = tokens[0]
            var readyReplicas = 0
            var replicas = 0
            var updatedReplicas = 0
            var availableReplicas = 0
            var age = ""

            if (tokens.size >= 2) {
                val readyToken = tokens[1]
                if (readyToken.contains('/')) {
                    val parts = readyToken.split('/')
                    readyReplicas = parts.getOrNull(0)?.toIntOrNull() ?: 0
                    replicas = parts.getOrNull(1)?.toIntOrNull() ?: 0
                }
            }
            if (tokens.size >= 3) {
                updatedReplicas = tokens[2].toIntOrNull() ?: 0
            }
            if (tokens.size >= 4) {
                availableReplicas = tokens[3].toIntOrNull() ?: 0
            }
            if (tokens.size >= 5) {
                age = tokens[4]
            }

            val containersList = mutableListOf<String>()
            val imagesList = mutableListOf<String>()
            if (tokens.size >= 6) {
                containersList.addAll(tokens[5].split(',').map { it.trim() }.filter { it.isNotEmpty() })
            }
            if (tokens.size >= 7) {
                imagesList.addAll(tokens[6].split(',').map { it.trim() }.filter { it.isNotEmpty() })
            }

            list.add(
                DeploymentInfo(
                    name = name,
                    namespace = defaultNamespace,
                    readyReplicas = readyReplicas,
                    replicas = replicas,
                    updatedReplicas = updatedReplicas,
                    availableReplicas = availableReplicas,
                    age = age,
                    images = imagesList,
                    containers = containersList
                )
            )
        }
        return list
    }

    fun parseServicesJson(jsonStr: String, defaultNamespace: String = ""): List<ServiceInfo> {
        val list = mutableListOf<ServiceInfo>()
        if (jsonStr.isBlank()) return list

        try {
            val root = JsonParser.parseString(jsonStr).asJsonObject
            val items = root.getAsJsonArray("items") ?: run {
                if (root.has("kind") && root.get("kind")?.asString == "Service") {
                    val array = JsonArray()
                    array.add(root)
                    array
                } else return list
            }
            val now = Instant.now()
            for (element in items) {
                val item = element.asJsonObject
                val metadata = item.getAsJsonObject("metadata") ?: JsonObject()
                val spec = item.getAsJsonObject("spec") ?: JsonObject()
                val status = item.getAsJsonObject("status") ?: JsonObject()

                val name = metadata.get("name")?.asString ?: continue
                val namespace = metadata.get("namespace")?.asString ?: defaultNamespace

                val creationTimestamp = metadata.get("creationTimestamp")?.asString
                val age = formatAge(creationTimestamp, now)

                val type = spec.get("type")?.asString ?: "ClusterIP"
                val clusterIP = spec.get("clusterIP")?.asString ?: ""

                // Extract external IP
                var externalIP = "<none>"
                val loadBalancer = status.getAsJsonObject("loadBalancer")
                val ingressArray = loadBalancer?.getAsJsonArray("ingress")
                if (ingressArray != null && ingressArray.size() > 0) {
                    val ingressIps = mutableListOf<String>()
                    for (ingElem in ingressArray) {
                        val ingObj = ingElem.asJsonObject
                        val ip = ingObj.get("ip")?.asString ?: ingObj.get("hostname")?.asString
                        if (!ip.isNullOrBlank()) {
                            ingressIps.add(ip)
                        }
                    }
                    if (ingressIps.isNotEmpty()) {
                        externalIP = ingressIps.joinToString(",")
                    }
                } else if (spec.has("externalIPs")) {
                    val extArray = spec.getAsJsonArray("externalIPs")
                    val extIps = mutableListOf<String>()
                    for (e in extArray) {
                        val ipStr = e.asString
                        if (!ipStr.isNullOrBlank()) {
                            extIps.add(ipStr)
                        }
                    }
                    if (extIps.isNotEmpty()) {
                        externalIP = extIps.joinToString(",")
                    }
                } else if (type == "ExternalName" && spec.has("externalName")) {
                    externalIP = spec.get("externalName")?.asString ?: "<none>"
                } else if (type == "LoadBalancer" && externalIP == "<none>") {
                    externalIP = "<pending>"
                }

                // Extract ports
                val portsArray = spec.getAsJsonArray("ports") ?: JsonArray()
                val portsList = mutableListOf<String>()
                for (pElem in portsArray) {
                    val pObj = pElem.asJsonObject
                    val port = pObj.get("port")?.asInt
                    val nodePort = pObj.get("nodePort")?.asInt
                    val protocol = pObj.get("protocol")?.asString ?: "TCP"
                    if (port != null) {
                        if (nodePort != null && nodePort > 0) {
                            portsList.add("$port:$nodePort/$protocol")
                        } else {
                            portsList.add("$port/$protocol")
                        }
                    }
                }
                val portsStr = if (portsList.isNotEmpty()) portsList.joinToString(",") else "<none>"

                // Extract selector
                val selectorObj = spec.getAsJsonObject("selector")
                val selectorMap = mutableMapOf<String, String>()
                val selectorEntries = mutableListOf<String>()
                if (selectorObj != null) {
                    for (key in selectorObj.keySet()) {
                        val v = selectorObj.get(key)?.asString ?: ""
                        selectorMap[key] = v
                        selectorEntries.add("$key=$v")
                    }
                }
                val selectorStr = if (selectorEntries.isNotEmpty()) selectorEntries.joinToString(",") else "<none>"

                list.add(
                    ServiceInfo(
                        name = name,
                        namespace = namespace,
                        type = type,
                        clusterIP = clusterIP,
                        externalIP = externalIP,
                        ports = portsStr,
                        age = age,
                        selector = selectorStr,
                        selectorMap = selectorMap
                    )
                )
            }
        } catch (e: Exception) {
            val t = jsonStr.trim()
            if (!t.startsWith("{") && !t.startsWith("[")) {
                return parseServicesTable(jsonStr, defaultNamespace)
            }
            return emptyList()
        }
        return list
    }

    fun parseServicesTable(tableStr: String, defaultNamespace: String = ""): List<ServiceInfo> {
        val list = mutableListOf<ServiceInfo>()
        if (tableStr.isBlank()) return list

        val trimmed = tableStr.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return emptyList()

        val lines = tableStr.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            if (line.startsWith("NAME", ignoreCase = true)) continue
            val tokens = line.split("\\s+".toRegex())
            if (tokens.isEmpty()) continue

            // NAME   TYPE   CLUSTER-IP   EXTERNAL-IP   PORT(S)   AGE   SELECTOR (optional)
            val name = tokens[0]
            val type = if (tokens.size >= 2) tokens[1] else "ClusterIP"
            val clusterIP = if (tokens.size >= 3) tokens[2] else ""
            val externalIP = if (tokens.size >= 4) tokens[3] else "<none>"
            val ports = if (tokens.size >= 5) tokens[4] else "<none>"
            val age = if (tokens.size >= 6) tokens[5] else ""
            val selector = if (tokens.size >= 7) tokens.subList(6, tokens.size).joinToString(" ") else ""

            list.add(
                ServiceInfo(
                    name = name,
                    namespace = defaultNamespace,
                    type = type,
                    clusterIP = clusterIP,
                    externalIP = externalIP,
                    ports = ports,
                    age = age,
                    selector = selector
                )
            )
        }
        return list
    }

    fun parseConfigMapsJson(jsonStr: String, defaultNamespace: String = ""): List<ConfigMapInfo> {
        val list = mutableListOf<ConfigMapInfo>()
        if (jsonStr.isBlank()) return list

        try {
            val root = JsonParser.parseString(jsonStr).asJsonObject
            val items = root.getAsJsonArray("items") ?: run {
                if (root.has("kind") && root.get("kind")?.asString == "ConfigMap") {
                    val array = JsonArray()
                    array.add(root)
                    array
                } else return list
            }
            val now = Instant.now()
            for (element in items) {
                val item = element.asJsonObject
                val metadata = item.getAsJsonObject("metadata") ?: JsonObject()

                val name = metadata.get("name")?.asString ?: continue
                val namespace = metadata.get("namespace")?.asString ?: defaultNamespace

                val creationTimestamp = metadata.get("creationTimestamp")?.asString
                val age = formatAge(creationTimestamp, now)

                val dataMap = mutableMapOf<String, String>()
                val keysList = mutableListOf<String>()

                val dataObj = item.getAsJsonObject("data")
                if (dataObj != null) {
                    for (k in dataObj.keySet()) {
                        keysList.add(k)
                        dataMap[k] = dataObj.get(k)?.asString ?: ""
                    }
                }

                val binKeysList = mutableListOf<String>()
                val binaryDataObj = item.getAsJsonObject("binaryData")
                if (binaryDataObj != null) {
                    for (k in binaryDataObj.keySet()) {
                        keysList.add(k)
                        binKeysList.add(k)
                    }
                }

                list.add(
                    ConfigMapInfo(
                        name = name,
                        namespace = namespace,
                        dataCount = keysList.size,
                        keys = keysList,
                        age = age,
                        data = dataMap,
                        binaryDataKeys = binKeysList
                    )
                )
            }
        } catch (e: Exception) {
            val t = jsonStr.trim()
            if (!t.startsWith("{") && !t.startsWith("[")) {
                return parseConfigMapsTable(jsonStr, defaultNamespace)
            }
            return emptyList()
        }
        return list
    }

    fun parseConfigMapsTable(tableStr: String, defaultNamespace: String = ""): List<ConfigMapInfo> {
        val list = mutableListOf<ConfigMapInfo>()
        if (tableStr.isBlank()) return list

        val trimmed = tableStr.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return emptyList()

        val lines = tableStr.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            if (line.startsWith("NAME", ignoreCase = true)) continue
            val tokens = line.split("\\s+".toRegex())
            if (tokens.isEmpty()) continue

            // NAME   DATA   AGE
            val name = tokens[0]
            val dataCount = if (tokens.size >= 2) tokens[1].toIntOrNull() ?: 0 else 0
            val age = if (tokens.size >= 3) tokens[2] else ""

            list.add(
                ConfigMapInfo(
                    name = name,
                    namespace = defaultNamespace,
                    dataCount = dataCount,
                    keys = emptyList(),
                    age = age
                )
            )
        }
        return list
    }

    // MARK: - Secrets
    fun parseSecretsJson(jsonStr: String, defaultNamespace: String = ""): List<SecretInfo> {
        val list = mutableListOf<SecretInfo>()
        if (jsonStr.isBlank()) return list

        try {
            val root = JsonParser.parseString(jsonStr).asJsonObject
            val items = root.getAsJsonArray("items") ?: run {
                if (root.has("kind") && root.get("kind")?.asString == "Secret") {
                    val array = JsonArray()
                    array.add(root)
                    array
                } else return list
            }
            val now = Instant.now()
            for (element in items) {
                val item = element.asJsonObject
                val metadata = item.getAsJsonObject("metadata") ?: JsonObject()

                val name = metadata.get("name")?.asString ?: continue
                val namespace = metadata.get("namespace")?.asString ?: defaultNamespace

                val creationTimestamp = metadata.get("creationTimestamp")?.asString
                val age = formatAge(creationTimestamp, now)

                val type = item.get("type")?.asString ?: "Opaque"

                val dataMap = mutableMapOf<String, String>()
                val keysList = mutableListOf<String>()

                val dataObj = item.getAsJsonObject("data")
                if (dataObj != null) {
                    for (k in dataObj.keySet()) {
                        keysList.add(k)
                        dataMap[k] = dataObj.get(k)?.asString ?: ""
                    }
                }

                val stringDataMap = mutableMapOf<String, String>()
                val stringDataObj = item.getAsJsonObject("stringData")
                if (stringDataObj != null) {
                    for (k in stringDataObj.keySet()) {
                        if (!keysList.contains(k)) {
                            keysList.add(k)
                        }
                        stringDataMap[k] = stringDataObj.get(k)?.asString ?: ""
                    }
                }

                list.add(
                    SecretInfo(
                        name = name,
                        namespace = namespace,
                        type = type,
                        dataCount = keysList.size,
                        keys = keysList,
                        age = age,
                        data = dataMap,
                        stringData = stringDataMap
                    )
                )
            }
        } catch (e: Exception) {
            val t = jsonStr.trim()
            if (!t.startsWith("{") && !t.startsWith("[")) {
                return parseSecretsTable(jsonStr, defaultNamespace)
            }
            return emptyList()
        }
        return list
    }

    fun parseSecretsTable(tableStr: String, defaultNamespace: String = ""): List<SecretInfo> {
        val list = mutableListOf<SecretInfo>()
        if (tableStr.isBlank()) return list

        val trimmed = tableStr.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return emptyList()

        val lines = tableStr.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            if (line.startsWith("NAME", ignoreCase = true)) continue
            val tokens = line.split("\\s+".toRegex())
            if (tokens.isEmpty()) continue

            // NAME   TYPE   DATA   AGE
            val name = tokens[0]
            val type = if (tokens.size >= 2) tokens[1] else "Opaque"
            val dataCount = if (tokens.size >= 3) tokens[2].toIntOrNull() ?: 0 else 0
            val age = if (tokens.size >= 4) tokens[3] else ""

            list.add(
                SecretInfo(
                    name = name,
                    namespace = defaultNamespace,
                    type = type,
                    dataCount = dataCount,
                    keys = emptyList(),
                    age = age
                )
            )
        }
        return list
    }

    // MARK: - Ingresses
    fun parseIngressesJson(jsonStr: String, defaultNamespace: String = ""): List<IngressInfo> {
        val list = mutableListOf<IngressInfo>()
        if (jsonStr.isBlank()) return list

        try {
            val root = JsonParser.parseString(jsonStr).asJsonObject
            val items = root.getAsJsonArray("items") ?: run {
                if (root.has("kind") && root.get("kind")?.asString == "Ingress") {
                    val array = JsonArray()
                    array.add(root)
                    array
                } else return list
            }
            val now = Instant.now()
            for (element in items) {
                val item = element.asJsonObject
                val metadata = item.getAsJsonObject("metadata") ?: JsonObject()
                val spec = item.getAsJsonObject("spec") ?: JsonObject()
                val status = item.getAsJsonObject("status") ?: JsonObject()

                val name = metadata.get("name")?.asString ?: continue
                val namespace = metadata.get("namespace")?.asString ?: defaultNamespace

                val creationTimestamp = metadata.get("creationTimestamp")?.asString
                val age = formatAge(creationTimestamp, now)

                val annotations = metadata.getAsJsonObject("annotations")
                val className = spec.get("ingressClassName")?.asString
                    ?: annotations?.get("kubernetes.io/ingress.class")?.asString
                    ?: ""

                val rulesArray = spec.getAsJsonArray("rules") ?: JsonArray()
                val hostList = mutableListOf<String>()
                val parsedRules = mutableListOf<IngressRuleInfo>()

                for (rElem in rulesArray) {
                    val rObj = rElem.asJsonObject
                    val host = rObj.get("host")?.asString ?: "*"
                    if (host.isNotBlank() && !hostList.contains(host)) {
                        hostList.add(host)
                    }

                    val httpObj = rObj.getAsJsonObject("http")
                    val pathsArray = httpObj?.getAsJsonArray("paths") ?: JsonArray()
                    for (pElem in pathsArray) {
                        val pObj = pElem.asJsonObject
                        val path = pObj.get("path")?.asString ?: "/"
                        val pathType = pObj.get("pathType")?.asString ?: "Prefix"

                        val backendObj = pObj.getAsJsonObject("backend")
                        val serviceObj = backendObj?.getAsJsonObject("service")
                        val backendService = serviceObj?.get("name")?.asString
                            ?: backendObj?.get("serviceName")?.asString
                            ?: ""
                        val portObj = serviceObj?.getAsJsonObject("port")
                        val backendPort = portObj?.get("number")?.asString
                            ?: portObj?.get("name")?.asString
                            ?: backendObj?.get("servicePort")?.asString
                            ?: ""

                        parsedRules.add(
                            IngressRuleInfo(
                                host = host,
                                path = path,
                                pathType = pathType,
                                backendService = backendService,
                                backendPort = backendPort
                            )
                        )
                    }
                }

                val hosts = if (hostList.isEmpty()) "*" else hostList.joinToString(", ")

                // Status address / loadbalancer IP
                val lbIngress = status.getAsJsonObject("loadBalancer")?.getAsJsonArray("ingress") ?: JsonArray()
                val addrList = mutableListOf<String>()
                for (lbElem in lbIngress) {
                    val lbObj = lbElem.asJsonObject
                    val ip = lbObj.get("ip")?.asString
                    val hostname = lbObj.get("hostname")?.asString
                    if (!ip.isNullOrBlank()) addrList.add(ip)
                    else if (!hostname.isNullOrBlank()) addrList.add(hostname)
                }
                val address = if (addrList.isEmpty()) "<none>" else addrList.joinToString(", ")

                val hasTls = spec.has("tls") && (spec.getAsJsonArray("tls")?.size() ?: 0) > 0
                val ports = if (hasTls) "80, 443" else "80"

                list.add(
                    IngressInfo(
                        name = name,
                        namespace = namespace,
                        className = className,
                        hosts = hosts,
                        hostsList = hostList,
                        address = address,
                        ports = ports,
                        age = age,
                        rules = parsedRules
                    )
                )
            }
        } catch (e: Exception) {
            val t = jsonStr.trim()
            if (!t.startsWith("{") && !t.startsWith("[")) {
                return parseIngressesTable(jsonStr, defaultNamespace)
            }
            return emptyList()
        }
        return list
    }

    fun parseIngressesTable(tableStr: String, defaultNamespace: String = ""): List<IngressInfo> {
        val list = mutableListOf<IngressInfo>()
        if (tableStr.isBlank()) return list

        val trimmed = tableStr.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return emptyList()

        val lines = tableStr.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            if (line.startsWith("NAME", ignoreCase = true)) continue
            val tokens = line.split("\\s+".toRegex())
            if (tokens.isEmpty()) continue

            // NAME   CLASS   HOSTS   ADDRESS   PORTS...   AGE
            val name = tokens[0]
            val className = if (tokens.size >= 2) tokens[1] else ""
            val hosts = if (tokens.size >= 3) tokens[2] else "*"
            val address = if (tokens.size >= 4) tokens[3] else "<none>"
            val age = if (tokens.size >= 5) tokens[tokens.size - 1] else ""
            val ports = if (tokens.size >= 6) {
                tokens.subList(4, tokens.size - 1).joinToString(" ")
            } else if (tokens.size == 5) {
                tokens[4]
            } else "80"

            list.add(
                IngressInfo(
                    name = name,
                    namespace = defaultNamespace,
                    className = className,
                    hosts = hosts,
                    hostsList = if (hosts == "*") listOf("*") else hosts.split(",").map { it.trim() },
                    address = address,
                    ports = ports,
                    age = age
                )
            )
        }
        return list
    }

    // MARK: - Jobs
    fun parseJobsJson(jsonStr: String, defaultNamespace: String = ""): List<JobInfo> {
        val list = mutableListOf<JobInfo>()
        if (jsonStr.isBlank()) return list

        try {
            val root = JsonParser.parseString(jsonStr).asJsonObject
            val items = root.getAsJsonArray("items") ?: run {
                if (root.has("kind") && root.get("kind")?.asString == "Job") {
                    val array = JsonArray()
                    array.add(root)
                    array
                } else return list
            }
            val now = Instant.now()
            for (element in items) {
                val item = element.asJsonObject
                val metadata = item.getAsJsonObject("metadata") ?: JsonObject()
                val spec = item.getAsJsonObject("spec") ?: JsonObject()
                val status = item.getAsJsonObject("status") ?: JsonObject()

                val name = metadata.get("name")?.asString ?: continue
                val namespace = metadata.get("namespace")?.asString ?: defaultNamespace

                val creationTimestamp = metadata.get("creationTimestamp")?.asString
                val age = formatAge(creationTimestamp, now)

                val desiredCompletions = spec.get("completions")?.asInt ?: 1
                val succeeded = status.get("succeeded")?.asInt ?: 0
                val active = status.get("active")?.asInt ?: 0
                val failed = status.get("failed")?.asInt ?: 0

                val completions = "$succeeded/$desiredCompletions"

                // Duration
                val startTimeStr = status.get("startTime")?.asString
                val completionTimeStr = status.get("completionTime")?.asString
                val duration = if (!startTimeStr.isNullOrBlank()) {
                    try {
                        val start = Instant.parse(startTimeStr)
                        val end = if (!completionTimeStr.isNullOrBlank()) Instant.parse(completionTimeStr) else now
                        val dur = Duration.between(start, end).seconds.coerceAtLeast(0)
                        val mins = dur / 60
                        val secs = dur % 60
                        if (mins > 0) "${mins}m${secs}s" else "${secs}s"
                    } catch (_: Exception) { "" }
                } else ""

                // Containers & Images
                val templateSpec = spec.getAsJsonObject("template")?.getAsJsonObject("spec")
                val containersArray = templateSpec?.getAsJsonArray("containers") ?: JsonArray()
                val containers = mutableListOf<String>()
                val images = mutableListOf<String>()
                for (cElem in containersArray) {
                    val cObj = cElem.asJsonObject
                    cObj.get("name")?.asString?.let { containers.add(it) }
                    cObj.get("image")?.asString?.let { images.add(it) }
                }

                val conditions = status.getAsJsonArray("conditions") ?: JsonArray()
                var conditionStr = when {
                    succeeded >= desiredCompletions -> "Complete"
                    failed > 0 -> "Failed"
                    active > 0 -> "Running"
                    else -> "Pending"
                }
                if (conditions.size() > 0) {
                    val lastCond = conditions.get(conditions.size() - 1).asJsonObject
                    val condType = lastCond.get("type")?.asString
                    val condStatus = lastCond.get("status")?.asString
                    if (condStatus.equals("True", ignoreCase = true) && !condType.isNullOrBlank()) {
                        conditionStr = condType
                    }
                }

                list.add(
                    JobInfo(
                        name = name,
                        namespace = namespace,
                        completions = completions,
                        duration = duration,
                        age = age,
                        containers = containers,
                        images = images,
                        condition = conditionStr,
                        active = active,
                        succeeded = succeeded,
                        failed = failed
                    )
                )
            }
        } catch (e: Exception) {
            val t = jsonStr.trim()
            if (!t.startsWith("{") && !t.startsWith("[")) {
                return parseJobsTable(jsonStr, defaultNamespace)
            }
            return emptyList()
        }
        return list
    }

    fun parseJobsTable(tableStr: String, defaultNamespace: String = ""): List<JobInfo> {
        val list = mutableListOf<JobInfo>()
        if (tableStr.isBlank()) return list

        val trimmed = tableStr.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return emptyList()

        val lines = tableStr.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            if (line.startsWith("NAME", ignoreCase = true)) continue
            val tokens = line.split("\\s+".toRegex())
            if (tokens.isEmpty()) continue

            // NAME   COMPLETIONS   DURATION   AGE   CONTAINERS(opt)   IMAGES(opt)
            val name = tokens[0]
            val completions = if (tokens.size >= 2) tokens[1] else "0/1"
            val duration = if (tokens.size >= 3) tokens[2] else ""
            val age = if (tokens.size >= 4) tokens[3] else ""
            val containers = if (tokens.size >= 5) tokens[4].split(",").map { it.trim() } else emptyList()
            val images = if (tokens.size >= 6) tokens[5].split(",").map { it.trim() } else emptyList()

            list.add(
                JobInfo(
                    name = name,
                    namespace = defaultNamespace,
                    completions = completions,
                    duration = duration,
                    age = age,
                    containers = containers,
                    images = images
                )
            )
        }
        return list
    }

    // MARK: - CronJobs
    fun parseCronJobsJson(jsonStr: String, defaultNamespace: String = ""): List<CronJobInfo> {
        val list = mutableListOf<CronJobInfo>()
        if (jsonStr.isBlank()) return list

        try {
            val root = JsonParser.parseString(jsonStr).asJsonObject
            val items = root.getAsJsonArray("items") ?: run {
                if (root.has("kind") && root.get("kind")?.asString == "CronJob") {
                    val array = JsonArray()
                    array.add(root)
                    array
                } else return list
            }
            val now = Instant.now()
            for (element in items) {
                val item = element.asJsonObject
                val metadata = item.getAsJsonObject("metadata") ?: JsonObject()
                val spec = item.getAsJsonObject("spec") ?: JsonObject()
                val status = item.getAsJsonObject("status") ?: JsonObject()

                val name = metadata.get("name")?.asString ?: continue
                val namespace = metadata.get("namespace")?.asString ?: defaultNamespace

                val creationTimestamp = metadata.get("creationTimestamp")?.asString
                val age = formatAge(creationTimestamp, now)

                val schedule = spec.get("schedule")?.asString ?: ""
                val suspend = spec.get("suspend")?.asBoolean ?: false

                val activeList = status.getAsJsonArray("active") ?: JsonArray()
                val active = activeList.size()

                val lastScheduleTime = status.get("lastScheduleTime")?.asString
                val lastSchedule = if (!lastScheduleTime.isNullOrBlank()) formatAge(lastScheduleTime, now) else "<none>"

                val templateSpec = spec.getAsJsonObject("jobTemplate")?.getAsJsonObject("spec")?.getAsJsonObject("template")?.getAsJsonObject("spec")
                val containersArray = templateSpec?.getAsJsonArray("containers") ?: JsonArray()
                val containers = mutableListOf<String>()
                val images = mutableListOf<String>()
                for (cElem in containersArray) {
                    val cObj = cElem.asJsonObject
                    cObj.get("name")?.asString?.let { containers.add(it) }
                    cObj.get("image")?.asString?.let { images.add(it) }
                }

                list.add(
                    CronJobInfo(
                        name = name,
                        namespace = namespace,
                        schedule = schedule,
                        suspend = suspend,
                        active = active,
                        lastSchedule = lastSchedule,
                        age = age,
                        containers = containers,
                        images = images
                    )
                )
            }
        } catch (e: Exception) {
            val t = jsonStr.trim()
            if (!t.startsWith("{") && !t.startsWith("[")) {
                return parseCronJobsTable(jsonStr, defaultNamespace)
            }
            return emptyList()
        }
        return list
    }

    fun parseCronJobsTable(tableStr: String, defaultNamespace: String = ""): List<CronJobInfo> {
        val list = mutableListOf<CronJobInfo>()
        if (tableStr.isBlank()) return list

        val trimmed = tableStr.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return emptyList()

        val lines = tableStr.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            if (line.startsWith("NAME", ignoreCase = true)) continue
            val tokens = line.split("\\s+".toRegex())
            if (tokens.isEmpty()) continue

            // NAME   SCHEDULE...   SUSPEND   ACTIVE   LAST SCHEDULE   AGE
            val name = tokens[0]
            if (tokens.size >= 6) {
                val age = tokens[tokens.size - 1]
                val lastSchedule = tokens[tokens.size - 2]
                val active = tokens[tokens.size - 3].toIntOrNull() ?: 0
                val suspend = tokens[tokens.size - 4].equals("True", ignoreCase = true)
                val schedule = tokens.subList(1, tokens.size - 4).joinToString(" ")

                list.add(
                    CronJobInfo(
                        name = name,
                        namespace = defaultNamespace,
                        schedule = schedule,
                        suspend = suspend,
                        active = active,
                        lastSchedule = lastSchedule,
                        age = age
                    )
                )
            } else {
                val schedule = if (tokens.size >= 2) tokens[1] else ""
                val suspend = if (tokens.size >= 3) tokens[2].equals("True", ignoreCase = true) else false
                val active = if (tokens.size >= 4) tokens[3].toIntOrNull() ?: 0 else 0
                val lastSchedule = if (tokens.size >= 5) tokens[4] else "<none>"
                val age = if (tokens.size >= 6) tokens[5] else ""

                list.add(
                    CronJobInfo(
                        name = name,
                        namespace = defaultNamespace,
                        schedule = schedule,
                        suspend = suspend,
                        active = active,
                        lastSchedule = lastSchedule,
                        age = age
                    )
                )
            }
        }
        return list
    }

    // MARK: - Events
    fun parseEventsJson(jsonStr: String, defaultNamespace: String = ""): List<EventInfo> {
        val list = mutableListOf<EventInfo>()
        if (jsonStr.isBlank()) return list

        val trimmed = jsonStr.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return parseEventsTable(jsonStr, defaultNamespace)
        }

        try {
            val root = JsonParser.parseString(jsonStr).asSafeJsonObject() ?: return list
            val items = root.getSafeArray("items") ?: run {
                if (root.getSafeString("kind") == "Event") {
                    val array = JsonArray()
                    array.add(root)
                    array
                } else return list
            }
            val now = Instant.now()
            for (element in items) {
                val item = element.asSafeJsonObject() ?: continue
                val metadata = item.getSafeObject("metadata") ?: JsonObject()

                val name = metadata.getSafeString("name") ?: ""
                val namespace = metadata.getSafeString("namespace") ?: defaultNamespace

                val type = item.getSafeString("type")?.ifBlank { "Normal" } ?: "Normal"
                val reason = item.getSafeString("reason") ?: ""
                val message = item.getSafeString("message")
                    ?: item.getSafeString("note")
                    ?: ""

                val involvedObj = item.getSafeObject("involvedObject") ?: item.getSafeObject("regarding")
                val invKind = involvedObj?.getSafeString("kind") ?: ""
                val invName = involvedObj?.getSafeString("name") ?: ""
                val involvedObject = if (invKind.isNotBlank() && invName.isNotBlank()) {
                    "$invKind/$invName"
                } else {
                    invName.ifBlank { invKind }
                }

                val sourceObj = item.getSafeObject("source")
                val sourceComp = sourceObj?.getSafeString("component")
                    ?: item.getSafeString("reportingComponent")
                    ?: item.getSafeString("reportingController")
                    ?: ""

                val count = item.getSafeInt("count")
                    ?: item.getSafeObject("series")?.getSafeInt("count")
                    ?: 1

                val lastTimestamp = item.getSafeString("lastTimestamp")
                    ?: item.getSafeObject("series")?.getSafeString("lastObservedTime")
                    ?: item.getSafeString("eventTime")
                    ?: item.getSafeString("firstTimestamp")
                    ?: metadata.getSafeString("creationTimestamp")
                val age = formatAge(lastTimestamp, now)

                list.add(
                    EventInfo(
                        name = name.ifBlank { involvedObject.ifBlank { "event-${list.size + 1}" } },
                        namespace = namespace,
                        type = type,
                        reason = reason,
                        involvedObject = involvedObject,
                        message = message,
                        source = sourceComp,
                        count = count,
                        age = age,
                        lastTimestamp = lastTimestamp ?: ""
                    )
                )
            }
        } catch (e: Exception) {
            val t = jsonStr.trim()
            if (!t.startsWith("{") && !t.startsWith("[")) {
                return parseEventsTable(jsonStr, defaultNamespace)
            }
            return emptyList()
        }
        return list
    }

    fun parseEventsTable(tableStr: String, defaultNamespace: String = ""): List<EventInfo> {
        val list = mutableListOf<EventInfo>()
        if (tableStr.isBlank()) return list

        val trimmed = tableStr.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return emptyList()
        }

        val lines = tableStr.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var hasNamespaceCol = false
        for (line in lines) {
            if (line.startsWith("LAST SEEN", ignoreCase = true) ||
                line.startsWith("FIRST SEEN", ignoreCase = true) ||
                line.startsWith("TYPE", ignoreCase = true) ||
                line.startsWith("NAMESPACE", ignoreCase = true)) {
                if (line.startsWith("NAMESPACE", ignoreCase = true)) {
                    hasNamespaceCol = true
                }
                continue
            }
            val tokens = line.split("\\s+".toRegex())
            if (tokens.isEmpty()) continue

            val ns: String
            val age: String
            val type: String
            val reason: String
            val objectName: String
            val message: String

            if (hasNamespaceCol && tokens.size >= 5) {
                ns = tokens[0]
                age = tokens[1]
                type = tokens[2]
                reason = tokens[3]
                objectName = tokens[4]
                message = if (tokens.size >= 6) tokens.subList(5, tokens.size).joinToString(" ") else ""
            } else {
                ns = defaultNamespace
                age = tokens[0]
                type = if (tokens.size >= 2) tokens[1] else "Normal"
                reason = if (tokens.size >= 3) tokens[2] else ""
                objectName = if (tokens.size >= 4) tokens[3] else ""
                message = if (tokens.size >= 5) tokens.subList(4, tokens.size).joinToString(" ") else ""
            }

            list.add(
                EventInfo(
                    name = objectName.ifBlank { "event-${list.size + 1}" },
                    namespace = ns.ifBlank { defaultNamespace },
                    type = type,
                    reason = reason,
                    involvedObject = objectName,
                    message = message,
                    source = "",
                    count = 1,
                    age = age
                )
            )
        }
        return list
    }

    // MARK: - StatefulSets
    fun parseStatefulSetsJson(jsonStr: String, defaultNamespace: String = ""): List<StatefulSetInfo> {
        val list = mutableListOf<StatefulSetInfo>()
        if (jsonStr.isBlank()) return list

        try {
            val root = JsonParser.parseString(jsonStr).asJsonObject
            val items = root.getAsJsonArray("items") ?: run {
                if (root.has("kind") && root.get("kind")?.asString == "StatefulSet") {
                    val array = JsonArray()
                    array.add(root)
                    array
                } else return list
            }
            val now = Instant.now()
            for (element in items) {
                val item = element.asJsonObject
                val metadata = item.getAsJsonObject("metadata") ?: JsonObject()
                val spec = item.getAsJsonObject("spec") ?: JsonObject()
                val status = item.getAsJsonObject("status") ?: JsonObject()

                val name = metadata.get("name")?.asString ?: continue
                val namespace = metadata.get("namespace")?.asString ?: defaultNamespace

                val creationTimestamp = metadata.get("creationTimestamp")?.asString
                val age = formatAge(creationTimestamp, now)

                val replicas = spec.get("replicas")?.asInt ?: 1
                val readyReplicas = status.get("readyReplicas")?.asInt ?: 0

                val templateSpec = spec.getAsJsonObject("template")?.getAsJsonObject("spec")
                val containersArray = templateSpec?.getAsJsonArray("containers") ?: JsonArray()
                val containers = mutableListOf<String>()
                val images = mutableListOf<String>()
                for (cElem in containersArray) {
                    val cObj = cElem.asJsonObject
                    cObj.get("name")?.asString?.let { containers.add(it) }
                    cObj.get("image")?.asString?.let { images.add(it) }
                }

                list.add(
                    StatefulSetInfo(
                        name = name,
                        namespace = namespace,
                        readyReplicas = readyReplicas,
                        replicas = replicas,
                        age = age,
                        containers = containers,
                        images = images
                    )
                )
            }
        } catch (e: Exception) {
            val t = jsonStr.trim()
            if (!t.startsWith("{") && !t.startsWith("[")) {
                return parseStatefulSetsTable(jsonStr, defaultNamespace)
            }
            return emptyList()
        }
        return list
    }

    fun parseStatefulSetsTable(tableStr: String, defaultNamespace: String = ""): List<StatefulSetInfo> {
        val list = mutableListOf<StatefulSetInfo>()
        if (tableStr.isBlank()) return list

        val trimmed = tableStr.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return emptyList()

        val lines = tableStr.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            if (line.startsWith("NAME", ignoreCase = true)) continue
            val tokens = line.split("\\s+".toRegex())
            if (tokens.isEmpty()) continue

            // NAME   READY   AGE   CONTAINERS(opt)   IMAGES(opt)
            val name = tokens[0]
            val readyStr = if (tokens.size >= 2) tokens[1] else "0/1"
            val parts = readyStr.split("/")
            val readyReplicas = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val replicas = parts.getOrNull(1)?.toIntOrNull() ?: 1
            val age = if (tokens.size >= 3) tokens[2] else ""
            val containers = if (tokens.size >= 4) tokens[3].split(",").map { it.trim() } else emptyList()
            val images = if (tokens.size >= 5) tokens[4].split(",").map { it.trim() } else emptyList()

            list.add(
                StatefulSetInfo(
                    name = name,
                    namespace = defaultNamespace,
                    readyReplicas = readyReplicas,
                    replicas = replicas,
                    age = age,
                    containers = containers,
                    images = images
                )
            )
        }
        return list
    }

    // MARK: - DaemonSets
    fun parseDaemonSetsJson(jsonStr: String, defaultNamespace: String = ""): List<DaemonSetInfo> {
        val list = mutableListOf<DaemonSetInfo>()
        if (jsonStr.isBlank()) return list

        try {
            val root = JsonParser.parseString(jsonStr).asJsonObject
            val items = root.getAsJsonArray("items") ?: run {
                if (root.has("kind") && root.get("kind")?.asString == "DaemonSet") {
                    val array = JsonArray()
                    array.add(root)
                    array
                } else return list
            }
            val now = Instant.now()
            for (element in items) {
                val item = element.asJsonObject
                val metadata = item.getAsJsonObject("metadata") ?: JsonObject()
                val spec = item.getAsJsonObject("spec") ?: JsonObject()
                val status = item.getAsJsonObject("status") ?: JsonObject()

                val name = metadata.get("name")?.asString ?: continue
                val namespace = metadata.get("namespace")?.asString ?: defaultNamespace

                val creationTimestamp = metadata.get("creationTimestamp")?.asString
                val age = formatAge(creationTimestamp, now)

                val desired = status.get("desiredNumberScheduled")?.asInt ?: 0
                val current = status.get("currentNumberScheduled")?.asInt ?: 0
                val ready = status.get("numberReady")?.asInt ?: 0
                val upToDate = status.get("updatedNumberScheduled")?.asInt ?: 0
                val available = status.get("numberAvailable")?.asInt ?: 0

                val templateSpec = spec.getAsJsonObject("template")?.getAsJsonObject("spec")
                val nodeSelectorObj = templateSpec?.getAsJsonObject("nodeSelector")
                val nodeSelector = if (nodeSelectorObj != null && nodeSelectorObj.keySet().isNotEmpty()) {
                    nodeSelectorObj.keySet().joinToString(",") { "$it=${nodeSelectorObj.get(it).asString}" }
                } else "<none>"

                val containersArray = templateSpec?.getAsJsonArray("containers") ?: JsonArray()
                val containers = mutableListOf<String>()
                val images = mutableListOf<String>()
                for (cElem in containersArray) {
                    val cObj = cElem.asJsonObject
                    cObj.get("name")?.asString?.let { containers.add(it) }
                    cObj.get("image")?.asString?.let { images.add(it) }
                }

                list.add(
                    DaemonSetInfo(
                        name = name,
                        namespace = namespace,
                        desired = desired,
                        current = current,
                        ready = ready,
                        upToDate = upToDate,
                        available = available,
                        nodeSelector = nodeSelector,
                        age = age,
                        containers = containers,
                        images = images
                    )
                )
            }
        } catch (e: Exception) {
            val t = jsonStr.trim()
            if (!t.startsWith("{") && !t.startsWith("[")) {
                return parseDaemonSetsTable(jsonStr, defaultNamespace)
            }
            return emptyList()
        }
        return list
    }

    fun parseDaemonSetsTable(tableStr: String, defaultNamespace: String = ""): List<DaemonSetInfo> {
        val list = mutableListOf<DaemonSetInfo>()
        if (tableStr.isBlank()) return list

        val trimmed = tableStr.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return emptyList()

        val lines = tableStr.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            if (line.startsWith("NAME", ignoreCase = true)) continue
            val tokens = line.split("\\s+".toRegex())
            if (tokens.isEmpty()) continue

            // NAME   DESIRED   CURRENT   READY   UP-TO-DATE   AVAILABLE   NODE SELECTOR   AGE
            val name = tokens[0]
            val desired = if (tokens.size >= 2) tokens[1].toIntOrNull() ?: 0 else 0
            val current = if (tokens.size >= 3) tokens[2].toIntOrNull() ?: 0 else 0
            val ready = if (tokens.size >= 4) tokens[3].toIntOrNull() ?: 0 else 0
            val upToDate = if (tokens.size >= 5) tokens[4].toIntOrNull() ?: 0 else 0
            val available = if (tokens.size >= 6) tokens[5].toIntOrNull() ?: 0 else 0
            val nodeSelector = if (tokens.size >= 7) tokens[6] else "<none>"
            val age = if (tokens.size >= 8) tokens[7] else ""

            list.add(
                DaemonSetInfo(
                    name = name,
                    namespace = defaultNamespace,
                    desired = desired,
                    current = current,
                    ready = ready,
                    upToDate = upToDate,
                    available = available,
                    nodeSelector = nodeSelector,
                    age = age
                )
            )
        }
        return list
    }

    // MARK: - PersistentVolumeClaims
    fun parsePersistentVolumeClaimsJson(jsonStr: String, defaultNamespace: String = ""): List<PersistentVolumeClaimInfo> {
        val list = mutableListOf<PersistentVolumeClaimInfo>()
        if (jsonStr.isBlank()) return list

        try {
            val root = JsonParser.parseString(jsonStr).asJsonObject
            val items = root.getAsJsonArray("items") ?: run {
                if (root.has("kind") && root.get("kind")?.asString == "PersistentVolumeClaim") {
                    val array = JsonArray()
                    array.add(root)
                    array
                } else return list
            }
            val now = Instant.now()
            for (element in items) {
                val item = element.asJsonObject
                val metadata = item.getAsJsonObject("metadata") ?: JsonObject()
                val spec = item.getAsJsonObject("spec") ?: JsonObject()
                val status = item.getAsJsonObject("status") ?: JsonObject()

                val name = metadata.get("name")?.asString ?: continue
                val namespace = metadata.get("namespace")?.asString ?: defaultNamespace

                val creationTimestamp = metadata.get("creationTimestamp")?.asString
                val age = formatAge(creationTimestamp, now)

                val phase = status.get("phase")?.asString ?: "Pending"
                val volume = spec.get("volumeName")?.asString ?: ""

                val capacity = status.getAsJsonObject("capacity")?.get("storage")?.asString
                    ?: spec.getAsJsonObject("resources")?.getAsJsonObject("requests")?.get("storage")?.asString
                    ?: ""

                val accessModesArray = spec.getAsJsonArray("accessModes") ?: status.getAsJsonArray("accessModes") ?: JsonArray()
                val accessModesList = mutableListOf<String>()
                for (aElem in accessModesArray) {
                    val mode = aElem.asString
                    val shortMode = when (mode) {
                        "ReadWriteOnce" -> "RWO"
                        "ReadOnlyMany" -> "ROX"
                        "ReadWriteMany" -> "RWX"
                        "ReadWriteOncePod" -> "RWOP"
                        else -> mode
                    }
                    accessModesList.add(shortMode)
                }
                val accessModes = if (accessModesList.isEmpty()) "" else accessModesList.joinToString(",")

                val storageClass = spec.get("storageClassName")?.asString
                    ?: metadata.getAsJsonObject("annotations")?.get("volume.beta.kubernetes.io/storage-class")?.asString
                    ?: ""

                val volumeMode = spec.get("volumeMode")?.asString ?: "Filesystem"

                list.add(
                    PersistentVolumeClaimInfo(
                        name = name,
                        namespace = namespace,
                        status = phase,
                        volume = volume,
                        capacity = capacity,
                        accessModes = accessModes,
                        storageClass = storageClass,
                        age = age,
                        volumeMode = volumeMode
                    )
                )
            }
        } catch (e: Exception) {
            val t = jsonStr.trim()
            if (!t.startsWith("{") && !t.startsWith("[")) {
                return parsePersistentVolumeClaimsTable(jsonStr, defaultNamespace)
            }
            return emptyList()
        }
        return list
    }

    fun parsePersistentVolumeClaimsTable(tableStr: String, defaultNamespace: String = ""): List<PersistentVolumeClaimInfo> {
        val list = mutableListOf<PersistentVolumeClaimInfo>()
        if (tableStr.isBlank()) return list

        val trimmed = tableStr.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return emptyList()

        val lines = tableStr.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            if (line.startsWith("NAME", ignoreCase = true)) continue
            val tokens = line.split("\\s+".toRegex())
            if (tokens.isEmpty()) continue

            // NAME   STATUS   VOLUME   CAPACITY   ACCESS MODES   STORAGECLASS   AGE
            val name = tokens[0]
            val status = if (tokens.size >= 2) tokens[1] else "Pending"
            val volume = if (tokens.size >= 3) tokens[2] else ""
            val capacity = if (tokens.size >= 4) tokens[3] else ""
            val accessModes = if (tokens.size >= 5) tokens[4] else ""
            val storageClass = if (tokens.size >= 6) tokens[5] else ""
            val age = if (tokens.size >= 7) tokens[6] else ""

            list.add(
                PersistentVolumeClaimInfo(
                    name = name,
                    namespace = defaultNamespace,
                    status = status,
                    volume = volume,
                    capacity = capacity,
                    accessModes = accessModes,
                    storageClass = storageClass,
                    age = age
                )
            )
        }
        return list
    }
}
