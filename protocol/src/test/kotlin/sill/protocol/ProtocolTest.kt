package sill.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import java.util.Base64

/** Vectors from Scripts/sillclient.py's own implementation (Python hashlib/hmac) and openssl. */
class ProtocolTest {
    private val fpMac = Crypto.sha256("mac".toByteArray())
    private val fpDev = Crypto.sha256("dev".toByteArray())

    @Test fun macId() = assertEquals("6J5657TWXV835GZ8", MacId.make(fpMac))

    @Test fun pbkdf2AndProofs() {
        val k = Crypto.pbkdf2("123456789012", "sill-pair-v1".toByteArray() + fpMac, 1000, 32)
        assertEquals("91014e38a5d5edb053d929b61b4cd9e07606a5377ba6599fb769c733955cc111", k.toHex())
        assertEquals(k.toHex(), Crypto.pbkdf2ByHand("123456789012".toByteArray(), "sill-pair-v1".toByteArray() + fpMac, 1000, 32).toHex())
        assertEquals("ea0d7bc572265334fb6bd0377e88de380b1e67107e5634ca343ed6d7ba2acadf", PairingProof.deviceProof(k, fpDev, fpMac).toHex())
        assertEquals("3b286612705d2bab6fb607ccd5e6303813d8df179a08a09eafc8df91a67e4c19", PairingProof.macProof(k, fpMac, fpDev).toHex())
    }

    @Test fun recognitionTag() {
        val rk = ByteArray(32) { it.toByte() }
        val tag = RecognitionTag.make(rk, byteArrayOf(1, 2, 3, 4, 5, 6))
        assertEquals("AQIDBAUGZbeEBvBH", tag)
        assertTrue(RecognitionTag.matches(tag, rk))
        assertFalse(RecognitionTag.matches(tag, ByteArray(32)))
        assertTrue(RecognitionTag.matches(RecognitionTag.make(rk), rk))
    }

    @Test fun spkiFromOpensslCertificate() {
        val der = Base64.getDecoder().decode("MIIBbjCCAROgAwIBAgIUba+CM/KkA5x04y8fMlXQkBLO5YcwCgYIKoZIzj0EAwIwDDEKMAgGA1UEAwwBdDAeFw0yNjEwMTYxNzAwMDhaFw0yNjEwMTkxNzAwMDhaMAwxCjAIBgNVBAMMAXQwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASehyx1nnXhaHb1mbhdG1T2Y2B6qOpumapbzsYO+XMBiW5XbWLmLpATtnQFxbOFCzE+6gHLVGwcvcJUmRa89842o1MwUTAdBgNVHQ4EFgQU0yR3Mt6UKXprfR6LLb7KeNjc4HEwHwYDVR0jBBgwFoAU0yR3Mt6UKXprfR6LLb7KeNjc4HEwDwYDVR0TAQH/BAUwAwEB/zAKBggqhkjOPQQDAgNJADBGAiEAyprinbm3/dJ1AQ2m/yF3uYOCv9uIKmUqfNgMUPbyQn8CIQDe4tUSH0AuMU6KJubTRXMLqCF6FzZ20eUJVzcJxjMxcA==")
        assertEquals("f544ba3b56b912e24166c1ba08bcdfe4532e2f25912d2c7f24f4dd09b63eb57b", Spki.fingerprint(Spki.fromCertificate(der)).toHex())
    }

    @Test fun deviceIdentityRoundTrip() {
        val id = DeviceIdentity.generate()
        assertEquals(65, id.point.size)
        // The certificate parses, verifies with its own key, and carries the same SPKI.
        id.certificate.verify(id.publicKey)
        assertEquals(id.fingerprint.toHex(), Spki.fingerprint(Spki.fromCertificate(id.certificate.encoded)).toHex())
        assertEquals(id.fingerprint.toHex(), Spki.fingerprint(id.publicKey.encoded).toHex())
        val again = DeviceIdentity.load(id.privateKeyBytes(), id.publicKeyBytes())
        assertEquals(id.fingerprint.toHex(), again.fingerprint.toHex())
    }

    @Test fun damm() {
        // Make a valid code: 11 digits plus their check digit; any other last digit fails.
        val base = "12345678901"
        val check = (0..9).first { PairingCode.damm(base + it) == 0 }
        assertEquals(PairingCode.Problem.CHECK, PairingCode.check(base + ((check + 1) % 10)).second)
        assertEquals(base + check, PairingCode.check("1234-5678-901$check").first)
        assertEquals(PairingCode.Problem.LENGTH, PairingCode.check("12345").second)
    }

    @Test fun json() {
        val v = Json.parse("""{"a":[1,2.5,"x\u00e9\n"],"b":{"c":null,"d":true},"e":-3}""") as Map<*, *>
        assertEquals(listOf(1L, 2.5, "xé\n"), v["a"])
        assertEquals(-3L, v["e"])
        assertEquals("""{"pointer":{"_0":"leftDown","x":0.5,"y":0.25}}""", Json.write(Input.pointer(PointerAction.LEFT_DOWN, 0.5, 0.25)))
        assertEquals("""{"key":{"hidUsage":44,"down":true,"modifiers":1048576}}""", Json.write(Input.key(44, true, Modifiers.COMMAND)))
        assertEquals("""{"window":{"_0":42}}""", Json.write(Source.Window(42).toJson()))
        assertEquals("""{"desktop":{}}""", Json.write(Source.Desktop.toJson()))
        assertEquals("""{"text":{"_0":"\b"}}""", Json.write(Input.text("\u0008")))
    }

    @Test fun windowList() {
        val wl = WindowList.parse("""{"macName":"Mac","windows":[{"id":7,"title":"T","appName":"A","bundleID":"b","width":10,"height":20}],"active":{"window":{"_0":7}},"hostVersion":"0.5.1","gestures":1}""".toByteArray())
        assertNotNull(wl)
        assertEquals(Source.Window(7), wl.active)
        assertEquals(1, wl.windows.size)
        assertEquals(1, wl.gestures)
        assertNull(wl.clipboard)
    }

    @Test fun framing() {
        val bytes = Wire.encode(Kind.PING, byteArrayOf(1, 2, 3), timestamp = 1.5)
        assertEquals(17, bytes.size)
        val m = MessageReader(bytes.inputStream()).next()!!
        assertEquals(Kind.PING, m.kind); assertEquals(1.5, m.timestamp); assertEquals(3, m.payload.size)
        assertNull(MessageReader(ByteArray(0).inputStream()).next())
    }

    @Test fun annexB() {
        val frame = byteArrayOf(0, 0, 0, 2, 0x26, 0x01, 0, 0, 0, 1, 0x02)
        assertEquals("000000012601" + "0000000102", Hevc.toAnnexB(frame, 4)!!.toHex())
        assertNull(Hevc.toAnnexB(byteArrayOf(0, 0, 0, 9, 1), 4))
    }

    @Test fun pairLink() {
        val k = fpMac
        val s = ByteArray(16) { 7 }
        val url = "sill://pair?v=1&m=" + MacId.make(k) + "&k=" + B64u.encode(k) + "&s=" + B64u.encode(s) + "&n=My%20Mac&p=7455&a=100.1.2.3"
        val l = PairLink.parse(url)!!
        assertEquals("My Mac", l.name); assertEquals(7455, l.port); assertEquals(listOf("100.1.2.3"), l.addresses)
        assertNull(PairLink.parse(url.replace("v=1", "v=2")))
    }
}
