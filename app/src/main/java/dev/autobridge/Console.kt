package dev.autobridge

/** How a status line reads: done, waiting, failed or just information (the start screen's log dots). */
object Console {
    enum class Kind { OK, WAIT, FAIL, INFO }

    /** Which kind a line is, from its wording. */
    fun kind(line: String): Kind {
        val l = line.lowercase()
        return when {
            listOf("disconnected", "error", "couldn't", "can't", "failed", "not sent", "not found", "is off", "needs", "must", "took the speakers").any { it in l } -> Kind.FAIL
            listOf("waiting", "looking", "first,", "set the").any { it in l } -> Kind.WAIT
            listOf("connected", "found", "sent", "saved", "reply", "status 0", "status: 0").any { it in l } -> Kind.OK
            else -> Kind.INFO
        }
    }
}
