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
 * The session's reader thread only hands frames over ([onFrame] never waits), so a decoder that
 * falls behind never stops the phone reading (the Mac drops a device that stops reading for 4 s).
 * A feeder thread puts them into the codec. When frames pile up, the deltas are dropped and the
 * decoder waits for the next keyframe (the Mac sends one on connect, at a switch, and every 4 s).
 * The last keyframe is kept, so a decoder made later (the Surface came late) starts from it.
 */
class VideoDecoder(private val onVideoSize: (width: Int, height: Int) -> Unit) {
    private class Frame(val data: ByteArray, val keyframe: Boolean)

    private val lock = Object()
    private var surface: Surface? = null
    private var sets: ParameterSets? = null
    private var size: Hevc.Size? = null
    private var codec: MediaCodec? = null
    private var generation = 0
    private val queue = ArrayDeque<Frame>()
    private var lastKeyframe: Frame? = null
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
        lastKeyframe = null
        size = new.sps()?.let { Hevc.spsSize(it) }
        size?.let { onVideoSize(it.width, it.height) }
        release()
        configure()
    }

    /** From the reader thread; never blocks on the codec. */
    fun onFrame(payload: ByteArray, keyframe: Boolean) {
        val s = synchronized(lock) { sets } ?: return
        val data = Hevc.toAnnexB(payload, s.nalLength) ?: return
        val f = Frame(data, keyframe)
        synchronized(lock) {
            if (keyframe) {
                lastKeyframe = f
                queue.clear()          // a keyframe makes everything before it moot
            } else if (queue.size >= MAX_QUEUED) {
                droppedFrames += queue.size + 1
                queue.clear()
                needKeyframe = true
                return
            }
            if (codec == null) return  // kept as lastKeyframe for when the codec exists
            queue.addLast(f)
            lock.notifyAll()
        }
    }

    fun shutdown() = synchronized(lock) {
        surface = null
        release()
        sets = null
        lastKeyframe = null
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
                queue.clear()
                lastKeyframe?.let { queue.addLast(it) }
                generation++
                startThreads(c, generation)
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
        return all.sortedWith(compareByDescending<MediaCodecInfo> { fits(it) }.thenByDescending { it.isHardwareAccelerated })
            .map { it.name }
    }

    private fun alive(gen: Int) = synchronized(lock) { generation == gen && codec != null }

    private fun startThreads(c: MediaCodec, gen: Int) {
        // The feeder: frames from the queue into the codec, in order, waiting as long as it takes.
        Thread({
            while (true) {
                val f = synchronized(lock) {
                    while (generation == gen && codec != null && queue.isEmpty()) lock.wait(100)
                    if (generation != gen || codec == null) null else queue.removeFirst()
                } ?: break
                synchronized(lock) {
                    if (needKeyframe && !f.keyframe) return@synchronized null
                    needKeyframe = false
                    Unit
                } ?: continue
                try {
                    var index = -1
                    while (index < 0 && alive(gen)) index = c.dequeueInputBuffer(20_000)
                    if (index < 0) break
                    val buf = c.getInputBuffer(index) ?: continue
                    buf.clear()
                    if (f.data.size > buf.capacity()) {
                        c.queueInputBuffer(index, 0, 0, 0, 0)
                        synchronized(lock) { needKeyframe = true }
                        continue
                    }
                    buf.put(f.data)
                    c.queueInputBuffer(index, 0, f.data.size, System.nanoTime() / 1000,
                        if (f.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                } catch (e: Exception) {
                    if (!alive(gen)) break
                    Log.w(TAG, "decode: $e")
                    synchronized(lock) { needKeyframe = true }
                }
            }
        }, "sill-decoder-in").apply { isDaemon = true }.start()

        // The drain: each decoded frame rendered at once.
        Thread({
            val info = MediaCodec.BufferInfo()
            while (alive(gen)) {
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
                    if (!alive(gen)) break
                    Thread.sleep(5)
                }
            }
        }, "sill-decoder-out").apply { isDaemon = true }.start()
    }

    private fun cropWidth(f: MediaFormat): Int =
        if (f.containsKey("crop-right") && f.containsKey("crop-left")) f.getInteger("crop-right") - f.getInteger("crop-left") + 1
        else if (f.containsKey(MediaFormat.KEY_WIDTH)) f.getInteger(MediaFormat.KEY_WIDTH) else 0

    private fun cropHeight(f: MediaFormat): Int =
        if (f.containsKey("crop-bottom") && f.containsKey("crop-top")) f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
        else if (f.containsKey(MediaFormat.KEY_HEIGHT)) f.getInteger(MediaFormat.KEY_HEIGHT) else 0

    /** Called with [lock] held. The threads see the generation change and stop before release. */
    private fun release() {
        val c = codec ?: return
        codec = null
        generation++
        queue.clear()
        lock.notifyAll()
        needKeyframe = true
        // MediaCodec calls from the threads that are still inside one fail with
        // IllegalStateException, which they treat as the end.
        try { c.stop() } catch (_: Exception) {}
        try { c.release() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "SillDecoder"
        /** Frames waiting for the codec before the deltas are given up (about 50 ms at 60 fps). */
        private const val MAX_QUEUED = 3
    }
}
