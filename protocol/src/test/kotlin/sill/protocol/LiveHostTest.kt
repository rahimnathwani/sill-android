package sill.protocol

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Against a real SillHost on this Mac, the way the app does it: ask, the code, a pinned session,
 * the Desktop picked by itself, video, input. Skipped unless SILL_LIVE_HOST and SILL_LIVE_PORT are
 * set. Run a TEST host only (it never posts input and never reads this Mac's pointer):
 *
 *   SILL_TEST_LOOPBACK=1 SILL_TEST_SOFTWARE_ENCODER=1 SILL_TEST_NO_ROUTER=1 \
 *   SILL_TEST_REMOTE_DIR=$T/host SILL_TEST_ASK_FROM_THIS_MAC=1 \
 *   .build/release/SillHost --synthetic --pairing > $T/host.log
 *
 * SILL_LIVE_CODE_FILE is that log: the host prints its code there after each ask.
 */
class LiveHostTest {
    private val host = System.getenv("SILL_LIVE_HOST")
    private val port = System.getenv("SILL_LIVE_PORT")?.toIntOrNull()

    @Test fun pairAndStream() {
        if (host == null || port == null) { println("SKIPPED: no SILL_LIVE_HOST/SILL_LIVE_PORT"); return }
        val identity = DeviceIdentity.generate()
        val name = "Android test"

        val logFile = File(System.getenv("SILL_LIVE_CODE_FILE"))
        val before = codes(logFile).size
        val asked = Pairing.ask(host, port, identity, name, "JVM")
        println("ask: ${asked.result.raw}")
        assertEquals(PairResult.SHOWN, asked.result.reason)

        val code = readCode(logFile, before)
        assertNotNull(code, "no code in the host's log")
        val t0 = System.nanoTime()
        val (paired, result) = Pairing.pairWithCode(host, port, identity, name, "JVM", code, asked.macFingerprint)
        println("pair: ${result.raw} in ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertNotNull(paired)
        assertEquals(32, paired.recognitionKey?.size)

        // A wrong pin is refused before anything is sent.
        try {
            Session(Target(host, port, ByteArray(32)), identity, name, "0.1", "1", object : SessionListener {}).connect()
            error("connected with a wrong pin")
        } catch (_: Tls.PinRefused) { println("wrong pin refused") }

        val lists = AtomicInteger(); val frames = AtomicInteger(); val keyframes = AtomicInteger()
        val sets = AtomicReference<ParameterSets>()
        val gotFrames = CountDownLatch(30)
        val ended = CountDownLatch(1)
        val endInfo = AtomicReference<String>()
        val session = Session(Target(host, port, paired.fingerprint), identity, name, "0.1", "1", object : SessionListener {
            override fun onWindowList(list: WindowList) { lists.incrementAndGet(); println("window list: ${list.macName} ${list.windows.size} windows, active ${list.active}, host ${list.hostVersion}") }
            override fun onParameterSets(sets0: ParameterSets) { sets.set(sets0) }
            override fun onFrame(payload: ByteArray, keyframe: Boolean, hostTimestamp: Double) {
                frames.incrementAndGet(); if (keyframe) keyframes.incrementAndGet()
                val s = sets.get()
                assertNotNull(s, "a frame before parameter sets")
                assertNotNull(Hevc.toAnnexB(payload, s.nalLength), "frame did not convert")
                gotFrames.countDown()
            }
            override fun onEnded(goodbye: Goodbye?, error: Throwable?) { endInfo.set("$goodbye $error"); ended.countDown() }
        })
        session.connect()
        session.start(Payloads.viewport(800.0, 500.0, 60))
        assertTrue(gotFrames.await(20, TimeUnit.SECONDS), "frames: ${frames.get()}, end: ${endInfo.get()}")
        val s = sets.get()
        val size = Hevc.spsSize(s.sps()!!)
        println("parameter sets: ${s.sets.size}, nal length ${s.nalLength}, size $size; frames ${frames.get()} (${keyframes.get()} key); rtt ${session.rttMs} ms")
        assertNotNull(size)
        assertTrue(keyframes.get() >= 1)

        // Input, as a tap and a scroll; the test host counts it as in.dry.
        session.input(Input.pointer(PointerAction.MOVE, 0.5, 0.5))
        session.input(Input.pointer(PointerAction.LEFT_DOWN, 0.5, 0.5))
        session.input(Input.pointer(PointerAction.LEFT_UP, 0.5, 0.5))
        session.input(Input.scrollGesture(ScrollPhase.BEGAN, 0.5, 0.5))
        session.input(Input.scroll(0.5, 0.5, 0.0, 0.05))
        session.input(Input.scrollGesture(ScrollPhase.ENDED, 0.5, 0.5))
        session.input(Input.text("hi"))
        Thread.sleep(2500)
        assertTrue(lists.get() >= 1)
        assertTrue(session.rttMs >= 0, "no pong")
        session.close()
        assertTrue(ended.await(3, TimeUnit.SECONDS))

        // A second session with the same key needs no pairing.
        val again = CountDownLatch(1)
        val s2 = Session(Target(host, port, paired.fingerprint), identity, name, "0.1", "1", object : SessionListener {
            override fun onWindowList(list: WindowList) { again.countDown() }
        })
        s2.connect(); s2.start(Payloads.viewport(400.0, 300.0, 60))
        assertTrue(again.await(5, TimeUnit.SECONDS))
        s2.close()
        // The recognition key reads a tag the host would advertise.
        assertTrue(RecognitionTag.matches(RecognitionTag.make(paired.recognitionKey!!), paired.recognitionKey!!))
    }

    private fun codes(f: File) =
        Regex("Pair with (\\d{4} \\d{4} \\d{4})").findAll(f.readText()).map { it.groupValues[1].replace(" ", "") }.toList()

    /** The code printed after the ask; the last one printed if none new comes within 3 s. */
    private fun readCode(f: File, before: Int): String? {
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            val all = codes(f)
            if (all.size > before) return all.last()
            Thread.sleep(100)
        }
        return codes(f).lastOrNull()
    }
}

/** A device whose key the Mac does not trust, dialing the right Mac pinned: refused, not lost. */
class LiveRefusedTest {
    @Test fun unpairedKeyIsRefused() {
        val host = System.getenv("SILL_LIVE_HOST") ?: run { println("SKIPPED"); return }
        val port = System.getenv("SILL_LIVE_PORT")!!.toInt()
        val stranger = DeviceIdentity.generate()
        val macFp = Tls.fingerprintOf(host, port, stranger)!!
        val ended = CountDownLatch(1)
        val err = AtomicReference<Throwable?>()
        val s = Session(Target(host, port, macFp), stranger, "stranger", "0.1", "1", object : SessionListener {
            override fun onEnded(goodbye: Goodbye?, error: Throwable?) { err.set(error); ended.countDown() }
        })
        try {
            s.connect()
            s.start(Payloads.viewport(400.0, 300.0, 60))
            assertTrue(ended.await(10, TimeUnit.SECONDS), "did not end")
        } catch (e: Exception) {
            err.set(e)   // a handshake that fails outright is refused just as well
        }
        println("ended with: ${err.get()}")
        assertTrue(err.get() is Session.Refused || err.get() is javax.net.ssl.SSLException, "${err.get()}")
    }
}
