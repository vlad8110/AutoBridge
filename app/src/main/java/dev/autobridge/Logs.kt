package dev.autobridge

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collects AutoBridge's own log (logcat for this process: Kotlin and native lines) and sends it to
 * the AutoBridge Phone app, which saves it as a file in the folder the owner picked (Phone Sync).
 */
object Logs {
    /** File name, e.g. autobridge-2026-11-24_17-05-31-auto.txt */
    fun name(reason: String, now: Date = Date()): String =
        "autobridge-" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(now) + "-" + reason.lowercase().filter { it.isLetterOrDigit() } + ".txt"

    fun collect(context: Context, reason: String, header: List<String>): ByteArray {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val top = buildString {
            appendLine("AutoBridge $version log — $reason")
            appendLine("Time: ${Date()}")
            appendLine("Car: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            header.forEach { appendLine(it) }
            appendLine("---- log ----")
        }
        val log = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}"))
            p.inputStream.bufferedReader().readLines().takeLast(5000).joinToString("\n")
        }.getOrElse { "logcat unavailable: $it" }
        return (top + log + "\n").toByteArray()
    }

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
