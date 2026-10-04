package com.opus.music.cast.raop

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decodes a remote audio URL (the Subsonic token-auth stream URL) to
 * 44.1 kHz stereo 16-bit PCM using MediaExtractor + MediaCodec, and emits
 * fixed 352-sample frames for [RaopConnection].
 *
 * Runs its own decode thread; frames are pulled via [takeFrame] with a
 * timeout so the sender can shut down promptly. Call [release] when done.
 */
class RaopPcmPipeline(private val url: String) {

    data class Frame(val left: ShortArray, val right: ShortArray, val count: Int)

    private val queue = LinkedBlockingQueue<Frame>(64)
    private val running = AtomicBoolean(false)
    private var decodeThread: Thread? = null

    @Volatile var error: Exception? = null
        private set

    /**
     * Pending seek target (microseconds), consumed by the decode thread.
     * Set via [seekTo]; -1 = none. Survives [release] so a seek requested
     * while a RAOP session is paused lands when the pipeline restarts
     * (RAOP resume restarts the decode from the top of the loop).
     */
    @Volatile private var seekRequestUs: Long = -1L

    /**
     * Jump the decode position to [positionMs]. The decode thread seeks
     * the extractor, flushes the codec and drops every queued frame from
     * the old position, so the sender (AP2 session / RAOP sender) starts
     * pulling new-position audio on its next take — the RTP timeline
     * itself never breaks, only the content jumps. No-op once released
     * and never started.
     */
    fun seekTo(positionMs: Long) {
        seekRequestUs = positionMs.coerceAtLeast(0L) * 1000L
    }

    /** Sentinel: end of stream reached. */
    private val EOS = Frame(ShortArray(0), ShortArray(0), -1)

