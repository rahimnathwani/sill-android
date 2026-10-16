package sill.protocol

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** base64url without padding, as Sill writes fingerprints, proofs and tags. */
object B64u {
    fun encode(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun decode(s: String): ByteArray? = try {
        if (!s.all { it.isLetterOrDigit() || it == '-' || it == '_' }) null
        else Base64.getUrlDecoder().decode(s)
    } catch (_: IllegalArgumentException) { null }
}

object Crypto {
    val random = SecureRandom()

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    /** Constant-time comparison. */
    fun equal(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    /** PBKDF2-HMAC-SHA256 over ASCII [password]. */
    fun pbkdf2(password: String, salt: ByteArray, rounds: Int, length: Int): ByteArray {
        require(password.all { it.code < 128 }) { "ASCII only" }
        return try {
            val spec = PBEKeySpec(password.toCharArray(), salt, rounds, length * 8)
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } catch (_: Exception) {
            pbkdf2ByHand(password.toByteArray(Charsets.US_ASCII), salt, rounds, length)
        }
    }

    internal fun pbkdf2ByHand(password: ByteArray, salt: ByteArray, rounds: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val out = ByteArray(length)
        var block = 1
        var off = 0
        while (off < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (r in 1 until rounds) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val n = minOf(t.size, length - off)
            System.arraycopy(t, 0, out, off, n)
            off += n
            block++
        }
        return out
    }
}

/** A P-256 SubjectPublicKeyInfo and its SHA-256: the fingerprint every pin compares. */
object Spki {
    /** The DER prefix of a P-256 SPKI, before the 65-byte uncompressed point. */
    val P256_PREFIX: ByteArray = hex("3059301306072a8648ce3d020106082a8648ce3d030107034200")

    fun der(point: ByteArray): ByteArray = P256_PREFIX + point

    fun fingerprint(spkiDer: ByteArray): ByteArray = Crypto.sha256(spkiDer)

    /**
     * A certificate's SubjectPublicKeyInfo, read from its DER: the 6th field of TBSCertificate
     * after the optional [0] version (as Scripts/sillclient.py reads it).
     */
    fun fromCertificate(der: ByteArray): ByteArray {
        val cert = Der.read(der, 0)
        val tbs = Der.read(der, cert.contentStart)
        val fields = ArrayList<Der.Tlv>()
        var i = tbs.contentStart
        while (i < tbs.end) {
            val f = Der.read(der, i)
            fields.add(f)
            i = f.end
        }
        val list = if (fields.firstOrNull()?.tag == 0xA0) fields.drop(1) else fields
        require(list.size >= 6) { "not a certificate" }
        val spki = list[5]
        return der.copyOfRange(spki.start, spki.end)
    }
}

/** The Mac ID: Crockford base32 of the first 80 bits of the Mac's key fingerprint. */
object MacId {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun make(fingerprint: ByteArray): String {
        val n = BigInteger(1, fingerprint.copyOfRange(0, 10))
        val sb = StringBuilder()
        for (i in 0 until 16) {
            val v = n.shiftRight(75 - 5 * i).toInt() and 31
            sb.append(ALPHABET[v])
        }
        return sb.toString()
    }
}

/** The 12-digit pairing code: 11 random digits and a Damm check digit. */
object PairingCode {
    private val TABLE = arrayOf(
        intArrayOf(0, 3, 1, 7, 5, 9, 8, 6, 4, 2), intArrayOf(7, 0, 9, 2, 1, 5, 4, 8, 6, 3),
        intArrayOf(4, 2, 0, 6, 8, 7, 1, 3, 5, 9), intArrayOf(1, 7, 5, 0, 9, 8, 3, 4, 2, 6),
        intArrayOf(6, 1, 2, 3, 0, 4, 5, 9, 7, 8), intArrayOf(3, 6, 7, 4, 2, 0, 9, 5, 8, 1),
        intArrayOf(5, 8, 6, 9, 7, 2, 0, 1, 3, 4), intArrayOf(8, 9, 4, 5, 3, 6, 2, 0, 1, 7),
        intArrayOf(9, 4, 3, 8, 6, 1, 7, 2, 0, 5), intArrayOf(2, 5, 8, 1, 4, 3, 6, 7, 9, 0),
    )

    fun damm(digits: String): Int {
        var i = 0
        for (c in digits) i = TABLE[i][c - '0']
        return i
    }

    enum class Problem { LENGTH, CHECK }

    /** The digits of [input] (spaces and dashes dropped), or why they are not a code. */
    fun check(input: String): Pair<String?, Problem?> {
        val digits = input.filter { it.isDigit() && it.code < 128 }
        if (digits.length != 12 || input.any { !(it.isDigit() || it == ' ' || it == '-') }) return null to Problem.LENGTH
        if (damm(digits) != 0) return null to Problem.CHECK
        return digits to null
    }
}

/** Pairing proofs (Pairing.swift's PairingProof). */
object PairingProof {
    const val ROUNDS = 600_000
    private const val LABEL = "sill-pair-v1"

    fun codeKey(code: String, macFingerprint: ByteArray): ByteArray =
        Crypto.pbkdf2(code, LABEL.toByteArray() + macFingerprint, ROUNDS, 32)

    fun deviceProof(key: ByteArray, deviceFp: ByteArray, macFp: ByteArray): ByteArray =
        Crypto.hmacSha256(key, "$LABEL device".toByteArray() + byteArrayOf(0) + deviceFp + macFp)

    fun macProof(key: ByteArray, macFp: ByteArray, deviceFp: ByteArray): ByteArray =
        Crypto.hmacSha256(key, "$LABEL mac".toByteArray() + byteArrayOf(0) + macFp + deviceFp)
}

/** The Bonjour TXT record's `r`: recognises a saved Mac whatever its Bonjour name. */
object RecognitionTag {
    fun matches(tag: String, recognitionKey: ByteArray): Boolean {
        val bytes = B64u.decode(tag) ?: return false
        if (bytes.size != 12) return false
        val expected = mac(recognitionKey, bytes.copyOfRange(0, 6))
        return Crypto.equal(expected, bytes.copyOfRange(6, 12))
    }

    fun make(recognitionKey: ByteArray, nonce: ByteArray = Crypto.randomBytes(6)): String =
        B64u.encode(nonce + mac(recognitionKey, nonce))

    private fun mac(key: ByteArray, nonce: ByteArray): ByteArray =
        Crypto.hmacSha256(key, "sill-tag-v1".toByteArray() + nonce).copyOfRange(0, 6)
}

internal fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
