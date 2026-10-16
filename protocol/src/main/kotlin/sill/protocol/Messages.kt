package sill.protocol

/**
 * The JSON payloads, in the exact shapes Swift's Codable gives them (Sources/StreamProtocol):
 * an enum case with associated values is an object keyed by the case, unlabeled values are `_0`.
 */
sealed class Source {
    object None : Source()
    object Desktop : Source()
    data class Window(val id: Long) : Source()

    fun toJson(): Map<String, Any?> = when (this) {
        None -> mapOf("none" to emptyMap<String, Any?>())
        Desktop -> mapOf("desktop" to emptyMap<String, Any?>())
        is Window -> mapOf("window" to mapOf("_0" to id))
    }

    companion object {
        fun fromJson(m: Map<String, Any?>?): Source = when {
            m == null -> None
            m.containsKey("desktop") -> Desktop
            m.containsKey("window") -> m.obj("window")?.long("_0")?.let { Window(it) } ?: None
            else -> None
        }
    }
}

data class WindowInfo(val id: Long, val title: String, val appName: String, val bundleID: String,
                      val width: Int, val height: Int)

data class WindowList(
    val macName: String,
    val windows: List<WindowInfo>,
    val active: Source,
    val launchID: String?,
    val hostVersion: String?,
    val protocol: Int?,
    val gestures: Int?,
    val clipboard: Int?,
) {
    companion object {
        fun parse(payload: ByteArray): WindowList? {
            val m = Json.parseObject(payload) ?: return null
            val windows = m.list("windows")?.mapNotNull { e ->
                @Suppress("UNCHECKED_CAST") val w = e as? Map<String, Any?> ?: return@mapNotNull null
                WindowInfo(
                    id = w.long("id") ?: return@mapNotNull null,
                    title = w.str("title") ?: "",
                    appName = w.str("appName") ?: "",
                    bundleID = w.str("bundleID") ?: "",
                    width = w.long("width")?.toInt() ?: 0,
                    height = w.long("height")?.toInt() ?: 0,
                )
            } ?: emptyList()
            return WindowList(
                macName = m.str("macName") ?: "Mac",
                windows = windows,
                active = Source.fromJson(m.obj("active")),
                launchID = m.str("launchID"),
                hostVersion = m.str("hostVersion"),
                protocol = m.long("protocol")?.toInt(),
                gestures = m.long("gestures")?.toInt(),
                clipboard = m.long("clipboard")?.toInt(),
            )
        }
    }
}

data class AppInfo(val name: String, val bundleID: String)

data class Goodbye(val reason: String, val message: String?, val minimumVersion: String?, val reconnect: Boolean?) {
    companion object {
        const val REMOVED = "removed"
        const val REMOTE_OFF = "remoteOff"
        const val INTERNET_OFF = "internetOff"
        const val QUIT = "quit"
        const val BUSY = "busy"
        const val PAIRING_REQUIRED = "pairingRequired"
        const val UPDATE = "update"

        fun parse(payload: ByteArray): Goodbye? {
            val m = Json.parseObject(payload) ?: return null
            return Goodbye(m.str("reason") ?: return null, m.str("message"), m.str("minimumVersion"), m.bool("reconnect"))
        }
    }

    /** Words for the connect screen, and whether to dial again by itself. */
    fun describe(macName: String, device: String): Pair<String, Boolean> = when (reason) {
        QUIT -> "$macName quit Sill." to false
        REMOVED -> "$macName removed this $device. Tap it to pair again." to false
        PAIRING_REQUIRED -> "$macName now asks devices to pair. Tap it to pair this $device." to false
        REMOTE_OFF -> "Remote Access is off on $macName." to false
        INTERNET_OFF -> "$macName no longer accepts connections from the internet." to false
        BUSY -> "$macName has too many devices connected. Trying again…" to true
        else -> (message?.take(300)?.takeIf { it.isNotBlank() } ?: "$macName closed the connection ($reason).") to (reconnect == true)
    }
}

