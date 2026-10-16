package sill.protocol

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * TLS 1.3 to a Mac's door (RemoteTLS.swift): both ends present self-signed P-256 certificates and
 * trust is the pin, the SHA-256 of the Mac's SPKI, never a CA or a host name. ALPN `sill/1` for a
 * session, `sill-pair/1` for one pairing exchange.
 */
object Tls {
    const val SESSION_ALPN = "sill/1"
    const val PAIRING_ALPN = "sill-pair/1"
    const val SERVER_NAME = "sill"
    const val DIAL_TIMEOUT_MS = 10_000

    /** The Mac presented another key than the one this device paired with. */
    class PinRefused(val presented: ByteArray) : IOException("the Mac presented another key")

    class AlpnRefused(val got: String?) : IOException("the Mac answered ALPN ${got ?: "none"}")

    class Connection(val socket: Socket, val macFingerprint: ByteArray?)

    /**
     * Dials [host]:[port] and completes the handshake. With [pin] the Mac's key must match it or
     * [PinRefused] is thrown before a byte is sent; without one any key is taken (pairing by code,
     * where the proofs bind both keys, and a door that lets any device in).
     */
    fun connect(host: String, port: Int, identity: DeviceIdentity, alpn: String, pin: ByteArray?,
                timeoutMs: Int = DIAL_TIMEOUT_MS): Connection {
        val trust = PinTrust(pin)
        val ctx = SSLContext.getInstance("TLSv1.3")
        ctx.init(arrayOf(IdentityKeys(identity)), arrayOf(trust), Crypto.random)
        val raw = Socket()
        try {
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(host, port), timeoutMs)
            raw.soTimeout = timeoutMs
            val s = ctx.socketFactory.createSocket(raw, SERVER_NAME, port, true) as SSLSocket
            val p = s.sslParameters
            p.protocols = arrayOf("TLSv1.3")
            p.applicationProtocols = arrayOf(alpn)
            p.serverNames = listOf(SNIHostName(SERVER_NAME))
            p.endpointIdentificationAlgorithm = null
            s.sslParameters = p
            try {
                s.startHandshake()
            } catch (e: IOException) {
                trust.refused?.let { throw PinRefused(it) }
                throw e
            }
            trust.refused?.let { s.close(); throw PinRefused(it) }
            val got = s.applicationProtocol
            if (got != alpn) { s.close(); throw AlpnRefused(got) }
            s.soTimeout = 0
            return Connection(s, trust.presented)
        } catch (e: Exception) {
            try { raw.close() } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * Which key a door presents, without asking it for anything: a pairing connection (ALPN
     * sill-pair/1) closed straight after its handshake, which a Mac neither counts as a refusal
     * nor answers. Null if the door could not be reached or did not speak TLS.
     */
    fun fingerprintOf(host: String, port: Int, identity: DeviceIdentity, timeoutMs: Int = DIAL_TIMEOUT_MS): ByteArray? = try {
        val c = connect(host, port, identity, PAIRING_ALPN, null, timeoutMs)
        try { c.socket.close() } catch (_: Exception) {}
        c.macFingerprint
    } catch (_: Exception) { null }

    /** A plain TCP connection: SillHost without --pairing, and development builds. */
    fun connectPlain(host: String, port: Int, timeoutMs: Int = DIAL_TIMEOUT_MS): Connection {
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        return Connection(raw, null)
    }

    private class IdentityKeys(private val identity: DeviceIdentity) : X509ExtendedKeyManager() {
        private val alias = "sill"
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(identity.certificate)
        override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey
    }

    private class PinTrust(private val pin: ByteArray?) : X509ExtendedTrustManager() {
        @Volatile var presented: ByteArray? = null
        @Volatile var refused: ByteArray? = null

        private fun check(chain: Array<out X509Certificate>?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
            val fp = Spki.fingerprint(Spki.fromCertificate(leaf.encoded))
            presented = fp
            if (pin != null && !Crypto.equal(fp, pin)) {
                refused = fp
                throw CertificateException("pin refused")
            }
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("client")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = throw CertificateException("client")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = throw CertificateException("client")
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}
