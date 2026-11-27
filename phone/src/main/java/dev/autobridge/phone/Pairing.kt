package dev.autobridge.phone

import java.io.DataInputStream
import java.io.IOException
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Before Android Auto starts on port 5300, the truck and the phone each prove they know the truck's pairing code
 * (shown on the truck's settings page, typed once into AutoBridge Phone), so another device on the hotspot can't
 * pose as either one. Everyone on the hotspot knows its password, so the password only counts until the phone has
 * the code; once the truck has paired by code it never accepts the password again. Each proof covers the sender's
 * own address, so a device in the middle can't pass the other side's proof along.
 * The same code also signs the phone's Wi-Fi details (port 5303) and encrypts logs and screenshots (port 5301).
 * The same file is in app/src/main/java/dev/autobridge/Pairing.kt: keep the two identical.
 */
object Pairing {
    private val BY_PASSWORD = "AB1".toByteArray()
    private val BY_CODE = "AB2".toByteArray()
    private const val NONCE = 16
    private const val PROOF = 32
    /** No 0/O, 1/I/L: easy to read off a screen and type. 12 of these are about 59 bits, too many to guess. */
    private const val LETTERS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val CODE_LENGTH = 12

    /** A new truck's pairing code, as shown: "K7QM-3XRP-9TWD". */
    fun newCode(): String = SecureRandom().let { r -> (1..CODE_LENGTH).map { LETTERS[r.nextInt(LETTERS.length)] }.joinToString("") }.chunked(4).joinToString("-")

    /** What the owner typed, as a code (any case, spaces or dashes), or null if it can't be one. */
    fun normalize(typed: String): String? = typed.uppercase().filter { it.isLetterOrDigit() }
        .takeIf { it.length == CODE_LENGTH && it.all { c -> c in LETTERS } }?.chunked(4)?.joinToString("-")

    /**
     * Phone side, right after accepting. With a [code] only a truck that knows it passes; with none (not typed yet)
     * any saved hotspot password does. Returns the secret the truck proved, or null (close the socket).
     */
    fun phone(s: Socket, code: String?, passwords: Collection<String>): String? {
        val input = DataInputStream(s.getInputStream()); val out = s.getOutputStream()
        val phoneNonce = nonce()
        out.write((if (code != null) BY_CODE else BY_PASSWORD) + phoneNonce); out.flush()
        val truckNonce = ByteArray(NONCE).also(input::readFully)
        val theirs = ByteArray(PROOF).also(input::readFully)
        val secrets = if (code != null) listOf(code) else passwords
        val secret = secrets.firstOrNull { it.isNotEmpty() && same(theirs, proof(it, "truck", phoneNonce, truckNonce, s.inetAddress.address)) } ?: return null
        out.write(proof(secret, "phone", phoneNonce, truckNonce, s.localAddress.address)); out.flush()
        return secret
    }

    enum class Result { CODE, PASSWORD, REFUSED }

    /** Truck side, right after connecting. [byPassword]: still accept the hotspot password (the truck never paired by code). */
    fun truck(s: Socket, code: String, password: String, byPassword: Boolean): Result {
        val input = DataInputStream(s.getInputStream()); val out = s.getOutputStream()
        val hello = ByteArray(BY_CODE.size + NONCE).also(input::readFully)
        val byCode = hello.copyOf(BY_CODE.size).contentEquals(BY_CODE)
        val legacy = hello.copyOf(BY_PASSWORD.size).contentEquals(BY_PASSWORD) && byPassword && password.isNotEmpty()
        if (!byCode && !legacy) return Result.REFUSED
        val secret = if (byCode) code else password
        val phoneNonce = hello.copyOfRange(BY_CODE.size, hello.size)
        val truckNonce = nonce()
        out.write(truckNonce + proof(secret, "truck", phoneNonce, truckNonce, s.localAddress.address)); out.flush()
        val theirs = ByteArray(PROOF).also(input::readFully)
        if (!same(theirs, proof(secret, "phone", phoneNonce, truckNonce, s.inetAddress.address))) return Result.REFUSED
        return if (byCode) Result.CODE else Result.PASSWORD
    }

    /** The phone's Wi-Fi details answer to [nonce] from the truck, with a last line that proves the phone knows [code]. */
    fun signSetup(code: String, nonce: ByteArray, message: String) = message + "MAC " + hex(mac(code, "setup", nonce, message.toByteArray())) + "\n"

    /** The details without the proof line, and whether that line proved [code]. Unsigned answers come back as they are. */
    fun checkSetup(code: String, nonce: ByteArray, reply: String): Pair<String, Boolean> {
        val at = reply.lastIndexOf("MAC ")
        if (at < 0 || (at > 0 && reply[at - 1] != '\n')) return reply to false
        val body = reply.substring(0, at)
        return body to same(reply.substring(at + 4).trim().toByteArray(), hex(mac(code, "setup", nonce, body.toByteArray())).toByteArray())
    }

    /** A log or screenshot, encrypted and sealed with the code ([name] is sealed in too, so it can't be swapped). */
    fun seal(code: String, name: String, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        return iv + cipher(Cipher.ENCRYPT_MODE, code, iv, name).doFinal(plain)
    }

    @Throws(IOException::class)
    fun open(code: String, name: String, sealed: ByteArray): ByteArray = try {
        if (sealed.size < SEAL_EXTRA) throw IOException("sealed file too short")
        cipher(Cipher.DECRYPT_MODE, code, sealed.copyOf(12), name).doFinal(sealed, 12, sealed.size - 12)
    } catch (e: java.security.GeneralSecurityException) { throw IOException("not sealed with this phone's pairing code") }

    /** Extra bytes [seal] adds: the 12-byte IV and the 16-byte tag. */
    const val SEAL_EXTRA = 28

    fun nonce() = ByteArray(NONCE).also { SecureRandom().nextBytes(it) }

    private fun cipher(mode: Int, code: String, iv: ByteArray, name: String) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(mac(code, "logs"), "AES"), GCMParameterSpec(128, iv)); updateAAD(name.toByteArray())
    }

    private fun mac(secret: String, vararg parts: Any): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        parts.forEach { update(if (it is String) it.toByteArray() else it as ByteArray) }
        doFinal()
    }

    private fun proof(secret: String, who: String, phoneNonce: ByteArray, truckNonce: ByteArray, address: ByteArray) =
        mac(secret, who, phoneNonce, truckNonce, if (address.size == 16) address.copyOfRange(12, 16) else address)  // an IPv4-mapped IPv6 address counts as its IPv4

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun same(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)
}
