package dev.autobridge.phone

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.IOException
import java.io.InputStream

/**
 * Saves log files the car sends. Into the folder the owner picked (Phone Sync), or into
 * Download/AutoBridge until a folder is picked.
 */
object LogSaver {
    private const val MAX_BYTES = 20 * 1024 * 1024
    private val NAME = Regex("[A-Za-z0-9._-]{1,100}")

    fun folder(context: Context): Uri? = prefs(context).getString("folder", null)?.let(Uri::parse)
    fun setFolder(context: Context, uri: Uri) = prefs(context).edit().putString("folder", uri.toString()).apply()

    /**
     * Reads one log off [input]: "AUTOBRIDGE-LOG\n", the file name, its length, then the bytes
     * (Logs.send in the car app). With the truck's pairing [code] it must come encrypted with it ("AUTOBRIDGE-LOG2"),
     * which also proves it's from the truck. Returns the name it was saved under.
     */
    @Throws(IOException::class)
    fun receive(context: Context, input: InputStream, code: String?): String {
        val (name, size) = header(input, code)
        // Grows as bytes really arrive: a peer that only claims 20 MB can't make us reserve it.
        val out = java.io.ByteArrayOutputStream(minOf(size, 64 * 1024))
        val buf = ByteArray(64 * 1024)
        while (out.size() < size) {
            val n = input.read(buf, 0, minOf(buf.size, size - out.size()))
            if (n < 0) throw IOException("log cut short")
            out.write(buf, 0, n)
        }
        save(context, name, out.toByteArray().let { if (code != null) Pairing.open(code, name, it) else it })
        return name
    }

    /** Checks what the car (or anyone else on the hotspot) sent before trusting it. */
    @Throws(IOException::class)
    fun header(input: InputStream, code: String? = null): Pair<String, Int> {
        fun line(): String {
            val b = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0 || b.length > 200) throw IOException("bad log header")
                if (c == '\n'.code) return b.toString()
                b.append(c.toChar())
            }
        }
        val kind = line()
        if (kind != (if (code != null) "AUTOBRIDGE-LOG2" else "AUTOBRIDGE-LOG"))
            throw IOException(if (kind == "AUTOBRIDGE-LOG" && code != null) "not encrypted with the pairing code" else "not an AutoBridge log")
        val name = line()
        val size = line().toIntOrNull()
        if (!NAME.matches(name) || !(name.endsWith(".txt") || name.endsWith(".png"))) throw IOException("bad log name")
        if (size == null || size !in 0..MAX_BYTES + (if (code != null) Pairing.SEAL_EXTRA else 0)) throw IOException("bad log size")
        return name to size
    }

    private fun save(context: Context, name: String, bytes: ByteArray) {
        val r = context.contentResolver
        val tree = folder(context)
        val uri = if (tree != null) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            DocumentsContract.createDocument(r, parent, mime(name), name)
        } else {
            r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime(name))
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/AutoBridge")
            })
        } ?: throw IOException("could not create the file")
        (r.openOutputStream(uri) ?: throw IOException("could not open the file")).use { it.write(bytes) }
    }

    private fun mime(name: String) = if (name.endsWith(".png")) "image/png" else "text/plain"

    private fun prefs(context: Context) = context.getSharedPreferences("logs", Context.MODE_PRIVATE)
}
