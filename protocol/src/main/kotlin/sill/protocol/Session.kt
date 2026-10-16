package sill.protocol

import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Where a session goes and how it is trusted. */
data class Target(
    val host: String,
    val port: Int,
    /** The Mac's key to pin, or null for a door that lets any device in (Require pairing off). */
    val pin: ByteArray?,
    /** A plain TCP door (SillHost without --pairing): no TLS at all. */
    val plain: Boolean = false,
) {
    override fun equals(other: Any?) = other is Target && other.host == host && other.port == port &&
        other.plain == plain && (other.pin?.contentEquals(pin ?: ByteArray(0)) ?: (pin == null))
    override fun hashCode() = host.hashCode() * 31 + port
}

/** What a session tells its owner. Called on the session's reader thread unless noted. */
interface SessionListener {
    fun onConnected(session: Session) {}
    fun onWindowList(list: WindowList) {}
    fun onParameterSets(sets: ParameterSets) {}
    fun onFrame(payload: ByteArray, keyframe: Boolean, hostTimestamp: Double) {}
    fun onThumbnail(windowID: Long, jpeg: ByteArray) {}
    fun onAppIcon(bundleID: String, png: ByteArray) {}
    fun onAppList(apps: List<AppInfo>) {}
    fun onHostSettings(state: Map<String, Any?>) {}
    /** Once, at the end. [goodbye] when the Mac said why; [error] for anything else. */
    fun onEnded(goodbye: Goodbye?, error: Throwable?) {}
}

/**
 * One session with a Mac (ALPN sill/1, or a plain door): the hello first, then the viewport; the
 * Mac sends its window list and starts streaming once something is selected. Liveness is the
 * device's: a ping every 0.25 s, and no byte for max(6 s, 4 × the worst round trip) ends it.
 */
