package org.repdev.engine

import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.Timeout

/**
 * Covers DirectSymitarSession's wire-protocol methods (connect/loginUser) against a scripted
 * fake AIX host, same shape as SshTransportIntegrationTest but over the plain-socket Telnet path
 * (port != 22) since SshTransport's own auth handshake is already covered there — this test's job
 * is the WINDOWSLEVEL=3 screen-scraping protocol itself, not SSH.
 *
 * The fake host doesn't need to read anything the client sends: every readUntil/readNextCommand
 * call just blocks on bytes already sitting in the pipe, so the whole script can be written in one
 * shot right after accept() and the client's small writes (username/password/CRs) never fill the
 * socket buffer. A background drain thread reads and discards them anyway, cheap insurance.
 */
class DirectSymitarSessionIntegrationTest {
    private lateinit var server: ServerSocket

    @BeforeTest
    fun startServer() {
        server = ServerSocket(0)
    }

    @AfterTest
    fun stopServer() {
        server.close()
    }

    /** One WINDOWSLEVEL=3 command frame: ESC 0xfe <body> 0xfc, matching Command.parse's format. */
    private fun frame(body: String): String = 27.toChar().toString() + 254.toChar() + body + 252.toChar()

    // ponytail: server-side socket is never explicitly closed here (would race the client still
    // reading the tail of the script right after this thread's write() returns) — it's closed for
    // free when the client disconnects and drops its end, or when the JVM exits after the test.
    private fun serve(script: String) {
        Thread {
            val socket: Socket = server.accept()
            Thread { runCatching { val ins = socket.getInputStream(); while (ins.read() != -1) Unit } }.start()
            val out: OutputStream = socket.getOutputStream()
            // DirectSymitarSession reads via InputStreamReader with no charset (platform default) —
            // encode with that same default so char codes 27/254/252 round-trip exactly regardless
            // of what the default happens to be on this JVM, rather than assuming ISO-8859-1.
            out.write(script.toByteArray(java.nio.charset.Charset.defaultCharset()))
            out.flush()
        }.start()
    }

    @Test
    @Timeout(5)
    fun `connect and loginUser succeed against a scripted Telnet AIX host`() {
        val script = "Password:" + "[c" + "SymStart~Global" +
            frame("Input") +
            frame("Foo") +
            frame("Foo2") +
            frame("Foo3") +
            frame("Misc~BankingDate=09162026") +
            frame("Misc~ConsoleNumber=5")
        serve(script)

        val session = DirectSymitarSession()
        try {
            val result = session.connect("localhost", server.localPort, "aixuser", "aixpass", 42, "TELLER1")
            assertEquals(SessionError.NONE, result)
            assertEquals(true, session.isConnected())
            assertEquals(5, session.getConsoleNum())
            assertEquals(8, session.getSymDate().get(java.util.Calendar.MONTH)) // "09162026" -> September, 0-based = 8
        } finally {
            session.disconnect()
        }
    }

    @Test
    @Timeout(5)
    fun `connect rejects an invalid AIX password`() {
        val script = "Password:" + "invalid login name or password"
        serve(script)

        val session = DirectSymitarSession()
        val result = session.connect("localhost", server.localPort, "aixuser", "wrongpass", 42, "TELLER1")
        assertEquals(SessionError.AIX_LOGIN_WRONG, result)
        assertFalse(session.isConnected())
    }
}
