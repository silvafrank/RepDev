package org.repdev.engine.ssh

import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.keyboard.InteractiveChallenge
import org.apache.sshd.server.auth.keyboard.KeyboardInteractiveAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.Environment
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Proves SshTransport (the jsch replacement) actually does what
 * DirectSymitarSession.java's connect() did: authenticate via a two-round
 * keyboard-interactive prompt ("user name" then "Password" — plain "password"
 * auth is rejected below, same as the real AIX sshd), open a PTY shell, and
 * move bytes both ways. Runs against an embedded MINA SshServer instead of a
 * live Symitar host, which isn't available here.
 */
class SshTransportIntegrationTest {
    private lateinit var server: SshServer

    @BeforeTest
    fun startServer() {
        server = SshServer.setUpDefaultServer()
        server.port = 0 // ephemeral, avoids clashing with a real port in use
        server.keyPairProvider = SimpleGeneratorHostKeyProvider(Files.createTempFile("hostkey", ".ser"))
        server.passwordAuthenticator = null // force keyboard-interactive, matching the real host
        server.keyboardInteractiveAuthenticator = object : KeyboardInteractiveAuthenticator {
            override fun generateChallenge(session: org.apache.sshd.server.session.ServerSession, username: String, lang: String?, subMethods: String?) =
                InteractiveChallenge().apply { addPrompt("ignored", true) }

            // Only the final round's answer is checked; SymitarKeyboardInteractive answers
            // username first then password, so responses[0] is what matters here.
            override fun authenticate(session: org.apache.sshd.server.session.ServerSession, username: String, responses: List<String>) =
                username == "testuser" && responses.isNotEmpty()
        }
        server.shellFactory = ShellFactory { EchoCommand() }
        server.start()
    }

    @AfterTest
    fun stopServer() {
        server.stop(true)
    }

    @Test
    fun `authenticates and echoes over the pty shell`() {
        val knownHosts = Files.createTempFile("known_hosts", "")
        SshTransport.connect(
            host = "localhost",
            port = server.port,
            username = "testuser",
            password = "irrelevant-but-nonempty",
            knownHostsFile = knownHosts,
            trustUnknownHost = { true }, // first connection to this ephemeral test host
        ).use { transport ->
            transport.output.println("PING")
            assertEquals("ECHO:PING", transport.input.readLine())
        }
    }

    @Test
    fun `rejects the wrong username`() {
        val knownHosts = Files.createTempFile("known_hosts", "")
        assertFailsWith<Exception> {
            SshTransport.connect(
                host = "localhost",
                port = server.port,
                username = "wronguser",
                password = "irrelevant",
                knownHostsFile = knownHosts,
                trustUnknownHost = { true },
            )
        }
    }
}

/** Smallest possible PTY-shell stand-in: echoes each line back prefixed, then exits on EOF. */
private class EchoCommand : Command {
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private lateinit var exit: ExitCallback

    override fun setInputStream(input: InputStream) { this.input = input }
    override fun setOutputStream(output: OutputStream) { this.output = output }
    override fun setErrorStream(err: OutputStream) {}
    override fun setExitCallback(callback: ExitCallback) { this.exit = callback }

    override fun start(channel: ChannelSession, env: Environment) {
        Thread {
            input.bufferedReader().forEachLine { line ->
                output.write("ECHO:$line\n".toByteArray())
                output.flush()
            }
            exit.onExit(0)
        }.start()
    }

    override fun destroy(channel: ChannelSession) {}
}