class Session(
    val target: Target,
    private val identity: DeviceIdentity,
    private val deviceName: String,
    private val appVersion: String,
    private val build: String,
    private val listener: SessionListener,
) {
    @Volatile var windowList: WindowList? = null
        private set
    @Volatile var rttMs: Int = -1
        private set
    /** The Mac's key this session saw (the pin, or the key an open door presented). */
    @Volatile var macFingerprint: ByteArray? = null
        private set

    private var socket: Socket? = null
    private var out: OutputStream? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "sill-writer").apply { isDaemon = true } }
    private val timers: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sill-timers").apply { isDaemon = true } }
    private val ended = AtomicBoolean(false)
    @Volatile private var lastReceived = System.nanoTime()
    @Volatile private var goodbye: Goodbye? = null

    // Once-a-second measurements, for the Mac's log and its menu (kind 12).
    private val statsLock = Any()
    private var framesThisSecond = 0
    private val frameAges = ArrayList<Int>()
    private val rtts = ArrayList<Int>()
    private var worstRtt = 0
    @Volatile var lastFps = 0
        private set

    // The Desktop when nothing streams (StreamClient's rule): at once, or 2 s after a watched window
    // closed unless the person picked something meanwhile; at most every 10 s.
    @Volatile private var lastAutoDesktop = 0L
    @Volatile private var lastPick = 0L
    @Volatile var autoDesktop = true

    /** Dials and handshakes; blocks. Throws on failure (Tls.PinRefused among them). */
    fun connect() {
        val conn = if (target.plain) Tls.connectPlain(target.host, target.port)
                   else Tls.connect(target.host, target.port, identity, Tls.SESSION_ALPN, target.pin)
        macFingerprint = conn.macFingerprint
        socket = conn.socket
        out = conn.socket.getOutputStream()
        // The hello is the first message of every session connection.
        sendJson(Kind.HELLO, Payloads.hello(appVersion, build, deviceName))
    }

    /** Starts the reader and the timers; call after [connect], with the first viewport. */
    fun start(viewport: Map<String, Any?>) {
        sendJson(Kind.VIEWPORT, viewport)
        lastReceived = System.nanoTime()
        Thread({ readLoop() }, "sill-reader").apply { isDaemon = true }.start()
        timers.scheduleAtFixedRate({ ping() }, 0, 250, TimeUnit.MILLISECONDS)
        timers.scheduleAtFixedRate({ stats() }, 1, 1, TimeUnit.SECONDS)
        listener.onConnected(this)
    }

    // --- Sending ---

    fun send(kind: Int, payload: ByteArray = ByteArray(0)) {
        val bytes = Wire.encode(kind, payload)
        try {
            writer.execute {
                try {
                    out?.write(bytes)
                    out?.flush()
                } catch (e: IOException) {
                    end(null, e)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Ended.
        }
    }

    fun sendJson(kind: Int, value: Any?) = send(kind, Json.bytes(value))

    fun select(source: Source) {
        if (source != Source.None) lastPick = System.currentTimeMillis()
        sendJson(Kind.SELECT_SOURCE, source.toJson())
    }

    fun input(event: Map<String, Any?>) = sendJson(Kind.INPUT, event)

    fun viewport(v: Map<String, Any?>) = sendJson(Kind.VIEWPORT, v)

    fun launchApp(bundleID: String) {
        lastPick = System.currentTimeMillis()
        sendJson(Kind.LAUNCH_APP, Payloads.launchApp(bundleID))
    }

    fun windowCommand(id: Long, action: String) = sendJson(Kind.WINDOW_COMMAND, Payloads.windowCommand(id, action))

    fun close() = end(null, null)

    val isEnded get() = ended.get()

    // --- Receiving ---

    private fun readLoop() {
        try {
            val s = socket ?: return
            val reader = MessageReader(s.getInputStream()) { lastReceived = System.nanoTime() }
            while (!ended.get()) {
                val m = reader.next() ?: break
                handle(m)
            }
            end(goodbye, if (goodbye == null && !ended.get()) IOException("The Mac closed the connection.") else null)
        } catch (e: Exception) {
            end(goodbye, if (goodbye != null) null else e)
        }
    }

    private fun handle(m: Message) {
        when (m.kind) {
            Kind.FRAME -> {
                synchronized(statsLock) {
                    framesThisSecond++
                    val age = ((Wire.now() - m.timestamp) * 1000).toInt()
                    if (age in 0..60_000) frameAges.add(age)
                }
                listener.onFrame(m.payload, m.isKeyframe, m.timestamp)
            }
            Kind.PARAMETER_SETS -> ParameterSets.decode(m.payload)?.let { listener.onParameterSets(it) }
            Kind.WINDOW_LIST -> WindowList.parse(m.payload)?.let { onWindowList(it) }
            Kind.THUMBNAIL -> ImageBlob.thumbnail(m.payload)?.let { listener.onThumbnail(it.first, it.second) }
            Kind.APP_ICON -> ImageBlob.icon(m.payload)?.let { listener.onAppIcon(it.first, it.second) }
            Kind.APP_LIST -> (Json.parse(m.payload) as? List<*>)?.let { l ->
                listener.onAppList(l.mapNotNull { e ->
                    @Suppress("UNCHECKED_CAST") val a = e as? Map<String, Any?> ?: return@mapNotNull null
                    AppInfo(a.str("name") ?: return@mapNotNull null, a.str("bundleID") ?: return@mapNotNull null)
                })
            }
            Kind.PONG -> if (m.payload.size == 8) {
                val sent = ByteBuffer.wrap(m.payload).double
                val rtt = ((System.nanoTime() / 1e9 - sent) * 1000).toInt()
                if (rtt in 0..60_000) synchronized(statsLock) { rtts.add(rtt); rttMs = rtt }
            }
            Kind.HOST_SETTINGS -> Json.parseObject(m.payload)?.let { listener.onHostSettings(it) }
            Kind.GOODBYE -> goodbye = Goodbye.parse(m.payload) ?: Goodbye("unknown", null, null, false)
            else -> {} // tick, cursor shape, Mac info, menus, pointer and kinds this build does not know
        }
    }

    private fun onWindowList(list: WindowList) {
        val previous = windowList
        windowList = list
        listener.onWindowList(list)
        if (!autoDesktop || list.active != Source.None) return
        val now = System.currentTimeMillis()
        if (now - lastAutoDesktop < 10_000) return
        if (previous?.active is Source.Window) {
            // A watched window closed: give the person 2 s to pick something else.
            val pickBefore = lastPick
            timers.schedule({
                if (windowList?.active == Source.None && lastPick == pickBefore && !ended.get()) {
                    lastAutoDesktop = System.currentTimeMillis()
                    sendJson(Kind.SELECT_SOURCE, Source.Desktop.toJson())
                }
            }, 2, TimeUnit.SECONDS)
        } else {
            lastAutoDesktop = now
            sendJson(Kind.SELECT_SOURCE, Source.Desktop.toJson())
        }
    }

    // --- Timers ---

    private fun ping() {
        if (ended.get()) return
        val silentFor = (System.nanoTime() - lastReceived) / 1_000_000
        val limit = maxOf(6000L, 4L * worstRtt)
        if (silentFor > limit) {
            end(null, IOException("The Mac stopped answering."))
            return
        }
        val b = ByteBuffer.allocate(8).putDouble(System.nanoTime() / 1e9).array()
        send(Kind.PING, b)
    }

    private fun stats() {
        if (ended.get()) return
        val fps: Int; val age: Int; val ageMax: Int; val rtt: Int; val rttMax: Int
        synchronized(statsLock) {
            fps = framesThisSecond
            framesThisSecond = 0
            age = median(frameAges); ageMax = frameAges.maxOrNull() ?: -1
            rtt = median(rtts); rttMax = rtts.maxOrNull() ?: -1
            if (rttMax > 0) worstRtt = rttMax
            frameAges.clear(); rtts.clear()
        }
        lastFps = fps
        sendJson(Kind.CLIENT_STATS, Payloads.clientStats(fps, age, rtt, deviceName, ageMax, rttMax))
    }

    private fun median(l: List<Int>): Int = if (l.isEmpty()) -1 else l.sorted()[l.size / 2]

    private fun end(bye: Goodbye?, error: Throwable?) {
        if (!ended.compareAndSet(false, true)) return
        timers.shutdownNow()
        writer.shutdown()
        try { socket?.close() } catch (_: Exception) {}
        listener.onEnded(bye ?: goodbye, if ((bye ?: goodbye) != null) null else error)
    }
}
