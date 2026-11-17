package com.kubernetes.terminal

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import com.kubernetes.model.SshProfile
import com.kubernetes.ssh.KubernetesSshClient
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class PodTerminalSession(
    private val profile: SshProfile,
    val namespace: String,
    val podName: String,
    val container: String? = null,
    val shell: String = "/bin/sh",
    private val onOutput: (String) -> Unit,
    private val onStatusChange: (String) -> Unit,
    private val onClosed: (Int) -> Unit
) {
    private var session: Session? = null
    private var channel: ChannelExec? = null
    private var outputStream: OutputStream? = null
    private val isRunning = AtomicBoolean(false)
    private var readerThread: Thread? = null

    fun start(cols: Int = 120, rows: Int = 30) {
        if (isRunning.get()) return

        Thread {
            try {
                onStatusChange("Connecting to SSH server (${profile.host}:${profile.port})...")
                val client = KubernetesSshClient(profile)
                val newSession = client.createSession()
                session = newSession
                newSession.connect(profile.connectionTimeoutSeconds * 1000)

                val containerMsg = if (!container.isNullOrBlank()) " [container: $container]" else ""
                onStatusChange("Connecting to pod '$podName'$containerMsg with shell '$shell'...")

                val containerFlag = if (!container.isNullOrBlank()) " -c $container" else ""
                val targetNs = if (namespace.isBlank()) profile.defaultNamespace.ifBlank { "default" } else namespace
                val command = "${profile.kubectlPath} exec -i -t $podName -n $targetNs$containerFlag -- $shell"

                val execChannel = newSession.openChannel("exec") as ChannelExec
                channel = execChannel
                execChannel.setCommand(command)
                execChannel.setPty(true)
                execChannel.setPtyType("xterm-256color", cols, rows, 0, 0)

                outputStream = execChannel.outputStream
                val inStream: InputStream = execChannel.inputStream
                val errStream: InputStream = execChannel.errStream

                execChannel.connect(profile.connectionTimeoutSeconds * 1000)
                isRunning.set(true)
                onStatusChange("Connected")

                readerThread = Thread {
                    val buffer = ByteArray(4096)
                    try {
                        while (isRunning.get() && !execChannel.isClosed) {
                            var readAny = false
                            if (inStream.available() > 0) {
                                val len = inStream.read(buffer)
                                if (len > 0) {
                                    val text = String(buffer, 0, len, StandardCharsets.UTF_8)
                                    onOutput(text)
                                    readAny = true
                                }
                            }
                            if (errStream.available() > 0) {
                                val len = errStream.read(buffer)
                                if (len > 0) {
                                    val text = String(buffer, 0, len, StandardCharsets.UTF_8)
                                    onOutput(text)
                                    readAny = true
                                }
                            }

                            if (!readAny) {
                                Thread.sleep(20)
                            }
                        }

                        // Read any remaining output after channel is marked closed
                        while (inStream.available() > 0) {
                            val len = inStream.read(buffer)
                            if (len > 0) {
                                val text = String(buffer, 0, len, StandardCharsets.UTF_8)
                                onOutput(text)
                            }
                        }
                        while (errStream.available() > 0) {
                            val len = errStream.read(buffer)
                            if (len > 0) {
                                val text = String(buffer, 0, len, StandardCharsets.UTF_8)
                                onOutput(text)
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            onOutput("\n[Terminal connection error: ${e.message}]\n")
                        }
                    } finally {
                        val exitCode = execChannel.exitStatus
                        isRunning.set(false)
                        onStatusChange("Disconnected (exit code: $exitCode)")
                        onClosed(exitCode)
                    }
                }.apply {
                    isDaemon = true
                    name = "PodTerminal-Reader-$podName"
                    start()
                }

            } catch (e: Exception) {
                isRunning.set(false)
                val errMsg = e.message ?: "Unknown error"
                onStatusChange("Failed to connect: $errMsg")
                onOutput("\n[Failed to start pod terminal: $errMsg]\n")
                onClosed(-1)
            }
        }.apply {
            isDaemon = true
            name = "PodTerminal-Starter-$podName"
            start()
        }
    }

    fun sendInput(text: String) {
        if (!isRunning.get()) return
        try {
            outputStream?.let { os ->
                os.write(text.toByteArray(StandardCharsets.UTF_8))
                os.flush()
            }
        } catch (_: Exception) {}
    }

    fun sendCtrlC() {
        sendInput("\u0003")
    }

    fun sendCtrlD() {
        sendInput("\u0004")
    }

    fun sendCtrlZ() {
        sendInput("\u001a")
    }

    fun sendCtrlL() {
        sendInput("\u000c")
    }

    fun resize(cols: Int, rows: Int) {
        try {
            channel?.setPtySize(cols, rows, 0, 0)
        } catch (_: Exception) {}
    }

    fun close() {
        isRunning.set(false)
        try {
            channel?.disconnect()
        } catch (_: Exception) {}
        try {
            session?.disconnect()
        } catch (_: Exception) {}
        channel = null
        session = null
    }

    val isSessionRunning: Boolean get() = isRunning.get()
}
