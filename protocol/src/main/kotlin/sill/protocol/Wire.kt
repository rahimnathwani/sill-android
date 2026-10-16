package sill.protocol

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Sill's wire format (Sources/StreamProtocol/StreamMessage.swift in Saffsanity/sill).
 *
 * Header, 14 bytes, big endian: kind (1), timestamp (8, an IEEE double, the sender's wall clock in
 * seconds since 1970), keyframe flag (1), payload length (4). Then the payload.
 */
object Kind {
    const val PARAMETER_SETS = 0
    const val FRAME = 1
    const val WINDOW_LIST = 2
    const val THUMBNAIL = 3
    const val APP_ICON = 4
    const val APP_LIST = 5
    const val SELECT_SOURCE = 6
    const val LAUNCH_APP = 7
    const val INPUT = 8
    const val VIEWPORT = 9
    const val PING = 10
    const val PONG = 11
    const val CLIENT_STATS = 12
    const val TICK = 13
    const val CURSOR_SHAPE = 14
    const val WINDOW_COMMAND = 15
    const val HOST_SETTINGS = 16
    const val CHANGE_SETTINGS = 17
    const val MAC_INFO = 18
    const val PAIR_REQUEST = 19
    const val PAIR_RESULT = 20
    const val PAIRING_WANTED = 21
    const val GOODBYE = 22
    const val HELLO = 23
    const val MAC_MENU = 24
    const val PRESS_MENU_ITEM = 25
    const val MAC_POINTER = 26
    const val FETCH_MENU = 27
    const val GESTURE = 28
    const val CLIPBOARD = 32
}

class Message(val kind: Int, val timestamp: Double, val isKeyframe: Boolean, val payload: ByteArray)

object Wire {
    const val HEADER_LENGTH = 14

    /** Caps on what a Mac may announce (StreamMessage.swift): a reader never waits for more. */
    const val MAX_FRAME_PAYLOAD = 32 shl 20
    const val MAX_OTHER_HOST_PAYLOAD = 4 shl 20

    fun now(): Double = System.currentTimeMillis() / 1000.0

    fun encode(kind: Int, payload: ByteArray = ByteArray(0), keyframe: Boolean = false,
               timestamp: Double = now()): ByteArray {
        val b = ByteBuffer.allocate(HEADER_LENGTH + payload.size)
        b.put(kind.toByte())
        b.putDouble(timestamp)
        b.put(if (keyframe) 1 else 0)
        b.putInt(payload.size)
        b.put(payload)
        return b.array()
    }

    fun encodeJson(kind: Int, value: Any?): ByteArray = encode(kind, Json.bytes(value))

    class TooLarge(kind: Int, length: Long) : IOException("kind $kind announced $length bytes")
}

/** Reads whole messages from a stream; calls [onBytes] as bytes arrive (for liveness). */
class MessageReader(input: InputStream, private val onBytes: () -> Unit = {}) {
    private val input = DataInputStream(input)
    private val header = ByteArray(Wire.HEADER_LENGTH)

    /** The next message, or null at a clean end of stream before a header. */
    fun next(): Message? {
        if (!readFullyOrEnd(header)) return null
        val b = ByteBuffer.wrap(header)
        val kind = b.get().toInt() and 0xFF
        val ts = b.getDouble()
        val key = b.get().toInt() == 1
        val len = b.getInt().toLong() and 0xFFFFFFFFL
        val cap = if (kind == Kind.FRAME) Wire.MAX_FRAME_PAYLOAD else Wire.MAX_OTHER_HOST_PAYLOAD
        if (len > cap) throw Wire.TooLarge(kind, len)
        val payload = ByteArray(len.toInt())
        // Read in pieces so a large keyframe on a slow link still counts as a sign of life.
        var off = 0
        while (off < payload.size) {
            val n = input.read(payload, off, minOf(256 * 1024, payload.size - off))
            if (n < 0) throw EOFException("stream ended inside a message")
            off += n
            onBytes()
        }
        return Message(kind, ts, key, payload)
    }

    private fun readFullyOrEnd(buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) {
                if (off == 0) return false
                throw EOFException("stream ended inside a header")
            }
            off += n
            onBytes()
        }
        return true
    }
}
