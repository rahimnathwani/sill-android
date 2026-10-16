package sill.protocol

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/** Minimal DER, enough to build and read the certificates Sill uses. */
object Der {
    class Tlv(val tag: Int, val start: Int, val contentStart: Int, val end: Int)

    fun read(b: ByteArray, at: Int): Tlv {
        val tag = b[at].toInt() and 0xFF
        var i = at + 1
        var n = b[i++].toInt() and 0xFF
        if (n and 0x80 != 0) {
            val k = n and 0x7F
            require(k in 1..4) { "bad DER length" }
            n = 0
            repeat(k) { n = (n shl 8) or (b[i++].toInt() and 0xFF) }
        }
        require(i + n <= b.size) { "DER runs past its end" }
        return Tlv(tag, at, i, i + n)
    }

    private fun length(n: Int): ByteArray {
        if (n < 0x80) return byteArrayOf(n.toByte())
        val bytes = ArrayList<Byte>()
        var v = n
        while (v > 0) { bytes.add(0, (v and 0xFF).toByte()); v = v ushr 8 }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
    }

    fun tlv(tag: Int, content: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + length(content.size) + content
    fun seq(vararg parts: ByteArray): ByteArray = tlv(0x30, parts.fold(ByteArray(0)) { a, p -> a + p })
    fun set(vararg parts: ByteArray): ByteArray = tlv(0x31, parts.fold(ByteArray(0)) { a, p -> a + p })
    fun oid(bytes: ByteArray): ByteArray = tlv(0x06, bytes)
    fun bitString(bytes: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0) + bytes)

    fun integer(raw: ByteArray): ByteArray {
        var b = if (raw.isEmpty()) byteArrayOf(0) else raw
        while (b.size > 1 && b[0].toInt() == 0 && (b[1].toInt() and 0x80) == 0) b = b.copyOfRange(1, b.size)
        if (b[0].toInt() and 0x80 != 0) b = byteArrayOf(0) + b
        return tlv(0x02, b)
    }

    val ECDSA_WITH_SHA256 = hex("2a8648ce3d040302")
    val COMMON_NAME = hex("550403")
}

/**
 * This device's identity: a P-256 key and a self-signed certificate made from it, presented in
 * every TLS handshake with a Mac. The Mac pins the key (SHA-256 of its SPKI) once paired.
 *
 * The certificate mirrors RemoteIdentity.swift's: v3, no extensions, random serial, issuer =
 * subject = a CN of 16 random hex characters, valid 2026-01-01 to 9999-12-31, ECDSA with SHA-256.
 */
class DeviceIdentity private constructor(
    val privateKey: PrivateKey,
    val publicKey: PublicKey,
    val certificate: X509Certificate,
) {
    /** The uncompressed point, 65 bytes. */
    val point: ByteArray = uncompressed(publicKey as ECPublicKey)
    val fingerprint: ByteArray = Spki.fingerprint(Spki.der(point))

    /** PKCS#8 bytes of the private key and X.509 SPKI bytes of the public, for storage. */
    fun privateKeyBytes(): ByteArray = privateKey.encoded
    fun publicKeyBytes(): ByteArray = publicKey.encoded

    companion object {
        fun generate(): DeviceIdentity {
            val g = KeyPairGenerator.getInstance("EC")
            g.initialize(ECGenParameterSpec("secp256r1"), Crypto.random)
            val kp = g.generateKeyPair()
            return from(kp.private, kp.public)
        }

        fun load(privateBytes: ByteArray, publicBytes: ByteArray): DeviceIdentity {
            val kf = KeyFactory.getInstance("EC")
            return from(kf.generatePrivate(PKCS8EncodedKeySpec(privateBytes)),
                        kf.generatePublic(X509EncodedKeySpec(publicBytes)))
        }

        private fun from(priv: PrivateKey, pub: PublicKey): DeviceIdentity {
            val point = uncompressed(pub as ECPublicKey)
            val der = certificate(priv, point)
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            return DeviceIdentity(priv, pub, cert)
        }

        fun uncompressed(pub: ECPublicKey): ByteArray {
            fun fixed(v: BigInteger): ByteArray {
                val b = v.toByteArray()
                return when {
                    b.size == 32 -> b
                    b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
                    else -> ByteArray(32 - b.size) + b
                }
            }
            return byteArrayOf(4) + fixed(pub.w.affineX) + fixed(pub.w.affineY)
        }

        fun certificate(key: PrivateKey, point: ByteArray): ByteArray {
            val cn = Crypto.randomBytes(8).toHex()
            val serial = Crypto.randomBytes(16)
            serial[0] = ((serial[0].toInt() and 0x7F) or 0x01).toByte()
            val name = Der.seq(Der.set(Der.seq(Der.oid(Der.COMMON_NAME), Der.tlv(0x0C, cn.toByteArray()))))
            val algorithm = Der.seq(Der.oid(Der.ECDSA_WITH_SHA256))
            val tbs = Der.seq(
                Der.tlv(0xA0, Der.integer(byteArrayOf(2))),
                Der.integer(serial),
                algorithm,
                name,
                Der.seq(Der.tlv(0x17, "260101000000Z".toByteArray()), Der.tlv(0x18, "99991231235959Z".toByteArray())),
                name,
                Spki.der(point),
            )
            val s = Signature.getInstance("SHA256withECDSA")
            s.initSign(key)
            s.update(tbs)
            return Der.seq(tbs, algorithm, Der.bitString(s.sign()))
        }
    }
}
