package com.kubernetes.terminal

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.kubernetes.model.AuthType
import com.kubernetes.model.PodInfo
import com.kubernetes.model.SshProfile
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalView

/**
 * Utility responsible for launching pod terminal sessions in IntelliJ's built-in
 * Terminal tool window to maximize developer throughput and minimize context switching.
 */
object TerminalLauncher {

    /**
     * Constructs a safe, ready-to-execute SSH command string that logs into the remote host
     * and immediately invokes interactive `kubectl exec` into the target pod container.
     */
    fun buildSshCommand(
        profile: SshProfile,
        namespace: String,
        podName: String,
        container: String? = null,
        shell: String = "/bin/sh"
    ): String {
        val kubectlPath = profile.kubectlPath.ifBlank { "kubectl" }
        val containerFlag = if (!container.isNullOrBlank()) "-c $container" else ""
        val targetNs = if (namespace.isBlank()) profile.defaultNamespace.ifBlank { "default" } else namespace
        val targetShell = shell.ifBlank { "/bin/sh" }
        val remoteExec = "$kubectlPath exec -it -n $targetNs $podName $containerFlag -- $targetShell".trim().replace("  ", " ")

        val portFlag = if (profile.port != 22 && profile.port > 0) "-p ${profile.port}" else ""
        val keyFlag = if (profile.authType == AuthType.PRIVATE_KEY && profile.privateKeyPath.isNotBlank()) {
            "-i \"${profile.privateKeyPath.replace("\\", "/")}\""
        } else ""

        val userHost = if (profile.username.isNotBlank()) "${profile.username}@${profile.host}" else profile.host
        val extraFlags = listOf(portFlag, keyFlag, "-t").filter { it.isNotBlank() }.joinToString(" ")

        return if (extraFlags.isNotBlank()) {
            "ssh $extraFlags $userHost \"$remoteExec\""
        } else {
            "ssh $userHost \"$remoteExec\""
        }.trim().replace("  ", " ")
    }

    /**
     * Checks if the built-in IntelliJ Terminal plugin is present and active.
     */
    fun isTerminalPluginAvailable(): Boolean {
        return try {
            Class.forName("org.jetbrains.plugins.terminal.TerminalView")
            true
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Opens a new terminal tab in IntelliJ's built-in Terminal tool window and executes the SSH kubectl exec command.
     * Returns true if successfully spawned, or false if the Terminal plugin is unavailable or failed.
     */
    @Suppress("DEPRECATION")
    fun openInBuiltinTerminal(
        project: Project,
        profile: SshProfile,
        pod: PodInfo,
        container: String? = null,
        shell: String = "/bin/sh"
    ): Boolean {
        return try {
            val terminalView = TerminalView.getInstance(project)
            val tabName = if (!container.isNullOrBlank()) "Pod: ${pod.name} [$container]" else "Pod: ${pod.name}"
            val widget = terminalView.createLocalShellWidget(project.basePath, tabName)

            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
            toolWindow?.show()

            val command = buildSshCommand(profile, pod.namespace, pod.name, container, shell)
            widget.executeCommand(command)
            true
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Main entry point for pod terminal access. Spawns interactive pod session directly in
     * IntelliJ's built-in Terminal.
     */
    fun openPodTerminal(
        project: Project,
        profile: SshProfile,
        pod: PodInfo,
        container: String? = null,
        shell: String = "/bin/sh"
    ): Boolean {
        return openInBuiltinTerminal(project, profile, pod, container, shell)
    }
}
