package sill.android

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import sill.protocol.Hevc
import sill.protocol.ParameterSets
import java.nio.ByteBuffer

/**
 * Decodes the Mac's HEVC stream onto a Surface with MediaCodec, as fast as it arrives: no
 * B-frames, no reordering, each decoded frame rendered at once (latency beats smoothness).
 *
 * Frames come from the session's reader thread; the Surface from the main thread. A new decoder
 * waits for a keyframe (the Mac sends one on connect, at a switch, and every 4 s).
 */
class VideoDecoder(private val onVideoSize: (width: Int, height: Int) -> Unit) {
    private val lock = Object()
    private var surface: Surface? = null
    private var sets: ParameterSets? = null
    private var size: Hevc.Size? = null
    private var codec: MediaCodec? = null
    private var drain: Thread? = null
    @Volatile private var draining = false
    private var needKeyframe = true
    @Volatile var decoderName: String? = null
        private set
    @Volatile var droppedFrames = 0
        private set

    fun setSurface(s: Surface?) = synchronized(lock) {
        if (s === surface) return
        surface = s
        release()
        if (s != null) configure()
    }

    fun onParameterSets(new: ParameterSets) = synchronized(lock) {
        if (new.sameAs(sets)) return
        sets = new
        size = new.sps()?.let { Hevc.spsSize(it) }
        size?.let { onVideoSize(it.width, it.height) }
        release()
        configure()
    }

    fun onFrame(payload: ByteArray, keyframe: Boolean) = synchronized(lock) {
        val c = codec ?: return
        val s = sets ?: return
        if (needKeyframe && !keyframe) return
        val data = Hevc.toAnnexB(payload, s.nalLength) ?: return
        try {
            var index = c.dequeueInputBuffer(20_000)
            if (index < 0) index = c.dequeueInputBuffer(80_000)
            if (index < 0) {
                // The decoder is behind; this delta is lost, so wait for the next keyframe rather
                // than show a smeared picture.
                droppedFrames++
                needKeyframe = true
                return
            }
            val buf: ByteBuffer = c.getInputBuffer(index) ?: return
            buf.clear()
            if (data.size > buf.capacity()) { c.queueInputBuffer(index, 0, 0, 0, 0); needKeyframe = true; return }
            buf.put(data)
            c.queueInputBuffer(index, 0, data.size, System.nanoTime() / 1000, if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            needKeyframe = false
        } catch (e: Exception) {
            Log.w(TAG, "decode: $e")
            release()
            configure()
        }
    }

    fun shutdown() = synchronized(lock) {
        surface = null
        release()
        sets = null
    }

    private fun configure() {
        val s = surface ?: return
        val p = sets ?: return
        val w = size?.codedWidth ?: 1920
        val h = size?.codedHeight ?: 1080
        for (name in candidates(w, h)) {
            try {
                val c = MediaCodec.createByCodecName(name)
                val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h)
                f.setByteBuffer("csd-0", ByteBuffer.wrap(p.annexB()))
                f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(w * h, 2 * 1024 * 1024))
                f.setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                // Vendor switches for the same thing on older Qualcomm and Exynos decoders.
                f.setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
                f.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
                c.configure(f, s, null, 0)
                c.start()
                codec = c
                decoderName = name
                needKeyframe = true
                startDrain(c)
                Log.i(TAG, "decoder $name for ${w}x$h")
                return
            } catch (e: Exception) {
                Log.w(TAG, "decoder $name refused ${w}x$h: $e")
            }
        }
        Log.e(TAG, "no HEVC decoder takes ${w}x$h")
    }

    /** HEVC decoders, hardware first, those that say they take the size before the rest. */
    private fun candidates(w: Int, h: Int): List<String> {
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) }
        }
        fun fits(info: MediaCodecInfo) = try {
            info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).videoCapabilities.isSizeSupported(w, h)
        } catch (_: Exception) { false }
        fun hardware(info: MediaCodecInfo) = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else !info.name.startsWith("c2.android") && !info.name.startsWith("OMX.google")
        return all.sortedWith(compareByDescending<MediaCodecInfo> { fits(it) }.thenByDescending { hardware(it) }).map { it.name }
    }

    private fun startDrain(c: MediaCodec) {
        draining = true
        val t = Thread({
            val info = MediaCodec.BufferInfo()
            while (draining) {
                try {
                    val i = c.dequeueOutputBuffer(info, 10_000)
                    when {
                        i >= 0 -> c.releaseOutputBuffer(i, true)
                        i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = c.outputFormat
                            val cw = cropWidth(f); val ch = cropHeight(f)
                            if (cw > 0 && ch > 0) onVideoSize(cw, ch)
                        }
                    }
                } catch (_: Exception) {
                    if (!draining) break
                    Thread.sleep(5)
                }
            }
        }, "sill-decoder-out")
        t.isDaemon = true
        drain = t
        t.start()
    }

    private fun cropWidth(f: MediaFormat): Int =
        if (f.containsKey("crop-right") && f.containsKey("crop-left")) f.getInteger("crop-right") - f.getInteger("crop-left") + 1
        else if (f.containsKey(MediaFormat.KEY_WIDTH)) f.getInteger(MediaFormat.KEY_WIDTH) else 0

    private fun cropHeight(f: MediaFormat): Int =
        if (f.containsKey("crop-bottom") && f.containsKey("crop-top")) f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
        else if (f.containsKey(MediaFormat.KEY_HEIGHT)) f.getInteger(MediaFormat.KEY_HEIGHT) else 0

    private fun release() {
        val c = codec ?: return
        codec = null
        draining = false
        drain?.join(200)
        drain = null
        try { c.stop() } catch (_: Exception) {}
        try { c.release() } catch (_: Exception) {}
        needKeyframe = true
    }

    companion object { private const val TAG = "SillDecoder" }
}