enum class PointerAction(val wire: String) { MOVE("move"), LEFT_DOWN("leftDown"), LEFT_UP("leftUp"), RIGHT_DOWN("rightDown"), RIGHT_UP("rightUp") }

enum class ScrollPhase(val wire: String) { BEGAN("began"), ENDED("ended"), MOMENTUM_BEGAN("momentumBegan"), MOMENTUM_ENDED("momentumEnded") }

/** Kind 8 payloads (Input.swift's InputEvent). Positions are fractions of the video frame. */
object Input {
    fun pointer(action: PointerAction, x: Double, y: Double): Map<String, Any?> =
        mapOf("pointer" to linkedMapOf("_0" to action.wire, "x" to x, "y" to y))

    fun scroll(x: Double, y: Double, dx: Double, dy: Double): Map<String, Any?> =
        mapOf("scroll" to linkedMapOf("x" to x, "y" to y, "dx" to dx, "dy" to dy))

    fun scrollGesture(phase: ScrollPhase, x: Double, y: Double): Map<String, Any?> =
        mapOf("scrollGesture" to linkedMapOf("_0" to phase.wire, "x" to x, "y" to y))

    /** "\n", "\t" and "\u0008" (backspace) arrive at the Mac as those keys. */
    fun text(s: String): Map<String, Any?> = mapOf("text" to mapOf("_0" to s))

    /** A key by USB HID usage, with CGEventFlags-style modifier bits ([Modifiers]). */
    fun key(hidUsage: Int, down: Boolean, modifiers: Long): Map<String, Any?> =
        mapOf("key" to linkedMapOf("hidUsage" to hidUsage, "down" to down, "modifiers" to modifiers))
}

object Modifiers {
    const val SHIFT = 1L shl 17
    const val CONTROL = 1L shl 18
    const val OPTION = 1L shl 19
    const val COMMAND = 1L shl 20
}

object Payloads {
    fun hello(appVersion: String, build: String, device: String): Map<String, Any?> =
        linkedMapOf("appVersion" to appVersion, "build" to build, "protocol" to 1, "device" to device.take(64))

    /** The stream panel's size in points (dp here); `scale` nil leaves the Mac's windows alone. */
    fun viewport(width: Double, height: Double, fps: Int, scale: Double? = null): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("width" to width, "height" to height)
        if (scale != null) m["scale"] = scale
        m["fps"] = fps
        return m
    }

    fun clientStats(fps: Int, frameAgeMs: Int, rttMs: Int, device: String, frameAgeMaxMs: Int, rttMaxMs: Int) =
        linkedMapOf<String, Any?>("fps" to fps, "frameAgeMs" to frameAgeMs, "rttMs" to rttMs, "device" to device,
            "frameAgeMaxMs" to frameAgeMaxMs, "rttMaxMs" to rttMaxMs)

    fun launchApp(bundleID: String) = mapOf("bundleID" to bundleID)

    /** close, minimize or fullScreen */
    fun windowCommand(id: Long, action: String) = linkedMapOf("id" to id, "action" to action)
}

/** Kind 3 (window ID, then JPEG) and kind 4 (bundle ID, then PNG). */
object ImageBlob {
    fun thumbnail(d: ByteArray): Pair<Long, ByteArray>? {
        if (d.size <= 4) return null
        val id = ((d[0].toLong() and 0xFF) shl 24) or ((d[1].toLong() and 0xFF) shl 16) or
            ((d[2].toLong() and 0xFF) shl 8) or (d[3].toLong() and 0xFF)
        return id to d.copyOfRange(4, d.size)
    }

    fun icon(d: ByteArray): Pair<String, ByteArray>? {
        if (d.size <= 2) return null
        val len = ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
        if (d.size <= 2 + len) return null
        return String(d, 2, len, Charsets.UTF_8) to d.copyOfRange(2 + len, d.size)
    }
}
