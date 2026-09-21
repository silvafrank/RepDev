package org.repdev.engine.ssh

import org.apache.sshd.client.SshClient
import org.apache.sshd.client.auth.keyboard.UserInteraction
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier
import org.apache.sshd.client.keyverifier.ModifiedServerKeyAcceptor
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.nio.file.Path
import java.security.PublicKey
import java.util.concurrent.TimeUnit

/** Thrown when the host key on file doesn't match what the server just presented — a possible MITM, not a prompt. */
class SshHostKeyChangedException(message: String) : IOException(message)

/**
 * Symitar's AIX sshd only offers keyboard-interactive auth (plain "password" auth
 * is rejected outright, in a single round, per the original DirectSymitarSession's
 * connect() comment) and prompts "user name" then "Password" as separate rounds.
 * Ported from SymitarUserInfo's round-tracking logic onto MINA SSHD's callback shape.
 */
private class SymitarKeyboardInteractive(
    private val username: String,
    private val password: String,
) : UserInteraction {
    private var usernameSent = false

    override fun isInteractionAllowed(session: ClientSession) = true

    override fun interactive(
        session: ClientSession,
        name: String?,
        instruction: String?,
        lang: String?,
        prompt: Array<String>,
        echo: BooleanArray,
    ): Array<String> = Array(prompt.size) {
        if (!usernameSent) {
            usernameSent = true
            username
        } else {
            password
        }
    }

    override fun getUpdatedPassword(session: ClientSession, prompt: String?, lang: String?): String = password
}

/**
 * SSH transport for the Symitar/AIX host connection. Replaces jsch — see
 * newdev/DECISIONS.md for why Apache MINA SSHD was picked over the actively
 * maintained mwiede/jsch fork (the other realistic option).
 *
 * Behavior change from the code this replaces, intentional: the original set
 * `StrictHostKeyChecking=no`, so a changed host key was silently accepted even
 * though known_hosts was loaded — defeating the point of loading it. Here, a
 * changed key throws [SshHostKeyChangedException]; an *unknown* host goes
 * through [trustUnknownHost] so the caller can prompt (or, same as before,
 * simply return true to trust-on-first-use).
 */
class SshTransport private constructor(
    private val client: SshClient,
    private val session: ClientSession,
    private val channel: ChannelShell,
) : AutoCloseable {

    /** Reads the shell's output (what a human would see on screen). */
    val input: BufferedReader = BufferedReader(InputStreamReader(channel.invertedOut))

    /** Sends keystrokes/commands to the shell. */
    val output: PrintWriter = PrintWriter(channel.invertedIn, /* autoFlush = */ true)

    override fun close() {
        runCatching { channel.close(false) }
        runCatching { session.close(false) }
        runCatching { client.stop() }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000L

        fun connect(
            host: String,
            port: Int,
            username: String,
            password: String,
            knownHostsFile: Path,
            trustUnknownHost: (PublicKey) -> Boolean = { false },
        ): SshTransport {
            val client = SshClient.setUpDefaultClient()
            client.serverKeyVerifier = knownHostsVerifier(knownHostsFile, trustUnknownHost)
            client.start()

            var session: ClientSession? = null
            var channel: ChannelShell? = null
            try {
                session = client.connect(username, host, port)
                    .verify(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .session

                session.setUserInteraction(SymitarKeyboardInteractive(username, password))
                session.addPasswordIdentity(password)
                session.auth().verify(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

                channel = session.createShellChannel()
                channel.ptyType = "aixterm" // matches the original's ChannelShell#setPtyType("aixterm")
                channel.open().verify(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

                return SshTransport(client, session, channel)
            } catch (e: Exception) {
                runCatching { channel?.close(true) }
                runCatching { session?.close(true) }
                runCatching { client.stop() }
                throw e
            }
        }

        private fun knownHostsVerifier(
            knownHostsFile: Path,
            trustUnknownHost: (PublicKey) -> Boolean,
        ): ServerKeyVerifier {
            val unknownHostDelegate = ServerKeyVerifier { _, _, serverKey -> trustUnknownHost(serverKey) }
            val verifier = KnownHostsServerKeyVerifier(unknownHostDelegate, knownHostsFile)
            // The library's own extension point for exactly this case, instead of subclassing:
            // a *changed* key (an entry exists but doesn't match) is always routed here rather
            // than to the delegate above, which only ever sees genuinely *unknown* hosts.
            verifier.modifiedServerKeyAcceptor = ModifiedServerKeyAcceptor { _, remoteAddress, _, _, _ ->
                throw SshHostKeyChangedException(
                    "Host key for $remoteAddress does not match the known_hosts entry recorded at $knownHostsFile. " +
                        "This could mean the server was legitimately rebuilt, or that traffic is being intercepted."
                )
            }
            return verifier
        }
    }
}
