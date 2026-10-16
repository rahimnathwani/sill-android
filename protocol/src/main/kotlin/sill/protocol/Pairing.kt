package sill.protocol

import java.io.IOException

/** A Mac this device has paired with: what the session pins and the TXT tag recognises. */
data class PairedMac(
    val macID: String,
    val name: String,
    /** SHA-256 of the Mac's SPKI, 32 bytes. */
    val fingerprint: ByteArray,
    /** 32 bytes; recognises the Mac's Bonjour tag. Null for a Mac that sent none. */
    val recognitionKey: ByteArray?,
    var lastHost: String? = null,
    var lastPort: Int? = null,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "macID" to macID, "name" to name, "fingerprint" to B64u.encode(fingerprint),
        "recognitionKey" to recognitionKey?.let { B64u.encode(it) }, "lastHost" to lastHost, "lastPort" to lastPort,
    )

    override fun equals(other: Any?) = other is PairedMac && other.macID == macID
    override fun hashCode() = macID.hashCode()

    companion object {
        fun fromJson(m: Map<String, Any?>): PairedMac? {
            val fp = m.str("fingerprint")?.let { B64u.decode(it) } ?: return null
            if (fp.size != 32) return null
            return PairedMac(
                macID = m.str("macID") ?: return null,
                name = m.str("name") ?: "Mac",
                fingerprint = fp,
                recognitionKey = m.str("recognitionKey")?.let { B64u.decode(it) }?.takeIf { it.size == 32 },
                lastHost = m.str("lastHost"),
                lastPort = m.long("lastPort")?.toInt(),
            )
        }
    }
}

/** Kind 20, the Mac's answer to a pairing request (Remote.swift's PairResult). */
class PairResult(val raw: Map<String, Any?>) {
    val ok get() = raw.bool("ok") == true
    val proof get() = raw.str("proof")
    val macID get() = raw.str("macID")
    val name get() = raw.str("name")
    val recognitionKey get() = raw.str("recognitionKey")
    val reason get() = raw.str("reason")
    val triesLeft get() = raw.long("triesLeft")?.toInt()
    val retryAfter get() = raw.double("retryAfter")
    val method get() = raw.str("method")
    val message get() = raw.str("message")

    companion object {
        const val SHOWN = "shown"
        const val OPEN_ON_MAC = "openOnMac"
        const val LOCKED = "locked"
        const val CODE = "code"
        const val CLOSED = "closed"
        const val EXPIRED = "expired"
        const val STOPPED = "stopped"
        const val BUSY = "busy"
    }
}

/**
 * The pairing exchanges (docs/home-pairing-plan.md): one `sill-pair/1` connection per request,
 * exactly one kind 19 out and one kind 20 back, then the Mac closes it.
 */
object Pairing {
    class Failed(message: String) : IOException(message)

    class AskAnswer(val result: PairResult, val macFingerprint: ByteArray)

    /** The device's "pair me": the Mac opens its pairing window and answers `shown` (or why not). */
    fun ask(host: String, port: Int, identity: DeviceIdentity, deviceName: String, model: String?,
            pin: ByteArray? = null): AskAnswer {
        val (r, fp) = exchange(host, port, identity, pin, request("ask", "", deviceName, model))
        return AskAnswer(r, fp)
    }

    /** The device's Cancel of an ask it made: the Mac closes the window it opened. */
    fun cancel(host: String, port: Int, identity: DeviceIdentity, deviceName: String, model: String?, pin: ByteArray?) {
        try { exchange(host, port, identity, pin, request("cancel", "", deviceName, model)) } catch (_: Exception) {}
    }

    /**
     * Pairs with the 12 typed digits, pinned to [pin] (the key the ask saw) when known. Returns the
     * paired Mac once its proof checks, else throws [Failed] with the Mac's reason, or returns the
     * refusal so the caller can show tries left.
     */
    fun pairWithCode(host: String, port: Int, identity: DeviceIdentity, deviceName: String, model: String?,
                     code: String, pin: ByteArray?): Pair<PairedMac?, PairResult> {
        // The key depends on the Mac's fingerprint. With a pin (the ask saw the key) it is derived
        // before dialing, so the slow PBKDF2 never runs against the Mac's 10 s pairing deadline.
        val early = pin?.let { PairingProof.codeKey(code, it) }
        val conn = Tls.connect(host, port, identity, Tls.PAIRING_ALPN, pin)
        conn.socket.use { s ->
            val macFp = conn.macFingerprint ?: throw Failed("no Mac key")
            val key = early ?: PairingProof.codeKey(code, macFp)
            val proof = PairingProof.deviceProof(key, identity.fingerprint, macFp)
            s.getOutputStream().write(Wire.encodeJson(Kind.PAIR_REQUEST, requestMap("code", B64u.encode(proof), deviceName, model)))
            s.getOutputStream().flush()
            val r = readResult(s)
            return finish(r, key, macFp, identity) to r
        }
    }

