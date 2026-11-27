package dev.autobridge

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Collects AutoBridge's own log (logcat for this process: Kotlin and native lines) and sends it to
 * the AutoBridge Phone app, which saves it as a file in the folder the owner picked (Phone Sync).
 */
object Logs {
    /** File name, e.g. autobridge-2026-11-24_17-05-31-auto.txt */
    fun name(reason: String, now: Date = Date()): String =
        "autobridge-" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(now) + "-" + reason.lowercase().filter { it.isLetterOrDigit() } + ".txt"

    @Volatile private var capturing = false
    private var dir: File? = null

    /**
     * Writes this process's log (logcat for our pid) to a file as it happens, so it survives Exit,
     * a force close or a crash and goes out with the next log. GM's shared log buffer is so busy
     * that reading it later only reached back a few seconds.
     */
    fun startCapture(context: Context) {
        if (capturing) return
        capturing = true
        val d = File(context.filesDir, "logs").apply { mkdirs() }
        dir = d
        thread(name = "ab-logcat", isDaemon = true) {
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            var first = true
            // logcat can exit (its log service restarted, or it was killed): start it again, or the log stops for good.
            while (true) {
                var p: java.lang.Process? = null
                runCatching {
                    rotate(d)  // a logcat that keeps exiting would otherwise grow the file with headers alone
                    var out = FileWriter(File(d, CURRENT), true)
                    out.write(if (first) "==== AutoBridge $version started ${Date()} (process ${Process.myPid()}) ====\n"
                              else "==== log capture restarted ${Date()} ====\n"); out.flush()
                    // After a restart, only new lines: the buffer's older ones are already in the file.
                    val since = if (first) emptyArray() else arrayOf("-T", "1")
                    first = false
                    p = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "time", "--pid=${Process.myPid()}", *since))
                    p!!.inputStream.bufferedReader().forEachLine { l ->
                        out.write(l); out.write("\n"); out.flush()  // flushed per line: nothing is lost if we die
                        if (rotate(d)) { out.close(); out = FileWriter(File(d, CURRENT), true) }
                    }
                    out.close()
                }
                p?.destroy()  // a write that failed (storage full) leaves logcat blocked; don't start a second beside it
                Thread.sleep(2000)
            }
        }
    }

    /** Past [MAX_BYTES], the current file becomes the previous one (the old previous is deleted). */
    fun rotate(d: File, maxBytes: Long = MAX_BYTES): Boolean {
        val cur = File(d, CURRENT)
        if (cur.length() < maxBytes) return false
        cur.renameTo(File(d, PREVIOUS).apply { delete() })
        return true
    }

    /**
     * Everything the truck has saved (both files, about 4 MB at most), newest app start first so the drive
     * that just happened is at the top. Each app start begins with its "==== AutoBridge … started" line.
     */
    fun saved(d: File): String {
        val sessions = mutableListOf(mutableListOf<String>())
        listOf(PREVIOUS, CURRENT).map { File(d, it) }.filter { it.exists() }.flatMap { it.readLines() }.forEach { l ->
            if (l.startsWith("==== AutoBridge ") && sessions.last().isNotEmpty()) sessions += mutableListOf<String>()
            sessions.last() += l
        }
        return sessions.asReversed().flatten().joinToString("\n")
    }

    fun collect(context: Context, reason: String, header: List<String>): ByteArray {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val top = buildString {
            appendLine("AutoBridge $version log — $reason")
            appendLine("Time: ${Date()}")
            appendLine("Car: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            header.forEach { appendLine(it) }
            appendLine("---- log (saved on the truck, survives restarts; newest app start first, oldest at the bottom) ----")
        }
        val kept = dir?.let { runCatching { saved(it) }.getOrNull() }.orEmpty()
        val log = kept.ifEmpty {
            runCatching {
                val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}"))
                p.inputStream.bufferedReader().readLines().takeLast(5000).joinToString("\n")
            }.getOrElse { "logcat unavailable: $it" }
        }
        return (top + log + "\n").toByteArray()
    }

    private const val CURRENT = "autobridge.log"
    private const val PREVIOUS = "autobridge.1.log"
    /** Two files of this size at most: about 4 MB, several hours of driving. */
    private const val MAX_BYTES = 2L * 1024 * 1024

    /** Header, name line, length line, then the file. Must match LogReceiver in the phone app. */
    @Throws(IOException::class)
    fun send(phoneIp: String, carIp: String?, name: String, bytes: ByteArray) {
        Socket().use { s ->
            carIp?.let { s.bind(InetSocketAddress(it, 0)) }  // leave through the hotspot
            s.connect(InetSocketAddress(phoneIp, Wireless.PHONE_LOG_PORT), 3000)
            s.soTimeout = 10_000
            val out = s.getOutputStream()
            out.write("AUTOBRIDGE-LOG\n$name\n${bytes.size}\n".toByteArray())
            out.write(bytes)
            out.flush()
            if (s.getInputStream().read() != 'K'.code) throw IOException("the phone app did not confirm")
        }
    }
}
