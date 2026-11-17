package com.kubernetes.ssh

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kubernetes.model.DeploymentInfo
import com.kubernetes.model.DockerImageInfo
import com.kubernetes.model.PodInfo
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

object KubernetesParser {

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
            return parseDeploymentsTable(jsonStr, defaultNamespace)
        }
        return list
    }

    fun parseDeploymentsTable(tableStr: String, defaultNamespace: String = ""): List<DeploymentInfo> {
        val list = mutableListOf<DeploymentInfo>()
        if (tableStr.isBlank()) return list

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
}
