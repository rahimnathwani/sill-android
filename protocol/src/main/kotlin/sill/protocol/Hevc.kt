package sill.protocol

import java.io.ByteArrayOutputStream

/**
 * HEVC as the Mac sends it: kind 0 carries VPS/SPS/PPS (`nalLen(1) count(1)` then per set
 * `length(2) bytes`), kind 1 an access unit of length-prefixed NAL units (hvcC style). Android's
 * MediaCodec wants Annex B: start codes in place of the lengths.
 */
class ParameterSets(val nalLength: Int, val sets: List<ByteArray>) {
    /** All sets with start codes: MediaCodec's `csd-0`. */
    fun annexB(): ByteArray {
        val out = ByteArrayOutputStream()
        for (s in sets) { out.write(START_CODE); out.write(s) }
        return out.toByteArray()
    }

    fun sps(): ByteArray? = sets.firstOrNull { s -> s.isNotEmpty() && ((s[0].toInt() shr 1) and 0x3F) == 33 }

    fun sameAs(other: ParameterSets?): Boolean =
        other != null && other.nalLength == nalLength && other.sets.size == sets.size &&
            sets.indices.all { sets[it].contentEquals(other.sets[it]) }

    companion object {
        val START_CODE = byteArrayOf(0, 0, 0, 1)

        fun decode(d: ByteArray): ParameterSets? {
            if (d.size < 2) return null
            val nalLen = d[0].toInt() and 0xFF
            val count = d[1].toInt() and 0xFF
            var off = 2
            val sets = ArrayList<ByteArray>()
            repeat(count) {
                if (d.size < off + 2) return null
                val len = ((d[off].toInt() and 0xFF) shl 8) or (d[off + 1].toInt() and 0xFF)
                off += 2
                if (d.size < off + len) return null
                sets.add(d.copyOfRange(off, off + len))
                off += len
            }
            return ParameterSets(nalLen, sets)
        }
    }
}

object Hevc {
    /** Rewrites length-prefixed NAL units as Annex B, into a new array. Null if malformed. */
    fun toAnnexB(frame: ByteArray, nalLength: Int): ByteArray? {
        if (nalLength !in 1..4) return null
        val out = ByteArrayOutputStream(frame.size + 16)
        var off = 0
        while (off < frame.size) {
            if (off + nalLength > frame.size) return null
            var len = 0L
            for (i in 0 until nalLength) len = (len shl 8) or (frame[off + i].toLong() and 0xFF)
            off += nalLength
            if (len <= 0 || off + len > frame.size) return null
            out.write(ParameterSets.START_CODE)
            out.write(frame, off, len.toInt())
            off += len.toInt()
        }
        return out.toByteArray()
    }

    /** The picture's displayed size from an SPS NAL unit (with its 2-byte header), cropping included. */
    data class Size(val width: Int, val height: Int, val codedWidth: Int, val codedHeight: Int)

    fun spsSize(nal: ByteArray): Size? = try {
        val r = BitReader(unescape(nal, 2))
        r.skip(4)                                    // sps_video_parameter_set_id
        val maxSubLayersMinus1 = r.bits(3)
        r.skip(1)                                    // sps_temporal_id_nesting_flag
        profileTierLevel(r, maxSubLayersMinus1)
        r.ue()                                       // sps_seq_parameter_set_id
        val chroma = r.ue()
        var separate = false
        if (chroma == 3) separate = r.bits(1) == 1
        val w = r.ue()
        val h = r.ue()
        var left = 0; var right = 0; var top = 0; var bottom = 0
        if (r.bits(1) == 1) { left = r.ue(); right = r.ue(); top = r.ue(); bottom = r.ue() }
        val (subW, subH) = when {
            separate || chroma == 0 -> 1 to 1
            chroma == 1 -> 2 to 2
            chroma == 2 -> 2 to 1
            else -> 1 to 1
        }
        Size(w - subW * (left + right), h - subH * (top + bottom), w, h)
            .takeIf { it.width in 16..16384 && it.height in 16..16384 }
    } catch (_: IndexOutOfBoundsException) { null }

    private fun profileTierLevel(r: BitReader, maxSubLayersMinus1: Int) {
        r.skip(88)          // general profile space, tier, idc, 32 compatibility flags, 48 bits of flags
        r.skip(8)           // general_level_idc
        val profilePresent = BooleanArray(maxSubLayersMinus1)
        val levelPresent = BooleanArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) {
            profilePresent[i] = r.bits(1) == 1
            levelPresent[i] = r.bits(1) == 1
        }
        if (maxSubLayersMinus1 > 0) for (i in maxSubLayersMinus1 until 8) r.skip(2)
        for (i in 0 until maxSubLayersMinus1) {
            if (profilePresent[i]) r.skip(88)
            if (levelPresent[i]) r.skip(8)
        }
    }

    /** RBSP from a NAL unit: drops the header and every emulation-prevention 0x03 after 00 00. */
    private fun unescape(nal: ByteArray, headerBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(nal.size)
        var zeros = 0
        for (i in headerBytes until nal.size) {
            val b = nal[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) { zeros = 0; continue }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class BitReader(val d: ByteArray) {
        var pos = 0
        fun bits(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = d[pos ushr 3].toInt() and 0xFF
                v = (v shl 1) or ((byte shr (7 - (pos and 7))) and 1)
                pos++
            }
            return v
        }
        fun skip(n: Int) { if ((pos + n + 7) / 8 > d.size) throw IndexOutOfBoundsException(); pos += n }
        fun ue(): Int {
            var zeros = 0
            while (bits(1) == 0) { zeros++; if (zeros > 31) throw IndexOutOfBoundsException() }
            return ((1 shl zeros) - 1) + bits(zeros)
        }
    }
}
