package org.repdev.engine

import java.util.Collections

/**
 * Ported from com.repdev.SymitarSession — abstract base for a connection to a
 * Symitar/AIX host. Same contract as the original; two SWT touchpoints were
 * swapped for plain listener interfaces per MODERNIZATION_PLAN.md:
 *
 * - `runRepGen`'s `ProgressBar`/`Text` params -> [RepgenProgressListener].
 * - `PromptListener` already was a plain interface in the original; kept as-is
 *   (now a `fun interface` so callers can pass a lambda).
 */
abstract class SymitarSession {
    var server: String = ""
        protected set
    var aixUsername: String = ""
        protected set
    var aixPassword: String = ""
        protected set
    var userID: String = ""
        protected set
    var sym: Int = 0
        protected set
    var port: Int = 0
        protected set

    abstract fun connect(server: String, port: Int, aixUsername: String, aixPassword: String, sym: Int, userID: String): SessionError
    abstract fun loginUser(userID: String): SessionError
    abstract fun disconnect(): SessionError
    abstract fun isConnected(): Boolean

    /** Null if not connected or the file can't be found. */
    abstract fun getFile(file: SymitarFile): String?
    abstract fun fileExists(file: SymitarFile): Boolean
    abstract fun removeFile(file: SymitarFile): SessionError
    abstract fun saveFile(file: SymitarFile, text: String): SessionError
    abstract fun renameFile(file: SymitarFile, newName: String): SessionError

    data class RunRepgenResult(val seq: Int, val time: Int)

    /** Replaces the original's `ProgressBar`/`Text` params — caller decides how to render either half. */
    fun interface RepgenProgressListener {
        fun onProgress(percent: Int?, message: String?)
    }

    /** Lets `runRepGen` ask the caller for a prompt answer without the session needing to know about UI. */
    fun interface PromptListener {
        fun getPrompt(name: String): String?
    }

    abstract fun runRepGen(name: String, queue: Int, progress: RepgenProgressListener?, prompter: PromptListener): RunRepgenResult
    abstract fun isSeqRunning(seq: Int): Boolean
    abstract fun terminateRepgen(seq: Int)

    class RunFMResult(var resultTitle: String = randomTitle(), var seq: Int = 0) {
        companion object {
            // ponytail: exact bound is a best-effort reconstruction (source has this literal
            // partially redacted); any 6+ digit bound gives the same "unique enough" title.
            private fun randomTitle(): String = "RepDev FM - " + "%06d".format((Math.random() * 10_000_000).toInt())
        }
    }

    enum class FMFile(val displayName: String) {
        ACCOUNT("Account"),
        INVENTORY("Inventory"),
        PAYEE("Payee"),
        GL_ACCOUNT("GL_Account"),
        RECIEVED_ITEM("Recieved_Item"),
        PARTICIPANT("Partipant"),
        PARTICIPATION("Participation"),
        DEALER("Dealer"),
        USER("User"),
        COLLATERAL("Collateral"),
    }

    abstract fun runBatchFM(searchTitle: String, searchDays: Int, file: FMFile, queue: Int): RunFMResult

    abstract fun getPrintItems(query: String, limit: Int): ArrayList<PrintItem>?
    abstract fun getPrintItems(seq: Sequence): ArrayList<PrintItem>?

    /**
     * Goes through recent batch output in print control looking for a report by name.
     * If `time` is -1, returns up to `limit` matches; otherwise returns the (at most one)
     * run that started within a second of `time`, to disambiguate two concurrent runs of
     * the same report (see original's doc comment — Run Report relies on this).
     *
     * Ported straight off `SymitarFile(sym, ""+cur.getSeq(), REPORT).getData()`, just
     * calling [getFile] directly instead of routing through a file descriptor that used to
     * reach back into a global session registry to do the exact same thing.
     */
    fun getReportSeqs(reportName: String, time: Int, search: Int, limit: Int): ArrayList<Sequence> {
        val items = getPrintItems("REPWRITER", search) ?: return ArrayList()
        val newItems = ArrayList<Sequence>()

        // Newest first, since that's what we're almost always looking for.
        Collections.reverse(items)

        for (cur in items) {
            var file = getFile(SymitarFile(name = cur.seq.toString(), type = FileType.REPORT, sym = sym)) ?: continue

            val marker = file.indexOf("Processing begun on")
            if (marker < 0) continue

            file = file.substring(marker + 41)
            val timeStr = file.substring(0, 8)
            var curTime = timeStr.substring(timeStr.lastIndexOf(":") + 1).toInt()
            curTime += 60 * timeStr.substring(timeStr.indexOf(":") + 1, timeStr.lastIndexOf(":")).toInt()
            curTime += 3600 * timeStr.substring(0, timeStr.indexOf(":")).toInt()

            file = file.substring(file.indexOf("(newline when done):") + 21)
            val name = file.substring(0, file.indexOf("\n"))

            if ((time == -1 || curTime - 1 == time || curTime == time || curTime + 1 == time) && name == reportName) {
                newItems.add(Sequence(sym, cur.batchSeq, cur.date))
                if (time != -1 || newItems.size >= limit) break
            }
        }

        return newItems
    }

    /** Same idea as [getReportSeqs] but for a batch FM job, keyed by its posting title. */
    fun getFMSeqs(reportName: String, search: Int, limit: Int): ArrayList<Sequence> {
        val items = getPrintItems("MISCFMPOST", search) ?: return ArrayList()
        val newItems = ArrayList<Sequence>()

        Collections.reverse(items)

        for (cur in items) {
            var file = getFile(SymitarFile(name = cur.seq.toString(), type = FileType.REPORT, sym = sym)) ?: continue

            file = file.substring(file.indexOf("Name of Posting: ") + 17)
            val name = file.substring(0, file.indexOf("\n")).trim()

            if (name == reportName) {
                newItems.add(Sequence(sym, cur.batchSeq, cur.date))
                if (newItems.size >= limit) break
            }
        }

        return newItems
    }

    /** Supports a leading/trailing "+" wildcard, same as the original. */
    abstract fun getFileList(type: FileType, search: String): ArrayList<SymitarFile>

    abstract fun printFileLPT(
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
    ): SessionError

    /** Default-options convenience overload, same defaults as the original. */
    fun printFileLPT(file: SymitarFile, queue: Int): SessionError =
        printFileLPT(file, queue, false, 0, 0, 0, 1, landscape = false, duplex = false, queuePriority = 4)

    // Nullable: the original's DirectSymitarSession implementation is a stub returning null.
    abstract fun printFileTPT(file: SymitarFile, queue: Int): SessionError?

    abstract fun errorCheckRepGen(filename: String): ErrorCheckResult?
    abstract fun installRepgen(f: String): ErrorCheckResult?

    fun getUserNum(leadingZero: Boolean = false): String {
        var userNum = if (userID.contains(".")) userID.substringBefore(".") else userID.take(3)
        if (!leadingZero) userNum = userNum.trimStart('0').ifEmpty { "0" }
        return userNum
    }
}
