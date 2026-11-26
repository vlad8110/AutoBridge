package dev.autobridge.phone

import java.io.DataInputStream
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Before Android Auto starts on port 5300, the truck and the phone each prove they know the truck's hotspot
 * password, so another device on the hotspot can't pose as either one. Each proof covers the sender's own
 * address, so a device in the middle can't pass the other side's proof along.
 * The same file is in app/src/main/java/dev/autobridge/Pairing.kt: keep the two identical.
 */
object Pairing {
    private val HELLO = "AB1".toByteArray()
    private const val NONCE = 16
    private const val PROOF = 32

    /** Phone side, right after accepting: the password the truck proved, or null (close the socket). */
    fun phone(s: Socket, passwords: Collection<String>): String? {
        val input = DataInputStream(s.getInputStream()); val out = s.getOutputStream()
        val phoneNonce = nonce()
        out.write(HELLO + phoneNonce); out.flush()
        val truckNonce = ByteArray(NONCE).also(input::readFully)
        val theirs = ByteArray(PROOF).also(input::readFully)
        val pw = passwords.firstOrNull { it.isNotEmpty() && same(theirs, proof(it, "truck", phoneNonce, truckNonce, s.inetAddress.address)) } ?: return null
        out.write(proof(pw, "phone", phoneNonce, truckNonce, s.localAddress.address)); out.flush()
        return pw
    }

    /** Truck side, right after connecting: true once the phone proved it knows [password]. */
    fun truck(s: Socket, password: String): Boolean {
        val input = DataInputStream(s.getInputStream()); val out = s.getOutputStream()
        val hello = ByteArray(HELLO.size + NONCE).also(input::readFully)
        if (!hello.copyOf(HELLO.size).contentEquals(HELLO)) return false
        val phoneNonce = hello.copyOfRange(HELLO.size, hello.size)
        val truckNonce = nonce()
        out.write(truckNonce + proof(password, "truck", phoneNonce, truckNonce, s.localAddress.address)); out.flush()
        val theirs = ByteArray(PROOF).also(input::readFully)
        return same(theirs, proof(password, "phone", phoneNonce, truckNonce, s.inetAddress.address))
    }

    private fun proof(password: String, who: String, phoneNonce: ByteArray, truckNonce: ByteArray, address: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(password.toByteArray(), "HmacSHA256"))
            update(who.toByteArray()); update(phoneNonce); update(truckNonce)
            doFinal(if (address.size == 16) address.copyOfRange(12, 16) else address)  // an IPv4-mapped IPv6 address counts as its IPv4
        }

    private fun nonce() = ByteArray(NONCE).also { SecureRandom().nextBytes(it) }
    private fun same(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)
}
