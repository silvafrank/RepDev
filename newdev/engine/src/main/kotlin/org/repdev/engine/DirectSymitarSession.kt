package org.repdev.engine

import org.repdev.engine.ssh.SshHostKeyChangedException
import org.repdev.engine.ssh.SshTransport
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.nio.file.Paths
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.GregorianCalendar
import java.util.regex.Pattern

/**
 * Ported from com.repdev.DirectSymitarSession — the screen-scraping session
 * that talks to the AIX host over a raw terminal (SSH shell or Telnet) using
 * Symitar's `WINDOWSLEVEL=3` binary command protocol (see the private
 * [Command] class). This is the single riskiest file in the whole app to
 * port: no spec, no tests upstream, years of AIX-quirk handling encoded as
 * literal byte sequences and string markers. The wire protocol below is
 * translated as directly as possible — same markers, same field names, same
 * branch order — not redesigned. See DECISIONS.md for what *did* change.
 *
 * What changed from the original, and why:
 * - jsch -> [SshTransport] (Apache MINA SSHD). Same "port 22 means SSH"
 *   branch, same keyboard-interactive username/password sequence (now
 *   handled inside `SshTransport.connect`), but a changed host key is now
 *   rejected instead of silently trusted (`StrictHostKeyChecking=no` in the
 *   original was a real MITM hole — see DECISIONS.md §1).
 * - Telnet is kept, not dropped: MODERNIZATION_PLAN.md's security list says
 *   "if kept at all, opt-in and loudly flagged", not "delete". Some consoles
 *   genuinely have no SSH. It's the same unauthenticated-transport risk it
 *   always was, so every Telnet connection now logs a loud plaintext warning
 *   (the original logged nothing).
 * - The SWT `ProgressBar`/`Text` params on `runRepGen` and the `MessageBox`
 *   popped when keepAlive terminates are both replaced with plain listeners
 *   ([SymitarSession.RepgenProgressListener], [keepAliveTerminatedListener]).
 *   The engine module has no UI dependency at all now.
 * - `readUntil`'s read loop used to spin forever re-appending `(char) -1`
 *   once the stream hit EOF (a real hang, not a preserved quirk) — now
 *   throws on end-of-stream.
 * - The static `lastActivity` clock is intentionally still shared across
 *   every session instance via [Companion] — that's not an incidental
 *   singleton, it's the original's actual intent (activity on any one SYM
 *   console keeps *all* of them alive), preserved on purpose.
 *
 * A few numeric literals in the original source (`CONNECT_TIMEOUT_MS`, the
 * keepalive poll interval, queue-array sizing, the FM random-title bound,
 * the length-field zero-padding width) render as partially redacted in every
 * tool this port was done through. Where a nearby comment pins the real
 * value (e.g. "every 55 seconds") that value is used; the rest are
 * best-effort reconstructions of a magic number that was never
 * behavior-critical to begin with (buffer/array sizing, cosmetic padding).
 */
class DirectSymitarSession : SymitarSession() {
    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val KEEP_ALIVE_POLL_MS = 55_000L // "every 55 seconds" per the original's comment
        const val QUEUE_SLOTS = 10_000
        const val MAX_FILE_DOWNLOAD_SIZE = 2_097_152L // 2MB

        /**
         * Shared across *every* [DirectSymitarSession] instance on purpose (see class doc) —
         * this is what the external keep-alive watchdog uses to decide whether activity on
         * some other SYM console should also keep this one's session alive.
         */
        @Volatile
        private var lastActivity: Calendar = GregorianCalendar()

        fun setLastActivity() {
            lastActivity = GregorianCalendar()
        }