    fun start() {
        if (running.getAndSet(true)) return
        decodeThread = Thread({ decodeLoop() }, "raop-decode").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Take the next frame, waiting up to [timeoutMs]. Returns null on
     * timeout (no data yet) or the EOS sentinel frame (count == -1).
     */
    fun takeFrame(timeoutMs: Long = 250): Frame? =
        queue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

    fun isEos(f: Frame): Boolean = f.count == -1

    fun release() {
        running.set(false)
        try {
            decodeThread?.join(2500)
        } catch (_: InterruptedException) {
        }
        decodeThread = null
        queue.clear()
    }

    // ------------------------------------------------------------------

    private fun decodeLoop() {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(url)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) throw IllegalStateException("no audio track in $url")
            extractor.selectTrack(track)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = try {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } catch (_: Exception) {
                2 // ENCODING_PCM_16BIT
            }
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val resampler = LinearResampler(srcRate)
            val outL = ArrayList<Float>(8192)
            val outR = ArrayList<Float>(8192)
            val info = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false

            while (running.get() && !outputEos) {
                val sk = seekRequestUs
                if (sk >= 0L) {
                    seekRequestUs = -1L
                    try {
                        extractor.seekTo(sk, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        codec.flush()
                        resampler.reset(srcRate)
                        outL.clear()
                        outR.clear()
                        queue.clear()
                        inputEos = false
                    } catch (_: Exception) {
                        // A failed seek leaves normal decode running.
                    }
                }
                if (!inputEos) {
                    val inIdx = codec.dequeueInputBuffer(5_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(inBuf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 5_000)
                when {
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        if (info.size > 0) {
                            drainChunk(outBuf, info, channels, pcmEncoding, resampler, outL, outR)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding = try {
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } catch (_: Exception) {
                            2
                        }
                        resampler.reset(srcRate)
                    }
                }
                emitFrames(outL, outR, partial = false)
            }
            // Flush any leftover resampled audio as a final short frame.
            resampler.flush(outL, outR)
            emitFrames(outL, outR, partial = true)
            queue.put(EOS)
        } catch (e: Exception) {
            if (running.get()) error = e
            try {
                queue.put(EOS)
            } catch (_: Exception) {
            }
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                extractor?.release()
            } catch (_: Exception) {
            }
            running.set(false)
        }
    }

    private fun drainChunk(
        buf: java.nio.ByteBuffer,
        info: MediaCodec.BufferInfo,
        channels: Int,
        pcmEncoding: Int,
        resampler: LinearResampler,
        outL: ArrayList<Float>,
        outR: ArrayList<Float>,
    ) {
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        val bb = buf.order(ByteOrder.nativeOrder())
        val frames = when (pcmEncoding) {
            4 -> info.size / 4 / channels // ENCODING_PCM_FLOAT
            3 -> info.size / channels // ENCODING_PCM_8BIT
            else -> info.size / 2 / channels // ENCODING_PCM_16BIT
        }
        if (frames <= 0) return
        val l = FloatArray(frames)
        val r = FloatArray(frames)
        for (i in 0 until frames) {
            var s0 = 0f
            var s1 = 0f
            for (c in 0 until channels) {
                val v: Float = when (pcmEncoding) {
                    4 -> bb.float.coerceIn(-1f, 1f)
                    3 -> ((bb.get().toInt() and 0xFF) - 128) / 128f
                    else -> bb.short / 32768f
                }
                if (c == 0) s0 = v else if (c == 1) s1 = v
            }
            // mono -> dual mono; >2ch -> first two channels
            l[i] = s0
            r[i] = if (channels >= 2) s1 else s0
        }
        resampler.process(l, r, frames, outL, outR)
    }

    private fun emitFrames(outL: ArrayList<Float>, outR: ArrayList<Float>, partial: Boolean) {
        while (outL.size >= AlacEncoder.FRAME_SAMPLES ||
            (partial && outL.isNotEmpty())
        ) {
            val n = minOf(outL.size, AlacEncoder.FRAME_SAMPLES)
            val left = ShortArray(n)
            val right = ShortArray(n)
            for (i in 0 until n) {
                left[i] = (outL[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                right[i] = (outR[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
            outL.subList(0, n).clear()
            outR.subList(0, n).clear()
            // Pad a short final frame — the encoder flags short frames,
            // but fixed-size arrays keep the sender loop simple.
            val fl = if (n < AlacEncoder.FRAME_SAMPLES) left.copyOf(AlacEncoder.FRAME_SAMPLES) else left
            val fr = if (n < AlacEncoder.FRAME_SAMPLES) right.copyOf(AlacEncoder.FRAME_SAMPLES) else right
            try {
                queue.put(Frame(fl, fr, n))
            } catch (_: InterruptedException) {
                return
            }
            if (partial) break
        }
    }

    /**
     * Chunk-wise linear resampler to 44.1 kHz with one-sample carry
     * between chunks so there are no clicks at chunk boundaries.
     * Internal (not private) so the JVM unit tests can cover it.
     */
    internal class LinearResampler(srcRate: Int) {
        private var step = srcRate / AlacEncoder.SAMPLE_RATE.toDouble()
        private var pos = 0.0
        private var lastL = 0f
        private var lastR = 0f

        fun reset(srcRate: Int) {
            step = srcRate / AlacEncoder.SAMPLE_RATE.toDouble()
            pos = 0.0
        }

        fun process(l: FloatArray, r: FloatArray, frames: Int, outL: ArrayList<Float>, outR: ArrayList<Float>) {
            var p = pos
            // Extended indexing: sample -1 is lastL/lastR (carry from the
            // previous chunk), samples 0..frames-1 are this chunk. Each
            // output needs samples i and i+1.
            while (true) {
                val i = kotlin.math.floor(p).toInt()
                if (i + 1 > frames - 1) break
                val f = (p - i).toFloat()
                val a0 = if (i < 0) lastL else l[i]
                val a1 = l[i + 1]
                val b0 = if (i < 0) lastR else r[i]
                val b1 = r[i + 1]
                outL.add(a0 + (a1 - a0) * f)
                outR.add(b0 + (b1 - b0) * f)
                p += step
            }
            // Carry: absolute position minus consumed frames (in (-1, 0]).
            pos = p - frames
            if (frames > 0) {
                lastL = l[frames - 1]
                lastR = r[frames - 1]
            }
        }

        /** Emit remaining tail (at most a couple of samples). */
        fun flush(outL: ArrayList<Float>, outR: ArrayList<Float>) {
            // Nothing buffered internally; the caller drains outL/outR.
        }
    }
}
