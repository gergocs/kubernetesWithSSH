package com.kubernetes.model

enum class AuthType {
    PASSWORD,
    PRIVATE_KEY
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
