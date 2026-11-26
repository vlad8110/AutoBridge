package dev.autobridge

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan

/** Renders the connection steps like a Linux boot/bash console (Catppuccin Mocha text colours on black). */
object Console {
    val BACKGROUND = 0xFF000000.toInt()  // black, like the rest of AutoBridge (was Catppuccin navy)
    private val TEXT = 0xFFcdd6f4.toInt()
    private val GREEN = 0xFFa6e3a1.toInt()
    private val YELLOW = 0xFFf9e2af.toInt()
    private val RED = 0xFFf38ba8.toInt()
    private val BLUE = 0xFF89b4fa.toInt()
    private val PURPLE = 0xFFcba6f7.toInt()
    private val GRAY = 0xFF6c7086.toInt()

    enum class Kind(val tag: String) { OK("  OK  "), WAIT(" WAIT "), FAIL(" FAIL "), INFO(" INFO ") }

    /** Which status tag a step gets, from its wording. */
    fun kind(line: String): Kind {
        val l = line.lowercase()
        return when {
            listOf("disconnected", "error", "couldn't", "can't", "failed", "not sent", "not found", "is off", "needs", "must", "took the speakers").any { it in l } -> Kind.FAIL
            listOf("waiting", "looking", "first,", "set the").any { it in l } -> Kind.WAIT
            listOf("connected", "found", "sent", "saved", "reply", "status 0", "status: 0").any { it in l } -> Kind.OK
            else -> Kind.INFO
        }
    }

    fun render(status: String, cursorOn: Boolean): CharSequence = SpannableStringBuilder().apply {
        fun add(text: String, color: Int, bold: Boolean = false) {
            val start = length
            append(text)
            setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bold) setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        fun prompt() { add("autobridge", GREEN, true); add("@", TEXT); add("truck", BLUE, true); add(":", TEXT); add("~", PURPLE, true); add("$ ", TEXT) }

        prompt(); add("./android-auto --wireless\n", TEXT)
        for (line in status.lines().filter { it.isNotBlank() }) {
            val k = kind(line)
            add("[", GRAY)
            add(k.tag, when (k) { Kind.OK -> GREEN; Kind.WAIT -> YELLOW; Kind.FAIL -> RED; Kind.INFO -> BLUE }, true)
            add("] ", GRAY)
            add(line + "\n", if (k == Kind.FAIL) RED else TEXT)
        }
        prompt(); add(if (cursorOn) "█" else " ", TEXT)
    }
}
