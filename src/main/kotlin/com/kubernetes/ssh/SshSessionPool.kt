package com.kubernetes.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.kubernetes.model.AuthType
import com.kubernetes.model.CommandResult
import com.kubernetes.model.SshProfile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe SSH Session Pool and multiplexer that caches and reuses active SSH connections per profile,
 * eliminating the expensive TCP and cryptographic authentication handshake on every command.
 */
object SshSessionPool {

    private data class PoolKey(
        val host: String,
        val port: Int,
        val username: String,
        val authType: AuthType,
        val keyPath: String,
        val passwordHash: Int
    )

    private class PooledSession(
        val session: Session,
        var lastUsedTime: Long = System.currentTimeMillis()
    ) {
        val lock = ReentrantLock()
    }

    private val pool = ConcurrentHashMap<PoolKey, PooledSession>()
    private val globalLock = ReentrantLock()

    private fun getPoolKey(profile: SshProfile): PoolKey {
        return PoolKey(
            host = profile.host.trim().lowercase(),
            port = if (profile.port > 0) profile.port else 22,
            username = profile.username.trim(),
            authType = profile.authType,
            keyPath = profile.privateKeyPath.trim(),
            passwordHash = profile.password.hashCode()
        )
    }

    /**
     * Executes a command using a cached/pooled session, automatically creating a session if needed,
     * or reconnecting if the cached session became disconnected.
     */
    fun execute(
        profile: SshProfile,
        command: String,
        inputBytes: ByteArray? = null
    ): CommandResult {
        if (profile.host.isBlank()) {
            throw IllegalStateException("SSH Host is not configured. Please open Plugin Settings.")
        }
        if (profile.username.isBlank()) {
            throw IllegalStateException("SSH Username is not configured. Please open Plugin Settings.")
        }

        val key = getPoolKey(profile)
        var pooled = pool[key]

        if (pooled == null || !pooled.session.isConnected) {
            globalLock.withLock {
                val existing = pool[key]
                if (existing == null || !existing.session.isConnected) {
                    try {
                        existing?.session?.disconnect()
                    } catch (_: Exception) {}

                    val newSession = createNewSession(profile)
                    newSession.connect(profile.connectionTimeoutSeconds * 1000)
                    val newPooled = PooledSession(newSession)
                    pool[key] = newPooled
                    pooled = newPooled
                } else {
                    pooled = existing
                }
            }
        }

        val currentPooled = pooled!!
        return currentPooled.lock.withLock {
            try {
                if (!currentPooled.session.isConnected) {
                    currentPooled.session.connect(profile.connectionTimeoutSeconds * 1000)
                }
                currentPooled.lastUsedTime = System.currentTimeMillis()
                runCommandOnSession(currentPooled.session, command, profile.connectionTimeoutSeconds, inputBytes)
            } catch (e: Exception) {
                // If failed due to broken connection, try reconnecting once
                try {
                    currentPooled.session.disconnect()
                } catch (_: Exception) {}

                try {
                    val freshSession = createNewSession(profile)
                    freshSession.connect(profile.connectionTimeoutSeconds * 1000)
                    val newPooled = PooledSession(freshSession)
                    pool[key] = newPooled
                    newPooled.lock.withLock {
                        runCommandOnSession(freshSession, command, profile.connectionTimeoutSeconds, inputBytes)
                    }
                } catch (retryEx: Exception) {
                    throw RuntimeException("SSH Command failed on ${profile.host}: ${e.message}", e)
                }
            }
        }
    }

    fun runCommandOnSession(
        session: Session,
        command: String,
        timeoutSeconds: Int,
        inputBytes: ByteArray? = null
    ): CommandResult {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)

        if (inputBytes != null) {
            channel.setInputStream(ByteArrayInputStream(inputBytes))
        }

        val stdoutStream = ByteArrayOutputStream()
        val stderrStream = ByteArrayOutputStream()

        val inputStream = channel.inputStream
        val errStream = channel.errStream

        channel.connect(timeoutSeconds * 1000)

        val buffer = ByteArray(8192)
        val maxWaitMillis = (timeoutSeconds * 1000).toLong().coerceAtLeast(15000L)
        val startTime = System.currentTimeMillis()

        while (true) {
            while (inputStream.available() > 0) {
                val len = inputStream.read(buffer, 0, buffer.size)
                if (len < 0) break
                stdoutStream.write(buffer, 0, len)
            }
            while (errStream.available() > 0) {
                val len = errStream.read(buffer, 0, buffer.size)
                if (len < 0) break
                stderrStream.write(buffer, 0, len)
            }
            if (channel.isClosed) {
                if (inputStream.available() > 0 || errStream.available() > 0) continue
                break
            }
            if (System.currentTimeMillis() - startTime > maxWaitMillis) {
                break
            }
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                break
            }
        }

        val exitStatus = channel.exitStatus
        channel.disconnect()

        val output = stdoutStream.toString(Charsets.UTF_8.name())
        val error = stderrStream.toString(Charsets.UTF_8.name())

        return CommandResult(exitStatus, output, error)
    }

    fun createNewSession(settings: SshProfile): Session {
        val jsch = JSch()

        if (settings.authType == AuthType.PRIVATE_KEY && settings.privateKeyPath.isNotBlank()) {
            val keyFile = File(settings.privateKeyPath)
            if (!keyFile.exists()) {
                throw IllegalArgumentException("Private key file does not exist: ${settings.privateKeyPath}")
            }
            if (settings.passphrase.isNotEmpty()) {
                jsch.addIdentity(keyFile.absolutePath, settings.passphrase)
            } else {
                jsch.addIdentity(keyFile.absolutePath)
            }
        }

        val port = if (settings.port > 0) settings.port else 22
        val session = jsch.getSession(settings.username.trim(), settings.host.trim(), port)

        if (settings.authType == AuthType.PASSWORD && settings.password.isNotEmpty()) {
            session.setPassword(settings.password)
        }

        val config = Properties()
        config["StrictHostKeyChecking"] = "no"
        config["PreferredAuthentications"] = if (settings.authType == AuthType.PASSWORD) {
            "password,keyboard-interactive,publickey"
        } else {
            "publickey,keyboard-interactive,password"
        }
        session.setConfig(config)
        session.timeout = settings.connectionTimeoutSeconds * 1000

        return session
    }

    fun invalidateProfile(profile: SshProfile) {
        val key = getPoolKey(profile)
        pool.remove(key)?.let { pooled ->
            try {
                pooled.session.disconnect()
            } catch (_: Exception) {}
        }
    }

    fun closeAll() {
        pool.values.forEach { pooled ->
            try {
                pooled.session.disconnect()
            } catch (_: Exception) {}
        }
        pool.clear()
    }
}