        fun getLastActivity(): Calendar = lastActivity
    }

    private var socket: Socket? = null
    private var sshTransport: SshTransport? = null
    private lateinit var input: BufferedReader
    private lateinit var output: PrintWriter

    private var connected = false
    private var loggedInAIX = false
    private var useSSH = false
    private var passWillExpire = false

    private var actualSym = -1
    private var consoleNum = -1
    private var hostIPA = ""
    private var symRev = ""
    private val symDate = GregorianCalendar()

    private var bFullTrace = false
    private var bSensitiveData = false
    private var trace: FileOutputStream? = null

    private var keepAlive: Thread? = null
    private var keepAliveEnabled = false
    private var keepAliveActive = false
    private var keepAliveNeverTerminate = false
    private var keepAliveHour = 19
    private var keepAliveMin = 0
    private val noActivityDelayMinutes = 20
    private val keepAliveWarningMinutes = 30

    /** Replaces the SWT MessageBox the original popped when keepAlive gave up. */
    var keepAliveTerminatedListener: (() -> Unit)? = null

    private fun log(str: String): String {
        println(str)
        return str
    }

    private fun log(o: Any): String = log(o.toString())

    fun keepAliveEnabled() = keepAliveEnabled
    fun keepAliveActive() = keepAliveActive

    fun enableKeepAlive(neverTerminate: Boolean, terminateHour: Int, terminateMinute: Int) {
        keepAliveEnabled = true
        keepAliveNeverTerminate = neverTerminate
        keepAliveHour = terminateHour
        keepAliveMin = terminateMinute
    }

    fun getTerminateHour() = keepAliveHour
    fun getTerminateMinute() = keepAliveMin
    fun getNeverTerminate() = keepAliveNeverTerminate

    // ---------------------------------------------------------------------
    // Wire protocol
    // ---------------------------------------------------------------------

    /** One WINDOWSLEVEL=3 protocol message: `Command~Key=Value~Key2=Value2`. */
    private class Command(var command: String = "") {
        val parameters = LinkedHashMap<String, String>()
        var data: String = ""

        init {
            parameters["MsgId"] = (nextMessageId++).toString()
        }

        /** Any file bytes riding along in this message, delimited by chars 253/254. */
        fun getFileData(): String {
            val start = data.indexOf(253.toChar())
            val end = data.indexOf(254.toChar())
            return if (start != -1 && end != -1) data.substring(start + 1, end) else ""
        }

        fun sendStr(): String {
            var body = "$command~"
            for ((key, value) in parameters) {
                body += if (value.isEmpty()) "$key~" else "$key=$value~"
            }
            body = body.substring(0, body.length - 1)
            return 0x07.toChar() + body.length.toString() + "\r" + body
        }

        override fun toString(): String = data

        companion object {
            private val COMMAND_PATTERN: Pattern = Pattern.compile("(.*?)~.*")
            private var nextMessageId = QUEUE_SLOTS

            fun parse(raw: String): Command {
                val cmd = Command()
                cmd.data = raw

                if (raw.contains("~") && raw.indexOf(253.toChar()) == -1) {
                    val match = COMMAND_PATTERN.matcher(raw)
                    match.matches()
                    val commandName = match.group(1)
                    cmd.command = commandName
                    for (part in raw.substring(commandName.length + 1).split("~")) {
                        val eq = part.indexOf("=")
                        if (eq == -1) cmd.parameters[part] = "" else cmd.parameters[part.substring(0, eq)] = part.substring(eq + 1)
                    }
                } else {
                    cmd.command = raw
                }
                return cmd
            }
        }
    }

    private fun write(str: String) {
        traceLog(str)
        output.write(str)
        output.flush()
    }

    private fun write(cmd: Command) = write(cmd.sendStr())

    private fun writeLog(command: String, vararg waitFor: String): String {
        write(command)
        return log(readUntil(*waitFor))
    }

    /** Reads the wire until any of `strs` has appeared in the buffer, returning everything read so far. */
    private fun readUntil(vararg strs: String): String {
        // StringBuilder, not String += : the original reallocated and copied the whole buffer on
        // every single byte, which is O(n^2) over a big response (a full RepGen source, a large
        // report). Also fixed: the original's loop kept appending `(char) -1` forever once the
        // stream hit EOF instead of ever returning, a real hang - this now throws instead.
        val buf = StringBuilder()
        while (true) {
            val cur = input.read()
            if (cur == -1) throw IOException("End of stream while waiting for: ${strs.joinToString()}")
            if (bFullTrace) {
                trace?.write(cur)
                trace?.flush()
            }
            buf.append(cur.toChar())
            for (str in strs) if (buf.contains(str)) return buf.toString()
        }
    }

    private fun readNextCommand(): Command {
        val tmpData = readUntil(0x1b.toChar().toString() + 0xfe.toChar(), "No such file or directory")
        if (tmpData.contains("No such file or directory")) throw IOException("SYM not Found")

        val data = readUntil(0xfc.toChar().toString())
        val cmd = Command.parse(data.substring(0, data.length - 1))

        // Async "From PID" messages can arrive at any time and would otherwise derail whatever
        // command sequence is currently being read - skip past them transparently.
        return if (cmd.command == "MsgDlg" && cmd.parameters["Text"]?.contains("From PID") == true) readNextCommand() else cmd
    }

    /** The extremely common `while (!(cur = readNextCommand()).getCommand().equals("Input")) log(cur);` pattern. */
    private fun untilInput(onEach: (Command) -> Unit = { log(it) }): Command {
        var cur = readNextCommand()
        while (cur.command != "Input") {
            onEach(cur)
            cur = readNextCommand()
        }
        return cur
    }

    private fun traceLog(str: String) {
        if (!bFullTrace) return
        try {
            val t = trace ?: return
            t.write(10)
            if (!bSensitiveData) {
                t.write("~~>".toByteArray())
                t.write(str.toByteArray())
                t.write("<~~".toByteArray())
            } else {
                t.write("~~>XXXXXXXX<~~".toByteArray())
            }
            t.write(10)
            t.flush()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    @Synchronized
    private fun wakeUp() = write(Command("WakeUp"))

    // ---------------------------------------------------------------------
    // Connect / login / disconnect
    // ---------------------------------------------------------------------

    override fun connect(server: String, port: Int, aixUsername: String, aixPassword: String, sym: Int, userID: String): SessionError {
        val traceFile = File("fulltrace.$sym.txt")
        if (traceFile.exists()) {
            bFullTrace = true
            traceFile.delete()
            runCatching { traceFile.createNewFile() }.onFailure { it.printStackTrace() }
            runCatching {
                trace = FileOutputStream(traceFile)
                println("opening fulltrace file...")
                traceLog("Starting the trace for Host:$server Port:$port SYM:$sym AIX:$aixUsername")
                traceLog("   ***   MAKE SURE TO DELETE THIS FILE WHEN DONE, OR IT MAY SLOW DOWN YOUR CONNECTION ! ! !   ***\n\n")
            }.onFailure { it.printStackTrace() }
        }

        setLastActivity()
        if (connected) return SessionError.ALREADY_CONNECTED

        this.server = server
        this.port = port
        this.aixUsername = aixUsername
        this.aixPassword = aixPassword
        this.sym = sym

        useSSH = port == 22
        if (useSSH) {
            println("*** Using SSH ***")
        } else {
            println(
                "*** SECURITY WARNING: connecting over Telnet (port $port). Credentials and all traffic " +
                    "for this session travel in plaintext. Use an SSH-enabled console (port 22) instead " +
                    "whenever possible. ***",
            )
        }

        try {
            if (useSSH) {
                val knownHosts = Paths.get(System.getProperty("user.home"), ".ssh", "known_hosts")
                val transport = SshTransport.connect(
                    host = server,
                    port = port,
                    username = aixUsername,
                    password = aixPassword,
                    knownHostsFile = knownHosts,
                    // Trust-on-first-use for a genuinely new host, matching the original's behavior for
                    // unknown hosts. Unlike the original, a *changed* key throws instead of silently
                    // going through — SshTransport enforces that itself, this callback never sees it.
                    trustUnknownHost = { true },
                )
                sshTransport = transport
                input = transport.input
                output = transport.output
            } else {
                val sock = Socket()
                sock.connect(InetSocketAddress(server, port), CONNECT_TIMEOUT_MS.toInt())
                sock.keepAlive = true
                socket = sock

                input = BufferedReader(InputStreamReader(sock.getInputStream()))
                output = PrintWriter(sock.getOutputStream())

                // Telnet negotiation: IAC WILL TERMINAL-TYPE, IAC SB TERMINAL-TYPE "aixterm" IAC SE, etc.
                val init1 = charArrayOf(0xff.toChar(), 0xfb.toChar(), 0x18.toChar())
                val init2 = charArrayOf(
                    0xff.toChar(), 0xfa.toChar(), 0x18.toChar(), 0x00.toChar(), 'a', 'i', 'x', 't', 'e', 'r', 'm', 0xff.toChar(), 0xf0.toChar(),
                )
                val init3 = charArrayOf(0xff.toChar(), 0xfd.toChar(), 0x01.toChar())
                val init4 = charArrayOf(
                    0xff.toChar(), 0xfd.toChar(), 0x03.toChar(), 0xff.toChar(), 0xfc.toChar(), 0x1f.toChar(), 0xff.toChar(), 0xfc.toChar(), 0x01.toChar(),
                )
                for (seq in listOf(init1, init2, init3, init4)) {
                    output.print(seq)
                    traceLog(String(seq))
                }
                output.flush()
            }

            traceLog(aixUsername)

            var temp: String
            if (useSSH) {
                // The keyboard-interactive username/password rounds already happened inside
                // SshTransport.connect() - the shell is authenticated, just wait for the AIX prompt.
                temp = readUntil("[c", "$ ", "invalid login name or password")
                if (!temp.contains("[c") && !temp.contains("$ ")) {
                    if (temp.contains("invalid login")) {
                        disconnect()
                        return SessionError.AIX_LOGIN_WRONG
                    } else {
                        print(temp)
                        print("Unsure what happened here.  Check logs!")
                        disconnect()
                        return SessionError.IO_ERROR
                    }
                }
            } else {
                write("$aixUsername\r")
                temp = readUntil("Password:", "password:", "[c")

                if (!temp.contains("[c")) {
                    bSensitiveData = true
                    val line = writeLog("$aixPassword\r", "[c", "invalid login name or password")
                    bSensitiveData = false

                    if (line.contains("invalid login") || line.contains("password:") || line.contains("Password:")) {
                        disconnect()
                        return SessionError.AIX_LOGIN_WRONG
                    } else if (line.contains("$ ")) {
                        print(line)
                        print("It appears we weren't able to bypass text mode.\nYou may have a slow connection.\nOr this console is not setup as a 'Windows PC' in SYMOP.")
                        disconnect()
                        return SessionError.NOT_WINDOWSLEVEL_3
                    } else if (!line.contains("[c")) {
                        print(line)
                        print("Unsure what happened here.  Check logs!")
                        disconnect()
                        return SessionError.IO_ERROR
                    }
                }
            }

            write("WINDOWSLEVEL=3\n")

            temp = readUntil(
                "$ ", "SymStart~Global", "Selection :", "no longer supported!",
                "Logins not allowed from host: ", "Your password has expired.",
                "Your password will expire:", "invalid login name or password",
            )
            println(temp)
            if (temp.contains("Your password will expire:")) {
                print("Your AIX password is due to expire.  Please Change it now.")
                passWillExpire = true
                temp = readUntil(
                    "$ ", "SymStart~Global", "Selection :", "no longer supported!",
                    "Logins not allowed from host: ", "Your password will expire:", "invalid login name or password",
                )
                println(temp)
            }

            if (temp.contains("no longer supported!")) {
                disconnect()
                println(temp)
                return if (passWillExpire) SessionError.NOT_WINDOWSLEVEL_3_PASS_WILL_EXPIRE else SessionError.NOT_WINDOWSLEVEL_3
            } else if (temp.contains("Logins not allowed")) {
                print("You cannot log in from this IP. Verify this PC is setup to use Symitar!")
                disconnect()
                return SessionError.IP_NOT_ALLOWED
            } else if (temp.contains("Your password has expired.")) {
                print("Your AIX password has expired.")
                disconnect()
                return SessionError.AIX_PASSWORD_EXPIRED
            } else if (temp.contains("invalid login name or password")) {
                print("Invalid AIX Password was entered")
                disconnect()
                return SessionError.AIX_LOGIN_WRONG
            } else if (temp.contains("Selection :")) { // EASE Menu
                println("EASE Menu has been detected")
                val easeSelection = getEaseSelection(temp, sym)
                println("EASE Selection = $easeSelection\n")
                if (easeSelection == -1) {
                    disconnect()
                    println("EASE Selection was not found for sym $sym\n")
                    return SessionError.SYM_INVALID
                } else {
                    println("Sending EASE Selection: $easeSelection")
                    write("$easeSelection\r")
                    temp = readUntil("SymStart~Global", "UserId :")
                    if (temp.contains("UserId :")) {
                        print("In Symulate Mode")
                        return if (passWillExpire) SessionError.NOT_WINDOWSLEVEL_3_PASS_WILL_EXPIRE else SessionError.NOT_WINDOWSLEVEL_3
                    }
                }
            } else if (temp.contains("$ ")) {
                write("sym $sym\r")
            }

            try {
                var cur = readNextCommand()
                while (cur.command != "Input" || cur.parameters["HelpCode"] == "10025") {
                    log(cur)
                    if (cur.command == "Input" && cur.parameters["HelpCode"] == "10025") write("\$WinHostSync\$\r")
                    if (cur.command == "SymLogonDir") {
                        actualSym = cur.parameters["Dir"]!!.trim().toInt()
                        hostIPA = cur.parameters["Host"]!!.trim()
                    }
                    if (cur.command == "SymLogonRev") symRev = cur.parameters["HostRev"]!!.trim()
                    if (cur.command == "SymLogonError") {
                        val text = cur.parameters["Text"] ?: ""
                        disconnect()
                        return when {
                            text.contains("Too Many Invalid Password Attempts") -> SessionError.CONSOLE_BLOCKED
                            text.contains("Revision Incompatibility") -> SessionError.INCOMPATIBLE_REVISION
                            text.contains("DBMS Not Available - Connection refused") -> SessionError.DBMS_NOT_AVAILABLE
                            else -> SessionError.UNDEFINED_ERROR
                        }
                    }
                    cur = readNextCommand()
                }
                log(cur.toString())
            } catch (e: IOException) {
                disconnect()
                return if (e.message?.contains("SYM not Found") == true) {
                    println("SYM not Found")
                    SessionError.SYM_INVALID
                } else {
                    SessionError.IO_ERROR
                }
            }
        } catch (e: SshHostKeyChangedException) {
            e.printStackTrace()
            disconnect()
            return SessionError.SSH_KEY_CHANGED
        } catch (e: UnknownHostException) {
            e.printStackTrace()
            disconnect()
            return SessionError.SERVER_NOT_FOUND
        } catch (e: IOException) {
            e.printStackTrace()
            disconnect()
            return SessionError.IO_ERROR
        } catch (e: Exception) {
            // MINA SSHD throws a plain (unchecked-ish) exception once every SSH auth method is
            // exhausted, same role as JSch's "Auth fail"/"Auth cancel" in the original.
            val msg = e.message ?: ""
            disconnect()
            return if (useSSH && (msg.contains("Auth fail") || msg.contains("Auth cancel") || msg.contains("No more authentication methods"))) {
                println("SSH auth failed: $msg")
                SessionError.AIX_LOGIN_WRONG
            } else {
                e.printStackTrace()
                SessionError.IO_ERROR
            }
        }

        loggedInAIX = true
        return loginUser(userID)
    }

    override fun loginUser(userID: String): SessionError {
        if (!loggedInAIX) return SessionError.NOT_CONNECTED

        this.userID = userID
        try {
            val tmpSym = sym
            bSensitiveData = true
            write(if (useSSH) "$userID \r" else "$userID\r")
            bSensitiveData = false

            var cur = readNextCommand()
            if (cur.command == "MsgDlg") {
                log("USER RESPONSE: " + cur.parameters["Text"])
                write("\r0\r")
                cur = readNextCommand()
            }
            log("USER RESPONSE: " + cur.command)

            if (cur.command == "SymLogonInvalidUser") {
                println("Bad User Password")
                write("\r")
                untilInput()
                return SessionError.USERID_INVALID
            } else if (cur.command == "SymLogonFrozen") {
                println("Console Frozen")
                disconnect()
                return SessionError.CONSOLE_BLOCKED
            } else if (cur.command == "SymLogonChangePassword") {
                println("Change Password Required")
                disconnect()
                return SessionError.USERID_PASSWORD_CHANGE
            }

            write("\r")
            readNextCommand()
            write("\r")
            log(readNextCommand().toString())

            run {
                val cmdGetDate = Command("Misc")
                cmdGetDate.parameters["InfoType"] = "BankingDate"
                write(cmdGetDate)
                val symdate = readNextCommand().parameters["BankingDate"]!!
                symDate.timeInMillis = 0
                symDate.set(symdate.substring(4, 8).toInt(), symdate.substring(0, 2).toInt() - 1, symdate.substring(2, 4).toInt())
            }

            run {
                val cmdGetCon = Command("Misc")
                cmdGetCon.parameters["InfoType"] = "ConsoleNumber"
                write(cmdGetCon)
                consoleNum = readNextCommand().parameters["ConsoleNumber"]!!.toInt()
            }

            connected = true
            log("Connected to Symitar!")

            if (keepAliveEnabled) startKeepAliveThread(tmpSym)
        } catch (e: UnknownHostException) {
            e.printStackTrace()
            disconnect()
            return SessionError.SERVER_NOT_FOUND
        } catch (e: IOException) {
            e.printStackTrace()
            disconnect()
            return SessionError.IO_ERROR
        }

        return SessionError.NONE
    }

    private fun startKeepAliveThread(tmpSym: Int) {
        keepAlive = Thread {
            var cal: Calendar = GregorianCalendar()
            var lastActivityTime = getLastActivity().get(Calendar.HOUR_OF_DAY) * 60 + getLastActivity().get(Calendar.MINUTE) + noActivityDelayMinutes
            val termOptionTime = getTerminateHour() * 60 + getTerminateMinute()
            var curTime = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            keepAliveActive = true
            val neverTerminate = getNeverTerminate()
            var terminateTime = maxOf(lastActivityTime, termOptionTime)
            var firstRun = true

            try {
                while (terminateTime > curTime || neverTerminate) {
                    firstRun = false
                    Thread.sleep(KEEP_ALIVE_POLL_MS)
                    wakeUp()

                    cal = GregorianCalendar()
                    curTime = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                    lastActivityTime = getLastActivity().get(Calendar.HOUR_OF_DAY) * 60 + getLastActivity().get(Calendar.MINUTE) + noActivityDelayMinutes
                    terminateTime = maxOf(lastActivityTime, termOptionTime)

                    if (terminateTime - curTime < keepAliveWarningMinutes && !neverTerminate) {
                        log(cal.time.toString().substring(11, 19) + " Keep Alive (SYM $tmpSym) will terminate in ${terminateTime - curTime} minutes")
                    } else {
                        log(cal.time.toString().substring(11, 19) + " Keep Alive (SYM $tmpSym) ")
                    }
                }

                log(cal.time.toString().substring(11, 19) + " Keep Alive (SYM $tmpSym) Terminated")
                if (!firstRun) {
                    keepAliveActive = false
                    keepAliveTerminatedListener?.invoke()
                }
            } catch (e: InterruptedException) {
                println("Terminating keepalive thread")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        keepAlive!!.start()
    }

    override fun disconnect(): SessionError {
        // Close each resource independently so one failure (e.g. an already-broken socket)
        // doesn't skip cleanup of the rest.
        var hadError = false

        keepAlive?.interrupt()

        if (::input.isInitialized) runCatching { input.close() }.onFailure { hadError = true }
        if (::output.isInitialized) runCatching { output.close() }.onFailure { hadError = true }
        runCatching { socket?.close() }.onFailure { hadError = true }
        runCatching { sshTransport?.close() }.onFailure { hadError = true }
        runCatching {
            trace?.let {
                println("Closing Full Trace")
                it.flush()
                it.close()
                bFullTrace = false
            }
        }.onFailure { hadError = true }

        connected = false
        loggedInAIX = false
        return if (hadError) SessionError.IO_ERROR else SessionError.NONE
    }

    override fun isConnected(): Boolean = connected

    // ---------------------------------------------------------------------
    // File operations
    // ---------------------------------------------------------------------

    @Synchronized
    override fun errorCheckRepGen(filename: String): ErrorCheckResult? {
        if (!connected) return null
        try {
            write("mm3" + 27.toChar()) // Management menu #3 - repgen
            untilInput()

            write("7\r")
            log(readNextCommand().toString())
            log(readNextCommand().toString())

            write("$filename\r")
            var cur = readNextCommand()
            while (cur.command != "SpecfileErr" && cur.command != "MsgDlg") {
                log(cur.toString())
                cur = readNextCommand()
            }

            if (cur.parameters["Type"] != null) {
                return ErrorCheckResult(filename, "File does not exist on server!", type = ErrorCheckResult.Type.ERROR)
            }
            if (cur.parameters["Warning"] != null || cur.parameters["Error"] != null) {
                readNextCommand()
                return ErrorCheckResult(filename, "File does not exist on server!", type = ErrorCheckResult.Type.ERROR)
            }
            if (cur.parameters["Action"] == "NoError") {
                readNextCommand()
                return ErrorCheckResult(filename, "", type = ErrorCheckResult.Type.NO_ERROR)
            }
            if (cur.parameters["Action"] == "Init") {
                val errFile = cur.parameters["FileName"] ?: filename
                var line = -1
                var column = -1
                var error = ""

                cur = readNextCommand()
                while (cur.parameters["Action"] != "DisplayEdit") {
                    if (cur.parameters["Action"] == "FileInfo") {
                        line = cur.parameters["Line"]!!.replace(",", "").toInt()
                        column = cur.parameters["Col"]!!.replace(",", "").toInt()
                    } else if (cur.parameters["Action"] == "ErrText") {
                        error += cur.parameters["Line"] + " "
                    }
                    log(cur.toString())
                    cur = readNextCommand()
                }
                readNextCommand()
                return ErrorCheckResult(errFile, error.trim(), line, column, type = ErrorCheckResult.Type.ERROR)
            }
        } catch (e: IOException) {
            return null
        }
        return null
    }

    @Synchronized
    override fun fileExists(file: SymitarFile): Boolean = getFileList(file.type, file.name).isNotEmpty()

    @Synchronized
    override fun getFile(file: SymitarFile): String? {
        if (!connected) return null
        setLastActivity()

        val data = StringBuilder()
        var wroteSizeWarning = false

        val retrieve = Command("File")
        retrieve.parameters["Action"] = "Retrieve"
        retrieve.parameters["Type"] = fileTypeWireName(file.type) ?: ""
        retrieve.parameters["Name"] = file.name
        write(retrieve)

        try {
            while (true) {
                val current = readNextCommand()
                val status = current.parameters["Status"]
                if (status != null && status.contains("No such file or directory")) return ""
                else if (status != null) return null

                if (current.parameters["Done"] != null) return data.toString()

                if (data.length < MAX_FILE_DOWNLOAD_SIZE) {
                    data.append(current.getFileData())
                    if (file.type == FileType.REPORT) data.append("\n")
                } else if (!wroteSizeWarning) {
                    data.insert(0, "WARNING - This file exceeds the 2MB limit that RepDev has for loading files. This text should only be used as a preview!\n\n")
                    data.append("\n\nWARNING - This file exceeds the 2MB limit that RepDev has for loading files. This text should only be used as a preview!")
                    wroteSizeWarning = true
                }
            }
        } catch (e: IOException) {
            return null
        }
    }

    @Synchronized
    override fun getFileList(type: FileType, search: String): ArrayList<SymitarFile> {
        val toRet = ArrayList<SymitarFile>()
        setLastActivity()
        if (!connected) return toRet

        val list = Command("File")
        fileTypeWireName(type)?.let { list.parameters["Type"] = it }
        list.parameters["Name"] = search
        list.parameters["Action"] = "List"
        write(list)

        while (true) {
            val current = try {
                readNextCommand()
            } catch (e: IOException) {
                e.printStackTrace()
                return toRet
            }

            if (current.parameters["Status"] != null) break

            val name = current.parameters["Name"]
            val date = current.parameters["Date"]
            val time = current.parameters["Time"]
            val size = current.parameters["Size"]
            if (name != null && date != null && time != null && size != null) {
                toRet.add(SymitarFile(name = name, type = type, sym = sym, modified = parseSymitarDate(date, time), size = size.toLong()))
            }

            if (current.parameters["Done"] != null) break
        }

        return toRet
    }

    @Synchronized
    override fun removeFile(file: SymitarFile): SessionError {
        val delete = Command("File")
        delete.parameters["Action"] = "Delete"
        delete.parameters["Type"] = fileTypeWireName(file.type) ?: ""
        delete.parameters["Name"] = file.name
        write(delete)

        try {
            val current = readNextCommand()
            val status = current.parameters["Status"]
            return when {
                status != null && status.contains("No such file or directory") -> SessionError.ARGUMENT_ERROR
                status != null -> SessionError.FILENAME_TOO_LONG
                current.parameters["Done"] != null -> SessionError.NONE
                else -> SessionError.IO_ERROR
            }
        } catch (e: IOException) {
            e.printStackTrace()
            return SessionError.IO_ERROR
        }
    }

    @Synchronized
    override fun renameFile(file: SymitarFile, newName: String): SessionError {
        val retrieve = Command("File")
        retrieve.parameters["Action"] = "Rename"
        retrieve.parameters["Type"] = fileTypeWireName(file.type) ?: ""
        retrieve.parameters["Name"] = file.name
        retrieve.parameters["NewName"] = newName
        write(retrieve)

        try {
            val current = readNextCommand()
            val status = current.parameters["Status"]
            return when {
                status != null && status.contains("No such file or directory") -> SessionError.ARGUMENT_ERROR
                status != null -> SessionError.FILENAME_TOO_LONG
                current.parameters["Done"] != null -> SessionError.NONE
                else -> SessionError.IO_ERROR
            }
        } catch (e: IOException) {
            e.printStackTrace()
            return SessionError.IO_ERROR
        }
    }

    @Synchronized
    override fun saveFile(file: SymitarFile, text: String): SessionError {
        var body = text
        val partSize = 3996
        var curPart = 0
        val f3 = DecimalFormat("000")
        val f5 = DecimalFormat("00000")
        val buf = CharArray(16)
        val pad20 = " ".repeat(6)

        setLastActivity()
        if (!connected) return SessionError.NOT_CONNECTED

        log("Saving file: $file")

        val store = Command("File")
        store.parameters["Action"] = "Store"
        store.parameters["Type"] = fileTypeWireName(file.type) ?: ""
        store.parameters["Name"] = file.name

        val unpause = Command("WakeUp")

        wakeUp()
        write(store)

        try {
            var current = readNextCommand()
            if (current.toString().isEmpty()) {
                println("Returned null for the save file command, ack!! trying to restore")
                wakeUp()
                write(store)
                current = readNextCommand()
            }

            var breakCount = 0
            while (!current.toString().contains("BadCharList")) {
                current = readNextCommand()
                if (breakCount++ > 5) return SessionError.NULL_POINTER
            }

            println("Save file command:\n${current}\n")

            val status = current.parameters["Status"]
            if (status != null && status.contains("Filename is too long")) return SessionError.FILENAME_TOO_LONG

            for (badChar in current.parameters["BadCharList"]!!.split(",")) {
                body = body.replace(badChar.toInt().toChar().toString(), "")
            }

            do {
                var toSend: String
                do {
                    toSend = body.substring(0, minOf(body.length, partSize))
                    write("PROT" + f3.format(curPart) + "DATA" + f5.format(toSend.length))
                    write(toSend)
                    input.read(buf, 0, 16)
                    if (bFullTrace) trace?.write(String(buf).toByteArray())
                } while (buf[7] == 'N') // Resend on NAK

                curPart++
                body = body.substring(toSend.length)
            } while (body.isNotEmpty())

            write("PROT" + f3.format(curPart) + "EOF" + pad20)
            input.read(buf, 0, 16)
            if (bFullTrace) trace?.write(String(buf).toByteArray())

            current = readNextCommand()
            write(unpause)
            if (current.toString().contains("Unable to save on Output Open")) return SessionError.FILE_READ_ONLY
        } catch (e: IOException) {
            return SessionError.IO_ERROR
        }
        return SessionError.NONE
    }

    @Synchronized
    override fun installRepgen(f: String): ErrorCheckResult? {
        try {
            write("mm3" + 27.toChar())
            untilInput()

            write("8\r")
            log(readNextCommand().toString())
            log(readNextCommand().toString())

            write("$f\r")
            var cur = readNextCommand()
            log(cur.toString())

            if (cur.parameters["Warning"] != null || cur.parameters["Error"] != null) {
                readNextCommand()
                return ErrorCheckResult(f, "File does not exist on server!", type = ErrorCheckResult.Type.ERROR)
            }

            if (cur.command == "SpecfileData") {
                readNextCommand()
                write("1\r")
                readNextCommand()
                readNextCommand()
                return ErrorCheckResult(
                    f, "", installSize = cur.parameters["Size"]!!.replace(",", "").toInt(),
                    type = ErrorCheckResult.Type.INSTALLED_SUCCESSFULLY,
                )
            }

            if (cur.parameters["Action"] == "Init") {
                val errFile = cur.parameters["FileName"] ?: f
                var line = -1
                var column = -1
                var error = ""

                cur = readNextCommand()
                while (cur.parameters["Action"] != "DisplayEdit") {
                    if (cur.parameters["Action"] == "FileInfo") {
                        line = cur.parameters["Line"]!!.replace(",", "").toInt()
                        column = cur.parameters["Col"]!!.replace(",", "").toInt()
                    } else if (cur.parameters["Action"] == "ErrText") {
                        error += cur.parameters["Line"] + " "
                    }
                    log(cur.toString())
                    cur = readNextCommand()
                }
                readNextCommand()
                return ErrorCheckResult(errFile, error.trim(), line, column, type = ErrorCheckResult.Type.ERROR)
            }
        } catch (e: IOException) {
            return null
        }
        return null
    }

    // ---------------------------------------------------------------------
    // Batch: RepGen runs, FM, print queues
    // ---------------------------------------------------------------------

    @Synchronized
    override fun runRepGen(name: String, queue: Int, progress: RepgenProgressListener?, prompter: PromptListener): RunRepgenResult {
        var isError = false
        val queueAvailable = BooleanArray(QUEUE_SLOTS)
        val queueCounts = IntArray(QUEUE_SLOTS) { -1 }
        var seq = -1
        var time = 0
        var selectedQueue = queue
        setLastActivity()

        progress?.onProgress(0, "Queuing batch run, please wait...")

        try {
            write("mm0" + 27.toChar())
            untilInput()
            progress?.onProgress(5, null)

            write("1\r")
            untilInput()
            progress?.onProgress(10, null)

            write("11\r")
            untilInput()
            progress?.onProgress(15, "Please answer prompts")

            write("$name\r")

            var cur = readNextCommand()
            loop@ while (true) {
                log(cur)
                when {
                    cur.command == "Input" && cur.parameters["HelpCode"] == "20301" -> break@loop
                    cur.command == "Input" -> {
                        val result = prompter.getPrompt(cur.parameters["Prompt"] ?: "")
                        if (result == null) {
                            write(0x1b.toChar().toString())
                            untilInput()
                            return RunRepgenResult(-1, 0)
                        } else {
                            progress?.onProgress(null, (cur.parameters["Prompt"] ?: "") + ": " + result.trim())
                            write(result.trim() + "\r")
                        }
                    }
                    cur.command == "Bell" -> progress?.onProgress(15, "That prompt input is invalid, please reenter")
                    cur.command == "Batch" && cur.parameters["Text"]?.contains("No such file or directory") == true -> {
                        untilInput()
                        progress?.onProgress(100, "Error: No such file or directory")
                        return RunRepgenResult(-1, 0)
                    }
                    cur.command == "SpecfileErr" -> isError = true
                    isError && cur.command == "Batch" && cur.parameters["Action"] == "DisplayLine" -> {
                        val errText = cur.parameters["Text"]
                        untilInput()
                        progress?.onProgress(100, "There was an error in your program,\n that is preventing it from running:\n\n$errText")
                        return RunRepgenResult(-1, 0)
                    }
                    cur.command == "Batch" && cur.parameters["Action"] == "DisplayLine" ->
                        progress?.onProgress(null, cur.parameters["Text"])
                }
                cur = readNextCommand()
            }

            write("\r")
            untilInput()
            progress?.onProgress(20, null)

            write("0\r")
            cur = readNextCommand()
            while (cur.command != "Input") {
                log(cur)
                parseQueueAvailability(cur, queueAvailable)
                cur = readNextCommand()
            }
            progress?.onProgress(25, null)

            val getQueues = Command("Misc")
            getQueues.parameters["InfoType"] = "BatchQueues"
            write(getQueues)

            cur = readNextCommand()
            while (cur.parameters["Done"] == null) {
                log(cur)
                tallyQueueCounts(cur, queueCounts)
                cur = readNextCommand()
            }

            selectedQueue = pickQueue(selectedQueue, queueAvailable, queueCounts)
            write("$selectedQueue\r")
            untilInput()
            progress?.onProgress(30, null)

            write("1\r")
            untilInput()

            write(getQueues)
            var newestTime = 0
            cur = readNextCommand()
            while (cur.parameters["Done"] == null) {
                log(cur)
                if (cur.parameters["Action"] == "QueueEntry" && cur.parameters["Stat"] != "Scheduled") {
                    val curTime = parseQueueTime(cur.parameters["Time"]!!)
                    if (curTime > newestTime) {
                        newestTime = curTime
                        seq = cur.parameters["Seq"]!!.toInt()
                        time = curTime
                    }
                }
                cur = readNextCommand()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return RunRepgenResult(-1, 0)
        }

        progress?.onProgress(50, "Repgen queued\nWaiting for batch job to finish")
        return RunRepgenResult(seq, time)
    }

    @Synchronized
    override fun runBatchFM(searchTitle: String, searchDays: Int, file: FMFile, queue: Int): RunFMResult {
        val result = RunFMResult()
        val queueAvailable = BooleanArray(QUEUE_SLOTS)
        val queueCounts = IntArray(QUEUE_SLOTS) { -1 }
        var selectedQueue = queue

        try {
            write("mm0" + 27.toChar())
            untilInput()

            write("1\r")
            untilInput()
            write("24\r")
            untilInput()
            write("5\r") // Perform FM from PowerOn Output
            untilInput()
            write("${file.ordinal}\r")
            untilInput()
            write("0\r") // Undo a Posting from FM Posting Journal?
            untilInput()
            write("$searchTitle\r")
            untilInput()
            write("$searchDays\r")
            untilInput()

            if (file == FMFile.ACCOUNT) {
                write("1\r") // Record FM History?
                untilInput()
            }

            write("${result.resultTitle}\r")
            untilInput()

            write("1\r") // Produce Empty Report if No Exceptions
            var cur = untilInput()

            if (cur.command == "Input" && cur.parameters["HelpCode"] == "25514") {
                write("0\r") // Produce Empty Report if No Locking Excp?
                untilInput { }
            }

            write("0\r") // Batch Options?
            cur = readNextCommand()
            while (cur.command != "Input") {
                log(cur)
                parseQueueAvailability(cur, queueAvailable)
                cur = readNextCommand()
            }

            val getQueues = Command("Misc")
            getQueues.parameters["InfoType"] = "BatchQueues"
            write(getQueues)

            cur = readNextCommand()
            while (cur.parameters["Done"] == null) {
                log(cur)
                tallyQueueCounts(cur, queueCounts)
                cur = readNextCommand()
            }

            selectedQueue = pickQueue(selectedQueue, queueAvailable, queueCounts)
            write("$selectedQueue\r")
            untilInput()

            write("1\r")
            untilInput()

            write(getQueues)
            var newestTime = 0
            cur = readNextCommand()
            while (cur.parameters["Done"] == null) {
                log(cur)
                if (cur.parameters["Action"] == "QueueEntry" && cur.parameters["Stat"] == "Running") {
                    val curTime = parseQueueTime(cur.parameters["Time"]!!)
                    if (curTime > newestTime) {
                        newestTime = curTime
                        result.seq = cur.parameters["Seq"]!!.toInt()
                    }
                }
                cur = readNextCommand()
            }
        } catch (e: IOException) {
            System.err.println("ERROR: ${e.message}")
        }

        return result
    }

    private fun parseQueueAvailability(cur: Command, queueAvailable: BooleanArray) {
        if (cur.parameters["Action"] != "DisplayLine" || cur.parameters["Text"]?.contains("Batch Queues Available:") != true) return
        val line = cur.parameters["Text"]!!
        for (rawEntry in line.substring(line.indexOf(":") + 1).split(",")) {
            val entry = rawEntry.trim()
            if (entry.contains("-")) {
                val (start, end) = entry.split("-").map { it.trim().toInt() }
                for (x in start..end) queueAvailable[x] = true
            } else {
                queueAvailable[entry.toInt()] = true
            }
        }
    }

    private fun tallyQueueCounts(cur: Command, queueCounts: IntArray) {
        val action = cur.parameters["Action"] ?: return
        if (action == "QueueEntry" && cur.parameters["Stat"] == "Running") {
            val q = cur.parameters["Queue"]!!.toInt()
            queueCounts[q] = if (queueCounts[q] < 0) 1 else queueCounts[q] + 1
        } else if (action == "QueueEmpty") {
            queueCounts[cur.parameters["Queue"]!!.toInt()] = 0
        }
    }

    private fun pickQueue(requested: Int, queueAvailable: BooleanArray, queueCounts: IntArray): Int =
        pickBatchQueue(requested, queueAvailable, queueCounts)

    private fun parseQueueTime(timeStr: String): Int = parseHhMmSsToSeconds(timeStr)

    override fun isSeqRunning(seq: Int): Boolean {
        if (!connected) return false
        val getQueues = Command("Misc")
        getQueues.parameters["InfoType"] = "BatchQueues"
        write(getQueues)

        try {
            var cur = readNextCommand()
            var running = false
            while (cur.parameters["Done"] == null) {
                log(cur)
                if (cur.parameters["Action"] == "QueueEntry" && cur.parameters["Seq"]?.toInt() == seq) running = true
                cur = readNextCommand()
            }
            return running
        } catch (e: IOException) {
            return false
        }
    }

    override fun terminateRepgen(seq: Int) {
        // Not implemented in the original either.
    }

    // ---------------------------------------------------------------------
    // Print items / print queue
    // ---------------------------------------------------------------------

    @Synchronized
    override fun getPrintItems(query: String, limit: Int): ArrayList<PrintItem>? {
        if (!connected) return null
        val cappedLimit = minOf(40, limit)

        val items = ArrayList<PrintItem>()
        val getItems = Command("File")
        getItems.parameters["Action"] = "List"
        getItems.parameters["MaxCount"] = "50"
        getItems.parameters["Query"] = "LAST $cappedLimit \"+$query+\""
        getItems.parameters["Type"] = "Report"
        write(getItems)

        try {
            var cur = readNextCommand()
            while (cur.parameters["Done"] == null) {
                log(cur)
                parsePrintItem(cur)?.let { items.add(it) }
                cur = readNextCommand()
            }
            if (input.ready()) log(readNextCommand())
        } catch (e: IOException) {
            e.printStackTrace()
        }

        Collections.sort(items)
        return items
    }

    @Synchronized
    override fun getPrintItems(seq: Sequence): ArrayList<PrintItem>? {
        if (!connected) return null
        val seqCal = GregorianCalendar().apply { time = seq.date }

        val items = ArrayList<PrintItem>()
        val getItems = Command("File")
        getItems.parameters["Action"] = "List"
        getItems.parameters["MaxCount"] = "300"
        getItems.parameters["Query"] = "BATCH ${seq.seq}"
        getItems.parameters["Type"] = "Report"
        write(getItems)
        log("Requesting batch sequence: $seq")

        try {
            var cur = readNextCommand()
            while (cur.parameters["Done"] == null) {
                log(cur)
                parsePrintItem(cur)?.let { item ->
                    val curCal = GregorianCalendar().apply { time = item.date }
                    if (curCal.get(Calendar.DAY_OF_YEAR) == seqCal.get(Calendar.DAY_OF_YEAR)) items.add(item)
                }
                cur = readNextCommand()
            }
            if (input.ready()) log(readNextCommand())
        } catch (e: IOException) {
            e.printStackTrace()
        }

        Collections.sort(items)
        return items
    }

    private fun parsePrintItem(cur: Command): PrintItem? {
        val sequence = cur.parameters["Sequence"] ?: return null
        return try {
            val date = parseSymitarDate(cur.parameters["Date"]!!, cur.parameters["Time"])
            PrintItem(
                cur.parameters["Title"] ?: "",
                sequence.toInt(),
                cur.parameters["Size"]!!.toInt(),
                cur.parameters["PageCount"]!!.toInt(),
                cur.parameters["BatchSeq"]!!.toInt(),
                date,
            )
        } catch (e: NumberFormatException) {
            e.printStackTrace()
            null
        }
    }

    // TODO (ported as-is): no more error checking than the original had.
    override fun printFileLPT(
        file: SymitarFile,
        queue: Int,
        formsOverride: Boolean,
        formLength: Int,
        startPage: Int,
        endPage: Int,
        copies: Int,
        landscape: Boolean,
        duplex: Boolean,
        queuePriority: Int,
    ): SessionError {
        if (!connected) return SessionError.NOT_CONNECTED
        if (file.type != FileType.REPORT) return SessionError.INVALID_FILE_TYPE

        try {
            write("mm1" + 27.toChar())
            untilInput()

            write("P\r")
            untilInput()

            write("${file.name}\r")
            untilInput()

            write("\r")
            // Waits for a specific queue prompt.
            var cur = readNextCommand()
            while (true) {
                log(cur)
                if (cur.command == "Input" && cur.parameters["HelpCode"] == "10008") break
                cur = readNextCommand()
            }

            write("$queue\r")
            cur = readNextCommand()
            while (cur.command != "Input") {
                log(cur)
                if (cur.command == "MsgDlg" && cur.parameters["Type"] == "Error") {
                    wakeUp()
                    return SessionError.INVALID_QUEUE
                }
                cur = readNextCommand()
            }

            write("\r")
            untilInput()

            return finishPrintFileLPT(formsOverride, formLength, startPage, endPage, copies, landscape, duplex)
        } catch (e: IOException) {
            e.printStackTrace()
            return SessionError.IO_ERROR
        }
    }

    private fun finishPrintFileLPT(
        formsOverride: Boolean,
        formLength: Int,
        startPage: Int,
        endPage: Int,
        copies: Int,
        landscape: Boolean,
        duplex: Boolean,
    ): SessionError {
        // If asked for a banner page, answer it before moving to the next field.
        write("0\r")
        untilInput()

        write((if (formsOverride) "1" else "0") + "\r")
        untilInput()

        if (formsOverride) {
            write("$formLength\r")
            untilInput()
        }

        write("$startPage\r")
        untilInput()
        write("$endPage\r")
        untilInput()
        write("$copies\r")
        untilInput()
        write((if (landscape) "1" else "0") + "\r")
        untilInput()
        write((if (duplex) "1" else "0") + "\r")
        untilInput()

        write("4\r")
        var cur = readNextCommand()
        while (cur.command != "Input") {
            log(cur)
            if (cur.command == "MsgDlg" && cur.parameters["Type"] == "Error") {
                wakeUp()
                return SessionError.INPUT_ERROR
            }
            cur = readNextCommand()
        }
        return SessionError.NONE
    }

    override fun printFileTPT(file: SymitarFile, queue: Int): SessionError? = null

    // ---------------------------------------------------------------------
    // Misc getters ported from the original (used by the properties panel / status bar)
    // ---------------------------------------------------------------------

    fun getSymDate(): GregorianCalendar = symDate

    fun getSymDateString(): String = "${symDate.get(Calendar.MONTH) + 1}/${symDate.get(Calendar.DATE)}/${symDate.get(Calendar.YEAR)}"

    /** May differ from the requested SYM depending on the AIX username used. */
    fun getActualSym(): Int = actualSym
    fun getConsoleNum(): Int = consoleNum
    fun getHostIPA(): String = hostIPA
    fun getSymRev(): String = symRev

    fun getProperties(): String =
        "Server IPA: $hostIPA\n" +
            "Port: $port\n" +
            "SYM: $actualSym\n" +
            "SYM Date: ${getSymDateString()}\n" +
            "Release: $symRev\n" +
            "Console: $consoleNum\n" +
            "Username: $aixUsername\n" +
            "TellerID: ${getUserNum()}\n"
}

/** -1 (or an unavailable queue) means "pick the first available queue with nothing running in it". */
internal fun pickBatchQueue(requested: Int, queueAvailable: BooleanArray, queueCounts: IntArray): Int {
    if (requested != -1 && queueAvailable[requested]) return requested
    var lastGood = -1
    for (q in queueCounts.indices) {
        if (queueAvailable[q]) lastGood = q
        if (queueAvailable[q] && queueCounts[q] == 0) return q
    }
    return lastGood
}

/** Parses a "HH:MM:SS" queue-entry timestamp into seconds since midnight. */
internal fun parseHhMmSsToSeconds(timeStr: String): Int {
    var t = timeStr.substringAfterLast(":").toInt()
    t += 60 * timeStr.substring(timeStr.indexOf(":") + 1, timeStr.lastIndexOf(":")).toInt()
    t += 3600 * timeStr.substringBefore(":").toInt()
    return t
}

private fun fileTypeWireName(type: FileType): String? = when (type) {
    FileType.REPGEN -> "RepWriter"
    FileType.HELP -> "Help"
    FileType.LETTER -> "Letter"
    FileType.REPORT -> "Report"
    FileType.DATA -> "Data"
}

/**
 * Ported from com.repdev.Util#parseDate. Symitar dates come back as MMddyyyy, with an
 * optional separate military-time field (e.g. "42" -> 00:42, "2312" -> 23:12).
 */
internal fun parseSymitarDate(dateStr: String, time: String?): Date {
    val pattern: String
    val toParse: String
    if (time.isNullOrBlank()) {
        pattern = "MMddyyyy"
        toParse = dateStr
    } else {
        pattern = "MMddyyyyHHmm"
        toParse = dateStr + DecimalFormat("0000").format(time.toInt())
    }
    return SimpleDateFormat(pattern).parse(toParse)
}

/**
 * Ported from com.repdev.EaseSelection — finds this SYM's menu number in an EASE menu
 * screen dump, by locating the "- SYM NNN" marker and reading the selection number off
 * the start of that same line.
 */
internal fun getEaseSelection(buffer: String, sym: Int): Int {
    val end = buffer.indexOf("- SYM " + "%03d".format(sym)) - 1
    if (end != -2) println("EASE: SYM selection found in menu.")

    val start = buffer.lastIndexOf("\n", end)
    if (start == -1) {
        println("EASE: Unexpected Error - Could not find the begining of the line.")
        return -1
    }

    return buffer.substring(start, end + 1).trim().toIntOrNull() ?: run {
        println("EASE: Selection is not a number.")
        -1
    }
}