    /** Pairs with a `sill://pair` link (the Mac's QR code), pinned to the link's key. */
    fun pairWithLink(host: String, port: Int, identity: DeviceIdentity, deviceName: String, model: String?,
                     link: PairLink): Pair<PairedMac?, PairResult> {
        val proof = PairingProof.deviceProof(link.secret, identity.fingerprint, link.fingerprint)
        val (r, macFp) = exchange(host, port, identity, link.fingerprint,
            requestMap("qr", B64u.encode(proof), deviceName, model))
        return finish(r, link.secret, macFp, identity) to r
    }

    private fun finish(r: PairResult, key: ByteArray, macFp: ByteArray, identity: DeviceIdentity): PairedMac? {
        if (!r.ok) return null
        val want = PairingProof.macProof(key, macFp, identity.fingerprint)
        val got = r.proof?.let { B64u.decode(it) } ?: throw Failed("The Mac sent no proof.")
        if (!Crypto.equal(got, want)) throw Failed("The Mac's proof does not check.")
        if (r.macID != MacId.make(macFp)) throw Failed("The Mac's ID does not match its key.")
        return PairedMac(
            macID = r.macID!!,
            name = r.name ?: "Mac",
            fingerprint = macFp,
            recognitionKey = r.recognitionKey?.let { B64u.decode(it) }?.takeIf { it.size == 32 },
        )
    }

    private fun request(method: String, proof: String, name: String, model: String?) =
        requestMap(method, proof, name, model)

    private fun requestMap(method: String, proof: String, name: String, model: String?): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("v" to 1, "method" to method, "proof" to proof, "name" to name.take(64))
        if (model != null) m["model"] = model
        return m
    }

    private fun exchange(host: String, port: Int, identity: DeviceIdentity, pin: ByteArray?,
                         req: Map<String, Any?>): Pair<PairResult, ByteArray> {
        val conn = Tls.connect(host, port, identity, Tls.PAIRING_ALPN, pin)
        conn.socket.use { s ->
            s.getOutputStream().write(Wire.encodeJson(Kind.PAIR_REQUEST, req))
            s.getOutputStream().flush()
            return readResult(s) to (conn.macFingerprint ?: throw Failed("no Mac key"))
        }
    }

    private fun readResult(s: java.net.Socket): PairResult {
        s.soTimeout = 20_000
        val m = MessageReader(s.getInputStream()).next() ?: throw Failed("The Mac closed the connection.")
        if (m.kind != Kind.PAIR_RESULT) throw Failed("The Mac answered with kind ${m.kind}.")
        val map = Json.parseObject(m.payload) ?: throw Failed("The Mac's answer did not parse.")
        return PairResult(map)
    }

    /** What to tell the person about a refusal. */
    fun describe(r: PairResult, macName: String): String {
        r.message?.takeIf { it.isNotBlank() && r.reason !in known }?.let { return it.take(300) }
        return when (r.reason) {
            PairResult.CODE -> r.triesLeft?.let { "That code didn't match. $it ${if (it == 1) "try" else "tries"} left." }
                ?: "That code didn't match."
            PairResult.CLOSED -> "$macName isn't showing a pairing code. Choose Pair iPhone or iPad… in Sill's menu on the Mac, or tap the Mac again."
            PairResult.EXPIRED -> "The code on $macName expired. Tap the Mac to get a new one."
            PairResult.STOPPED -> "$macName stopped pairing after too many wrong codes. Try again in a few minutes."
            PairResult.BUSY -> "$macName is busy pairing another device. Try again in a moment."
            PairResult.OPEN_ON_MAC -> "Open Sill on $macName, then tap it again."
            PairResult.LOCKED -> "Unlock $macName, then tap it again."
            else -> "$macName refused to pair (${r.reason ?: "no reason"})."
        }
    }

    private val known = setOf(PairResult.CODE, PairResult.CLOSED, PairResult.EXPIRED, PairResult.STOPPED,
        PairResult.BUSY, PairResult.SHOWN, PairResult.OPEN_ON_MAC, PairResult.LOCKED)
}

/** The QR code's content: `sill://pair?v=1&m=<macID>&k=<fp>&s=<secret>&n=<name>&p=<port>&a=<address>…`. */
class PairLink(val macID: String, val fingerprint: ByteArray, val secret: ByteArray, val name: String,
               val port: Int, val addresses: List<String>) {
    companion object {
        fun parse(text: String): PairLink? {
            val t = text.trim()
            if (!t.lowercase().startsWith("sill://pair?")) return null
            val query = t.substringAfter('?')
            val q = HashMap<String, MutableList<String>>()
            for (part in query.split('&')) {
                if (part.isEmpty()) continue
                val k = java.net.URLDecoder.decode(part.substringBefore('='), "UTF-8")
                val v = java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                q.getOrPut(k) { ArrayList() }.add(v)
            }
            fun one(k: String) = q[k]?.singleOrNull()
            if (one("v") != "1") return null
            val k = one("k")?.let { B64u.decode(it) } ?: return null
            val s = one("s")?.let { B64u.decode(it) } ?: return null
            if (k.size != 32 || s.size != 16) return null
            val m = one("m") ?: return null
            if (m != MacId.make(k)) return null
            val p = one("p")?.toIntOrNull() ?: return null
            if (p !in 1..65535) return null
            return PairLink(m, k, s, one("n") ?: "Mac", p, q["a"] ?: emptyList())
        }
    }
}
